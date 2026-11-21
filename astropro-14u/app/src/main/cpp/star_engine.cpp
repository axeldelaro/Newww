#include "astro_types.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <numeric>
#include <vector>

namespace astro {

namespace {

constexpr float kPi = 3.14159265358979323846f;

float medianOf(std::vector<float>& vals) {
    if (vals.empty()) return 0.0f;
    const size_t mid = vals.size() / 2;
    std::nth_element(vals.begin(), vals.begin() + mid, vals.end());
    return vals[mid];
}

void computeMedianAndMad(const float* data, size_t n, float& outMedian, float& outSigma) {
    if (n == 0) {
        outMedian = 0.0f;
        outSigma = 0.001f;
        return;
    }
    const size_t step = std::max<size_t>(1, n / 4096);
    std::vector<float> samples;
    samples.reserve(n / step + 1);
    for (size_t i = 0; i < n; i += step) {
        samples.push_back(data[i]);
    }
    outMedian = medianOf(samples);
    std::vector<float> absDev(samples.size());
    for (size_t i = 0; i < samples.size(); ++i) {
        absDev[i] = std::abs(samples[i] - outMedian);
    }
    const float mad = medianOf(absDev);
    outSigma = std::max(1.4826f * mad, 1e-4f);
}

struct TriangleDesc {
    int i, j, k;       // Vertex indices: i opposite longest side a, j opposite b, k opposite c
    float a;           // Longest side length in px
    float r1;          // b / a
    float r2;          // c / a
    int chirality;     // +1 or -1 (signed cross product)
};

std::vector<TriangleDesc> buildTriangles(const std::vector<StarPoint>& stars, int maxN) {
    const int n = std::min(maxN, static_cast<int>(stars.size()));
    std::vector<TriangleDesc> tris;
    if (n < 3) return tris;
    tris.reserve(static_cast<size_t>(n) * (n - 1) * (n - 2) / 6);

    for (int p0 = 0; p0 < n; ++p0) {
        for (int p1 = p0 + 1; p1 < n; ++p1) {
            const float d01 = std::hypot(stars[p0].x - stars[p1].x, stars[p0].y - stars[p1].y);
            if (d01 < 12.0f) continue;
            for (int p2 = p1 + 1; p2 < n; ++p2) {
                const float d12 = std::hypot(stars[p1].x - stars[p2].x, stars[p1].y - stars[p2].y);
                const float d20 = std::hypot(stars[p2].x - stars[p0].x, stars[p2].y - stars[p0].y);
                if (d12 < 12.0f || d20 < 12.0f) continue;

                // Pair (opposite vertex, side length)
                std::array<std::pair<int, float>, 3> opp = {{
                    {p2, d01},
                    {p0, d12},
                    {p1, d20}
                }};
                // Sort descending by side length: a >= b >= c
                if (opp[0].second < opp[1].second) std::swap(opp[0], opp[1]);
                if (opp[1].second < opp[2].second) std::swap(opp[1], opp[2]);
                if (opp[0].second < opp[1].second) std::swap(opp[0], opp[1]);

                const float a = opp[0].second;
                const float b = opp[1].second;
                const float c = opp[2].second;
                if (a < 20.0f) continue;

                const float r1 = b / a;
                const float r2 = c / a;
                // Skip nearly isosceles (ambiguous vertex ordering) or nearly colinear triangles
                if ((1.0f - r1) < 0.025f || (r1 - r2) < 0.025f) continue;
                if ((r1 + r2) < 1.03f) continue;

                const int va = opp[0].first;
                const int vb = opp[1].first;
                const int vc = opp[2].first;
                const float cross = (stars[vb].x - stars[va].x) * (stars[vc].y - stars[va].y) -
                                    (stars[vb].y - stars[va].y) * (stars[vc].x - stars[va].x);
                if (std::abs(cross) < 40.0f) continue;

                tris.push_back(TriangleDesc{
                    va, vb, vc, a, r1, r2, (cross > 0.0f) ? 1 : -1
                });
            }
        }
    }
    return tris;
}

bool solveSimilarity2Points(
    float sx1, float sy1, float rx1, float ry1,
    float sx2, float sy2, float rx2, float ry2,
    float outM[6]
) {
    const float dsx = sx2 - sx1;
    const float dsy = sy2 - sy1;
    const float denom = dsx * dsx + dsy * dsy;
    if (denom < 100.0f) return false;

    const float drx = rx2 - rx1;
    const float dry = ry2 - ry1;
    const float a = (dsx * drx + dsy * dry) / denom;
    const float b = (dsx * dry - dsy * drx) / denom;

    const float scale = std::hypot(a, b);
    if (scale < 0.94f || scale > 1.06f) return false;

    const float tx = rx1 - (a * sx1 - b * sy1);
    const float ty = ry1 - (b * sx1 + a * sy1);

    outM[0] = a;  outM[1] = -b; outM[2] = tx;
    outM[3] = b;  outM[4] = a;  outM[5] = ty;
    return true;
}

bool fitSimilarityLeastSquares(
    const std::vector<std::pair<StarPoint, StarPoint>>& pairs,
    float outM[6],
    float& outRmsPx
) {
    if (pairs.size() < 2) return false;
    double msx = 0, msy = 0, mrx = 0, mry = 0;
    for (const auto& [r, s] : pairs) {
        msx += s.x; msy += s.y;
        mrx += r.x; mry += r.y;
    }
    const double invN = 1.0 / static_cast<double>(pairs.size());
    msx *= invN; msy *= invN;
    mrx *= invN; mry *= invN;

    double numA = 0, numB = 0, den = 0;
    for (const auto& [r, s] : pairs) {
        const double sx = s.x - msx;
        const double sy = s.y - msy;
        const double rx = r.x - mrx;
        const double ry = r.y - mry;
        numA += sx * rx + sy * ry;
        numB += sx * ry - sy * rx;
        den += sx * sx + sy * sy;
    }
    if (den < 1e-6) return false;

    const double a = numA / den;
    const double b = numB / den;
    const double tx = mrx - (a * msx - b * msy);
    const double ty = mry - (b * msx + a * msy);

    outM[0] = static_cast<float>(a);
    outM[1] = static_cast<float>(-b);
    outM[2] = static_cast<float>(tx);
    outM[3] = static_cast<float>(b);
    outM[4] = static_cast<float>(a);
    outM[5] = static_cast<float>(ty);

    double errSq = 0.0;
    for (const auto& [r, s] : pairs) {
        const double px = a * s.x - b * s.y + tx;
        const double py = b * s.x + a * s.y + ty;
        const double dx = px - r.x;
        const double dy = py - r.y;
        errSq += dx * dx + dy * dy;
    }
    outRmsPx = static_cast<float>(std::sqrt(errSq * invN));
    return true;
}

} // namespace

FocusMetrics measureFocusCrop(const float* luma, int w, int h) {
    FocusMetrics fm{};
    if (!luma || w < 16 || h < 16) return fm;

    const size_t n = static_cast<size_t>(w) * static_cast<size_t>(h);
    computeMedianAndMad(luma, n, fm.bgMedian, fm.bgStd);

    // Compute Tenengrad gradient energy (useful fallback for Moon / planetary surface focus)
    double gradSum = 0.0;
    for (int y = 1; y < h - 1; ++y) {
        for (int x = 1; x < w - 1; ++x) {
            const float gx =
                -luma[(y - 1) * w + (x - 1)] + luma[(y - 1) * w + (x + 1)]
                - 2.0f * luma[y * w + (x - 1)] + 2.0f * luma[y * w + (x + 1)]
                - luma[(y + 1) * w + (x - 1)] + luma[(y + 1) * w + (x + 1)];
            const float gy =
                -luma[(y - 1) * w + (x - 1)] - 2.0f * luma[(y - 1) * w + x] - luma[(y - 1) * w + (x + 1)]
                + luma[(y + 1) * w + (x - 1)] + 2.0f * luma[(y + 1) * w + x] + luma[(y + 1) * w + (x + 1)];
            gradSum += static_cast<double>(gx * gx + gy * gy);
        }
    }
    fm.tenengrad = static_cast<float>(gradSum / static_cast<double>((w - 2) * (h - 2)));

    // Locate brightest star in interior (with margin for aperture)
    const int margin = std::min(10, std::min(w, h) / 4);
    float bestPeak = -1.0f;
    int bestX = w / 2, bestY = h / 2;
    for (int y = margin; y < h - margin; ++y) {
        for (int x = margin; x < w - margin; ++x) {
            const float v = luma[static_cast<size_t>(y) * w + x];
            if (v > bestPeak) {
                bestPeak = v;
                bestX = x;
                bestY = y;
            }
        }
    }

    fm.peakValue = bestPeak;
    const float peakAboveBg = bestPeak - fm.bgMedian;
    fm.snr = peakAboveBg / std::max(fm.bgStd, 1e-4f);
    if (fm.snr < 5.0f || peakAboveBg <= 1e-4f) {
        fm.starFound = 0;
        return fm;
    }

    // Refine sub-pixel centroid iteratively (2 passes)
    float cx = static_cast<float>(bestX);
    float cy = static_cast<float>(bestY);
    const int apRad = std::min(12, margin);
    const float thresh = fm.bgMedian + 2.0f * fm.bgStd;

    for (int iter = 0; iter < 2; ++iter) {
        const int ix = static_cast<int>(std::round(cx));
        const int iy = static_cast<int>(std::round(cy));
        double sumW = 0.0, sumX = 0.0, sumY = 0.0;
        for (int dy = -apRad; dy <= apRad; ++dy) {
            const int py = iy + dy;
            if (py < 0 || py >= h) continue;
            for (int dx = -apRad; dx <= apRad; ++dx) {
                const int px = ix + dx;
                if (px < 0 || px >= w) continue;
                if (dx * dx + dy * dy > apRad * apRad) continue;
                const float v = luma[static_cast<size_t>(py) * w + px];
                if (v > thresh) {
                    const float wgt = v - fm.bgMedian;
                    sumW += wgt;
                    sumX += wgt * px;
                    sumY += wgt * py;
                }
            }
        }
        if (sumW > 1e-6) {
            cx = static_cast<float>(sumX / sumW);
            cy = static_cast<float>(sumY / sumW);
        }
    }

    fm.starX = cx;
    fm.starY = cy;
    fm.starFound = 1;

    // Collect aperture pixels sorted by radius for exact cumulative HFD & radial FWHM
    struct RadSample { float r; float flux; };
    std::vector<RadSample> samples;
    const int ix = static_cast<int>(std::round(cx));
    const int iy = static_cast<int>(std::round(cy));
    samples.reserve(static_cast<size_t>((2 * apRad + 1) * (2 * apRad + 1)));

    double totalFlux = 0.0;
    double m2 = 0.0;
    for (int dy = -apRad; dy <= apRad; ++dy) {
        const int py = iy + dy;
        if (py < 0 || py >= h) continue;
        for (int dx = -apRad; dx <= apRad; ++dx) {
            const int px = ix + dx;
            if (px < 0 || px >= w) continue;
            const float rx = static_cast<float>(px) - cx;
            const float ry = static_cast<float>(py) - cy;
            const float r2 = rx * rx + ry * ry;
            if (r2 > static_cast<float>(apRad * apRad)) continue;
            const float val = luma[static_cast<size_t>(py) * w + px] - fm.bgMedian;
            if (val > 1.5f * fm.bgStd) {
                const float r = std::sqrt(r2);
                samples.push_back({r, val});
                totalFlux += val;
                m2 += val * r2;
            }
        }
    }

    if (totalFlux <= 1e-6 || samples.empty()) {
        fm.starFound = 0;
        return fm;
    }

    std::sort(samples.begin(), samples.end(),
              [](const RadSample& a, const RadSample& b) { return a.r < b.r; });

    // Exact 50% cumulative flux radius -> HFD = 2 * R50
    const double halfFlux = 0.5 * totalFlux;
    double accum = 0.0;
    float r50 = 0.75f;
    for (size_t i = 0; i < samples.size(); ++i) {
        const double prev = accum;
        accum += samples[i].flux;
        if (accum >= halfFlux) {
            const float prevR = (i > 0) ? samples[i - 1].r : 0.0f;
            const double frac = (halfFlux - prev) / std::max(1e-9, static_cast<double>(samples[i].flux));
            r50 = prevR + static_cast<float>(frac) * (samples[i].r - prevR);
            break;
        }
    }
    fm.hfd = std::max(0.8f, 2.0f * r50);

    // Gaussian second-moment FWHM: for 2D isotropic Gaussian, E[r^2] = 2 * sigma^2 => sigma = sqrt(m2 / (2 * totalFlux))
    const float sigmaMom = std::sqrt(static_cast<float>(m2 / (2.0 * totalFlux)));
    const float fwhmMom = 2.35482f * sigmaMom;

    // Also measure direct half-maximum crossing radius from radial bins
    const float halfMax = 0.5f * peakAboveBg;
    float rHalfSum = 0.0f;
    int rHalfCount = 0;
    // Bin radial profile in 0.5 px rings
    constexpr int kBins = 24;
    float binSum[kBins] = {};
    int binCnt[kBins] = {};
    for (const auto& s : samples) {
        const int b = std::clamp(static_cast<int>(s.r * 2.0f), 0, kBins - 1);
        binSum[b] += s.flux;
        binCnt[b]++;
    }
    float prevMean = peakAboveBg;
    float prevRad = 0.0f;
    for (int b = 0; b < kBins; ++b) {
        if (binCnt[b] == 0) continue;
        const float mean = binSum[b] / static_cast<float>(binCnt[b]);
        const float rad = (b + 0.5f) * 0.5f;
        if (mean <= halfMax && prevMean > halfMax) {
            const float t = (prevMean - halfMax) / std::max(1e-6f, prevMean - mean);
            rHalfSum = prevRad + t * (rad - prevRad);
            rHalfCount = 1;
            break;
        }
        prevMean = mean;
        prevRad = rad;
    }

    if (rHalfCount > 0 && rHalfSum > 0.35f) {
        fm.fwhm = 0.5f * (2.0f * rHalfSum + fwhmMom);
    } else {
        fm.fwhm = std::max(0.8f, fwhmMom);
    }
    return fm;
}

std::vector<StarPoint> detectStars(const FloatImageRGB& img, int maxStars) {
    std::vector<StarPoint> stars;
    if (!img.valid() || img.width < 32 || img.height < 32) return stars;

    const int w = img.width;
    const int h = img.height;
    const size_t n = img.pixelCount();
    std::vector<float> luma(n);
    for (size_t i = 0; i < n; ++i) {
        luma[i] = 0.2126f * img.r[i] + 0.7152f * img.g[i] + 0.0722f * img.b[i];
    }

    // 8x6 tiled local sky background + MAD estimation (immune to light pollution gradients)
    constexpr int kGx = 8;
    constexpr int kGy = 6;
    float tileBg[kGy][kGx];
    float tileSig[kGy][kGx];
    std::vector<float> buf;

    for (int ty = 0; ty < kGy; ++ty) {
        const int y0 = (ty * h) / kGy;
        const int y1 = ((ty + 1) * h) / kGy;
        for (int tx = 0; tx < kGx; ++tx) {
            const int x0 = (tx * w) / kGx;
            const int x1 = ((tx + 1) * w) / kGx;
            const int step = std::max(1, std::min(x1 - x0, y1 - y0) / 24);
            buf.clear();
            for (int y = y0; y < y1; y += step) {
                for (int x = x0; x < x1; x += step) {
                    buf.push_back(luma[static_cast<size_t>(y) * w + x]);
                }
            }
            const float med = medianOf(buf);
            for (float& v : buf) v = std::abs(v - med);
            const float mad = medianOf(buf);
            tileBg[ty][tx] = med;
            tileSig[ty][tx] = std::max(1.4826f * mad, 1e-4f);
        }
    }

    auto sampleTile = [&](const float grid[kGy][kGx], int x, int y) -> float {
        const float fx = (static_cast<float>(x) + 0.5f) * kGx / static_cast<float>(w) - 0.5f;
        const float fy = (static_cast<float>(y) + 0.5f) * kGy / static_cast<float>(h) - 0.5f;
        const int x0 = std::clamp(static_cast<int>(std::floor(fx)), 0, kGx - 1);
        const int y0 = std::clamp(static_cast<int>(std::floor(fy)), 0, kGy - 1);
        const int x1 = std::min(x0 + 1, kGx - 1);
        const int y1 = std::min(y0 + 1, kGy - 1);
        const float tx = std::clamp(fx - x0, 0.0f, 1.0f);
        const float ty = std::clamp(fy - y0, 0.0f, 1.0f);
        const float top = grid[y0][x0] * (1.0f - tx) + grid[y0][x1] * tx;
        const float bot = grid[y1][x0] * (1.0f - tx) + grid[y1][x1] * tx;
        return top * (1.0f - ty) + bot * ty;
    };

    const int margin = 6;
    std::vector<StarPoint> candidates;
    candidates.reserve(512);

    for (int y = margin; y < h - margin; ++y) {
        for (int x = margin; x < w - margin; ++x) {
            const float v = luma[static_cast<size_t>(y) * w + x];
            const float bg = sampleTile(tileBg, x, y);
            const float sig = sampleTile(tileSig, x, y);
            if (v < bg + 6.0f * sig) continue;

            // Check strict local maximum in 5x5 neighborhood
            bool isMax = true;
            for (int dy = -2; dy <= 2 && isMax; ++dy) {
                for (int dx = -2; dx <= 2; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    const float nv = luma[static_cast<size_t>(y + dy) * w + (x + dx)];
                    if (nv > v || (nv == v && (dy < 0 || (dy == 0 && dx < 0)))) {
                        isMax = false;
                        break;
                    }
                }
            }
            if (!isMax) continue;

            // Reject single-pixel hot pixels: require at least 2 of 4 immediate neighbors above bg + 2*sig
            const float nThresh = bg + 2.0f * sig;
            int brightNeighbors = 0;
            if (luma[static_cast<size_t>(y) * w + (x - 1)] > nThresh) brightNeighbors++;
            if (luma[static_cast<size_t>(y) * w + (x + 1)] > nThresh) brightNeighbors++;
            if (luma[static_cast<size_t>(y - 1) * w + x] > nThresh) brightNeighbors++;
            if (luma[static_cast<size_t>(y + 1) * w + x] > nThresh) brightNeighbors++;
            if (brightNeighbors < 2) continue;

            // Sub-pixel intensity-weighted centroid in 9x9 aperture
            double sumW = 0.0, sumX = 0.0, sumY = 0.0, sumR2 = 0.0;
            for (int dy = -4; dy <= 4; ++dy) {
                for (int dx = -4; dx <= 4; ++dx) {
                    if (dx * dx + dy * dy > 18) continue;
                    const float pv = luma[static_cast<size_t>(y + dy) * w + (x + dx)] - bg;
                    if (pv > 2.0f * sig) {
                        sumW += pv;
                        sumX += pv * (x + dx);
                        sumY += pv * (y + dy);
                    }
                }
            }
            if (sumW <= 1e-6) continue;
            const float cx = static_cast<float>(sumX / sumW);
            const float cy = static_cast<float>(sumY / sumW);

            for (int dy = -4; dy <= 4; ++dy) {
                for (int dx = -4; dx <= 4; ++dx) {
                    if (dx * dx + dy * dy > 18) continue;
                    const float pv = luma[static_cast<size_t>(y + dy) * w + (x + dx)] - bg;
                    if (pv > 2.0f * sig) {
                        const float rx = (x + dx) - cx;
                        const float ry = (y + dy) - cy;
                        sumR2 += pv * (rx * rx + ry * ry);
                    }
                }
            }
            const float fwhm = 2.35482f * std::sqrt(static_cast<float>(sumR2 / (2.0 * sumW)));
            if (fwhm < 0.7f || fwhm > 16.0f) continue;

            StarPoint sp;
            sp.x = cx;
            sp.y = cy;
            sp.flux = static_cast<float>(sumW);
            sp.peak = v;
            sp.fwhm = fwhm;
            sp.hfd = fwhm;
            sp.snr = (v - bg) / sig;
            candidates.push_back(sp);
        }
    }

    // Sort by integrated flux descending and enforce minimum separation (8 px)
    std::sort(candidates.begin(), candidates.end(),
              [](const StarPoint& a, const StarPoint& b) { return a.flux > b.flux; });

    stars.reserve(static_cast<size_t>(maxStars));
    for (const auto& c : candidates) {
        bool tooClose = false;
        for (const auto& s : stars) {
            const float dx = c.x - s.x;
            const float dy = c.y - s.y;
            if (dx * dx + dy * dy < 64.0f) {
                tooClose = true;
                break;
            }
        }
        if (!tooClose) {
            stars.push_back(c);
            if (static_cast<int>(stars.size()) >= maxStars) break;
        }
    }
    return stars;
}

AlignResult alignStarsTriangleRansac(
    const std::vector<StarPoint>& refStars,
    const std::vector<StarPoint>& srcStars
) {
    AlignResult res{};
    res.starsDetected = static_cast<int>(srcStars.size());
    if (refStars.size() < 4 || srcStars.size() < 4) {
        return res;
    }

    constexpr int kMaxMatchStars = 32;
    const int nRef = std::min(kMaxMatchStars, static_cast<int>(refStars.size()));
    const int nSrc = std::min(kMaxMatchStars, static_cast<int>(srcStars.size()));

    auto refTris = buildTriangles(refStars, nRef);
    auto srcTris = buildTriangles(srcStars, nSrc);
    if (refTris.empty() || srcTris.empty()) return res;

    std::sort(refTris.begin(), refTris.end(),
              [](const TriangleDesc& a, const TriangleDesc& b) { return a.r1 < b.r1; });

    // Accumulate correspondence votes in nRef x nSrc matrix
    std::vector<int> votes(static_cast<size_t>(nRef) * nSrc, 0);
    constexpr float kRatioTol = 0.015f;

    for (const auto& st : srcTris) {
        auto it = std::lower_bound(
            refTris.begin(), refTris.end(), st.r1 - kRatioTol,
            [](const TriangleDesc& t, float val) { return t.r1 < val; }
        );
        for (; it != refTris.end() && it->r1 <= st.r1 + kRatioTol; ++it) {
            if (it->chirality != st.chirality) continue;
            if (std::abs(it->r2 - st.r2) > kRatioTol) continue;
            const float scaleRatio = it->a / st.a;
            if (scaleRatio < 0.95f || scaleRatio > 1.05f) continue;

            votes[static_cast<size_t>(it->i) * nSrc + st.i]++;
            votes[static_cast<size_t>(it->j) * nSrc + st.j]++;
            votes[static_cast<size_t>(it->k) * nSrc + st.k]++;
        }
    }

    // Collect top candidate pairs (refIdx, srcIdx)
    struct MatchPair { int refIdx; int srcIdx; int vote; };
    std::vector<MatchPair> cand;
    cand.reserve(static_cast<size_t>(nRef) * 2);

    for (int r = 0; r < nRef; ++r) {
        int bestS = -1, bestV = 0;
        for (int s = 0; s < nSrc; ++s) {
            const int v = votes[static_cast<size_t>(r) * nSrc + s];
            if (v > bestV) {
                bestV = v;
                bestS = s;
            }
        }
        if (bestS >= 0 && bestV >= 2) {
            cand.push_back({r, bestS, bestV});
        }
    }

    std::sort(cand.begin(), cand.end(),
              [](const MatchPair& a, const MatchPair& b) { return a.vote > b.vote; });
    if (cand.size() > 28) cand.resize(28);
    if (cand.size() < 3) return res;

    // Exhaustive/RANSAC pair hypothesis search over top voted correspondences
    float bestM[6] = {1, 0, 0, 0, 1, 0};
    int bestInliers = 0;
    float bestScoreErr = 1e9f;
    const int nCand = static_cast<int>(cand.size());
    constexpr float kInlierTolSq = 4.0f; // 2.0 px

    for (int i = 0; i < nCand; ++i) {
        for (int j = i + 1; j < nCand; ++j) {
            if (cand[i].refIdx == cand[j].refIdx || cand[i].srcIdx == cand[j].srcIdx) continue;
            float M[6];
            const auto& r1 = refStars[cand[i].refIdx];
            const auto& s1 = srcStars[cand[i].srcIdx];
            const auto& r2 = refStars[cand[j].refIdx];
            const auto& s2 = srcStars[cand[j].srcIdx];
            if (!solveSimilarity2Points(s1.x, s1.y, r1.x, r1.y, s2.x, s2.y, r2.x, r2.y, M)) continue;

            // Count inliers across ALL detected stars (nearest neighbor within 2.0px)
            int inliers = 0;
            float sumErrSq = 0.0f;
            for (const auto& s : srcStars) {
                const float px = M[0] * s.x + M[1] * s.y + M[2];
                const float py = M[3] * s.x + M[4] * s.y + M[5];
                float minD2 = kInlierTolSq;
                bool matched = false;
                for (const auto& r : refStars) {
                    const float dx = px - r.x;
                    const float dy = py - r.y;
                    const float d2 = dx * dx + dy * dy;
                    if (d2 < minD2) {
                        minD2 = d2;
                        matched = true;
                    }
                }
                if (matched) {
                    inliers++;
                    sumErrSq += minD2;
                }
            }

            if (inliers > bestInliers || (inliers == bestInliers && sumErrSq < bestScoreErr)) {
                bestInliers = inliers;
                bestScoreErr = sumErrSq;
                for (int k = 0; k < 6; ++k) bestM[k] = M[k];
            }
        }
    }

    if (bestInliers < 3) return res;

    // Refine with iterative nearest-neighbor least-squares similarity fit (2 passes)
    float curM[6];
    for (int k = 0; k < 6; ++k) curM[k] = bestM[k];
    std::vector<std::pair<StarPoint, StarPoint>> matchedPairs;
    float rmsPx = 0.0f;

    for (int pass = 0; pass < 2; ++pass) {
        const float tolSq = (pass == 0) ? 4.0f : 2.25f; // 2.0px then 1.5px
        matchedPairs.clear();
        std::vector<bool> refUsed(refStars.size(), false);
        for (const auto& s : srcStars) {
            const float px = curM[0] * s.x + curM[1] * s.y + curM[2];
            const float py = curM[3] * s.x + curM[4] * s.y + curM[5];
            int bestR = -1;
            float bestD2 = tolSq;
            for (size_t ri = 0; ri < refStars.size(); ++ri) {
                if (refUsed[ri]) continue;
                const float dx = px - refStars[ri].x;
                const float dy = py - refStars[ri].y;
                const float d2 = dx * dx + dy * dy;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestR = static_cast<int>(ri);
                }
            }
            if (bestR >= 0) {
                refUsed[bestR] = true;
                matchedPairs.push_back({refStars[bestR], s});
            }
        }
        if (matchedPairs.size() < 3) break;
        if (!fitSimilarityLeastSquares(matchedPairs, curM, rmsPx)) break;
    }

    if (matchedPairs.size() < 3) return res;

    res.success = true;
    for (int k = 0; k < 6; ++k) res.matrix[k] = curM[k];
    res.dx = curM[2];
    res.dy = curM[5];
    res.scale = std::hypot(curM[0], curM[3]);
    res.angleDeg = std::atan2(curM[3], curM[0]) * (180.0f / kPi);
    res.residualPx = rmsPx;
    res.starsMatched = static_cast<int>(matchedPairs.size());
    return res;
}

void warpImageAffine(
    const FloatImageRGB& src,
    const float matrix[6],
    int dstW, int dstH,
    FloatImageRGB& dst,
    std::vector<uint8_t>& validMask
) {
    dst.resize(dstW, dstH);
    validMask.assign(static_cast<size_t>(dstW) * dstH, 0);
    if (!src.valid()) return;

    // Invert 2x3 matrix (src -> dst) to get (dst -> src)
    const double a = matrix[0], b = matrix[1], tx = matrix[2];
    const double c = matrix[3], d = matrix[4], ty = matrix[5];
    const double det = a * d - b * c;
    if (std::abs(det) < 1e-8) return;
    const double invDet = 1.0 / det;

    const float ia = static_cast<float>(d * invDet);
    const float ib = static_cast<float>(-b * invDet);
    const float ic = static_cast<float>(-c * invDet);
    const float id = static_cast<float>(a * invDet);
    const float itx = static_cast<float>(-(ia * tx + ib * ty));
    const float ity = static_cast<float>(-(ic * tx + id * ty));

    const int sw = src.width;
    const int sh = src.height;
    const float maxX = static_cast<float>(sw - 2);
    const float maxY = static_cast<float>(sh - 2);

    for (int y = 0; y < dstH; ++y) {
        float sx = ib * y + itx;
        float sy = id * y + ity;
        const size_t rowOff = static_cast<size_t>(y) * dstW;

        for (int x = 0; x < dstW; ++x) {
            if (sx >= 0.0f && sy >= 0.0f && sx <= maxX && sy <= maxY) {
                const int x0 = static_cast<int>(sx);
                const int y0 = static_cast<int>(sy);
                const float fx = sx - x0;
                const float fy = sy - y0;
                const float w00 = (1.0f - fx) * (1.0f - fy);
                const float w10 = fx * (1.0f - fy);
                const float w01 = (1.0f - fx) * fy;
                const float w11 = fx * fy;

                const size_t i00 = static_cast<size_t>(y0) * sw + x0;
                const size_t i10 = i00 + 1;
                const size_t i01 = i00 + sw;
                const size_t i11 = i01 + 1;

                const size_t didx = rowOff + x;
                dst.r[didx] = w00 * src.r[i00] + w10 * src.r[i10] + w01 * src.r[i01] + w11 * src.r[i11];
                dst.g[didx] = w00 * src.g[i00] + w10 * src.g[i10] + w01 * src.g[i01] + w11 * src.g[i11];
                dst.b[didx] = w00 * src.b[i00] + w10 * src.b[i10] + w01 * src.b[i01] + w11 * src.b[i11];
                validMask[didx] = 1;
            }
            sx += ia;
            sy += ic;
        }
    }
}

float computeSharpnessTenengrad(const FloatImageRGB& img) {
    if (!img.valid() || img.width < 4 || img.height < 4) return 0.0f;
    const int w = img.width;
    const int h = img.height;
    const int step = std::max(1, std::min(w, h) / 360);
    double sum = 0.0;
    int64_t count = 0;

    auto lum = [&](int x, int y) {
        const size_t i = static_cast<size_t>(y) * w + x;
        return 0.2126f * img.r[i] + 0.7152f * img.g[i] + 0.0722f * img.b[i];
    };

    for (int y = step; y < h - step; y += step) {
        for (int x = step; x < w - step; x += step) {
            const float gx =
                -lum(x - step, y - step) + lum(x + step, y - step)
                - 2.0f * lum(x - step, y) + 2.0f * lum(x + step, y)
                - lum(x - step, y + step) + lum(x + step, y + step);
            const float gy =
                -lum(x - step, y - step) - 2.0f * lum(x, y - step) - lum(x + step, y - step)
                + lum(x - step, y + step) + 2.0f * lum(x, y + step) + lum(x + step, y + step);
            sum += static_cast<double>(gx * gx + gy * gy);
            count++;
        }
    }
    return count > 0 ? static_cast<float>(sum / static_cast<double>(count)) : 0.0f;
}

AlignResult alignPlanetaryTranslation(
    const FloatImageRGB& ref,
    const FloatImageRGB& src,
    int maxShift
) {
    AlignResult res{};
    if (!ref.valid() || !src.valid() || ref.width != src.width || ref.height != src.height) {
        return res;
    }

    const int w = ref.width;
    const int h = ref.height;
    // Build fast 2x or 4x downsampled luma maps for coarse-to-fine search
    const int ds = std::max(1, std::min(w, h) / 256);
    const int dw = w / ds;
    const int dh = h / ds;
    std::vector<float> rL(static_cast<size_t>(dw) * dh);
    std::vector<float> sL(static_cast<size_t>(dw) * dh);

    auto fillLuma = [&](const FloatImageRGB& im, std::vector<float>& out, float& cx, float& cy) {
        double sumW = 0, sumX = 0, sumY = 0;
        float maxV = 0.0f;
        for (int y = 0; y < dh; ++y) {
            for (int x = 0; x < dw; ++x) {
                const size_t i = static_cast<size_t>(y * ds) * w + (x * ds);
                const float v = 0.2126f * im.r[i] + 0.7152f * im.g[i] + 0.0722f * im.b[i];
                out[static_cast<size_t>(y) * dw + x] = v;
                if (v > maxV) maxV = v;
            }
        }
        const float thresh = maxV * 0.25f;
        for (int y = 0; y < dh; ++y) {
            for (int x = 0; x < dw; ++x) {
                const float v = out[static_cast<size_t>(y) * dw + x];
                if (v > thresh) {
                    sumW += v;
                    sumX += v * x;
                    sumY += v * y;
                }
            }
        }
        if (sumW > 1e-6) {
            cx = static_cast<float>(sumX / sumW);
            cy = static_cast<float>(sumY / sumW);
        } else {
            cx = dw * 0.5f;
            cy = dh * 0.5f;
        }
    };

    float rcx = 0, rcy = 0, scx = 0, scy = 0;
    fillLuma(ref, rL, rcx, rcy);
    fillLuma(src, sL, scx, scy);

    // Initial shift estimate from bright disc centroid
    int initDx = std::clamp(static_cast<int>(std::round(rcx - scx)), -maxShift / ds, maxShift / ds);
    int initDy = std::clamp(static_cast<int>(std::round(rcy - scy)), -maxShift / ds, maxShift / ds);

    // Coarse SSD search around initial centroid shift
    const int coarseRad = std::max(8, (maxShift / ds) / 2);
    int bestDx = initDx, bestDy = initDy;
    double bestCost = 1e30;

    auto evalSsd = [&](const std::vector<float>& A, const std::vector<float>& B, int gw, int gh, int dx, int dy) {
        const int x0 = std::max(0, dx);
        const int y0 = std::max(0, dy);
        const int x1 = std::min(gw, gw + dx);
        const int y1 = std::min(gh, gh + dy);
        if (x1 - x0 < gw / 3 || y1 - y0 < gh / 3) return 1e30;
        double ssd = 0.0;
        int64_t cnt = 0;
        for (int y = y0; y < y1; ++y) {
            const float* aRow = A.data() + static_cast<size_t>(y) * gw;
            const float* bRow = B.data() + static_cast<size_t>(y - dy) * gw;
            for (int x = x0; x < x1; ++x) {
                const float d = aRow[x] - bRow[x - dx];
                ssd += static_cast<double>(d * d);
                cnt++;
            }
        }
        return cnt > 0 ? ssd / static_cast<double>(cnt) : 1e30;
    };

    for (int dy = initDy - coarseRad; dy <= initDy + coarseRad; ++dy) {
        for (int dx = initDx - coarseRad; dx <= initDx + coarseRad; ++dx) {
            const double c = evalSsd(rL, sL, dw, dh, dx, dy);
            if (c < bestCost) {
                bestCost = c;
                bestDx = dx;
                bestDy = dy;
            }
        }
    }

    // Fine full-resolution search in a small window around coarse shift
    const int fineCenterX = bestDx * ds;
    const int fineCenterY = bestDy * ds;
    const int fineRad = std::max(3, ds + 1);
    const int sampleStep = std::max(1, std::min(w, h) / 480);

    auto evalFullSsd = [&](int dx, int dy) -> double {
        const int x0 = std::max(0, dx);
        const int y0 = std::max(0, dy);
        const int x1 = std::min(w, w + dx);
        const int y1 = std::min(h, h + dy);
        if (x1 - x0 < w / 3 || y1 - y0 < h / 3) return 1e30;
        double ssd = 0.0;
        int64_t cnt = 0;
        for (int y = y0; y < y1; y += sampleStep) {
            for (int x = x0; x < x1; x += sampleStep) {
                const size_t ir = static_cast<size_t>(y) * w + x;
                const size_t is = static_cast<size_t>(y - dy) * w + (x - dx);
                const float lr = 0.2126f * ref.r[ir] + 0.7152f * ref.g[ir] + 0.0722f * ref.b[ir];
                const float ls = 0.2126f * src.r[is] + 0.7152f * src.g[is] + 0.0722f * src.b[is];
                const float d = lr - ls;
                ssd += static_cast<double>(d * d);
                cnt++;
            }
        }
        return cnt > 0 ? ssd / static_cast<double>(cnt) : 1e30;
    };

    int fBestDx = fineCenterX, fBestDy = fineCenterY;
    double fBestCost = 1e30;
    for (int dy = fineCenterY - fineRad; dy <= fineCenterY + fineRad; ++dy) {
        for (int dx = fineCenterX - fineRad; dx <= fineCenterX + fineRad; ++dx) {
            const double c = evalFullSsd(dx, dy);
            if (c < fBestCost) {
                fBestCost = c;
                fBestDx = dx;
                fBestDy = dy;
            }
        }
    }

    // Sub-pixel parabolic refinement along X and Y
    const double cxM = evalFullSsd(fBestDx - 1, fBestDy);
    const double cxP = evalFullSsd(fBestDx + 1, fBestDy);
    const double cyM = evalFullSsd(fBestDx, fBestDy - 1);
    const double cyP = evalFullSsd(fBestDx, fBestDy + 1);

    float subX = 0.0f, subY = 0.0f;
    const double denX = cxM - 2.0 * fBestCost + cxP;
    if (denX > 1e-12) subX = std::clamp(static_cast<float>(0.5 * (cxM - cxP) / denX), -0.5f, 0.5f);
    const double denY = cyM - 2.0 * fBestCost + cyP;
    if (denY > 1e-12) subY = std::clamp(static_cast<float>(0.5 * (cyM - cyP) / denY), -0.5f, 0.5f);

    res.success = true;
    res.dx = static_cast<float>(fBestDx) + subX;
    res.dy = static_cast<float>(fBestDy) + subY;
    res.angleDeg = 0.0f;
    res.scale = 1.0f;
    res.residualPx = static_cast<float>(std::sqrt(std::max(0.0, fBestCost)));
    res.matrix[0] = 1.0f; res.matrix[1] = 0.0f; res.matrix[2] = res.dx;
    res.matrix[3] = 0.0f; res.matrix[4] = 1.0f; res.matrix[5] = res.dy;
    return res;
}

} // namespace astro
