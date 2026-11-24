#pragma once

#include <cstdint>
#include <vector>

namespace strikecam {

enum class Difficulty : int32_t {
    CHILL = 0,
    PRO = 1,
    EXPERT = 2,
    UPTEMPO_INSANE = 3
};

enum class TargetType : int32_t {
    JAB_LEFT = 0,
    JAB_RIGHT = 1,
    SLASH_UP = 2,
    SLASH_DOWN = 3,
    SLASH_IN = 4,
    SLASH_OUT = 5,
    DOUBLE_STRIKE = 6,
    DODGE_DUCK = 7,
    DODGE_SLIP_LEFT = 8,
    DODGE_SLIP_RIGHT = 9
};

enum class TargetHand : int32_t {
    LEFT = 0,
    RIGHT = 1,
    BOTH = 2,
    BODY = 3
};

struct BeatTarget {
    int32_t id = 0;
    float timeMs = 0.0f;
    TargetType type = TargetType::JAB_LEFT;
    TargetHand hand = TargetHand::LEFT;
    float normX = 0.5f;       // Normalized screen target lane [0.18..0.82]
    float normY = 0.42f;      // Normalized screen target elevation [0.24..0.58]
    float durationMs = 0.0f;  // 0 for strikes; 450..650ms for dodge walls
    float intensity = 0.7f;   // 0..1 musical accent intensity
};

struct DropSection {
    float startMs = 0.0f;
    float endMs = 0.0f;
    float peakEnergy = 0.0f;
};

struct SpectralAnalysis {
    float frameRateHz = 86.1328125f;
    float durationMs = 0.0f;
    float bpm = 140.0f;
    float firstBeatMs = 0.0f;
    std::vector<float> lowBandFlux;    // 40 - 160 Hz (Kick)
    std::vector<float> midBandFlux;    // 250 - 2500 Hz (Snare / Vocal)
    std::vector<float> highBandFlux;   // 3000 - 10000 Hz (Transient / Screech)
    std::vector<float> rmsEnergy;      // Per-frame broadband RMS
    std::vector<DropSection> drops;
};

struct BeatmapResult {
    float bpm = 140.0f;
    float firstBeatMs = 0.0f;
    float durationMs = 0.0f;
    std::vector<DropSection> drops;
    std::vector<BeatTarget> targets;
};

inline bool isDodgeTarget(TargetType type) {
    return type == TargetType::DODGE_DUCK ||
           type == TargetType::DODGE_SLIP_LEFT ||
           type == TargetType::DODGE_SLIP_RIGHT;
}

inline bool isSlashTarget(TargetType type) {
    return type == TargetType::SLASH_UP ||
           type == TargetType::SLASH_DOWN ||
           type == TargetType::SLASH_IN ||
           type == TargetType::SLASH_OUT;
}

/**
 * Analyzes mono or interleaved stereo Float32 PCM using an iterative radix-2 FFT
 * to extract 3-band spectral flux (Kick / Snare / High), exact BPM, and Drop sections.
 */
SpectralAnalysis analyzeAudioSpectrum(
    const float* pcmData,
    size_t totalFloats,
    int32_t sampleRate,
    int32_t channels
);

/**
 * Generates a choreographed, physically playable Rhythm-Boxing / Beat-Saber beatmap
 * from raw Float32 PCM audio for the requested difficulty level.
 */
BeatmapResult generateBeatmap(
    const float* pcmData,
    size_t totalFloats,
    int32_t sampleRate,
    int32_t channels,
    Difficulty difficulty
);

/**
 * Generates a beatmap from precomputed SpectralAnalysis (useful when switching difficulties).
 */
BeatmapResult generateBeatmapFromAnalysis(
    const SpectralAnalysis& analysis,
    Difficulty difficulty
);

} // namespace strikecam
