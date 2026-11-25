#include "remix_engine.h"
#include "dsp_utils.h"

#include <algorithm>
#include <array>
#include <atomic>
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

std::vector<float> resampleStereoCubicParallel(
    const float* in,
    size_t inFrames,
    size_t outFrames,
    const std::atomic<bool>* cancelFlag = nullptr
) {
    if (!in || inFrames < 4 || outFrames == 0) {
        return std::vector<float>(outFrames * 2, 0.0f);
    }
    std::vector<float> out(outFrames * 2, 0.0f);
    const double step = static_cast<double>(inFrames - 1) / static_cast<double>(std::max<size_t>(1, outFrames - 1));
    const size_t maxIdx = inFrames - 1;

    auto worker = [&](size_t startF, size_t endF) {
        for (size_t i = startF; i < endF; ++i) {
            if ((i & 16383u) == 0u && cancelFlag && cancelFlag->load(std::memory_order_relaxed)) return;
            const double pos = static_cast<double>(i) * step;
            const size_t idx = static_cast<size_t>(pos);
            const float frac = static_cast<float>(pos - static_cast<double>(idx));

            const size_t i0 = (idx > 0) ? idx - 1 : 0;
            const size_t i1 = std::min(idx, maxIdx);
            const size_t i2 = std::min(idx + 1, maxIdx);
            const size_t i3 = std::min(idx + 2, maxIdx);

            out[i * 2] = hermiteSample(in[i0 * 2], in[i1 * 2], in[i2 * 2], in[i3 * 2], frac);
            out[i * 2 + 1] = hermiteSample(in[i0 * 2 + 1], in[i1 * 2 + 1], in[i2 * 2 + 1], in[i3 * 2 + 1], frac);
        }
    };

    const unsigned hw = std::max(1u, std::min(6u, std::thread::hardware_concurrency()));
    if (hw > 1 && outFrames > 65536u) {
        std::vector<std::thread> threads;
        threads.reserve(hw);
        const size_t chunk = (outFrames + hw - 1) / hw;
        for (unsigned t = 0; t < hw; ++t) {
            const size_t b = t * chunk;
            const size_t e = std::min(outFrames, b + chunk);
            if (b < e) threads.emplace_back(worker, b, e);
        }
        for (auto& th : threads) th.join();
    } else {
        worker(0, outFrames);
    }
    return out;
}

struct FrameSection {
    size_t startFrame = 0;
    size_t endFrame = 0;
    SectionType type = SectionType::DROP_1;
    std::string label;
    int startBar = 0;
    int endBar = 0;
};

struct MixEvent {
    const std::vector<float>* asset = nullptr;
    size_t startFrame = 0;
    size_t endFrame = 0;      // exclusive end on timeline
    float gainL = 1.0f;
    float gainR = 1.0f;
    bool isLoop = false;
    size_t loopFadeFrames = 0;
};

inline void mixEventIntoBlock(
    float* blkKick,
    size_t blkStart,
    size_t blkEnd,
    const MixEvent& ev
) noexcept {
    if (!ev.asset || ev.asset->empty()) return;
    const size_t ovStart = std::max(blkStart, ev.startFrame);
    const size_t ovEnd = std::min(blkEnd, ev.endFrame);
    if (ovStart >= ovEnd) return;

    const float* src = ev.asset->data();
    const size_t srcFrames = ev.asset->size() / 2;
    if (srcFrames == 0) return;

    if (!ev.isLoop) {
        const size_t srcOffset = ovStart - ev.startFrame;
        const size_t avail = (srcOffset < srcFrames) ? (srcFrames - srcOffset) : 0;
        const size_t count = std::min(ovEnd - ovStart, avail);
        float* dst = blkKick + (ovStart - blkStart) * 2;
        const float* sp = src + srcOffset * 2;
        const float gL = ev.gainL;
        const float gR = ev.gainR;
        for (size_t i = 0; i < count; ++i) {
            dst[i * 2] += sp[i * 2] * gL;
            dst[i * 2 + 1] += sp[i * 2 + 1] * gR;
        }
    } else {
        const size_t fadeF = ev.loopFadeFrames;
        const float invFade = (fadeF > 0) ? (1.0f / static_cast<float>(fadeF)) : 1.0f;
        const float gL = ev.gainL;
        const float gR = ev.gainR;
        for (size_t f = ovStart; f < ovEnd; ++f) {
            const size_t rel = f - ev.startFrame;
            const size_t lp = rel % srcFrames;
            float env = 1.0f;
            if (fadeF > 0) {
                if (rel < fadeF) env = static_cast<float>(rel) * invFade;
                else if (ev.endFrame - f < fadeF) env = static_cast<float>(ev.endFrame - f) * invFade;
            }
            const size_t bi = (f - blkStart) * 2;
            blkKick[bi] += src[lp * 2] * gL * env;
            blkKick[bi + 1] += src[lp * 2 + 1] * gR * env;
        }
    }
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
        res.speedRatio = 1.0f;
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
        res.gridMultiplier = 1.0f;
        res.speedRatio = 1.0f;
        res.effectiveTargetBpm = src;
        return res;
    }

    // Default: TempoSyncMode::NEVER_SLOW
    const float halfTimeSpeed = (tgt * 0.5f) / src;
    if (halfTimeSpeed >= 0.995f && halfTimeSpeed <= 1.38f) {
        res.gridMultiplier = 0.5f;
        res.speedRatio = std::max(1.0f, halfTimeSpeed);
        res.effectiveTargetBpm = src * res.speedRatio * 2.0f;
        return res;
    }

    if (tgt >= src) {
        res.gridMultiplier = 1.0f;
        res.speedRatio = clampf(tgt / src, 1.0f, 1.62f);
        res.effectiveTargetBpm = src * res.speedRatio;
        return res;
    }

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
// High-Speed Multi-Threaded Pyramid WSOLA Time-Stretcher (up to 6 cores!)
// ============================================================================
std::vector<float> RemixEngine::wsolaTimeStretch(
    const float* stereoIn,
    size_t inFrames,
    float timeScale,
    std::atomic<int>* progressPct,
    int progressStart,
    int progressEnd,
    const std::atomic<bool>* cancelFlag
) {
    if (!stereoIn || inFrames == 0) return {};

    const float scale = clampf(timeScale, 0.45f, 2.40f);
    const size_t outFrames = std::max<size_t>(1, static_cast<size_t>(std::llround(static_cast<double>(inFrames) * scale)));

    if (std::fabs(scale - 1.0f) < 0.004f) {
        if (progressPct) progressPct->store(progressEnd, std::memory_order_relaxed);
        return std::vector<float>(stereoIn, stereoIn + inFrames * 2);
    }

    constexpr size_t kWin = 1536;    // 34.8 ms Hann window (crisp vocal transients + clean tone)
    constexpr size_t kHopOut = 384;  // 75% overlap (exact COLA for Hann window)
    constexpr int kSearchTol = 192;  // ±4.35 ms pitch-period waveform lock

    if (inFrames < kWin * 2) {
        auto r = resampleStereoCubicParallel(stereoIn, inFrames, outFrames, cancelFlag);
        if (progressPct) progressPct->store(progressEnd, std::memory_order_relaxed);
        return r;
    }

    // Parallel mono downmix for ultra-fast correlation search
    std::vector<float> monoIn(inFrames);
    const unsigned hwThreads = std::max(1u, std::min(6u, std::thread::hardware_concurrency()));
    {
        auto mixMono = [&](size_t s0, size_t s1) {
            for (size_t i = s0; i < s1; ++i) {
                monoIn[i] = 0.5f * (stereoIn[i * 2] + stereoIn[i * 2 + 1]);
            }
        };
        if (hwThreads > 1 && inFrames > 131072u) {
            std::vector<std::thread> ths;
            ths.reserve(hwThreads);
            const size_t ch = (inFrames + hwThreads - 1) / hwThreads;
            for (unsigned t = 0; t < hwThreads; ++t) {
                const size_t b = t * ch, e = std::min(inFrames, b + ch);
                if (b < e) ths.emplace_back(mixMono, b, e);
            }
            for (auto& th : ths) th.join();
        } else {
            mixMono(0, inFrames);
        }
    }

    const auto hann = makeHannWindow(kWin);
    const size_t numSteps = (outFrames > kHopOut) ? ((outFrames - 1) / kHopOut + 1) : 1;
    const double hopIn = static_cast<double>(kHopOut) / static_cast<double>(scale);
    const int maxValidStart = static_cast<int>(inFrames) - static_cast<int>(kWin);

    std::vector<size_t> bestSrcPos(numSteps, 0);
    std::atomic<size_t> stepsDone{0};
    const int searchEndPct = progressStart + ((progressEnd - progressStart) * 7) / 10;

    auto searchRange = [&](size_t sBegin, size_t sEnd) {
        constexpr size_t kCmpLen = 256;
        size_t localDone = 0;
        for (size_t stepIdx = sBegin; stepIdx < sEnd; ++stepIdx) {
            if ((stepIdx & 63u) == 0u) {
                if (cancelFlag && cancelFlag->load(std::memory_order_relaxed)) return;
                if (progressPct && localDone > 0) {
                    const size_t tot = stepsDone.fetch_add(localDone, std::memory_order_relaxed) + localDone;
                    localDone = 0;
                    const int pct = progressStart + static_cast<int>((tot * static_cast<size_t>(searchEndPct - progressStart)) / std::max<size_t>(1, numSteps));
                    progressPct->store(std::min(searchEndPct, pct), std::memory_order_relaxed);
                }
            }
            ++localDone;

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

            int bestC = clampedNominal;
            float bestSc = -1e30f;
            for (int cand = searchLo; cand <= searchHi; cand += 8) {
                const float sc = scoreCand(cand, 4);
                if (sc > bestSc) {
                    bestSc = sc;
                    bestC = cand;
                }
            }

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

    if (hwThreads > 1 && numSteps > 256) {
        std::vector<std::thread> workers;
        workers.reserve(hwThreads);
        const size_t chunk = (numSteps + hwThreads - 1) / hwThreads;
        for (unsigned t = 0; t < hwThreads; ++t) {
            const size_t b = t * chunk;
            const size_t e = std::min(numSteps, b + chunk);
            if (b < e) workers.emplace_back(searchRange, b, e);
        }
        for (auto& w : workers) w.join();
    } else {
        searchRange(0, numSteps);
    }

    if (cancelFlag && cancelFlag->load(std::memory_order_relaxed)) return {};

    // Parallel non-overlapping slice overlap-add! (Each output frame has at most 4 overlapping Hann frames!)
    std::vector<float> out(outFrames * 2, 0.0f);
    auto synthSlice = [&](size_t fStart, size_t fEnd) {
        const size_t stepFirst = (fStart >= kWin) ? ((fStart - kWin) / kHopOut) : 0;
        const size_t stepLast = std::min(numSteps, (fEnd / kHopOut) + 1);
        constexpr size_t kBlk = 8192;
        std::array<float, kBlk> normBlk{};

        for (size_t blkStart = fStart; blkStart < fEnd; blkStart += kBlk) {
            if (cancelFlag && cancelFlag->load(std::memory_order_relaxed)) return;
            const size_t blkEnd = std::min(fEnd, blkStart + kBlk);
            const size_t blkLen = blkEnd - blkStart;
            std::fill(normBlk.begin(), normBlk.begin() + blkLen, 0.0f);

            const size_t sLo = (blkStart >= kWin) ? ((blkStart - kWin) / kHopOut) : stepFirst;
            const size_t sHi = std::min(stepLast, (blkEnd / kHopOut) + 1);

            for (size_t st = sLo; st < sHi; ++st) {
                const size_t outPos = st * kHopOut;
                if (outPos >= blkEnd) break;
                const size_t srcPos = bestSrcPos[st];
                const size_t winEnd = std::min(outPos + kWin, outPos + (inFrames - srcPos));
                const size_t ovStart = std::max(blkStart, outPos);
                const size_t ovEnd = std::min(blkEnd, winEnd);
                if (ovStart >= ovEnd) continue;

                const size_t wOff = ovStart - outPos;
                const size_t count = ovEnd - ovStart;
                const float* sPtr = stereoIn + (srcPos + wOff) * 2;
                float* dPtr = out.data() + ovStart * 2;
                const float* wPtr = hann.data() + wOff;
                float* nPtr = normBlk.data() + (ovStart - blkStart);

                for (size_t i = 0; i < count; ++i) {
                    const float w = wPtr[i];
                    dPtr[i * 2] += sPtr[i * 2] * w;
                    dPtr[i * 2 + 1] += sPtr[i * 2 + 1] * w;
                    nPtr[i] += w;
                }
            }

            float* dPtr = out.data() + blkStart * 2;
            for (size_t i = 0; i < blkLen; ++i) {
                const float inv = (normBlk[i] > 1e-4f) ? (1.0f / normBlk[i]) : 1.0f;
                dPtr[i * 2] *= inv;
                dPtr[i * 2 + 1] *= inv;
            }
        }
    };

    if (hwThreads > 1 && outFrames > 131072u) {
        std::vector<std::thread> workers;
        workers.reserve(hwThreads);
        const size_t chunk = (outFrames + hwThreads - 1) / hwThreads;
        for (unsigned t = 0; t < hwThreads; ++t) {
            const size_t b = t * chunk;
            const size_t e = std::min(outFrames, b + chunk);
            if (b < e) workers.emplace_back(synthSlice, b, e);
        }
        for (auto& w : workers) w.join();
    } else {
        synthSlice(0, outFrames);
    }

    if (progressPct) progressPct->store(progressEnd, std::memory_order_relaxed);
    return out;
}

std::vector<float> RemixEngine::timeStretchAndPitchShift(
    const float* stereoIn,
    size_t inFrames,
    float timeScale,
    float pitchSemitones,
    std::atomic<int>* progressPct,
    int progressStart,
    int progressEnd,
    const std::atomic<bool>* cancelFlag
) {
    if (!stereoIn || inFrames == 0) return {};

    const float clampedScale = clampf(timeScale, 0.50f, 2.0f);
    const float st = clampf(pitchSemitones, -5.0f, 7.0f);
    const size_t targetFrames = std::max<size_t>(
        1,
        static_cast<size_t>(std::llround(static_cast<double>(inFrames) * clampedScale))
    );

    if (std::fabs(st) < 0.05f) {
        return wsolaTimeStretch(stereoIn, inFrames, clampedScale, progressPct, progressStart, progressEnd, cancelFlag);
    }

    const int wsolaEnd = progressStart + ((progressEnd - progressStart) * 8) / 10;
    const float pitchFactor = std::pow(2.0f, st / 12.0f);
    const float intermediateScale = clampf(clampedScale * pitchFactor, 0.45f, 2.40f);
    auto stretched = wsolaTimeStretch(stereoIn, inFrames, intermediateScale, progressPct, progressStart, wsolaEnd, cancelFlag);
    if (cancelFlag && cancelFlag->load(std::memory_order_relaxed)) return {};

    auto resampled = resampleStereoCubicParallel(stretched.data(), stretched.size() / 2, targetFrames, cancelFlag);
    if (progressPct) progressPct->store(progressEnd, std::memory_order_relaxed);
    return resampled;
}

// ============================================================================
// ULTRA-TURBO FUSED MULTI-THREADED STUDIO REMIX RENDERER
// 100% L2-cache-hot 16K blocks across up to 6 CPU cores + zero-latency-offset
// O(1) LookaheadLimiter + Dynamic Kick Vocal-Pocket Carving + 50% Less RAM!
// ============================================================================
RemixOutput RemixEngine::renderRemix(
    const float* stereoPcm,
    size_t totalFrames,
    const AnalysisResult& analysis,
    const RemixConfig& config,
    const float* preWarpedPcm,
    size_t preWarpedFrames,
    std::atomic<int>* progressPct,
    int progressStart,
    const std::atomic<bool>* cancelFlag
) {
    const auto tStart = std::chrono::steady_clock::now();
    RemixOutput result;
    if (!stereoPcm || totalFrames == 0) {
        return result;
    }

    auto isCancelled = [&]() -> bool {
        return cancelFlag && cancelFlag->load(std::memory_order_relaxed);
    };

    const float sourceBpm = (config.sourceBpmOverride > 55.0f)
        ? clampf(config.sourceBpmOverride, 60.0f, 240.0f)
        : clampf(analysis.bpm, 60.0f, 240.0f);

    // 1. NEVER-SLOW Tempo Sync (speedRatio >= 1.00f ALWAYS!)
    const TempoSyncResult sync = computeNeverSlowTempoSync(sourceBpm, config.targetBpm, config.tempoSyncMode);
    const float speedRatio = sync.speedRatio;
    const float targetBpm = sync.effectiveTargetBpm;
    const float timeScale = 1.0f / speedRatio;

    // 2. Use pre-warped cache if provided, or zero-cost 1.00x direct view, else parallel WSOLA!
    std::vector<float> localWarped;
    const float* warpedPtr = nullptr;
    size_t outFrames = 0;

    if (preWarpedPcm && preWarpedFrames > 0) {
        warpedPtr = preWarpedPcm;
        outFrames = preWarpedFrames;
    } else if (std::fabs(timeScale - 1.0f) < 0.004f && std::fabs(config.vocalPitchSemitones) < 0.05f) {
        warpedPtr = stereoPcm;
        outFrames = totalFrames;
    } else {
        localWarped = timeStretchAndPitchShift(
            stereoPcm, totalFrames, timeScale, config.vocalPitchSemitones,
            progressPct, 0, progressStart, cancelFlag
        );
        if (isCancelled() || localWarped.empty()) {
            result.cancelled = true;
            return result;
        }
        warpedPtr = localWarped.data();
        outFrames = localWarped.size() / 2;
    }
    const float outDurSec = static_cast<float>(outFrames) / kSampleRate;

    if (progressPct) progressPct->store(std::max(progressStart, 10), std::memory_order_relaxed);

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
    const double invBeatFrames = 1.0 / static_cast<double>(beatFramesF);
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

    // 5. Synthesize Immutable Studio Kick, Percussion & Genre Synth Assets ONCE (~15 ms!)
    const int switchBars = (config.kickSwitchBars == 8 || config.kickSwitchBars == 16) ? config.kickSwitchBars : 4;
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

    if (isCancelled()) {
        result.cancelled = true;
        return result;
    }

    // 6. Build Flat Timeline Event Schedule (< 1 ms, ~100 KB, zero 85 MB kickBus allocation!)
    std::vector<std::vector<float>> ownedBuildups;
    ownedBuildups.reserve(frameSections.size() * 2 + 2);
    std::vector<MixEvent> oneShotEvents;
    std::vector<MixEvent> longEvents; // buildups & looped genre layers
    oneShotEvents.reserve(4096);

    auto pushOneShot = [&](const std::vector<float>& asset, size_t startF, size_t secEndF, float gL, float gR) {
        if (asset.empty() || startF >= secEndF) return;
        const size_t eF = std::min(secEndF, startF + asset.size() / 2);
        oneShotEvents.push_back({&asset, startF, eF, gL, gR, false, 0});
    };

    for (const auto& sec : frameSections) {
        if (sec.endFrame <= sec.startFrame) continue;
        const float secDurSec = static_cast<float>(sec.endFrame - sec.startFrame) / kSampleRate;

        if (sec.type == SectionType::BUILDUP && secDurSec >= 0.45f) {
            ownedBuildups.push_back(HardcoreSynth::renderSnareBuildupRoll(targetBpm, secDurSec, effectiveRootHz, config.zaagAggression));
            const auto& rollRef = ownedBuildups.back();
            ownedBuildups.push_back(HardcoreSynth::renderNoiseRiser(secDurSec, effectiveRootHz, config.zaagAggression));
            const auto& riserRef = ownedBuildups.back();

            const float rollGain = (config.genreTheme == RemixGenreTheme::TECHNO_CLUB) ? 0.46f : 0.64f;
            longEvents.push_back({&rollRef, sec.startFrame, std::min(sec.endFrame, sec.startFrame + rollRef.size() / 2), rollGain, rollGain, false, 0});
            longEvents.push_back({&riserRef, sec.startFrame, std::min(sec.endFrame, sec.startFrame + riserRef.size() / 2), 0.44f, 0.44f, false, 0});
        } else if (sec.type == SectionType::FAKE_DROP) {
            const size_t silenceCutoff = sec.startFrame + static_cast<size_t>((sec.endFrame - sec.startFrame) * 0.78f);
            pushOneShot(kickSet1[0], sec.startFrame, silenceCutoff, 0.86f, 0.86f);
            if (!screechA.empty() && secDurSec >= 0.25f) {
                const size_t stabPos = sec.startFrame + static_cast<size_t>(std::min(beatFramesF * 0.5f, (silenceCutoff - sec.startFrame) * 0.35f));
                pushOneShot(screechA, stabPos, silenceCutoff, 0.50f, 0.50f);
            }
        } else if (sec.type == SectionType::DROP_1 || sec.type == SectionType::DROP_2) {
            const bool isDrop2 = (sec.type == SectionType::DROP_2);
            pushOneShot(subImpact, sec.startFrame, sec.endFrame, 0.46f, 0.46f);

            const size_t fadeF = std::min<size_t>(2205, (sec.endFrame - sec.startFrame) / 4);
            if (!acid303Loop.empty()) {
                const float acidGain = (config.genreTheme == RemixGenreTheme::TECHNO_CLUB)
                    ? (isDrop2 ? 0.52f : 0.32f) * synthAmt
                    : (isDrop2 ? 0.68f : 0.48f) * synthAmt;
                longEvents.push_back({&acid303Loop, sec.startFrame, sec.endFrame, acidGain, acidGain, true, fadeF});
            }
            if (!euphoricPadLoop.empty()) {
                const float padGain = (isDrop2 ? 0.62f : 0.46f) * synthAmt;
                longEvents.push_back({&euphoricPadLoop, sec.startFrame, sec.endFrame, padGain, padGain, true, fadeF});
            }

            const int numDropBeats = static_cast<int>(std::floor(static_cast<double>(sec.endFrame - sec.startFrame) * invBeatFrames));
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

                if (config.enableTripletRolls && isTurnaroundBar && beatInBar == 3 &&
                    config.genreTheme != RemixGenreTheme::TECHNO_CLUB) {
                    const float tripStep = beatFramesF / 3.0f;
                    for (int t = 0; t < 3; ++t) {
                        const size_t tStart = beatStart + static_cast<size_t>(std::llround(t * tripStep));
                        const float accent = 0.85f + 0.06f * static_cast<float>(t);
                        pushOneShot(activeSet[4], tStart, sec.endFrame, accent, accent);
                    }
                } else {
                    int pitchIdx = 0;
                    if (config.genreTheme == RemixGenreTheme::FRENCHCORE && config.enablePitchKickMelody) {
                        pitchIdx = dropBarIdx % 4;
                    } else if (config.enablePitchKickMelody && isTurnaroundBar && config.genreTheme != RemixGenreTheme::TECHNO_CLUB) {
                        static constexpr std::array<int, 4> kTurnaround = {0, 1, 2, 3};
                        pitchIdx = kTurnaround[beatInBar];
                    } else if (config.enablePitchKickMelody && (dropBarNum % 2 == 0) && beatInBar == 3 && config.genreTheme != RemixGenreTheme::TECHNO_CLUB) {
                        pitchIdx = 1;
                    }
                    const float kGain = isDrop2 ? 1.02f : 0.98f;
                    pushOneShot(activeSet[pitchIdx], beatStart, sec.endFrame, kGain, kGain);
                }

                if (percAmt > 0.05f) {
                    const size_t offbeatPos = beatStart + static_cast<size_t>(beatFramesF * 0.5f);
                    if (offbeatPos < sec.endFrame) {
                        const float hatGain = (isDrop2 ? 0.34f : 0.27f) * percAmt;
                        pushOneShot(openHat, offbeatPos, sec.endFrame, hatGain, hatGain);
                    }

                    if (config.genreTheme == RemixGenreTheme::TECHNO_CLUB ||
                        config.genreTheme == RemixGenreTheme::HARD_TECHNO) {
                        for (int s16 = 1; s16 < 4; ++s16) {
                            if (s16 == 2) continue;
                            const size_t p16 = beatStart + static_cast<size_t>(beatFramesF * (0.25f * s16));
                            if (p16 < sec.endFrame) {
                                const float g16 = ((s16 == 3) ? 0.28f : 0.20f) * percAmt;
                                pushOneShot(closedHat, p16, sec.endFrame, g16, g16);
                            }
                        }
                    }

                    if ((beatInBar == 1 || beatInBar == 3) &&
                        (config.genreTheme == RemixGenreTheme::TECHNO_CLUB ||
                         config.genreTheme == RemixGenreTheme::HARD_TECHNO ||
                         config.genreTheme == RemixGenreTheme::GABBER)) {
                        const float clapGain = (isDrop2 ? 0.56f : 0.45f) * percAmt;
                        pushOneShot(clap909, beatStart, sec.endFrame, clapGain, clapGain);
                    }

                    if (isDrop2 && (config.genreTheme == RemixGenreTheme::TECHNO_CLUB ||
                                    config.genreTheme == RemixGenreTheme::HARD_TECHNO ||
                                    config.genreTheme == RemixGenreTheme::GABBER ||
                                    config.genreTheme == RemixGenreTheme::FRENCHCORE)) {
                        const float rideGain = 0.30f * percAmt;
                        pushOneShot(ride909, beatStart, sec.endFrame, rideGain, rideGain);
                        if (offbeatPos < sec.endFrame) {
                            pushOneShot(ride909, offbeatPos, sec.endFrame, rideGain * 0.72f, rideGain * 0.72f);
                        }
                    }
                }

                if (!hooverStab.empty() && (dropBarIdx % 2 == 0) && beatInBar == 0) {
                    const float hGain = (isDrop2 ? 0.55f : 0.42f) * synthAmt;
                    pushOneShot(hooverStab, beatStart, sec.endFrame, hGain, hGain);
                }

                if (!screechA.empty()) {
                    if ((isSwitchBoundaryBar && beatInBar == 2) || (isDrop2 && (dropBarNum % 2 == 1) && beatInBar == 0)) {
                        const auto& sc = (dropBarNum % 2 == 0) ? screechA : screechB;
                        const float scGain = isDrop2 ? 0.34f : 0.28f;
                        pushOneShot(sc, beatStart, sec.endFrame, scGain, scGain);
                    }
                }
            }
        }
    }

    std::sort(oneShotEvents.begin(), oneShotEvents.end(), [](const MixEvent& a, const MixEvent& b) {
        return a.startFrame < b.startFrame;
    });

    // 7. FUSED L2-CACHE-HOT MULTI-THREADED RENDER + MASTERING ENGINE!
    //    Single output buffer (50% less peak RAM!), 6-core parallel execution, 97 ms filter+limiter
    //    warm-up per thread + exact 4 ms lookahead latency compensation (0.00 ms Beat 1 precision!).
    std::vector<float> outPcm(outFrames * 2, 0.0f);

    const float bassCutHz = clampf(config.bassCutHz, 110.0f, 340.0f);
    const float origMixGain = clampf(config.originalMix, 0.15f, 1.0f);
    const float vocalClarity = clampf(config.vocalClarity, 0.0f, 1.0f);
    const float epicVocalBoost = clampf(config.epicVocalBoost, 0.0f, 1.0f);
    const float vocalScDepth = clampf(config.vocalSidechain, 0.0f, 0.85f);
    const float stutterAmt = clampf(config.stutterAmount, 0.0f, 1.0f);
    const float kickGain = clampf(config.kickLevel, 0.35f, 1.25f);
    const int masterMode = std::max(0, std::min(2, config.masterMode));
    const float ottDepth = (masterMode == 0) ? 0.22f : ((masterMode == 1) ? 0.36f : 0.52f);
    const float preGain = (masterMode == 0) ? 1.32f : ((masterMode == 1) ? 1.46f : 1.60f);
    const float ottMidScale = 1.0f + 0.09f * epicVocalBoost;
    const float sideScale = 1.02f - 0.16f * vocalClarity;
    const bool isTechnoOrFrenchcore = (config.genreTheme == RemixGenreTheme::TECHNO_CLUB ||
                                       config.genreTheme == RemixGenreTheme::FRENCHCORE);
    const float envSlew = std::exp(-1.0f / (0.012f * kSampleRate));
    const float dropSlew = std::exp(-1.0f / (0.0022f * kSampleRate)); // crisp 2.2 ms zero-click drop slam

    constexpr size_t kBlockFrames = 16384; // 128 KB stereo block -> 100% L2-cache resident!
    constexpr size_t kWarmupFrames = 4096; // 93 ms pre-roll settles every filter, OTT & limiter!
    const size_t totalBlocks = std::max<size_t>(1, (outFrames + kBlockFrames - 1) / kBlockFrames);
    std::atomic<size_t> doneBlocks{0};
    const int renderPctBase = std::max(progressStart, 10);

    const unsigned hwThreads = std::max(1u, std::min(6u, std::thread::hardware_concurrency()));
    const unsigned numWorkers = (outFrames > 65536u) ? hwThreads : 1u;

    std::vector<double> workerSumSq(numWorkers, 0.0);
    std::vector<float> workerPeak(numWorkers, 0.0f);

    auto renderSlice = [&](unsigned workerId, size_t sliceStart, size_t sliceEnd) {
        if (sliceStart >= sliceEnd) return;

        // Thread-local DSP state (zero lock contention, 100% in L1 cache!)
        LinkwitzRiley4 bassSplitL, bassSplitR;
        LinkwitzRiley2 vocalSplitL, vocalSplitR;
        bassSplitL.configure(bassCutHz);
        bassSplitR.configure(bassCutHz);
        vocalSplitL.configure(680.0f);
        vocalSplitR.configure(680.0f);

        Biquad midDeMud, midPresence, midUpperFocus, midAir, sideVocalDip, buildHpfL, buildHpfR;
        midDeMud.configure(BiquadType::PEAK, 310.0f, 1.35f, -2.6f * vocalClarity);
        midPresence.configure(BiquadType::PEAK, 2150.0f, 1.05f, (5.0f + 2.0f * epicVocalBoost) * vocalClarity);
        midUpperFocus.configure(BiquadType::PEAK, 3550.0f, 1.30f, (2.4f + 1.5f * epicVocalBoost) * vocalClarity);
        midAir.configure(BiquadType::HIGH_SHELF, 7000.0f, 0.707f, 2.3f * vocalClarity);
        sideVocalDip.configure(BiquadType::PEAK, 1900.0f, 1.0f, -2.0f * vocalClarity);
        buildHpfL.configure(BiquadType::HPF, bassCutHz, 1.15f);
        buildHpfR.configure(BiquadType::HPF, bassCutHz, 1.15f);

        VocalAutoLeveller vocalLeveller;
        vocalLeveller.configure();

        Biquad kickVocCarveL, kickVocCarveR;
        kickVocCarveL.configure(BiquadType::PEAK, 1850.0f, 1.05f, 0.0f);
        kickVocCarveR.configure(BiquadType::PEAK, 1850.0f, 1.05f, 0.0f);
        float lastCarveDb = 0.0f;

        OnePoleDcBlocker dcL, dcR;
        Ott3Band masterOtt;
        masterOtt.configure(130.0f, 2600.0f);
        OversampledClipper2x clipL, clipR;
        LookaheadLimiter limiter;
        limiter.configure(0.97f, 4.0f, 58.0f);
        const size_t limLatency = limiter.latencySamples();

        float lowGainSm = 0.85f;
        float midGainSm = 1.0f;
        float vocGainSm = 1.0f;
        float buildWetSm = 0.0f;
        float localPeak = 0.0f;
        double localSumSq = 0.0;

        // Input timeline range: start kWarmupFrames early, end limLatency past sliceEnd so output is 0.00ms aligned!
        const size_t warmStart = (sliceStart >= kWarmupFrames) ? (sliceStart - kWarmupFrames) : 0;
        const size_t feedEnd = sliceEnd + limLatency; // may exceed outFrames by limLatency on last worker (flushes limiter!)

        // Rolling section & one-shot event cursors -> O(1) lookup!
        size_t secCursor = 0;
        while (secCursor + 1 < frameSections.size() && frameSections[secCursor].endFrame <= warmStart) {
            ++secCursor;
        }
        size_t evCursor = 0;
        while (evCursor < oneShotEvents.size() && oneShotEvents[evCursor].endFrame <= warmStart) {
            ++evCursor;
        }

        std::vector<float> blkKick(kBlockFrames * 2, 0.0f);

        for (size_t blkStart = warmStart; blkStart < feedEnd; blkStart += kBlockFrames) {
            if (isCancelled()) return;
            const size_t blkEnd = std::min(feedEnd, blkStart + kBlockFrames);
            const size_t blkLen = blkEnd - blkStart;

            // 1. Render ONLY this 128 KB block's kick/percussion/synth layer into L2-hot blkKick!
            std::fill(blkKick.begin(), blkKick.begin() + blkLen * 2, 0.0f);

            if (blkStart < outFrames) {
                const size_t audioBlkEnd = std::min(blkEnd, outFrames);

                // Advance evCursor past expired one-shot events (max one-shot len <= 2.5s; keep earliest active)
                while (evCursor < oneShotEvents.size() && oneShotEvents[evCursor].endFrame <= blkStart) {
                    ++evCursor;
                }
                for (size_t eIdx = evCursor; eIdx < oneShotEvents.size(); ++eIdx) {
                    const auto& ev = oneShotEvents[eIdx];
                    if (ev.startFrame >= audioBlkEnd) break;
                    if (ev.endFrame > blkStart) {
                        mixEventIntoBlock(blkKick.data(), blkStart, audioBlkEnd, ev);
                    }
                }
                for (const auto& ev : longEvents) {
                    if (ev.startFrame < audioBlkEnd && ev.endFrame > blkStart) {
                        mixEventIntoBlock(blkKick.data(), blkStart, audioBlkEnd, ev);
                    }
                }
            }

            // 2. Fused Vocal Split + Dynamic Vocal-Pocket Carving + Mastering + Zero-Latency Limiter!
            for (size_t j = 0; j < blkLen; ++j) {
                const size_t i = blkStart + j;

                float l = 0.0f;
                float r = 0.0f;

                if (i < outFrames) {
                    while (secCursor + 1 < frameSections.size() && i >= frameSections[secCursor].endFrame) {
                        ++secCursor;
                    }
                    const FrameSection* sec = frameSections.empty() ? nullptr : &frameSections[secCursor];
                    const SectionType sType = sec ? sec->type : SectionType::DROP_1;
                    const size_t secStart = sec ? sec->startFrame : 0;
                    const size_t secLen = sec ? std::max<size_t>(1, sec->endFrame - sec->startFrame) : outFrames;
                    const float secProg = clampf(static_cast<float>(i - secStart) / static_cast<float>(secLen), 0.0f, 1.0f);

                    const double localFrames = (sType == SectionType::DROP_1 || sType == SectionType::DROP_2)
                        ? static_cast<double>(i - secStart)
                        : static_cast<double>(i >= downbeatFrame ? (i - downbeatFrame) : 0);
                    const double beatPos = localFrames * invBeatFrames;
                    const int beatIdx = static_cast<int>(beatPos);
                    const float beatFrac = static_cast<float>(beatPos - static_cast<double>(beatIdx));
                    const int barIdx = beatIdx >> 2;
                    const int beatInBar = beatIdx & 3;

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
                        if ((barIdx & 3) == 3 && beatInBar == 3) {
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

                    const bool isDrop = (sType == SectionType::DROP_1 || sType == SectionType::DROP_2);
                    const float levelLift = vocalLeveller.process(vhMidSculpted, isDrop ? epicVocalBoost : (epicVocalBoost * 0.35f));
                    const float vhMidFinal = vhMidSculpted * levelLift;

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
                        targetVoc = (1.06f + 0.16f * epicVocalBoost) * g;
                    } else if (sType == SectionType::FAKE_DROP) {
                        targetLow = 0.0f;
                        targetMid = 0.84f * (0.60f + 0.40f * origMixGain);
                        targetVoc = 1.14f * (0.60f + 0.40f * origMixGain);
                    } else {
                        targetLow = 0.0f;
                        const float dLin = clampf((beatFrac - 0.04f) * (1.0f / 0.48f), 0.0f, 1.0f);
                        const float duckShape = (beatFrac < 0.04f)
                            ? (beatFrac * 25.0f)
                            : (1.0f - (0.60f * std::sqrt(dLin) + 0.40f * dLin));
                        const float lowMidDuck = 1.0f - 0.76f * duckShape;
                        const float vocalDuck = 1.0f - vocalScDepth * duckShape;

                        const float dropOrigBase = 0.58f + 0.74f * origMixGain;
                        targetMid = dropOrigBase * 0.74f * lowMidDuck;
                        targetVoc = dropOrigBase * (1.18f + 0.50f * vocalClarity + 0.24f * epicVocalBoost) * vocalDuck;
                    }

                    const float slew = isDrop ? dropSlew : envSlew;
                    lowGainSm = lowGainSm * envSlew + targetLow * (1.0f - envSlew);
                    midGainSm = midGainSm * slew + targetMid * (1.0f - slew);
                    vocGainSm = vocGainSm * slew + targetVoc * (1.0f - slew);
                    buildWetSm = buildWetSm * envSlew + targetBuildWet * (1.0f - envSlew);

                    float vL = (loL * lowGainSm + lmL * midGainSm + vhL * vocGainSm) * chopEnv;
                    float vR = (loR * lowGainSm + lmR * midGainSm + vhR * vocGainSm) * chopEnv;

                    if (buildWetSm > 0.005f) {
                        if ((i & 63u) == 0u && sType == SectionType::BUILDUP) {
                            const float cut = bassCutHz + 920.0f * secProg * secProg;
                            buildHpfL.configure(BiquadType::HPF, cut, 1.22f);
                            buildHpfR.configure(BiquadType::HPF, cut, 1.22f);
                        }
                        const float filtL = buildHpfL.process(vL);
                        const float filtR = buildHpfR.process(vR);
                        vL = vL * (1.0f - buildWetSm) + filtL * buildWetSm;
                        vR = vR * (1.0f - buildWetSm) + filtR * buildWetSm;
                    }

                    // Dynamic Kick Vocal-Pocket Carving (-5.8 dB at 1.85 kHz ONLY when vocals sing!)
                    if ((i & 63u) == 0u && epicVocalBoost > 0.05f) {
                        const float vEnv = vocalLeveller.currentEnvelope();
                        const float activeFrac = clampf((vEnv - 0.022f) * (1.0f / 0.21f), 0.0f, 1.0f);
                        const float carveDb = -5.8f * epicVocalBoost * activeFrac;
                        if (std::fabs(carveDb - lastCarveDb) > 0.12f) {
                            lastCarveDb = carveDb;
                            kickVocCarveL.configure(BiquadType::PEAK, 1850.0f, 1.05f, carveDb);
                            kickVocCarveR.configure(BiquadType::PEAK, 1850.0f, 1.05f, carveDb);
                        }
                    }

                    const float kL = fastTanh(kickVocCarveL.process(blkKick[j * 2]) * 1.03f) * kickGain;
                    const float kR = fastTanh(kickVocCarveR.process(blkKick[j * 2 + 1]) * 1.03f) * kickGain;

                    l = dcL.process(vL + kL);
                    r = dcR.process(vR + kR);

                    masterOtt.processStereo(l, r, ottDepth, 1.0f, 1.06f, ottMidScale);

                    l *= preGain;
                    r *= preGain;

                    if (masterMode == 2) {
                        l = clipL.process(l, 1.18f, 0.98f, 0.01f);
                        r = clipR.process(r, 1.18f, 0.98f, 0.01f);
                    } else if (masterMode == 1) {
                        l = fastTanh(l * 1.04f);
                        r = fastTanh(r * 1.04f);
                    }
                }

                // O(1) Lookahead Limiter with exact 0.00 ms latency compensation!
                limiter.processStereo(l, r);

                if (i >= sliceStart + limLatency) {
                    const size_t outIdx = i - limLatency;
                    if (outIdx < sliceEnd) {
                        const float fl = clampf(std::isfinite(l) ? l : 0.0f, -0.98f, 0.98f);
                        const float fr = clampf(std::isfinite(r) ? r : 0.0f, -0.98f, 0.98f);
                        outPcm[outIdx * 2] = fl;
                        outPcm[outIdx * 2 + 1] = fr;
                        localPeak = std::max(localPeak, std::max(std::fabs(fl), std::fabs(fr)));
                        localSumSq += 0.5 * (static_cast<double>(fl) * fl + static_cast<double>(fr) * fr);
                    }
                }
            }

            if (progressPct && blkEnd > sliceStart + limLatency) {
                const size_t done = doneBlocks.fetch_add(1, std::memory_order_relaxed) + 1;
                const int pct = renderPctBase + static_cast<int>((done * static_cast<size_t>(96 - renderPctBase)) / totalBlocks);
                progressPct->store(std::min(96, pct), std::memory_order_relaxed);
            }
        }

        workerPeak[workerId] = localPeak;
        workerSumSq[workerId] = localSumSq;
    };

    if (numWorkers > 1) {
        std::vector<std::thread> workers;
        workers.reserve(numWorkers);
        for (unsigned t = 0; t < numWorkers; ++t) {
            const size_t s0 = (static_cast<size_t>(t) * outFrames) / numWorkers;
            const size_t s1 = (static_cast<size_t>(t + 1) * outFrames) / numWorkers;
            workers.emplace_back(renderSlice, t, s0, s1);
        }
        for (auto& w : workers) w.join();
    } else {
        renderSlice(0, 0, outFrames);
    }

    localWarped.clear();
    localWarped.shrink_to_fit();

    if (isCancelled()) {
        result.cancelled = true;
        return result;
    }

    // 8. Smooth 10 ms edge fades + combine worker telemetry
    const size_t edgeFade = std::min<size_t>(441, outFrames / 4);
    for (size_t i = 0; i < edgeFade; ++i) {
        const float g = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, edgeFade));
        outPcm[i * 2] *= g;
        outPcm[i * 2 + 1] *= g;
        const size_t tail = outFrames - 1 - i;
        outPcm[tail * 2] *= g;
        outPcm[tail * 2 + 1] *= g;
    }

    float peakAbs = 0.0f;
    double sumSq = 0.0;
    for (unsigned t = 0; t < numWorkers; ++t) {
        peakAbs = std::max(peakAbs, workerPeak[t]);
        sumSq += workerSumSq[t];
    }

    const float rms = static_cast<float>(std::sqrt(sumSq / static_cast<double>(std::max<size_t>(1, outFrames))));
    result.waveformPeaks = MusicAnalyzer::extractWaveformPeaks(outPcm.data(), outFrames, 240);
    if (progressPct) progressPct->store(100, std::memory_order_relaxed);

    const auto tEnd = std::chrono::steady_clock::now();
    const float renderMs = std::chrono::duration<float, std::milli>(tEnd - tStart).count();

    result.stereoPcm = std::move(outPcm);
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
