#pragma once

#include "hardcore_kicks.h"
#include "music_analyzer.h"

#include <string>
#include <vector>

namespace zaagforge {

struct RemixConfig {
    float targetBpm = 210.0f;           // 150..240 BPM
    KickType drop1Kick = KickType::PIEP_UOK;
    KickType drop2Kick = KickType::ZAAG_PIEP_HYBRID;
    float drive = 0.88f;                // 0..1
    float zaagAggression = 0.88f;       // 0..1
    float originalMix = 0.72f;          // 0..1 original track vocal/melody level in drops
    float vocalPitchSemitones = 0.0f;   // -5..+7 semitones
    float stutterAmount = 0.65f;        // 0..1 rhythmic 1/8 & 1/16 vocal stutter chops
    bool enableFakeDrop = true;
    bool enableScreeches = true;
    bool enablePitchKickMelody = true;
    bool enableTripletRolls = true;
    int kickSwitchBars = 4;             // 4 or 8 bars
};

struct RemixOutput {
    std::vector<float> stereoPcm;       // Interleaved L/R 44.1 kHz Float32
    std::vector<float> waveformPeaks;   // Normalized peaks for DJ waveform UI
    std::vector<SectionMarker> sections;
    float sourceBpm = 128.0f;
    float effectiveTargetBpm = 210.0f;
    float tempoStretchRatio = 1.0f;
    float rootHz = 55.0f;
    std::string keyName = "A";
    float durationSec = 0.0f;
    float peakDb = -0.2f;
    float rmsDb = -6.5f;
};

class RemixEngine {
public:
    /**
     * Computes optimal musical speed ratio (smart half/double-time sync)
     * so 1 original beat maps cleanly to 1, 2, or 0.5 hardcore beats.
     * Returns pair {speedRatio, gridMultiplier}.
     */
    static std::pair<float, float> computeMusicalTempoSync(float sourceBpm, float targetBpm);

    /**
     * Pure C++20 WSOLA time-stretcher (stereo interleaved 44.1 kHz).
     * timeScale > 1.0 stretches (slower), timeScale < 1.0 compresses (faster).
     */
    static std::vector<float> wsolaTimeStretch(
        const float* stereoIn,
        size_t inFrames,
        float timeScale
    );

    /**
     * Combined WSOLA time-stretch + independent pitch shift (semitones in -5..+7).
     * Preserves exact target duration = inFrames * timeScale!
     */
    static std::vector<float> timeStretchAndPitchShift(
        const float* stereoIn,
        size_t inFrames,
        float timeScale,
        float pitchSemitones
    );

    /**
     * Full automated Hardcore / Uptempo / Piep / Zaag / Krach / Frenchcore remix renderer.
     */
    static RemixOutput renderRemix(
        const float* stereoPcm,
        size_t totalFrames,
        const AnalysisResult& analysis,
        const RemixConfig& config
    );
};

}  // namespace zaagforge
