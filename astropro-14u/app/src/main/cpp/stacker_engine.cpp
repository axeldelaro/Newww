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

} // namespace

// =========================================================================
// DeepSkyStacker — Star-Aligned Streaming Welford + Kappa-Sigma Rejection
// =========================================================================

void DeepSkyStacker::reset(float kappa, float maxResidualPx) {
    kappa_ = std::clamp(kappa, 1.5f, 5.0f);
    maxResidualPx_ = std::max(0.5f, maxResidualPx);
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

FrameStackReport DeepSkyStacker::addFrame(const FloatImageRGB& frame) {
    FrameStackReport rep{};
    received_++;
    rep.totalReceived = received_;

    if (!frame.valid()) {
        rejected_++;
        rep.totalAccepted = accepted_;
        rep.totalRejected = rejected_;
        return rep;
    }

    if (accepted_ == 0) {
        // First accepted frame establishes the reference astrometric coordinate system
        width_ = frame.width;
        height_ = frame.height;
        refImage_ = frame;
        refStars_ = detectStars(frame, 80);
        skySigmaR_ = estimateSkySigma(frame.r);
        skySigmaG_ = estimateSkySigma(frame.g);
        skySigmaB_ = estimateSkySigma(frame.b);

        CompactFrame16 cf;
        cf.width = width_;
        cf.height = height_;
        packFloatToU16(frame, cf.maxScale, cf.r, cf.g, cf.b);
        cf.mask.assign(frame.pixelCount(), 1);
        initialFrames_.push_back(std::move(cf));

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

    // Detect stars and align to reference
    auto stars = detectStars(frame, 80);
    rep.starsDetected = static_cast<int>(stars.size());

    FloatImageRGB aligned;
    std::vector<uint8_t> validMask;

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

// =========================================================================
// LuckyImagingStacker — Sharpest Frame Selection + Sub-Pixel NCC + Wavelets
// =========================================================================

void LuckyImagingStacker::reset(int targetFrames, float keepPercent, float waveletStrength) {
    const int desired = static_cast<int>(std::ceil(std::max(1, targetFrames) * std::clamp(keepPercent, 0.05f, 1.0f)));
    maxKeep_ = std::clamp(desired, 3, 25);
    waveletStrength_ = std::clamp(waveletStrength, 0.0f, 3.0f);
    received_ = 0;
    pool_.clear();
}

FrameStackReport LuckyImagingStacker::addFrame(const FloatImageRGB& frame) {
    FrameStackReport rep{};
    received_++;
    rep.totalReceived = received_;
    if (!frame.valid()) {
        rep.totalAccepted = static_cast<int>(pool_.size());
        rep.totalRejected = received_ - rep.totalAccepted;
        return rep;
    }

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
