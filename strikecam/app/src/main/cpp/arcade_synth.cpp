#include "arcade_synth.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

namespace strikecam {

namespace {

constexpr float kTwoPi = 6.28318530717958647692f;
constexpr float kMasterCeiling = 0.94f;

inline float fastTanh(float x) {
    return std::tanh(x);
}

inline float sawWave(float phase) {
    float p = phase - std::floor(phase);
    return 2.0f * p - 1.0f;
}

inline float squareWave(float phase, float duty = 0.5f) {
    float p = phase - std::floor(phase);
    return (p < duty) ? 1.0f : -1.0f;
}

inline float midiToHz(float midi) {
    return 440.0f * std::pow(2.0f, (midi - 69.0f) / 12.0f);
}

// Deterministic fast xorshift noise generator for crisp snares/hi-hats
struct NoiseGen {
    uint32_t state = 0x9E3779B9u;
    inline float next() {
        uint32_t x = state;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        state = x;
        return (static_cast<float>(x & 0x00FFFFFFu) / 8388608.0f) - 1.0f;
    }
};

// Simple 1-pole low-pass filter for punchy bass/acid/screech shaping
struct OnePoleLP {
    float y = 0.0f;
    inline float process(float x, float cutoffHz, float sr) {
        float a = std::clamp(kTwoPi * cutoffHz / (kTwoPi * cutoffHz + sr), 0.001f, 0.99f);
        y += a * (x - y);
        return y;
    }
};

struct OnePoleHP {
    float prevX = 0.0f;
    float y = 0.0f;
    inline float process(float x, float cutoffHz, float sr) {
        float rc = 1.0f / (kTwoPi * std::max(20.0f, cutoffHz));
        float dt = 1.0f / sr;
        float a = rc / (rc + dt);
        y = a * (y + x - prevX);
        prevX = x;
        return y;
    }
};

// Apply clean soft-clipping and ensure no NaN/Inf with strict ceiling <= 0.96f
void finalizeMaster(std::vector<float>& buf) {
    float peak = 0.0f;
    for (float& s : buf) {
        if (!std::isfinite(s)) s = 0.0f;
        s = fastTanh(s * 0.85f) * 1.04f;
        float a = std::abs(s);
        if (a > peak) peak = a;
    }
    if (peak > kMasterCeiling) {
        float scale = kMasterCeiling / peak;
        for (float& s : buf) {
            s *= scale;
        }
    }
}

} // namespace

ArcadeTrackMeta getBuiltInTrackMeta(int32_t trackId) {
    switch (trackId) {
        case 0:
            return {ArcadeTrackId::NEON_VELOCITY, "Neon Velocity", "Synthwave Drive", 135.0f, 66.0f};
        case 1:
            return {ArcadeTrackId::IRON_FIST, "Iron Fist", "Raw Hard Techno", 152.0f, 64.0f};
        case 2:
            return {ArcadeTrackId::CYBER_RUSH, "Cyber Rush", "Drum & Bass", 174.0f, 66.0f};
        case 3:
            return {ArcadeTrackId::ZAAG_OVERDRIVE, "Zaag Overdrive", "Uptempo Zaag Assault", 200.0f, 64.0f};
        case 4:
        default:
            return {ArcadeTrackId::PIEP_BOSS, "Piep Boss", "Extreme Piepcore", 215.0f, 64.0f};
    }
}

std::vector<float> synthesizeBuiltInTrack(int32_t trackId, int32_t sampleRate) {
    const ArcadeTrackMeta meta = getBuiltInTrackMeta(trackId);
    const float sr = static_cast<float>(std::max(8000, sampleRate));
    const size_t totalFrames = static_cast<size_t>(meta.durationSec * sr);
    std::vector<float> stereo(totalFrames * 2, 0.0f);

    const float bpm = meta.bpm;
    const float beatSec = 60.0f / bpm;
    const float stepSec = beatSec * 0.25f; // 16th-note grid

    NoiseGen noise;
    OnePoleLP bassLp;
    OnePoleLP leadLp;
    OnePoleHP hatHp;
    OnePoleHP snareHp;

    double bassPhase = 0.0;
    double leadPhase1 = 0.0;
    double leadPhase2 = 0.0;
    double kickPhase = 0.0;

    // Musical chord/bass progressions per track
    constexpr std::array<float, 4> kSynthwaveRoots = {45.0f, 41.0f, 48.0f, 43.0f}; // Am - F - C - G
    constexpr std::array<float, 4> kTechnoRoots = {38.0f, 38.0f, 41.0f, 40.0f};    // D - D - F - E
    constexpr std::array<float, 4> kDnbRoots = {42.0f, 46.0f, 44.0f, 39.0f};       // F# - Bb - G# - Eb
    constexpr std::array<float, 4> kZaagRoots = {41.0f, 41.0f, 44.0f, 43.0f};      // F - F - G# - G
    constexpr std::array<float, 4> kPiepRoots = {43.0f, 46.0f, 41.0f, 44.0f};      // G - Bb - F - G#

    constexpr std::array<float, 8> kArpIntervals = {0.0f, 7.0f, 12.0f, 15.0f, 19.0f, 15.0f, 12.0f, 7.0f};
    constexpr std::array<float, 8> kScreechIntervals = {12.0f, 15.0f, 12.0f, 19.0f, 18.0f, 15.0f, 12.0f, 10.0f};

    int prevBeatInt = -1;

    for (size_t i = 0; i < totalFrames; ++i) {
        const double t = static_cast<double>(i) / static_cast<double>(sr);
        const double beatPos = t / static_cast<double>(beatSec);
        const int beatInt = static_cast<int>(beatPos);
        const float beatFrac = static_cast<float>(beatPos - static_cast<double>(beatInt));
        const int barIdx = beatInt / 4;
        const int beatInBar = beatInt % 4;
        const int step16 = static_cast<int>(t / static_cast<double>(stepSec)) % 16;
        const float stepFrac = static_cast<float>(std::fmod(t, static_cast<double>(stepSec)) / static_cast<double>(stepSec));

        if (beatInt != prevBeatInt) {
            kickPhase = 0.0;
            prevBeatInt = beatInt;
        }

        // Dynamic song structure: Intro -> Build -> Drop 1 -> Breakdown -> Drop 2 -> Climax
        const float progress = static_cast<float>(i) / static_cast<float>(totalFrames);
        const bool isIntro = progress < 0.14f;
        const bool isBreakdown = progress >= 0.48f && progress < 0.60f;
        const bool isDrop = (progress >= 0.16f && progress < 0.48f) || (progress >= 0.62f && progress < 0.96f);
        const float sectionGain = isIntro ? 0.72f : (isBreakdown ? 0.62f : (isDrop ? 1.15f : 0.85f));

        // Clean fade-in / fade-out envelope
        float masterEnv = 1.0f;
        if (t < 0.08) masterEnv = static_cast<float>(t / 0.08);
        if (t > static_cast<double>(meta.durationSec) - 0.8) {
            masterEnv = std::clamp(static_cast<float>((meta.durationSec - t) / 0.8), 0.0f, 1.0f);
        }

        float kick = 0.0f;
        float snare = 0.0f;
        float hat = 0.0f;
        float bass = 0.0f;
        float leadL = 0.0f;
        float leadR = 0.0f;

        const float nSample = noise.next();

        switch (trackId) {
            case 0: { // Neon Velocity — 135 BPM Synthwave Drive
                const float root = kSynthwaveRoots[(barIdx / 2) % 4];
                // 4-on-the-floor punchy synthwave kick
                const float kTime = beatFrac * beatSec;
                const float kFreq = 48.0f + 135.0f * std::exp(-kTime * 34.0f);
                kickPhase += static_cast<double>(kFreq / sr);
                const float kEnv = std::exp(-kTime * 11.5f);
                kick = std::sin(static_cast<float>(kickPhase) * kTwoPi) * kEnv * (isBreakdown ? 0.55f : 0.92f);

                // Gated 80s snare on beats 2 & 4
                if (beatInBar == 1 || beatInBar == 3) {
                    const float sEnv = std::exp(-kTime * 13.0f);
                    const float sTone = std::sin(kTime * kTwoPi * 195.0f) * std::exp(-kTime * 22.0f);
                    snare = (snareHp.process(nSample, 900.0f, sr) * 0.62f + sTone * 0.45f) * sEnv * (isDrop ? 0.75f : 0.48f);
                }

                // Crisp 8th off-beat hi-hat
                if ((step16 % 2) == 0) {
                    const float hEnv = std::exp(-stepFrac * stepSec * 42.0f);
                    hat = hatHp.process(nSample, 7200.0f, sr) * hEnv * ((step16 % 4 == 2) ? 0.34f : 0.16f);
                }

                // Rolling 16th Juno-style bass
                const float bHz = midiToHz(root + ((step16 % 4 == 2) ? 12.0f : 0.0f));
                bassPhase += static_cast<double>(bHz / sr);
                const float rawBass = sawWave(static_cast<float>(bassPhase)) * 0.6f +
                                      squareWave(static_cast<float>(bassPhase * 0.5), 0.45f) * 0.4f;
                const float bCut = isDrop ? (900.0f + 950.0f * std::exp(-stepFrac * 5.0f)) : 520.0f;
                const float sidechain = std::clamp(beatFrac * 3.2f, 0.25f, 1.0f);
                bass = bassLp.process(rawBass, bCut, sr) * 0.50f * sidechain;

                // Wide stereo supersaw neon arpeggio
                const float arpNote = root + 24.0f + kArpIntervals[step16 % 8];
                const float lHz = midiToHz(arpNote);
                leadPhase1 += static_cast<double>((lHz * 0.996f) / sr);
                leadPhase2 += static_cast<double>((lHz * 1.004f) / sr);
                const float arpEnv = std::exp(-stepFrac * 4.5f) * (isDrop ? 0.42f : 0.28f);
                const float l1 = leadLp.process(sawWave(static_cast<float>(leadPhase1)), isDrop ? 3800.0f : 1800.0f, sr);
                const float l2 = sawWave(static_cast<float>(leadPhase2)) * 0.55f;
                leadL = (l1 + l2 * 0.3f) * arpEnv;
                leadR = (l2 + l1 * 0.3f) * arpEnv;
                break;
            }
            case 1: { // Iron Fist — 152 BPM Raw Hard Techno
                const float root = kTechnoRoots[(barIdx / 2) % 4];
                const float kTime = beatFrac * beatSec;
                // Distorted industrial kick + dark sub rumble
                const float kFreq = 46.0f + 180.0f * std::exp(-kTime * 42.0f);
                kickPhase += static_cast<double>(kFreq / sr);
                const float kEnv = std::exp(-kTime * 8.5f);
                const float rumble = std::sin(kTime * kTwoPi * 48.0f) * std::sin(beatFrac * 3.14159f) * 0.38f;
                kick = fastTanh((std::sin(static_cast<float>(kickPhase) * kTwoPi) * kEnv * 1.9f + rumble) * (isBreakdown ? 0.65f : 1.15f)) * 0.86f;

                // Metallic off-beat ride + industrial clap
                if (step16 == 2 || step16 == 6 || step16 == 10 || step16 == 14) {
                    const float hEnv = std::exp(-stepFrac * stepSec * 28.0f);
                    hat = hatHp.process(nSample, 6500.0f, sr) * hEnv * 0.40f;
                }
                if (beatInBar == 1 || beatInBar == 3) {
                    const float sEnv = std::exp(-kTime * 19.0f);
                    snare = snareHp.process(nSample, 1400.0f, sr) * sEnv * (isDrop ? 0.62f : 0.35f);
                }

                // Acid 303 squelch bassline
                const float acidOffset = (step16 == 3 || step16 == 11) ? 12.0f : ((step16 == 7 || step16 == 15) ? 7.0f : 0.0f);
                const float bHz = midiToHz(root + acidOffset);
                bassPhase += static_cast<double>(bHz / sr);
                const float sweep = 420.0f + (isDrop ? 2600.0f : 900.0f) * std::exp(-stepFrac * 5.2f);
                bass = fastTanh(bassLp.process(sawWave(static_cast<float>(bassPhase)), sweep, sr) * 1.6f) * 0.44f;

                // Warehouse stab synth
                if (isDrop && (step16 == 0 || step16 == 6 || step16 == 12)) {
                    const float stabHz = midiToHz(root + 24.0f + ((barIdx % 2) ? 3.0f : 0.0f));
                    leadPhase1 += static_cast<double>(stabHz / sr);
                    leadPhase2 += static_cast<double>((stabHz * 1.498f) / sr); // fifth chord
                    const float stEnv = std::exp(-stepFrac * 6.5f) * 0.36f;
                    leadL = fastTanh((sawWave(static_cast<float>(leadPhase1)) + sawWave(static_cast<float>(leadPhase2))) * 1.2f) * stEnv;
                    leadR = fastTanh((sawWave(static_cast<float>(leadPhase2)) - sawWave(static_cast<float>(leadPhase1)) * 0.5f) * 1.2f) * stEnv;
                }
                break;
            }
            case 2: { // Cyber Rush — 174 BPM Drum & Bass
                const float root = kDnbRoots[(barIdx / 2) % 4];
                const float sTime = stepFrac * stepSec;
                // Authentic 2-step DnB breakbeat: kicks on 0 and 10 (plus pulse anchor on 4 & 12 for tight rhythm)
                const bool kickStep = (step16 == 0 || step16 == 10 || (isDrop && step16 == 8));
                const bool snareStep = (step16 == 4 || step16 == 12);

                if (kickStep) {
                    const float kFreq = 52.0f + 165.0f * std::exp(-sTime * 48.0f);
                    kickPhase += static_cast<double>(kFreq / sr);
                    kick = std::sin(static_cast<float>(kickPhase) * kTwoPi) * std::exp(-sTime * 16.0f) * 0.92f;
                } else if (beatFrac < 0.25f) {
                    // Subtle sub-pulse reinforcement on beats for crisp rhythm locking
                    kick = std::sin(beatFrac * beatSec * kTwoPi * 60.0f) * std::exp(-beatFrac * beatSec * 28.0f) * 0.35f;
                }
                if (snareStep) {
                    const float sEnv = std::exp(-sTime * 18.0f);
                    const float punch = std::sin(sTime * kTwoPi * 225.0f) * std::exp(-sTime * 30.0f);
                    snare = (snareHp.process(nSample, 1100.0f, sr) * 0.70f + punch * 0.65f) * sEnv * 0.88f;
                }
                // Rapid 16th ghost break hats
                {
                    const float hEnv = std::exp(-sTime * 55.0f);
                    const float accent = ((step16 % 2) == 0) ? 0.32f : 0.18f;
                    hat = hatHp.process(nSample, 8200.0f, sr) * hEnv * accent;
                }
                // Detuned Reese bass
                const float bHz = midiToHz(root);
                bassPhase += static_cast<double>(bHz / sr);
                leadPhase1 += static_cast<double>((bHz * 1.007f) / sr);
                const float reeseRaw = (sawWave(static_cast<float>(bassPhase)) + sawWave(static_cast<float>(leadPhase1))) * 0.5f;
                const float wobble = 350.0f + (isDrop ? 1200.0f : 450.0f) * (0.5f + 0.5f * std::sin(static_cast<float>(t * kTwoPi * 1.45)));
                bass = bassLp.process(reeseRaw, wobble, sr) * 0.56f;

                // Shimmering cyber arpeggio
                const float leadHz = midiToHz(root + 24.0f + kArpIntervals[(step16 / 2) % 8]);
                leadPhase2 += static_cast<double>(leadHz / sr);
                const float lEnv = std::exp(-stepFrac * 3.5f) * (isDrop ? 0.34f : 0.22f);
                const float lSig = squareWave(static_cast<float>(leadPhase2), 0.3f) * lEnv;
                leadL = lSig * ((step16 % 4 < 2) ? 1.0f : 0.45f);
                leadR = lSig * ((step16 % 4 >= 2) ? 1.0f : 0.45f);
                break;
            }
            case 3: { // Zaag Overdrive — 200 BPM Uptempo Zaag Assault!
                const float root = kZaagRoots[(barIdx / 2) % 4];
                const float kTime = beatFrac * beatSec;
                // Signature Uptempo Zaagkick: saw-modulated punch + aggressive mid-crunch wavefold + sub tail
                const float kFreq = 54.0f + 320.0f * std::exp(-kTime * 58.0f);
                kickPhase += static_cast<double>(kFreq / sr);
                const float kEnv = std::exp(-kTime * 7.8f);
                const float zaagBuzz = sawWave(static_cast<float>(kickPhase * 3.0)) * (isDrop ? 0.72f : 0.30f) *
                                       std::exp(-kTime * 11.0f);
                const float rawKick = (std::sin(static_cast<float>(kickPhase) * kTwoPi) * 1.5f + zaagBuzz) * kEnv;
                // Wavefold + hard tanh saturation for unmistakable zaag crunch
                const float folded = std::sin(rawKick * 2.4f) * 0.55f + fastTanh(rawKick * 2.8f) * 0.65f;
                kick = folded * (isBreakdown ? 0.55f : 0.95f);

                // Snappy uptempo off-beat hi-hat & snare hits
                if ((step16 % 4) == 2) {
                    hat = hatHp.process(nSample, 7600.0f, sr) * std::exp(-stepFrac * stepSec * 45.0f) * 0.36f;
                }
                if (beatInBar == 1 || beatInBar == 3) {
                    snare = snareHp.process(nSample, 1800.0f, sr) * std::exp(-kTime * 22.0f) * 0.52f;
                }

                // Raw hardstyle screech synth on drops!
                const float screechHz = midiToHz(root + 24.0f + kScreechIntervals[(beatInt / 1) % 8]);
                const float pitchEnv = 1.0f + 0.14f * std::exp(-beatFrac * 8.0f);
                leadPhase1 += static_cast<double>((screechHz * pitchEnv * 0.992f) / sr);
                leadPhase2 += static_cast<double>((screechHz * pitchEnv * 1.008f) / sr);
                const float scEnv = (isDrop ? 0.40f : 0.24f) * std::exp(-beatFrac * 2.6f);
                const float scL = fastTanh((sawWave(static_cast<float>(leadPhase1)) + squareWave(static_cast<float>(leadPhase1 * 1.5), 0.35f)) * 1.8f);
                const float scR = fastTanh((sawWave(static_cast<float>(leadPhase2)) + squareWave(static_cast<float>(leadPhase2 * 1.5), 0.65f)) * 1.8f);
                leadL = scL * scEnv;
                leadR = scR * scEnv;
                break;
            }
            case 4:
            default: { // Piep Boss — 215 BPM Extreme Piepcore Reflexes!
                const float root = kPiepRoots[(barIdx / 2) % 4];
                const float kTime = beatFrac * beatSec;
                // Signature high-pitched resonant Piepkick chirp transient + distorted sub punch
                const float piepChirpHz = 1200.0f + 2400.0f * std::exp(-kTime * 140.0f);
                const float piepTransient = std::sin(kTime * kTwoPi * piepChirpHz) * std::exp(-kTime * 52.0f) * (isDrop ? 1.15f : 0.65f);
                const float subFreq = 58.0f + 240.0f * std::exp(-kTime * 55.0f);
                kickPhase += static_cast<double>(subFreq / sr);
                const float subTail = fastTanh(std::sin(static_cast<float>(kickPhase) * kTwoPi) * 2.4f) * std::exp(-kTime * 9.5f);
                kick = fastTanh((piepTransient * 0.95f + subTail * 0.92f) * 1.35f) * (isBreakdown ? 0.58f : 0.94f);

                // Relentless double-time hats & boss snare
                hat = hatHp.process(nSample, 8800.0f, sr) * std::exp(-stepFrac * stepSec * 52.0f) * ((step16 % 2 == 0) ? 0.34f : 0.16f);
                if (beatInBar == 1 || beatInBar == 3) {
                    snare = snareHp.process(nSample, 2000.0f, sr) * std::exp(-kTime * 26.0f) * 0.58f;
                }

                // Frantic boss-battle hypersaw stabs + siren sweep
                const float stabHz = midiToHz(root + 24.0f + kArpIntervals[(step16 / 2) % 8]);
                const float siren = 1.0f + (isDrop ? 0.06f * std::sin(static_cast<float>(t * kTwoPi * 3.5)) : 0.0f);
                leadPhase1 += static_cast<double>((stabHz * siren * 0.993f) / sr);
                leadPhase2 += static_cast<double>((stabHz * siren * 1.007f) / sr);
                const float lEnv = std::exp(-stepFrac * 4.2f) * (isDrop ? 0.42f : 0.25f);
                leadL = fastTanh(sawWave(static_cast<float>(leadPhase1)) * 1.7f) * lEnv;
                leadR = fastTanh(sawWave(static_cast<float>(leadPhase2)) * 1.7f) * lEnv;
                break;
            }
        }

        const float monoCore = (kick * 0.72f + snare * 0.55f + bass * 0.65f) * sectionGain;
        const float left = (monoCore + hat * 0.45f + leadL * sectionGain) * masterEnv;
        const float right = (monoCore + hat * 0.55f + leadR * sectionGain) * masterEnv;

        stereo[i * 2] = left;
        stereo[i * 2 + 1] = right;
    }

    finalizeMaster(stereo);
    return stereo;
}

std::vector<float> synthesizeSfx(int32_t sfxId, int32_t sampleRate) {
    const float sr = static_cast<float>(std::max(8000, sampleRate));
    NoiseGen noise;
    OnePoleHP hp;

    float durationSec = 0.22f;
    switch (sfxId) {
        case static_cast<int32_t>(ArcadeSfxId::PERFECT):         durationSec = 0.24f; break;
        case static_cast<int32_t>(ArcadeSfxId::GREAT):           durationSec = 0.18f; break;
        case static_cast<int32_t>(ArcadeSfxId::GOOD):            durationSec = 0.14f; break;
        case static_cast<int32_t>(ArcadeSfxId::DODGE_CLEAR):     durationSec = 0.26f; break;
        case static_cast<int32_t>(ArcadeSfxId::MISS):            durationSec = 0.28f; break;
        case static_cast<int32_t>(ArcadeSfxId::COMBO_MILESTONE): durationSec = 0.42f; break;
        case static_cast<int32_t>(ArcadeSfxId::COUNTDOWN_TICK):  durationSec = 0.12f; break;
        case static_cast<int32_t>(ArcadeSfxId::COUNTDOWN_GO):    durationSec = 0.36f; break;
        default:                                                 durationSec = 0.18f; break;
    }

    const size_t totalSamples = static_cast<size_t>(durationSec * sr);
    std::vector<float> out(totalSamples, 0.0f);

    double phaseA = 0.0;
    double phaseB = 0.0;

    for (size_t i = 0; i < totalSamples; ++i) {
        const float t = static_cast<float>(i) / sr;
        const float prog = static_cast<float>(i) / static_cast<float>(totalSamples);
        float sample = 0.0f;

        switch (sfxId) {
            case static_cast<int32_t>(ArcadeSfxId::PERFECT): {
                // Punchy kinetic thump + crystalline laser major-5th chime
                const float punchHz = 75.0f + 260.0f * std::exp(-t * 55.0f);
                phaseA += static_cast<double>(punchHz / sr);
                const float punch = std::sin(static_cast<float>(phaseA) * kTwoPi) * std::exp(-t * 24.0f) * 0.75f;
                const float laser = (std::sin(t * kTwoPi * 880.0f) + 0.6f * std::sin(t * kTwoPi * 1318.5f)) *
                                    std::exp(-t * 14.0f) * 0.45f;
                const float snap = hp.process(noise.next(), 2400.0f, sr) * std::exp(-t * 65.0f) * 0.38f;
                sample = punch + laser + snap;
                break;
            }
            case static_cast<int32_t>(ArcadeSfxId::GREAT): {
                const float punchHz = 80.0f + 200.0f * std::exp(-t * 50.0f);
                phaseA += static_cast<double>(punchHz / sr);
                const float punch = std::sin(static_cast<float>(phaseA) * kTwoPi) * std::exp(-t * 26.0f) * 0.70f;
                const float ping = std::sin(t * kTwoPi * 660.0f) * std::exp(-t * 18.0f) * 0.38f;
                sample = punch + ping;
                break;
            }
            case static_cast<int32_t>(ArcadeSfxId::GOOD): {
                const float thudHz = 70.0f + 130.0f * std::exp(-t * 45.0f);
                phaseA += static_cast<double>(thudHz / sr);
                sample = std::sin(static_cast<float>(phaseA) * kTwoPi) * std::exp(-t * 28.0f) * 0.68f;
                break;
            }
            case static_cast<int32_t>(ArcadeSfxId::DODGE_CLEAR): {
                // Airy upward whoosh + crisp confirmation ping
                const float whooshEnv = std::sin(prog * 3.14159f) * 0.55f;
                const float whoosh = hp.process(noise.next(), 600.0f + 2600.0f * prog, sr) * whooshEnv;
                const float sweepHz = 360.0f + 680.0f * prog;
                phaseA += static_cast<double>(sweepHz / sr);
                const float tone = std::sin(static_cast<float>(phaseA) * kTwoPi) * whooshEnv * 0.45f;
                sample = whoosh + tone;
                break;
            }
            case static_cast<int32_t>(ArcadeSfxId::MISS): {
                // Low tactical warning buzz with downward pitch drop
                const float buzzHz = 165.0f - 85.0f * prog;
                phaseA += static_cast<double>(buzzHz / sr);
                phaseB += static_cast<double>((buzzHz * 1.07f) / sr);
                const float env = std::exp(-t * 7.5f) * 0.55f;
                sample = (sawWave(static_cast<float>(phaseA)) + sawWave(static_cast<float>(phaseB))) * 0.5f * env;
                break;
            }
            case static_cast<int32_t>(ArcadeSfxId::COMBO_MILESTONE): {
                // Rising 3-note heroic arcade power arpeggio (A5 - C#6 - E6)
                const float freq = (prog < 0.30f) ? 880.0f : ((prog < 0.60f) ? 1108.73f : 1318.51f);
                phaseA += static_cast<double>(freq / sr);
                phaseB += static_cast<double>((freq * 2.0f) / sr);
                const float env = std::exp(-t * 5.2f) * (1.0f - std::exp(-t * 140.0f));
                sample = (std::sin(static_cast<float>(phaseA) * kTwoPi) * 0.7f +
                          std::sin(static_cast<float>(phaseB) * kTwoPi) * 0.25f) * env * 0.72f;
                break;
            }
            case static_cast<int32_t>(ArcadeSfxId::COUNTDOWN_TICK): {
                const float env = std::exp(-t * 32.0f);
                sample = std::sin(t * kTwoPi * 784.0f) * env * 0.70f;
                break;
            }
            case static_cast<int32_t>(ArcadeSfxId::COUNTDOWN_GO):
            default: {
                const float env = std::exp(-t * 7.5f);
                sample = (std::sin(t * kTwoPi * 1046.5f) * 0.6f +
                          std::sin(t * kTwoPi * 1567.98f) * 0.4f +
                          std::sin(t * kTwoPi * 523.25f) * 0.4f) * env * 0.65f;
                break;
            }
        }
        out[i] = sample;
    }

    finalizeMaster(out);
    return out;
}

std::vector<int16_t> floatToPcm16(const std::vector<float>& input) {
    std::vector<int16_t> pcm(input.size());
    for (size_t i = 0; i < input.size(); ++i) {
        float clamped = std::clamp(std::isfinite(input[i]) ? input[i] : 0.0f, -1.0f, 1.0f);
        pcm[i] = static_cast<int16_t>(std::round(clamped * 32767.0f));
    }
    return pcm;
}

} // namespace strikecam
