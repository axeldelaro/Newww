#include "beatmap_generator.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <numeric>

namespace strikecam {

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr int kFftSize = 1024;
constexpr int kHopSize = 512;

struct FftTables {
    std::array<float, kFftSize> hann{};
    std::array<int, kFftSize> bitRev{};
    std::array<float, kFftSize / 2> twCos{};
    std::array<float, kFftSize / 2> twSin{};

    FftTables() {
        for (int i = 0; i < kFftSize; ++i) {
            hann[i] = 0.5f * (1.0f - std::cos(2.0f * kPi * static_cast<float>(i) / static_cast<float>(kFftSize - 1)));
        }
        int logN = 0;
        while ((1 << logN) < kFftSize) ++logN;
        for (int i = 0; i < kFftSize; ++i) {
            int rev = 0;
            int x = i;
            for (int b = 0; b < logN; ++b) {
                rev = (rev << 1) | (x & 1);
                x >>= 1;
            }
            bitRev[i] = rev;
        }
        for (int i = 0; i < kFftSize / 2; ++i) {
            float angle = -2.0f * kPi * static_cast<float>(i) / static_cast<float>(kFftSize);
            twCos[i] = std::cos(angle);
            twSin[i] = std::sin(angle);
        }
    }
};

const FftTables& getFftTables() {
    static const FftTables tables;
    return tables;
}

inline void computeRadix2Fft(float* re, float* im) {
    const auto& tables = getFftTables();
    for (int i = 0; i < kFftSize; ++i) {
        int j = tables.bitRev[i];
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    for (int len = 2; len <= kFftSize; len <<= 1) {
        const int half = len >> 1;
        const int step = kFftSize / len;
        for (int i = 0; i < kFftSize; i += len) {
            for (int j = 0; j < half; ++j) {
                const int twIdx = j * step;
                const float wr = tables.twCos[twIdx];
                const float wi = tables.twSin[twIdx];
                const int uIdx = i + j;
                const int vIdx = uIdx + half;
                const float vr = re[vIdx] * wr - im[vIdx] * wi;
                const float vi = re[vIdx] * wi + im[vIdx] * wr;
                const float ur = re[uIdx];
                const float ui = im[uIdx];
                re[uIdx] = ur + vr;
                im[uIdx] = ui + vi;
                re[vIdx] = ur - vr;
                im[vIdx] = ui - vi;
            }
        }
    }
}

void normalizeFluxAdaptive(std::vector<float>& flux, int halfWindow) {
    const int n = static_cast<int>(flux.size());
    if (n == 0) return;
    std::vector<float> out(n, 0.0f);
    std::vector<float> prefix(n + 1, 0.0f);
    for (int i = 0; i < n; ++i) {
        prefix[i + 1] = prefix[i] + flux[i];
    }
    float maxVal = 1e-6f;
    for (int i = 0; i < n; ++i) {
        int lo = std::max(0, i - halfWindow);
        int hi = std::min(n - 1, i + halfWindow);
        float mean = (prefix[hi + 1] - prefix[lo]) / static_cast<float>(hi - lo + 1);
        float v = std::max(0.0f, flux[i] - mean * 0.90f);
        out[i] = v;
        if (v > maxVal) maxVal = v;
    }
    std::vector<float> sorted = out;
    std::sort(sorted.begin(), sorted.end());
    float normScale = sorted[static_cast<size_t>(sorted.size() * 98 / 100)];
    if (normScale < 1e-5f) normScale = maxVal;
    for (float& v : out) {
        v = std::clamp(v / normScale, 0.0f, 2.0f);
    }
    flux.swap(out);
}

inline float sampleInterpolated(const std::vector<float>& signal, float idx) {
    if (signal.empty()) return 0.0f;
    if (idx <= 0.0f) return signal.front();
    const int last = static_cast<int>(signal.size()) - 1;
    if (idx >= static_cast<float>(last)) return signal.back();
    const int i0 = static_cast<int>(idx);
    const float frac = idx - static_cast<float>(i0);
    return signal[i0] * (1.0f - frac) + signal[i0 + 1] * frac;
}

// Precompute normalized autocorrelation table once for integer lags [0..maxLag]
std::vector<float> buildAutocorrTable(const std::vector<float>& onset, int maxLag) {
    const int n = static_cast<int>(onset.size());
    const int clampedMax = std::min(maxLag, std::max(0, n - 8));
    std::vector<float> ac(static_cast<size_t>(clampedMax + 1), 0.0f);
    if (clampedMax < 4) return ac;

    std::vector<double> sqPrefix(n + 1, 0.0);
    for (int i = 0; i < n; ++i) {
        sqPrefix[i + 1] = sqPrefix[i] + static_cast<double>(onset[i] * onset[i]);
    }

    for (int lag = 4; lag <= clampedMax; ++lag) {
        const int count = n - lag;
        double sum = 0.0;
        const float* a = onset.data();
        const float* b = onset.data() + lag;
        for (int i = 0; i < count; ++i) {
            sum += static_cast<double>(a[i] * b[i]);
        }
        double eA = sqPrefix[count];
        double eB = sqPrefix[n] - sqPrefix[lag];
        ac[lag] = static_cast<float>(sum / (std::sqrt(eA * eB) + 1e-9));
    }
    return ac;
}

struct GridMatch {
    float score = 0.0f;
    float phaseFrames = 0.0f;
    float minHalfStrength = 0.0f;
};

GridMatch evaluateBeatGrid(const std::vector<float>& onset, float periodFrames) {
    GridMatch best;
    const int n = static_cast<int>(onset.size());
    if (n < 16 || periodFrames < 6.0f) return best;

    const int phaseSteps = std::clamp(static_cast<int>(std::round(periodFrames * 1.5f)), 16, 72);
    for (int s = 0; s < phaseSteps; ++s) {
        const float phi = (static_cast<float>(s) / static_cast<float>(phaseSteps)) * periodFrames;
        double evenSum = 0.0;
        double oddSum = 0.0;
        int evenCount = 0;
        int oddCount = 0;
        int beatIdx = 0;
        for (float pos = phi; pos < static_cast<float>(n - 2); pos += periodFrames) {
            const float v = std::max({
                sampleInterpolated(onset, pos - 0.75f),
                sampleInterpolated(onset, pos),
                sampleInterpolated(onset, pos + 0.75f)
            });
            if ((beatIdx & 1) == 0) {
                evenSum += v;
                ++evenCount;
            } else {
                oddSum += v;
                ++oddCount;
            }
            ++beatIdx;
        }
        const int totalBeats = evenCount + oddCount;
        if (totalBeats < 4) continue;
        const float evenAvg = evenCount > 0 ? static_cast<float>(evenSum / evenCount) : 0.0f;
        const float oddAvg = oddCount > 0 ? static_cast<float>(oddSum / oddCount) : 0.0f;
        const float avg = static_cast<float>((evenSum + oddSum) / totalBeats);
        const float balance = std::min(evenAvg, oddAvg) / (std::max(evenAvg, oddAvg) + 1e-5f);
        const float score = avg * (0.45f + 0.55f * balance);
        if (score > best.score) {
            best.score = score;
            best.phaseFrames = phi;
            best.minHalfStrength = std::min(evenAvg, oddAvg);
        }
    }
    return best;
}

void detectTempoAndPhase(
    const std::vector<float>& onset,
    float frameRateHz,
    float& outBpm,
    float& outFirstBeatMs
) {
    outBpm = 140.0f;
    outFirstBeatMs = 0.0f;
    if (onset.size() < 40) return;

    // Max lag needed for 4x period at 78 BPM
    const int maxLag = static_cast<int>(std::ceil(frameRateHz * 60.0f / 78.0f * 4.0f)) + 4;
    const std::vector<float> ac = buildAutocorrTable(onset, maxLag);

    auto acAt = [&](float lag) -> float {
        return sampleInterpolated(ac, lag);
    };

    float bestBpm = 140.0f;
    float bestScore = -1.0f;

    // Instant O(1)-per-BPM scan across 80..230 BPM using precomputed autocorrelation table
    for (float bpm = 80.0f; bpm <= 230.0f; bpm += 0.5f) {
        const float period = frameRateHz * 60.0f / bpm;
        const float r1 = acAt(period);
        const float r2 = acAt(period * 2.0f);
        const float r3 = acAt(period * 3.0f);
        const float r4 = acAt(period * 4.0f);

        float tempoPrior = 1.0f;
        if (bpm < 105.0f) tempoPrior = 0.88f;
        else if (bpm >= 120.0f && bpm <= 220.0f) tempoPrior = 1.04f;

        const float score = (0.46f * r1 + 0.28f * r2 + 0.16f * r3 + 0.10f * r4) * tempoPrior;
        if (score > bestScore) {
            bestScore = score;
            bestBpm = bpm;
        }
    }

    auto scoreCandidate = [&](float candidateBpm) -> std::pair<float, GridMatch> {
        if (candidateBpm < 78.0f || candidateBpm > 235.0f) return {-1.0f, GridMatch{}};
        const float period = frameRateHz * 60.0f / candidateBpm;
        const float r1 = acAt(period);
        const float r2 = acAt(period * 2.0f);
        const GridMatch gm = evaluateBeatGrid(onset, period);
        const float combined = (0.55f * r1 + 0.45f * r2) * 0.55f + gm.score * 0.45f;
        return {combined, gm};
    };

    auto [baseScore, baseGm] = scoreCandidate(bestBpm);
    float chosenBpm = bestBpm;
    float chosenScore = baseScore;
    GridMatch chosenGm = baseGm;

    // Disambiguate half/double octave (e.g. 100 -> 200 BPM or 107.5 -> 215 BPM)
    if (bestBpm * 2.0f <= 232.0f) {
        auto [dblScore, dblGm] = scoreCandidate(bestBpm * 2.0f);
        const float rDbl = acAt(frameRateHz * 60.0f / (bestBpm * 2.0f));
        const float rBase = acAt(frameRateHz * 60.0f / bestBpm);
        if (rDbl >= rBase * 0.72f && dblGm.minHalfStrength >= baseGm.minHalfStrength * 0.55f && dblScore >= chosenScore * 0.92f) {
            chosenBpm = bestBpm * 2.0f;
            chosenScore = dblScore;
            chosenGm = dblGm;
        }
    }
    if (chosenBpm * 0.5f >= 88.0f) {
        auto [halfScore, halfGm] = scoreCandidate(chosenBpm * 0.5f);
        const float rCur = acAt(frameRateHz * 60.0f / chosenBpm);
        const float rHalf = acAt(frameRateHz * 60.0f / (chosenBpm * 0.5f));
        if (rCur < rHalf * 0.52f && halfScore > chosenScore * 1.08f) {
            chosenBpm = chosenBpm * 0.5f;
            chosenScore = halfScore;
            chosenGm = halfGm;
        }
    }

    // Fast fine refinement ±1.6 BPM in 0.2 BPM steps
    float fineBestBpm = chosenBpm;
    float fineBestScore = -1.0f;
    GridMatch fineBestGm = chosenGm;
    for (float delta = -1.6f; delta <= 1.61f; delta += 0.2f) {
        const float cand = std::clamp(chosenBpm + delta, 78.0f, 235.0f);
        auto [s, gm] = scoreCandidate(cand);
        if (s > fineBestScore) {
            fineBestScore = s;
            fineBestBpm = cand;
            fineBestGm = gm;
        }
    }

    outBpm = std::round(fineBestBpm * 10.0f) / 10.0f;
    outFirstBeatMs = (fineBestGm.phaseFrames / frameRateHz) * 1000.0f;
}

std::vector<DropSection> detectDropSections(
    const std::vector<float>& rmsEnergy,
    float frameRateHz
) {
    std::vector<DropSection> drops;
    const int n = static_cast<int>(rmsEnergy.size());
    if (n < 20) return drops;

    // 2.4-second smoothing window cancels out single-beat kick-decay ripple completely
    const int halfWin = std::max(8, static_cast<int>(std::round(frameRateHz * 1.2f)));
    std::vector<float> smooth(n, 0.0f);
    std::vector<float> prefix(n + 1, 0.0f);
    for (int i = 0; i < n; ++i) {
        prefix[i + 1] = prefix[i] + rmsEnergy[i];
    }
    for (int i = 0; i < n; ++i) {
        const int lo = std::max(0, i - halfWin);
        const int hi = std::min(n - 1, i + halfWin);
        smooth[i] = (prefix[hi + 1] - prefix[lo]) / static_cast<float>(hi - lo + 1);
    }

    std::vector<float> active;
    active.reserve(n);
    for (float v : smooth) {
        if (v > 1e-4f) active.push_back(v);
    }
    if (active.empty()) return drops;
    std::sort(active.begin(), active.end());
    const float p25 = active[active.size() * 25 / 100];
    const float median = active[active.size() / 2];
    const float p90 = active[active.size() * 90 / 100];

    // Robust threshold between low-energy sections and high-energy drops
    float threshold = p25 + 0.55f * (p90 - p25);
    if ((p90 - p25) < p90 * 0.12f) {
        // Heavily compressed / flat loudness master: highlight top quartile sections
        threshold = median * 1.03f;
    }
    if (threshold < 1e-4f) return drops;

    const int minDropFrames = std::max(8, static_cast<int>(std::round(frameRateHz * 1.5f)));
    const float mergeGapMs = 600.0f;

    bool inDrop = false;
    int startIdx = 0;
    float peak = 0.0f;

    auto pushSection = [&](int sIdx, int eIdx, float pk) {
        if (eIdx - sIdx < minDropFrames) return;
        float startMs = (static_cast<float>(sIdx) / frameRateHz) * 1000.0f;
        float endMs = (static_cast<float>(eIdx) / frameRateHz) * 1000.0f;
        if (!drops.empty() && (startMs - drops.back().endMs) <= mergeGapMs) {
            drops.back().endMs = endMs;
            drops.back().peakEnergy = std::max(drops.back().peakEnergy, pk);
        } else {
            drops.push_back(DropSection{startMs, endMs, pk});
        }
    };

    for (int i = 0; i < n; ++i) {
        const bool high = smooth[i] >= threshold;
        if (high && !inDrop) {
            inDrop = true;
            startIdx = i;
            peak = smooth[i];
        } else if (high && inDrop) {
            if (smooth[i] > peak) peak = smooth[i];
        } else if (!high && inDrop) {
            pushSection(startIdx, i, peak);
            inDrop = false;
        }
    }
    if (inDrop) {
        pushSection(startIdx, n, peak);
    }
    return drops;
}

bool isInDropSection(float timeMs, const std::vector<DropSection>& drops) {
    for (const auto& d : drops) {
        if (timeMs >= d.startMs && timeMs <= d.endMs) return true;
    }
    return false;
}

struct WallInterval {
    float blockStartMs;
    float blockEndMs;
};

bool overlapsAnyWall(float timeMs, const std::vector<WallInterval>& zones) {
    for (const auto& z : zones) {
        if (timeMs >= z.blockStartMs && timeMs <= z.blockEndMs) {
            return true;
        }
    }
    return false;
}

} // namespace

SpectralAnalysis analyzeAudioSpectrum(
    const float* pcmData,
    size_t totalFloats,
    int32_t sampleRate,
    int32_t channels
) {
    SpectralAnalysis result;
    if (!pcmData || totalFloats == 0 || sampleRate <= 0 || channels <= 0) {
        return result;
    }

    const size_t totalSamples = totalFloats / static_cast<size_t>(channels);
    result.durationMs = (static_cast<float>(totalSamples) / static_cast<float>(sampleRate)) * 1000.0f;
    result.frameRateHz = static_cast<float>(sampleRate) / static_cast<float>(kHopSize);

    if (totalSamples < static_cast<size_t>(kFftSize)) {
        return result;
    }

    std::vector<float> mono(totalSamples);
    if (channels == 1) {
        std::copy(pcmData, pcmData + totalSamples, mono.begin());
    } else if (channels == 2) {
        for (size_t i = 0; i < totalSamples; ++i) {
            mono[i] = 0.5f * (pcmData[i * 2] + pcmData[i * 2 + 1]);
        }
    } else {
        const float invCh = 1.0f / static_cast<float>(channels);
        for (size_t i = 0; i < totalSamples; ++i) {
            float sum = 0.0f;
            const size_t base = i * static_cast<size_t>(channels);
            for (int c = 0; c < channels; ++c) {
                sum += pcmData[base + static_cast<size_t>(c)];
            }
            mono[i] = sum * invCh;
        }
    }

    const size_t numFrames = (totalSamples - kFftSize) / kHopSize + 1;
    result.lowBandFlux.resize(numFrames, 0.0f);
    result.midBandFlux.resize(numFrames, 0.0f);
    result.highBandFlux.resize(numFrames, 0.0f);
    result.rmsEnergy.resize(numFrames, 0.0f);

    const float binHz = static_cast<float>(sampleRate) / static_cast<float>(kFftSize);
    const int halfBins = kFftSize / 2;
    auto hzToBin = [&](float hz) -> int {
        return std::clamp(static_cast<int>(std::round(hz / binHz)), 1, halfBins - 1);
    };

    const int lowStart = hzToBin(40.0f);
    const int lowEnd = std::max(lowStart + 1, hzToBin(160.0f));
    const int midStart = hzToBin(250.0f);
    const int midEnd = std::max(midStart + 1, hzToBin(2500.0f));
    const int highStart = hzToBin(3000.0f);
    const int highEnd = std::max(highStart + 1, hzToBin(std::min(10000.0f, sampleRate * 0.45f)));

    const auto& tables = getFftTables();
    std::array<float, kFftSize> re{};
    std::array<float, kFftSize> im{};
    std::vector<float> prevLogMag(halfBins, 0.0f);
    std::vector<float> curLogMag(halfBins, 0.0f);

    const float invLowBins = 1.0f / static_cast<float>(lowEnd - lowStart + 1);
    const float invMidBins = 1.0f / static_cast<float>(midEnd - midStart + 1);
    const float invHighBins = 1.0f / static_cast<float>(highEnd - highStart + 1);

    for (size_t f = 0; f < numFrames; ++f) {
        const float* framePtr = mono.data() + f * kHopSize;
        float sqSum = 0.0f;
        for (int i = 0; i < kFftSize; ++i) {
            float s = framePtr[i];
            if (!std::isfinite(s)) s = 0.0f;
            sqSum += s * s;
            re[i] = s * tables.hann[i];
            im[i] = 0.0f;
        }
        result.rmsEnergy[f] = std::sqrt(sqSum / static_cast<float>(kFftSize));

        computeRadix2Fft(re.data(), im.data());

        for (int k = 1; k < halfBins; ++k) {
            const float mag = std::sqrt(re[k] * re[k] + im[k] * im[k]);
            curLogMag[k] = std::log1p(mag * 8.0f);
        }

        if (f > 0) {
            float lowF = 0.0f;
            for (int k = lowStart; k <= lowEnd; ++k) {
                const float d = curLogMag[k] - prevLogMag[k];
                if (d > 0.0f) lowF += d;
            }
            float midF = 0.0f;
            for (int k = midStart; k <= midEnd; ++k) {
                const float d = curLogMag[k] - prevLogMag[k];
                if (d > 0.0f) midF += d;
            }
            float highF = 0.0f;
            for (int k = highStart; k <= highEnd; ++k) {
                const float d = curLogMag[k] - prevLogMag[k];
                if (d > 0.0f) highF += d;
            }
            result.lowBandFlux[f] = lowF * invLowBins;
            result.midBandFlux[f] = midF * invMidBins;
            result.highBandFlux[f] = highF * invHighBins;
        }
        prevLogMag.swap(curLogMag);
    }

    normalizeFluxAdaptive(result.lowBandFlux, 10);
    normalizeFluxAdaptive(result.midBandFlux, 10);
    normalizeFluxAdaptive(result.highBandFlux, 10);

    std::vector<float> combinedOnset(numFrames, 0.0f);
    for (size_t f = 0; f < numFrames; ++f) {
        combinedOnset[f] = 1.40f * result.lowBandFlux[f] +
                           0.85f * result.midBandFlux[f] +
                           0.60f * result.highBandFlux[f];
    }

    detectTempoAndPhase(combinedOnset, result.frameRateHz, result.bpm, result.firstBeatMs);
    result.drops = detectDropSections(result.rmsEnergy, result.frameRateHz);
    return result;
}

BeatmapResult generateBeatmapFromAnalysis(
    const SpectralAnalysis& analysis,
    Difficulty difficulty
) {
    BeatmapResult out;
    out.bpm = analysis.bpm;
    out.firstBeatMs = analysis.firstBeatMs;
    out.durationMs = analysis.durationMs;
    out.drops = analysis.drops;

    if (analysis.durationMs < 2500.0f || analysis.lowBandFlux.empty()) {
        return out;
    }

    struct DiffParams {
        float minGlobalMs;
        float minSameHandMs;
        float wallMinGapMs;
        float wallDurationMs;
        float wallClearanceBeforeMs;
        float wallClearanceAfterMs;
        float slashRatio;
        int wallBarStride;
        int doubleBarStride;
        bool enableHalfBeats;
        float halfBeatThreshold;
        int normalBeatStride;
    };

    DiffParams cfg{};
    switch (difficulty) {
        case Difficulty::CHILL:
            cfg = {480.0f, 580.0f, 8500.0f, 600.0f, 380.0f, 340.0f, 0.22f, 8, 12, false, 99.0f, 2};
            break;
        case Difficulty::PRO:
            cfg = {265.0f, 390.0f, 6800.0f, 560.0f, 350.0f, 320.0f, 0.34f, 6, 4, false, 99.0f, 1};
            break;
        case Difficulty::EXPERT:
            cfg = {165.0f, 265.0f, 5400.0f, 520.0f, 330.0f, 300.0f, 0.44f, 5, 3, true, 0.24f, 1};
            break;
        case Difficulty::UPTEMPO_INSANE:
            cfg = {105.0f, 175.0f, 4400.0f, 480.0f, 320.0f, 280.0f, 0.52f, 4, 2, true, 0.08f, 1};
            break;
    }

    const float bpm = std::clamp(analysis.bpm, 75.0f, 235.0f);
    const float beatMs = 60000.0f / bpm;
    const float startLeadMs = 1600.0f;
    const float endMarginMs = 1100.0f;
    const float playableEndMs = analysis.durationMs - endMarginMs;
    if (playableEndMs <= startLeadMs) return out;

    auto sampleBand = [&](const std::vector<float>& vec, float timeMs) -> float {
        const float frame = (timeMs / 1000.0f) * analysis.frameRateHz;
        const int idx = std::clamp(static_cast<int>(std::round(frame)), 0, static_cast<int>(vec.size()) - 1);
        float best = vec[idx];
        if (idx > 0) best = std::max(best, vec[idx - 1]);
        if (idx + 1 < static_cast<int>(vec.size())) best = std::max(best, vec[idx + 1]);
        return best;
    };

    float gridStartMs = analysis.firstBeatMs;
    while (gridStartMs < startLeadMs) {
        gridStartMs += beatMs;
    }

    // Step 1: Place musical Dodge Walls on bar boundaries with guaranteed safety buffers
    std::vector<BeatTarget> walls;
    std::vector<WallInterval> wallZones;
    float lastWallEndMs = -10000.0f;
    int wallCycle = 0;

    int barCount = 0;
    for (float barMs = gridStartMs + beatMs * 4.0f; barMs < playableEndMs - 1400.0f; barMs += beatMs * 4.0f) {
        ++barCount;
        if ((barCount % cfg.wallBarStride) != 0) continue;
        if (barMs - lastWallEndMs < cfg.wallMinGapMs) continue;

        TargetType wallType = TargetType::DODGE_DUCK;
        float wallNormX = 0.50f;
        float wallNormY = 0.28f;
        if (difficulty == Difficulty::CHILL) {
            wallType = (wallCycle % 3 == 2) ? TargetType::DODGE_SLIP_LEFT : TargetType::DODGE_DUCK;
            if (wallType == TargetType::DODGE_SLIP_LEFT) {
                wallNormX = 0.68f;
                wallNormY = 0.45f;
            }
        } else {
            switch (wallCycle % 3) {
                case 0: wallType = TargetType::DODGE_DUCK; wallNormX = 0.50f; wallNormY = 0.28f; break;
                case 1: wallType = TargetType::DODGE_SLIP_LEFT; wallNormX = 0.68f; wallNormY = 0.45f; break;
                default: wallType = TargetType::DODGE_SLIP_RIGHT; wallNormX = 0.32f; wallNormY = 0.45f; break;
            }
        }
        ++wallCycle;

        BeatTarget w;
        w.timeMs = barMs;
        w.type = wallType;
        w.hand = TargetHand::BODY;
        w.normX = wallNormX;
        w.normY = wallNormY;
        w.durationMs = cfg.wallDurationMs;
        w.intensity = isInDropSection(barMs, analysis.drops) ? 0.95f : 0.72f;
        walls.push_back(w);
        wallZones.push_back(WallInterval{
            barMs - cfg.wallClearanceBeforeMs,
            barMs + cfg.wallDurationMs + cfg.wallClearanceAfterMs
        });
        lastWallEndMs = barMs + cfg.wallDurationMs;
    }

    if (walls.empty() && analysis.durationMs >= 12000.0f) {
        float midMs = std::clamp(analysis.durationMs * 0.5f, startLeadMs + 2500.0f, playableEndMs - 1500.0f);
        BeatTarget w;
        w.timeMs = midMs;
        w.type = TargetType::DODGE_DUCK;
        w.hand = TargetHand::BODY;
        w.normX = 0.50f;
        w.normY = 0.28f;
        w.durationMs = cfg.wallDurationMs;
        w.intensity = 0.85f;
        walls.push_back(w);
        wallZones.push_back(WallInterval{
            midMs - cfg.wallClearanceBeforeMs,
            midMs + cfg.wallDurationMs + cfg.wallClearanceAfterMs
        });
    }

    // Step 2: Generate rhythm-boxing strikes on musical beats + half-beat accents
    std::vector<BeatTarget> strikes;
    float lastAnyMs = -10000.0f;
    float lastLeftMs = -10000.0f;
    float lastRightMs = -10000.0f;
    bool nextLeft = true;
    int strikeCounter = 0;

    const int subDiv = cfg.enableHalfBeats ? 2 : 1;
    const float stepMs = beatMs / static_cast<float>(subDiv);

    // Pre-identify scheduled DOUBLE_STRIKE bar beats so we leave a crisp physical wind-up before them
    auto isScheduledDoubleBar = [&](int barIdx, float barTimeMs) -> bool {
        if (barIdx < 1 || (barIdx % cfg.doubleBarStride) != 0) return false;
        if (overlapsAnyWall(barTimeMs, wallZones)) return false;
        if (difficulty == Difficulty::CHILL) return isInDropSection(barTimeMs, analysis.drops);
        return isInDropSection(barTimeMs, analysis.drops) || ((barIdx % (cfg.doubleBarStride * 2)) == 0);
    };

    int stepIdx = 0;
    for (float t = gridStartMs; t <= playableEndMs; t += stepMs, ++stepIdx) {
        const bool isMainBeat = (stepIdx % subDiv) == 0;
        const int beatIndex = stepIdx / subDiv;
        const int barIdx = beatIndex / 4;
        const int beatInBar = beatIndex % 4;
        const bool inDrop = isInDropSection(t, analysis.drops);

        if (overlapsAnyWall(t, wallZones)) {
            continue;
        }

        // Brief physical wind-up right before an upcoming DOUBLE_STRIKE downbeat so both fists are cocked!
        const int nextBarIdx = barIdx + 1;
        const float nextBarMs = gridStartMs + static_cast<float>(nextBarIdx * 4) * beatMs;
        if (nextBarMs <= playableEndMs && isScheduledDoubleBar(nextBarIdx, nextBarMs)) {
            if ((nextBarMs - t) < cfg.minSameHandMs + 50.0f && (nextBarMs - t) > 1.0f) {
                continue;
            }
        }

        if (isMainBeat && cfg.normalBeatStride > 1 && !inDrop && (beatIndex % cfg.normalBeatStride) != 0) {
            continue;
        }

        const float low = sampleBand(analysis.lowBandFlux, t);
        const float mid = sampleBand(analysis.midBandFlux, t);
        const float high = sampleBand(analysis.highBandFlux, t);
        const float rms = sampleBand(analysis.rmsEnergy, t);
        const float combined = 0.50f * low + 0.30f * mid + 0.20f * high;

        if (rms < 0.004f && combined < 0.05f) {
            continue;
        }

        if (!isMainBeat) {
            float needed = inDrop ? (cfg.halfBeatThreshold * 0.65f) : cfg.halfBeatThreshold;
            if (difficulty == Difficulty::EXPERT && !inDrop && (beatIndex % 2 == 1)) {
                needed *= 1.45f;
            }
            const bool forceDropFlurry =
                (difficulty == Difficulty::UPTEMPO_INSANE && inDrop) ||
                (difficulty == Difficulty::EXPERT && inDrop && (beatIndex % 2 == 0));
            if (combined < needed && !forceDropFlurry) {
                continue;
            }
        } else if (difficulty == Difficulty::CHILL && inDrop && (beatIndex % 3 == 2) && combined < 0.55f) {
            continue;
        } else if (difficulty == Difficulty::PRO && !inDrop && (beatIndex % 4 == 3) && combined < 0.35f) {
            continue;
        }

        if (t - lastAnyMs < cfg.minGlobalMs) {
            continue;
        }

        float snappedT = t;
        {
            float bestLocal = combined;
            for (float dt : {-18.0f, -9.0f, 9.0f, 18.0f}) {
                const float candT = t + dt;
                if (candT < startLeadMs || candT > playableEndMs) continue;
                if (overlapsAnyWall(candT, wallZones)) continue;
                if (candT - lastAnyMs < cfg.minGlobalMs) continue;
                const float c = 0.55f * sampleBand(analysis.lowBandFlux, candT) +
                                0.30f * sampleBand(analysis.midBandFlux, candT) +
                                0.15f * sampleBand(analysis.highBandFlux, candT);
                if (c > bestLocal * 1.18f) {
                    bestLocal = c;
                    snappedT = candT;
                }
            }
        }

        const bool canLeft = (snappedT - lastLeftMs) >= cfg.minSameHandMs;
        const bool canRight = (snappedT - lastRightMs) >= cfg.minSameHandMs;
        if (!canLeft && !canRight) {
            continue;
        }

        const bool wantDouble = isMainBeat && beatInBar == 0 &&
                                canLeft && canRight &&
                                isScheduledDoubleBar(barIdx, t);

        BeatTarget target;
        target.timeMs = snappedT;
        target.durationMs = 0.0f;
        target.intensity = std::clamp(0.45f + combined * 0.45f + (inDrop ? 0.15f : 0.0f), 0.35f, 1.0f);

        if (wantDouble) {
            target.type = TargetType::DOUBLE_STRIKE;
            target.hand = TargetHand::BOTH;
            target.normX = 0.50f;
            target.normY = 0.38f;
            lastLeftMs = snappedT;
            lastRightMs = snappedT;
            lastAnyMs = snappedT;
            strikes.push_back(target);
            ++strikeCounter;
            continue;
        }

        bool useLeft = nextLeft;
        if (useLeft && !canLeft) useLeft = false;
        if (!useLeft && !canRight) useLeft = true;

        const int slashMod = std::max(2, static_cast<int>(std::round(1.0f / cfg.slashRatio)));
        const bool makeSlash = ((strikeCounter % slashMod) == (slashMod - 1)) ||
                               (high > 0.72f && mid > 0.45f && (strikeCounter % 2 == 1));

        if (makeSlash) {
            if (high >= mid && high >= low * 0.85f) {
                target.type = ((strikeCounter % 2) == 0) ? TargetType::SLASH_UP : TargetType::SLASH_OUT;
            } else if (mid >= low) {
                target.type = TargetType::SLASH_IN;
            } else {
                target.type = ((strikeCounter % 3) == 0) ? TargetType::SLASH_DOWN : TargetType::SLASH_UP;
            }
            target.hand = useLeft ? TargetHand::LEFT : TargetHand::RIGHT;
            if (useLeft) {
                target.normX = (target.type == TargetType::SLASH_OUT) ? 0.30f : 0.35f;
            } else {
                target.normX = (target.type == TargetType::SLASH_OUT) ? 0.70f : 0.65f;
            }
            switch (target.type) {
                case TargetType::SLASH_UP:   target.normY = 0.48f; break;
                case TargetType::SLASH_DOWN: target.normY = 0.32f; break;
                case TargetType::SLASH_IN:   target.normY = 0.40f; break;
                case TargetType::SLASH_OUT:  target.normY = 0.38f; break;
                default:                     target.normY = 0.40f; break;
            }
        } else {
            target.type = useLeft ? TargetType::JAB_LEFT : TargetType::JAB_RIGHT;
            target.hand = useLeft ? TargetHand::LEFT : TargetHand::RIGHT;
            const float laneJitter = ((strikeCounter / 2) % 2 == 0) ? 0.0f : 0.05f;
            target.normX = useLeft ? (0.31f + laneJitter) : (0.69f - laneJitter);
            target.normY = ((strikeCounter % 4) == 0) ? 0.33f : 0.43f;
        }

        if (useLeft) {
            lastLeftMs = snappedT;
            nextLeft = false;
        } else {
            lastRightMs = snappedT;
            nextLeft = true;
        }
        lastAnyMs = snappedT;
        strikes.push_back(target);
        ++strikeCounter;
    }

    out.targets.reserve(strikes.size() + walls.size());
    out.targets.insert(out.targets.end(), strikes.begin(), strikes.end());
    out.targets.insert(out.targets.end(), walls.begin(), walls.end());

    std::sort(out.targets.begin(), out.targets.end(), [](const BeatTarget& a, const BeatTarget& b) {
        if (std::abs(a.timeMs - b.timeMs) > 0.01f) return a.timeMs < b.timeMs;
        return static_cast<int>(a.type) < static_cast<int>(b.type);
    });

    for (size_t i = 0; i < out.targets.size(); ++i) {
        out.targets[i].id = static_cast<int32_t>(i + 1);
    }

    return out;
}

BeatmapResult generateBeatmap(
    const float* pcmData,
    size_t totalFloats,
    int32_t sampleRate,
    int32_t channels,
    Difficulty difficulty
) {
    SpectralAnalysis analysis = analyzeAudioSpectrum(pcmData, totalFloats, sampleRate, channels);
    return generateBeatmapFromAnalysis(analysis, difficulty);
}

} // namespace strikecam
