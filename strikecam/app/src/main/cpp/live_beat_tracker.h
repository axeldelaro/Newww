#pragma once

#include <cstdint>
#include <deque>
#include <vector>

namespace strikecam {

struct LiveBeatEvent {
    bool triggered = false;
    bool isDrop = false;
    int32_t beatIndex = 0;
    float strength = 0.0f;
    float estimatedBpm = 140.0f;
    double beatTimeMs = 0.0;
};

/**
 * Real-time dual-mode onset/beat tracker for Live Streaming Sync Mode
 * (Spotify / Deezer / SoundCloud background playback).
 *
 * Supports:
 * 1. Real-time ambient PCM energy flux onset detection with adaptive rolling threshold.
 * 2. Exact BPM grid generator with Tap-Sync tempo+phase locking and +-50ms phase nudge.
 */
class LiveBeatTracker {
public:
    explicit LiveBeatTracker(int32_t sampleRate = 44100, float initialBpm = 140.0f);

    void reset(double nowMs = 0.0);

    // Grid & Tap-Sync control
    void setBpm(float bpm);
    float getBpm() const { return bpm_; }
    void setPhaseOffsetMs(double offsetMs);
    void nudgePhaseMs(double deltaMs);
    double getPhaseOffsetMs() const { return phaseOffsetMs_; }

    /**
     * Registers a manual user tap to synchronize tempo and beat phase in real time.
     * Returns updated BPM estimate.
     */
    float registerTap(double nowMs);

    /**
     * Feeds a mono Float32 PCM buffer from live microphone/audio capture.
     * Detects low/broadband energy transients, updates live BPM estimate, and returns onset event.
     */
    LiveBeatEvent processAudioChunk(const float* monoPcm, size_t numSamples, double nowMs);

    /**
     * Polls the phase-locked BPM grid to spawn upcoming targets ahead of time.
     * `leadTimeMs` allows scheduling targets so they arrive on-beat after travel time.
     */
    LiveBeatEvent pollGridBeat(double nowMs, double leadTimeMs = 0.0);

    float getCurrentEnergy() const { return lastEnergy_; }

private:
    int32_t sampleRate_;
    float bpm_;
    double anchorTimeMs_ = 0.0;
    double phaseOffsetMs_ = 0.0;
    int32_t lastGridIndex_ = -1;

    // Tap-sync rolling history
    std::deque<double> tapTimestampsMs_;

    // Ambient onset detection state
    float lpState_ = 0.0f;
    float prevLowEnergy_ = 0.0f;
    float lastEnergy_ = 0.0f;
    std::deque<float> fluxHistory_;
    std::deque<float> energyHistory_;
    double lastOnsetMs_ = -1000.0;
    std::deque<double> onsetIntervalsMs_;
    int32_t onsetBeatCounter_ = 0;
};

} // namespace strikecam
