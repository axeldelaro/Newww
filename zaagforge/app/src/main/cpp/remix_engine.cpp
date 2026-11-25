#include "remix_engine.h"
#include "dsp_utils.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <limits>
#include <thread>

namespace zaagforge {

namespace {

inline float hermiteSample(float y0, float y1, float y2, float y3, float t) noexcept {
    const float c0 = y1;
    const float c1 = 0.5f * (y2 - y0);
    const float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
    const float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
    return ((c3 * t + c2) * t + c1) * t + c0;
}

std::vector<float> resampleStereoCubic(const float* in, size_t inFrames, size_t outFrames) {
    if (!in || inFrames < 4 || outFrames == 0) {
        return std::vector<float>(outFrames * 2, 0.0f);
    }
    std::vector<float> out(outFrames * 2, 0.0f);
    const double step = static_cast<double>(inFrames - 1) / static_cast<double>(std::max<size_t>(1, outFrames - 1));

    for (size_t i = 0; i < outFrames; ++i) {
        const double pos = static_cast<double>(i) * step;
        const size_t idx = static_cast<size_t>(pos);
        const float frac = static_cast<float>(pos - static_cast<double>(idx));

        const size_t i0 = (idx > 0) ? idx - 1 : 0;
        const size_t i1 = std::min(idx, inFrames - 1);
        const size_t i2 = std::min(idx + 1, inFrames - 1);
        const size_t i3 = std::min(idx + 2, inFrames - 1);

        out[i * 2] = sanitize(hermiteSample(in[i0 * 2], in[i1 * 2], in[i2 * 2], in[i3 * 2], frac));
        out[i * 2 + 1] = sanitize(hermiteSample(in[i0 * 2 + 1], in[i1 * 2 + 1], in[i2 * 2 + 1], in[i3 * 2 + 1], frac));
    }
    return out;
}

void mixBufferAt(
    std::vector<float>& dst,
    const std::vector<float>& src,
    size_t dstStartFrame,
    float gainL = 1.0f,
    float gainR = 1.0f,
    size_t maxDstEndFrame = std::numeric_limits<size_t>::max()
) {
    const size_t dstFrames = std::min(dst.size() / 2, maxDstEndFrame);
    const size_t srcFrames = src.size() / 2;
    if (dstStartFrame >= dstFrames || srcFrames == 0) return;

    const size_t count = std::min(srcFrames, dstFrames - dstStartFrame);
    for (size_t i = 0; i < count; ++i) {
        dst[(dstStartFrame + i) * 2] += src[i * 2] * gainL;
        dst[(dstStartFrame + i) * 2 + 1] += src[i * 2 + 1] * gainR;
    }
}

void mixLoopedLayer(
    std::vector<float>& dst,
    const std::vector<float>& loop,
    size_t startFrame,
    size_t endFrame,
    float gain
) {
    const size_t loopFrames = loop.size() / 2;
    const size_t dstFrames = dst.size() / 2;
    if (loopFrames == 0 || startFrame >= endFrame || startFrame >= dstFrames || gain <= 1e-4f) return;
    const size_t clampedEnd = std::min(endFrame, dstFrames);
    const size_t fadeFrames = std::min<size_t>(2205, (clampedEnd - startFrame) / 4);

    for (size_t f = startFrame; f < clampedEnd; ++f) {
        const size_t rel = f - startFrame;
        const size_t lp = rel % loopFrames;
        float env = 1.0f;
        if (rel < fadeFrames) env = static_cast<float>(rel) / static_cast<float>(std::max<size_t>(1, fadeFrames));
        else if (clampedEnd - f < fadeFrames) env = static_cast<float>(clampedEnd - f) / static_cast<float>(std::max<size_t>(1, fadeFrames));
        dst[f * 2] += loop[lp * 2] * gain * env;
        dst[f * 2 + 1] += loop[lp * 2 + 1] * gain * env;
    }
}

struct FrameSection {
    size_t startFrame = 0;
    size_t endFrame = 0;
    SectionType type = SectionType::DROP_1;
    std::string label;
    int startBar = 0;
    int endBar = 0;
};

const FrameSection* findFrameSection(const std::vector<FrameSection>& secs, size_t frame) {
    for (const auto& s : secs) {
        if (frame >= s.startFrame && frame < s.endFrame) {
            return &s;
        }
    }
    return secs.empty() ? nullptr : &secs.back();
}

}  // namespace

// ============================================================================
// NEVER-SLOW Musical Tempo Synchronization (speedRatio >= 1.00f ALWAYS!)
// ============================================================================
TempoSyncResult RemixEngine::computeNeverSlowTempoSync(
    float sourceBpm,
    float targetBpm,
    TempoSyncMode mode
) {
    const float src = clampf(sourceBpm, 60.0f, 240.0f);
    const float tgt = clampf(targetBpm, 124.0f, 250.0f);
    TempoSyncResult res;

    if (mode == TempoSyncMode::ORIGINAL_SPEED_LOCK) {
        // 100% original track speed (0 WSOLA stretch -> blazing fast & studio-pristine!)
        res.speedRatio = 1.0f;
        // If user chose high-BPM style (>= 170) on a slow/hip-hop/mid-tempo track (<= 118),
        // double-time kicks cleanly onto 2x sourceBpm so rhythm stays 100% locked!
        if (tgt >= 168.0f && src <= 118.0f) {
            res.gridMultiplier = 0.5f;
            res.effectiveTargetBpm = clampf(src * 2.0f, 130.0f, 240.0f);
        } else {
            res.gridMultiplier = 1.0f;
            res.effectiveTargetBpm = clampf(src, 115.0f, 240.0f);
        }
        return res;
    }

    if (mode == TempoSyncMode::FORCE_TARGET_BPM) {
        // Always accelerate cleanly to targetBpm — STILL never slower than 1.00x!
        if (tgt * 0.5f >= src * 0.995f && (tgt * 0.5f) / src <= 1.38f) {
            res.gridMultiplier = 0.5f;
            res.speedRatio = std::max(1.0f, (tgt * 0.5f) / src);
            res.effectiveTargetBpm = src * res.speedRatio * 2.0f;
            return res;
        }
        if (tgt >= src) {
            res.gridMultiplier = 1.0f;
            res.speedRatio = clampf(tgt / src, 1.0f, 1.65f);
            res.effectiveTargetBpm = src * res.speedRatio;
            return res;
        }
        // Source is already faster than target -> NEVER slow down! Keep 1.00x and lock kicks to sourceBpm!
        res.gridMultiplier = 1.0f;
        res.speedRatio = 1.0f;
        res.effectiveTargetBpm = src;
        return res;
    }

    // Default: TempoSyncMode::NEVER_SLOW
    // 1. Check half-time vocals over double-time kicks ONLY when it does NOT slow down the song (>= 0.995x)!
    //    Example: 96 BPM rap/pop -> 200 BPM Uptempo = 1.04x speed (super natural, energetic, ZERO slowdown!)
    const float halfTimeSpeed = (tgt * 0.5f) / src;
    if (halfTimeSpeed >= 0.995f && halfTimeSpeed <= 1.38f) {
        res.gridMultiplier = 0.5f;
        res.speedRatio = std::max(1.0f, halfTimeSpeed);
        res.effectiveTargetBpm = src * res.speedRatio * 2.0f;
        return res;
    }

    // 2. Direct 1:1 speed-up when targetBpm >= sourceBpm (never slows down!)
    if (tgt >= src) {
        res.gridMultiplier = 1.0f;
        // Cap extreme speedup at 1.62x so vocals stay intelligible while hitting full energy
        res.speedRatio = clampf(tgt / src, 1.0f, 1.62f);
        res.effectiveTargetBpm = src * res.speedRatio;
        return res;
    }

    // 3. Source is ALREADY faster than targetBpm (e.g. 150 BPM track into 138 BPM Techno):
    //    NEVER slow down the user's song — keep exact 1.00x speed and lock kicks to sourceBpm!
    res.gridMultiplier = 1.0f;
    res.speedRatio = 1.0f;
    res.effectiveTargetBpm = src;
    return res;
}

std::pair<float, float> RemixEngine::computeMusicalTempoSync(float sourceBpm, float targetBpm) {
    const auto r = computeNeverSlowTempoSync(sourceBpm, targetBpm, TempoSyncMode::NEVER_SLOW);
    return {r.speedRatio, r.gridMultiplier};
}

// ============================================================================
// High-Speed Multi-Threaded Pyramid WSOLA Time-Stretcher (~10x faster!)
// ============================================================================
std::vector<float> RemixEngine::wsolaTimeStretch(
    const float* stereoIn,
    size_t inFrames,
    float timeScale
) {
    if (!stereoIn || inFrames == 0) return {};

    const float scale = clampf(timeScale, 0.45f, 2.40f);
    const size_t outFrames = std::max<size_t>(1, static_cast<size_t>(std::llround(static_cast<double>(inFrames) * scale)));

    // Instant zero-overhead bypass when 1.00x!
    if (std::fabs(scale - 1.0f) < 0.004f) {
        return std::vector<float>(stereoIn, stereoIn + inFrames * 2);
    }

    constexpr size_t kWin = 1536;    // 34.8 ms Hann window (crisp vocal transients + clean tone)
    constexpr size_t kHopOut = 384;  // 75% overlap (exact COLA for Hann window)
    constexpr int kSearchTol = 192;  // ±4.35 ms pitch-period waveform lock

    if (inFrames < kWin * 2) {
        return resampleStereoCubic(stereoIn, inFrames, outFrames);
    }

    std::vector<float> monoIn(inFrames);
    for (size_t i = 0; i < inFrames; ++i) {
        monoIn[i] = 0.5f * (sanitize(stereoIn[i * 2]) + sanitize(stereoIn[i * 2 + 1]));
    }

    const auto hann = makeHannWindow(kWin);
    const size_t numSteps = (outFrames > kHopOut) ? ((outFrames - 1) / kHopOut + 1) : 1;
    const double hopIn = static_cast<double>(kHopOut) / static_cast<double>(scale);
    const int maxValidStart = static_cast<int>(inFrames) - static_cast<int>(kWin);

    // Precompute optimal source positions using ultra-fast 3-level pyramid search!
    // Each step searches independently around its nominal reference -> embarrassingly parallelizable across ARM64 cores!
    std::vector<size_t> bestSrcPos(numSteps, 0);

    auto searchRange = [&](size_t sBegin, size_t sEnd) {
        constexpr size_t kCmpLen = 256;
        for (size_t stepIdx = sBegin; stepIdx < sEnd; ++stepIdx) {
            if (stepIdx == 0) {
                bestSrcPos[0] = 0;
                continue;
            }
            const int nominalSrc = static_cast<int>(std::llround(static_cast<double>(stepIdx) * hopIn));
            const int prevNominal = static_cast<int>(std::llround(static_cast<double>(stepIdx - 1) * hopIn));
            const int refPos = std::max(0, std::min(maxValidStart, prevNominal + static_cast<int>(kHopOut)));
            const int clampedNominal = std::max(0, std::min(maxValidStart, nominalSrc));

            const int searchLo = std::max(0, clampedNominal - kSearchTol);
            const int searchHi = std::min(maxValidStart, clampedNominal + kSearchTol);

            auto scoreCand = [&](int cand, size_t stepK) -> float {
                float dot = 0.0f;
                float energy = 1e-6f;
                const float* aPtr = monoIn.data() + refPos;
                const float* bPtr = monoIn.data() + cand;
                for (size_t k = 0; k < kCmpLen; k += stepK) {
                    const float a = aPtr[k];
                    const float b = bPtr[k];
                    dot += a * b;
                    energy += b * b;
                }
                return dot / std::sqrt(energy);
            };

            // Level 1: Coarse stride 8 with 4x decimated dot (64 ops/candidate!)
            int bestC = clampedNominal;
            float bestSc = -1e30f;
            for (int cand = searchLo; cand <= searchHi; cand += 8) {
                const float sc = scoreCand(cand, 4);
                if (sc > bestSc) {
                    bestSc = sc;
                    bestC = cand;
                }
            }

            // Level 2: Medium refine ±6 stride 2
            const int medLo = std::max(searchLo, bestC - 6);
            const int medHi = std::min(searchHi, bestC + 6);
            int medBest = bestC;
            bestSc = -1e30f;
            for (int cand = medLo; cand <= medHi; cand += 2) {
                const float sc = scoreCand(cand, 2);
                if (sc > bestSc) {
                    bestSc = sc;
                    medBest = cand;
                }
            }

            // Level 3: Exact 1-sample lock ±1
            const int fineLo = std::max(searchLo, medBest - 1);
            const int fineHi = std::min(searchHi, medBest + 1);
            int fineBest = medBest;
            bestSc = -1e30f;
            for (int cand = fineLo; cand <= fineHi; ++cand) {
                const float sc = scoreCand(cand, 2);
                if (sc > bestSc) {
                    bestSc = sc;
                    fineBest = cand;
                }
            }
            bestSrcPos[stepIdx] = static_cast<size_t>(fineBest);
        }
    };

    const unsigned hwThreads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    if (hwThreads > 1 && numSteps > 256) {
        std::vector<std::thread> workers;
        workers.reserve(hwThreads);
        const size_t chunk = (numSteps + hwThreads - 1) / hwThreads;
        for (unsigned t = 0; t < hwThreads; ++t) {
            const size_t b = t * chunk;
            const size_t e = std::min(numSteps, b + chunk);
            if (b < e) {
                workers.emplace_back(searchRange, b, e);
            }
        }
        for (auto& w : workers) w.join();
    } else {
        searchRange(0, numSteps);
    }

    // Overlap-add synthesis (fast sequential pass, cache-friendly, exact COLA normalization!)
    std::vector<float> out(outFrames * 2, 0.0f);
    std::vector<float> normAcc(outFrames, 0.0f);

    for (size_t stepIdx = 0; stepIdx < numSteps; ++stepIdx) {
        const size_t outPos = stepIdx * kHopOut;
        if (outPos >= outFrames) break;
        const size_t srcPos = bestSrcPos[stepIdx];
        const size_t copyLen = std::min(kWin, std::min(outFrames - outPos, inFrames - srcPos));
        const float* sPtr = stereoIn + srcPos * 2;
        float* dPtr = out.data() + outPos * 2;
        float* nPtr = normAcc.data() + outPos;
        for (size_t i = 0; i < copyLen; ++i) {
            const float w = hann[i];
            dPtr[i * 2] += sPtr[i * 2] * w;
            dPtr[i * 2 + 1] += sPtr[i * 2 + 1] * w;
            nPtr[i] += w;
        }
    }

    for (size_t i = 0; i < outFrames; ++i) {
        const float inv = (normAcc[i] > 1e-4f) ? (1.0f / normAcc[i]) : 1.0f;
        out[i * 2] = sanitize(out[i * 2] * inv);
        out[i * 2 + 1] = sanitize(out[i * 2 + 1] * inv);
    }
    return out;
}

std::vector<float> RemixEngine::timeStretchAndPitchShift(
    const float* stereoIn,
    size_t inFrames,
    float timeScale,
    float pitchSemitones
) {
    if (!stereoIn || inFrames == 0) return {};

    const float clampedScale = clampf(timeScale, 0.50f, 2.0f);
    const float st = clampf(pitchSemitones, -5.0f, 7.0f);
    const size_t targetFrames = std::max<size_t>(
        1,
        static_cast<size_t>(std::llround(static_cast<double>(inFrames) * clampedScale))
    );

    if (std::fabs(st) < 0.05f) {
        return wsolaTimeStretch(stereoIn, inFrames, clampedScale);
    }

    const float pitchFactor = std::pow(2.0f, st / 12.0f);
    const float intermediateScale = clampf(clampedScale * pitchFactor, 0.45f, 2.40f);
    auto stretched = wsolaTimeStretch(stereoIn, inFrames, intermediateScale);
    return resampleStereoCubic(stretched.data(), stretched.size() / 2, targetFrames);
}

RemixOutput RemixEngine::renderRemix(
    const float* stereoPcm,
    size_t totalFrames,
    const AnalysisResult& analysis,
    const RemixConfig& config,
    const float* preWarpedPcm,
    size_t preWarpedFrames
) {
    const auto tStart = std::chrono::steady_clock::now();
    RemixOutput result;
    if (!stereoPcm || totalFrames == 0) {
        return result;
    }

    const float sourceBpm = (config.sourceBpmOverride > 55.0f)
        ? clampf(config.sourceBpmOverride, 60.0f, 240.0f)
        : clampf(analysis.bpm, 60.0f, 240.0f);

    // 1. NEVER-SLOW Tempo Sync (speedRatio >= 1.00f ALWAYS!)
    const TempoSyncResult sync = computeNeverSlowTempoSync(sourceBpm, config.targetBpm, config.tempoSyncMode);
    const float speedRatio = sync.speedRatio;
    const float targetBpm = sync.effectiveTargetBpm;
    const float timeScale = 1.0f / speedRatio;

    // 2. Use pre-warped cache if provided (instant < 0.25s re-render!), else fast WSOLA!
    std::vector<float> localWarped;
    const float* warpedPtr = nullptr;
    size_t outFrames = 0;

    if (preWarpedPcm && preWarpedFrames > 0) {
        warpedPtr = preWarpedPcm;
        outFrames = preWarpedFrames;
    } else if (std::fabs(timeScale - 1.0f) < 0.004f && std::fabs(config.vocalPitchSemitones) < 0.05f) {
        // ZERO time-stretch needed -> read directly from original buffer (0 extra RAM, 0 ms!)
        warpedPtr = stereoPcm;
        outFrames = totalFrames;
    } else {
        localWarped = timeStretchAndPitchShift(stereoPcm, totalFrames, timeScale, config.vocalPitchSemitones);
        warpedPtr = localWarped.data();
        outFrames = localWarped.size() / 2;
    }
    const float outDurSec = static_cast<float>(outFrames) / kSampleRate;

    // 3. Key-lock sub root (shifted if vocal pitch shifted)
    float effectiveRootHz = analysis.rootHz;
    int effectiveKeyIdx = analysis.keyIndex;
    if (std::fabs(config.vocalPitchSemitones) >= 0.5f) {
        const int shiftSt = static_cast<int>(std::round(config.vocalPitchSemitones));
        effectiveKeyIdx = ((analysis.keyIndex + shiftSt) % 12 + 12) % 12;
        effectiveRootHz = clampf(midiToHz(static_cast<float>(pitchClassToSubMidi(effectiveKeyIdx))), 41.2f, 73.5f);
    }
    const std::string effectiveKeyName = formatKeyName(effectiveKeyIdx, analysis.isMinor);

    // 4. Exact Bar-Quantized Arrangement Sections
    ArrangementOptions arrOpts;
    arrOpts.dropMode = config.dropMode;
    arrOpts.buildupBars = config.buildupBars;
    arrOpts.enableFakeDrop = config.enableFakeDrop && (config.genreTheme != RemixGenreTheme::TECHNO_CLUB);
    arrOpts.drop1BarNudge = config.drop1BarNudge;
    arrOpts.drop2BarNudge = config.drop2BarNudge;
    arrOpts.customDrop1Frac = config.customDrop1Frac;
    arrOpts.customDrop2Frac = config.customDrop2Frac;
    arrOpts.bpmOverride = config.sourceBpmOverride;
    arrOpts.gridPhaseNudgeMs = config.gridPhaseNudgeMs;
    arrOpts.gridBeatShift = config.gridBeatShift;

    std::vector<SectionMarker> sections;
    if (analysis.durationSec > 1.0f && analysis.totalBars >= 4) {
        sections = MusicAnalyzer::buildArrangement(analysis, arrOpts);
    } else if (!analysis.sections.empty()) {
        sections = analysis.sections;
    } else {
        sections.push_back({0.0f, 1.0f, SectionType::DROP_1, "DROP 1", 0, 4});
    }

    const float beatSec = 60.0f / targetBpm;
    const float beatFramesF = beatSec * kSampleRate;
    const float srcBeatSec = 60.0f / sourceBpm;
    const float effectiveSrcDownbeatSec = std::max(
        0.0f,
        analysis.downbeatSec
            + static_cast<float>(config.gridBeatShift) * srcBeatSec
            + config.gridPhaseNudgeMs * 0.001f
    );
    const double warpedDownbeatSec = static_cast<double>(effectiveSrcDownbeatSec) * static_cast<double>(timeScale);
    const size_t downbeatFrame = std::min(outFrames > 0 ? outFrames - 1 : 0, static_cast<size_t>(std::llround(warpedDownbeatSec * kSampleRate)));
    const double warpedSrcBarFrames = (static_cast<double>(srcBeatSec) * 4.0) * static_cast<double>(timeScale) * kSampleRate;

    std::vector<FrameSection> frameSections;
    frameSections.reserve(sections.size());
    for (size_t idx = 0; idx < sections.size(); ++idx) {
        const auto& s = sections[idx];
        size_t sf = (idx == 0)
            ? 0
            : ((s.startBar > 0)
                ? std::min(outFrames, downbeatFrame + static_cast<size_t>(std::llround(s.startBar * warpedSrcBarFrames)))
                : std::min(outFrames, static_cast<size_t>(std::llround(s.startFrac * outFrames))));
        size_t ef = (idx + 1 == sections.size())
            ? outFrames
            : ((s.endBar > 0)
                ? std::min(outFrames, downbeatFrame + static_cast<size_t>(std::llround(s.endBar * warpedSrcBarFrames)))
                : std::min(outFrames, static_cast<size_t>(std::llround(s.endFrac * outFrames))));
        if (!frameSections.empty()) sf = frameSections.back().endFrame;
        if (ef < sf) ef = sf;
        frameSections.push_back({sf, ef, s.type, s.label, s.startBar, s.endBar});
    }
    if (!frameSections.empty()) {
        frameSections.front().startFrame = 0;
        frameSections.back().endFrame = outFrames;
    }

    sections.clear();
    for (const auto& fs : frameSections) {
        sections.push_back({
            static_cast<float>(fs.startFrame) / static_cast<float>(std::max<size_t>(1, outFrames)),
            static_cast<float>(fs.endFrame) / static_cast<float>(std::max<size_t>(1, outFrames)),
            fs.type,
            fs.label,
            fs.startBar,
            fs.endBar
        });
    }

    // 5. STREAMING SPLIT-BUS VOCAL CLARITY ENGINE + DYNAMIC VOCAL ENVELOPE TRACKING!
    //    Tracks real-time vocal envelope (decimated every 64 frames -> tiny RAM!) so the kick bus
    //    can dynamically carve a surgical vocal pocket whenever lyrics sing during epic/bass drops!
    std::vector<float> vocalBus(outFrames * 2, 0.0f);
    std::vector<float> vocalEnvTrack((outFrames + 63) / 64, 0.0f);

    const float bassCutHz = clampf(config.bassCutHz, 110.0f, 340.0f);
    const float origMixGain = clampf(config.originalMix, 0.15f, 1.0f);
    const float vocalClarity = clampf(config.vocalClarity, 0.0f, 1.0f);
    const float epicVocalBoost = clampf(config.epicVocalBoost, 0.0f, 1.0f);
    const float vocalScDepth = clampf(config.vocalSidechain, 0.0f, 0.85f);
    const float stutterAmt = clampf(config.stutterAmount, 0.0f, 1.0f);

    LinkwitzRiley4 bassSplitL, bassSplitR;
    LinkwitzRiley4 vocalSplitL, vocalSplitR;
    bassSplitL.configure(bassCutHz);
    bassSplitR.configure(bassCutHz);
    vocalSplitL.configure(680.0f); // Lower split captures warm chest/lower vocal formants cleanly!
    vocalSplitR.configure(680.0f);

    Biquad midDeMud, midPresence, midUpperFocus, midAir, sideVocalDip, buildHpfL, buildHpfR;
    midDeMud.configure(BiquadType::PEAK, 310.0f, 1.35f, -2.6f * vocalClarity);
    midPresence.configure(BiquadType::PEAK, 2200.0f, 1.05f, (4.8f + 1.8f * epicVocalBoost) * vocalClarity);
    midUpperFocus.configure(BiquadType::PEAK, 3600.0f, 1.30f, (2.2f + 1.4f * epicVocalBoost) * vocalClarity);
    midAir.configure(BiquadType::HIGH_SHELF, 6800.0f, 0.707f, 2.2f * vocalClarity);
    // Slightly trim stereo clutter in vocal band so center lyrics stand out clearly
    sideVocalDip.configure(BiquadType::PEAK, 1900.0f, 1.0f, -2.0f * vocalClarity);
    buildHpfL.configure(BiquadType::HPF, bassCutHz, 1.15f);
    buildHpfR.configure(BiquadType::HPF, bassCutHz, 1.15f);

    VocalAutoLeveller vocalLeveller;
    vocalLeveller.configure();

    const float envSlew = std::exp(-1.0f / (0.014f * kSampleRate));
    float lowGainSm = 0.85f;
    float midGainSm = 1.0f;
    float vocGainSm = 1.0f;
    float buildWetSm = 0.0f;

    const bool isTechnoOrFrenchcore = (config.genreTheme == RemixGenreTheme::TECHNO_CLUB ||
                                       config.genreTheme == RemixGenreTheme::FRENCHCORE);

    for (size_t i = 0; i < outFrames; ++i) {
        const FrameSection* sec = findFrameSection(frameSections, i);
        const SectionType sType = sec ? sec->type : SectionType::DROP_1;
        const size_t secStart = sec ? sec->startFrame : 0;
        const size_t secLen = sec ? std::max<size_t>(1, sec->endFrame - sec->startFrame) : outFrames;
        const float secProg = clampf(static_cast<float>(i - secStart) / static_cast<float>(secLen), 0.0f, 1.0f);

        const double localFrames = (sType == SectionType::DROP_1 || sType == SectionType::DROP_2)
            ? static_cast<double>(i - secStart)
            : static_cast<double>(i >= downbeatFrame ? (i - downbeatFrame) : 0);
        const double beatPos = localFrames / static_cast<double>(beatFramesF);
        const int beatIdx = static_cast<int>(std::floor(beatPos));
        const float beatFrac = static_cast<float>(beatPos - std::floor(beatPos));
        const int barIdx = beatIdx / 4;
        const int beatInBar = beatIdx % 4;

        size_t readFrame = i;
        float chopEnv = 1.0f;

        if (sType == SectionType::FAKE_DROP) {
            if (secProg >= 0.78f) {
                chopEnv = clampf((0.83f - secProg) / 0.05f, 0.0f, 1.0f);
            } else if (secProg >= 0.25f) {
                const size_t sliceFrames = std::max<size_t>(
                    512,
                    static_cast<size_t>((secProg > 0.52f ? 0.25f : 0.50f) * beatFramesF)
                );
                const size_t anchor = secStart + static_cast<size_t>(0.25f * secLen);
                const size_t localIdx = (i >= anchor) ? (i - anchor) : 0;
                const size_t slicePos = localIdx % sliceFrames;
                readFrame = std::min(outFrames - 1, anchor + slicePos);
                const float edgeFade = std::min(
                    1.0f,
                    std::min(static_cast<float>(slicePos) / 140.0f, static_cast<float>(sliceFrames - slicePos) / 140.0f)
                );
                const float gate = (static_cast<float>(slicePos) / static_cast<float>(sliceFrames) < 0.80f) ? 1.0f : 0.14f;
                chopEnv = edgeFade * gate;
            }
        } else if ((sType == SectionType::DROP_1 || sType == SectionType::DROP_2) && stutterAmt > 0.22f && !isTechnoOrFrenchcore) {
            const bool isTurnaround = (barIdx % 4 == 3 && beatInBar == 3);
            if (isTurnaround) {
                const float div = (stutterAmt > 0.65f) ? 0.25f : 0.50f;
                const size_t sliceLen = std::max<size_t>(512, static_cast<size_t>(div * beatFramesF));
                const size_t anchor = (i / (sliceLen * 4)) * (sliceLen * 4);
                const size_t posInSlice = (i - anchor) % sliceLen;
                readFrame = std::min(outFrames - 1, anchor + posInSlice);
                const float fade = std::min(
                    1.0f,
                    std::min(static_cast<float>(posInSlice) / 120.0f, static_cast<float>(sliceLen - posInSlice) / 120.0f)
                );
                chopEnv = (1.0f - stutterAmt * 0.65f) + (stutterAmt * 0.65f) * fade;
            }
        }

        const float inL = warpedPtr[readFrame * 2];
        const float inR = warpedPtr[readFrame * 2 + 1];

        float loL, hiAllL, lmL, vhL;
        float loR, hiAllR, lmR, vhR;
        bassSplitL.split(inL, loL, hiAllL);
        bassSplitR.split(inR, loR, hiAllR);
        vocalSplitL.split(hiAllL, lmL, vhL);
        vocalSplitR.split(hiAllR, lmR, vhR);

        // Mid/Side clean vocal clarity & de-mud
        const float lmMid = midDeMud.process(0.5f * (lmL + lmR));
        const float lmSide = 0.5f * (lmL - lmR);
        lmL = lmMid + lmSide;
        lmR = lmMid - lmSide;

        const float rawVhMid = 0.5f * (vhL + vhR);
        const float vhMidSculpted = midAir.process(midUpperFocus.process(midPresence.process(rawVhMid)));
        const float vhSideSculpted = sideVocalDip.process(0.5f * (vhL - vhR));

        // Transparent vocal auto-leveller lifts quiet lyrics during epic & bass-heavy drops!
        const bool isDrop = (sType == SectionType::DROP_1 || sType == SectionType::DROP_2);
        const float levelLift = vocalLeveller.process(vhMidSculpted, isDrop ? epicVocalBoost : (epicVocalBoost * 0.35f));
        const float vhMidFinal = vhMidSculpted * levelLift;

        if ((i & 63u) == 0u) {
            vocalEnvTrack[i >> 6] = vocalLeveller.currentEnvelope();
        }

        const float sideScale = 1.02f - 0.16f * vocalClarity;
        vhL = vhMidFinal + vhSideSculpted * sideScale;
        vhR = vhMidFinal - vhSideSculpted * sideScale;

        float targetLow = 0.82f;
        float targetMid = 0.96f;
        float targetVoc = 1.00f;
        float targetBuildWet = 0.0f;

        if (sType == SectionType::INTRO || sType == SectionType::VOCAL_BREAK || sType == SectionType::MID_BREAK) {
            const float g = 0.78f + 0.22f * origMixGain;
            targetLow = 0.76f * g;
            targetMid = 0.96f * g;
            targetVoc = (1.02f + 0.12f * vocalClarity) * g;
        } else if (sType == SectionType::BUILDUP) {
            targetBuildWet = 1.0f;
            const float g = 0.76f + 0.24f * origMixGain;
            targetLow = (1.0f - secProg) * 0.38f * g;
            targetMid = 0.92f * g;
            targetVoc = (1.06f + 0.15f * epicVocalBoost) * g;
        } else if (sType == SectionType::FAKE_DROP) {
            targetLow = 0.0f;
            targetMid = 0.84f * (0.60f + 0.40f * origMixGain);
            targetVoc = 1.12f * (0.60f + 0.40f * origMixGain);
        } else {
            // DROP 1 / DROP 2:
            // - Original bass (< bassCutHz) stripped completely for surgical sub separation!
            // - Low-mids duck deeply (76%) for punchy tight kicks
            // - Vocal band (> 680 Hz) stays PROUD & UPFRONT with gentle ducking + epicVocalBoost!
            targetLow = 0.0f;
            const float dLin = clampf((beatFrac - 0.04f) / 0.48f, 0.0f, 1.0f);
            const float duckShape = (beatFrac < 0.04f)
                ? (beatFrac / 0.04f)
                : (1.0f - (0.60f * std::sqrt(dLin) + 0.40f * dLin));
            const float lowMidDuck = 1.0f - 0.76f * duckShape;
            const float vocalDuck = 1.0f - vocalScDepth * duckShape;

            const float dropOrigBase = 0.56f + 0.74f * origMixGain;
            targetMid = dropOrigBase * 0.74f * lowMidDuck;
            targetVoc = dropOrigBase * (1.16f + 0.48f * vocalClarity + 0.22f * epicVocalBoost) * vocalDuck;
        }

        lowGainSm = lowGainSm * envSlew + targetLow * (1.0f - envSlew);
        midGainSm = isDrop ? targetMid : (midGainSm * envSlew + targetMid * (1.0f - envSlew));
        vocGainSm = isDrop ? targetVoc : (vocGainSm * envSlew + targetVoc * (1.0f - envSlew));
        buildWetSm = buildWetSm * envSlew + targetBuildWet * (1.0f - envSlew);

        float outL = (loL * lowGainSm + lmL * midGainSm + vhL * vocGainSm) * chopEnv;
        float outR = (loR * lowGainSm + lmR * midGainSm + vhR * vocGainSm) * chopEnv;

        if (buildWetSm > 0.005f) {
            if ((i & 63u) == 0u) {
                const float cut = bassCutHz + 920.0f * secProg * secProg;
                buildHpfL.configure(BiquadType::HPF, cut, 1.22f);
                buildHpfR.configure(BiquadType::HPF, cut, 1.22f);
            }
            const float filtL = buildHpfL.process(outL);
            const float filtR = buildHpfR.process(outR);
            outL = outL * (1.0f - buildWetSm) + filtL * buildWetSm;
            outR = outR * (1.0f - buildWetSm) + filtR * buildWetSm;
        }

        vocalBus[i * 2] = sanitize(outL);
        vocalBus[i * 2 + 1] = sanitize(outR);
    }

    localWarped.clear();
    localWarped.shrink_to_fit();

    // 6. DEDICATED KICK + GENRE-AUTHENTIC PRODUCER GROOVE & SYNTH BUS!
    std::vector<float> kickBus(outFrames * 2, 0.0f);

    const int switchBars = (config.kickSwitchBars == 8 || config.kickSwitchBars == 16) ? config.kickSwitchBars : 4;

    // Harmonic Frenchcore chord bassline progression (Sefa / Dr. Peacock style!) vs Hardcore turnaround intervals
    const std::array<float, 4> melodyOffsets = (config.genreTheme == RemixGenreTheme::FRENCHCORE)
        ? (analysis.isMinor ? std::array<float, 4>{0.0f, -4.0f, 3.0f, -2.0f} : std::array<float, 4>{0.0f, 7.0f, 9.0f, 5.0f})
        : std::array<float, 4>{0.0f, 3.0f, 5.0f, 7.0f};

    auto buildKickSet = [&](KickType kt, float driveBoost, float aggBoost) {
        std::array<std::vector<float>, 5> set;
        for (int m = 0; m < 4; ++m) {
            KickParams kp;
            kp.type = kt;
            kp.rootHz = effectiveRootHz;
            kp.bpm = targetBpm;
            kp.drive = clampf(config.drive + driveBoost, 0.1f, 1.0f);
            kp.aggression = clampf(config.zaagAggression + aggBoost, 0.1f, 1.0f);
            kp.bassSaturation = clampf(config.bassSaturation, 0.0f, 1.0f);
            kp.pitchSemitoneOffset = melodyOffsets[m];
            kp.lengthBeats = 1.0f;
            kp.seed = 2026u + static_cast<uint32_t>(kt) * 31u + static_cast<uint32_t>(m);
            set[m] = HardcoreSynth::renderKick(kp);
        }
        KickParams tripP;
        tripP.type = kt;
        tripP.rootHz = effectiveRootHz;
        tripP.bpm = targetBpm;
        tripP.drive = clampf(config.drive + driveBoost, 0.1f, 1.0f);
        tripP.aggression = clampf(config.zaagAggression + aggBoost, 0.1f, 1.0f);
        tripP.bassSaturation = clampf(config.bassSaturation, 0.0f, 1.0f);
        tripP.pitchSemitoneOffset = 3.0f;
        tripP.lengthBeats = 0.3333f;
        tripP.seed = 7777u + static_cast<uint32_t>(kt) * 17u;
        set[4] = HardcoreSynth::renderKick(tripP);
        return set;
    };

    const auto kickSet1 = buildKickSet(config.drop1Kick, 0.0f, 0.0f);
    const auto kickSet2 = buildKickSet(config.drop2Kick, 0.04f, 0.04f);
    const auto kickSet2Finale = buildKickSet(config.drop2Kick, 0.08f, 0.07f);

    const float percAmt = clampf(config.percGrooveAmount, 0.0f, 1.0f);
    const float synthAmt = clampf(config.synthLayerAmount, 0.0f, 1.0f);

    const auto openHat = HardcoreSynth::renderHardcoreHat(beatSec * 0.45f, true, 909u);
    const auto closedHat = HardcoreSynth::renderHardcoreHat(beatSec * 0.14f, false, 908u);
    const auto clap909 = HardcoreSynth::render909Clap(0.16f, 9091u);
    const auto ride909 = HardcoreSynth::render909Ride(beatSec * 0.50f, 9092u);
    const auto subImpact = HardcoreSynth::renderSubImpact(effectiveRootHz, std::min(1.5f, beatSec * 4.0f));

    // Genre-authentic musical signature layers!
    std::vector<float> screechA, screechB, hooverStab, acid303Loop, euphoricPadLoop;
    if (config.enableScreeches && config.genreTheme != RemixGenreTheme::TECHNO_CLUB && config.genreTheme != RemixGenreTheme::FRENCHCORE) {
        screechA = HardcoreSynth::renderIndustrialScreech(effectiveRootHz, beatSec * 1.35f, config.zaagAggression, 0);
        screechB = HardcoreSynth::renderIndustrialScreech(effectiveRootHz, beatSec * 1.15f, config.zaagAggression, 1);
    }
    if (synthAmt > 0.05f) {
        if (config.genreTheme == RemixGenreTheme::HARD_TECHNO ||
            config.drop1Kick == KickType::ACID_303_TECHNO ||
            config.genreTheme == RemixGenreTheme::TECHNO_CLUB) {
            acid303Loop = HardcoreSynth::renderAcid303Riff(effectiveRootHz, targetBpm, 4, config.zaagAggression, 303u);
        } else if (config.genreTheme == RemixGenreTheme::FRENCHCORE) {
            euphoricPadLoop = HardcoreSynth::renderEuphoricChordLayer(effectiveRootHz, analysis.isMinor, targetBpm, 4);
        } else if (config.genreTheme == RemixGenreTheme::GABBER) {
            hooverStab = HardcoreSynth::renderRaveHooverStab(effectiveRootHz, beatSec * 1.6f, config.zaagAggression);
        }
    }

    // 7. Render Sample-Exact Genre-Authentic Arrangement across Sections!
    for (const auto& sec : frameSections) {
        if (sec.endFrame <= sec.startFrame) continue;
        const float secDurSec = static_cast<float>(sec.endFrame - sec.startFrame) / kSampleRate;

        if (sec.type == SectionType::BUILDUP && secDurSec >= 0.45f) {
            const auto roll = HardcoreSynth::renderSnareBuildupRoll(targetBpm, secDurSec, effectiveRootHz, config.zaagAggression);
            const auto riser = HardcoreSynth::renderNoiseRiser(secDurSec, effectiveRootHz, config.zaagAggression);
            const float rollGain = (config.genreTheme == RemixGenreTheme::TECHNO_CLUB) ? 0.46f : 0.64f;
            mixBufferAt(kickBus, roll, sec.startFrame, rollGain, rollGain, sec.endFrame);
            mixBufferAt(kickBus, riser, sec.startFrame, 0.44f, 0.44f, sec.endFrame);
        } else if (sec.type == SectionType::FAKE_DROP) {
            const size_t silenceCutoff = sec.startFrame + static_cast<size_t>((sec.endFrame - sec.startFrame) * 0.78f);
            mixBufferAt(kickBus, kickSet1[0], sec.startFrame, 0.86f, 0.86f, silenceCutoff);
            if (!screechA.empty() && secDurSec >= 0.25f) {
                const size_t stabPos = sec.startFrame + static_cast<size_t>(std::min(beatFramesF * 0.5f, (silenceCutoff - sec.startFrame) * 0.35f));
                mixBufferAt(kickBus, screechA, stabPos, 0.50f, 0.50f, silenceCutoff);
            }
        } else if (sec.type == SectionType::DROP_1 || sec.type == SectionType::DROP_2) {
            const bool isDrop2 = (sec.type == SectionType::DROP_2);

            mixBufferAt(kickBus, subImpact, sec.startFrame, 0.46f, 0.46f, sec.endFrame);

            // Genre musical bed layers (Acid 303 for Techno/Hard Techno; Euphoric Supersaw Pads for Sefa/Peacock Frenchcore!)
            if (!acid303Loop.empty()) {
                const float acidGain = (config.genreTheme == RemixGenreTheme::TECHNO_CLUB)
                    ? (isDrop2 ? 0.52f : 0.32f) * synthAmt
                    : (isDrop2 ? 0.68f : 0.48f) * synthAmt;
                mixLoopedLayer(kickBus, acid303Loop, sec.startFrame, sec.endFrame, acidGain);
            }
            if (!euphoricPadLoop.empty()) {
                const float padGain = (isDrop2 ? 0.62f : 0.46f) * synthAmt;
                mixLoopedLayer(kickBus, euphoricPadLoop, sec.startFrame, sec.endFrame, padGain);
            }

            const int numDropBeats = static_cast<int>(std::floor(static_cast<double>(sec.endFrame - sec.startFrame) / static_cast<double>(beatFramesF)));
            for (int lb = 0; lb < numDropBeats; ++lb) {
                const size_t beatStart = sec.startFrame + static_cast<size_t>(std::llround(static_cast<double>(lb) * static_cast<double>(beatFramesF)));
                if (beatStart >= sec.endFrame) break;

                const int dropBarIdx = lb / 4;
                const int beatInBar = lb % 4;
                const int dropBarNum = dropBarIdx + 1;

                const int switchPhase = (dropBarIdx / switchBars) % 2;
                const bool useSet2 = isDrop2 ? (switchPhase == 0) : (switchPhase == 1);
                const auto& activeSet = useSet2 ? (isDrop2 ? kickSet2Finale : kickSet2) : kickSet1;

                const bool isTurnaroundBar = (dropBarNum % 4 == 0);
                const bool isSwitchBoundaryBar = (dropBarNum % switchBars == 0);

                // Kick selection per genre theme!
                if (config.enableTripletRolls && isTurnaroundBar && beatInBar == 3 &&
                    config.genreTheme != RemixGenreTheme::TECHNO_CLUB) {
                    const float tripStep = beatFramesF / 3.0f;
                    for (int t = 0; t < 3; ++t) {
                        const size_t tStart = beatStart + static_cast<size_t>(std::llround(t * tripStep));
                        const float accent = 0.85f + 0.06f * static_cast<float>(t);
                        mixBufferAt(kickBus, activeSet[4], tStart, accent, accent, sec.endFrame);
                    }
                } else {
                    int pitchIdx = 0;
                    if (config.genreTheme == RemixGenreTheme::FRENCHCORE && config.enablePitchKickMelody) {
                        // True Sefa / Dr. Peacock 4-bar euphoric harmonic bassline contour!
                        pitchIdx = dropBarIdx % 4;
                    } else if (config.enablePitchKickMelody && isTurnaroundBar && config.genreTheme != RemixGenreTheme::TECHNO_CLUB) {
                        static constexpr std::array<int, 4> kTurnaround = {0, 1, 2, 3};
                        pitchIdx = kTurnaround[beatInBar];
                    } else if (config.enablePitchKickMelody && (dropBarNum % 2 == 0) && beatInBar == 3 && config.genreTheme != RemixGenreTheme::TECHNO_CLUB) {
                        pitchIdx = 1;
                    }
                    const float kGain = isDrop2 ? 1.02f : 0.98f;
                    mixBufferAt(kickBus, activeSet[pitchIdx], beatStart, kGain, kGain, sec.endFrame);
                }

                // Pro 909 Percussive Groove per Genre!
                if (percAmt > 0.05f) {
                    // Offbeat 909 Open Hi-Hat
                    const size_t offbeatPos = beatStart + static_cast<size_t>(beatFramesF * 0.5f);
                    if (offbeatPos < sec.endFrame) {
                        const float hatGain = (isDrop2 ? 0.34f : 0.27f) * percAmt;
                        mixBufferAt(kickBus, openHat, offbeatPos, hatGain, hatGain, sec.endFrame);
                    }

                    // Driving 16th-note closed hats for Techno & Hard Techno (Charlotte / Nico / Sara Landry!)
                    if (config.genreTheme == RemixGenreTheme::TECHNO_CLUB ||
                        config.genreTheme == RemixGenreTheme::HARD_TECHNO) {
                        for (int s16 = 1; s16 < 4; ++s16) {
                            if (s16 == 2) continue;
                            const size_t p16 = beatStart + static_cast<size_t>(beatFramesF * (0.25f * s16));
                            if (p16 < sec.endFrame) {
                                const float g16 = ((s16 == 3) ? 0.28f : 0.20f) * percAmt;
                                mixBufferAt(kickBus, closedHat, p16, g16, g16, sec.endFrame);
                            }
                        }
                    }

                    // Crisp 909 Stereo Handclap on beats 2 & 4 (Techno / Hard Techno / Gabber!)
                    if ((beatInBar == 1 || beatInBar == 3) &&
                        (config.genreTheme == RemixGenreTheme::TECHNO_CLUB ||
                         config.genreTheme == RemixGenreTheme::HARD_TECHNO ||
                         config.genreTheme == RemixGenreTheme::GABBER)) {
                        const float clapGain = (isDrop2 ? 0.56f : 0.45f) * percAmt;
                        mixBufferAt(kickBus, clap909, beatStart, clapGain, clapGain, sec.endFrame);
                    }

                    // Shimmering 909 Warehouse Ride Cymbal in Drop 2 (classic festival lift!)
                    if (isDrop2 && (config.genreTheme == RemixGenreTheme::TECHNO_CLUB ||
                                    config.genreTheme == RemixGenreTheme::HARD_TECHNO ||
                                    config.genreTheme == RemixGenreTheme::GABBER ||
                                    config.genreTheme == RemixGenreTheme::FRENCHCORE)) {
                        const float rideGain = 0.30f * percAmt;
                        mixBufferAt(kickBus, ride909, beatStart, rideGain, rideGain, sec.endFrame);
                        if (offbeatPos < sec.endFrame) {
                            mixBufferAt(kickBus, ride909, offbeatPos, rideGain * 0.72f, rideGain * 0.72f, sec.endFrame);
                        }
                    }
                }

                // Gabber Mentasm / Hoover Rave Stabs on phrase starts (Angerfist / Neophyte!)
                if (!hooverStab.empty() && (dropBarIdx % 2 == 0) && beatInBar == 0) {
                    const float hGain = (isDrop2 ? 0.55f : 0.42f) * synthAmt;
                    mixBufferAt(kickBus, hooverStab, beatStart, hGain, hGain, sec.endFrame);
                }

                // Industrial Screeches (Uptempo / Rawstyle / Hard Techno only!)
                if (!screechA.empty()) {
                    if ((isSwitchBoundaryBar && beatInBar == 2) || (isDrop2 && (dropBarNum % 2 == 1) && beatInBar == 0)) {
                        const auto& sc = (dropBarNum % 2 == 0) ? screechA : screechB;
                        const float scGain = isDrop2 ? 0.34f : 0.28f;
                        mixBufferAt(kickBus, sc, beatStart, scGain, scGain, sec.endFrame);
                    }
                }
            }
        }
    }

    // 8. DYNAMIC KICK VOCAL-POCKET CARVING + SPLIT-BUS SUMMING!
    //    Whenever vocals are singing during epic/heavy-bass drops, carve a clean dynamic bell dip
    //    at 1.85 kHz on `kickBus` ONLY — sub (<150 Hz) & punch stay 100% untouched while lyrics shine!
    Biquad kickVocCarveL, kickVocCarveR;
    kickVocCarveL.configure(BiquadType::PEAK, 1850.0f, 1.05f, 0.0f);
    kickVocCarveR.configure(BiquadType::PEAK, 1850.0f, 1.05f, 0.0f);

    const float kickGain = clampf(config.kickLevel, 0.35f, 1.25f);
    for (size_t i = 0; i < outFrames; ++i) {
        if ((i & 63u) == 0u && epicVocalBoost > 0.05f) {
            const float vEnv = vocalEnvTrack[i >> 6];
            const float activeFrac = clampf((vEnv - 0.025f) / 0.22f, 0.0f, 1.0f);
            const float carveDb = -5.2f * epicVocalBoost * activeFrac;
            kickVocCarveL.configure(BiquadType::PEAK, 1850.0f, 1.05f, carveDb);
            kickVocCarveR.configure(BiquadType::PEAK, 1850.0f, 1.05f, carveDb);
        }
        float kL = kickVocCarveL.process(kickBus[i * 2]);
        float kR = kickVocCarveR.process(kickBus[i * 2 + 1]);
        kL = std::tanh(kL * 1.03f) * kickGain;
        kR = std::tanh(kR * 1.03f) * kickGain;
        vocalBus[i * 2] = sanitize(vocalBus[i * 2] + kL);
        vocalBus[i * 2 + 1] = sanitize(vocalBus[i * 2 + 1] + kR);
    }
    kickBus.clear();
    kickBus.shrink_to_fit();

    // 9. ULTRA-FAST TRANSPARENT MASTERING CHAIN (O(1) 4 ms LookaheadLimiter!)
    const int masterMode = std::max(0, std::min(2, config.masterMode));
    const float ottDepth = (masterMode == 0) ? 0.22f : ((masterMode == 1) ? 0.36f : 0.52f);
    const float preGain = (masterMode == 0) ? 1.32f : ((masterMode == 1) ? 1.46f : 1.60f);

    Biquad dcL, dcR;
    dcL.configure(BiquadType::HPF, 25.0f, 0.707f);
    dcR.configure(BiquadType::HPF, 25.0f, 0.707f);

    Ott3Band masterOtt;
    masterOtt.configure(130.0f, 2600.0f);
    OversampledClipper2x clipL, clipR;
    LookaheadLimiter limiter;
    limiter.configure(0.97f, 4.0f, 58.0f);

    double sumSq = 0.0;
    float peakAbs = 0.0f;

    for (size_t i = 0; i < outFrames; ++i) {
        float l = dcL.process(vocalBus[i * 2]);
        float r = dcR.process(vocalBus[i * 2 + 1]);

        masterOtt.processStereo(l, r, ottDepth, 1.0f, 1.06f, 1.0f + 0.08f * epicVocalBoost);

        l *= preGain;
        r *= preGain;

        if (masterMode == 2) {
            l = clipL.process(l, 1.18f, 0.98f, 0.01f);
            r = clipR.process(r, 1.18f, 0.98f, 0.01f);
        } else if (masterMode == 1) {
            l = std::tanh(l * 1.04f);
            r = std::tanh(r * 1.04f);
        }

        limiter.processStereo(l, r);

        l = clampf(sanitize(l), -0.98f, 0.98f);
        r = clampf(sanitize(r), -0.98f, 0.98f);

        vocalBus[i * 2] = l;
        vocalBus[i * 2 + 1] = r;

        peakAbs = std::max(peakAbs, std::max(std::fabs(l), std::fabs(r)));
        sumSq += 0.5 * (static_cast<double>(l) * l + static_cast<double>(r) * r);
    }

    const size_t edgeFade = std::min<size_t>(441, outFrames / 4);
    for (size_t i = 0; i < edgeFade; ++i) {
        const float g = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, edgeFade));
        vocalBus[i * 2] *= g;
        vocalBus[i * 2 + 1] *= g;
        const size_t tail = outFrames - 1 - i;
        vocalBus[tail * 2] *= g;
        vocalBus[tail * 2 + 1] *= g;
    }

    const float rms = static_cast<float>(std::sqrt(sumSq / static_cast<double>(std::max<size_t>(1, outFrames))));
    const auto tEnd = std::chrono::steady_clock::now();
    const float renderMs = std::chrono::duration<float, std::milli>(tEnd - tStart).count();

    result.waveformPeaks = MusicAnalyzer::extractWaveformPeaks(vocalBus.data(), outFrames, 240);
    result.stereoPcm = std::move(vocalBus);
    result.sections = std::move(sections);
    result.sourceBpm = sourceBpm;
    result.effectiveTargetBpm = targetBpm;
    result.tempoStretchRatio = speedRatio;
    result.rootHz = effectiveRootHz;
    result.keyName = effectiveKeyName;
    result.durationSec = outDurSec;
    result.peakDb = linearToDb(peakAbs);
    result.rmsDb = linearToDb(rms);
    result.renderTimeMs = renderMs;
    return result;
}

}  // namespace zaagforge
