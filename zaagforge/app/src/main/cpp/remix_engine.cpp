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
    float gainR = 1.0f
) {
    const size_t dstFrames = dst.size() / 2;
    const size_t srcFrames = src.size() / 2;
    if (dstStartFrame >= dstFrames || srcFrames == 0) return;

    const size_t count = std::min(srcFrames, dstFrames - dstStartFrame);
    for (size_t i = 0; i < count; ++i) {
        dst[(dstStartFrame + i) * 2] += src[i * 2] * gainL;
        dst[(dstStartFrame + i) * 2 + 1] += src[i * 2 + 1] * gainR;
    }
}

SectionType findSectionAt(const std::vector<SectionMarker>& sections, float frac) {
    for (const auto& s : sections) {
        if (frac >= s.startFrac && frac < s.endFrac) {
            return s.type;
        }
    }
    return sections.empty() ? SectionType::DROP_1 : sections.back().type;
}

const SectionMarker* findSectionMarkerAt(const std::vector<SectionMarker>& sections, float frac) {
    for (const auto& s : sections) {
        if (frac >= s.startFrac && frac < s.endFrac) {
            return &s;
        }
    }
    return sections.empty() ? nullptr : &sections.back();
}

}  // namespace

std::pair<float, float> RemixEngine::computeMusicalTempoSync(float sourceBpm, float targetBpm) {
    const float src = clampf(sourceBpm, 60.0f, 240.0f);
    const float tgt = clampf(targetBpm, 140.0f, 250.0f);

    // Musical grid multipliers:
    // M = 0.5 -> half-time vocals over double-time hardcore kicks (e.g. 100 BPM -> 200 BPM locks at 1.0x!)
    // M = 1.0 -> 1:1 beat lock (e.g. 140 BPM -> 160/200 BPM speeds up cleanly)
    // M = 2.0 -> double-time source into slower rawstyle
    const std::array<float, 3> mults = {0.5f, 1.0f, 2.0f};
    float bestSpeed = tgt / src;
    float bestMult = 1.0f;
    float bestCost = std::numeric_limits<float>::max();

    for (float m : mults) {
        const float speed = (tgt * m) / src;
        if (speed < 0.62f || speed > 1.72f) continue;
        // Center slight preference around 1.08x so remixes feel energetic without turning chipmunk-fast
        const float cost = std::fabs(std::log2(speed / 1.08f));
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

    // Short-circuit identity copy when virtually 1.0x
    if (std::fabs(scale - 1.0f) < 0.002f) {
        return std::vector<float>(stereoIn, stereoIn + inFrames * 2);
    }

    // Small inputs fall back to clean linear copy/resample
    constexpr size_t kWin = 1536;     // ~34.8 ms
    constexpr size_t kHopOut = 384;   // 75% overlap -> exact COLA for Hann window
    constexpr int kSearchTol = 224;   // ±5.1 ms waveform similarity search

    if (inFrames < kWin * 2) {
        return resampleStereoCubic(stereoIn, inFrames, outFrames);
    }

    // Precompute mono buffer for fast cross-correlation search
    std::vector<float> monoIn(inFrames);
    for (size_t i = 0; i < inFrames; ++i) {
        monoIn[i] = 0.5f * (sanitize(stereoIn[i * 2]) + sanitize(stereoIn[i * 2 + 1]));
    }

    const auto hann = makeHannWindow(kWin);
    std::vector<float> out(outFrames * 2, 0.0f);
    std::vector<float> normAcc(outFrames, 0.0f);

    // First frame placed cleanly at origin
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
        // Natural continuation reference from previous placed window
        const int refPos = static_cast<int>(prevSrcPos + kHopOut);

        const int maxValidStart = static_cast<int>(inFrames) - static_cast<int>(kWin);
        const int clampedNominal = std::max(0, std::min(maxValidStart, nominalSrc));

        int bestSrc = clampedNominal;
        if (refPos >= 0 && refPos <= maxValidStart) {
            const int searchLo = std::max(0, clampedNominal - kSearchTol);
            const int searchHi = std::min(maxValidStart, clampedNominal + kSearchTol);

            float bestCorr = -1e30f;
            // Stride 4 for coarse search, then fine refine ±3 around peak -> blazing fast on ARM64!
            int coarseBest = clampedNominal;
            constexpr size_t kCmpLen = 512;
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
                for (size_t k = 0; k < kCmpLen; k += 2) {
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

    // Pitch shift factor: > 1 raises pitch (UOK chipmunk +5 st = 1.335x)
    // WSOLA stretches by (timeScale * pitchFactor), then cubic resample shrinks back to exact targetFrames!
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
    const float sourceBpm = clampf(analysis.bpm, 70.0f, 220.0f);
    const auto [speedRatio, gridMult] = computeMusicalTempoSync(sourceBpm, targetBpm);
    const float timeScale = 1.0f / speedRatio;

    // 1. Warp original track to exact hardcore beat grid + apply vocal pitch shift!
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

    // 3. Split warped original audio via 4th-Order Linkwitz-Riley Crossover @ 240 Hz
    // + Mid/Side vocal presence boost on high stem
    std::vector<float> origLow(outFrames * 2, 0.0f);
    std::vector<float> origHigh(outFrames * 2, 0.0f);

    LinkwitzRiley4 xoverL, xoverR;
    xoverL.configure(240.0f);
    xoverR.configure(240.0f);

    Biquad vocalPresence, airBoost;
    vocalPresence.configure(BiquadType::PEAK, 2250.0f, 1.35f, 3.8f);
    airBoost.configure(BiquadType::HIGH_SHELF, 6500.0f, 0.707f, 2.2f);

    for (size_t i = 0; i < outFrames; ++i) {
        float loL, hiL, loR, hiR;
        xoverL.split(warped[i * 2], loL, hiL);
        xoverR.split(warped[i * 2 + 1], loR, hiR);

        // Mid/Side vocal boost on high stem (> 240 Hz)
        const float mid = 0.5f * (hiL + hiR);
        const float side = 0.5f * (hiL - hiR);
        const float midBoosted = airBoost.process(vocalPresence.process(mid));
        const float sideWidened = side * 1.16f;

        origLow[i * 2] = loL;
        origLow[i * 2 + 1] = loR;
        origHigh[i * 2] = midBoosted + sideWidened;
        origHigh[i * 2 + 1] = midBoosted - sideWidened;
    }

    // 4. Prepare section map (respecting enableFakeDrop toggle)
    std::vector<SectionMarker> sections = analysis.sections;
    if (sections.empty()) {
        sections.push_back({0.0f, 1.0f, SectionType::DROP_1, "DROP 1"});
    }
    if (!config.enableFakeDrop) {
        for (auto& s : sections) {
            if (s.type == SectionType::FAKE_DROP) {
                s.type = SectionType::BUILDUP;
                s.label = "BUILD";
            }
        }
    }

    // 5. Master output mix bus + beat-grid timing
    std::vector<float> master(outFrames * 2, 0.0f);

    const float beatSec = 60.0f / targetBpm;
    const float beatFramesF = beatSec * kSampleRate;
    const float warpedOffsetSec = clampf(analysis.beatOffsetSec * timeScale, 0.0f, beatSec);
    const size_t offsetFrames = static_cast<size_t>(warpedOffsetSec * kSampleRate);

    // 6. Mix Original Track Stem across arrangement sections with:
    //    - Full spectrum in Intro / Vocal Break / Mid Break
    //    - Rising resonant HPF sweep in Buildups
    //    - Rhythmic 1/8 & 1/16 Stutter Vocal Chopper in Fake-Drop & Drop turnarounds
    //    - Kick-synced sidechain ducking + bass-strip (< 240 Hz removed) during Drops!
    Biquad buildHpfL, buildHpfR;
    const float origMixGain = clampf(config.originalMix, 0.0f, 1.0f);
    const float stutterAmt = clampf(config.stutterAmount, 0.0f, 1.0f);

    for (size_t i = 0; i < outFrames; ++i) {
        const float frac = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, outFrames));
        const SectionMarker* sec = findSectionMarkerAt(sections, frac);
        const SectionType sType = sec ? sec->type : SectionType::DROP_1;
        const float secDur = sec ? std::max(0.001f, sec->endFrac - sec->startFrac) : 1.0f;
        const float secProg = sec ? clampf((frac - sec->startFrac) / secDur, 0.0f, 1.0f) : 0.0f;

        const float beatPos = (i >= offsetFrames)
            ? static_cast<float>(i - offsetFrames) / beatFramesF
            : 0.0f;
        const int beatIdx = static_cast<int>(std::floor(beatPos));
        const float beatFrac = beatPos - std::floor(beatPos);
        const int barIdx = beatIdx / 4;
        const int beatInBar = beatIdx % 4;

        float l = 0.0f, r = 0.0f;

        if (sType == SectionType::INTRO || sType == SectionType::VOCAL_BREAK || sType == SectionType::MID_BREAK) {
            // Keep rich original vocal/melody + controlled bass
            const float gain = 0.72f + 0.25f * origMixGain;
            l = (origLow[i * 2] * 0.78f + origHigh[i * 2] * 1.05f) * gain;
            r = (origLow[i * 2 + 1] * 0.78f + origHigh[i * 2 + 1] * 1.05f) * gain;
        } else if (sType == SectionType::BUILDUP) {
            // Sweeping HPF strips bass smoothly toward drop tension
            if (i % 64 == 0) {
                const float cut = 180.0f + 1350.0f * secProg * secProg;
                buildHpfL.configure(BiquadType::HPF, cut, 1.35f);
                buildHpfR.configure(BiquadType::HPF, cut, 1.35f);
            }
            const float rawL = origLow[i * 2] * (1.0f - secProg) * 0.55f + origHigh[i * 2];
            const float rawR = origLow[i * 2 + 1] * (1.0f - secProg) * 0.55f + origHigh[i * 2 + 1];
            l = buildHpfL.process(rawL) * (0.75f + 0.22f * origMixGain);
            r = buildHpfR.process(rawR) * (0.75f + 0.22f * origMixGain);
        } else if (sType == SectionType::FAKE_DROP) {
            // FAKE-DROP: dramatic dropout + rhythmic 1/8 & 1/16 stutter vocal chop + silence cliff!
            if (secProg > 0.86f) {
                // Iconic sudden silence breath right before the drop slams!
                l = 0.0f;
                r = 0.0f;
            } else {
                // Repeat vocal slice at 1/8 -> 1/16 grid!
                const size_t sliceFrames = std::max<size_t>(
                    512,
                    static_cast<size_t>((secProg > 0.50f ? 0.25f : 0.50f) * beatFramesF)
                );
                const size_t secStartFrame = static_cast<size_t>((sec ? sec->startFrac : 0.0f) * outFrames);
                const size_t localIdx = (i >= secStartFrame) ? (i - secStartFrame) : 0;
                const size_t slicePos = localIdx % sliceFrames;
                const size_t srcFrame = std::min(outFrames - 1, secStartFrame + slicePos);

                // Click-free micro-gate on stutter slices
                const float edgeFade = std::min(
                    1.0f,
                    std::min(static_cast<float>(slicePos) / 140.0f, static_cast<float>(sliceFrames - slicePos) / 140.0f)
                );
                const float gate = (static_cast<float>(slicePos) / static_cast<float>(sliceFrames) < 0.78f) ? 1.0f : 0.12f;
                const float gain = (0.78f + 0.30f * origMixGain) * edgeFade * gate;
                l = origHigh[srcFrame * 2] * gain;
                r = origHigh[srcFrame * 2 + 1] * gain;
            }
        } else {
            // DROP_1 or DROP_2: Strip original bass (< 240 Hz), pump vocals via tight kick-synced sidechain!
            // Sharp 6ms duck -> smooth rhythmic pump release
            const float duck = (beatFrac < 0.05f)
                ? (1.0f - 0.78f * (beatFrac / 0.05f))
                : (0.22f + 0.78f * std::pow(clampf((beatFrac - 0.05f) / 0.65f, 0.0f, 1.0f), 0.75f));

            // Optional Rhythmic Stutter Vocal Chopper on bar turnarounds (beat 4 of bars 3/7/...)
            size_t readFrame = i;
            float chopEnv = 1.0f;
            const bool isStutterZone = (stutterAmt > 0.15f)
                && ((barIdx % 4 == 3 && beatInBar >= 2) || (stutterAmt > 0.65f && barIdx % 2 == 1 && beatInBar == 3));
            if (isStutterZone) {
                const float div = (stutterAmt > 0.60f) ? 0.25f : 0.50f; // 1/16 or 1/8 beat slice
                const size_t sliceLen = std::max<size_t>(512, static_cast<size_t>(div * beatFramesF));
                const size_t anchor = (i / (sliceLen * 4)) * (sliceLen * 4);
                const size_t posInSlice = (i - anchor) % sliceLen;
                readFrame = std::min(outFrames - 1, anchor + posInSlice);
                const float fade = std::min(
                    1.0f,
                    std::min(static_cast<float>(posInSlice) / 120.0f, static_cast<float>(sliceLen - posInSlice) / 120.0f)
                );
                const float stutterWet = clampf(stutterAmt, 0.0f, 1.0f);
                chopEnv = (1.0f - stutterWet) + stutterWet * fade * 1.15f;
            }

            const float vocalGain = (0.18f + 0.72f * origMixGain) * duck * chopEnv;
            l = origHigh[readFrame * 2] * vocalGain;
            r = origHigh[readFrame * 2 + 1] * vocalGain;
        }

        master[i * 2] = l;
        master[i * 2 + 1] = r;
    }

    // 7. Pre-render Hardcore Kick Cache (Drop 1, Drop 2, melodic pitch-kick variants, triplet rolls!)
    const int switchBars = (config.kickSwitchBars == 8) ? 8 : 4;
    const std::array<float, 4> melodyOffsets = {0.0f, 3.0f, 5.0f, 7.0f}; // Minor pentatonic / hardcore melody intervals

    auto buildKickSet = [&](KickType kt, float driveBoost, float aggBoost) {
        std::array<std::vector<float>, 5> set; // 0..3 = pitched full kicks, 4 = 1/3 triplet roll kick
        for (int m = 0; m < 4; ++m) {
            KickParams kp;
            kp.type = kt;
            kp.rootHz = effectiveRootHz;
            kp.bpm = targetBpm;
            kp.drive = clampf(config.drive + driveBoost, 0.1f, 1.0f);
            kp.aggression = clampf(config.zaagAggression + aggBoost, 0.1f, 1.0f);
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
        tripP.pitchSemitoneOffset = 3.0f;
        tripP.lengthBeats = 0.3333f;
        tripP.seed = 7777u + static_cast<uint32_t>(kt) * 17u;
        set[4] = HardcoreSynth::renderKick(tripP);
        return set;
    };

    const auto kickSet1 = buildKickSet(config.drop1Kick, 0.0f, 0.0f);
    const auto kickSet2 = buildKickSet(config.drop2Kick, 0.05f, 0.05f);
    const auto kickSet2Finale = buildKickSet(config.drop2Kick, 0.12f, 0.10f);
    const auto openHat = HardcoreSynth::renderHardcoreHat(beatSec * 0.45f, true, 909u);
    const auto screechA = HardcoreSynth::renderIndustrialScreech(effectiveRootHz, beatSec * 1.5f, config.zaagAggression, 0);
    const auto screechB = HardcoreSynth::renderIndustrialScreech(effectiveRootHz, beatSec * 1.25f, config.zaagAggression, 1);
    const auto subImpact = HardcoreSynth::renderSubImpact(effectiveRootHz, std::min(1.6f, beatSec * 4.0f));

    // 8. Layer Build-Up Rolls, Noise Risers, Fake-Drop Screeches, and Drop Impacts along sections!
    for (const auto& sec : sections) {
        const size_t sStart = static_cast<size_t>(sec.startFrac * outFrames);
        const size_t sEnd = static_cast<size_t>(sec.endFrac * outFrames);
        if (sEnd <= sStart) continue;
        const float secDurSec = static_cast<float>(sEnd - sStart) / kSampleRate;

        if (sec.type == SectionType::BUILDUP && secDurSec >= 0.45f) {
            const auto roll = HardcoreSynth::renderSnareBuildupRoll(targetBpm, secDurSec, effectiveRootHz, config.zaagAggression);
            const auto riser = HardcoreSynth::renderNoiseRiser(secDurSec, effectiveRootHz, config.zaagAggression);
            mixBufferAt(master, roll, sStart, 0.74f, 0.74f);
            mixBufferAt(master, riser, sStart, 0.56f, 0.56f);
        } else if (sec.type == SectionType::FAKE_DROP) {
            // Brutal Fake-Drop: punchy lone fake-kick hit on beat 1 + screech stab!
            mixBufferAt(master, kickSet1[0], sStart, 0.92f, 0.92f);
            if (config.enableScreeches && secDurSec >= 0.25f) {
                const size_t stabPos = sStart + static_cast<size_t>(std::min(beatFramesF * 0.5f, (sEnd - sStart) * 0.25f));
                mixBufferAt(master, screechA, stabPos, 0.68f, 0.68f);
            }
        } else if (sec.type == SectionType::DROP_1 || sec.type == SectionType::DROP_2) {
            // Massive Festival Sub Impact on bar 1 beat 1 of each Drop!
            mixBufferAt(master, subImpact, sStart, 0.62f, 0.62f);
        }
    }

    // 9. Lay Down Beat-Locked 4-on-the-Floor Hardcore Kicks + Kick-Switches + Triplets + Screeches!
    const int totalBeats = static_cast<int>(std::ceil(outDurSec / beatSec));
    int dropBarCounter = 0;
    int lastDropBarIndex = -1;

    for (int b = 0; b < totalBeats; ++b) {
        const size_t beatStart = offsetFrames + static_cast<size_t>(std::llround(static_cast<double>(b) * beatFramesF));
        if (beatStart >= outFrames) break;

        const float frac = static_cast<float>(beatStart) / static_cast<float>(std::max<size_t>(1, outFrames));
        const SectionType sType = findSectionAt(sections, frac);

        if (sType != SectionType::DROP_1 && sType != SectionType::DROP_2) {
            continue;
        }

        const int barIndex = b / 4;
        const int beatInBar = b % 4;
        if (barIndex != lastDropBarIndex) {
            ++dropBarCounter;
            lastDropBarIndex = barIndex;
        }

        // Automatic Kick-Switch every 4 or 8 bars!
        const int switchPhase = ((dropBarCounter - 1) / switchBars) % 2;
        const bool isDrop2 = (sType == SectionType::DROP_2);
        const bool useSet2 = isDrop2 ? (switchPhase == 0) : (switchPhase == 1);
        const auto& activeSet = useSet2 ? (isDrop2 ? kickSet2Finale : kickSet2) : kickSet1;

        const bool isTurnaroundBar = (dropBarCounter % 4 == 0);
        const bool isSwitchBoundaryBar = (dropBarCounter % switchBars == 0);

        // Triplet Kick Roll on final beat of turnaround / kick-switch bars!
        if (config.enableTripletRolls && isTurnaroundBar && beatInBar == 3) {
            const float tripStep = beatFramesF / 3.0f;
            for (int t = 0; t < 3; ++t) {
                const size_t tStart = beatStart + static_cast<size_t>(std::llround(t * tripStep));
                const float accent = 0.90f + 0.08f * static_cast<float>(t);
                mixBufferAt(master, activeSet[4], tStart, accent, accent);
            }
        } else {
            // Optional Melodic Pitch Kick Turnaround (bar 4: 0, +3, +5, +7 or +3)
            int pitchIdx = 0;
            if (config.enablePitchKickMelody && isTurnaroundBar) {
                static constexpr std::array<int, 4> kTurnaroundPattern = {0, 1, 2, 3};
                pitchIdx = kTurnaroundPattern[beatInBar];
            } else if (config.enablePitchKickMelody && (dropBarCounter % 2 == 0) && beatInBar == 3) {
                pitchIdx = 1; // subtle +3 st bounce lift at end of even bars
            }

            const float kickLevel = isDrop2 ? 1.06f : 1.00f;
            mixBufferAt(master, activeSet[pitchIdx], beatStart, kickLevel, kickLevel);
        }

        // Offbeat 909 Hardcore Open Hi-Hat for authentic festival drive
        const size_t offbeatPos = beatStart + static_cast<size_t>(beatFramesF * 0.5f);
        const float hatGain = isDrop2 ? 0.34f : 0.28f;
        mixBufferAt(master, openHat, offbeatPos, hatGain, hatGain);

        // Key-locked Industrial Screech stabs on switch bars & drop accents!
        if (config.enableScreeches) {
            if ((isSwitchBoundaryBar && beatInBar == 2) || (isDrop2 && (dropBarCounter % 2 == 1) && beatInBar == 0)) {
                const auto& sc = (dropBarCounter % 2 == 0) ? screechA : screechB;
                const float scGain = isDrop2 ? 0.54f : 0.44f;
                mixBufferAt(master, sc, beatStart, scGain, scGain);
            }
        }
    }

    // 10. Festival Mastering Chain:
    //     - DC Blocker (24 Hz HPF)
    //     - 3-Band OTT Multiband Dynamics (140 Hz / 2500 Hz) + Strict Mono Bass (< 140 Hz) + Stereo Mid/High Width
    //     - 2x Oversampled Soft/Hard Brickwall Clipper hitting festival loudness (peaks strictly <= 0.98f!)
    Biquad dcL, dcR;
    dcL.configure(BiquadType::HPF, 24.0f, 0.707f);
    dcR.configure(BiquadType::HPF, 24.0f, 0.707f);

    Ott3Band masterOtt;
    masterOtt.configure(140.0f, 2500.0f);
    OversampledClipper2x clipL, clipR;

    const float masterDrive = 1.45f + 0.55f * clampf(config.drive, 0.0f, 1.0f);
    double sumSq = 0.0;
    float peakAbs = 0.0f;

    for (size_t i = 0; i < outFrames; ++i) {
        float l = dcL.process(master[i * 2]);
        float r = dcR.process(master[i * 2 + 1]);

        masterOtt.processStereo(l, r, 0.62f, 1.0f, 1.14f);

        l = clipL.process(l, masterDrive, 0.98f, 0.02f);
        r = clipR.process(r, masterDrive, 0.98f, 0.02f);

        // Strict final safety guard: zero NaN/Inf, peaks <= 0.98f
        l = clampf(sanitize(l), -0.98f, 0.98f);
        r = clampf(sanitize(r), -0.98f, 0.98f);

        master[i * 2] = l;
        master[i * 2 + 1] = r;

        peakAbs = std::max(peakAbs, std::max(std::fabs(l), std::fabs(r)));
        sumSq += 0.5 * (static_cast<double>(l) * l + static_cast<double>(r) * r);
    }

    // Smooth 10ms fade-in/out on full render edges
    const size_t edgeFade = std::min<size_t>(441, outFrames / 4);
    for (size_t i = 0; i < edgeFade; ++i) {
        const float g = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, edgeFade));
        master[i * 2] *= g;
        master[i * 2 + 1] *= g;
        const size_t tail = outFrames - 1 - i;
        master[tail * 2] *= g;
        master[tail * 2 + 1] *= g;
    }

    const float rms = static_cast<float>(std::sqrt(sumSq / static_cast<double>(std::max<size_t>(1, outFrames))));

    result.waveformPeaks = MusicAnalyzer::extractWaveformPeaks(master.data(), outFrames, 240);
    result.stereoPcm = std::move(master);
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
