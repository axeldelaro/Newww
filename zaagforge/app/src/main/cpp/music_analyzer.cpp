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
    int downbeatBeatIndex = 0;
    float downbeatSec = 0.0f;
};

BpmEstimate detectBpmAndDownbeat(const std::vector<float>& mono, float sr) {
    BpmEstimate est;
    constexpr size_t kWin = 1024;
    constexpr size_t kHop = 512;
    if (mono.size() < kWin * 8) {
        return est;
    }

    // Analyze up to 110 seconds across the track for rock-solid BPM + bar downbeat phase
    const size_t maxSamples = std::min(mono.size(), static_cast<size_t>(sr * 110.0f));
    const size_t numFrames = (maxSamples - kWin) / kHop;
    if (numFrames < 64) {
        return est;
    }

    const auto hann = makeHannWindow(kWin);
    std::vector<std::complex<float>> fftBuf(kWin);
    const size_t halfBins = kWin / 2 + 1;
    std::vector<float> prevMag(halfBins, 0.0f);
    std::vector<float> flux(numFrames, 0.0f);
    std::vector<float> lowFluxVec(numFrames, 0.0f);
    std::vector<float> harmFluxVec(numFrames, 0.0f);

    const float binHz = sr / static_cast<float>(kWin);
    const size_t binLowEnd = std::min<size_t>(halfBins - 1, static_cast<size_t>(185.0f / binHz));
    const size_t binHarmEnd = std::min<size_t>(halfBins - 1, static_cast<size_t>(1600.0f / binHz));
    const size_t binMidEnd = std::min<size_t>(halfBins - 1, static_cast<size_t>(2800.0f / binHz));
    const size_t binHighEnd = std::min<size_t>(halfBins - 1, static_cast<size_t>(10000.0f / binHz));

    for (size_t f = 0; f < numFrames; ++f) {
        const size_t base = f * kHop;
        for (size_t i = 0; i < kWin; ++i) {
            fftBuf[i] = std::complex<float>(mono[base + i] * hann[i], 0.0f);
        }
        fftInPlace(fftBuf, false);

        float lowF = 0.0f;
        float harmF = 0.0f;
        float midF = 0.0f;
        float highF = 0.0f;

        for (size_t k = 1; k <= binHighEnd; ++k) {
            const float mag = std::log1p(std::abs(fftBuf[k]) * 10.0f);
            const float diff = mag - prevMag[k];
            prevMag[k] = mag;
            if (diff > 0.0f) {
                if (k <= binLowEnd) lowF += diff;
                else if (k <= binMidEnd) {
                    midF += diff;
                    if (k <= binHarmEnd) harmF += diff;
                } else {
                    highF += diff;
                }
            }
        }
        lowFluxVec[f] = lowF;
        harmFluxVec[f] = harmF;
        flux[f] = lowF * 2.3f + midF * 1.0f + highF * 0.65f;
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

    constexpr float kMinBpm = 70.0f;
    constexpr float kMaxBpm = 220.0f;
    constexpr float kBpmStep = 0.5f;
    const int numCandidates = static_cast<int>((kMaxBpm - kMinBpm) / kBpmStep) + 1;

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

        float comb = sampleAcf(lag) * 1.00f
                   + sampleAcf(lag * 2.0f) * 0.72f
                   + sampleAcf(lag * 3.0f) * 0.40f
                   + sampleAcf(lag * 4.0f) * 0.48f;

        // Stronger octave disambiguation against half-speed lock
        const float halfLagScore = sampleAcf(lag * 0.5f);
        if (bpm < 105.0f && halfLagScore > sampleAcf(lag) * 0.86f) {
            comb *= 0.78f;
        }
        const float distFromCenter = std::fabs(std::log2(bpm / 132.0f));
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

    std::vector<float> sortedScores = scores;
    std::nth_element(sortedScores.begin(), sortedScores.begin() + sortedScores.size() / 2, sortedScores.end());
    const float medianScore = std::max(sortedScores[sortedScores.size() / 2], 1e-6f);
    est.bpm = clampf(refinedBpm, kMinBpm, kMaxBpm);
    est.confidence = clampf((bestScore / medianScore - 1.0f) * 0.35f, 0.1f, 0.99f);

    // 1. Sub-beat micro-phase alignment across [0, beatFrames) in 64 steps (< 4 ms accuracy!)
    const float beatFrames = frameRate * 60.0f / est.bpm;
    constexpr int kPhaseSteps = 64;
    float bestPhaseScore = -1.0f;
    float bestPhaseFrames = 0.0f;

    auto sampleAt = [&](const std::vector<float>& arr, float pos) -> float {
        if (pos < 0.0f || pos >= static_cast<float>(arr.size() - 1)) return 0.0f;
        const size_t i0 = static_cast<size_t>(pos);
        const float frac = pos - static_cast<float>(i0);
        return arr[i0] * (1.0f - frac) + arr[i0 + 1] * frac;
    };

    for (int s = 0; s < kPhaseSteps; ++s) {
        const float phase = (static_cast<float>(s) / static_cast<float>(kPhaseSteps)) * beatFrames;
        float score = 0.0f;
        for (float pos = phase; pos < static_cast<float>(numFrames - 2); pos += beatFrames) {
            // Peak-sensitive window around exact beat frame
            const float c0 = sampleAt(onset, pos);
            const float cm = sampleAt(onset, pos - 0.5f);
            const float cp = sampleAt(onset, pos + 0.5f);
            score += c0 * 1.0f + 0.35f * (cm + cp);
        }
        if (score > bestPhaseScore) {
            bestPhaseScore = score;
            bestPhaseFrames = phase;
        }
    }
    est.beatOffsetSec = bestPhaseFrames / frameRate;

    // 2. True 4-Beat Bar Downbeat Phase Scorer (which beat 0..3 is real Beat 1 of each bar!)
    // On Beat 1 of a 4/4 bar: sub-bass kick hits strongest AND chord/harmonic flux changes most!
    std::array<double, 4> barPhaseScores{};
    barPhaseScores.fill(0.0);

    int beatIdx = 0;
    for (float pos = bestPhaseFrames; pos < static_cast<float>(numFrames - 2); pos += beatFrames) {
        const int slot = beatIdx % 4;
        const float lowE = sampleAt(lowFluxVec, pos) + 0.5f * sampleAt(lowFluxVec, pos + 1.0f);
        const float harmE = sampleAt(harmFluxVec, pos) + 0.5f * sampleAt(harmFluxVec, pos + 1.0f);
        const float onE = sampleAt(onset, pos);
        // Beat 1 carries combined harmonic chord change + strong kick transient!
        // Also measure 4-bar phrase boundary accent (every 16 beats)
        const double phraseBoost = ((beatIdx / 4) % 4 == 0) ? 1.30 : 1.0;
        barPhaseScores[slot] += static_cast<double>(lowE * 1.35f + harmE * 1.55f + onE * 0.80f) * phraseBoost;
        ++beatIdx;
    }

    int bestSlot = 0;
    double bestBarScore = barPhaseScores[0];
    for (int s = 1; s < 4; ++s) {
        if (barPhaseScores[s] > bestBarScore * 1.02) {
            bestBarScore = barPhaseScores[s];
            bestSlot = s;
        }
    }
    est.downbeatBeatIndex = bestSlot;
    est.downbeatSec = est.beatOffsetSec + static_cast<float>(bestSlot) * (60.0f / est.bpm);
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
            const float regWeight = (freq < 600.0f) ? 1.45f : 1.0f;

            const int pc = ((nearestMidi % 12) + 12) % 12;
            chroma[pc] += static_cast<double>(mag * mag * weight * regWeight);
        }
    }

    double totalChroma = 0.0;
    for (double v : chroma) totalChroma += v;
    if (totalChroma < 1e-9) {
        return key;
    }
    for (double& v : chroma) v /= totalChroma;

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

struct BarStructureAnalysis {
    int totalBars = 8;
    int chorus1Bar = 4;
    int chorus2Bar = 8;
    std::vector<float> barRms;
    std::vector<float> barVocal;
    std::vector<float> barNovelty;
};

/**
 * Computes bar-by-bar RMS, center vocal-band energy (300..3400 Hz Mid minus Side),
 * and 4-bar phrase novelty to accurately locate real Chorus 1 & Chorus 2 downbeats!
 */
BarStructureAnalysis analyzeMusicalStructure(
    const float* stereoPcm,
    size_t totalFrames,
    float sr,
    float bpm,
    float downbeatSec
) {
    BarStructureAnalysis st;
    const float durationSec = static_cast<float>(totalFrames) / sr;
    const float barSec = (60.0f / clampf(bpm, 60.0f, 240.0f)) * 4.0f;
    st.totalBars = std::max(4, static_cast<int>(std::floor((durationSec - downbeatSec) / barSec)));

    st.barRms.assign(st.totalBars, 0.0f);
    st.barVocal.assign(st.totalBars, 0.0f);
    st.barNovelty.assign(st.totalBars, 0.0f);

    Biquad vocalHpf, vocalLpf;
    vocalHpf.configure(BiquadType::HPF, 300.0f, 0.707f, 0.0f, sr);
    vocalLpf.configure(BiquadType::LPF, 3400.0f, 0.707f, 0.0f, sr);

    float maxRms = 1e-6f;
    float maxVoc = 1e-6f;

    for (int b = 0; b < st.totalBars; ++b) {
        const size_t s0 = static_cast<size_t>(std::max(0.0f, (downbeatSec + b * barSec) * sr));
        const size_t s1 = std::min(totalFrames, static_cast<size_t>((downbeatSec + (b + 1) * barSec) * sr));
        if (s1 <= s0) continue;

        double eAll = 0.0;
        double eVoc = 0.0;
        // Stride 2 for fast accurate bar envelope over 5-minute songs
        size_t count = 0;
        for (size_t i = s0; i < s1; i += 2) {
            const float l = sanitize(stereoPcm[i * 2]);
            const float r = sanitize(stereoPcm[i * 2 + 1]);
            const float mid = 0.5f * (l + r);
            const float side = 0.5f * (l - r);
            eAll += static_cast<double>(mid) * mid + 0.5 * static_cast<double>(side) * side;

            const float vMid = vocalLpf.process(vocalHpf.process(mid));
            // Center vocal prominence (penalize pure wide stereo pads slightly)
            const float vocClean = std::max(0.0f, std::fabs(vMid) - 0.35f * std::fabs(side));
            eVoc += static_cast<double>(vocClean) * vocClean;
            ++count;
        }
        if (count > 0) {
            st.barRms[b] = static_cast<float>(std::sqrt(eAll / static_cast<double>(count)));
            st.barVocal[b] = static_cast<float>(std::sqrt(eVoc / static_cast<double>(count)));
            maxRms = std::max(maxRms, st.barRms[b]);
            maxVoc = std::max(maxVoc, st.barVocal[b]);
        }
    }

    for (int b = 0; b < st.totalBars; ++b) {
        st.barRms[b] /= maxRms;
        st.barVocal[b] /= maxVoc;
    }

    // Compute 4-bar checkerboard novelty contrast: sharp jump from quieter verse/pre-chorus into loud chorus!
    const int win = (st.totalBars >= 32) ? 4 : 2;
    for (int b = win; b < st.totalBars - win; ++b) {
        float beforeRms = 0.0f, afterRms = 0.0f;
        float beforeVoc = 0.0f, afterVoc = 0.0f;
        for (int k = 0; k < win; ++k) {
            beforeRms += st.barRms[b - win + k];
            afterRms += st.barRms[b + k];
            beforeVoc += st.barVocal[b - win + k];
            afterVoc += st.barVocal[b + k];
        }
        beforeRms /= static_cast<float>(win);
        afterRms /= static_cast<float>(win);
        beforeVoc /= static_cast<float>(win);
        afterVoc /= static_cast<float>(win);

        // Positive novelty = energy & vocal chorus lifts cleanly right AT bar `b`!
        const float energyLift = std::max(0.0f, afterRms - beforeRms * 0.92f);
        const float vocalLift = std::max(0.0f, afterVoc - beforeVoc * 0.90f);
        const float sustainedChorus = afterRms * 0.55f + afterVoc * 0.45f;
        // Favor 4-bar musical phrase boundaries (bars 4, 8, 12, 16, 20, ...)
        const float phraseAlign = (b % 4 == 0) ? 1.28f : ((b % 2 == 0) ? 1.05f : 0.72f);
        st.barNovelty[b] = (energyLift * 1.70f + vocalLift * 0.85f + sustainedChorus * 0.65f) * phraseAlign;
    }

    // Find real Chorus 1 bar in [18% .. 52%] of track (leaving room for build-up!)
    if (st.totalBars <= 20) {
        // Short 30s preview / demo: snap cleanly to 4-bar boundary near 35..45%
        int target = std::max(4, ((static_cast<int>(std::round(st.totalBars * 0.38f)) + 2) / 4) * 4);
        target = std::min(target, std::max(4, st.totalBars - 4));
        // Search ±2 bars for best novelty lift
        int bestB = target;
        float bestSc = -1.0f;
        for (int b = std::max(4, target - 2); b <= std::min(st.totalBars - 4, target + 2); ++b) {
            if (st.barNovelty[b] > bestSc) {
                bestSc = st.barNovelty[b];
                bestB = b;
            }
        }
        st.chorus1Bar = bestB;
        st.chorus2Bar = std::min(st.totalBars - 2, st.chorus1Bar + std::max(4, ((st.totalBars - st.chorus1Bar) / 2 / 4) * 4));
    } else {
        // Full song (> 45s): locate true Chorus 1 & Chorus 2 phrase boundaries!
        const int c1Lo = std::max(8, static_cast<int>(std::floor(st.totalBars * 0.18f)));
        const int c1Hi = std::min(st.totalBars - 12, static_cast<int>(std::ceil(st.totalBars * 0.50f)));
        int bestC1 = ((c1Lo + c1Hi) / 2 / 4) * 4;
        float bestC1Score = -1.0f;
        for (int b = c1Lo; b <= c1Hi; ++b) {
            // Mild preference for classic 24%..38% chorus window while respecting real acoustic jump
            const float frac = static_cast<float>(b) / static_cast<float>(st.totalBars);
            const float posPrior = 1.0f - 0.35f * std::fabs(frac - 0.30f);
            const float sc = st.barNovelty[b] * posPrior;
            if (sc > bestC1Score) {
                bestC1Score = sc;
                bestC1 = b;
            }
        }
        // Snap cleanly to nearest 2-bar or 4-bar phrase boundary if score is close
        if (bestC1 % 4 != 0) {
            const int snapDown = (bestC1 / 4) * 4;
            const int snapUp = snapDown + 4;
            if (snapDown >= c1Lo && st.barNovelty[snapDown] >= bestC1Score * 0.86f) bestC1 = snapDown;
            else if (snapUp <= c1Hi && st.barNovelty[snapUp] >= bestC1Score * 0.86f) bestC1 = snapUp;
        }
        st.chorus1Bar = std::max(6, bestC1);

        // Find Chorus 2 at least 16 bars after Chorus 1 (in [56% .. 86%])
        const int c2Lo = std::max(st.chorus1Bar + 12, static_cast<int>(std::floor(st.totalBars * 0.56f)));
        const int c2Hi = std::min(st.totalBars - 6, static_cast<int>(std::ceil(st.totalBars * 0.85f)));
        int bestC2 = std::min(st.totalBars - 6, std::max(c2Lo, ((static_cast<int>(st.totalBars * 0.68f)) / 4) * 4));
        float bestC2Score = -1.0f;
        for (int b = c2Lo; b <= c2Hi; ++b) {
            const float sc = st.barNovelty[b];
            if (sc > bestC2Score) {
                bestC2Score = sc;
                bestC2 = b;
            }
        }
        st.chorus2Bar = bestC2;
    }

    return st;
}

}  // namespace

std::vector<SectionMarker> MusicAnalyzer::buildArrangement(
    const AnalysisResult& analysis,
    const ArrangementOptions& options
) {
    std::vector<SectionMarker> sections;
    const float durationSec = std::max(1.0f, analysis.durationSec);
    const float bpm = (options.bpmOverride > 55.0f) ? clampf(options.bpmOverride, 60.0f, 240.0f) : clampf(analysis.bpm, 60.0f, 240.0f);
    const float beatSec = 60.0f / bpm;
    const float barSec = beatSec * 4.0f;

    const float effectiveDownbeatSec = std::max(
        0.0f,
        analysis.downbeatSec
            + static_cast<float>(options.gridBeatShift) * beatSec
            + options.gridPhaseNudgeMs * 0.001f
    );
    const int totalBars = std::max(4, static_cast<int>(std::floor((durationSec - effectiveDownbeatSec) / barSec)));

    auto barToFrac = [&](int barIdx) -> float {
        if (barIdx <= 0) return 0.0f;
        if (barIdx >= totalBars) return 1.0f;
        const float sec = effectiveDownbeatSec + static_cast<float>(barIdx) * barSec;
        return clampf(sec / durationSec, 0.0f, 1.0f);
    };

    auto pushSec = [&](int b0, int b1, SectionType type) {
        const int s0 = std::max(0, std::min(totalBars, b0));
        const int s1 = std::max(s0, std::min(totalBars, b1));
        if (s1 > s0) {
            sections.push_back({
                barToFrac(s0),
                barToFrac(s1),
                type,
                MusicAnalyzer::sectionTypeName(type),
                s0,
                s1
            });
        }
    };

    if (durationSec <= 4.0f || totalBars < 6) {
        sections.push_back({0.0f, 1.0f, SectionType::DROP_1, "DROP 1", 0, totalBars});
        return sections;
    }

    // Determine exact Drop 1 & Drop 2 target bars (strictly integer musical bars!)
    int drop1Bar = analysis.chorus1Bar;
    if (options.customDrop1Frac >= 0.0f && options.customDrop1Frac <= 0.95f) {
        const float targetSec = options.customDrop1Frac * durationSec;
        drop1Bar = static_cast<int>(std::llround((targetSec - effectiveDownbeatSec) / barSec));
    } else if (options.dropMode == 1) {
        // DIRECT_DROP: fast festival entry after short 4-bar buildup
        drop1Bar = std::min(totalBars - 4, std::max(4, options.buildupBars));
    } else if (options.dropMode == 2) {
        // VOCALS_FIRST: preserve full verse + chorus 1 vocal before slamming in later!
        drop1Bar = std::min(totalBars - 8, analysis.chorus1Bar + 8);
    }
    drop1Bar += options.drop1BarNudge;
    drop1Bar = std::max(2, std::min(totalBars - 4, drop1Bar));

    int drop2Bar = analysis.chorus2Bar;
    if (options.customDrop2Frac >= 0.0f && options.customDrop2Frac <= 0.98f) {
        const float targetSec = options.customDrop2Frac * durationSec;
        drop2Bar = static_cast<int>(std::llround((targetSec - effectiveDownbeatSec) / barSec));
    } else if (options.dropMode == 3) {
        // DOUBLE_DROP: tight 4-bar mid-break into massive second drop!
        drop2Bar = std::min(totalBars - 4, drop1Bar + std::max(8, (totalBars - drop1Bar) / 2));
    }
    drop2Bar += options.drop2BarNudge;

    // Build-up + optional 1-bar Fake-Drop sit STRICTLY BEFORE drop1Bar so DROP 1 slams on BEAT 1!
    const int reqBuildBars = std::max(2, std::min(12, options.buildupBars));
    const int fakeBars = (options.enableFakeDrop && drop1Bar >= 3) ? 1 : 0;
    const int fakeStart = drop1Bar - fakeBars;
    const int buildStart = std::max(0, fakeStart - reqBuildBars);

    // Intro / Vocal Break cleanly fills [0 .. buildStart] without kicking early!
    if (buildStart >= 6 && durationSec > 46.0f) {
        const int introEnd = std::max(2, std::min(buildStart - 2, (buildStart / 3 / 2) * 2));
        pushSec(0, introEnd, SectionType::INTRO);
        pushSec(introEnd, buildStart, SectionType::VOCAL_BREAK);
    } else if (buildStart > 0) {
        pushSec(0, buildStart, SectionType::VOCAL_BREAK);
    }

    pushSec(buildStart, fakeStart, SectionType::BUILDUP);
    if (fakeBars > 0) {
        pushSec(fakeStart, drop1Bar, SectionType::FAKE_DROP);
    }

    // Decide whether we have room for Mid-Break + Build 2 + Drop 2 (full song) or Direct Drop 1 -> Drop 2 Switch (30s preview)
    const int remBars = totalBars - drop1Bar;
    if (durationSec <= 46.0f || remBars < 14) {
        // Short 30s preview / demo: Drop 1 -> Drop 2 Kick-Switch Finale on exact bar!
        int switchBar = (drop2Bar > drop1Bar + 2 && drop2Bar < totalBars - 1)
            ? drop2Bar
            : (drop1Bar + std::max(2, (remBars / 2 / 2) * 2));
        switchBar = std::max(drop1Bar + 2, std::min(totalBars - 1, switchBar));
        pushSec(drop1Bar, switchBar, SectionType::DROP_1);
        pushSec(switchBar, totalBars, SectionType::DROP_2);
    } else {
        // Full song: Drop 1 -> Mid Vocal Break -> Build 2 -> Drop 2 Finale!
        drop2Bar = std::max(drop1Bar + 10, std::min(totalBars - 4, drop2Bar));
        const int build2Bars = std::min(4, std::max(2, reqBuildBars / 2));
        const int build2Start = drop2Bar - build2Bars;
        const int midBreakLen = (options.dropMode == 3) ? 4 : std::max(4, std::min(12, (drop2Bar - drop1Bar) / 3));
        const int midBreakStart = std::max(drop1Bar + 4, build2Start - midBreakLen);

        pushSec(drop1Bar, midBreakStart, SectionType::DROP_1);
        pushSec(midBreakStart, build2Start, SectionType::MID_BREAK);
        pushSec(build2Start, drop2Bar, SectionType::BUILDUP);
        pushSec(drop2Bar, totalBars, SectionType::DROP_2);
    }

    if (!sections.empty()) {
        sections.front().startFrac = 0.0f;
        sections.back().endFrac = 1.0f;
        for (size_t i = 1; i < sections.size(); ++i) {
            sections[i].startFrac = sections[i - 1].endFrac;
        }
    }
    return sections;
}

AnalysisResult MusicAnalyzer::analyze(
    const float* stereoPcm,
    size_t totalFrames,
    float sampleRate,
    size_t waveformBins
) {
    AnalysisResult res;
    if (!stereoPcm || totalFrames == 0) {
        res.waveformPeaks.assign(waveformBins, 0.0f);
        res.sections.push_back({0.0f, 1.0f, SectionType::DROP_1, "DROP 1", 0, 4});
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

    // 1. BPM + Sub-Beat Phase + 4-Beat Bar Downbeat
    const auto bpmEst = detectBpmAndDownbeat(mono, sr);
    res.bpm = bpmEst.bpm;
    res.bpmConfidence = bpmEst.confidence;
    res.beatOffsetSec = bpmEst.beatOffsetSec;
    res.downbeatBeatIndex = bpmEst.downbeatBeatIndex;
    res.downbeatSec = bpmEst.downbeatSec;

    // 2. Musical Key + Hardcore Sub Root
    const auto keyEst = detectMusicalKey(mono, sr);
    res.keyIndex = keyEst.keyIndex;
    res.isMinor = keyEst.isMinor;
    res.rootMidi = keyEst.rootMidi;
    res.rootHz = keyEst.rootHz;
    res.keyName = keyEst.keyName;

    // 3. Structural Chorus / Vocal-Hook Detection
    const auto st = analyzeMusicalStructure(stereoPcm, totalFrames, sr, res.bpm, res.downbeatSec);
    res.totalBars = st.totalBars;
    res.chorus1Bar = st.chorus1Bar;
    res.chorus2Bar = st.chorus2Bar;
    const float barSec = (60.0f / res.bpm) * 4.0f;
    res.chorus1Sec = clampf(res.downbeatSec + res.chorus1Bar * barSec, 0.0f, res.durationSec);
    res.chorus2Sec = clampf(res.downbeatSec + res.chorus2Bar * barSec, 0.0f, res.durationSec);
    res.barRms = st.barRms;
    res.barVocalEnergy = st.barVocal;
    res.barNovelty = st.barNovelty;

    // 4. Bar-Quantized Arrangement Sections aligned to real Chorus 1 & 2!
    ArrangementOptions defOpts;
    defOpts.buildupBars = (res.durationSec <= 46.0f) ? 4 : 4;
    res.sections = buildArrangement(res, defOpts);

    // 5. Waveform Peaks
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

        // Realistic verse (bars 0..5) -> chorus lift (bar 6+) so chorus detector locks onto exact phrase!
        const bool isChorus = (barIdx >= 6);
        const float sc = (style == 0) ? (0.35f + 0.65f * std::min(1.0f, beatFrac * 2.4f)) : 1.0f;

        float chordL = 0.0f, chordR = 0.0f;
        for (int n = 0; n < 3; ++n) {
            const float fBase = midiToHz(static_cast<float>(chords[chordIdx][n]));
            chordPhases[n * 2] = std::fmod(chordPhases[n * 2] + (fBase * 0.996f) / kSampleRate, 1.0f);
            chordPhases[n * 2 + 1] = std::fmod(chordPhases[n * 2 + 1] + (fBase * 1.004f) / kSampleRate, 1.0f);
            chordL += (2.0f * chordPhases[n * 2] - 1.0f) * 0.14f;
            chordR += (2.0f * chordPhases[n * 2 + 1] - 1.0f) * 0.14f;
        }
        chordL = padLpfL.process(chordL) * sc * (isChorus ? 1.05f : 0.65f);
        chordR = padLpfR.process(chordR) * sc * (isChorus ? 1.05f : 0.65f);

        const float leadHz = midiToHz(static_cast<float>(melodyNotes[melStep]))
                           * (1.0f + 0.008f * std::sin(kTwoPi * 5.4f * t));
        leadPhase = std::fmod(leadPhase + leadHz / kSampleRate, 1.0f);
        leadPhase2 = std::fmod(leadPhase2 + (leadHz * 1.003f) / kSampleRate, 1.0f);
        const float rawLead = ((2.0f * leadPhase - 1.0f) + (leadPhase2 < 0.38f ? 1.0f : -1.0f)) * 0.5f;
        const float melEnv = std::min(1.0f, melFrac * 18.0f) * std::exp(-melFrac * (style == 0 ? 2.4f : 4.2f));
        if (i % 64 == 0) {
            const float morph = 0.5f + 0.5f * std::sin(kTwoPi * 0.25f * beatPos);
            formant1.configure(BiquadType::BPF_PEAK, 620.0f + 260.0f * morph, 4.5f);
            formant2.configure(BiquadType::BPF_PEAK, 1350.0f + 900.0f * morph, 5.5f);
        }
        const float vocalLead = (formant1.process(rawLead) * 1.4f + formant2.process(rawLead) * 1.1f + rawLead * 0.22f)
                              * melEnv * (isChorus ? 0.50f : 0.36f);

        const float bassHz = midiToHz(static_cast<float>(chords[chordIdx][0] - 12));
        bassPhase = std::fmod(bassPhase + bassHz / kSampleRate, 1.0f);
        const float bassEnv = (style == 0)
            ? std::exp(-beatFrac * 3.0f)
            : std::exp(-std::fmod(beatPos, 2.0f) * 1.4f);
        const float bass = std::tanh(std::sin(kTwoPi * bassPhase) * 1.5f) * bassEnv * (isChorus ? 0.36f : 0.20f);

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
                drum += std::sin(kPhase) * std::exp(-localT * 14.0f) * (isChorus ? 0.58f : 0.42f);
            }
        }

        const bool isSnareBeat = (style == 0) ? (beatIdx % 2 == 1) : (beatIdx % 4 == 2);
        if (isSnareBeat && beatFrac * beatSec < 0.16f) {
            const float st = beatFrac * beatSec;
            drum += rng.nextBipolar() * std::exp(-st * 24.0f) * 0.34f
                  + std::sin(kTwoPi * 195.0f * st) * std::exp(-st * 30.0f) * 0.20f;
        }

        const float hatSub = (style == 0) ? 2.0f : 4.0f;
        const float hatFrac = (beatPos * hatSub) - std::floor(beatPos * hatSub);
        const float hat = hatHpf.process(rng.nextBipolar()) * std::exp(-hatFrac * 22.0f) * (isChorus ? 0.18f : 0.10f);

        const float dynamics = (barIdx < 2) ? 0.68f : (isChorus ? 1.0f : 0.80f);
        const float l = std::tanh((chordL + vocalLead * 0.96f + bass + drum + hat * 0.85f) * dynamics * 0.94f) * 0.88f;
        const float r = std::tanh((chordR + vocalLead * 1.04f + bass + drum + hat * 1.15f) * dynamics * 0.94f) * 0.88f;

        out[i * 2] = sanitize(l);
        out[i * 2 + 1] = sanitize(r);
    }

    return out;
}

}  // namespace zaagforge
