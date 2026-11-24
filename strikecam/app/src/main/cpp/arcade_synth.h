#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace strikecam {

enum class ArcadeTrackId : int32_t {
    NEON_VELOCITY = 0,   // 135 BPM — Synthwave Drive
    IRON_FIST = 1,       // 152 BPM — Raw Hard Techno
    CYBER_RUSH = 2,      // 174 BPM — Drum & Bass
    ZAAG_OVERDRIVE = 3,  // 200 BPM — Uptempo Zaag Assault
    PIEP_BOSS = 4        // 215 BPM — Extreme Piepcore Reflexes
};

enum class ArcadeSfxId : int32_t {
    PERFECT = 0,
    GREAT = 1,
    GOOD = 2,
    DODGE_CLEAR = 3,
    MISS = 4,
    COMBO_MILESTONE = 5,
    COUNTDOWN_TICK = 6,
    COUNTDOWN_GO = 7
};

struct ArcadeTrackMeta {
    ArcadeTrackId id;
    const char* name;
    const char* genre;
    float bpm;
    float durationSec;
};

constexpr int32_t kSynthSampleRate = 44100;
constexpr int32_t kBuiltInTrackCount = 5;
constexpr int32_t kSfxCount = 8;

ArcadeTrackMeta getBuiltInTrackMeta(int32_t trackId);

/**
 * Synthesizes one of the 5 complete stereo 44.1 kHz Float32 arcade tracks on demand.
 * Output is interleaved stereo (L, R, L, R, ...), guaranteed finite with peak <= 0.98f.
 */
std::vector<float> synthesizeBuiltInTrack(int32_t trackId, int32_t sampleRate = kSynthSampleRate);

/**
 * Synthesizes crisp mono 44.1 kHz Float32 arcade hit/miss/dodge/countdown SFX buffers.
 * Guaranteed finite with peak <= 0.98f.
 */
std::vector<float> synthesizeSfx(int32_t sfxId, int32_t sampleRate = kSynthSampleRate);

/**
 * Converts normalized Float32 [-1..1] audio to clamped Int16 PCM for Android AudioTrack.
 */
std::vector<int16_t> floatToPcm16(const std::vector<float>& input);

} // namespace strikecam
