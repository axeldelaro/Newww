#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <cstdint>
#include <string>
#include <vector>

namespace zaagforge {

constexpr float kSampleRate = 44100.0f;
constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 6.28318530717958647692f;
constexpr float kEpsilon = 1e-12f;

inline float sanitize(float x) noexcept {
    if (!std::isfinite(x)) return 0.0f;
    if (std::fabs(x) < 1e-24f) return 0.0f;
    return x;
}

inline float clampf(float x, float lo, float hi) noexcept {
    return std::max(lo, std::min(hi, x));
}

inline float dbToLinear(float db) noexcept {
    return std::pow(10.0f, db * 0.05f);
}

inline float linearToDb(float lin) noexcept {
    return 20.0f * std::log10(std::max(std::fabs(lin), 1e-6f));
}

inline float midiToHz(float midi) noexcept {
    return 440.0f * std::pow(2.0f, (midi - 69.0f) / 12.0f);
}

inline float hzToMidi(float hz) noexcept {
    return 69.0f + 12.0f * std::log2(std::max(hz, 1.0f) / 440.0f);
}

/**
 * Maps pitch class (0=C .. 11=B) to optimal hardcore sub-bass MIDI note
 * in range 41.2 Hz (E1, MIDI 28) .. 73.4 Hz (D2, MIDI 38).
 */
inline int pitchClassToSubMidi(int pitchClass) noexcept {
    int pc = ((pitchClass % 12) + 12) % 12;
    // E1=28 (pc=4), F1=29 (pc=5) ... B1=35 (pc=11), C2=36 (pc=0), C#2=37 (pc=1), D2=38 (pc=2), D#2=39->actually D#1=27 is 38.9Hz, D#2=39 is 77.8Hz; map pc=3 (D#) to 28..39 -> 38/39 or 27?
    // Wait: E1=28 (41.20 Hz), ..., D2=38 (73.42 Hz). Only pc=3 (D#/Eb) is 38.89 Hz (MIDI 27) or 77.78 Hz (MIDI 39).
    // If pc == 3 (Eb), MIDI 28 (E1) is too sharp; MIDI 39 (77.78Hz) or MIDI 27 (38.89Hz) — wait: prompt says rootHz in 41..73 Hz!
    // Let's check exact frequencies:
    // pc=4 (E)  -> MIDI 28 = 41.20 Hz
    // pc=5 (F)  -> MIDI 29 = 43.65 Hz
    // pc=6 (F#) -> MIDI 30 = 46.25 Hz
    // pc=7 (G)  -> MIDI 31 = 49.00 Hz
    // pc=8 (G#) -> MIDI 32 = 51.91 Hz
    // pc=9 (A)  -> MIDI 33 = 55.00 Hz
    // pc=10(A#) -> MIDI 34 = 58.27 Hz
    // pc=11(B)  -> MIDI 35 = 61.74 Hz
    // pc=0 (C)  -> MIDI 36 = 65.41 Hz
    // pc=1 (C#) -> MIDI 37 = 69.30 Hz
    // pc=2 (D)  -> MIDI 38 = 73.42 Hz
    // pc=3 (D#) -> MIDI 27 = 38.89 Hz (or fifth A#1=58.27 Hz for punchy club sub in 41..73Hz, or clamp to 41..73Hz).
    // Using fifth (pc+7)%12 -> A#1 (58.27 Hz) or keeping MIDI 39 clamped? Actually MIDI 27*1.06? No, don't detune!
    // For Eb/D#, in hardcore production, producers either pitch to Eb1 (38.9Hz) or Bb1 (58.3Hz fifth) or Eb2 (77.8Hz).
    // Wait: Eb1 is 38.9Hz (just below 41), Eb2 is 77.8Hz (just above 73). Let's return MIDI 27+12=39 -> or for strict 41..73 Hz:
    // Actually if we check 28..38, that covers 11 of 12 pitch classes; for pc==3 (D#/Eb), using fifth Bb1 (MIDI 34 = 58.27 Hz) is harmonically locked to Eb major/minor AND squarely in 41..73 Hz!
    if (pc >= 4) return 24 + pc;      // E1 (28) .. B1 (35)
    if (pc <= 2) return 36 + pc;      // C2 (36) .. D2 (38)
    return 34;                         // Eb/D# -> harmonic fifth Bb1 (58.27 Hz) in club sub sweet spot
}

inline std::string formatKeyName(int pitchClass, bool isMinor) {
    static const std::array<const char*, 12> kNames = {
        "C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"
    };
    int pc = ((pitchClass % 12) + 12) % 12;
    return std::string(kNames[pc]) + (isMinor ? "m" : "");
}

// Deterministic fast PRNG for reproducible DSP noise & tests
struct XorShift32 {
    uint32_t state = 0x13579BDFu;
    explicit XorShift32(uint32_t seed = 0x13579BDFu) : state(seed ? seed : 0x13579BDFu) {}

    inline uint32_t nextU32() noexcept {
        uint32_t x = state;
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        state = x;
        return x;
    }

    inline float nextBipolar() noexcept {
        return static_cast<float>(static_cast<int32_t>(nextU32())) * (1.0f / 2147483648.0f);
    }

    inline float nextUnipolar() noexcept {
        return static_cast<float>(nextU32() & 0x00FFFFFFu) * (1.0f / 16777216.0f);
    }
};

// ============================================================================
// High-Precision RBJ Biquad Filter (Direct Form II Transposed in double)
// ============================================================================
enum class BiquadType {
    LPF,
    HPF,
    BPF_PEAK,
    PEAK,
    LOW_SHELF,
    HIGH_SHELF,
    NOTCH,
    ALLPASS
};

class Biquad {
public:
    void reset() noexcept {
        z1_ = 0.0;
        z2_ = 0.0;
    }

    void configure(BiquadType type, float freqHz, float q, float gainDb = 0.0f, float sr = kSampleRate) noexcept {
        const double clampedFreq = std::max(10.0, std::min(static_cast<double>(sr) * 0.48, static_cast<double>(freqHz)));
        const double clampedQ = std::max(0.1, static_cast<double>(q));
        const double w0 = 2.0 * 3.14159265358979323846 * clampedFreq / static_cast<double>(sr);
        const double cosW0 = std::cos(w0);
        const double sinW0 = std::sin(w0);
        const double alpha = sinW0 / (2.0 * clampedQ);
        const double A = std::pow(10.0, static_cast<double>(gainDb) / 40.0);

        double b0 = 1.0, b1 = 0.0, b2 = 0.0;
        double a0 = 1.0, a1 = 0.0, a2 = 0.0;

        switch (type) {
            case BiquadType::LPF:
                b0 = (1.0 - cosW0) * 0.5;
                b1 = 1.0 - cosW0;
                b2 = (1.0 - cosW0) * 0.5;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha;
                break;
            case BiquadType::HPF:
                b0 = (1.0 + cosW0) * 0.5;
                b1 = -(1.0 + cosW0);
                b2 = (1.0 + cosW0) * 0.5;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha;
                break;
            case BiquadType::BPF_PEAK:
                b0 = alpha;
                b1 = 0.0;
                b2 = -alpha;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha;
                break;
            case BiquadType::PEAK:
                b0 = 1.0 + alpha * A;
                b1 = -2.0 * cosW0;
                b2 = 1.0 - alpha * A;
                a0 = 1.0 + alpha / A;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha / A;
                break;
            case BiquadType::LOW_SHELF: {
                const double beta = 2.0 * std::sqrt(A) * alpha;
                b0 = A * ((A + 1.0) - (A - 1.0) * cosW0 + beta);
                b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosW0);
                b2 = A * ((A + 1.0) - (A - 1.0) * cosW0 - beta);
                a0 = (A + 1.0) + (A - 1.0) * cosW0 + beta;
                a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cosW0);
                a2 = (A + 1.0) + (A - 1.0) * cosW0 - beta;
                break;
            }
            case BiquadType::HIGH_SHELF: {
                const double beta = 2.0 * std::sqrt(A) * alpha;
                b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + beta);
                b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0);
                b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - beta);
                a0 = (A + 1.0) - (A - 1.0) * cosW0 + beta;
                a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0);
                a2 = (A + 1.0) - (A - 1.0) * cosW0 - beta;
                break;
            }
            case BiquadType::NOTCH:
                b0 = 1.0;
                b1 = -2.0 * cosW0;
                b2 = 1.0;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha;
                break;
            case BiquadType::ALLPASS:
                b0 = 1.0 - alpha;
                b1 = -2.0 * cosW0;
                b2 = 1.0 + alpha;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cosW0;
                a2 = 1.0 - alpha;
                break;
        }

        const double invA0 = 1.0 / (std::fabs(a0) > 1e-18 ? a0 : 1.0);
        b0_ = b0 * invA0;
        b1_ = b1 * invA0;
        b2_ = b2 * invA0;
        a1_ = a1 * invA0;
        a2_ = a2 * invA0;
    }

    inline float process(float input) noexcept {
        const double x = static_cast<double>(sanitize(input));
        const double y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return sanitize(static_cast<float>(y));
    }

private:
    double b0_ = 1.0, b1_ = 0.0, b2_ = 0.0;
    double a1_ = 0.0, a2_ = 0.0;
    double z1_ = 0.0, z2_ = 0.0;
};

// ============================================================================
// 4th-Order Linkwitz-Riley Crossover (24 dB/oct phase-aligned)
// ============================================================================
class LinkwitzRiley4 {
public:
    void configure(float crossoverHz, float sr = kSampleRate) noexcept {
        constexpr float kButterQ = 0.70710678f;
        lp1_.configure(BiquadType::LPF, crossoverHz, kButterQ, 0.0f, sr);
        lp2_.configure(BiquadType::LPF, crossoverHz, kButterQ, 0.0f, sr);
        hp1_.configure(BiquadType::HPF, crossoverHz, kButterQ, 0.0f, sr);
        hp2_.configure(BiquadType::HPF, crossoverHz, kButterQ, 0.0f, sr);
    }

    void reset() noexcept {
        lp1_.reset();
        lp2_.reset();
        hp1_.reset();
        hp2_.reset();
    }

    inline void split(float x, float& lowOut, float& highOut) noexcept {
        lowOut = lp2_.process(lp1_.process(x));
        highOut = hp2_.process(hp1_.process(x));
    }

private:
    Biquad lp1_, lp2_;
    Biquad hp1_, hp2_;
};

// ============================================================================
// Fractional Delay Metallic / Formant Comb Resonator
// ============================================================================
class CombResonator {
public:
    explicit CombResonator(size_t maxDelaySamples = 2048)
        : buffer_(std::max<size_t>(maxDelaySamples, 64), 0.0f) {}

    void reset() noexcept {
        std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        writeIdx_ = 0;
        dampState_ = 0.0f;
    }

    inline float process(float x, float delaySamples, float feedback, float damping = 0.25f) noexcept {
        const size_t n = buffer_.size();
        const float d = clampf(delaySamples, 1.0f, static_cast<float>(n - 2));
        const float readPos = static_cast<float>(writeIdx_) - d;
        float wrapped = readPos;
        while (wrapped < 0.0f) wrapped += static_cast<float>(n);
        const size_t i0 = static_cast<size_t>(wrapped) % n;
        const size_t i1 = (i0 + 1) % n;
        const float frac = wrapped - std::floor(wrapped);

        const float delayed = buffer_[i0] * (1.0f - frac) + buffer_[i1] * frac;
        dampState_ = delayed * (1.0f - damping) + dampState_ * damping;

        const float fb = clampf(feedback, -0.96f, 0.96f);
        const float val = sanitize(x + dampState_ * fb);
        buffer_[writeIdx_] = std::tanh(val * 0.95f) * 1.05f; // gentle self-limiting stability
        writeIdx_ = (writeIdx_ + 1) % n;
        return delayed;
    }

private:
    std::vector<float> buffer_;
    size_t writeIdx_ = 0;
    float dampState_ = 0.0f;
};

// ============================================================================
// Non-Linear Waveshapers & 2x Oversampled Anti-Aliased Clipper
// ============================================================================
inline float softClipTanh(float x, float drive = 1.0f) noexcept {
    return std::tanh(x * drive);
}

inline float hardClipAsym(float x, float drive, float posLimit = 0.85f, float negLimit = 1.0f, float dcBias = 0.06f) noexcept {
    const float v = x * drive + dcBias;
    if (v > posLimit) return posLimit;
    if (v < -negLimit) return -negLimit;
    return v;
}

inline float wavefold(float x, float gain) noexcept {
    // Smooth trigonometric + triangle wavefolding for brutal Piep/Krach harmonics
    const float g = clampf(gain, 0.5f, 18.0f);
    const float v = x * g;
    return std::sin(v) * 0.72f + std::tanh(v * 0.45f) * 0.28f;
}

inline float bitCrushSample(float x, float bits) noexcept {
    const float levels = std::pow(2.0f, clampf(bits, 2.0f, 16.0f));
    return std::round(x * levels) / levels;
}

class OversampledClipper2x {
public:
    void reset() noexcept {
        prevIn_ = 0.0f;
        lpPrev_ = 0.0f;
    }

    inline float process(float x, float drive, float ceiling = 0.98f, float asymBias = 0.04f) noexcept {
        // 2x linear upsampling + half-band smoothing downsample to reduce clipping aliasing
        const float mid = 0.5f * (prevIn_ + x);
        prevIn_ = x;

        auto shape = [&](float s) -> float {
            const float d = s * drive + asymBias;
            // Smooth knee transitioning into brickwall ceiling
            const float soft = std::tanh(d);
            const float hard = clampf(d, -1.0f, 1.0f);
            const float blend = soft * 0.45f + hard * 0.55f;
            return clampf(blend - asymBias * 0.35f, -ceiling, ceiling);
        };

        const float y0 = shape(mid);
        const float y1 = shape(x);
        const float out = 0.25f * lpPrev_ + 0.5f * y0 + 0.25f * y1;
        lpPrev_ = y1;
        return clampf(sanitize(out), -ceiling, ceiling);
    }

private:
    float prevIn_ = 0.0f;
    float lpPrev_ = 0.0f;
};

// ============================================================================
// 3-Band OTT-Style Upward + Downward Multiband Compressor
// ============================================================================
class Ott3Band {
public:
    void configure(float lowXoverHz = 140.0f, float highXoverHz = 2500.0f, float sr = kSampleRate) noexcept {
        lowSplitL_.configure(lowXoverHz, sr);
        lowSplitR_.configure(lowXoverHz, sr);
        highSplitL_.configure(highXoverHz, sr);
        highSplitR_.configure(highXoverHz, sr);

        atkCoeff_ = std::exp(-1.0f / (0.004f * sr));
        relCoeff_ = std::exp(-1.0f / (0.065f * sr));
        reset();
    }

    void reset() noexcept {
        lowSplitL_.reset();
        lowSplitR_.reset();
        highSplitL_.reset();
        highSplitR_.reset();
        env_.fill(0.05f);
    }

    inline void processStereo(float& l, float& r, float depth = 0.65f, float monoSubBlend = 1.0f, float stereoWidth = 1.12f) noexcept {
        float lowL, restL, midL, highL;
        float lowR, restR, midR, highR;
        lowSplitL_.split(l, lowL, restL);
        lowSplitR_.split(r, lowR, restR);
        highSplitL_.split(restL, midL, highL);
        highSplitR_.split(restR, midR, highR);

        // Mono bass below crossover
        const float subMono = 0.5f * (lowL + lowR);
        lowL = lowL * (1.0f - monoSubBlend) + subMono * monoSubBlend;
        lowR = lowR * (1.0f - monoSubBlend) + subMono * monoSubBlend;

        auto compressBand = [&](int idx, float& bl, float& br, float downThresh, float upThresh, float makeup) {
            const float peak = std::max(std::fabs(bl), std::fabs(br));
            const float coeff = (peak > env_[idx]) ? atkCoeff_ : relCoeff_;
            env_[idx] = coeff * env_[idx] + (1.0f - coeff) * peak;

            const float e = std::max(env_[idx], 1e-4f);
            float gain = 1.0f;
            if (e > downThresh) {
                // Downward compression 6:1
                const float ratio = 1.0f / 6.0f;
                const float target = downThresh * std::pow(e / downThresh, ratio);
                gain = target / e;
            } else if (e < upThresh && e > 0.004f) {
                // OTT Upward compression (capped +9 dB so silence never explodes)
                const float boost = std::pow(upThresh / e, 0.35f);
                gain = std::min(boost, 2.8f);
            }
            const float finalGain = (1.0f - depth) + depth * (gain * makeup);
            bl *= finalGain;
            br *= finalGain;
        };

        compressBand(0, lowL, lowR, 0.42f, 0.14f, 1.18f);
        compressBand(1, midL, midR, 0.36f, 0.12f, 1.24f);
        compressBand(2, highL, highR, 0.30f, 0.10f, 1.22f);

        // Stereo width on mids + highs
        auto widen = [&](float& xl, float& xr, float w) {
            const float m = 0.5f * (xl + xr);
            const float s = 0.5f * (xl - xr) * w;
            xl = m + s;
            xr = m - s;
        };
        widen(midL, midR, stereoWidth);
        widen(highL, highR, stereoWidth * 1.05f);

        l = sanitize(lowL + midL + highL);
        r = sanitize(lowR + midR + highR);
    }

    inline float processMono(float x, float depth = 0.65f) noexcept {
        float l = x, r = x;
        processStereo(l, r, depth, 1.0f, 1.0f);
        return 0.5f * (l + r);
    }

private:
    LinkwitzRiley4 lowSplitL_, lowSplitR_;
    LinkwitzRiley4 highSplitL_, highSplitR_;
    std::array<float, 3> env_{0.05f, 0.05f, 0.05f};
    float atkCoeff_ = 0.99f;
    float relCoeff_ = 0.999f;
};

// ============================================================================
// In-Place Radix-2 Cooley-Tukey FFT
// ============================================================================
inline void fftInPlace(std::vector<std::complex<float>>& a, bool inverse = false) {
    const size_t n = a.size();
    if (n <= 1) return;

    // Bit-reversal permutation
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) {
            j ^= bit;
        }
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }

    for (size_t len = 2; len <= n; len <<= 1) {
        const float angle = (inverse ? 2.0f : -2.0f) * kPi / static_cast<float>(len);
        const std::complex<float> wlen(std::cos(angle), std::sin(angle));
        for (size_t i = 0; i < n; i += len) {
            std::complex<float> w(1.0f, 0.0f);
            const size_t half = len >> 1;
            for (size_t j = 0; j < half; ++j) {
                const auto u = a[i + j];
                const auto v = a[i + j + half] * w;
                a[i + j] = u + v;
                a[i + j + half] = u - v;
                w *= wlen;
            }
        }
    }

    if (inverse) {
        const float invN = 1.0f / static_cast<float>(n);
        for (auto& x : a) x *= invN;
    }
}

inline std::vector<float> makeHannWindow(size_t size) {
    std::vector<float> win(size);
    if (size <= 1) {
        if (size == 1) win[0] = 1.0f;
        return win;
    }
    for (size_t i = 0; i < size; ++i) {
        win[i] = 0.5f * (1.0f - std::cos(kTwoPi * static_cast<float>(i) / static_cast<float>(size - 1)));
    }
    return win;
}

}  // namespace zaagforge
