#include "live_beat_tracker.h"

#include <algorithm>
#include <cmath>
#include <numeric>

namespace strikecam {

namespace {
constexpr float kTwoPi = 6.28318530717958647692f;
} // namespace

LiveBeatTracker::LiveBeatTracker(int32_t sampleRate, float initialBpm)
    : sampleRate_(std::max(8000, sampleRate)),
      bpm_(std::clamp(initialBpm, 60.0f, 240.0f)) {}

void LiveBeatTracker::reset(double nowMs) {
    anchorTimeMs_ = nowMs;
    phaseOffsetMs_ = 0.0;
    lastGridIndex_ = -1;
    tapTimestampsMs_.clear();
    lpState_ = 0.0f;
    prevLowEnergy_ = 0.0f;
    lastEnergy_ = 0.0f;
    fluxHistory_.clear();
    energyHistory_.clear();
    lastOnsetMs_ = nowMs - 1000.0;
    onsetIntervalsMs_.clear();
    onsetBeatCounter_ = 0;
}

void LiveBeatTracker::setBpm(float bpm) {
    bpm_ = std::clamp(bpm, 60.0f, 240.0f);
}

void LiveBeatTracker::setPhaseOffsetMs(double offsetMs) {
    phaseOffsetMs_ = std::clamp(offsetMs, -2000.0, 2000.0);
}

void LiveBeatTracker::nudgePhaseMs(double deltaMs) {
    phaseOffsetMs_ = std::clamp(phaseOffsetMs_ + deltaMs, -2000.0, 2000.0);
}

float LiveBeatTracker::registerTap(double nowMs) {
    // Discard stale tap chain after > 2.2s pause
    if (!tapTimestampsMs_.empty() && (nowMs - tapTimestampsMs_.back()) > 2200.0) {
        tapTimestampsMs_.clear();
    }

    tapTimestampsMs_.push_back(nowMs);
    while (tapTimestampsMs_.size() > 8) {
        tapTimestampsMs_.pop_front();
    }

    // Lock beat phase directly to user's latest tap
    anchorTimeMs_ = nowMs;
    phaseOffsetMs_ = 0.0;
    lastGridIndex_ = 0;

    if (tapTimestampsMs_.size() >= 2) {
        std::vector<double> intervals;
        intervals.reserve(tapTimestampsMs_.size() - 1);
        for (size_t i = 1; i < tapTimestampsMs_.size(); ++i) {
            double dt = tapTimestampsMs_[i] - tapTimestampsMs_[i - 1];
            if (dt >= 230.0 && dt <= 1100.0) {
                intervals.push_back(dt);
            }
        }
        if (!intervals.empty()) {
            std::sort(intervals.begin(), intervals.end());
            double medianDt = intervals[intervals.size() / 2];
            double avgDt = std::accumulate(intervals.begin(), intervals.end(), 0.0) /
                           static_cast<double>(intervals.size());
            double blendedDt = 0.65 * medianDt + 0.35 * avgDt;
            bpm_ = std::clamp(static_cast<float>(60000.0 / blendedDt), 60.0f, 240.0f);
        }
    }

    return bpm_;
}

LiveBeatEvent LiveBeatTracker::processAudioChunk(const float* monoPcm, size_t numSamples, double nowMs) {
    LiveBeatEvent ev;
    ev.estimatedBpm = bpm_;
    ev.beatTimeMs = nowMs;

    if (!monoPcm || numSamples == 0) {
        return ev;
    }

    // Extract kick/bass low-pass energy (~180 Hz cutoff) and broadband RMS
    const float alpha = std::clamp(
        kTwoPi * 180.0f / (kTwoPi * 180.0f + static_cast<float>(sampleRate_)),
        0.005f, 0.5f
    );

    double lowSq = 0.0;
    double fullSq = 0.0;
    for (size_t i = 0; i < numSamples; ++i) {
        float x = std::isfinite(monoPcm[i]) ? monoPcm[i] : 0.0f;
        lpState_ += alpha * (x - lpState_);
        lowSq += static_cast<double>(lpState_ * lpState_);
        fullSq += static_cast<double>(x * x);
    }

    const float lowRms = static_cast<float>(std::sqrt(lowSq / static_cast<double>(numSamples)));
    const float fullRms = static_cast<float>(std::sqrt(fullSq / static_cast<double>(numSamples)));
    const float weightedEnergy = lowRms * 1.45f + fullRms * 0.55f;
    lastEnergy_ = fullRms;

    const float flux = std::max(0.0f, weightedEnergy - prevLowEnergy_ * 0.88f);
    prevLowEnergy_ = weightedEnergy;

    fluxHistory_.push_back(flux);
    energyHistory_.push_back(fullRms);
    while (fluxHistory_.size() > 45) fluxHistory_.pop_front();
    while (energyHistory_.size() > 60) energyHistory_.pop_front();

    if (fluxHistory_.size() < 4) {
        return ev;
    }

    float meanFlux = std::accumulate(fluxHistory_.begin(), fluxHistory_.end(), 0.0f) /
                     static_cast<float>(fluxHistory_.size());
    float meanEnergy = std::accumulate(energyHistory_.begin(), energyHistory_.end(), 0.0f) /
                       static_cast<float>(energyHistory_.size());

    float variance = 0.0f;
    for (float f : fluxHistory_) {
        float d = f - meanFlux;
        variance += d * d;
    }
    float stdFlux = std::sqrt(variance / static_cast<float>(fluxHistory_.size()));

    // Minimum refractory period scales with current BPM (prevent double triggers on kick tails)
    const double minRefractoryMs = std::clamp(60000.0 / static_cast<double>(bpm_) * 0.55, 190.0, 420.0);
    const float threshold = std::max(0.018f, meanFlux + 1.15f * stdFlux);

    if (flux >= threshold && fullRms >= 0.015f && (nowMs - lastOnsetMs_) >= minRefractoryMs) {
        const double dt = nowMs - lastOnsetMs_;
        lastOnsetMs_ = nowMs;

        if (dt >= 230.0 && dt <= 1050.0) {
            onsetIntervalsMs_.push_back(dt);
            while (onsetIntervalsMs_.size() > 10) onsetIntervalsMs_.pop_front();
            if (onsetIntervalsMs_.size() >= 3) {
                std::vector<double> sorted(onsetIntervalsMs_.begin(), onsetIntervalsMs_.end());
                std::sort(sorted.begin(), sorted.end());
                double med = sorted[sorted.size() / 2];
                float instBpm = std::clamp(static_cast<float>(60000.0 / med), 65.0f, 235.0f);
                // Smooth tempo tracking toward detected pulse
                bpm_ = bpm_ * 0.70f + instBpm * 0.30f;
            }
        }

        ev.triggered = true;
        ev.beatIndex = onsetBeatCounter_++;
        ev.strength = std::clamp(flux / std::max(threshold * 1.4f, 0.02f), 0.35f, 1.0f);
        ev.isDrop = fullRms > meanEnergy * 1.30f && ev.strength > 0.70f;
        ev.estimatedBpm = bpm_;
        ev.beatTimeMs = nowMs;
    }

    return ev;
}

LiveBeatEvent LiveBeatTracker::pollGridBeat(double nowMs, double leadTimeMs) {
    LiveBeatEvent ev;
    ev.estimatedBpm = bpm_;

    const double beatMs = 60000.0 / static_cast<double>(std::clamp(bpm_, 60.0f, 240.0f));
    const double effectiveNow = nowMs + leadTimeMs - (anchorTimeMs_ + phaseOffsetMs_);
    if (effectiveNow < 0.0) {
        return ev;
    }

    const int32_t gridIndex = static_cast<int32_t>(std::floor(effectiveNow / beatMs));
    if (gridIndex > lastGridIndex_) {
        lastGridIndex_ = gridIndex;
        ev.triggered = true;
        ev.beatIndex = gridIndex;
        ev.beatTimeMs = anchorTimeMs_ + phaseOffsetMs_ + static_cast<double>(gridIndex) * beatMs;
        ev.isDrop = (gridIndex % 16) == 0;
        ev.strength = ((gridIndex % 4) == 0) ? 1.0f : 0.72f;
        ev.estimatedBpm = bpm_;
    }

    return ev;
}

} // namespace strikecam
