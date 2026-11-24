#pragma once

#include <cstdint>
#include <vector>

namespace zaagforge {

enum class KickType : int {
    PIEP_UOK = 0,
    KRACH_NOISEFLOW = 1,
    ZAAG_CHAINSAW = 2,
    UPTEMPO_BOUNCE = 3,
    ZAAG_PIEP_HYBRID = 4,
    RAWSTYLE_GATED = 5,
    FRENCHCORE_MELODIC = 6,
    SCHRANZ_INDUSTRIAL = 7
};

struct KickParams {
    KickType type = KickType::ZAAG_CHAINSAW;
    float rootHz = 55.0f;           // Key-locked sub root (41..73 Hz)
    float bpm = 200.0f;             // Tempo for exact beat-length envelope
    float drive = 0.85f;            // 0..1 distortion intensity
    float aggression = 0.85f;       // 0..1 piep resonance / zaag buzz / krach metallic grit
    float pitchSemitoneOffset = 0.0f; // Melodic kick pitch offset (e.g. 0, +3, +5, +7)
    float lengthBeats = 1.0f;       // 1.0 = full beat, 0.333 = triplet roll, 0.5 = double kick
    uint32_t seed = 1337u;
};

class HardcoreSynth {
public:
    /**
     * Synthesizes a single stereo 44.1 kHz Float32 hardcore kick (interleaved L/R).
     * Guaranteed finite, DC-free, sub < 140 Hz mono-locked, peaks <= 0.98f.
     */
    static std::vector<float> renderKick(const KickParams& params);

    /**
     * Synthesizes a 1-bar (4 beats) stereo audition loop of the selected kick
     * with crisp offbeat hardcore hats and subtle bar-end turnaround for instant pad preview.
     */
    static std::vector<float> renderKickPreviewLoop(
        KickType type,
        float rootHz,
        float bpm,
        float drive,
        float aggression
    );

    /**
     * Key-locked Industrial Screech / Laser Stab (stereo interleaved 44.1 kHz).
     */
    static std::vector<float> renderIndustrialScreech(
        float rootHz,
        float durationSec,
        float aggression,
        int variant = 0
    );

    /**
     * Accelerating Snare/Kick Build-up Roll (1/4 -> 1/8 -> 1/16 -> 1/32 pitch rise).
     */
    static std::vector<float> renderSnareBuildupRoll(
        float bpm,
        float durationSec,
        float rootHz,
        float aggression
    );

    /**
     * Sweeping Stereo Noise Riser + Harmonic Tension Tone.
     */
    static std::vector<float> renderNoiseRiser(
        float durationSec,
        float rootHz,
        float aggression
    );

    /**
     * Festival Drop Sub Impact / Downlifter Boom.
     */
    static std::vector<float> renderSubImpact(
        float rootHz,
        float durationSec
    );

    /**
     * Crisp 909-style Hardcore Offbeat Hi-Hat / Ride Hit (stereo interleaved).
     */
    static std::vector<float> renderHardcoreHat(
        float durationSec,
        bool isOpen,
        uint32_t seed = 909u
    );
};

}  // namespace zaagforge
