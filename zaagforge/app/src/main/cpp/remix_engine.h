#pragma once

#include "hardcore_kicks.h"
#include "music_analyzer.h"

#include <string>
#include <vector>

namespace zaagforge {

enum class TempoSyncMode : int {
    NEVER_SLOW = 0,          // Default: NEVER slows down the song (speed >= 1.00x always!)
    ORIGINAL_SPEED_LOCK = 1, // Exact 1.00x original speed (0 WSOLA stretch -> ultra-fast & pristine!)
    FORCE_TARGET_BPM = 2     // Strictly speeds up to targetBpm (still >= 1.00x!)
};

enum class RemixGenreTheme : int {
    UPTEMPO_ZAAG = 0, // Unicorn On Ketamine / Noiseflow / Dimitri K / Krowdexx
    TECHNO_CLUB = 1,  // Charlotte de Witte / Amelie Lens / Adam Beyer (True Normal Techno)
    HARD_TECHNO = 2,  // Sara Landry / Nico Moreno / I Hate Models / 999999999 / Klangkuenstler
    FRENCHCORE = 3,   // Sefa / Dr. Peacock / Billx (True Euphoric Frenchcore)
    GABBER = 4,       // Angerfist / Neophyte / Tha Playah (True 909 Millennium Gabber)
    RAWSTYLE = 5      // Dual Damage / Warface / Kruelty
};

struct RemixConfig {
    float targetBpm = 210.0f;           // 126..240 BPM (now supports 132-145 Normal Techno too!)
    RemixGenreTheme genreTheme = RemixGenreTheme::UPTEMPO_ZAAG;
    TempoSyncMode tempoSyncMode = TempoSyncMode::NEVER_SLOW;
    KickType drop1Kick = KickType::PIEP_UOK;
    KickType drop2Kick = KickType::ZAAG_PIEP_HYBRID;
    float drive = 0.86f;                // 0..1 upper kick distortion character
    float zaagAggression = 0.88f;       // 0..1 piep/zaag/krach/acid bite
    float bassSaturation = 0.34f;       // 0..1 dedicated sub/bass saturation (clean by default!)
    float originalMix = 0.86f;          // 0..1 original track vocal/melody level in drops
    float vocalClarity = 0.84f;         // 0..1 Mid/Side vocal focus & de-mud presence boost
    float epicVocalBoost = 0.82f;       // 0..1 dynamic vocal auto-leveller + kick vocal-pocket carving!
    float vocalSidechain = 0.24f;       // 0..1 gentle sidechain ducking on vocal band (> 650 Hz)
    float kickLevel = 0.80f;            // 0.4..1.2 kick bus level relative to song
    float bassCutHz = 195.0f;           // 110..340 Hz surgical LR4 crossover for original track
    float synthLayerAmount = 0.55f;     // 0..1 Euphoric Frenchcore chords / Acid 303 / Gabber Hoover stabs
    float percGrooveAmount = 0.65f;     // 0..1 909 Claps, 16th Hats, Rides groove level
    int masterMode = 0;                 // 0=STUDIO_CLEAN, 1=CLUB_PUNCH, 2=FESTIVAL_HARD
    float vocalPitchSemitones = 0.0f;   // -5..+7 semitones
    float stutterAmount = 0.45f;        // 0..1 rhythmic 1/8 & 1/16 vocal stutter chops
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

struct TempoSyncResult {
    float speedRatio = 1.0f;            // ALWAYS >= 1.00f (never slows down user's song!)
    float gridMultiplier = 1.0f;        // 0.5 (half-time vocals over 2x kicks) or 1.0
    float effectiveTargetBpm = 140.0f;  // Exact kick BPM locked to song rhythm
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
    float rmsDb = -8.5f;
    float renderTimeMs = 0.0f;
};

class RemixEngine {
public:
    /**
     * Never-slow musical tempo synchronization:
     * Mathematically guarantees speedRatio >= 1.00f so user tracks are NEVER slowed down!
     */
    static TempoSyncResult computeNeverSlowTempoSync(
        float sourceBpm,
        float targetBpm,
        TempoSyncMode mode = TempoSyncMode::NEVER_SLOW
    );

    /** Backward-compatible pair wrapper (always >= 1.00x!). */
    static std::pair<float, float> computeMusicalTempoSync(float sourceBpm, float targetBpm);

    /**
     * High-speed multi-threaded C++20 WSOLA time-stretcher (stereo 44.1 kHz).
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
     * Full Studio-Grade Remix Renderer with optional pre-warped cache pointer
     * (when preWarpedPcm != nullptr, skips WSOLA completely for < 0.25s instant re-renders!).
     */
    static RemixOutput renderRemix(
        const float* stereoPcm,
        size_t totalFrames,
        const AnalysisResult& analysis,
        const RemixConfig& config,
        const float* preWarpedPcm = nullptr,
        size_t preWarpedFrames = 0
    );
};

}  // namespace zaagforge
