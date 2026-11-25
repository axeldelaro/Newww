#include "remix_engine.h"
#include "dsp_utils.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>

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

std::pair<float, float> RemixEngine::computeMusicalTempoSync(float sourceBpm, float targetBpm) {
    const float src = clampf(sourceBpm, 60.0f, 240.0f);
    const float tgt = clampf(targetBpm, 140.0f, 250.0f);

    const std::array<float, 3> mults = {0.5f, 1.0f, 2.0f};
    float bestSpeed = tgt / src;
    float bestMult = 1.0f;
    float bestCost = std::numeric_limits<float>::max();

    for (float m : mults) {
        const float speed = (tgt * m) / src;
        if (speed < 0.62f || speed > 1.72f) continue;
        // Prefer staying closest to natural 1.06x speed so vocals sound super natural & clear!
        const float cost = std::fabs(std::log2(speed / 1.06f));
        if (cost < bestCost) {
            bestCost = cost;
            bestSpeed = speed;
            bestMult = m;
        }
    }

    bestSpeed = clampf(bestSpeed, 0.65f, 1.65f);
    return {bestSpeed, bestMult};
}

std::vector<float> RemixEngine::wsolaTimeStretch(
    const float* stereoIn,
    size_t inFrames,
    float timeScale
) {
    if (!stereoIn || inFrames == 0) return {};

    const float scale = clampf(timeScale, 0.45f, 2.40f);
    const size_t outFrames = std::max<size_t>(1, static_cast<size_t>(std::llround(static_cast<double>(inFrames) * scale)));

    if (std::fabs(scale - 1.0f) < 0.002f) {
        return std::vector<float>(stereoIn, stereoIn + inFrames * 2);
    }

    // Studio-grade 46.4 ms Hann window with 75% overlap (exact COLA) & wide similarity window -> smooth crystal-clear vocals!
    constexpr size_t kWin = 2048;
    constexpr size_t kHopOut = 512;
    constexpr int kSearchTol = 320; // ±7.25 ms waveform lock

    if (inFrames < kWin * 2) {
        return resampleStereoCubic(stereoIn, inFrames, outFrames);
    }

    std::vector<float> monoIn(inFrames);
    for (size_t i = 0; i < inFrames; ++i) {
        monoIn[i] = 0.5f * (sanitize(stereoIn[i * 2]) + sanitize(stereoIn[i * 2 + 1]));
    }

    const auto hann = makeHannWindow(kWin);
    std::vector<float> out(outFrames * 2, 0.0f);
    std::vector<float> normAcc(outFrames, 0.0f);

    for (size_t i = 0; i < kWin && i < outFrames && i < inFrames; ++i) {
        const float w = hann[i];
        out[i * 2] += stereoIn[i * 2] * w;
        out[i * 2 + 1] += stereoIn[i * 2 + 1] * w;
        normAcc[i] += w;
    }

    size_t prevSrcPos = 0;
    const double hopIn = static_cast<double>(kHopOut) / static_cast<double>(scale);
    size_t outPos = kHopOut;
    size_t stepIdx = 1;

    while (outPos < outFrames) {
        const int nominalSrc = static_cast<int>(std::llround(static_cast<double>(stepIdx) * hopIn));
        const int refPos = static_cast<int>(prevSrcPos + kHopOut);

        const int maxValidStart = static_cast<int>(inFrames) - static_cast<int>(kWin);
        const int clampedNominal = std::max(0, std::min(maxValidStart, nominalSrc));

        int bestSrc = clampedNominal;
        if (refPos >= 0 && refPos <= maxValidStart) {
            const int searchLo = std::max(0, clampedNominal - kSearchTol);
            const int searchHi = std::min(maxValidStart, clampedNominal + kSearchTol);

            float bestCorr = -1e30f;
            int coarseBest = clampedNominal;
            constexpr size_t kCmpLen = 640;
            for (int cand = searchLo; cand <= searchHi; cand += 4) {
                float dot = 0.0f;
                float energy = 1e-6f;
                for (size_t k = 0; k < kCmpLen; k += 2) {
                    const float a = monoIn[static_cast<size_t>(refPos) + k];
                    const float b = monoIn[static_cast<size_t>(cand) + k];
                    dot += a * b;
                    energy += b * b;
                }
                const float score = dot / std::sqrt(energy);
                if (score > bestCorr) {
                    bestCorr = score;
                    coarseBest = cand;
                }
            }

            bestSrc = coarseBest;
            const int fineLo = std::max(searchLo, coarseBest - 3);
            const int fineHi = std::min(searchHi, coarseBest + 3);
            for (int cand = fineLo; cand <= fineHi; ++cand) {
                float dot = 0.0f;
                float energy = 1e-6f;
                for (size_t k = 0; k < kCmpLen; ++k) {
                    const float a = monoIn[static_cast<size_t>(refPos) + k];
                    const float b = monoIn[static_cast<size_t>(cand) + k];
                    dot += a * b;
                    energy += b * b;
                }
                const float score = dot / std::sqrt(energy);
                if (score > bestCorr) {
                    bestCorr = score;
                    bestSrc = cand;
                }
            }
        }

        const size_t copyLen = std::min(kWin, std::min(outFrames - outPos, inFrames - static_cast<size_t>(bestSrc)));
        for (size_t i = 0; i < copyLen; ++i) {
            const float w = hann[i];
            const size_t sIdx = static_cast<size_t>(bestSrc) + i;
            const size_t dIdx = outPos + i;
            out[dIdx * 2] += stereoIn[sIdx * 2] * w;
            out[dIdx * 2 + 1] += stereoIn[sIdx * 2 + 1] * w;
            normAcc[dIdx] += w;
        }

        prevSrcPos = static_cast<size_t>(bestSrc);
        outPos += kHopOut;
        ++stepIdx;
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
    const RemixConfig& config
) {
    RemixOutput result;
    if (!stereoPcm || totalFrames == 0) {
        return result;
    }

    const float targetBpm = clampf(config.targetBpm, 150.0f, 240.0f);
    const float sourceBpm = (config.sourceBpmOverride > 55.0f)
        ? clampf(config.sourceBpmOverride, 60.0f, 240.0f)
        : clampf(analysis.bpm, 60.0f, 240.0f);
    const auto [speedRatio, gridMult] = computeMusicalTempoSync(sourceBpm, targetBpm);
    const float timeScale = 1.0f / speedRatio;

    // 1. Warp original track cleanly to exact hardcore beat grid + optional vocal pitch shift!
    auto warped = timeStretchAndPitchShift(
        stereoPcm,
        totalFrames,
        timeScale,
        config.vocalPitchSemitones
    );
    const size_t outFrames = warped.size() / 2;
    const float outDurSec = static_cast<float>(outFrames) / kSampleRate;

    // 2. Shift rootHz if vocal pitch shifted (keeps kicks harmonically locked to pitched vocals!)
    float effectiveRootHz = analysis.rootHz;
    int effectiveKeyIdx = analysis.keyIndex;
    if (std::fabs(config.vocalPitchSemitones) >= 0.5f) {
        const int shiftSt = static_cast<int>(std::round(config.vocalPitchSemitones));
        effectiveKeyIdx = ((analysis.keyIndex + shiftSt) % 12 + 12) % 12;
        effectiveRootHz = clampf(midiToHz(static_cast<float>(pitchClassToSubMidi(effectiveKeyIdx))), 41.2f, 73.5f);
    }
    const std::string effectiveKeyName = formatKeyName(effectiveKeyIdx, analysis.isMinor);

    // 3. Build Exact Bar-Quantized Arrangement Sections!
    ArrangementOptions arrOpts;
    arrOpts.dropMode = config.dropMode;
    arrOpts.buildupBars = config.buildupBars;
    arrOpts.enableFakeDrop = config.enableFakeDrop;
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

    // 4. Compute Sample-Exact Warped Beat & Bar Grid!
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

    // Quantize EVERY section start/end onto exact integer bar sample boundaries so ZERO kicks land early or mid-chorus!
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
        if (!frameSections.empty()) {
            sf = frameSections.back().endFrame;
        }
        if (ef < sf) ef = sf;
        frameSections.push_back({sf, ef, s.type, s.label, s.startBar, s.endBar});
    }
    if (!frameSections.empty()) {
        frameSections.front().startFrame = 0;
        frameSections.back().endFrame = outFrames;
    }

    // Update section fracs to reflect exact sample-quantized boundaries for waveform display
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

    // 5. STREAMING SPLIT-BUS VOCAL/ORIGINAL PROCESSING (1 pass -> ZERO duplicate 76 MB origLow/origHigh arrays!)
    //    - Surgical 3-band split: origLow (< bassCutHz), origLowMid (bassCutHz..850 Hz), origVocalHigh (> 850 Hz)
    //    - Clean Mid/Side Vocal Clarity Sculpting (de-mud 320 Hz, presence 2.35 kHz, air 6.5 kHz)
    //    - Frequency-selective sidechain: low-mids duck deeply (74%), vocals duck gently (config.vocalSidechain) -> LYRICS STAY CRYSTAL CLEAR!
    //    - Smooth 15ms section transition smoothing -> ZERO abrupt clicks!
    std::vector<float> vocalBus(outFrames * 2, 0.0f);

    const float bassCutHz = clampf(config.bassCutHz, 110.0f, 340.0f);
    const float origMixGain = clampf(config.originalMix, 0.1f, 1.0f);
    const float vocalClarity = clampf(config.vocalClarity, 0.0f, 1.0f);
    const float vocalScDepth = clampf(config.vocalSidechain, 0.0f, 0.90f);
    const float stutterAmt = clampf(config.stutterAmount, 0.0f, 1.0f);

    LinkwitzRiley4 bassSplitL, bassSplitR;
    LinkwitzRiley4 vocalSplitL, vocalSplitR;
    bassSplitL.configure(bassCutHz);
    bassSplitR.configure(bassCutHz);
    vocalSplitL.configure(850.0f);
    vocalSplitR.configure(850.0f);

    // Surgical Mid-channel vocal sculpting filters (linear & distortion-free!)
    Biquad midDeMud, midPresence, midAir, buildHpfL, buildHpfR;
    midDeMud.configure(BiquadType::PEAK, 320.0f, 1.35f, -2.8f * vocalClarity);
    midPresence.configure(BiquadType::PEAK, 2350.0f, 1.15f, 4.6f * vocalClarity);
    midAir.configure(BiquadType::HIGH_SHELF, 6400.0f, 0.707f, 2.2f * vocalClarity);
    buildHpfL.configure(BiquadType::HPF, bassCutHz, 1.15f);
    buildHpfR.configure(BiquadType::HPF, bassCutHz, 1.15f);

    // Smooth section gain slewing (15 ms time constant -> click-free transitions!)
    const float envSlew = std::exp(-1.0f / (0.015f * kSampleRate));
    float lowGainSm = 0.85f;
    float midGainSm = 1.0f;
    float vocGainSm = 1.0f;
    float buildWetSm = 0.0f;

    for (size_t i = 0; i < outFrames; ++i) {
        const FrameSection* sec = findFrameSection(frameSections, i);
        const SectionType sType = sec ? sec->type : SectionType::DROP_1;
        const size_t secStart = sec ? sec->startFrame : 0;
        const size_t secLen = sec ? std::max<size_t>(1, sec->endFrame - sec->startFrame) : outFrames;
        const float secProg = clampf(static_cast<float>(i - secStart) / static_cast<float>(secLen), 0.0f, 1.0f);

        // Compute beat position locked to current Drop's exact Beat 1 (or global downbeat outside drops)
        const double localFrames = (sType == SectionType::DROP_1 || sType == SectionType::DROP_2)
            ? static_cast<double>(i - secStart)
            : static_cast<double>(i >= downbeatFrame ? (i - downbeatFrame) : 0);
        const double beatPos = localFrames / static_cast<double>(beatFramesF);
        const int beatIdx = static_cast<int>(std::floor(beatPos));
        const float beatFrac = static_cast<float>(beatPos - std::floor(beatPos));
        const int barIdx = beatIdx / 4;
        const int beatInBar = beatIdx % 4;

        // Choose source sample (normal or rhythmic stutter slice during Fake-Drop / Drop turnarounds)
        size_t readFrame = i;
        float chopEnv = 1.0f;

        if (sType == SectionType::FAKE_DROP) {
            // Iconic 1-bar Fake-Drop:
            // Beat 1 (0..25%): fake kick impact + clean vocal phrase start
            // Beats 2..3 (25..78%): rhythmic 1/8 -> 1/16 vocal stutter chop
            // Beat 4 (78..100%): crisp dramatic silence cliff right before DROP 1 SLAMS ON CHORUS BEAT 1!
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
        } else if ((sType == SectionType::DROP_1 || sType == SectionType::DROP_2) && stutterAmt > 0.20f) {
            // Subtle musical stutter ONLY on beat 4 of bar 4/8 turnarounds (never mangles whole chorus lyrics!)
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
                chopEnv = (1.0f - stutterAmt * 0.7f) + (stutterAmt * 0.7f) * fade;
            }
        }

        const float inL = warped[readFrame * 2];
        const float inR = warped[readFrame * 2 + 1];

        // 3-band split of original track
        float loL, hiAllL, lmL, vhL;
        float loR, hiAllR, lmR, vhR;
        bassSplitL.split(inL, loL, hiAllL);
        bassSplitR.split(inR, loR, hiAllR);
        vocalSplitL.split(hiAllL, lmL, vhL);
        vocalSplitR.split(hiAllR, lmR, vhR);

        // Clean Mid/Side vocal clarity sculpting on > 850 Hz + de-mud on low-mid
        const float lmMid = 0.5f * (lmL + lmR);
        const float lmSide = 0.5f * (lmL - lmR);
        const float lmMidClean = midDeMud.process(lmMid);
        lmL = lmMidClean + lmSide;
        lmR = lmMidClean - lmSide;

        const float vhMid = 0.5f * (vhL + vhR);
        const float vhSide = 0.5f * (vhL - vhR);
        const float vhMidClarity = midAir.process(midPresence.process(vhMid));
        // Slight center focus so lyrics sit dead-center above stereo screeches/hats
        const float sideScale = 1.04f - 0.14f * vocalClarity;
        vhL = vhMidClarity + vhSide * sideScale;
        vhR = vhMidClarity - vhSide * sideScale;

        // Target band gains per section (slewed smoothly to prevent any click!)
        float targetLow = 0.82f;
        float targetMid = 0.94f;
        float targetVoc = 0.96f;
        float targetBuildWet = 0.0f;

        if (sType == SectionType::INTRO || sType == SectionType::VOCAL_BREAK || sType == SectionType::MID_BREAK) {
            const float g = 0.74f + 0.22f * origMixGain;
            targetLow = 0.76f * g;
            targetMid = 0.96f * g;
            targetVoc = (1.00f + 0.08f * vocalClarity) * g;
        } else if (sType == SectionType::BUILDUP) {
            targetBuildWet = 1.0f;
            const float g = 0.72f + 0.22f * origMixGain;
            targetLow = (1.0f - secProg) * 0.42f * g;
            targetMid = 0.90f * g;
            targetVoc = 1.02f * g;
        } else if (sType == SectionType::FAKE_DROP) {
            targetLow = 0.0f;
            targetMid = 0.82f * (0.55f + 0.45f * origMixGain);
            targetVoc = 1.06f * (0.55f + 0.45f * origMixGain);
        } else {
            // DROP_1 or DROP_2: Strip original bass (< bassCutHz) completely!
            targetLow = 0.0f;
            // Frequency-selective sidechain:
            // - Low-mids (bassCutHz..850 Hz) duck deeply (74%) to carve huge room for the hardcore kick!
            // - Vocal band (> 850 Hz) ducks ONLY by gentle `vocalScDepth` so lyrics stay intelligible & upfront!
            const float duckShape = (beatFrac < 0.04f)
                ? (beatFrac / 0.04f)
                : (1.0f - std::pow(clampf((beatFrac - 0.04f) / 0.52f, 0.0f, 1.0f), 0.70f));
            const float lowMidDuck = 1.0f - 0.74f * duckShape;
            const float vocalDuck = 1.0f - vocalScDepth * duckShape;

            const float dropOrigBase = 0.46f + 0.74f * origMixGain;
            targetMid = dropOrigBase * 0.78f * lowMidDuck;
            targetVoc = dropOrigBase * (1.12f + 0.46f * vocalClarity) * vocalDuck;
        }

        // Smooth transitions (fast enough to track sidechain while eliminating section-edge clicks)
        const bool isDrop = (sType == SectionType::DROP_1 || sType == SectionType::DROP_2);
        lowGainSm = lowGainSm * envSlew + targetLow * (1.0f - envSlew);
        midGainSm = isDrop ? targetMid : (midGainSm * envSlew + targetMid * (1.0f - envSlew));
        vocGainSm = isDrop ? targetVoc : (vocGainSm * envSlew + targetVoc * (1.0f - envSlew));
        buildWetSm = buildWetSm * envSlew + targetBuildWet * (1.0f - envSlew);

        float outL = (loL * lowGainSm + lmL * midGainSm + vhL * vocGainSm) * chopEnv;
        float outR = (loR * lowGainSm + lmR * midGainSm + vhR * vocGainSm) * chopEnv;

        if (buildWetSm > 0.005f) {
            if (i % 64 == 0) {
                const float cut = bassCutHz + 980.0f * secProg * secProg;
                buildHpfL.configure(BiquadType::HPF, cut, 1.25f);
                buildHpfR.configure(BiquadType::HPF, cut, 1.25f);
            }
            const float filtL = buildHpfL.process(outL);
            const float filtR = buildHpfR.process(outR);
            outL = outL * (1.0f - buildWetSm) + filtL * buildWetSm;
            outR = outR * (1.0f - buildWetSm) + filtR * buildWetSm;
        }

        vocalBus[i * 2] = sanitize(outL);
        vocalBus[i * 2 + 1] = sanitize(outR);
    }

    // Immediately free `warped` BEFORE allocating `kickBus` -> cuts peak native RAM in HALF!
    warped.clear();
    warped.shrink_to_fit();

    // 6. Dedicated Kick + FX Bus (rendered completely separately so kick saturation NEVER distorts vocals!)
    std::vector<float> kickBus(outFrames * 2, 0.0f);

    const int switchBars = (config.kickSwitchBars == 8 || config.kickSwitchBars == 16) ? config.kickSwitchBars : 4;
    const std::array<float, 4> melodyOffsets = {0.0f, 3.0f, 5.0f, 7.0f};

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
    const auto kickSet2Finale = buildKickSet(config.drop2Kick, 0.09f, 0.08f);
    const auto openHat = HardcoreSynth::renderHardcoreHat(beatSec * 0.45f, true, 909u);
    const auto screechA = HardcoreSynth::renderIndustrialScreech(effectiveRootHz, beatSec * 1.35f, config.zaagAggression, 0);
    const auto screechB = HardcoreSynth::renderIndustrialScreech(effectiveRootHz, beatSec * 1.15f, config.zaagAggression, 1);
    const auto subImpact = HardcoreSynth::renderSubImpact(effectiveRootHz, std::min(1.5f, beatSec * 4.0f));

    // 7. Render Sample-Exact Section FX & Beat-Locked Kicks strictly bounded within each section!
    for (const auto& sec : frameSections) {
        if (sec.endFrame <= sec.startFrame) continue;
        const float secDurSec = static_cast<float>(sec.endFrame - sec.startFrame) / kSampleRate;

        if (sec.type == SectionType::BUILDUP && secDurSec >= 0.45f) {
            const auto roll = HardcoreSynth::renderSnareBuildupRoll(targetBpm, secDurSec, effectiveRootHz, config.zaagAggression);
            const auto riser = HardcoreSynth::renderNoiseRiser(secDurSec, effectiveRootHz, config.zaagAggression);
            mixBufferAt(kickBus, roll, sec.startFrame, 0.68f, 0.68f, sec.endFrame);
            mixBufferAt(kickBus, riser, sec.startFrame, 0.48f, 0.48f, sec.endFrame);
        } else if (sec.type == SectionType::FAKE_DROP) {
            // Exact lone fake-kick hit on Beat 1 + crisp screech stab, strictly silenced before DROP 1!
            const size_t silenceCutoff = sec.startFrame + static_cast<size_t>((sec.endFrame - sec.startFrame) * 0.78f);
            mixBufferAt(kickBus, kickSet1[0], sec.startFrame, 0.88f, 0.88f, silenceCutoff);
            if (config.enableScreeches && secDurSec >= 0.25f) {
                const size_t stabPos = sec.startFrame + static_cast<size_t>(std::min(beatFramesF * 0.5f, (silenceCutoff - sec.startFrame) * 0.35f));
                mixBufferAt(kickBus, screechA, stabPos, 0.56f, 0.56f, silenceCutoff);
            }
        } else if (sec.type == SectionType::DROP_1 || sec.type == SectionType::DROP_2) {
            const bool isDrop2 = (sec.type == SectionType::DROP_2);

            // Massive Festival Sub Impact strictly on Bar 1 Beat 1 of the Drop!
            mixBufferAt(kickBus, subImpact, sec.startFrame, 0.50f, 0.50f, sec.endFrame);

            // Sample-locked 4-on-the-floor beats anchored directly to sec.startFrame (0 beat drift!)
            const int numDropBeats = static_cast<int>(std::floor(static_cast<double>(sec.endFrame - sec.startFrame) / static_cast<double>(beatFramesF)));
            for (int lb = 0; lb < numDropBeats; ++lb) {
                const size_t beatStart = sec.startFrame + static_cast<size_t>(std::llround(static_cast<double>(lb) * static_cast<double>(beatFramesF)));
                if (beatStart >= sec.endFrame) break;

                const int dropBarIdx = lb / 4; // 0-indexed bar inside this drop
                const int beatInBar = lb % 4;
                const int dropBarNum = dropBarIdx + 1;

                const int switchPhase = (dropBarIdx / switchBars) % 2;
                const bool useSet2 = isDrop2 ? (switchPhase == 0) : (switchPhase == 1);
                const auto& activeSet = useSet2 ? (isDrop2 ? kickSet2Finale : kickSet2) : kickSet1;

                const bool isTurnaroundBar = (dropBarNum % 4 == 0);
                const bool isSwitchBoundaryBar = (dropBarNum % switchBars == 0);

                if (config.enableTripletRolls && isTurnaroundBar && beatInBar == 3) {
                    const float tripStep = beatFramesF / 3.0f;
                    for (int t = 0; t < 3; ++t) {
                        const size_t tStart = beatStart + static_cast<size_t>(std::llround(t * tripStep));
                        const float accent = 0.86f + 0.06f * static_cast<float>(t);
                        mixBufferAt(kickBus, activeSet[4], tStart, accent, accent, sec.endFrame);
                    }
                } else {
                    int pitchIdx = 0;
                    if (config.enablePitchKickMelody && isTurnaroundBar) {
                        static constexpr std::array<int, 4> kTurnaroundPattern = {0, 1, 2, 3};
                        pitchIdx = kTurnaroundPattern[beatInBar];
                    } else if (config.enablePitchKickMelody && (dropBarNum % 2 == 0) && beatInBar == 3) {
                        pitchIdx = 1;
                    }
                    const float kGain = isDrop2 ? 1.03f : 0.98f;
                    mixBufferAt(kickBus, activeSet[pitchIdx], beatStart, kGain, kGain, sec.endFrame);
                }

                // Crisp 909 Offbeat Open Hi-Hat
                const size_t offbeatPos = beatStart + static_cast<size_t>(beatFramesF * 0.5f);
                if (offbeatPos < sec.endFrame) {
                    const float hatGain = isDrop2 ? 0.28f : 0.23f;
                    mixBufferAt(kickBus, openHat, offbeatPos, hatGain, hatGain, sec.endFrame);
                }

                // Key-locked Industrial Screech Stabs (kept tasteful so vocals stay intelligible!)
                if (config.enableScreeches) {
                    if ((isSwitchBoundaryBar && beatInBar == 2) || (isDrop2 && (dropBarNum % 2 == 1) && beatInBar == 0)) {
                        const auto& sc = (dropBarNum % 2 == 0) ? screechA : screechB;
                        const float scGain = isDrop2 ? 0.38f : 0.32f;
                        mixBufferAt(kickBus, sc, beatStart, scGain, scGain, sec.endFrame);
                    }
                }
            }
        }
    }

    // 8. Studio Split-Bus Summing + Transparent Lookahead Mastering Chain!
    //    - Glue kickBus softly on its OWN bus FIRST (so kicks never intermodulate vocals!)
    //    - Sum clean vocalBus + scaled kickBus IN-PLACE into vocalBus, then free kickBus!
    const float kickGain = clampf(config.kickLevel, 0.35f, 1.25f);
    for (size_t i = 0; i < outFrames; ++i) {
        const float kL = std::tanh(kickBus[i * 2] * 1.04f) * kickGain;
        const float kR = std::tanh(kickBus[i * 2 + 1] * 1.04f) * kickGain;
        vocalBus[i * 2] = sanitize(vocalBus[i * 2] + kL);
        vocalBus[i * 2 + 1] = sanitize(vocalBus[i * 2 + 1] + kR);
    }
    kickBus.clear();
    kickBus.shrink_to_fit();

    // Master Mode selection:
    // 0 = STUDIO CLEAN (transparent lookahead limiter, minimal OTT -> cleanest vocals & tightest sub!)
    // 1 = CLUB PUNCH   (balanced club glue + subtle warm soft-clip + lookahead limiter)
    // 2 = FESTIVAL HARD (maximum festival loudness + oversampled clipper + lookahead limiter)
    const int masterMode = std::max(0, std::min(2, config.masterMode));
    const float ottDepth = (masterMode == 0) ? 0.24f : ((masterMode == 1) ? 0.38f : 0.54f);
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

        masterOtt.processStereo(l, r, ottDepth, 1.0f, 1.08f);

        l *= preGain;
        r *= preGain;

        if (masterMode == 2) {
            l = clipL.process(l, 1.18f, 0.98f, 0.01f);
            r = clipR.process(r, 1.18f, 0.98f, 0.01f);
        } else if (masterMode == 1) {
            l = std::tanh(l * 1.04f);
            r = std::tanh(r * 1.04f);
        }

        // Transparent 4 ms lookahead true-peak brickwall limiter (zero harsh clipping fuzz!)
        limiter.processStereo(l, r);

        l = clampf(sanitize(l), -0.98f, 0.98f);
        r = clampf(sanitize(r), -0.98f, 0.98f);

        vocalBus[i * 2] = l;
        vocalBus[i * 2 + 1] = r;

        peakAbs = std::max(peakAbs, std::max(std::fabs(l), std::fabs(r)));
        sumSq += 0.5 * (static_cast<double>(l) * l + static_cast<double>(r) * r);
    }

    // Smooth 10ms fade-in/out on full render edges
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
    return result;
}

}  // namespace zaagforge
