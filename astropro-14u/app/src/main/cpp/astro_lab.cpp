#include "astro_types.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <vector>

namespace astro {

namespace {

float computeChannelMedianAndMad(const std::vector<float>& ch, float& outMad) {
    if (ch.empty()) {
        outMad = 0.001f;
        return 0.0f;
    }
    const size_t step = std::max<size_t>(1, ch.size() / 8192);
    std::vector<float> s;
    s.reserve(ch.size() / step + 1);
    for (size_t i = 0; i < ch.size(); i += step) s.push_back(ch[i]);
    const size_t mid = s.size() / 2;
    std::nth_element(s.begin(), s.begin() + mid, s.end());
    const float med = s[mid];
    for (float& v : s) v = std::abs(v - med);
    std::nth_element(s.begin(), s.begin() + mid, s.end());
    outMad = std::max(1.4826f * s[mid], 1e-4f);
    return med;
}

inline float mtf(float m, float x) {
    if (x <= 0.0f) return 0.0f;
    if (x >= 1.0f) return 1.0f;
    if (std::abs(m - 0.5f) < 1e-5f) return x;
    const float denom = (2.0f * m - 1.0f) * x - m;
    if (std::abs(denom) < 1e-7f) return x;
    return std::clamp(((m - 1.0f) * x) / denom, 0.0f, 1.0f);
}

bool solveLinear6x6(double A[6][6], double b[6], double x[6]) {
    double M[6][7];
    for (int i = 0; i < 6; ++i) {
        for (int j = 0; j < 6; ++j) M[i][j] = A[i][j];
        M[i][i] += 1e-6; // Mild Tikhonov regularization for numerical stability
        M[i][6] = b[i];
    }

    for (int col = 0; col < 6; ++col) {
        int pivot = col;
        double maxAbs = std::abs(M[col][col]);
        for (int row = col + 1; row < 6; ++row) {
            if (std::abs(M[row][col]) > maxAbs) {
                maxAbs = std::abs(M[row][col]);
                pivot = row;
            }
        }
        if (maxAbs < 1e-12) return false;
        if (pivot != col) {
            for (int k = col; k < 7; ++k) std::swap(M[col][k], M[pivot][k]);
        }
        const double diag = M[col][col];
        for (int row = col + 1; row < 6; ++row) {
            const double factor = M[row][col] / diag;
            for (int k = col; k < 7; ++k) {
                M[row][k] -= factor * M[col][k];
            }
        }
    }

    for (int i = 5; i >= 0; --i) {
        double sum = M[i][6];
        for (int j = i + 1; j < 6; ++j) sum -= M[i][j] * x[j];
        x[i] = sum / M[i][i];
    }
    return true;
}

void fitAndSubtractChannelGradient(std::vector<float>& ch, int w, int h, float strength) {
    constexpr int kGx = 12;
    constexpr int kGy = 9;
    struct Sample { double u, v, val; bool active; };
    std::vector<Sample> samples;
    samples.reserve(kGx * kGy);

    std::vector<float> patch;
    for (int gy = 0; gy < kGy; ++gy) {
        const int y0 = (gy * h) / kGy;
        const int y1 = ((gy + 1) * h) / kGy;
        for (int gx = 0; gx < kGx; ++gx) {
            const int x0 = (gx * w) / kGx;
            const int x1 = ((gx + 1) * w) / kGx;
            const int step = std::max(1, std::min(x1 - x0, y1 - y0) / 16);
            patch.clear();
            for (int y = y0; y < y1; y += step) {
                for (int x = x0; x < x1; x += step) {
                    patch.push_back(ch[static_cast<size_t>(y) * w + x]);
                }
            }
            if (patch.empty()) continue;
            // Use 30th percentile of patch so stars inside box do not lift sky background sample
            const size_t qIdx = patch.size() * 3 / 10;
            std::nth_element(patch.begin(), patch.begin() + qIdx, patch.end());
            const double cx = 0.5 * (x0 + x1);
            const double cy = 0.5 * (y0 + y1);
            const double u = (2.0 * cx / static_cast<double>(w)) - 1.0;
            const double v = (2.0 * cy / static_cast<double>(h)) - 1.0;
            samples.push_back({u, v, static_cast<double>(patch[qIdx]), true});
        }
    }
    if (samples.size() < 8) return;

    // Fit 2D quadratic surface c0 + c1*u + c2*v + c3*u^2 + c4*u*v + c5*v^2 in 2 sigma-clipped passes
    double c[6] = {};
    for (int pass = 0; pass < 2; ++pass) {
        double A[6][6] = {};
        double rhs[6] = {};
        int activeCnt = 0;
        for (const auto& s : samples) {
            if (!s.active) continue;
            activeCnt++;
            const double phi[6] = {1.0, s.u, s.v, s.u * s.u, s.u * s.v, s.v * s.v};
            for (int r = 0; r < 6; ++r) {
                rhs[r] += phi[r] * s.val;
                for (int k = 0; k < 6; ++k) A[r][k] += phi[r] * phi[k];
            }
        }
        if (activeCnt < 8 || !solveLinear6x6(A, rhs, c)) return;

        if (pass == 0) {
            std::vector<float> res;
            res.reserve(samples.size());
            for (const auto& s : samples) {
                const double pred = c[0] + c[1] * s.u + c[2] * s.v +
                                    c[3] * s.u * s.u + c[4] * s.u * s.v + c[5] * s.v * s.v;
                res.push_back(static_cast<float>(s.val - pred));
            }
            float mad = 0.001f;
            const float med = computeChannelMedianAndMad(res, mad);
            for (size_t i = 0; i < samples.size(); ++i) {
                // Reject bright nebula/star-contaminated boxes above fit + 2.2*MAD
                if (res[i] > med + 2.2f * mad) {
                    samples[i].active = false;
                }
            }
        }
    }

    // Compute minimum surface value so subtraction flattens sky without clipping dark corners
    double minSurf = 1e9;
    for (int gy = 0; gy <= 8; ++gy) {
        const double v = -1.0 + 0.25 * gy;
        for (int gx = 0; gx <= 8; ++gx) {
            const double u = -1.0 + 0.25 * gx;
            const double surf = c[0] + c[1] * u + c[2] * v + c[3] * u * u + c[4] * u * v + c[5] * v * v;
            minSurf = std::min(minSurf, surf);
        }
    }
    const double pedestal = std::max(0.0, minSurf);

    const double invW = 2.0 / static_cast<double>(w);
    const double invH = 2.0 / static_cast<double>(h);
    for (int y = 0; y < h; ++y) {
        const double v = (y + 0.5) * invH - 1.0;
        const double c2v = c[2] * v + c[5] * v * v;
        const double c4v = c[4] * v;
        const size_t row = static_cast<size_t>(y) * w;
        for (int x = 0; x < w; ++x) {
            const double u = (x + 0.5) * invW - 1.0;
            const double surf = c[0] + (c[1] + c4v) * u + c[3] * u * u + c2v;
            const float delta = static_cast<float>(surf - pedestal) * strength;
            ch[row + x] = std::max(0.0f, ch[row + x] - delta);
        }
    }
}

void convolveB3SplineSeparable(const std::vector<float>& src, int w, int h, int step,
                               std::vector<float>& tmp, std::vector<float>& dst) {
    constexpr float k0 = 1.0f / 16.0f;
    constexpr float k1 = 4.0f / 16.0f;
    constexpr float k2 = 6.0f / 16.0f;

    const size_t n = static_cast<size_t>(w) * h;
    tmp.resize(n);
    dst.resize(n);

    // Horizontal pass
    for (int y = 0; y < h; ++y) {
        const float* sRow = src.data() + static_cast<size_t>(y) * w;
        float* tRow = tmp.data() + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; ++x) {
            const int xm2 = std::max(0, x - 2 * step);
            const int xm1 = std::max(0, x - step);
            const int xp1 = std::min(w - 1, x + step);
            const int xp2 = std::min(w - 1, x + 2 * step);
            tRow[x] = k0 * (sRow[xm2] + sRow[xp2]) + k1 * (sRow[xm1] + sRow[xp1]) + k2 * sRow[x];
        }
    }

    // Vertical pass
    for (int y = 0; y < h; ++y) {
        const int ym2 = std::max(0, y - 2 * step);
        const int ym1 = std::max(0, y - step);
        const int yp1 = std::min(h - 1, y + step);
        const int yp2 = std::min(h - 1, y + 2 * step);
        const float* rM2 = tmp.data() + static_cast<size_t>(ym2) * w;
        const float* rM1 = tmp.data() + static_cast<size_t>(ym1) * w;
        const float* r0  = tmp.data() + static_cast<size_t>(y) * w;
        const float* rP1 = tmp.data() + static_cast<size_t>(yp1) * w;
        const float* rP2 = tmp.data() + static_cast<size_t>(yp2) * w;
        float* dRow = dst.data() + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; ++x) {
            dRow[x] = k0 * (rM2[x] + rP2[x]) + k1 * (rM1[x] + rP1[x]) + k2 * r0[x];
        }
    }
}

inline uint8_t floatToSrgbByte(float v, bool applyGamma) {
    const float c = std::clamp(v, 0.0f, 1.0f);
    float s = c;
    if (applyGamma) {
        s = (c <= 0.0031308f) ? (12.92f * c) : (1.055f * std::pow(c, 1.0f / 2.4f) - 0.055f);
    }
    return static_cast<uint8_t>(std::clamp(s * 255.0f + 0.5f, 0.0f, 255.0f));
}

} // namespace

void applyQuadraticBgRemoval(FloatImageRGB& img, float strength) {
    if (!img.valid() || strength <= 0.001f) return;
    const float s = std::clamp(strength, 0.0f, 1.0f);
    fitAndSubtractChannelGradient(img.r, img.width, img.height, s);
    fitAndSubtractChannelGradient(img.g, img.width, img.height, s);
    fitAndSubtractChannelGradient(img.b, img.width, img.height, s);
}

void applyBackgroundNeutralization(FloatImageRGB& img) {
    if (!img.valid()) return;
    float madR, madG, madB;
    const float mR = computeChannelMedianAndMad(img.r, madR);
    const float mG = computeChannelMedianAndMad(img.g, madG);
    const float mB = computeChannelMedianAndMad(img.b, madB);
    const float target = std::min({mR, mG, mB});

    const float dR = mR - target;
    const float dG = mG - target;
    const float dB = mB - target;
    const size_t n = img.pixelCount();
    for (size_t i = 0; i < n; ++i) {
        img.r[i] = std::max(0.0f, img.r[i] - dR);
        img.g[i] = std::max(0.0f, img.g[i] - dG);
        img.b[i] = std::max(0.0f, img.b[i] - dB);
    }
}

void applyScnrGreen(FloatImageRGB& img, float strength) {
    if (!img.valid() || strength <= 0.001f) return;
    const float s = std::clamp(strength, 0.0f, 1.0f);
    const size_t n = img.pixelCount();
    for (size_t i = 0; i < n; ++i) {
        const float avgRB = 0.5f * (img.r[i] + img.b[i]);
        if (img.g[i] > avgRB) {
            img.g[i] -= s * (img.g[i] - avgRB);
        }
    }
}

void applyATrousWavelets(FloatImageRGB& img, float fineGain, float medGain, float coarseGain, bool fiveScale) {
    if (!img.valid()) return;
    if (std::abs(fineGain) < 0.01f && std::abs(medGain) < 0.01f && std::abs(coarseGain) < 0.01f) return;

    const int w = img.width;
    const int h = img.height;
    const size_t n = img.pixelCount();

    std::vector<float> luma(n);
    for (size_t i = 0; i < n; ++i) {
        luma[i] = 0.2126f * img.r[i] + 0.7152f * img.g[i] + 0.0722f * img.b[i];
    }

    const int numScales = fiveScale ? 5 : 3;
    const float gains[5] = {
        1.0f + fineGain,
        1.0f + medGain,
        1.0f + coarseGain,
        1.0f + coarseGain * 0.6f,
        1.0f + coarseGain * 0.3f
    };

    std::vector<float> cur = luma;
    std::vector<float> smooth(n), tmp(n), accum(n, 0.0f);

    for (int s = 0; s < numScales; ++s) {
        const int step = 1 << s;
        convolveB3SplineSeparable(cur, w, h, step, tmp, smooth);
        const float g = gains[s];
        for (size_t i = 0; i < n; ++i) {
            const float detail = cur[i] - smooth[i];
            accum[i] += detail * g;
        }
        cur.swap(smooth);
    }

    // Add residual coarse plane and reconstruct luminance ratio into RGB cleanly
    for (size_t i = 0; i < n; ++i) {
        const float newL = std::max(0.0f, cur[i] + accum[i]);
        const float oldL = luma[i];
        if (oldL > 1e-5f) {
            const float ratio = std::clamp(newL / oldL, 0.25f, 3.5f);
            img.r[i] = std::clamp(img.r[i] * ratio, 0.0f, 1.0f);
            img.g[i] = std::clamp(img.g[i] * ratio, 0.0f, 1.0f);
            img.b[i] = std::clamp(img.b[i] * ratio, 0.0f, 1.0f);
        }
    }
}

void applyBilateralDenoise(FloatImageRGB& img, float strength) {
    if (!img.valid() || strength <= 0.01f) return;
    const int w = img.width;
    const int h = img.height;
    const size_t n = img.pixelCount();
    const float s = std::clamp(strength, 0.0f, 1.0f);

    // Estimate noise scale from green channel MAD so stars/edges stay crisp while dark sky smooths
    float madG = 0.01f;
    computeChannelMedianAndMad(img.g, madG);
    const float sigmaRange = std::max(0.012f, madG * (1.5f + 3.0f * s));
    const float invTwoSigR2 = 1.0f / (2.0f * sigmaRange * sigmaRange);

    static constexpr float kSpatial[5] = {0.25f, 0.70f, 1.0f, 0.70f, 0.25f};

    std::vector<float> outR(n), outG(n), outB(n);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const size_t ci = static_cast<size_t>(y) * w + x;
            const float cr = img.r[ci], cg = img.g[ci], cb = img.b[ci];
            const float cL = 0.2126f * cr + 0.7152f * cg + 0.0722f * cb;

            float wSum = 0.0f, sR = 0.0f, sG = 0.0f, sB = 0.0f;
            for (int dy = -2; dy <= 2; ++dy) {
                const int ny = std::clamp(y + dy, 0, h - 1);
                const float wy = kSpatial[dy + 2];
                const size_t nRow = static_cast<size_t>(ny) * w;
                for (int dx = -2; dx <= 2; ++dx) {
                    const int nx = std::clamp(x + dx, 0, w - 1);
                    const size_t ni = nRow + nx;
                    const float nr = img.r[ni], ng = img.g[ni], nb = img.b[ni];
                    const float nL = 0.2126f * nr + 0.7152f * ng + 0.0722f * nb;
                    const float dL = nL - cL;
                    const float wgt = wy * kSpatial[dx + 2] * std::exp(-(dL * dL) * invTwoSigR2);
                    wSum += wgt;
                    sR += wgt * nr;
                    sG += wgt * ng;
                    sB += wgt * nb;
                }
            }
            const float invW = 1.0f / std::max(wSum, 1e-6f);
            outR[ci] = cr * (1.0f - s) + (sR * invW) * s;
            outG[ci] = cg * (1.0f - s) + (sG * invW) * s;
            outB[ci] = cb * (1.0f - s) + (sB * invW) * s;
        }
    }
    img.r.swap(outR);
    img.g.swap(outG);
    img.b.swap(outB);
}

namespace {

inline float sampleBilinearPlane(const std::vector<float>& ch, int w, int h, float x, float y) {
    const float cx = std::clamp(x, 0.0f, static_cast<float>(w - 1));
    const float cy = std::clamp(y, 0.0f, static_cast<float>(h - 1));
    const int x0 = static_cast<int>(cx);
    const int y0 = static_cast<int>(cy);
    const int x1 = std::min(x0 + 1, w - 1);
    const int y1 = std::min(y0 + 1, h - 1);
    const float fx = cx - x0;
    const float fy = cy - y0;
    const size_t r0 = static_cast<size_t>(y0) * w;
    const size_t r1 = static_cast<size_t>(y1) * w;
    const float top = ch[r0 + x0] * (1.0f - fx) + ch[r0 + x1] * fx;
    const float bot = ch[r1 + x0] * (1.0f - fx) + ch[r1 + x1] * fx;
    return top * (1.0f - fy) + bot * fy;
}

void convolveGaussianSeparable(
    const std::vector<float>& src, int w, int h,
    const std::vector<float>& kernel, int rad,
    std::vector<float>& tmp, std::vector<float>& dst
) {
    const size_t n = static_cast<size_t>(w) * static_cast<size_t>(h);
    tmp.resize(n);
    dst.resize(n);

    // Horizontal pass
    for (int y = 0; y < h; ++y) {
        const float* sRow = src.data() + static_cast<size_t>(y) * w;
        float* tRow = tmp.data() + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; ++x) {
            float acc = 0.0f;
            for (int k = -rad; k <= rad; ++k) {
                const int sx = std::clamp(x + k, 0, w - 1);
                acc += kernel[static_cast<size_t>(k + rad)] * sRow[sx];
            }
            tRow[x] = acc;
        }
    }

    // Vertical pass
    for (int y = 0; y < h; ++y) {
        float* dRow = dst.data() + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; ++x) {
            float acc = 0.0f;
            for (int k = -rad; k <= rad; ++k) {
                const int sy = std::clamp(y + k, 0, h - 1);
                acc += kernel[static_cast<size_t>(k + rad)] * tmp[static_cast<size_t>(sy) * w + x];
            }
            dRow[x] = acc;
        }
    }
}

} // namespace

void applyChromaticAberrationFix(FloatImageRGB& img, float strength) {
    if (!img.valid() || img.width < 16 || img.height < 16 || strength <= 0.005f) return;
    const float s = std::clamp(strength, 0.0f, 1.0f);
    const int w = img.width;
    const int h = img.height;
    const size_t n = img.pixelCount();

    float madG = 0.005f;
    const float medG = computeChannelMedianAndMad(img.g, madG);
    float maxG = 0.01f;
    for (size_t i = 0; i < n; ++i) {
        if (img.g[i] > maxG) maxG = img.g[i];
    }

    // -----------------------------------------------------------------
    // Stage 1: Automatic Sub-Pixel Radial Lateral CA Estimation & Realignment
    // -----------------------------------------------------------------
    const float cx = 0.5f * static_cast<float>(w - 1);
    const float cy = 0.5f * static_cast<float>(h - 1);
    const float maxR = std::max(1.0f, std::hypot(cx, cy));

    struct EdgeSample {
        float x, y;
        float ux, uy;
        float dG;
    };
    std::vector<EdgeSample> samples;
    samples.reserve(900);

    const float gradFloor = std::max(3.5f * madG, (maxG - medG) * 0.035f);
    const int step = std::max(1, std::min(w, h) / 140);

    for (int y = 4; y < h - 4; y += step) {
        for (int x = 4; x < w - 4; x += step) {
            const float dx = static_cast<float>(x) - cx;
            const float dy = static_cast<float>(y) - cy;
            const float r = std::hypot(dx, dy);
            if (r < 0.28f * maxR || r > 0.96f * maxR) continue;
            const float invR = 1.0f / r;
            const float ux = dx * invR;
            const float uy = dy * invR;
            const float gP = sampleBilinearPlane(img.g, w, h, x + ux, y + uy);
            const float gM = sampleBilinearPlane(img.g, w, h, x - ux, y - uy);
            const float dG = gP - gM;
            if (std::abs(dG) > gradFloor) {
                samples.push_back({static_cast<float>(x), static_cast<float>(y), ux, uy, dG});
                if (samples.size() >= 900) break;
            }
        }
        if (samples.size() >= 900) break;
    }

    auto estimateRadialDelta = [&](const std::vector<float>& ch) -> float {
        if (samples.size() < 12) return 0.0f;

        auto evalCorr = [&](float delta) -> double {
            double dot = 0.0, normG = 0.0, normC = 0.0;
            const float scale = 1.0f + delta;
            for (const auto& sp : samples) {
                const float sx = cx + (sp.x - cx) * scale;
                const float sy = cy + (sp.y - cy) * scale;
                if (sx < 2.0f || sx > w - 3.0f || sy < 2.0f || sy > h - 3.0f) continue;
                const float cP = sampleBilinearPlane(ch, w, h, sx + sp.ux, sy + sp.uy);
                const float cM = sampleBilinearPlane(ch, w, h, sx - sp.ux, sy - sp.uy);
                const float dC = cP - cM;
                dot += static_cast<double>(sp.dG) * static_cast<double>(dC);
                normG += static_cast<double>(sp.dG) * static_cast<double>(sp.dG);
                normC += static_cast<double>(dC) * static_cast<double>(dC);
            }
            const double denom = std::sqrt(normG * normC);
            return denom > 1e-12 ? (dot / denom) : 0.0;
        };

        constexpr int kSteps = 17;
        constexpr float kStepSize = 0.002f; // searches [-1.6% .. +1.6%] radial scale
        double scores[kSteps];
        int bestIdx = kSteps / 2;
        double bestScore = -2.0;

        for (int i = 0; i < kSteps; ++i) {
            const float delta = (i - kSteps / 2) * kStepSize;
            scores[i] = evalCorr(delta);
            if (scores[i] > bestScore) {
                bestScore = scores[i];
                bestIdx = i;
            }
        }

        const double zeroScore = scores[kSteps / 2];
        if (bestScore < 0.35 || bestScore <= zeroScore + 0.0015) {
            return 0.0f;
        }

        float bestDelta = (bestIdx - kSteps / 2) * kStepSize;
        if (bestIdx > 0 && bestIdx < kSteps - 1) {
            const double yM = scores[bestIdx - 1];
            const double y0 = scores[bestIdx];
            const double yP = scores[bestIdx + 1];
            const double den = yM - 2.0 * y0 + yP;
            if (den < -1e-10) {
                const float off = std::clamp(static_cast<float>(0.5 * (yM - yP) / den), -0.5f, 0.5f);
                bestDelta += off * kStepSize;
            }
        }
        return std::clamp(bestDelta, -0.018f, 0.018f);
    };

    const float deltaR = estimateRadialDelta(img.r) * s;
    const float deltaB = estimateRadialDelta(img.b) * s;

    if (std::abs(deltaR) > 1e-4f || std::abs(deltaB) > 1e-4f) {
        std::vector<float> newR(n), newB(n);
        const float scaleR = 1.0f + deltaR;
        const float scaleB = 1.0f + deltaB;
        for (int y = 0; y < h; ++y) {
            const float dy = static_cast<float>(y) - cy;
            const float syR = cy + dy * scaleR;
            const float syB = cy + dy * scaleB;
            const size_t row = static_cast<size_t>(y) * w;
            for (int x = 0; x < w; ++x) {
                const float dx = static_cast<float>(x) - cx;
                newR[row + x] = (std::abs(deltaR) > 1e-4f)
                    ? sampleBilinearPlane(img.r, w, h, cx + dx * scaleR, syR)
                    : img.r[row + x];
                newB[row + x] = (std::abs(deltaB) > 1e-4f)
                    ? sampleBilinearPlane(img.b, w, h, cx + dx * scaleB, syB)
                    : img.b[row + x];
            }
        }
        img.r.swap(newR);
        img.b.swap(newB);
    }

    // -----------------------------------------------------------------
    // Stage 2: High-Contrast Axial Purple/Magenta & Green Fringe Suppression
    // -----------------------------------------------------------------
    std::vector<float> edgeMag(n, 0.0f);
    for (int y = 1; y < h - 1; ++y) {
        const size_t rPrev = static_cast<size_t>(y - 1) * w;
        const size_t rCurr = static_cast<size_t>(y) * w;
        const size_t rNext = static_cast<size_t>(y + 1) * w;
        for (int x = 1; x < w - 1; ++x) {
            const float gx = -img.g[rPrev + x - 1] + img.g[rPrev + x + 1]
                             - 2.0f * img.g[rCurr + x - 1] + 2.0f * img.g[rCurr + x + 1]
                             - img.g[rNext + x - 1] + img.g[rNext + x + 1];
            const float gy = -img.g[rPrev + x - 1] - 2.0f * img.g[rPrev + x] - img.g[rPrev + x + 1]
                             + img.g[rNext + x - 1] + 2.0f * img.g[rNext + x] + img.g[rNext + x + 1];
            edgeMag[rCurr + x] = 0.25f * std::hypot(gx, gy);
        }
    }

    const float spanG = std::max(0.02f, maxG - medG);
    const float edgeLo = std::max(2.5f * madG, spanG * 0.020f);
    const float edgeHi = std::max(10.0f * madG, spanG * 0.12f);
    const float invEdgeSpan = 1.0f / std::max(1e-5f, edgeHi - edgeLo);
    const float fringeFloor = std::max(1.8f * madG, spanG * 0.012f);

    for (int y = 1; y < h - 1; ++y) {
        for (int x = 1; x < w - 1; ++x) {
            // 3x3 max edge strength catches fringes right outside high-contrast star & lunar edges
            float localEdge = 0.0f;
            for (int dy = -1; dy <= 1; ++dy) {
                const size_t row = static_cast<size_t>(y + dy) * w;
                for (int dx = -1; dx <= 1; ++dx) {
                    localEdge = std::max(localEdge, edgeMag[row + (x + dx)]);
                }
            }
            const float edgeW = std::clamp((localEdge - edgeLo) * invEdgeSpan, 0.0f, 1.0f);
            if (edgeW <= 0.01f) continue;

            const size_t idx = static_cast<size_t>(y) * w + x;
            float r = img.r[idx];
            float g = img.g[idx];
            float b = img.b[idx];

            // Axial purple/magenta fringe (both R and B exceed G; preserves pure red Ha & pure blue nebulae!)
            const float minRB = std::min(r, b);
            const float purpleExcess = minRB - g;
            if (purpleExcess > fringeFloor) {
                const float pull = s * edgeW * 0.88f;
                r = std::max(0.0f, r - purpleExcess * pull);
                b = std::max(0.0f, b - purpleExcess * pull);
                g = g + purpleExcess * pull * 0.25f;
            } else {
                // Green axial fringe on opposite focus side
                const float maxRB = std::max(r, b);
                const float greenExcess = g - maxRB;
                if (greenExcess > fringeFloor) {
                    g = std::max(0.0f, g - greenExcess * (s * edgeW * 0.72f));
                }
            }

            img.r[idx] = r;
            img.g[idx] = g;
            img.b[idx] = b;
        }
    }
}

void applyRichardsonLucyDeconv(FloatImageRGB& img, float strength, float psfSigma) {
    if (!img.valid() || img.width < 8 || img.height < 8 || strength <= 0.005f) return;
    const float s = std::clamp(strength, 0.0f, 1.0f);
    const int w = img.width;
    const int h = img.height;
    const size_t n = img.pixelCount();

    const int iters = std::clamp(static_cast<int>(std::round(1.0f + s * 5.0f)), 1, 6);
    const float sigma = std::clamp(psfSigma, 0.65f, 2.5f);
    const int rad = std::clamp(static_cast<int>(std::ceil(sigma * 3.0f)), 2, 6);

    // Normalized 1D separable Gaussian PSF kernel
    std::vector<float> kernel(static_cast<size_t>(2 * rad + 1));
    float kSum = 0.0f;
    const float inv2s2 = 1.0f / (2.0f * sigma * sigma);
    for (int k = -rad; k <= rad; ++k) {
        const float v = std::exp(-(static_cast<float>(k * k)) * inv2s2);
        kernel[static_cast<size_t>(k + rad)] = v;
        kSum += v;
    }
    for (float& kv : kernel) kv /= kSum;

    // Extract luminance L0
    std::vector<float> luma0(n);
    for (size_t i = 0; i < n; ++i) {
        luma0[i] = std::max(0.0f, 0.2126f * img.r[i] + 0.7152f * img.g[i] + 0.0722f * img.b[i]);
    }

    // Automatic sky-noise protection damping mask (smooth ramp above median + 2.5*MAD)
    float bgMad = 0.002f;
    const float bgMed = computeChannelMedianAndMad(luma0, bgMad);
    const float maskLo = bgMed + 2.5f * bgMad;
    const float maskHi = bgMed + 5.5f * bgMad;
    const float invMaskSpan = 1.0f / std::max(1e-5f, maskHi - maskLo);

    std::vector<float> dampMask(n, 0.0f);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            float lMax = 0.0f;
            for (int dy = -1; dy <= 1; ++dy) {
                const int ny = std::clamp(y + dy, 0, h - 1);
                const size_t row = static_cast<size_t>(ny) * w;
                for (int dx = -1; dx <= 1; ++dx) {
                    const int nx = std::clamp(x + dx, 0, w - 1);
                    lMax = std::max(lMax, luma0[row + nx]);
                }
            }
            const float t = std::clamp((lMax - maskLo) * invMaskSpan, 0.0f, 1.0f);
            dampMask[static_cast<size_t>(y) * w + x] = t * t * (3.0f - 2.0f * t);
        }
    }

    std::vector<float> est = luma0;
    std::vector<float> conv(n), ratio(n), corr(n), tmp(n);
    const float stepGain = 0.65f + 0.35f * s;

    for (int it = 0; it < iters; ++it) {
        convolveGaussianSeparable(est, w, h, kernel, rad, tmp, conv);
        for (size_t i = 0; i < n; ++i) {
            ratio[i] = std::clamp(luma0[i] / std::max(conv[i], 1e-5f), 0.15f, 4.5f);
        }
        convolveGaussianSeparable(ratio, w, h, kernel, rad, tmp, corr);
        for (size_t i = 0; i < n; ++i) {
            const float m = dampMask[i];
            if (m <= 1e-4f) continue;
            const float factor = std::clamp(1.0f + m * stepGain * (corr[i] - 1.0f), 0.35f, 2.2f);
            est[i] = std::max(0.0f, est[i] * factor);
        }
    }

    // Blend deconvolved luminance and reconstruct RGB preserving color ratios cleanly
    const float blend = std::clamp(0.35f + 0.65f * s, 0.0f, 1.0f);
    for (size_t i = 0; i < n; ++i) {
        const float oldL = luma0[i];
        if (oldL > 1e-5f) {
            const float newL = oldL * (1.0f - blend) + est[i] * blend;
            const float rMul = std::clamp(newL / oldL, 0.25f, 3.0f);
            img.r[i] = std::max(0.0f, img.r[i] * rMul);
            img.g[i] = std::max(0.0f, img.g[i] * rMul);
            img.b[i] = std::max(0.0f, img.b[i] * rMul);
        }
    }
}

void processAstroLab(
    const FloatImageRGB& inputLinear,
    const LabParams& params,
    FloatImageRGB& out
) {
    if (!inputLinear.valid()) {
        out.clear();
        return;
    }

    out = inputLinear;
    const size_t n = out.pixelCount();

    // 1. 2D Quadratic Polynomial Background Gradient Extraction (city light pollution removal)
    if (params.bgRemovalStrength > 0.005f) {
        applyQuadraticBgRemoval(out, params.bgRemovalStrength);
    }

    // 2. Background Neutralization
    if (params.bgNeutralize) {
        applyBackgroundNeutralization(out);
    }

    // 3. Linear Temperature & Tint shift
    if (std::abs(params.temperature) > 0.005f || std::abs(params.tint) > 0.005f) {
        const float rGain = 1.0f + 0.35f * params.temperature + 0.15f * params.tint;
        const float gGain = 1.0f - 0.25f * params.tint;
        const float bGain = 1.0f - 0.35f * params.temperature + 0.15f * params.tint;
        for (size_t i = 0; i < n; ++i) {
            out.r[i] = std::max(0.0f, out.r[i] * rGain);
            out.g[i] = std::max(0.0f, out.g[i] * gGain);
            out.b[i] = std::max(0.0f, out.b[i] * bGain);
        }
    }

    // 3b. Linear Regularized Richardson-Lucy Deconvolution (physically exact before non-linear stretch!)
    if (params.deconvolution > 0.005f) {
        applyRichardsonLucyDeconv(out, params.deconvolution, 1.15f);
    }

    // 3c. Linear Chromatic Aberration Fix (sub-pixel radial R/B alignment + purple/green fringe removal)
    if (params.chromaticAberrationFix > 0.005f) {
        applyChromaticAberrationFix(out, params.chromaticAberrationFix);
    }

    // 3d. Automatic HDR Linear Radiance Soft-Shoulder Compression (preserves >1.0 unclipped HDR nebula cores!)
    float maxLinear = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        maxLinear = std::max(maxLinear, std::max(out.r[i], std::max(out.g[i], out.b[i])));
    }
    if (maxLinear > 1.05f) {
        const float kHdr = 3.5f;
        const float invNorm = 1.0f / std::asinh(kHdr * maxLinear);
        for (size_t i = 0; i < n; ++i) {
            const float I = (out.r[i] + out.g[i] + out.b[i]) * (1.0f / 3.0f);
            if (I > 1e-7f) {
                const float factor = (std::asinh(kHdr * I) * invNorm) / I;
                out.r[i] = std::clamp(out.r[i] * factor, 0.0f, 1.0f);
                out.g[i] = std::clamp(out.g[i] * factor, 0.0f, 1.0f);
                out.b[i] = std::clamp(out.b[i] * factor, 0.0f, 1.0f);
            }
        }
    }

    // 4. Stretch stage: Auto-STF (PixInsight MTF) or Manual Black/White/MTF + Lupton Arcsinh
    float bp = std::clamp(params.blackPoint, 0.0f, 0.85f);
    float wp = std::clamp(params.whitePoint, bp + 0.05f, 1.0f);
    float mid = std::clamp(params.midtones, 0.002f, 0.998f);

    if (params.autoStf) {
        std::vector<float> luma(n);
        for (size_t i = 0; i < n; ++i) {
            luma[i] = 0.2126f * out.r[i] + 0.7152f * out.g[i] + 0.0722f * out.b[i];
        }
        float mad = 0.002f;
        const float med = computeChannelMedianAndMad(luma, mad);
        const float darkBias = std::clamp(params.blackPoint, 0.0f, 0.25f);
        const float sigmaMul = std::max(0.8f, 2.8f - darkBias * 16.0f);
        bp = std::clamp(med - sigmaMul * mad, 0.0f, 0.9f);
        wp = 1.0f;
        const float normMed = std::clamp((med - bp) / std::max(0.01f, wp - bp), 0.0005f, 0.99f);
        const float targetBg = std::clamp(0.18f - darkBias * 0.65f, 0.09f, 0.18f);
        // Exact PixInsight STF midtone balance: solve MTF(m, normMed) == targetBg
        mid = std::clamp(mtf(targetBg, normMed), 0.002f, 0.995f);
    }

    const float invSpan = 1.0f / std::max(1e-4f, wp - bp);
    const bool useAsinh = params.asinhBeta > 0.2f;
    const float beta = params.asinhBeta;
    const float invAsinhBeta = useAsinh ? (1.0f / std::asinh(beta)) : 1.0f;

    for (size_t i = 0; i < n; ++i) {
        float r = std::clamp((out.r[i] - bp) * invSpan, 0.0f, 1.0f);
        float g = std::clamp((out.g[i] - bp) * invSpan, 0.0f, 1.0f);
        float b = std::clamp((out.b[i] - bp) * invSpan, 0.0f, 1.0f);

        // Lupton Arcsinh color-preserving stretch
        if (useAsinh) {
            const float I = (r + g + b) * (1.0f / 3.0f);
            if (I > 1e-7f) {
                const float factor = (std::asinh(beta * I) * invAsinhBeta) / I;
                r = std::min(1.0f, r * factor);
                g = std::min(1.0f, g * factor);
                b = std::min(1.0f, b * factor);
            }
        }

        // Midtone Transfer Function
        out.r[i] = mtf(mid, r);
        out.g[i] = mtf(mid, g);
        out.b[i] = mtf(mid, b);
    }

    // 5. SCNR Green Cast Removal
    if (params.scnrStrength > 0.005f) {
        applyScnrGreen(out, params.scnrStrength);
    }

    // 6. 3-Scale À Trous B3-Spline Wavelet Sharpening
    if (std::abs(params.waveletFine) > 0.01f ||
        std::abs(params.waveletMedium) > 0.01f ||
        std::abs(params.waveletCoarse) > 0.01f) {
        applyATrousWavelets(out, params.waveletFine, params.waveletMedium, params.waveletCoarse, false);
    }

    // 7. Edge/Star-Preserving Bilateral Denoise
    if (params.denoiseStrength > 0.01f) {
        applyBilateralDenoise(out, params.denoiseStrength);
    }

    // 8. Luminance-Preserving Saturation
    if (std::abs(params.saturation - 1.0f) > 0.005f) {
        const float sat = std::clamp(params.saturation, 0.0f, 2.5f);
        for (size_t i = 0; i < n; ++i) {
            const float l = 0.2126f * out.r[i] + 0.7152f * out.g[i] + 0.0722f * out.b[i];
            out.r[i] = std::clamp(l + (out.r[i] - l) * sat, 0.0f, 1.0f);
            out.g[i] = std::clamp(l + (out.g[i] - l) * sat, 0.0f, 1.0f);
            out.b[i] = std::clamp(l + (out.b[i] - l) * sat, 0.0f, 1.0f);
        }
    }
}

void renderFloatRGBToRgba8(
    const FloatImageRGB& img,
    uint8_t* rgbaOut,
    int outW, int outH,
    bool applySrgbGamma,
    int histBins,
    int32_t* histR,
    int32_t* histG,
    int32_t* histB
) {
    if (!img.valid() || !rgbaOut || outW <= 0 || outH <= 0) return;

    const bool doHist = (histBins > 0 && histR && histG && histB);
    if (doHist) {
        std::memset(histR, 0, static_cast<size_t>(histBins) * sizeof(int32_t));
        std::memset(histG, 0, static_cast<size_t>(histBins) * sizeof(int32_t));
        std::memset(histB, 0, static_cast<size_t>(histBins) * sizeof(int32_t));
    }

    const int sw = img.width;
    const int sh = img.height;
    const float hScale = static_cast<float>(histBins) / 256.0f;

    for (int oy = 0; oy < outH; ++oy) {
        const int sy = std::clamp((oy * sh) / outH, 0, sh - 1);
        const size_t sRow = static_cast<size_t>(sy) * sw;
        uint8_t* dRow = rgbaOut + static_cast<size_t>(oy) * outW * 4;

        for (int ox = 0; ox < outW; ++ox) {
            const int sx = std::clamp((ox * sw) / outW, 0, sw - 1);
            const size_t si = sRow + sx;
            const uint8_t rb = floatToSrgbByte(img.r[si], applySrgbGamma);
            const uint8_t gb = floatToSrgbByte(img.g[si], applySrgbGamma);
            const uint8_t bb = floatToSrgbByte(img.b[si], applySrgbGamma);

            dRow[ox * 4 + 0] = rb;
            dRow[ox * 4 + 1] = gb;
            dRow[ox * 4 + 2] = bb;
            dRow[ox * 4 + 3] = 255;

            if (doHist && ((ox & 1) == 0) && ((oy & 1) == 0)) {
                histR[std::min(histBins - 1, static_cast<int>(rb * hScale))]++;
                histG[std::min(histBins - 1, static_cast<int>(gb * hScale))]++;
                histB[std::min(histBins - 1, static_cast<int>(bb * hScale))]++;
            }
        }
    }
}

} // namespace astro
