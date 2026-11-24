#include "music_analyzer.h"
#include "dsp_utils.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <numeric>

namespace zaagforge {

const char* MusicAnalyzer::sectionTypeName(SectionType t) {
    switch (t) {
        case SectionType::INTRO: return "INTRO";
        case SectionType::VOCAL_BREAK: return "BREAK";
        case SectionType::BUILDUP: return "BUILD";
        case SectionType::FAKE_DROP: return "FAKE-DROP";
        case SectionType::DROP_1: return "DROP 1";
        case SectionType::MID_BREAK: return "MID-BREAK";
        case SectionType::DROP_2: return "DROP 2";
    }
    return "DROP";
}

std::vector<float> MusicAnalyzer::extractWaveformPeaks(
    const float* stereoPcm,
    size_t totalFrames,
    size_t numBins
) {
    if (!stereoPcm || totalFrames == 0 || numBins == 0) {
        return std::vector<float>(numBins, 0.0f);
    }

    std::vector<float> peaks(numBins, 0.0f);
    float maxPeak = 1e-6f;
    for (size_t b = 0; b < numBins; ++b) {
        const size_t start = (b * totalFrames) / numBins;
        const size_t end = std::max(start + 1, ((b + 1) * totalFrames) / numBins);
        const size_t step = std::max<size_t>(1, (end - start) / 256);
        float p = 0.0f;
        for (size_t i = start; i < end && i < totalFrames; i += step) {
            const float l = std::fabs(sanitize(stereoPcm[i * 2]));
            const float r = std::fabs(sanitize(stereoPcm[i * 2 + 1]));
            p = std::max(p, std::max(l, r));
        }
        peaks[b] = p;
        maxPeak = std::max(maxPeak, p);
    }

    const float invMax = (maxPeak > 0.05f) ? (1.0f / maxPeak) : 1.0f;
    for (float& p : peaks) {
        p = clampf(p * invMax, 0.0f, 1.0f);
    }
    return peaks;
}

namespace {

struct BpmEstimate {
    float bpm = 128.0f;
    float confidence = 0.5f;
    float beatOffsetSec = 0.0f;
};

BpmEstimate detectBpmAndPhase(const std::vector<float>& mono, float sr) {
    BpmEstimate est;
    constexpr size_t kWin = 1024;
    constexpr size_t kHop = 512;
    if (mono.size() < kWin * 8) {
        return est;
    }

    // Limit BPM analysis window to first 90s for speed + solid accuracy
    const size_t maxSamples = std::min(mono.size(), static_cast<size_t>(sr * 90.0f));
    const size_t numFrames = (maxSamples - kWin) / kHop;
    if (numFrames < 64) {
        return est;
    }

    const auto hann = makeHannWindow(kWin);
    std::vector<std::complex<float>> fftBuf(kWin);
    const size_t halfBins = kWin / 2 + 1;
    std::vector<float> prevMag(halfBins, 0.0f);
    std::vector<float> flux(numFrames, 0.0f);

    const float binHz = sr / static_cast<float>(kWin);
    const size_t binLowEnd = std::min<size_t>(halfBins - 1, static_cast<size_t>(190.0f / binHz));
    const size_t binMidEnd = std::min<size_t>(halfBins - 1, static_cast<size_t>(2600.0f / binHz));
    const size_t binHighEnd = std::min<size_t>(halfBins - 1, static_cast<size_t>(10000.0f / binHz));

    for (size_t f = 0; f < numFrames; ++f) {
        const size_t base = f * kHop;
        for (size_t i = 0; i < kWin; ++i) {
            fftBuf[i] = std::complex<float>(mono[base + i] * hann[i], 0.0f);
        }
        fftInPlace(fftBuf, false);

        float lowFlux = 0.0f;
        float midFlux = 0.0f;
        float highFlux = 0.0f;

        for (size_t k = 1; k <= binHighEnd; ++k) {
            const float mag = std::log1p(std::abs(fftBuf[k]) * 10.0f);
            const float diff = mag - prevMag[k];
            prevMag[k] = mag;
            if (diff > 0.0f) {
                if (k <= binLowEnd) lowFlux += diff;
                else if (k <= binMidEnd) midFlux += diff;
                else highFlux += diff;
            }
        }
        // Weight kick low-end strongest for tight rhythmic beat lock
        flux[f] = lowFlux * 2.2f + midFlux * 1.0f + highFlux * 0.65f;
    }

    // Adaptive local threshold subtraction (half-wave rectified onset envelope)
    std::vector<float> onset(numFrames, 0.0f);
    constexpr int kRadius = 8;
    float maxOnset = 1e-6f;
    for (size_t f = 0; f < numFrames; ++f) {
        const size_t lo = (f > static_cast<size_t>(kRadius)) ? f - kRadius : 0;
        const size_t hi = std::min(numFrames - 1, f + kRadius);
        float sum = 0.0f;
        for (size_t j = lo; j <= hi; ++j) sum += flux[j];
        const float mean = sum / static_cast<float>(hi - lo + 1);
        const float v = std::max(0.0f, flux[f] - mean * 1.08f);
        onset[f] = v;
        maxOnset = std::max(maxOnset, v);
    }

    if (maxOnset < 1e-5f) {
        return est;
    }
    for (float& v : onset) v /= maxOnset;

    const float frameRate = sr / static_cast<float>(kHop);

    // Comb-filter + Autocorrelation across 70..220 BPM in 0.5 BPM grid
    constexpr float kMinBpm = 70.0f;
    constexpr float kMaxBpm = 220.0f;
    constexpr float kBpmStep = 0.5f;
    const int numCandidates = static_cast<int>((kMaxBpm - kMinBpm) / kBpmStep) + 1;

    // Precompute lag autocorrelation for speed
    const size_t maxLag = std::min(numFrames / 2, static_cast<size_t>(std::ceil(frameRate * 60.0f / 35.0f)) + 4);
    std::vector<float> acf(maxLag + 1, 0.0f);
    for (size_t lag = 1; lag <= maxLag; ++lag) {
        double acc = 0.0;
        const size_t count = numFrames - lag;
        for (size_t i = 0; i < count; ++i) {
            acc += static_cast<double>(onset[i]) * static_cast<double>(onset[i + lag]);
        }
        acf[lag] = static_cast<float>(acc / static_cast<double>(std::max<size_t>(1, count)));
    }

    auto sampleAcf = [&](float lagF) -> float {
        if (lagF < 1.0f || lagF >= static_cast<float>(maxLag - 1)) return 0.0f;
        const size_t i0 = static_cast<size_t>(lagF);
        const float frac = lagF - static_cast<float>(i0);
        return acf[i0] * (1.0f - frac) + acf[i0 + 1] * frac;
    };

    std::vector<float> scores(numCandidates, 0.0f);
    float bestScore = -1.0f;
    int bestIdx = 0;

    for (int c = 0; c < numCandidates; ++c) {
        const float bpm = kMinBpm + c * kBpmStep;
        const float lag = frameRate * 60.0f / bpm;

        // Multi-harmonic comb score (1x, 2x, 3x, 4x beat periods)
        float comb = sampleAcf(lag) * 1.00f
                   + sampleAcf(lag * 2.0f) * 0.72f
                   + sampleAcf(lag * 3.0f) * 0.40f
                   + sampleAcf(lag * 4.0f) * 0.45f;

        // Penalize half-speed false positives if strong double-speed subdivisions exist
        const float halfLagScore = sampleAcf(lag * 0.5f);
        if (bpm < 105.0f && halfLagScore > sampleAcf(lag) * 0.88f) {
            comb *= 0.80f;
        }
        // Subtle perceptual prior gently favoring standard musical pulse (100..185 BPM)
        // without blocking genuine 70..95 or 190..220 BPM tracks
        const float distFromCenter = std::fabs(std::log2(bpm / 136.0f));
        const float prior = std::exp(-0.45f * distFromCenter * distFromCenter);

        const float score = comb * (0.78f + 0.22f * prior);
        scores[c] = score;
        if (score > bestScore) {
            bestScore = score;
            bestIdx = c;
        }
    }

    // Parabolic refinement around best BPM bin
    float refinedBpm = kMinBpm + bestIdx * kBpmStep;
    if (bestIdx > 0 && bestIdx < numCandidates - 1) {
        const float y0 = scores[bestIdx - 1];
        const float y1 = scores[bestIdx];
        const float y2 = scores[bestIdx + 1];
        const float denom = (y0 - 2.0f * y1 + y2);
        if (std::fabs(denom) > 1e-9f) {
            const float delta = clampf(0.5f * (y0 - y2) / denom, -0.5f, 0.5f);
            refinedBpm += delta * kBpmStep;
        }
    }

    // Confidence estimation: peak-to-median ratio
    std::vector<float> sortedScores = scores;
    std::nth_element(sortedScores.begin(), sortedScores.begin() + sortedScores.size() / 2, sortedScores.end());
    const float medianScore = std::max(sortedScores[sortedScores.size() / 2], 1e-6f);
    est.bpm = clampf(refinedBpm, kMinBpm, kMaxBpm);
    est.confidence = clampf((bestScore / medianScore - 1.0f) * 0.35f, 0.1f, 0.99f);

    // Beat-phase alignment: sweep phase across [0, beatPeriodFrames)
    const float beatFrames = frameRate * 60.0f / est.bpm;
    constexpr int kPhaseSteps = 48;
    float bestPhaseScore = -1.0f;
    float bestPhaseFrames = 0.0f;

    for (int s = 0; s < kPhaseSteps; ++s) {
        const float phase = (static_cast<float>(s) / static_cast<float>(kPhaseSteps)) * beatFrames;
        float score = 0.0f;
        int hits = 0;
        for (float pos = phase; pos < static_cast<float>(numFrames - 2); pos += beatFrames) {
            const size_t i0 = static_cast<size_t>(pos);
            const float frac = pos - static_cast<float>(i0);
            const float val = onset[i0] * (1.0f - frac) + onset[i0 + 1] * frac;
            // Slightly weight every 4th beat (downbeat)
            const float weight = (hits % 4 == 0) ? 1.25f : 1.0f;
            score += val * weight;
            ++hits;
        }
        if (score > bestPhaseScore) {
            bestPhaseScore = score;
            bestPhaseFrames = phase;
        }
    }

    est.beatOffsetSec = bestPhaseFrames / frameRate;
    return est;
}

struct KeyEstimate {
    int keyIndex = 5;
    bool isMinor = true;
    int rootMidi = 29;
    float rootHz = 43.65f;
    std::string keyName = "Fm";
};

KeyEstimate detectMusicalKey(const std::vector<float>& mono, float sr) {
    KeyEstimate key;
    constexpr size_t kWin = 4096;
    if (mono.size() < kWin * 2) {
        return key;
    }

    // Sample up to 64 evenly distributed frames across the track
    const size_t maxFrames = 64;
    const size_t available = mono.size() - kWin;
    const size_t stride = std::max<size_t>(kWin / 2, available / maxFrames);

    const auto hann = makeHannWindow(kWin);
    std::vector<std::complex<float>> fftBuf(kWin);
    std::array<double, 12> chroma{};
    chroma.fill(0.0);

    const float binHz = sr / static_cast<float>(kWin);
    const size_t minBin = std::max<size_t>(2, static_cast<size_t>(60.0f / binHz));
    const size_t maxBin = std::min<size_t>(kWin / 2 - 1, static_cast<size_t>(2200.0f / binHz));

    for (size_t pos = 0; pos + kWin <= mono.size(); pos += stride) {
        for (size_t i = 0; i < kWin; ++i) {
            fftBuf[i] = std::complex<float>(mono[pos + i] * hann[i], 0.0f);
        }
        fftInPlace(fftBuf, false);

        for (size_t k = minBin; k <= maxBin; ++k) {
            const float freq = static_cast<float>(k) * binHz;
            const float mag = std::abs(fftBuf[k]);
            if (mag < 1e-5f) continue;

            const float midi = hzToMidi(freq);
            const int nearestMidi = static_cast<int>(std::round(midi));
            const float detune = std::fabs(midi - static_cast<float>(nearestMidi));
            const float weight = std::exp(-6.0f * detune * detune);
            // Bass & harmony register (65..600 Hz) carries strongest key root evidence
            const float regWeight = (freq < 600.0f) ? 1.45f : 1.0f;

            const int pc = ((nearestMidi % 12) + 12) % 12;
            chroma[pc] += static_cast<double>(mag * mag * weight * regWeight);
        }
    }

    // Normalize chromagram
    double totalChroma = 0.0;
    for (double v : chroma) totalChroma += v;
    if (totalChroma < 1e-9) {
        return key;
    }
    for (double& v : chroma) v /= totalChroma;

    // Krumhansl-Schmuckler major & minor templates
    static constexpr std::array<double, 12> kMajorProfile = {
        6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88
    };
    static constexpr std::array<double, 12> kMinorProfile = {
        6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17
    };

    auto pearson = [&](const std::array<double, 12>& profile, int shift) -> double {
        double meanX = 0.0, meanY = 0.0;
        for (int i = 0; i < 12; ++i) {
            meanX += chroma[(i + shift) % 12];
            meanY += profile[i];
        }
        meanX /= 12.0;
        meanY /= 12.0;

        double num = 0.0, denX = 0.0, denY = 0.0;
        for (int i = 0; i < 12; ++i) {
            const double dx = chroma[(i + shift) % 12] - meanX;
            const double dy = profile[i] - meanY;
            num += dx * dy;
            denX += dx * dx;
            denY += dy * dy;
        }
        const double den = std::sqrt(denX * denY);
        return (den > 1e-12) ? (num / den) : -1.0;
    };

    double bestCorr = -2.0;
    int bestPc = 5;
    bool bestMinor = true;

    for (int pc = 0; pc < 12; ++pc) {
        const double corrMaj = pearson(kMajorProfile, pc);
        if (corrMaj > bestCorr) {
            bestCorr = corrMaj;
            bestPc = pc;
            bestMinor = false;
        }
        const double corrMin = pearson(kMinorProfile, pc);
        if (corrMin > bestCorr) {
            bestCorr = corrMin;
            bestPc = pc;
            bestMinor = true;
        }
    }

    key.keyIndex = bestPc;
    key.isMinor = bestMinor;
    key.rootMidi = pitchClassToSubMidi(bestPc);
    key.rootHz = clampf(midiToHz(static_cast<float>(key.rootMidi)), 41.2f, 73.5f);
    key.keyName = formatKeyName(bestPc, bestMinor);
    return key;
}

std::vector<SectionMarker> buildArrangementSections(
    const std::vector<float>& mono,
    float sr,
    float bpm,
    float beatOffsetSec
) {
    const float durationSec = static_cast<float>(mono.size()) / sr;
    std::vector<SectionMarker> sections;
    if (durationSec <= 2.0f) {
        sections.push_back({0.0f, 1.0f, SectionType::DROP_1, "DROP 1"});
        return sections;
    }

    const float barSec = (60.0f / clampf(bpm, 70.0f, 220.0f)) * 4.0f;
    const int totalBars = std::max(4, static_cast<int>(std::floor((durationSec - beatOffsetSec) / barSec)));

    auto barToFrac = [&](float barIdx) -> float {
        const float sec = beatOffsetSec + barIdx * barSec;
        return clampf(sec / durationSec, 0.0f, 1.0f);
    };

    // Analyze bar energies to adapt drop placement to musical structure
    std::vector<float> barRms(totalBars, 0.0f);
    float avgBarRms = 0.0f;
    for (int b = 0; b < totalBars; ++b) {
        const size_t s0 = static_cast<size_t>(std::max(0.0f, (beatOffsetSec + b * barSec) * sr));
        const size_t s1 = std::min(mono.size(), static_cast<size_t>((beatOffsetSec + (b + 1) * barSec) * sr));
        if (s1 > s0) {
            double e = 0.0;
            for (size_t i = s0; i < s1; ++i) e += static_cast<double>(mono[i]) * mono[i];
            barRms[b] = static_cast<float>(std::sqrt(e / static_cast<double>(s1 - s0)));
            avgBarRms += barRms[b];
        }
    }
    avgBarRms /= static_cast<float>(std::max(1, totalBars));

    auto pushSection = [&](float f0, float f1, SectionType type) {
        f0 = clampf(f0, 0.0f, 1.0f);
        f1 = clampf(f1, f0, 1.0f);
        if (f1 - f0 > 0.005f) {
            sections.push_back({f0, f1, type, MusicAnalyzer::sectionTypeName(type)});
        }
    };

    if (durationSec <= 46.0f) {
        // Short track / 30s Deezer preview / Starter demo: fast festival structure!
        // Vocal Break/Intro -> Buildup -> Fake-Drop (0.5 bar) -> Drop 1 -> Drop 2 (Switch Finale)
        const float bIntro = std::max(1.0f, std::floor(totalBars * 0.14f));
        const float bBuildEnd = std::min(static_cast<float>(totalBars) * 0.34f, bIntro + std::max(2.0f, std::floor(totalBars * 0.16f)));
        const float bFakeEnd = std::min(static_cast<float>(totalBars) * 0.42f, bBuildEnd + 0.75f);
        const float bDrop1End = bFakeEnd + (static_cast<float>(totalBars) - bFakeEnd) * 0.50f;

        pushSection(0.0f, barToFrac(bIntro), SectionType::VOCAL_BREAK);
        pushSection(barToFrac(bIntro), barToFrac(bBuildEnd), SectionType::BUILDUP);
        pushSection(barToFrac(bBuildEnd), barToFrac(bFakeEnd), SectionType::FAKE_DROP);
        pushSection(barToFrac(bFakeEnd), barToFrac(bDrop1End), SectionType::DROP_1);
        pushSection(barToFrac(bDrop1End), 1.0f, SectionType::DROP_2);
    } else {
        // Full track (> 46s): Complete 7-section producer arrangement!
        // Detect if there is a quieter intro in the first ~10%
        int introBars = std::max(2, static_cast<int>(std::round(totalBars * 0.07f)));
        while (introBars < totalBars / 6 && barRms[introBars] < avgBarRms * 0.68f) {
            ++introBars;
        }
        const float bIntro = static_cast<float>(introBars);
        const float bVocalEnd = bIntro + std::max(4.0f, std::round(totalBars * 0.10f));
        const float bBuild1End = bVocalEnd + std::max(4.0f, std::round(totalBars * 0.08f));
        const float bFakeEnd = bBuild1End + 1.0f;
        const float bDrop1End = std::min(static_cast<float>(totalBars) * 0.58f, bFakeEnd + std::max(8.0f, std::round(totalBars * 0.28f)));
        const float bMidBreakEnd = std::min(static_cast<float>(totalBars) * 0.72f, bDrop1End + std::max(4.0f, std::round(totalBars * 0.12f)));
        const float bBuild2End = std::min(static_cast<float>(totalBars) * 0.78f, bMidBreakEnd + std::max(2.0f, std::round(totalBars * 0.05f)));

        pushSection(0.0f, barToFrac(bIntro), SectionType::INTRO);
        pushSection(barToFrac(bIntro), barToFrac(bVocalEnd), SectionType::VOCAL_BREAK);
        pushSection(barToFrac(bVocalEnd), barToFrac(bBuild1End), SectionType::BUILDUP);
        pushSection(barToFrac(bBuild1End), barToFrac(bFakeEnd), SectionType::FAKE_DROP);
        pushSection(barToFrac(bFakeEnd), barToFrac(bDrop1End), SectionType::DROP_1);
        pushSection(barToFrac(bDrop1End), barToFrac(bMidBreakEnd), SectionType::MID_BREAK);
        pushSection(barToFrac(bMidBreakEnd), barToFrac(bBuild2End), SectionType::BUILDUP);
        pushSection(barToFrac(bBuild2End), 1.0f, SectionType::DROP_2);
    }

    // Guarantee complete coverage [0.0 .. 1.0]
    if (!sections.empty()) {
        sections.front().startFrac = 0.0f;
        sections.back().endFrac = 1.0f;
        for (size_t i = 1; i < sections.size(); ++i) {
            sections[i].startFrac = sections[i - 1].endFrac;
        }
    }
    return sections;
}

}  // namespace

AnalysisResult MusicAnalyzer::analyze(
    const float* stereoPcm,
    size_t totalFrames,
    float sampleRate,
    size_t waveformBins
) {
    AnalysisResult res;
    if (!stereoPcm || totalFrames == 0) {
        res.waveformPeaks.assign(waveformBins, 0.0f);
        res.sections.push_back({0.0f, 1.0f, SectionType::DROP_1, "DROP 1"});
        return res;
    }

    const float sr = (sampleRate > 8000.0f) ? sampleRate : kSampleRate;
    res.durationSec = static_cast<float>(totalFrames) / sr;

    // Mix stereo to mono + compute RMS
    std::vector<float> mono(totalFrames);
    double sumSq = 0.0;
    for (size_t i = 0; i < totalFrames; ++i) {
        const float l = sanitize(stereoPcm[i * 2]);
        const float r = sanitize(stereoPcm[i * 2 + 1]);
        const float m = 0.5f * (l + r);
        mono[i] = m;
        sumSq += static_cast<double>(m) * m;
    }
    const float rms = static_cast<float>(std::sqrt(sumSq / static_cast<double>(totalFrames)));
    res.avgRmsDb = linearToDb(rms);

    // 1. BPM + Beat Phase
    const auto bpmEst = detectBpmAndPhase(mono, sr);
    res.bpm = bpmEst.bpm;
    res.bpmConfidence = bpmEst.confidence;
    res.beatOffsetSec = bpmEst.beatOffsetSec;

    // 2. Musical Key + Hardcore Sub Root
    const auto keyEst = detectMusicalKey(mono, sr);
    res.keyIndex = keyEst.keyIndex;
    res.isMinor = keyEst.isMinor;
    res.rootMidi = keyEst.rootMidi;
    res.rootHz = keyEst.rootHz;
    res.keyName = keyEst.keyName;

    // 3. Producer Arrangement Sections
    res.sections = buildArrangementSections(mono, sr, res.bpm, res.beatOffsetSec);

    // 4. Waveform Peaks
    res.waveformPeaks = extractWaveformPeaks(stereoPcm, totalFrames, waveformBins);
    return res;
}

// ============================================================================
// Built-In Musical Demo Starter Track Generator (2 Rich Studio Demos!)
// ============================================================================
std::vector<float> MusicAnalyzer::generateDemoTrack(int style, float durationSec) {
    const float dur = clampf(durationSec, 8.0f, 90.0f);
    const size_t totalFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(totalFrames * 2, 0.0f);

    XorShift32 rng(style == 0 ? 0x5A414147u : 0x4B524143u);

    const float bpm = (style == 0) ? 128.0f : 140.0f;
    const float beatSec = 60.0f / bpm;

    // Style 0: Fm - Db - Ab - Eb (MIDI roots: 53, 49, 56, 51) — Euphoric Synthpop Vocal Hook
    // Style 1: Dm - Bb - Gm - A7 (MIDI roots: 50, 46, 43, 45) — Dark Trap Anthem
    const std::array<std::array<int, 3>, 4> chords = (style == 0)
        ? std::array<std::array<int, 3>, 4>{{
            {53, 56, 60}, {49, 53, 56}, {56, 60, 63}, {51, 55, 58}
        }}
        : std::array<std::array<int, 3>, 4>{{
            {50, 53, 57}, {46, 50, 53}, {43, 46, 50}, {45, 49, 52}
        }};

    const std::array<int, 16> melodyNotes = (style == 0)
        ? std::array<int, 16>{72, 72, 75, 72, 70, 68, 70, 72, 68, 68, 72, 70, 68, 65, 67, 70}
        : std::array<int, 16>{69, 69, 70, 69, 65, 62, 65, 67, 69, 67, 65, 64, 62, 62, 65, 64};

    // Dual formant filter for realistic vocal-like hook synth!
    Biquad formant1, formant2, padLpfL, padLpfR, hatHpf;
    padLpfL.configure(BiquadType::LPF, style == 0 ? 3200.0f : 1800.0f, 1.1f);
    padLpfR.configure(BiquadType::LPF, style == 0 ? 3200.0f : 1800.0f, 1.1f);
    hatHpf.configure(BiquadType::HPF, 7200.0f, 0.9f);

    std::array<float, 6> chordPhases{};
    float bassPhase = 0.0f;
    float leadPhase = 0.0f;
    float leadPhase2 = 0.0f;

    for (size_t i = 0; i < totalFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float beatPos = t / beatSec;
        const int beatIdx = static_cast<int>(std::floor(beatPos));
        const float beatFrac = beatPos - std::floor(beatPos);
        const int barIdx = beatIdx / 4;
        const int chordIdx = barIdx % 4;
        const int melStep = (static_cast<int>(std::floor(beatPos * 2.0f))) % 16;
        const float melFrac = (beatPos * 2.0f) - std::floor(beatPos * 2.0f);

        // Sidechain pump on chords
        const float sc = (style == 0) ? (0.35f + 0.65f * std::min(1.0f, beatFrac * 2.4f)) : 1.0f;

        // 1. Detuned Supersaw Chords (Stereo)
        float chordL = 0.0f, chordR = 0.0f;
        for (int n = 0; n < 3; ++n) {
            const float fBase = midiToHz(static_cast<float>(chords[chordIdx][n]));
            const float fDetuneL = fBase * 0.996f;
            const float fDetuneR = fBase * 1.004f;
            chordPhases[n * 2] = std::fmod(chordPhases[n * 2] + fDetuneL / kSampleRate, 1.0f);
            chordPhases[n * 2 + 1] = std::fmod(chordPhases[n * 2 + 1] + fDetuneR / kSampleRate, 1.0f);
            const float sawL = 2.0f * chordPhases[n * 2] - 1.0f;
            const float sawR = 2.0f * chordPhases[n * 2 + 1] - 1.0f;
            chordL += sawL * 0.14f;
            chordR += sawR * 0.14f;
        }
        chordL = padLpfL.process(chordL) * sc;
        chordR = padLpfR.process(chordR) * sc;

        // 2. Catchy Formant Vocal Lead Melody
        const float leadHz = midiToHz(static_cast<float>(melodyNotes[melStep]))
                           * (1.0f + 0.008f * std::sin(kTwoPi * 5.4f * t)); // natural vibrato
        leadPhase = std::fmod(leadPhase + leadHz / kSampleRate, 1.0f);
        leadPhase2 = std::fmod(leadPhase2 + (leadHz * 1.003f) / kSampleRate, 1.0f);
        const float rawLead = ((2.0f * leadPhase - 1.0f) + (leadPhase2 < 0.38f ? 1.0f : -1.0f)) * 0.5f;
        const float melEnv = std::min(1.0f, melFrac * 18.0f) * std::exp(-melFrac * (style == 0 ? 2.4f : 4.2f));
        if (i % 64 == 0) {
            // Morph vowel formants ("Ah" -> "Ee") across bars
            const float morph = 0.5f + 0.5f * std::sin(kTwoPi * 0.25f * beatPos);
            formant1.configure(BiquadType::BPF_PEAK, 620.0f + 260.0f * morph, 4.5f);
            formant2.configure(BiquadType::BPF_PEAK, 1350.0f + 900.0f * morph, 5.5f);
        }
        const float vocalLead = (formant1.process(rawLead) * 1.4f + formant2.process(rawLead) * 1.1f + rawLead * 0.22f)
                              * melEnv * 0.42f;

        // 3. Bassline
        const float bassHz = midiToHz(static_cast<float>(chords[chordIdx][0] - 12));
        bassPhase = std::fmod(bassPhase + bassHz / kSampleRate, 1.0f);
        const float bassEnv = (style == 0)
            ? std::exp(-beatFrac * 3.0f)
            : std::exp(-std::fmod(beatPos, 2.0f) * 1.4f);
        const float bass = std::tanh(std::sin(kTwoPi * bassPhase) * 1.6f) * bassEnv * 0.34f;

        // 4. Pop / Trap Drums
        float drum = 0.0f;
        const bool isKickBeat = (style == 0)
            ? true
            : ((beatIdx % 4 == 0) || (beatIdx % 4 == 2 && beatFrac > 0.5f));
        if (isKickBeat) {
            const float localT = (style == 0)
                ? beatFrac * beatSec
                : ((beatIdx % 4 == 0) ? beatFrac * beatSec : (beatFrac - 0.5f) * beatSec);
            if (localT >= 0.0f && localT < 0.22f) {
                const float kPhase = kTwoPi * (48.0f * localT + (140.0f / 38.0f) * (1.0f - std::exp(-localT * 38.0f)));
                drum += std::sin(kPhase) * std::exp(-localT * 14.0f) * 0.58f;
            }
        }

        // Snare / Clap on beats 2 & 4 (style 0) or beat 3 half-time (style 1)
        const bool isSnareBeat = (style == 0) ? (beatIdx % 2 == 1) : (beatIdx % 4 == 2);
        if (isSnareBeat && beatFrac * beatSec < 0.16f) {
            const float st = beatFrac * beatSec;
            const float snap = rng.nextBipolar() * std::exp(-st * 24.0f) * 0.36f;
            const float body = std::sin(kTwoPi * 195.0f * st) * std::exp(-st * 30.0f) * 0.22f;
            drum += snap + body;
        }

        // Crisp Hi-Hats (offbeat 8ths for pop, 16ths for trap)
        const float hatSub = (style == 0) ? 2.0f : 4.0f;
        const float hatFrac = (beatPos * hatSub) - std::floor(beatPos * hatSub);
        const float hat = hatHpf.process(rng.nextBipolar()) * std::exp(-hatFrac * 22.0f) * 0.16f;

        // Slight energy lift in second half for dynamic analysis
        const float dynamics = (barIdx < 2) ? 0.80f : 1.0f;
        const float l = std::tanh((chordL + vocalLead * 0.92f + bass + drum + hat * 0.85f) * dynamics * 0.95f) * 0.88f;
        const float r = std::tanh((chordR + vocalLead * 1.08f + bass + drum + hat * 1.15f) * dynamics * 0.95f) * 0.88f;

        out[i * 2] = sanitize(l);
        out[i * 2 + 1] = sanitize(r);
    }

    return out;
}

}  // namespace zaagforge
