#include "astro_types.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <numeric>

namespace astro {

namespace {

float estimateSkySigma(const std::vector<float>& ch) {
    if (ch.empty()) return 0.002f;
    const size_t step = std::max<size_t>(1, ch.size() / 4096);
    std::vector<float> s;
    s.reserve(ch.size() / step + 1);
    for (size_t i = 0; i < ch.size(); i += step) s.push_back(ch[i]);
    const size_t mid = s.size() / 2;
    std::nth_element(s.begin(), s.begin() + mid, s.end());
    const float med = s[mid];
    for (float& v : s) v = std::abs(v - med);
    std::nth_element(s.begin(), s.begin() + mid, s.end());
    return std::max(1.4826f * s[mid], 0.0005f);
}

void packFloatToU16(const FloatImageRGB& src, float& outScale,
                    std::vector<uint16_t>& r, std::vector<uint16_t>& g, std::vector<uint16_t>& b) {
    float maxV = 1.0f;
    const size_t n = src.pixelCount();
    for (size_t i = 0; i < n; ++i) {
        maxV = std::max(maxV, std::max(src.r[i], std::max(src.g[i], src.b[i])));
    }
    outScale = maxV;
    const float inv = 65535.0f / maxV;
    r.resize(n);
    g.resize(n);
    b.resize(n);
    for (size_t i = 0; i < n; ++i) {
        r[i] = static_cast<uint16_t>(std::clamp(src.r[i] * inv + 0.5f, 0.0f, 65535.0f));
        g[i] = static_cast<uint16_t>(std::clamp(src.g[i] * inv + 0.5f, 0.0f, 65535.0f));
        b[i] = static_cast<uint16_t>(std::clamp(src.b[i] * inv + 0.5f, 0.0f, 65535.0f));
    }
}

void unpackU16ToFloat(int w, int h, float scale,
                      const std::vector<uint16_t>& r,
                      const std::vector<uint16_t>& g,
                      const std::vector<uint16_t>& b,
                      FloatImageRGB& out) {
    out.resize(w, h);
    const size_t n = out.pixelCount();
    const float k = scale / 65535.0f;
    for (size_t i = 0; i < n; ++i) {
        out.r[i] = r[i] * k;
        out.g[i] = g[i] * k;
        out.b[i] = b[i] * k;
    }
}

inline void seedPixelFrom3(
    const float vals[3], int validN, float skySig, float kappa,
    float& outMean, float& outM2, uint16_t& outCount, int64_t& rejectedPixels
) {
    if (validN == 0) {
        outMean = 0.0f;
        outM2 = 0.0f;
        outCount = 0;
        return;
    }
    if (validN == 1) {
        outMean = vals[0];
        outM2 = 0.0f;
        outCount = 1;
        return;
    }
    if (validN == 2) {
        outMean = 0.5f * (vals[0] + vals[1]);
        const float d = vals[0] - outMean;
        outM2 = 2.0f * d * d;
        outCount = 2;
        return;
    }

    // validN == 3: Sort ascending
    float v0 = vals[0], v1 = vals[1], v2 = vals[2];
    if (v0 > v1) std::swap(v0, v1);
    if (v1 > v2) std::swap(v1, v2);
    if (v0 > v1) std::swap(v0, v1);

    // Check if the extreme high or low value is a stark outlier (e.g., satellite/airplane on frame 1..3!)
    const float dLow = v1 - v0;
    const float dHigh = v2 - v1;
    const float closePairScale = std::max(std::min(dLow, dHigh), skySig);
    const float thresh = std::max(kappa * 1.4f * closePairScale, kappa * 2.5f * skySig);

    if (dHigh > thresh && dHigh > 3.0f * dLow) {
        // High outlier rejected (satellite/cosmic ray/hot pixel)
        outMean = 0.5f * (v0 + v1);
        const float d = v0 - outMean;
        outM2 = 2.0f * d * d;
        outCount = 2;
        rejectedPixels++;
    } else if (dLow > thresh && dLow > 3.0f * dHigh) {
        // Low outlier rejected (dead pixel/obstruction)
        outMean = 0.5f * (v1 + v2);
        const float d = v1 - outMean;
        outM2 = 2.0f * d * d;
        outCount = 2;
        rejectedPixels++;
    } else {
        outMean = (v0 + v1 + v2) * (1.0f / 3.0f);
        const float d0 = v0 - outMean;
        const float d1 = v1 - outMean;
        const float d2 = v2 - outMean;
        outM2 = d0 * d0 + d1 * d1 + d2 * d2;
        outCount = 3;
    }
}

constexpr float kPiStack = 3.14159265358979323846f;

void downsampleToMaxWidth(const FloatImageRGB& in, int maxW, FloatImageRGB& out) {
    if (!in.valid() || in.width <= maxW) {
        out = in;
        return;
    }
    const int sw = in.width;
    const int sh = in.height;
    const int dw = maxW;
    const int dh = std::max(16, (sh * dw) / sw);
    out.resize(dw, dh);

    const float scaleX = static_cast<float>(sw) / static_cast<float>(dw);
    const float scaleY = static_cast<float>(sh) / static_cast<float>(dh);

    for (int y = 0; y < dh; ++y) {
        const float sy = (y + 0.5f) * scaleY - 0.5f;
        const int y0 = std::clamp(static_cast<int>(std::floor(sy)), 0, sh - 1);
        const int y1 = std::min(y0 + 1, sh - 1);
        const float fy = std::clamp(sy - y0, 0.0f, 1.0f);
        const size_t dRow = static_cast<size_t>(y) * dw;

        for (int x = 0; x < dw; ++x) {
            const float sx = (x + 0.5f) * scaleX - 0.5f;
            const int x0 = std::clamp(static_cast<int>(std::floor(sx)), 0, sw - 1);
            const int x1 = std::min(x0 + 1, sw - 1);
            const float fx = std::clamp(sx - x0, 0.0f, 1.0f);

            const float w00 = (1.0f - fx) * (1.0f - fy);
            const float w10 = fx * (1.0f - fy);
            const float w01 = (1.0f - fx) * fy;
            const float w11 = fx * fy;

            const size_t i00 = static_cast<size_t>(y0) * sw + x0;
            const size_t i10 = static_cast<size_t>(y0) * sw + x1;
            const size_t i01 = static_cast<size_t>(y1) * sw + x0;
            const size_t i11 = static_cast<size_t>(y1) * sw + x1;

            out.r[dRow + x] = w00 * in.r[i00] + w10 * in.r[i10] + w01 * in.r[i01] + w11 * in.r[i11];
            out.g[dRow + x] = w00 * in.g[i00] + w10 * in.g[i10] + w01 * in.g[i01] + w11 * in.g[i11];
            out.b[dRow + x] = w00 * in.b[i00] + w10 * in.b[i10] + w01 * in.b[i01] + w11 * in.b[i11];
        }
    }
}

float vecMedian(std::vector<float>& v) {
    if (v.empty()) return 0.0f;
    const size_t mid = v.size() / 2;
    std::nth_element(v.begin(), v.begin() + mid, v.end());
    return v[mid];
}

} // namespace

// =========================================================================
// Automatic Sky / Foreground Horizon Contour Detector (Zero-Effort Sequator)
// =========================================================================

bool autoDetectHorizon(const FloatImageRGB& refFrame, std::vector<float>& horizonY) {
    horizonY.clear();
    if (!refFrame.valid() || refFrame.width < 64 || refFrame.height < 64) {
        return false;
    }

    const int w = refFrame.width;
    const int h = refFrame.height;
    const size_t n = refFrame.pixelCount();

    std::vector<float> luma(n);
    for (size_t i = 0; i < n; ++i) {
        luma[i] = 0.2126f * refFrame.r[i] + 0.7152f * refFrame.g[i] + 0.0722f * refFrame.b[i];
    }

    // Detect pinpoint stars to enforce strict Auto-No-Op protection for zenith / pure deep-sky frames
    const auto stars = detectStars(refFrame, 120);
    int bottomStars = 0;
    int bottomLeft = 0, bottomRight = 0;
    const float bottomZoneY = 0.88f * static_cast<float>(h);
    for (const auto& s : stars) {
        if (s.y >= bottomZoneY) {
            bottomStars++;
            if (s.x < w * 0.5f) bottomLeft++;
            else bottomRight++;
        }
    }
    if (stars.size() >= 6 &&
        (bottomStars >= std::max(2, static_cast<int>(stars.size()) / 9) ||
         (bottomLeft >= 1 && bottomRight >= 1 && bottomStars >= 3))) {
        // Stars detected cleanly all the way to the bottom edge -> pure sky (no foreground silhouette)
        return false;
    }

    constexpr int kCols = 64;
    std::vector<float> colHorizon(kCols, static_cast<float>(h - 1));
    std::vector<uint8_t> colValid(kCols, 0);
    std::vector<float> colMaxStarY(kCols, 0.0f);

    for (const auto& s : stars) {
        const int c = std::clamp(static_cast<int>(s.x * kCols / w), 0, kCols - 1);
        colMaxStarY[c] = std::max(colMaxStarY[c], s.y);
    }

    // Build vertical median luminance profile for each of the 64 columns
    std::vector<float> rowBuf;
    std::vector<float> prof(static_cast<size_t>(h));
    std::vector<float> sampleBuf;
    int validCols = 0;

    const int topSkyEnd = std::max(8, h / 4);
    const int botBandStart = std::min(h - 4, h - std::max(5, (h * 6) / 100));

    for (int c = 0; c < kCols; ++c) {
        const int x0 = (c * w) / kCols;
        const int x1 = std::max(x0 + 1, ((c + 1) * w) / kCols);
        const int colW = x1 - x0;
        const int xStep = std::max(1, colW / 8);

        for (int y = 0; y < h; ++y) {
            rowBuf.clear();
            const size_t rowOff = static_cast<size_t>(y) * w;
            for (int x = x0; x < x1; x += xStep) {
                rowBuf.push_back(luma[rowOff + x]);
            }
            prof[y] = vecMedian(rowBuf);
        }

        // Upper-sky baseline & noise sigma
        sampleBuf.assign(prof.begin(), prof.begin() + topSkyEnd);
        const float skyMed = vecMedian(sampleBuf);
        for (float& v : sampleBuf) v = std::abs(v - skyMed);
        const float skySig = std::max(1.4826f * vecMedian(sampleBuf), 0.0015f);

        // Bottom foreground band median
        sampleBuf.assign(prof.begin() + botBandStart, prof.end());
        const float fgMed = vecMedian(sampleBuf);

        // Local lowest star across [c-1..c+1]
        float localStarY = colMaxStarY[c];
        if (c > 0) localStarY = std::max(localStarY, colMaxStarY[c - 1]);
        if (c + 1 < kCols) localStarY = std::max(localStarY, colMaxStarY[c + 1]);

        if (localStarY >= static_cast<float>(botBandStart - 2)) {
            continue;
        }

        const bool isDarkSilhouette =
            (fgMed < skyMed - std::max(2.5f * skySig, 0.012f)) ||
            (skyMed > 0.015f && fgMed < skyMed * 0.68f && (skyMed - fgMed) > 0.008f);
        const bool isBrightForeground =
            (fgMed > skyMed + std::max(4.5f * skySig, 0.035f));

        if (!isDarkSilhouette && !isBrightForeground) {
            continue;
        }

        const float midThresh = 0.5f * (fgMed + skyMed);
        const int yMin = std::clamp(std::max(h / 5, static_cast<int>(std::ceil(localStarY)) + 4), 4, h - 6);
        int crossY = -1;

        for (int y = h - 3; y >= yMin; --y) {
            const float win = (prof[y - 1] + prof[y] + prof[y + 1]) * (1.0f / 3.0f);
            if (isDarkSilhouette) {
                if (win >= midThresh) {
                    crossY = y;
                    break;
                }
            } else {
                if (win <= midThresh) {
                    crossY = y;
                    break;
                }
            }
        }

        if (crossY < 0) continue;

        // Refine within +-4 px around threshold crossing to peak vertical edge gradient
        int bestY = crossY;
        float bestGrad = -1.0f;
        const int ry0 = std::max(yMin, crossY - 4);
        const int ry1 = std::min(h - 4, crossY + 4);
        for (int y = ry0; y <= ry1; ++y) {
            const float grad = std::abs(prof[y - 2] - prof[y + 2]);
            if (grad > bestGrad) {
                bestGrad = grad;
                bestY = y;
            }
        }

        colHorizon[c] = static_cast<float>(bestY);
        colValid[c] = 1;
        validCols++;
    }

    // Require coherent foreground across at least 65% of the horizontal frame
    if (validCols < (kCols * 65) / 100) {
        return false;
    }

    // Fill any isolated gaps by linear interpolation between nearest valid columns
    for (int c = 0; c < kCols; ++c) {
        if (colValid[c]) continue;
        int left = c - 1;
        while (left >= 0 && !colValid[left]) left--;
        int right = c + 1;
        while (right < kCols && !colValid[right]) right++;
        if (left >= 0 && right < kCols) {
            const float t = static_cast<float>(c - left) / static_cast<float>(right - left);
            colHorizon[c] = colHorizon[left] * (1.0f - t) + colHorizon[right] * t;
        } else if (left >= 0) {
            colHorizon[c] = colHorizon[left];
        } else if (right < kCols) {
            colHorizon[c] = colHorizon[right];
        }
    }

    // 5-tap 1D median filter to remove outlier spikes
    std::vector<float> medCol(kCols);
    std::vector<float> win5;
    win5.reserve(5);
    for (int c = 0; c < kCols; ++c) {
        win5.clear();
        for (int d = -2; d <= 2; ++d) {
            win5.push_back(colHorizon[std::clamp(c + d, 0, kCols - 1)]);
        }
        medCol[c] = vecMedian(win5);
    }

    // 2 passes of 5-tap Gaussian [1, 4, 6, 4, 1]/16 for smooth natural mountain/horizon contour
    std::vector<float> smoothCol = medCol;
    for (int pass = 0; pass < 2; ++pass) {
        std::vector<float> next(kCols);
        for (int c = 0; c < kCols; ++c) {
            const float p0 = smoothCol[std::clamp(c - 2, 0, kCols - 1)];
            const float p1 = smoothCol[std::clamp(c - 1, 0, kCols - 1)];
            const float p2 = smoothCol[c];
            const float p3 = smoothCol[std::clamp(c + 1, 0, kCols - 1)];
            const float p4 = smoothCol[std::clamp(c + 2, 0, kCols - 1)];
            next[c] = (p0 + 4.0f * p1 + 6.0f * p2 + 4.0f * p3 + p4) * (1.0f / 16.0f);
        }
        smoothCol.swap(next);
    }

    // Validate foreground occupies a realistic vertical fraction (8% .. 72% of frame height)
    std::vector<float> copyH = smoothCol;
    const float medHorizon = vecMedian(copyH);
    const float fgFraction = (static_cast<float>(h) - medHorizon) / static_cast<float>(h);
    if (fgFraction < 0.08f || fgFraction > 0.72f) {
        return false;
    }

    // Auto-No-Op protection #2: verify stars do not populate the detected foreground region
    int starsBelow = 0;
    for (const auto& s : stars) {
        const int c = std::clamp(static_cast<int>(s.x * kCols / w), 0, kCols - 1);
        if (s.y > smoothCol[c] + 3.0f) {
            starsBelow++;
        }
    }
    if (stars.size() >= 4 && starsBelow * 10 > static_cast<int>(stars.size())) {
        return false;
    }

    // Expand smoothed 64-column contour smoothly to full width [0..w-1]
    horizonY.resize(static_cast<size_t>(w));
    for (int x = 0; x < w; ++x) {
        const float fc = (static_cast<float>(x) + 0.5f) * static_cast<float>(kCols) / static_cast<float>(w) - 0.5f;
        const int c0 = std::clamp(static_cast<int>(std::floor(fc)), 0, kCols - 1);
        const int c1 = std::min(c0 + 1, kCols - 1);
        const float t = std::clamp(fc - static_cast<float>(c0), 0.0f, 1.0f);
        horizonY[x] = std::clamp(smoothCol[c0] * (1.0f - t) + smoothCol[c1] * t, 4.0f, static_cast<float>(h - 4));
    }
    return true;
}

// =========================================================================
// Drizzle2xAccumulator — Fruchter & Hook Variable-Pixel Linear Reconstruction
// =========================================================================

void Drizzle2xAccumulator::reset(int baseW, int baseH, float pixfrac) {
    baseW_ = std::max(0, baseW);
    baseH_ = std::max(0, baseH);
    outW_ = baseW_ * 2;
    outH_ = baseH_ * 2;
    pixfrac_ = std::clamp(pixfrac, 0.35f, 1.0f);
    frameCount_ = 0;
    const size_t n = static_cast<size_t>(outW_) * static_cast<size_t>(outH_);
    sumR_.assign(n, 0.0f);
    sumG_.assign(n, 0.0f);
    sumB_.assign(n, 0.0f);
    weight_.assign(n, 0.0f);
}

void Drizzle2xAccumulator::addFrame(
    const FloatImageRGB& src,
    const float matrix[6],
    const std::vector<uint8_t>* rejectMask
) {
    if (!src.valid() || outW_ <= 0 || outH_ <= 0) return;
    const int sw = src.width;
    const int sh = src.height;
    const float m0 = matrix[0], m1 = matrix[1], m2 = matrix[2];
    const float m3 = matrix[3], m4 = matrix[4], m5 = matrix[5];

    // Output grid pixel [ix, ix+1] maps to input sub-pixel range [(ix-0.5)/2, (ix+0.5)/2].
    // An input pixel centered at (rx, ry) lands at output coordinates ox = 2*rx + 0.5, oy = 2*ry + 0.5
    // with droplet half-width = 0.5 * 2.0 * pixfrac = pixfrac output pixels.
    const float halfDrop = pixfrac_;
    const bool useRej = rejectMask != nullptr &&
                        rejectMask->size() == static_cast<size_t>(baseW_) * static_cast<size_t>(baseH_);

    for (int y = 0; y < sh; ++y) {
        const size_t sRow = static_cast<size_t>(y) * sw;
        const float fy = static_cast<float>(y);
        float rx = m1 * fy + m2;
        float ry = m4 * fy + m5;

        for (int x = 0; x < sw; ++x) {
            if (rx >= -0.5f && ry >= -0.5f &&
                rx <= static_cast<float>(baseW_) - 0.5f &&
                ry <= static_cast<float>(baseH_) - 0.5f) {
                if (useRej) {
                    const int rxi = std::clamp(static_cast<int>(std::round(rx)), 0, baseW_ - 1);
                    const int ryi = std::clamp(static_cast<int>(std::round(ry)), 0, baseH_ - 1);
                    if ((*rejectMask)[static_cast<size_t>(ryi) * baseW_ + rxi]) {
                        rx += m0;
                        ry += m3;
                        continue;
                    }
                }

                const float ox = 2.0f * rx + 0.5f;
                const float oy = 2.0f * ry + 0.5f;
                const float xMin = ox - halfDrop;
                const float xMax = ox + halfDrop;
                const float yMin = oy - halfDrop;
                const float yMax = oy + halfDrop;

                const int ix0 = std::max(0, static_cast<int>(std::floor(xMin)));
                const int ix1 = std::min(outW_ - 1, static_cast<int>(std::floor(xMax)));
                const int iy0 = std::max(0, static_cast<int>(std::floor(yMin)));
                const int iy1 = std::min(outH_ - 1, static_cast<int>(std::floor(yMax)));

                const size_t si = sRow + x;
                const float pr = src.r[si];
                const float pg = src.g[si];
                const float pb = src.b[si];

                for (int iy = iy0; iy <= iy1; ++iy) {
                    const float yOv = std::min(yMax, static_cast<float>(iy + 1)) -
                                      std::max(yMin, static_cast<float>(iy));
                    if (yOv <= 0.0f) continue;
                    const size_t oRow = static_cast<size_t>(iy) * outW_;
                    for (int ix = ix0; ix <= ix1; ++ix) {
                        const float xOv = std::min(xMax, static_cast<float>(ix + 1)) -
                                          std::max(xMin, static_cast<float>(ix));
                        if (xOv <= 0.0f) continue;
                        const float area = xOv * yOv;
                        const size_t oi = oRow + ix;
                        sumR_[oi] += area * pr;
                        sumG_[oi] += area * pg;
                        sumB_[oi] += area * pb;
                        weight_[oi] += area;
                    }
                }
            }
            rx += m0;
            ry += m3;
        }
    }
    frameCount_++;
}

void Drizzle2xAccumulator::finalize(FloatImageRGB& out) const {
    if (outW_ <= 0 || outH_ <= 0 || frameCount_ == 0) {
        out.clear();
        return;
    }
    out.resize(outW_, outH_);
    const size_t n = out.pixelCount();
    std::vector<uint8_t> valid(n, 0);

    for (size_t i = 0; i < n; ++i) {
        if (weight_[i] > 1e-5f) {
            const float invW = 1.0f / weight_[i];
            out.r[i] = std::max(0.0f, sumR_[i] * invW);
            out.g[i] = std::max(0.0f, sumG_[i] * invW);
            out.b[i] = std::max(0.0f, sumB_[i] * invW);
            valid[i] = 1;
        }
    }

    // Graceful hole-fill fallback where sub-pixel weight < eps (up to 2 passes)
    for (int pass = 0; pass < 2; ++pass) {
        bool anyHole = false;
        std::vector<uint8_t> nextValid = valid;
        for (int y = 0; y < outH_; ++y) {
            for (int x = 0; x < outW_; ++x) {
                const size_t idx = static_cast<size_t>(y) * outW_ + x;
                if (valid[idx]) continue;
                anyHole = true;
                float sr = 0.0f, sg = 0.0f, sb = 0.0f;
                int cnt = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    const int ny = y + dy;
                    if (ny < 0 || ny >= outH_) continue;
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int nx = x + dx;
                        if (nx < 0 || nx >= outW_) continue;
                        const size_t ni = static_cast<size_t>(ny) * outW_ + nx;
                        if (valid[ni]) {
                            sr += out.r[ni];
                            sg += out.g[ni];
                            sb += out.b[ni];
                            cnt++;
                        }
                    }
                }
                if (cnt > 0) {
                    const float inv = 1.0f / static_cast<float>(cnt);
                    out.r[idx] = sr * inv;
                    out.g[idx] = sg * inv;
                    out.b[idx] = sb * inv;
                    nextValid[idx] = 1;
                }
            }
        }
        valid.swap(nextValid);
        if (!anyHole) break;
    }
}

// =========================================================================
// DeepSkyStacker — Star-Aligned Streaming Welford + Kappa-Sigma + Auto-Horizon + Drizzle 2x
// =========================================================================

void DeepSkyStacker::reset(float kappa, float maxResidualPx, bool landscapeMode, bool drizzle2x) {
    kappa_ = std::clamp(kappa, 1.5f, 5.0f);
    maxResidualPx_ = std::max(0.5f, maxResidualPx);
    landscapeMode_ = landscapeMode;
    drizzle2x_ = drizzle2x;
    hasForeground_ = false;
    width_ = 0;
    height_ = 0;
    received_ = 0;
    accepted_ = 0;
    rejected_ = 0;
    alignedCount_ = 0;
    sumResidual_ = 0.0;
    totalRejectedPixels_ = 0;
    seeded_ = false;
    refImage_.clear();
    refStars_.clear();
    initialFrames_.clear();
    meanR_.clear(); meanG_.clear(); meanB_.clear();
    m2R_.clear(); m2G_.clear(); m2B_.clear();
    count_.clear();
    horizonY_.clear();
    fgSumR_.clear(); fgSumG_.clear(); fgSumB_.clear();
    fgCount_ = 0;
    drizzle_.reset(0, 0);
}

void DeepSkyStacker::seedFromInitialFrames() {
    if (seeded_ || initialFrames_.empty()) return;
    const size_t n = static_cast<size_t>(width_) * static_cast<size_t>(height_);
    meanR_.assign(n, 0.0f);
    meanG_.assign(n, 0.0f);
    meanB_.assign(n, 0.0f);
    m2R_.assign(n, 0.0f);
    m2G_.assign(n, 0.0f);
    m2B_.assign(n, 0.0f);
    count_.assign(n, 0);

    const int nf = static_cast<int>(initialFrames_.size());
    float scales[3] = {1.0f / 65535.0f, 1.0f / 65535.0f, 1.0f / 65535.0f};
    for (int f = 0; f < nf; ++f) {
        scales[f] = initialFrames_[f].maxScale / 65535.0f;
    }

    for (size_t i = 0; i < n; ++i) {
        float vr[3], vg[3], vb[3];
        int vn = 0;
        for (int f = 0; f < nf; ++f) {
            if (initialFrames_[f].mask[i]) {
                vr[vn] = initialFrames_[f].r[i] * scales[f];
                vg[vn] = initialFrames_[f].g[i] * scales[f];
                vb[vn] = initialFrames_[f].b[i] * scales[f];
                vn++;
            }
        }
        uint16_t cr = 0, cg = 0, cb = 0;
        int64_t rej = 0;
        seedPixelFrom3(vr, vn, skySigmaR_, kappa_, meanR_[i], m2R_[i], cr, rej);
        seedPixelFrom3(vg, vn, skySigmaG_, kappa_, meanG_[i], m2G_[i], cg, rej);
        seedPixelFrom3(vb, vn, skySigmaB_, kappa_, meanB_[i], m2B_[i], cb, rej);
        count_[i] = std::min(cr, std::min(cg, cb));
        if (rej > 0) totalRejectedPixels_++;
    }

    initialFrames_.clear();
    initialFrames_.shrink_to_fit();
    seeded_ = true;
}

FrameStackReport DeepSkyStacker::addFrame(const FloatImageRGB& rawFrame) {
    FrameStackReport rep{};
    received_++;
    rep.totalReceived = received_;

    if (!rawFrame.valid()) {
        rejected_++;
        rep.totalAccepted = accepted_;
        rep.totalRejected = rejected_;
        return rep;
    }

    // Smart mobile RAM cap for Drizzle 2x: cap base resolution at 1600px -> crisp 3200x2400 2x output!
    FloatImageRGB resizedFrame;
    const FloatImageRGB* framePtr = &rawFrame;
    if (drizzle2x_ && rawFrame.width > 2200) {
        downsampleToMaxWidth(rawFrame, 1600, resizedFrame);
        framePtr = &resizedFrame;
    }
    const FloatImageRGB& frame = *framePtr;

    if (accepted_ == 0) {
        // First accepted frame establishes the reference astrometric coordinate system
        width_ = frame.width;
        height_ = frame.height;
        refImage_ = frame;

        if (landscapeMode_) {
            hasForeground_ = autoDetectHorizon(frame, horizonY_);
        }

        auto allStars = detectStars(frame, 80);
        if (hasForeground_ && horizonY_.size() == static_cast<size_t>(width_)) {
            refStars_.clear();
            for (const auto& s : allStars) {
                const int sx = std::clamp(static_cast<int>(std::round(s.x)), 0, width_ - 1);
                if (s.y < horizonY_[sx] - 5.0f) {
                    refStars_.push_back(s);
                }
            }
            if (refStars_.size() < 4) {
                refStars_ = std::move(allStars);
            }
            // Seed ground-locked foreground accumulator with unshifted reference frame
            const size_t n = frame.pixelCount();
            fgSumR_.assign(frame.r.begin(), frame.r.end());
            fgSumG_.assign(frame.g.begin(), frame.g.end());
            fgSumB_.assign(frame.b.begin(), frame.b.end());
            fgCount_ = 1;
            (void)n;
        } else {
            refStars_ = std::move(allStars);
        }

        skySigmaR_ = estimateSkySigma(frame.r);
        skySigmaG_ = estimateSkySigma(frame.g);
        skySigmaB_ = estimateSkySigma(frame.b);

        CompactFrame16 cf;
        cf.width = width_;
        cf.height = height_;
        packFloatToU16(frame, cf.maxScale, cf.r, cf.g, cf.b);
        cf.mask.assign(frame.pixelCount(), 1);
        initialFrames_.push_back(std::move(cf));

        if (drizzle2x_) {
            drizzle_.reset(width_, height_, 0.72f);
            static constexpr float kIdentity[6] = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f};
            drizzle_.addFrame(frame, kIdentity, nullptr);
        }

        accepted_ = 1;
        rep.accepted = true;
        rep.totalAccepted = accepted_;
        rep.totalRejected = rejected_;
        rep.starsDetected = static_cast<int>(refStars_.size());
        rep.starsMatched = rep.starsDetected;
        rep.residualPx = 0.0f;
        return rep;
    }

    if (frame.width != width_ || frame.height != height_) {
        rejected_++;
        rep.totalAccepted = accepted_;
        rep.totalRejected = rejected_;
        return rep;
    }

    // Detect stars (restricted to sky zone when auto-horizon foreground is active) and align to reference
    auto rawStars = detectStars(frame, 80);
    std::vector<StarPoint> stars;
    if (hasForeground_ && horizonY_.size() == static_cast<size_t>(width_)) {
        stars.reserve(rawStars.size());
        for (const auto& s : rawStars) {
            const int sx = std::clamp(static_cast<int>(std::round(s.x)), 0, width_ - 1);
            if (s.y < horizonY_[sx] - 5.0f) {
                stars.push_back(s);
            }
        }
        if (stars.size() < 4) stars = std::move(rawStars);
    } else {
        stars = std::move(rawStars);
    }
    rep.starsDetected = static_cast<int>(stars.size());

    FloatImageRGB aligned;
    std::vector<uint8_t> validMask;
    float alignMatrix[6] = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f};

    if (refStars_.size() >= 4) {
        AlignResult ar = alignStarsTriangleRansac(refStars_, stars);
        rep.starsMatched = ar.starsMatched;
        rep.residualPx = ar.residualPx;

        if (!ar.success || ar.residualPx > maxResidualPx_) {
            rejected_++;
            rep.accepted = false;
            rep.totalAccepted = accepted_;
            rep.totalRejected = rejected_;
            return rep;
        }
        for (int k = 0; k < 6; ++k) alignMatrix[k] = ar.matrix[k];
        warpImageAffine(frame, ar.matrix, width_, height_, aligned, validMask);
        alignedCount_++;
        sumResidual_ += ar.residualPx;
    } else {
        // Fallback when scene has < 4 stars (e.g., dense clouds / testing flat scene): direct stack
        aligned = frame;
        validMask.assign(frame.pixelCount(), 1);
        rep.starsMatched = 0;
        rep.residualPx = 0.0f;
    }

    accepted_++;
    rep.accepted = true;

    // Ground-locked foreground accumulation (unshifted so mountains/trees stay razor-sharp!)
    if (hasForeground_ && fgCount_ > 0) {
        const size_t n = frame.pixelCount();
        for (size_t i = 0; i < n; ++i) {
            fgSumR_[i] += frame.r[i];
            fgSumG_[i] += frame.g[i];
            fgSumB_[i] += frame.b[i];
        }
        fgCount_++;
    }

    std::vector<uint8_t> drizzleRejMask;

    if (!seeded_) {
        CompactFrame16 cf;
        cf.width = width_;
        cf.height = height_;
        packFloatToU16(aligned, cf.maxScale, cf.r, cf.g, cf.b);
        cf.mask = std::move(validMask);
        initialFrames_.push_back(std::move(cf));
        if (initialFrames_.size() == 3) {
            seedFromInitialFrames();
        }
    } else {
        // Streaming Welford + Kappa-Sigma outlier rejection (constant memory for 100+ frames!)
        const size_t n = static_cast<size_t>(width_) * static_cast<size_t>(height_);
        const float floorR = skySigmaR_ * 1.15f;
        const float floorG = skySigmaG_ * 1.15f;
        const float floorB = skySigmaB_ * 1.15f;
        int64_t frameRejPx = 0;
        if (drizzle2x_) drizzleRejMask.assign(n, 0);

        for (size_t i = 0; i < n; ++i) {
            if (!validMask[i]) continue;
            const uint16_t c = count_[i];
            const float vr = aligned.r[i];
            const float vg = aligned.g[i];
            const float vb = aligned.b[i];

            if (c == 0) {
                meanR_[i] = vr; meanG_[i] = vg; meanB_[i] = vb;
                m2R_[i] = 0.0f; m2G_[i] = 0.0f; m2B_[i] = 0.0f;
                count_[i] = 1;
                continue;
            }

            if (c >= 2) {
                const float invDf = 1.0f / static_cast<float>(c - 1);
                // Add small proportional tolerance (3% of signal) to preserve bright star peaks under seeing jitter
                const float sigR = std::max({std::sqrt(m2R_[i] * invDf), floorR, meanR_[i] * 0.03f});
                const float sigG = std::max({std::sqrt(m2G_[i] * invDf), floorG, meanG_[i] * 0.03f});
                const float sigB = std::max({std::sqrt(m2B_[i] * invDf), floorB, meanB_[i] * 0.03f});

                const bool outR = std::abs(vr - meanR_[i]) > kappa_ * sigR;
                const bool outG = std::abs(vg - meanG_[i]) > kappa_ * sigG;
                const bool outB = std::abs(vb - meanB_[i]) > kappa_ * sigB;
                if (outR || outG || outB) {
                    frameRejPx++;
                    if (drizzle2x_) drizzleRejMask[i] = 1;
                    continue;
                }
            }

            const uint16_t nc = static_cast<uint16_t>(c + 1);
            const float invNc = 1.0f / static_cast<float>(nc);

            const float dR1 = vr - meanR_[i];
            meanR_[i] += dR1 * invNc;
            m2R_[i] += dR1 * (vr - meanR_[i]);

            const float dG1 = vg - meanG_[i];
            meanG_[i] += dG1 * invNc;
            m2G_[i] += dG1 * (vg - meanG_[i]);

            const float dB1 = vb - meanB_[i];
            meanB_[i] += dB1 * invNc;
            m2B_[i] += dB1 * (vb - meanB_[i]);

            count_[i] = nc;
        }
        totalRejectedPixels_ += frameRejPx;
    }

    if (drizzle2x_) {
        drizzle_.addFrame(frame, alignMatrix, drizzleRejMask.empty() ? nullptr : &drizzleRejMask);
    }

    rep.totalAccepted = accepted_;
    rep.totalRejected = rejected_;
    rep.rejectedPixels = totalRejectedPixels_;
    return rep;
}

void DeepSkyStacker::getCurrentImage(FloatImageRGB& out) const {
    if (width_ <= 0 || height_ <= 0) {
        out.clear();
        return;
    }

    int scale = 1;
    if (drizzle2x_ && drizzle_.frameCount() > 0) {
        drizzle_.finalize(out);
        scale = 2;
    } else {
        const size_t n = static_cast<size_t>(width_) * static_cast<size_t>(height_);
        out.resize(width_, height_);

        if (seeded_) {
            for (size_t i = 0; i < n; ++i) {
                if (count_[i] > 0) {
                    out.r[i] = std::max(0.0f, meanR_[i]);
                    out.g[i] = std::max(0.0f, meanG_[i]);
                    out.b[i] = std::max(0.0f, meanB_[i]);
                }
            }
        } else if (!initialFrames_.empty()) {
            const int nf = static_cast<int>(initialFrames_.size());
            for (size_t i = 0; i < n; ++i) {
                float sr = 0, sg = 0, sb = 0;
                int cnt = 0;
                for (int f = 0; f < nf; ++f) {
                    if (initialFrames_[f].mask[i]) {
                        const float sc = initialFrames_[f].maxScale / 65535.0f;
                        sr += initialFrames_[f].r[i] * sc;
                        sg += initialFrames_[f].g[i] * sc;
                        sb += initialFrames_[f].b[i] * sc;
                        cnt++;
                    }
                }
                if (cnt > 0) {
                    const float inv = 1.0f / static_cast<float>(cnt);
                    out.r[i] = sr * inv;
                    out.g[i] = sg * inv;
                    out.b[i] = sb * inv;
                }
            }
        }
    }

    // Seamless 16-pixel feathered cosine blend between star-aligned sky and ground-locked foreground
    if (hasForeground_ && fgCount_ > 0 && horizonY_.size() == static_cast<size_t>(width_) && out.valid()) {
        const float invFg = 1.0f / static_cast<float>(fgCount_);
        const int ow = out.width;
        const int oh = out.height;
        const float feather = 16.0f * static_cast<float>(scale);
        const float halfFeather = 0.5f * feather;

        auto sampleFg = [&](float bx, float by, float& r, float& g, float& b) {
            const float cx = std::clamp(bx, 0.0f, static_cast<float>(width_ - 1));
            const float cy = std::clamp(by, 0.0f, static_cast<float>(height_ - 1));
            const int x0 = static_cast<int>(cx);
            const int y0 = static_cast<int>(cy);
            const int x1 = std::min(x0 + 1, width_ - 1);
            const int y1 = std::min(y0 + 1, height_ - 1);
            const float fx = cx - x0;
            const float fy = cy - y0;
            const float w00 = (1.0f - fx) * (1.0f - fy);
            const float w10 = fx * (1.0f - fy);
            const float w01 = (1.0f - fx) * fy;
            const float w11 = fx * fy;
            const size_t i00 = static_cast<size_t>(y0) * width_ + x0;
            const size_t i10 = static_cast<size_t>(y0) * width_ + x1;
            const size_t i01 = static_cast<size_t>(y1) * width_ + x0;
            const size_t i11 = static_cast<size_t>(y1) * width_ + x1;
            r = (w00 * fgSumR_[i00] + w10 * fgSumR_[i10] + w01 * fgSumR_[i01] + w11 * fgSumR_[i11]) * invFg;
            g = (w00 * fgSumG_[i00] + w10 * fgSumG_[i10] + w01 * fgSumG_[i01] + w11 * fgSumG_[i11]) * invFg;
            b = (w00 * fgSumB_[i00] + w10 * fgSumB_[i10] + w01 * fgSumB_[i01] + w11 * fgSumB_[i11]) * invFg;
        };

        for (int y = 0; y < oh; ++y) {
            const size_t row = static_cast<size_t>(y) * ow;
            for (int x = 0; x < ow; ++x) {
                const int bx = std::clamp(x / scale, 0, width_ - 1);
                const float hY = horizonY_[bx] * static_cast<float>(scale);
                const float t = std::clamp((static_cast<float>(y) - (hY - halfFeather)) / feather, 0.0f, 1.0f);
                if (t <= 0.0f) continue;

                const float fgW = 0.5f * (1.0f - std::cos(kPiStack * t));
                const float skyW = 1.0f - fgW;
                const size_t oi = row + x;

                float fr, fg, fb;
                if (scale == 1) {
                    fr = fgSumR_[oi] * invFg;
                    fg = fgSumG_[oi] * invFg;
                    fb = fgSumB_[oi] * invFg;
                } else {
                    sampleFg((static_cast<float>(x) - 0.5f) * 0.5f, (static_cast<float>(y) - 0.5f) * 0.5f, fr, fg, fb);
                }

                out.r[oi] = out.r[oi] * skyW + fr * fgW;
                out.g[oi] = out.g[oi] * skyW + fg * fgW;
                out.b[oi] = out.b[oi] * skyW + fb * fgW;
            }
        }
    }
}

// =========================================================================
// LuckyImagingStacker — Sharpest Frame Selection + Sub-Pixel NCC + Drizzle 2x + Wavelets
// =========================================================================

void LuckyImagingStacker::reset(int targetFrames, float keepPercent, float waveletStrength, bool enableDrizzle2x) {
    const int desired = static_cast<int>(std::ceil(std::max(1, targetFrames) * std::clamp(keepPercent, 0.05f, 1.0f)));
    maxKeep_ = std::clamp(desired, 3, 25);
    waveletStrength_ = std::clamp(waveletStrength, 0.0f, 3.0f);
    drizzle2x_ = enableDrizzle2x;
    received_ = 0;
    pool_.clear();
}

FrameStackReport LuckyImagingStacker::addFrame(const FloatImageRGB& rawFrame) {
    FrameStackReport rep{};
    received_++;
    rep.totalReceived = received_;
    if (!rawFrame.valid()) {
        rep.totalAccepted = static_cast<int>(pool_.size());
        rep.totalRejected = received_ - rep.totalAccepted;
        return rep;
    }

    FloatImageRGB resizedFrame;
    const FloatImageRGB* framePtr = &rawFrame;
    if (drizzle2x_ && rawFrame.width > 2200) {
        downsampleToMaxWidth(rawFrame, 1600, resizedFrame);
        framePtr = &resizedFrame;
    }
    const FloatImageRGB& frame = *framePtr;

    const float sharpness = computeSharpnessTenengrad(frame);
    rep.sharpnessScore = sharpness;

    if (static_cast<int>(pool_.size()) < maxKeep_) {
        ScoredFrame sf;
        sf.sharpness = sharpness;
        sf.width = frame.width;
        sf.height = frame.height;
        packFloatToU16(frame, sf.maxScale, sf.r, sf.g, sf.b);
        pool_.push_back(std::move(sf));
        rep.accepted = true;
    } else {
        auto worstIt = std::min_element(
            pool_.begin(), pool_.end(),
            [](const ScoredFrame& a, const ScoredFrame& b) { return a.sharpness < b.sharpness; }
        );
        if (worstIt != pool_.end() && sharpness > worstIt->sharpness) {
            worstIt->sharpness = sharpness;
            worstIt->width = frame.width;
            worstIt->height = frame.height;
            packFloatToU16(frame, worstIt->maxScale, worstIt->r, worstIt->g, worstIt->b);
            rep.accepted = true;
        }
    }

    rep.totalAccepted = static_cast<int>(pool_.size());
    rep.totalRejected = received_ - rep.totalAccepted;
    return rep;
}

bool LuckyImagingStacker::finalize(FloatImageRGB& out) {
    if (pool_.empty()) {
        out.clear();
        return false;
    }

    // Sort descending by sharpness so pool_[0] is the sharpest reference frame
    std::sort(pool_.begin(), pool_.end(),
              [](const ScoredFrame& a, const ScoredFrame& b) { return a.sharpness > b.sharpness; });

    FloatImageRGB ref;
    unpackU16ToFloat(pool_[0].width, pool_[0].height, pool_[0].maxScale,
                     pool_[0].r, pool_[0].g, pool_[0].b, ref);

    const int w = ref.width;
    const int h = ref.height;

    if (drizzle2x_) {
        Drizzle2xAccumulator drizzle;
        drizzle.reset(w, h, 0.72f);
        static constexpr float kIdentity[6] = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f};
        drizzle.addFrame(ref, kIdentity, nullptr);

        FloatImageRGB src;
        for (size_t f = 1; f < pool_.size(); ++f) {
            if (pool_[f].width != w || pool_[f].height != h) continue;
            unpackU16ToFloat(pool_[f].width, pool_[f].height, pool_[f].maxScale,
                             pool_[f].r, pool_[f].g, pool_[f].b, src);
            AlignResult ar = alignPlanetaryTranslation(ref, src, 64);
            if (!ar.success) continue;
            drizzle.addFrame(src, ar.matrix, nullptr);
        }
        drizzle.finalize(out);
    } else {
        const size_t n = ref.pixelCount();
        std::vector<double> sumR(n, 0.0), sumG(n, 0.0), sumB(n, 0.0);
        std::vector<uint16_t> wgt(n, 0);

        for (size_t i = 0; i < n; ++i) {
            sumR[i] = ref.r[i];
            sumG[i] = ref.g[i];
            sumB[i] = ref.b[i];
            wgt[i] = 1;
        }

        FloatImageRGB src, warped;
        std::vector<uint8_t> mask;
        for (size_t f = 1; f < pool_.size(); ++f) {
            if (pool_[f].width != w || pool_[f].height != h) continue;
            unpackU16ToFloat(pool_[f].width, pool_[f].height, pool_[f].maxScale,
                             pool_[f].r, pool_[f].g, pool_[f].b, src);
            AlignResult ar = alignPlanetaryTranslation(ref, src, 64);
            if (!ar.success) continue;
            warpImageAffine(src, ar.matrix, w, h, warped, mask);
            for (size_t i = 0; i < n; ++i) {
                if (mask[i]) {
                    sumR[i] += warped.r[i];
                    sumG[i] += warped.g[i];
                    sumB[i] += warped.b[i];
                    wgt[i]++;
                }
            }
        }

        out.resize(w, h);
        for (size_t i = 0; i < n; ++i) {
            if (wgt[i] > 0) {
                const float inv = 1.0f / static_cast<float>(wgt[i]);
                out.r[i] = static_cast<float>(sumR[i]) * inv;
                out.g[i] = static_cast<float>(sumG[i]) * inv;
                out.b[i] = static_cast<float>(sumB[i]) * inv;
            }
        }
    }

    if (waveletStrength_ > 0.01f) {
        applyATrousWavelets(out,
                            waveletStrength_ * 0.9f,
                            waveletStrength_ * 0.65f,
                            waveletStrength_ * 0.35f,
                            true);
    }
    pool_.clear();
    pool_.shrink_to_fit();
    return true;
}

// =========================================================================
// HdrAstroStacker — 32-Bit Linear Radiance Multi-Bracket HDR Fusion Engine
// =========================================================================

void HdrAstroStacker::reset(int expectedBrackets) {
    expectedBrackets_ = std::clamp(expectedBrackets, 2, 7);
    brackets_.clear();
}

FrameStackReport HdrAstroStacker::addBracket(const FloatImageRGB& frame, float evMultiplier) {
    FrameStackReport rep{};
    if (!frame.valid() || evMultiplier <= 1e-5f) {
        rep.totalReceived = static_cast<int>(brackets_.size());
        rep.totalAccepted = static_cast<int>(brackets_.size());
        return rep;
    }

    if (!brackets_.empty() &&
        (frame.width != brackets_[0].img.width || frame.height != brackets_[0].img.height)) {
        rep.totalReceived = static_cast<int>(brackets_.size()) + 1;
        rep.totalAccepted = static_cast<int>(brackets_.size());
        rep.totalRejected = 1;
        return rep;
    }

    BracketFrame bf;
    bf.img = frame;
    bf.evMultiplier = evMultiplier;
    brackets_.push_back(std::move(bf));

    rep.accepted = true;
    rep.totalReceived = static_cast<int>(brackets_.size());
    rep.totalAccepted = static_cast<int>(brackets_.size());
    return rep;
}

bool HdrAstroStacker::finalize(FloatImageRGB& out) {
    if (brackets_.empty()) {
        out.clear();
        return false;
    }
    if (brackets_.size() == 1) {
        out = brackets_[0].img;
        brackets_.clear();
        return true;
    }

    // Sort brackets ascending by exposure multiplier (shortest -> longest)
    std::sort(brackets_.begin(), brackets_.end(),
              [](const BracketFrame& a, const BracketFrame& b) {
                  return a.evMultiplier < b.evMultiplier;
              });

    const size_t nBrackets = brackets_.size();
    const size_t refIdx = nBrackets / 2;
    const FloatImageRGB& refImg = brackets_[refIdx].img;
    const float refEv = std::max(1e-5f, brackets_[refIdx].evMultiplier);
    const int w = refImg.width;
    const int h = refImg.height;
    const size_t n = refImg.pixelCount();

    const auto refStars = detectStars(refImg, 60);
    const bool useStarAlign = (refStars.size() >= 6);

    // Align every bracket to the middle reference frame
    std::vector<FloatImageRGB> aligned(nBrackets);
    std::vector<std::vector<uint8_t>> masks(nBrackets);
    std::vector<float> relGains(nBrackets, 1.0f);

    for (size_t k = 0; k < nBrackets; ++k) {
        relGains[k] = std::max(1e-4f, brackets_[k].evMultiplier / refEv);
        if (k == refIdx) {
            aligned[k] = refImg;
            masks[k].assign(n, 1);
            continue;
        }

        bool alignedOk = false;
        AlignResult ar{};
        if (useStarAlign) {
            auto srcStars = detectStars(brackets_[k].img, 60);
            if (srcStars.size() >= 4) {
                ar = alignStarsTriangleRansac(refStars, srcStars);
                if (ar.success && ar.residualPx <= 4.0f) {
                    alignedOk = true;
                }
            }
        }
        if (!alignedOk) {
            // Exposure-normalized luminance match for bright Moon / Orion core translation alignment
            FloatImageRGB srcNorm;
            srcNorm.resize(w, h);
            const float invG = 1.0f / relGains[k];
            for (size_t i = 0; i < n; ++i) {
                srcNorm.r[i] = std::clamp(brackets_[k].img.r[i] * invG, 0.0f, 1.0f);
                srcNorm.g[i] = std::clamp(brackets_[k].img.g[i] * invG, 0.0f, 1.0f);
                srcNorm.b[i] = std::clamp(brackets_[k].img.b[i] * invG, 0.0f, 1.0f);
            }
            ar = alignPlanetaryTranslation(refImg, srcNorm, 48);
            if (ar.success) {
                alignedOk = true;
            }
        }

        if (alignedOk) {
            warpImageAffine(brackets_[k].img, ar.matrix, w, h, aligned[k], masks[k]);
        } else {
            aligned[k] = brackets_[k].img;
            masks[k].assign(n, 1);
        }
    }

    // Fuse in linear 32-bit float radiance space with smooth saturation-aware Gaussian weighting
    out.resize(w, h);
    for (size_t i = 0; i < n; ++i) {
        double sumR = 0.0, sumG = 0.0, sumB = 0.0, sumW = 0.0;

        for (size_t k = 0; k < nBrackets; ++k) {
            if (!masks[k][i]) continue;
            const float r = aligned[k].r[i];
            const float g = aligned[k].g[i];
            const float b = aligned[k].b[i];
            const float peakCh = std::max(r, std::max(g, b));
            const float relG = relGains[k];

            // Smooth Gaussian saturation cutoff above 0.82 so blown cores hand off seamlessly to shorter brackets
            float satW = 1.0f;
            if (peakCh > 0.82f) {
                if (peakCh >= 0.985f && k > 0) {
                    satW = 0.0f;
                } else {
                    const float d = (peakCh - 0.82f) / 0.055f;
                    satW = std::exp(-d * d);
                }
            }
            if (satW <= 1e-6f) continue;

            // Shadow SNR boost: longer exposures (higher relG) have dramatically lower read noise in dark nebulae
            const float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            const float snrTrust = std::pow(relG, 1.45f);
            // Dark-zone preference for long exposures (<0.25) + smooth midtone Gaussian bell
            const float darkBoost = (luma < 0.25f) ? (1.0f + 1.5f * std::max(0.0f, relG - 1.0f)) : 1.0f;
            const float wellExposed = 0.20f + 0.80f * std::exp(-std::pow((std::clamp(peakCh, 0.0f, 1.0f) - 0.42f) / 0.42f, 2.0f));
            const double wgt = static_cast<double>(satW * snrTrust * darkBoost * wellExposed);

            const double invG = 1.0 / static_cast<double>(relG);
            sumR += wgt * (static_cast<double>(r) * invG);
            sumG += wgt * (static_cast<double>(g) * invG);
            sumB += wgt * (static_cast<double>(b) * invG);
            sumW += wgt;
        }

        if (sumW > 1e-9) {
            const double invW = 1.0 / sumW;
            out.r[i] = static_cast<float>(std::max(0.0, sumR * invW));
            out.g[i] = static_cast<float>(std::max(0.0, sumG * invW));
            out.b[i] = static_cast<float>(std::max(0.0, sumB * invW));
        } else {
            // If even the shortest bracket clipped, preserve shortest bracket linear radiance
            const float invShortest = 1.0f / relGains[0];
            out.r[i] = aligned[0].r[i] * invShortest;
            out.g[i] = aligned[0].g[i] * invShortest;
            out.b[i] = aligned[0].b[i] * invShortest;
        }
    }

    brackets_.clear();
    brackets_.shrink_to_fit();
    return true;
}

// =========================================================================
// StarTrailsStacker — Real-Time Lighten & Comet Mode
// =========================================================================

void StarTrailsStacker::reset(bool cometMode, float decayFactor) {
    cometMode_ = cometMode;
    decayFactor_ = std::clamp(decayFactor, 0.80f, 0.998f);
    count_ = 0;
    accum_.clear();
}

FrameStackReport StarTrailsStacker::addFrame(const FloatImageRGB& frame) {
    FrameStackReport rep{};
    if (!frame.valid()) {
        rep.totalReceived = count_;
        rep.totalAccepted = count_;
        return rep;
    }

    if (count_ == 0) {
        accum_ = frame;
    } else if (frame.width == accum_.width && frame.height == accum_.height) {
        const size_t n = accum_.pixelCount();
        if (cometMode_) {
            const float dec = decayFactor_;
            for (size_t i = 0; i < n; ++i) {
                accum_.r[i] = std::max(accum_.r[i] * dec, frame.r[i]);
                accum_.g[i] = std::max(accum_.g[i] * dec, frame.g[i]);
                accum_.b[i] = std::max(accum_.b[i] * dec, frame.b[i]);
            }
        } else {
            for (size_t i = 0; i < n; ++i) {
                accum_.r[i] = std::max(accum_.r[i], frame.r[i]);
                accum_.g[i] = std::max(accum_.g[i], frame.g[i]);
                accum_.b[i] = std::max(accum_.b[i], frame.b[i]);
            }
        }
    } else {
        return rep;
    }

    count_++;
    rep.accepted = true;
    rep.totalReceived = count_;
    rep.totalAccepted = count_;
    return rep;
}

// =========================================================================
// CalibrationBuilder — Master Dark / Flat / Bias (Pixelwise Median)
// =========================================================================

void CalibrationBuilder::reset(
    CalibrationType type, int width, int height, int channels, CfaPattern cfa,
    float iso, float exposureSec, float focalMm
) {
    type_ = type;
    width_ = width;
    height_ = height;
    channels_ = channels;
    cfa_ = cfa;
    iso_ = iso;
    exposureSec_ = exposureSec;
    focalMm_ = focalMm;
    frames_.clear();
}

void CalibrationBuilder::addBayerFrame(const uint8_t* rawBytes, const RawParams& params) {
    if (!rawBytes || params.width != width_ || params.height != height_ || channels_ != 1) return;
    std::vector<float> bayer;
    calibrateRawToFloat(rawBytes, params, nullptr, nullptr, nullptr, bayer);
    if (bayer.size() == static_cast<size_t>(width_) * height_) {
        frames_.push_back(std::move(bayer));
    }
}

void CalibrationBuilder::addRgbFrame(const FloatImageRGB& rgb) {
    if (!rgb.valid() || rgb.width != width_ || rgb.height != height_ || channels_ != 3) return;
    const size_t plane = rgb.pixelCount();
    std::vector<float> packed(plane * 3);
    std::copy(rgb.r.begin(), rgb.r.end(), packed.begin());
    std::copy(rgb.g.begin(), rgb.g.end(), packed.begin() + plane);
    std::copy(rgb.b.begin(), rgb.b.end(), packed.begin() + 2 * plane);
    frames_.push_back(std::move(packed));
}

CalibrationMaster CalibrationBuilder::build(const CalibrationMaster* masterBias) const {
    CalibrationMaster master;
    master.width = width_;
    master.height = height_;
    master.channels = channels_;
    master.cfa = cfa_;
    master.type = type_;
    master.iso = iso_;
    master.exposureSec = exposureSec_;
    master.focalMm = focalMm_;
    master.frameCount = static_cast<int>(frames_.size());

    if (frames_.empty() || width_ <= 0 || height_ <= 0) return master;

    const size_t total = static_cast<size_t>(width_) * height_ * channels_;
    master.data.resize(total, 0.0f);

    const size_t nf = frames_.size();
    std::vector<float> col(nf);
    const size_t mid = nf / 2;

    for (size_t i = 0; i < total; ++i) {
        for (size_t f = 0; f < nf; ++f) {
            col[f] = frames_[f][i];
        }
        std::nth_element(col.begin(), col.begin() + mid, col.end());
        float val = col[mid];
        if ((nf & 1) == 0 && nf >= 2) {
            const float prevMax = *std::max_element(col.begin(), col.begin() + mid);
            val = 0.5f * (val + prevMax);
        }
        master.data[i] = val;
    }

    if (type_ == CalibrationType::FLAT) {
        // Subtract optional master bias before normalizing flat field
        if (masterBias && masterBias->valid() &&
            masterBias->width == width_ && masterBias->height == height_ &&
            masterBias->channels == channels_) {
            for (size_t i = 0; i < total; ++i) {
                master.data[i] = std::max(0.001f, master.data[i] - masterBias->data[i]);
            }
        }

        // Normalize per CFA sub-channel (for Bayer) or per RGB plane using central 40% median
        const int x0 = width_ * 3 / 10;
        const int x1 = width_ * 7 / 10;
        const int y0 = height_ * 3 / 10;
        const int y1 = height_ * 7 / 10;

        if (channels_ == 1) {
            float chMed[4] = {1.0f, 1.0f, 1.0f, 1.0f};
            for (int c = 0; c < 4; ++c) {
                const int cx = c & 1;
                const int cy = (c >> 1) & 1;
                std::vector<float> s;
                for (int y = y0; y < y1; ++y) {
                    if ((y & 1) != cy) continue;
                    for (int x = x0; x < x1; ++x) {
                        if ((x & 1) != cx) continue;
                        s.push_back(master.data[static_cast<size_t>(y) * width_ + x]);
                    }
                }
                if (!s.empty()) {
                    std::nth_element(s.begin(), s.begin() + s.size() / 2, s.end());
                    chMed[c] = std::max(0.005f, s[s.size() / 2]);
                }
            }
            for (int y = 0; y < height_; ++y) {
                const int yBit = (y & 1) << 1;
                for (int x = 0; x < width_; ++x) {
                    const int c = yBit | (x & 1);
                    const size_t idx = static_cast<size_t>(y) * width_ + x;
                    master.data[idx] = std::clamp(master.data[idx] / chMed[c], 0.10f, 2.0f);
                }
            }
        } else {
            const size_t plane = static_cast<size_t>(width_) * height_;
            for (int ch = 0; ch < 3; ++ch) {
                std::vector<float> s;
                for (int y = y0; y < y1; ++y) {
                    for (int x = x0; x < x1; ++x) {
                        s.push_back(master.data[ch * plane + static_cast<size_t>(y) * width_ + x]);
                    }
                }
                float med = 1.0f;
                if (!s.empty()) {
                    std::nth_element(s.begin(), s.begin() + s.size() / 2, s.end());
                    med = std::max(0.005f, s[s.size() / 2]);
                }
                for (size_t i = 0; i < plane; ++i) {
                    master.data[ch * plane + i] = std::clamp(master.data[ch * plane + i] / med, 0.10f, 2.0f);
                }
            }
        }
    }

    return master;
}

} // namespace astro
