#include "astro_types.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace astro {

namespace {

inline int cfaColorAt(int x, int y, CfaPattern cfa) {
    // Returns 0 = R, 1 = G on R-row, 2 = G on B-row, 3 = B
    const int p = ((y & 1) << 1) | (x & 1);
    switch (cfa) {
        case CfaPattern::RGGB: {
            static constexpr int kMap[4] = {0, 1, 2, 3};
            return kMap[p];
        }
        case CfaPattern::GRBG: {
            static constexpr int kMap[4] = {1, 0, 3, 2};
            return kMap[p];
        }
        case CfaPattern::GBRG: {
            static constexpr int kMap[4] = {2, 3, 0, 1};
            return kMap[p];
        }
        case CfaPattern::BGGR:
        default: {
            static constexpr int kMap[4] = {3, 2, 1, 0};
            return kMap[p];
        }
    }
}

inline float sampleClamp(const float* bayer, int w, int h, int x, int y) {
    const int cx = std::clamp(x, 0, w - 1);
    const int cy = std::clamp(y, 0, h - 1);
    return bayer[static_cast<size_t>(cy) * w + cx];
}

inline float srgbByteToLinear(uint8_t v) {
    const float s = static_cast<float>(v) * (1.0f / 255.0f);
    return (s <= 0.04045f) ? (s * (1.0f / 12.92f)) : std::pow((s + 0.055f) * (1.0f / 1.055f), 2.4f);
}

inline void applyWbAndCcm(float& r, float& g, float& b, const RawParams& p) {
    r *= p.wbGains[0];
    g *= p.wbGains[1];
    b *= p.wbGains[2];
    if (p.hasCcm) {
        const float nr = p.ccm[0] * r + p.ccm[1] * g + p.ccm[2] * b;
        const float ng = p.ccm[3] * r + p.ccm[4] * g + p.ccm[5] * b;
        const float nb = p.ccm[6] * r + p.ccm[7] * g + p.ccm[8] * b;
        r = nr;
        g = ng;
        b = nb;
    }
    r = std::max(0.0f, r);
    g = std::max(0.0f, g);
    b = std::max(0.0f, b);
}

} // namespace

void calibrateRawToFloat(
    const uint8_t* rawBytes,
    const RawParams& params,
    const CalibrationMaster* bias,
    const CalibrationMaster* dark,
    const CalibrationMaster* flat,
    std::vector<float>& outBayer
) {
    const int w = params.width;
    const int h = params.height;
    if (w <= 0 || h <= 0 || rawBytes == nullptr) {
        outBayer.clear();
        return;
    }
    const int stride = params.rowStrideBytes > 0 ? params.rowStrideBytes : (w * 2);
    const size_t total = static_cast<size_t>(w) * static_cast<size_t>(h);
    outBayer.resize(total);

    float invRange[4];
    for (int i = 0; i < 4; ++i) {
        const float denom = std::max(1.0f, params.whiteLevel - params.blackLevel[i]);
        invRange[i] = 1.0f / denom;
    }

    const bool useDark = dark != nullptr && dark->valid() &&
                         dark->channels == 1 && dark->width == w && dark->height == h;
    const bool useBias = !useDark && bias != nullptr && bias->valid() &&
                         bias->channels == 1 && bias->width == w && bias->height == h;
    const bool useFlat = flat != nullptr && flat->valid() &&
                         flat->channels == 1 && flat->width == w && flat->height == h;

    const float* darkPtr = useDark ? dark->data.data() : nullptr;
    const float* biasPtr = useBias ? bias->data.data() : nullptr;
    const float* flatPtr = useFlat ? flat->data.data() : nullptr;

    for (int y = 0; y < h; ++y) {
        const uint16_t* row = reinterpret_cast<const uint16_t*>(rawBytes + static_cast<size_t>(y) * stride);
        float* dst = outBayer.data() + static_cast<size_t>(y) * w;
        const int yBit = (y & 1) << 1;

        for (int x = 0; x < w; ++x) {
            const int blIdx = yBit | (x & 1);
            float v = (static_cast<float>(row[x]) - params.blackLevel[blIdx]) * invRange[blIdx];
            const size_t idx = static_cast<size_t>(y) * w + x;
            if (darkPtr) {
                v -= darkPtr[idx];
            } else if (biasPtr) {
                v -= biasPtr[idx];
            }
            if (flatPtr) {
                const float f = std::max(flatPtr[idx], 0.05f);
                v /= f;
            }
            dst[x] = std::max(v, -0.05f);
        }
    }
}

void demosaicBayerToFloatRGB(
    const float* bayer,
    const RawParams& params,
    FloatImageRGB& out
) {
    const int w = params.width;
    const int h = params.height;
    if (w < 4 || h < 4 || bayer == nullptr) {
        out.clear();
        return;
    }

    if (params.bin2x2) {
        // 2x2 super-pixel debayering: zero interpolation ringing, sqrt(2) SNR boost, 4x lower RAM
        const int ow = w / 2;
        const int oh = h / 2;
        out.resize(ow, oh);

        int rDx = 0, rDy = 0, g1Dx = 1, g1Dy = 0, g2Dx = 0, g2Dy = 1, bDx = 1, bDy = 1;
        switch (params.cfa) {
            case CfaPattern::RGGB:
                rDx = 0; rDy = 0; g1Dx = 1; g1Dy = 0; g2Dx = 0; g2Dy = 1; bDx = 1; bDy = 1;
                break;
            case CfaPattern::GRBG:
                g1Dx = 0; g1Dy = 0; rDx = 1; rDy = 0; bDx = 0; bDy = 1; g2Dx = 1; g2Dy = 1;
                break;
            case CfaPattern::GBRG:
                g1Dx = 0; g1Dy = 0; bDx = 1; bDy = 0; rDx = 0; rDy = 1; g2Dx = 1; g2Dy = 1;
                break;
            case CfaPattern::BGGR:
            default:
                bDx = 0; bDy = 0; g1Dx = 1; g1Dy = 0; g2Dx = 0; g2Dy = 1; rDx = 1; rDy = 1;
                break;
        }

        for (int oy = 0; oy < oh; ++oy) {
            const int sy = oy * 2;
            const float* row0 = bayer + static_cast<size_t>(sy) * w;
            const float* row1 = bayer + static_cast<size_t>(sy + 1) * w;
            const size_t outRow = static_cast<size_t>(oy) * ow;

            for (int ox = 0; ox < ow; ++ox) {
                const int sx = ox * 2;
                float r = (rDy == 0 ? row0 : row1)[sx + rDx];
                float g = 0.5f * ((g1Dy == 0 ? row0 : row1)[sx + g1Dx] +
                                  (g2Dy == 0 ? row0 : row1)[sx + g2Dx]);
                float b = (bDy == 0 ? row0 : row1)[sx + bDx];
                applyWbAndCcm(r, g, b, params);
                out.r[outRow + ox] = r;
                out.g[outRow + ox] = g;
                out.b[outRow + ox] = b;
            }
        }
        return;
    }

    // Full-resolution Malvar-He-Cutler 5x5 high-quality linear demosaicing
    out.resize(w, h);
    constexpr float kInv8 = 0.125f;

    for (int y = 0; y < h; ++y) {
        const size_t outRow = static_cast<size_t>(y) * w;
        const bool interiorY = (y >= 2 && y < h - 2);

        for (int x = 0; x < w; ++x) {
            const int color = cfaColorAt(x, y, params.cfa);
            float p00, pm10, pp10, p0m1, p0p1, pm20, pp20, p0m2, p0p2;
            float pm1m1, pp1m1, pm1p1, pp1p1;

            if (interiorY && x >= 2 && x < w - 2) {
                const size_t idx = static_cast<size_t>(y) * w + x;
                p00 = bayer[idx];
                pm10 = bayer[idx - 1];
                pp10 = bayer[idx + 1];
                p0m1 = bayer[idx - w];
                p0p1 = bayer[idx + w];
                pm20 = bayer[idx - 2];
                pp20 = bayer[idx + 2];
                p0m2 = bayer[idx - 2 * w];
                p0p2 = bayer[idx + 2 * w];
                pm1m1 = bayer[idx - w - 1];
                pp1m1 = bayer[idx - w + 1];
                pm1p1 = bayer[idx + w - 1];
                pp1p1 = bayer[idx + w + 1];
            } else {
                p00 = sampleClamp(bayer, w, h, x, y);
                pm10 = sampleClamp(bayer, w, h, x - 1, y);
                pp10 = sampleClamp(bayer, w, h, x + 1, y);
                p0m1 = sampleClamp(bayer, w, h, x, y - 1);
                p0p1 = sampleClamp(bayer, w, h, x, y + 1);
                pm20 = sampleClamp(bayer, w, h, x - 2, y);
                pp20 = sampleClamp(bayer, w, h, x + 2, y);
                p0m2 = sampleClamp(bayer, w, h, x, y - 2);
                p0p2 = sampleClamp(bayer, w, h, x, y + 2);
                pm1m1 = sampleClamp(bayer, w, h, x - 1, y - 1);
                pp1m1 = sampleClamp(bayer, w, h, x + 1, y - 1);
                pm1p1 = sampleClamp(bayer, w, h, x - 1, y + 1);
                pp1p1 = sampleClamp(bayer, w, h, x + 1, y + 1);
            }

            float r = 0.0f, g = 0.0f, b = 0.0f;
            if (color == 0) {
                // Red site
                r = p00;
                g = (4.0f * p00 + 2.0f * (pm10 + pp10 + p0m1 + p0p1) -
                     (pm20 + pp20 + p0m2 + p0p2)) * kInv8;
                b = (6.0f * p00 + 2.0f * (pm1m1 + pp1m1 + pm1p1 + pp1p1) -
                     1.5f * (pm20 + pp20 + p0m2 + p0p2)) * kInv8;
            } else if (color == 3) {
                // Blue site
                b = p00;
                g = (4.0f * p00 + 2.0f * (pm10 + pp10 + p0m1 + p0p1) -
                     (pm20 + pp20 + p0m2 + p0p2)) * kInv8;
                r = (6.0f * p00 + 2.0f * (pm1m1 + pp1m1 + pm1p1 + pp1p1) -
                     1.5f * (pm20 + pp20 + p0m2 + p0p2)) * kInv8;
            } else if (color == 1) {
                // Green on Red row (horizontal = R, vertical = B)
                const float diag = pm1m1 + pp1m1 + pm1p1 + pp1p1;
                g = p00;
                r = (5.0f * p00 + 4.0f * (pm10 + pp10) - (diag + pm20 + pp20) +
                     0.5f * (p0m2 + p0p2)) * kInv8;
                b = (5.0f * p00 + 4.0f * (p0m1 + p0p1) - (diag + p0m2 + p0p2) +
                     0.5f * (pm20 + pp20)) * kInv8;
            } else {
                // Green on Blue row (horizontal = B, vertical = R)
                const float diag = pm1m1 + pp1m1 + pm1p1 + pp1p1;
                g = p00;
                b = (5.0f * p00 + 4.0f * (pm10 + pp10) - (diag + pm20 + pp20) +
                     0.5f * (p0m2 + p0p2)) * kInv8;
                r = (5.0f * p00 + 4.0f * (p0m1 + p0p1) - (diag + p0m2 + p0p2) +
                     0.5f * (pm20 + pp20)) * kInv8;
            }

            applyWbAndCcm(r, g, b, params);
            out.r[outRow + x] = r;
            out.g[outRow + x] = g;
            out.b[outRow + x] = b;
        }
    }
}

void processRaw16ToFloatRGB(
    const uint8_t* rawBytes,
    const RawParams& params,
    const CalibrationMaster* bias,
    const CalibrationMaster* dark,
    const CalibrationMaster* flat,
    FloatImageRGB& out
) {
    std::vector<float> bayer;
    calibrateRawToFloat(rawBytes, params, bias, dark, flat, bayer);
    if (bayer.empty()) {
        out.clear();
        return;
    }
    demosaicBayerToFloatRGB(bayer.data(), params, out);
}

void convertYuv420ToFloatRGB(
    const uint8_t* yPlane, int yRowStride, int yPixelStride,
    const uint8_t* uPlane, int uRowStride, int uPixelStride,
    const uint8_t* vPlane, int vRowStride, int vPixelStride,
    int width, int height,
    int downsampleStep,
    const CalibrationMaster* darkRgb,
    const CalibrationMaster* flatRgb,
    FloatImageRGB& out
) {
    if (!yPlane || !uPlane || !vPlane || width <= 0 || height <= 0) {
        out.clear();
        return;
    }
    const int step = std::max(1, downsampleStep);
    const int ow = width / step;
    const int oh = height / step;
    out.resize(ow, oh);

    // Precompute sRGB byte -> linear float LUT
    float lut[256];
    for (int i = 0; i < 256; ++i) {
        lut[i] = srgbByteToLinear(static_cast<uint8_t>(i));
    }

    const bool useDark = darkRgb != nullptr && darkRgb->valid() &&
                         darkRgb->channels == 3 && darkRgb->width == ow && darkRgb->height == oh;
    const bool useFlat = flatRgb != nullptr && flatRgb->valid() &&
                         flatRgb->channels == 3 && flatRgb->width == ow && flatRgb->height == oh;
    const size_t planeN = static_cast<size_t>(ow) * static_cast<size_t>(oh);

    for (int oy = 0; oy < oh; ++oy) {
        const int sy = oy * step;
        const int uvY = sy >> 1;
        const uint8_t* yRow = yPlane + static_cast<size_t>(sy) * yRowStride;
        const uint8_t* uRow = uPlane + static_cast<size_t>(uvY) * uRowStride;
        const uint8_t* vRow = vPlane + static_cast<size_t>(uvY) * vRowStride;
        const size_t outRow = static_cast<size_t>(oy) * ow;

        for (int ox = 0; ox < ow; ++ox) {
            const int sx = ox * step;
            const int uvX = sx >> 1;
            const float Y = static_cast<float>(yRow[sx * yPixelStride]);
            const float U = static_cast<float>(uRow[uvX * uPixelStride]) - 128.0f;
            const float V = static_cast<float>(vRow[uvX * vPixelStride]) - 128.0f;

            const int ri = std::clamp(static_cast<int>(Y + 1.402f * V + 0.5f), 0, 255);
            const int gi = std::clamp(static_cast<int>(Y - 0.344136f * U - 0.714136f * V + 0.5f), 0, 255);
            const int bi = std::clamp(static_cast<int>(Y + 1.772f * U + 0.5f), 0, 255);

            float r = lut[ri];
            float g = lut[gi];
            float b = lut[bi];

            const size_t idx = outRow + ox;
            if (useDark) {
                r -= darkRgb->data[idx];
                g -= darkRgb->data[planeN + idx];
                b -= darkRgb->data[2 * planeN + idx];
            }
            if (useFlat) {
                r /= std::max(flatRgb->data[idx], 0.05f);
                g /= std::max(flatRgb->data[planeN + idx], 0.05f);
                b /= std::max(flatRgb->data[2 * planeN + idx], 0.05f);
            }
            out.r[idx] = std::max(0.0f, r);
            out.g[idx] = std::max(0.0f, g);
            out.b[idx] = std::max(0.0f, b);
        }
    }
}

void analyzePreviewYuv(
    const uint8_t* yPlane, int yRowStride, int yPixelStride,
    const uint8_t* uPlane, int uRowStride, int uPixelStride,
    const uint8_t* vPlane, int vRowStride, int vPixelStride,
    int width, int height,
    int histBins,
    int32_t* histR, int32_t* histG, int32_t* histB,
    bool computeLoupe,
    float loupeNormX, float loupeNormY,
    int loupeOutSize,
    uint8_t* loupeRgbaOut,
    FocusMetrics& outMetrics
) {
    outMetrics = FocusMetrics{};
    if (!yPlane || !uPlane || !vPlane || width < 8 || height < 8) return;

    // 1. Fast subsampled RGB Histogram (stride 4x4 => ~<0.4ms on 1440x1080)
    if (histBins > 0 && histR && histG && histB) {
        std::memset(histR, 0, static_cast<size_t>(histBins) * sizeof(int32_t));
        std::memset(histG, 0, static_cast<size_t>(histBins) * sizeof(int32_t));
        std::memset(histB, 0, static_cast<size_t>(histBins) * sizeof(int32_t));

        const int step = std::max(2, std::min(width, height) / 160);
        const float scale = static_cast<float>(histBins) / 256.0f;

        for (int y = 0; y < height; y += step) {
            const uint8_t* yRow = yPlane + static_cast<size_t>(y) * yRowStride;
            const uint8_t* uRow = uPlane + static_cast<size_t>(y >> 1) * uRowStride;
            const uint8_t* vRow = vPlane + static_cast<size_t>(y >> 1) * vRowStride;

            for (int x = 0; x < width; x += step) {
                const float Y = static_cast<float>(yRow[x * yPixelStride]);
                const float U = static_cast<float>(uRow[(x >> 1) * uPixelStride]) - 128.0f;
                const float V = static_cast<float>(vRow[(x >> 1) * vPixelStride]) - 128.0f;

                const int r = std::clamp(static_cast<int>(Y + 1.402f * V), 0, 255);
                const int g = std::clamp(static_cast<int>(Y - 0.344136f * U - 0.714136f * V), 0, 255);
                const int b = std::clamp(static_cast<int>(Y + 1.772f * U), 0, 255);

                histR[std::min(histBins - 1, static_cast<int>(r * scale))]++;
                histG[std::min(histBins - 1, static_cast<int>(g * scale))]++;
                histB[std::min(histBins - 1, static_cast<int>(b * scale))]++;
            }
        }
    }

    // 2. Real-time 10x Focus Loupe crop + exact FWHM/HFD computation
    if (computeLoupe && loupeOutSize >= 32) {
        const int cropSize = std::min(128, std::min(width, height) / 3);
        const int half = cropSize / 2;
        const int cx = std::clamp(static_cast<int>(loupeNormX * width), half, width - half - 1);
        const int cy = std::clamp(static_cast<int>(loupeNormY * height), half, height - half - 1);

        std::vector<float> luma(static_cast<size_t>(cropSize) * cropSize);
        for (int dy = 0; dy < cropSize; ++dy) {
            const int sy = cy - half + dy;
            const uint8_t* yRow = yPlane + static_cast<size_t>(sy) * yRowStride;
            for (int dx = 0; dx < cropSize; ++dx) {
                const int sx = cx - half + dx;
                luma[static_cast<size_t>(dy) * cropSize + dx] =
                    static_cast<float>(yRow[sx * yPixelStride]) * (1.0f / 255.0f);
            }
        }

        outMetrics = measureFocusCrop(luma.data(), cropSize, cropSize);

        // Render zoomed loupe RGBA8 (nearest/bilinear upsampling with subtle boost for dim stars)
        if (loupeRgbaOut != nullptr) {
            const float bg = outMetrics.bgMedian;
            const float gain = (outMetrics.peakValue - bg > 0.08f) ? 1.35f : 2.2f;
            for (int oy = 0; oy < loupeOutSize; ++oy) {
                const int dy = std::clamp((oy * cropSize) / loupeOutSize, 0, cropSize - 1);
                const int sy = cy - half + dy;
                const uint8_t* yRow = yPlane + static_cast<size_t>(sy) * yRowStride;
                const uint8_t* uRow = uPlane + static_cast<size_t>(sy >> 1) * uRowStride;
                const uint8_t* vRow = vPlane + static_cast<size_t>(sy >> 1) * vRowStride;

                for (int ox = 0; ox < loupeOutSize; ++ox) {
                    const int dx = std::clamp((ox * cropSize) / loupeOutSize, 0, cropSize - 1);
                    const int sx = cx - half + dx;
                    const float Y = static_cast<float>(yRow[sx * yPixelStride]);
                    const float U = static_cast<float>(uRow[(sx >> 1) * uPixelStride]) - 128.0f;
                    const float V = static_cast<float>(vRow[(sx >> 1) * vPixelStride]) - 128.0f;

                    float r = (Y + 1.402f * V) * gain;
                    float g = (Y - 0.344136f * U - 0.714136f * V) * gain;
                    float b = (Y + 1.772f * U) * gain;

                    const size_t oidx = (static_cast<size_t>(oy) * loupeOutSize + ox) * 4;
                    loupeRgbaOut[oidx + 0] = static_cast<uint8_t>(std::clamp(r, 0.0f, 255.0f));
                    loupeRgbaOut[oidx + 1] = static_cast<uint8_t>(std::clamp(g, 0.0f, 255.0f));
                    loupeRgbaOut[oidx + 2] = static_cast<uint8_t>(std::clamp(b, 0.0f, 255.0f));
                    loupeRgbaOut[oidx + 3] = 255;
                }
            }
        }
    }
}

} // namespace astro
