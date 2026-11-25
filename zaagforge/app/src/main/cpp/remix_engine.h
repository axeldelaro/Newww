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
    float drive = 0.88f;                // 0..1 upper kick distortion character
    float zaagAggression = 0.88f;       // 0..1 piep/zaag/krach bite
    float bassSaturation = 0.35f;       // 0..1 dedicated sub/bass saturation (clean by default!)
    float originalMix = 0.80f;          // 0..1 original track vocal/melody level in drops
    float vocalClarity = 0.78f;         // 0..1 Mid/Side vocal focus & de-mud presence boost
    float vocalSidechain = 0.36f;       // 0..1 gentle sidechain ducking on vocal band (> 850 Hz)
    float kickLevel = 0.84f;            // 0.4..1.2 kick bus level relative to song
    float bassCutHz = 200.0f;           // 110..340 Hz surgical LR4 crossover for original track
    int masterMode = 0;                 // 0=STUDIO_CLEAN, 1=CLUB_PUNCH, 2=FESTIVAL_HARD
    float vocalPitchSemitones = 0.0f;   // -5..+7 semitones
    float stutterAmount = 0.55f;        // 0..1 rhythmic 1/8 & 1/16 vocal stutter chops
    bool enableFakeDrop = true;
    bool enableScreeches = true;
    bool enablePitchKickMelody = true;
    bool enableTripletRolls = true;
    int kickSwitchBars = 4;             // 4, 8, or 16 bars

    // Surgical Timing & Structural Drop Alignment Controls
    float sourceBpmOverride = 0.0f;     // 0 = auto detected BPM; > 0 = manual / ÷2 / ×2 override
    float gridPhaseNudgeMs = 0.0f;      // -250..+250 ms micro phase offset
    int gridBeatShift = 0;              // 0..3 shift downbeat by +N beats
    int dropMode = 0;                   // 0=AUTO_CHORUS, 1=DIRECT_DROP, 2=VOCALS_FIRST, 3=DOUBLE_DROP
    int buildupBars = 4;                // 4, 8, or 12 exact bars
    int drop1BarNudge = 0;              // -16..+16 bars nudge
    int drop2BarNudge = 0;              // -16..+16 bars nudge
    float customDrop1Frac = -1.0f;      // >= 0: user tapped "POSER DROP 1 ICI"
    float customDrop2Frac = -1.0f;      // >= 0: user tapped "POSER DROP 2 ICI"
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
     * High-quality C++20 WSOLA time-stretcher (stereo interleaved 44.1 kHz, 2048-pt Hann).
     */
    static std::vector<float> wsolaTimeStretch(
        const float* stereoIn,
        size_t inFrames,
        float timeScale
    );

    /**
     * Combined WSOLA time-stretch + independent pitch shift (semitones in -5..+7).
     */
    static std::vector<float> timeStretchAndPitchShift(
        const float* stereoIn,
        size_t inFrames,
        float timeScale,
        float pitchSemitones
    );

    /**
     * Full Studio-Grade Hardcore / Uptempo / Piep / Zaag / Krach / Frenchcore remix renderer
     * with split-bus clean vocals, controlled sub-bass, exact downbeat-locked section cuts,
     * and transparent lookahead mastering!
     */
    static RemixOutput renderRemix(
        const float* stereoPcm,
        size_t totalFrames,
        const AnalysisResult& analysis,
        const RemixConfig& config
    );
};

}  // namespace zaagforge
