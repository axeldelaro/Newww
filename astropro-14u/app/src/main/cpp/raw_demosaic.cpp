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

int computeFocusPeakingPoints(
    const uint8_t* yPlane, int yRowStride, int yPixelStride,
    int width, int height,
    float threshold,
    int maxPoints,
    float* outNormXY
) {
    if (!yPlane || !outNormXY || width < 8 || height < 8 || maxPoints <= 0) {
        return 0;
    }

    constexpr int kGridW = 96;
    constexpr int kGridH = 72;
    const int gw = std::min(kGridW, width);
    const int gh = std::min(kGridH, height);
    if (gw < 4 || gh < 4) return 0;

    const int pStride = std::max(1, yPixelStride);
    const int rStride = yRowStride > 0 ? yRowStride : (width * pStride);

    // Track peak true 1-pixel-step Sobel gradient magnitude squared inside each 96x72 cell.
    // Measuring 1-pixel spatial derivatives guarantees genuine focus selectivity:
    // razor-sharp lunar craters and pinpoint stars score high, while defocused edges drop near zero.
    int cellBestMagSq[kGridW * kGridH];
    std::memset(cellBestMagSq, 0, sizeof(cellBestMagSq));

    const int stepX = std::max(1, width / (gw * 2));
    const int stepY = std::max(1, height / (gh * 2));

    for (int y = 1; y < height - 1; y += stepY) {
        const uint8_t* rPrev = yPlane + static_cast<size_t>(y - 1) * rStride;
        const uint8_t* rCurr = yPlane + static_cast<size_t>(y) * rStride;
        const uint8_t* rNext = yPlane + static_cast<size_t>(y + 1) * rStride;
        const int gy = std::min(gh - 1, (y * gh) / height);
        int* cellRow = cellBestMagSq + gy * gw;

        for (int x = 1; x < width - 1; x += stepX) {
            const int xm = (x - 1) * pStride;
            const int xc = x * pStride;
            const int xp = (x + 1) * pStride;

            const int p00 = rPrev[xm];
            const int p01 = rPrev[xc];
            const int p02 = rPrev[xp];
            const int p10 = rCurr[xm];
            const int p12 = rCurr[xp];
            const int p20 = rNext[xm];
            const int p21 = rNext[xc];
            const int p22 = rNext[xp];

            const int sx = -p00 + p02 - 2 * p10 + 2 * p12 - p20 + p22;
            const int sy = -p00 - 2 * p01 - p02 + p20 + 2 * p21 + p22;
            const int magSq = sx * sx + sy * sy;

            const int gx = std::min(gw - 1, (x * gw) / width);
            if (magSq > cellRow[gx]) {
                cellRow[gx] = magSq;
            }
        }
    }

    // A full 0..255 step edge gives Sobel magnitude = 4 * 255 = 1020
    const float thresh = std::clamp(threshold, 0.03f, 0.95f);
    const float threshRaw = thresh * 1020.0f;
    const int threshSq = static_cast<int>(threshRaw * threshRaw);

    struct EdgeCandidate {
        float x;
        float y;
        int strengthSq;
    };
    std::vector<EdgeCandidate> candidates;
    candidates.reserve(static_cast<size_t>(std::min(maxPoints * 2, gw * gh / 4)));

    const float invGw = 1.0f / static_cast<float>(gw);
    const float invGh = 1.0f / static_cast<float>(gh);

    for (int gy = 0; gy < gh; ++gy) {
        const int* cellRow = cellBestMagSq + gy * gw;
        for (int gx = 0; gx < gw; ++gx) {
            const int sSq = cellRow[gx];
            if (sSq >= threshSq) {
                candidates.push_back({
                    (static_cast<float>(gx) + 0.5f) * invGw,
                    (static_cast<float>(gy) + 0.5f) * invGh,
                    sSq
                });
            }
        }
    }

    if (candidates.empty()) return 0;

    int outCount = static_cast<int>(candidates.size());
    if (outCount > maxPoints) {
        std::nth_element(
            candidates.begin(),
            candidates.begin() + maxPoints,
            candidates.end(),
            [](const EdgeCandidate& a, const EdgeCandidate& b) {
                return a.strengthSq > b.strengthSq;
            }
        );
        outCount = maxPoints;
    }

    for (int i = 0; i < outCount; ++i) {
        outNormXY[i * 2 + 0] = candidates[i].x;
        outNormXY[i * 2 + 1] = candidates[i].y;
    }
    return outCount;
}

BestFocusStarResult findBestFocusStar(
    const uint8_t* yPlane, int yRowStride, int yPixelStride,
    int width, int height
) {
    BestFocusStarResult res{};
    if (!yPlane || width < 32 || height < 32) return res;

    const int pStride = std::max(1, yPixelStride);
    const int rStride = yRowStride > 0 ? yRowStride : (width * pStride);

    auto pixAt = [&](int x, int y) -> float {
        return static_cast<float>(yPlane[static_cast<size_t>(y) * rStride + static_cast<size_t>(x) * pStride]);
    };

    // Search strictly inside the central 70% of the frame (auto-rejects border hot pixels & edge coma)
    const int marginX = std::max(8, static_cast<int>(width * 0.15f));
    const int marginY = std::max(8, static_cast<int>(height * 0.15f));
    const int x0 = std::min(marginX, width / 2 - 4);
    const int x1 = std::max(width - marginX, width / 2 + 4);
    const int y0 = std::min(marginY, height / 2 - 4);
    const int y1 = std::max(height - marginY, height / 2 + 4);

    // 1. Estimate sky background median & MAD sigma inside central search window
    const int bgStep = std::max(1, std::min(x1 - x0, y1 - y0) / 48);
    std::vector<float> samples;
    samples.reserve(4096);
    for (int y = y0; y < y1; y += bgStep) {
        for (int x = x0; x < x1; x += bgStep) {
            samples.push_back(pixAt(x, y));
        }
    }
    if (samples.empty()) return res;

    const size_t mid = samples.size() / 2;
    std::nth_element(samples.begin(), samples.begin() + mid, samples.end());
    const float bgMed = samples[mid];
    for (float& v : samples) v = std::abs(v - bgMed);
    std::nth_element(samples.begin(), samples.begin() + mid, samples.end());
    const float bgSig = std::max(1.4826f * samples[mid], 1.2f);

    const float detThresh = bgMed + std::max(6.0f * bgSig, 12.0f);
    const float nbrThresh = bgMed + std::max(2.0f * bgSig, 5.0f);

    BestFocusStarResult bestUnsat{};
    float bestUnsatScore = -1.0f;
    BestFocusStarResult bestSat{};
    float bestSatScore = -1.0f;

    static constexpr int kRingDx[8] = {7, 5, 0, -5, -7, -5, 0, 5};
    static constexpr int kRingDy[8] = {0, 5, 7, 5, 0, -5, -7, -5};

    // 2. Scan for compact bright unsaturated stars in central 70%
    for (int y = y0; y < y1; ++y) {
        for (int x = x0; x < x1; ++x) {
            const float v = pixAt(x, y);
            if (v < detThresh) continue;

            // Strict 5x5 local maximum
            bool isMax = true;
            for (int dy = -2; dy <= 2 && isMax; ++dy) {
                for (int dx = -2; dx <= 2; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    const float nv = pixAt(x + dx, y + dy);
                    if (nv > v || (nv == v && (dy < 0 || (dy == 0 && dx < 0)))) {
                        isMax = false;
                        break;
                    }
                }
            }
            if (!isMax) continue;

            // Reject single-pixel hot pixels (require >= 2 of 4 direct neighbors above bg + 2*sigma)
            const float nL = pixAt(x - 1, y);
            const float nR = pixAt(x + 1, y);
            const float nU = pixAt(x, y - 1);
            const float nD = pixAt(x, y + 1);
            int brightNbrs = 0;
            if (nL > nbrThresh) brightNbrs++;
            if (nR > nbrThresh) brightNbrs++;
            if (nU > nbrThresh) brightNbrs++;
            if (nD > nbrThresh) brightNbrs++;
            if (brightNbrs < 2) continue;

            // Verify compact star PSF (outer radius-7 ring must fall back toward background)
            float ringSum = 0.0f;
            for (int k = 0; k < 8; ++k) {
                ringSum += pixAt(x + kRingDx[k], y + kRingDy[k]);
            }
            const float ringMean = ringSum * 0.125f;
            const float aboveBg = v - bgMed;
            if (ringMean > bgMed + 0.45f * aboveBg) continue;

            // Sub-pixel centroid & integrated flux in 9x9 aperture
            double sumW = 0.0, sumX = 0.0, sumY = 0.0;
            int satPixels = 0;
            for (int dy = -4; dy <= 4; ++dy) {
                for (int dx = -4; dx <= 4; ++dx) {
                    if (dx * dx + dy * dy > 18) continue;
                    const float pv = pixAt(x + dx, y + dy);
                    if (pv >= 250.0f) satPixels++;
                    const float excess = pv - bgMed;
                    if (excess > 2.0f * bgSig) {
                        sumW += excess;
                        sumX += excess * (x + dx);
                        sumY += excess * (y + dy);
                    }
                }
            }
            if (sumW <= 1.0) continue;

            const float cx = static_cast<float>(sumX / sumW);
            const float cy = static_cast<float>(sumY / sumW);
            const float snr = aboveBg / bgSig;
            // Subtle center preference (1.0 at center -> 0.85 near 70% edge)
            const float distNorm = std::hypot((cx / width) - 0.5f, (cy / height) - 0.5f);
            const float centerWeight = std::max(0.80f, 1.0f - 0.4f * distNorm);
            const float score = static_cast<float>(sumW) * centerWeight;

            const bool isSaturated = (v >= 247.0f) || (satPixels >= 2);
            if (!isSaturated) {
                if (score > bestUnsatScore) {
                    bestUnsatScore = score;
                    bestUnsat.found = true;
                    bestUnsat.isStar = true;
                    bestUnsat.normX = std::clamp(cx / static_cast<float>(width), 0.05f, 0.95f);
                    bestUnsat.normY = std::clamp(cy / static_cast<float>(height), 0.05f, 0.95f);
                    bestUnsat.peak = v / 255.0f;
                    bestUnsat.snr = snr;
                }
            } else {
                if (score > bestSatScore) {
                    bestSatScore = score;
                    bestSat.found = true;
                    bestSat.isStar = true;
                    bestSat.normX = std::clamp(cx / static_cast<float>(width), 0.05f, 0.95f);
                    bestSat.normY = std::clamp(cy / static_cast<float>(height), 0.05f, 0.95f);
                    bestSat.peak = v / 255.0f;
                    bestSat.snr = snr;
                }
            }
        }
    }

    if (bestUnsat.found) return bestUnsat;
    if (bestSat.found) return bestSat;

    // 3. Fallback: sharpest lunar crater / horizon high-contrast tile inside central 70%
    constexpr int kTilesX = 10;
    constexpr int kTilesY = 8;
    const int spanX = std::max(1, x1 - x0);
    const int spanY = std::max(1, y1 - y0);
    const int step = std::max(1, std::min(spanX, spanY) / 160);

    double bestTileGrad = 35.0; // Noise floor
    float bestTx = 0.5f, bestTy = 0.5f;
    bool foundContrast = false;

    for (int ty = 0; ty < kTilesY; ++ty) {
        const int ty0 = y0 + (ty * spanY) / kTilesY;
        const int ty1 = y0 + ((ty + 1) * spanY) / kTilesY;
        for (int tx = 0; tx < kTilesX; ++tx) {
            const int tx0 = x0 + (tx * spanX) / kTilesX;
            const int tx1 = x0 + ((tx + 1) * spanX) / kTilesX;
            double gradSum = 0.0;
            int cnt = 0;
            for (int y = std::max(1, ty0); y < std::min(height - 1, ty1); y += step) {
                for (int x = std::max(1, tx0); x < std::min(width - 1, tx1); x += step) {
                    const float gx = -pixAt(x - 1, y - 1) + pixAt(x + 1, y - 1)
                                     - 2.0f * pixAt(x - 1, y) + 2.0f * pixAt(x + 1, y)
                                     - pixAt(x - 1, y + 1) + pixAt(x + 1, y + 1);
                    const float gy = -pixAt(x - 1, y - 1) - 2.0f * pixAt(x, y - 1) - pixAt(x + 1, y - 1)
                                     + pixAt(x - 1, y + 1) + 2.0f * pixAt(x, y + 1) + pixAt(x + 1, y + 1);
                    gradSum += static_cast<double>(gx * gx + gy * gy);
                    cnt++;
                }
            }
            if (cnt > 0) {
                const double meanGrad = gradSum / static_cast<double>(cnt);
                if (meanGrad > bestTileGrad) {
                    bestTileGrad = meanGrad;
                    bestTx = (0.5f * (tx0 + tx1)) / static_cast<float>(width);
                    bestTy = (0.5f * (ty0 + ty1)) / static_cast<float>(height);
                    foundContrast = true;
                }
            }
        }
    }

    if (foundContrast) {
        res.found = true;
        res.isStar = false;
        res.normX = std::clamp(bestTx, 0.15f, 0.85f);
        res.normY = std::clamp(bestTy, 0.15f, 0.85f);
        res.peak = 1.0f;
        res.snr = static_cast<float>(std::sqrt(bestTileGrad));
    }
    return res;
}

} // namespace astro
