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
};

struct AnalysisResult {
    float bpm = 128.0f;
    float bpmConfidence = 0.0f;
    float beatOffsetSec = 0.0f;
    int keyIndex = 5;       // 0=C .. 11=B
    bool isMinor = true;
    int rootMidi = 29;      // F1 = 43.65 Hz
    float rootHz = 43.65f;
    std::string keyName = "Fm";
    float durationSec = 0.0f;
    float avgRmsDb = -14.0f;
    std::vector<SectionMarker> sections;
    std::vector<float> waveformPeaks;
};

class MusicAnalyzer {
public:
    /**
     * Analyzes interleaved stereo 44.1 kHz (or arbitrary sr) Float32 PCM audio.
     */
    static AnalysisResult analyze(
        const float* stereoPcm,
        size_t totalFrames,
        float sampleRate = 44100.0f,
        size_t waveformBins = 240
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
     * so users can test every UOK/Krach/Zaag preset immediately.
     * style = 0: 128 BPM F-minor Vocal Synthpop Hook
     * style = 1: 140 BPM D-minor Dark Trap Anthem
     */
    static std::vector<float> generateDemoTrack(int style, float durationSec = 32.0f);

    static const char* sectionTypeName(SectionType t);
};

}  // namespace zaagforge
