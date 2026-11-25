#pragma once

#include <string>
#include <vector>

namespace zaagforge {

enum class SectionType : int {
    INTRO = 0,
    VOCAL_BREAK = 1,
    BUILDUP = 2,
    FAKE_DROP = 3,
    DROP_1 = 4,
    MID_BREAK = 5,
    DROP_2 = 6
};

struct SectionMarker {
    float startFrac = 0.0f;
    float endFrac = 1.0f;
    SectionType type = SectionType::DROP_1;
    std::string label;
    int startBar = 0;
    int endBar = 0;
};

struct ArrangementOptions {
    int dropMode = 0;             // 0=AUTO_CHORUS, 1=DIRECT_DROP, 2=VOCALS_FIRST, 3=DOUBLE_DROP
    int buildupBars = 4;          // 4, 8, or 12 exact bars
    bool enableFakeDrop = true;   // occupies exact final 1 bar before DROP 1
    int drop1BarNudge = 0;        // -16..+16 bars nudge relative to detected chorus 1
    int drop2BarNudge = 0;        // -16..+16 bars nudge relative to detected chorus 2
    float customDrop1Frac = -1.0f; // >= 0: user tapped "POSER DROP 1 ICI" -> snaps to nearest bar
    float customDrop2Frac = -1.0f; // >= 0: user tapped "POSER DROP 2 ICI" -> snaps to nearest bar
    float bpmOverride = 0.0f;     // > 0 overrides detected source BPM
    float gridPhaseNudgeMs = 0.0f; // -250..+250 ms micro phase offset
    int gridBeatShift = 0;        // 0..3 shift bar downbeat by +N beats
};

struct AnalysisResult {
    float bpm = 128.0f;
    float bpmConfidence = 0.0f;
    float beatOffsetSec = 0.0f;   // Sub-beat phase lock in [0, beatSec)
    int downbeatBeatIndex = 0;    // 0..3 which beat in first measure is true Beat 1
    float downbeatSec = 0.0f;     // True Bar 1 Beat 1 timestamp = beatOffsetSec + downbeatBeatIndex * beatSec
    int totalBars = 8;
    int chorus1Bar = 4;           // Auto-detected Chorus 1 start bar (4-bar phrase aligned)
    int chorus2Bar = 8;           // Auto-detected Chorus 2 start bar
    float chorus1Sec = 0.0f;
    float chorus2Sec = 0.0f;
    int keyIndex = 5;             // 0=C .. 11=B
    bool isMinor = true;
    int rootMidi = 29;            // F1 = 43.65 Hz
    float rootHz = 43.65f;
    std::string keyName = "Fm";
    float durationSec = 0.0f;
    float avgRmsDb = -14.0f;
    std::vector<SectionMarker> sections;
    std::vector<float> waveformPeaks;
    std::vector<float> barRms;
    std::vector<float> barVocalEnergy;
    std::vector<float> barNovelty;
};

class MusicAnalyzer {
public:
    /**
     * Analyzes interleaved stereo 44.1 kHz (or arbitrary sr) Float32 PCM audio:
     * - Multi-band spectral flux BPM + <4ms micro-phase + 4-beat bar downbeat detection
     * - Krumhansl-Schmuckler tonal key + club sub-bass root lock
     * - Structural chorus / vocal-hook novelty detection & bar-quantized arrangement!
     */
    static AnalysisResult analyze(
        const float* stereoPcm,
        size_t totalFrames,
        float sampleRate = 44100.0f,
        size_t waveformBins = 240
    );

    /**
     * Rebuilds bar-quantized arrangement sections cleanly using detected chorus peaks
     * and user producer overrides (dropMode, buildupBars, drop1/2 bar nudge or custom playhead drop!).
     */
    static std::vector<SectionMarker> buildArrangement(
        const AnalysisResult& analysis,
        const ArrangementOptions& options = ArrangementOptions{}
    );

    /**
     * Extracts normalized peak envelope (0..1) from interleaved stereo PCM.
     */
    static std::vector<float> extractWaveformPeaks(
        const float* stereoPcm,
        size_t totalFrames,
        size_t numBins = 240
    );

    /**
     * Generates a crisp built-in musical starter track (stereo 44.1 kHz Float32 PCM)
     * with genuine Intro -> Verse -> Chorus 1 -> Bridge -> Chorus 2 dynamics!
     */
    static std::vector<float> generateDemoTrack(int style, float durationSec = 32.0f);

    static const char* sectionTypeName(SectionType t);
};

}  // namespace zaagforge
