#pragma once

#include <cstdint>
#include <vector>

namespace zaagforge {

enum class KickType : int {
    PIEP_UOK = 0,          // Unicorn On Ketamine / GPF
    KRACH_NOISEFLOW = 1,   // Noiseflow / Deutscher Krach
    ZAAG_CHAINSAW = 2,     // Krowdexx / Rebelion / Dimitri K
    UPTEMPO_BOUNCE = 3,    // Dimitri K / Barber / Major Conspiracy
    ZAAG_PIEP_HYBRID = 4,  // Festival Uptempo Switch
    RAWSTYLE_GATED = 5,    // Dual Damage / Warface / Kruelty
    FRENCHCORE_MELODIC = 6,// Sefa / Dr. Peacock / Billx (True Euphoric Frenchcore!)
    SCHRANZ_INDUSTRIAL = 7,// Klangkuenstler / O.B.I.
    TECHNO_ROLLING = 8,    // Charlotte de Witte / Amelie Lens / Adam Beyer (True Normal Techno!)
    HARD_TECHNO_RAVE = 9,  // Sara Landry / Nico Moreno / I Hate Models (True Hard Techno!)
    GABBER_MILLENNIUM = 10,// Angerfist / Neophyte / Tha Playah (True 909 Gabber!)
    ACID_303_TECHNO = 11   // 999999999 / Alignment (Acid Techno 303!)
};

constexpr int kNumKickTypes = 12;

struct KickParams {
    KickType type = KickType::ZAAG_CHAINSAW;
    float rootHz = 55.0f;           // Key-locked sub root (41..73 Hz)
    float bpm = 200.0f;             // Tempo for exact beat-length envelope
    float drive = 0.85f;            // 0..1 upper kick character distortion intensity
    float aggression = 0.85f;       // 0..1 piep resonance / zaag buzz / krach grit / rumble
    float bassSaturation = 0.34f;   // 0..1 dedicated sub-bass warmth/saturation
    float pitchSemitoneOffset = 0.0f; // Melodic kick pitch offset (e.g. 0, +3, +5, +7)
    float lengthBeats = 1.0f;       // 1.0 = full beat, 0.333 = triplet roll, 0.5 = double kick
    uint32_t seed = 1337u;
};

class HardcoreSynth {
public:
    static std::vector<float> renderKick(const KickParams& params);

    static std::vector<float> renderKickPreviewLoop(
        KickType type,
        float rootHz,
        float bpm,
        float drive,
        float aggression,
        float bassSaturation = 0.34f
    );

    static std::vector<float> renderIndustrialScreech(
        float rootHz,
        float durationSec,
        float aggression,
        int variant = 0
    );

    static std::vector<float> renderSnareBuildupRoll(
        float bpm,
        float durationSec,
        float rootHz,
        float aggression
    );

    static std::vector<float> renderNoiseRiser(
        float durationSec,
        float rootHz,
        float aggression
    );

    static std::vector<float> renderSubImpact(
        float rootHz,
        float durationSec
    );

    static std::vector<float> renderHardcoreHat(
        float durationSec,
        bool isOpen,
        uint32_t seed = 909u
    );

    // Pro Artist-Inspired Genre Layers (Techno / Hard Techno / Frenchcore / Gabber!)
    static std::vector<float> render909Clap(
        float durationSec = 0.16f,
        uint32_t seed = 9091u
    );

    static std::vector<float> render909Ride(
        float durationSec = 0.24f,
        uint32_t seed = 9092u
    );

    static std::vector<float> renderAcid303Riff(
        float rootHz,
        float bpm,
        int bars,
        float aggression,
        uint32_t seed = 303u
    );

    static std::vector<float> renderEuphoricChordLayer(
        float rootHz,
        bool isMinor,
        float bpm,
        int bars
    );

    static std::vector<float> renderRaveHooverStab(
        float rootHz,
        float durationSec,
        float aggression
    );
};

}  // namespace zaagforge
