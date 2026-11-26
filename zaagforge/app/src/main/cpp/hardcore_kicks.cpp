#include "hardcore_kicks.h"
#include "dsp_utils.h"

#include <algorithm>
#include <array>
#include <cmath>

namespace zaagforge {

namespace {

void applyBoundaryFades(std::vector<float>& stereo, size_t fadeInFrames = 10, size_t fadeOutFrames = 96) {
    const size_t totalFrames = stereo.size() / 2;
    if (totalFrames == 0) return;

    const size_t fi = std::min(fadeInFrames, totalFrames / 4);
    for (size_t i = 0; i < fi; ++i) {
        const float g = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, fi));
        stereo[i * 2] *= g;
        stereo[i * 2 + 1] *= g;
    }

    const size_t fo = std::min(fadeOutFrames, totalFrames / 3);
    for (size_t i = 0; i < fo; ++i) {
        const size_t idx = totalFrames - 1 - i;
        const float g = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, fo));
        stereo[idx * 2] *= g;
        stereo[idx * 2 + 1] *= g;
    }
}

inline float shapeCleanControlledSub(float rawSineSub, float bassSat) noexcept {
    const float sat = clampf(bassSat, 0.0f, 1.0f);
    if (sat < 0.02f) {
        return clampf(rawSineSub, -0.96f, 0.96f);
    }
    const float preDrive = 1.0f + 2.4f * sat * sat;
    const float driven = std::tanh(rawSineSub * preDrive) / std::tanh(preDrive);
    return rawSineSub * (1.0f - sat) + driven * sat;
}

void sanitizeAndFinalizeKick(std::vector<float>& stereo, float targetPeak = 0.94f) {
    const size_t totalFrames = stereo.size() / 2;
    if (totalFrames == 0) return;

    Biquad dcL, dcR;
    dcL.configure(BiquadType::HPF, 26.0f, 0.707f);
    dcR.configure(BiquadType::HPF, 26.0f, 0.707f);

    float maxAbs = 1e-6f;
    for (size_t i = 0; i < totalFrames; ++i) {
        const float l = dcL.process(stereo[i * 2]);
        const float r = dcR.process(stereo[i * 2 + 1]);
        stereo[i * 2] = l;
        stereo[i * 2 + 1] = r;
        maxAbs = std::max(maxAbs, std::max(std::fabs(l), std::fabs(r)));
    }

    const float norm = (maxAbs > 0.01f) ? (targetPeak / maxAbs) : 1.0f;
    for (float& s : stereo) {
        s = clampf(sanitize(s * norm), -0.96f, 0.96f);
    }
    applyBoundaryFades(stereo, 8, 96);
}

// ============================================================================
// 1. PIEP_UOK (Unicorn On Ketamine / GPF — Surgical Singing Nasal Piep!)
// ============================================================================
std::vector<float> synthPiepUok(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad piepBpf, formant1, mudCut, subLpf, topHpf, airLpf;
    const float piepCenter = 1850.0f + 780.0f * agg;
    const float piepQ = 7.5f + 2.8f * agg;
    piepBpf.configure(BiquadType::BPF_PEAK, piepCenter, piepQ);
    formant1.configure(BiquadType::PEAK, 920.0f, 3.2f, 7.5f + 3.0f * agg);
    mudCut.configure(BiquadType::PEAK, 290.0f, 1.4f, -6.5f);
    subLpf.configure(BiquadType::LPF, 122.0f, 0.707f);
    topHpf.configure(BiquadType::HPF, 340.0f, 0.85f);
    airLpf.configure(BiquadType::LPF, 8200.0f, 0.85f); // trim harsh 10-18 kHz static fizz!

    OversampledClipper2x topClipper;
    double piepPhase = 0.0, subPhase = 0.0, chirpPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Surgical UOK nasal scream pitch drop: 3800 Hz -> 560 Hz
        const float sweepFreq = 560.0f + 3240.0f * std::exp(-t * (145.0f + 55.0f * agg));
        piepPhase += kTwoPi * sweepFreq / kSampleRate;

        // Clean resonant singing formant chirp (minimal aliasing, maximum iconic UOK character!)
        const float chirpFreq = piepCenter * (1.0f + 0.30f * std::exp(-t * 95.0f));
        chirpPhase += kTwoPi * chirpFreq / kSampleRate;

        const float envPiep = std::min(1.0f, t / 0.0015f) * std::exp(-u * (2.15f - 0.35f * agg));
        const float piepSrc = (std::sin(piepPhase) * 0.72f + std::sin(chirpPhase) * 0.52f + std::sin(piepPhase * 2.0) * 0.22f) * envPiep;

        float body = piepBpf.process(piepSrc) * (3.6f + 2.2f * agg) + piepSrc * 0.35f;
        body = formant1.process(body);
        body = topClipper.process(body, 2.0f + 2.0f * drv, 0.90f, 0.05f);
        float piepLayer = airLpf.process(topHpf.process(mudCut.process(body)));

        // Tight, punchy key-locked sub (zero mud, decays cleanly before next kick!)
        const float subFreq = baseHz + (baseHz * 1.55f) * std::exp(-t * 105.0f);
        subPhase += kTwoPi * subFreq / kSampleRate;
        const float subEnv = std::min(1.0f, t / 0.005f) * std::exp(-u * 2.1f);
        const float subClean = subLpf.process(shapeCleanControlledSub(std::sin(subPhase) * subEnv, bSat)) * 0.90f;

        const float stereoDiff = std::sin(chirpPhase * 0.5) * piepLayer * 0.05f;
        const float monoCore = subClean * 0.76f + piepLayer * (0.62f + 0.18f * agg);

        out[i * 2] = fastTanh((monoCore + stereoDiff) * 1.05f);
        out[i * 2 + 1] = fastTanh((monoCore - stereoDiff) * 1.05f);
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 2. KRACH_NOISEFLOW (Noiseflow / Deutscher Krach)
// ============================================================================
std::vector<float> synthKrachNoiseflow(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;
    XorShift32 rng(p.seed ^ 0x4B524348u);

    CombResonator combL(512), combR(512);
    Biquad metallicBpf, subLpf, midHpfL, midHpfR, crunchPeakL, crunchPeakR;
    metallicBpf.configure(BiquadType::PEAK, 2100.0f + 1200.0f * agg, 2.8f, 9.5f + 4.5f * agg);
    subLpf.configure(BiquadType::LPF, 128.0f, 0.707f);
    midHpfL.configure(BiquadType::HPF, 195.0f, 0.85f);
    midHpfR.configure(BiquadType::HPF, 195.0f, 0.85f);
    crunchPeakL.configure(BiquadType::PEAK, 3300.0f, 2.2f, 7.5f + 3.5f * agg);
    crunchPeakR.configure(BiquadType::PEAK, 3300.0f, 2.2f, 7.5f + 3.5f * agg);

    OversampledClipper2x clipWallL, clipWallR;

    const float delayMsL = 2.3f + (1.0f - agg) * 1.55f;
    const float delayMsR = delayMsL * 1.045f;
    const float delaySampL = delayMsL * 0.001f * kSampleRate;
    const float delaySampR = delayMsR * 0.001f * kSampleRate;
    const float combFb = 0.80f + 0.11f * agg;

    double phCar = 0.0, phMod1 = 0.0, phMod2 = 0.0, phSub = 0.0;
    float heldCrushL = 0.0f, heldCrushR = 0.0f;
    const int srReduce = (agg > 0.65f) ? 3 : 2;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        const float f0 = 260.0f + 1650.0f * std::exp(-t * 58.0f);
        phMod1 += kTwoPi * (f0 * 1.41421356f) / kSampleRate;
        phMod2 += kTwoPi * (f0 * 2.73f) / kSampleRate;
        const float fmIndex = (5.2f + 4.8f * agg) * std::exp(-t * 16.0f) + 1.3f * agg;
        phCar += kTwoPi * f0 / kSampleRate;

        const float fmMetal = std::sin(phCar + fmIndex * (std::sin(phMod1) * 0.65f + std::sin(phMod2) * 0.55f));
        const float noiseSnap = rng.nextBipolar() * std::exp(-t * 75.0f) * 0.82f;
        const float excite = (fmMetal * std::exp(-u * 1.4f) + noiseSnap) * 0.85f;

        float clangL = combL.process(excite, delaySampL, combFb, 0.16f) + excite * 0.52f;
        float clangR = combR.process(excite, delaySampR, combFb, 0.16f) + excite * 0.52f;

        clangL = wavefold(metallicBpf.process(clangL), 2.5f + 3.2f * drv);
        clangR = wavefold(metallicBpf.process(clangR), 2.5f + 3.2f * drv);

        if (i % static_cast<size_t>(srReduce) == 0) {
            const float bits = 6.5f - 2.0f * agg;
            heldCrushL = bitCrushSample(clangL, bits);
            heldCrushR = bitCrushSample(clangR, bits);
        }
        float wallL = midHpfL.process(crunchPeakL.process(clangL * 0.62f + heldCrushL * 0.45f));
        float wallR = midHpfR.process(crunchPeakR.process(clangR * 0.62f + heldCrushR * 0.45f));
        wallL = clipWallL.process(wallL, 1.55f + 1.25f * drv, 0.90f, 0.04f);
        wallR = clipWallR.process(wallR, 1.55f + 1.25f * drv, 0.90f, 0.04f);

        const float subFreq = baseHz + (baseHz * 2.0f) * std::exp(-t * 74.0f);
        phSub += kTwoPi * subFreq / kSampleRate;
        const float subEnv = std::min(1.0f, t / 0.004f) * std::exp(-u * 1.65f);
        const float subMono = subLpf.process(shapeCleanControlledSub(std::sin(phSub) * subEnv, bSat)) * 0.86f;

        out[i * 2] = std::tanh((subMono * 0.72f + wallL * (0.64f + 0.22f * agg)) * 1.06f);
        out[i * 2 + 1] = std::tanh((subMono * 0.72f + wallR * (0.64f + 0.22f * agg)) * 1.06f);
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 3. ZAAG_CHAINSAW (Krowdexx / Rebelion / Dimitri K)
// ============================================================================
std::vector<float> synthZaagChainsaw(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad zaagHpf, bellLow, bellHigh, subScoop, subLpf, airShelf, buzzGuardHpfL, buzzGuardHpfR;
    zaagHpf.configure(BiquadType::HPF, 185.0f, 0.85f);
    bellLow.configure(BiquadType::PEAK, 450.0f, 2.4f, 8.5f + 4.5f * agg);
    bellHigh.configure(BiquadType::PEAK, 1600.0f, 2.2f, 10.0f + 4.5f * agg);
    subScoop.configure(BiquadType::NOTCH, 245.0f, 2.2f);
    subLpf.configure(BiquadType::LPF, 112.0f, 0.707f);
    airShelf.configure(BiquadType::HIGH_SHELF, 4200.0f, 0.707f, 5.0f + 3.0f * agg);
    buzzGuardHpfL.configure(BiquadType::HPF, 150.0f, 0.707f);
    buzzGuardHpfR.configure(BiquadType::HPF, 150.0f, 0.707f);

    CombResonator formantComb(512);
    OversampledClipper2x buzzClipL, buzzClipR;
    const float combDelay = (1.55f + 0.55f * (1.0f - agg)) * 0.001f * kSampleRate;

    double subPhase = 0.0, sawPh1 = 0.0, sawPh2 = 0.0, sawPh3 = 0.0, tokPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        const float subSweep = baseHz + std::min(52.0f, baseHz) * std::exp(-t * 92.0f);
        subPhase += kTwoPi * subSweep / kSampleRate;
        const float subEnv = std::min(1.0f, t / 0.004f) * (1.0f - 0.32f * u) * std::exp(-u * 1.15f);
        const float cleanSub = subLpf.process(shapeCleanControlledSub(std::sin(subPhase) * subEnv, bSat)) * 0.90f;

        const float tokFreq = 180.0f + 2200.0f * std::exp(-t * 140.0f);
        tokPhase += kTwoPi * tokFreq / kSampleRate;
        const float tok = std::sin(tokPhase) * std::exp(-t * 95.0f) * 0.72f;

        const float sawBase = baseHz * 2.0f + 240.0f * std::exp(-t * 36.0f);
        sawPh1 = std::fmod(sawPh1 + sawBase / kSampleRate, 1.0);
        sawPh2 = std::fmod(sawPh2 + (sawBase * 1.007f) / kSampleRate, 1.0);
        sawPh3 = std::fmod(sawPh3 + (sawBase * 1.995f) / kSampleRate, 1.0);
        const float sawWall = ((2.0f * static_cast<float>(sawPh1) - 1.0f) * 0.55f
                             + (2.0f * static_cast<float>(sawPh2) - 1.0f) * 0.45f
                             + (2.0f * static_cast<float>(sawPh3) - 1.0f) * 0.35f)
                            * (0.95f - 0.25f * u);

        float z = zaagHpf.process(sawWall + tok);
        z = bellLow.process(z);
        z = bellHigh.process(z);
        z = hardClipAsym(z, 2.8f + 2.8f * drv, 0.78f, 0.96f, 0.09f);
        z = wavefold(z, 1.7f + 2.0f * agg);

        const float combed = formantComb.process(z, combDelay, 0.60f + 0.18f * agg, 0.22f);
        z = subScoop.process(z * 0.65f + combed * 0.52f);
        z = hardClipAsym(z, 2.0f + 1.8f * drv, 0.84f, 0.94f, -0.06f);
        z = airShelf.process(z);

        float buzzL = buzzGuardHpfL.process(buzzClipL.process(z + (2.0f * static_cast<float>(sawPh2) - 1.0f) * 0.07f, 1.45f + 0.85f * drv, 0.90f, 0.03f));
        float buzzR = buzzGuardHpfR.process(buzzClipR.process(z - (2.0f * static_cast<float>(sawPh2) - 1.0f) * 0.07f, 1.45f + 0.85f * drv, 0.90f, 0.03f));

        out[i * 2] = std::tanh((cleanSub * 0.72f + buzzL * (0.62f + 0.24f * agg)) * 1.06f);
        out[i * 2 + 1] = std::tanh((cleanSub * 0.72f + buzzR * (0.62f + 0.24f * agg)) * 1.06f);
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 4. UPTEMPO_BOUNCE (Dimitri K / Barber / Major Conspiracy)
// ============================================================================
std::vector<float> synthUptempoBounce(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad tokPeak, bounceRes, subLpf, midHpf, hiCrunch;
    tokPeak.configure(BiquadType::PEAK, 960.0f, 3.6f, 9.5f + 4.0f * agg);
    bounceRes.configure(BiquadType::PEAK, 200.0f, 2.2f, 5.5f + 3.0f * agg);
    subLpf.configure(BiquadType::LPF, 125.0f, 0.707f);
    midHpf.configure(BiquadType::HPF, 145.0f, 0.707f);
    hiCrunch.configure(BiquadType::PEAK, 2400.0f, 1.8f, 5.5f + 3.5f * agg);
    OversampledClipper2x topClip;

    double bodyPhase = 0.0, tokPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        float tailHz = baseHz;
        if (u < 0.28f) {
            const float a = u / 0.28f;
            tailHz = 112.0f + (baseHz - 112.0f) * (1.0f - std::exp(-a * 4.5f));
        } else {
            const float b = (u - 0.28f) / 0.72f;
            const float bounceBump = std::sin(kPi * clampf(b * 1.15f, 0.0f, 1.0f));
            const float targetTop = std::max(baseHz * 1.58f, 92.0f);
            tailHz = baseHz + (targetTop - baseHz) * bounceBump * (0.65f + 0.35f * agg);
        }
        const float freq = tailHz + 520.0f * std::exp(-t * 115.0f);
        bodyPhase += kTwoPi * freq / kSampleRate;

        const float tokHz = 260.0f + 1350.0f * std::exp(-t * 135.0f);
        tokPhase += kTwoPi * tokHz / kSampleRate;
        const float tok = tokPeak.process(std::sin(tokPhase) * std::exp(-t * 68.0f)) * 0.90f;

        const float tailEnv = (0.35f + 0.65f * clampf((u - 0.06f) * 5.0f, 0.0f, 1.0f))
                            * (1.0f - 0.22f * u) * std::exp(-u * 1.25f);
        const float rawBody = std::sin(bodyPhase) * tailEnv;

        const float cleanSub = subLpf.process(shapeCleanControlledSub(rawBody, bSat)) * 0.85f;
        float top = bounceRes.process(rawBody) * 0.85f + tok * 0.90f;
        top = hardClipAsym(top, 2.2f + 2.4f * drv, 0.82f, 0.94f, 0.06f);
        top = midHpf.process(hiCrunch.process(top));
        top = topClip.process(top, 1.45f + 0.95f * drv, 0.88f, 0.03f);

        const float y = std::tanh((cleanSub * 0.72f + top * 0.70f) * 1.06f);
        out[i * 2] = y;
        out[i * 2 + 1] = y;
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 5. ZAAG_PIEP_HYBRID
// ============================================================================
std::vector<float> synthZaagPiepHybrid(const KickParams& p, size_t nFrames, float baseHz) {
    auto piep = synthPiepUok(p, nFrames, baseHz);
    auto zaag = synthZaagChainsaw(p, nFrames, baseHz);
    std::vector<float> out(nFrames * 2, 0.0f);

    for (size_t i = 0; i < nFrames; ++i) {
        const float u = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, nFrames));
        const float zaagWeight = clampf((u - 0.18f) / 0.20f, 0.0f, 1.0f);
        const float smoothZaag = zaagWeight * zaagWeight * (3.0f - 2.0f * zaagWeight);
        const float piepWeight = 1.0f - smoothZaag * 0.80f;

        out[i * 2] = std::tanh((piep[i * 2] * piepWeight + zaag[i * 2] * (0.20f + 0.85f * smoothZaag)) * 1.05f);
        out[i * 2 + 1] = std::tanh((piep[i * 2 + 1] * piepWeight + zaag[i * 2 + 1] * (0.20f + 0.85f * smoothZaag)) * 1.05f);
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 6. RAWSTYLE_GATED (Dual Damage / Warface / Kruelty)
// ============================================================================
std::vector<float> synthRawstyleGated(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;
    XorShift32 rng(p.seed ^ 0x52415747u);

    CombResonator rev1(2048), rev2(2048), rev3(2048);
    Biquad klapBpf, revBandL, revBandR, revHpfL, revHpfR, punchEq, subLpf;
    klapBpf.configure(BiquadType::BPF_PEAK, 2450.0f, 4.2f);
    revBandL.configure(BiquadType::LPF, 2800.0f + 1600.0f * agg, 0.9f);
    revBandR.configure(BiquadType::LPF, 2800.0f + 1600.0f * agg, 0.9f);
    revHpfL.configure(BiquadType::HPF, 165.0f, 0.707f);
    revHpfR.configure(BiquadType::HPF, 165.0f, 0.707f);
    punchEq.configure(BiquadType::PEAK, 720.0f, 2.2f, 8.0f + 3.5f * agg);
    subLpf.configure(BiquadType::LPF, 132.0f, 0.707f);
    OversampledClipper2x topClipL, topClipR;

    double punchPhase = 0.0, subPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        const float pFreq = baseHz * 1.8f + 1750.0f * std::exp(-t * 95.0f);
        punchPhase += kTwoPi * pFreq / kSampleRate;
        const float punch = punchEq.process(std::sin(punchPhase) * std::exp(-t * 36.0f));

        const float klapExc = (rng.nextBipolar() * 0.7f + std::sin(punchPhase * 2.5)) * std::exp(-t * 115.0f);
        const float klaplong = klapBpf.process(klapExc) * (2.5f + 1.7f * agg);

        const float revIn = hardClipAsym(punch + klaplong * 0.6f, 2.5f + 2.0f * drv, 0.85f, 0.95f, 0.05f);
        const float r1 = rev1.process(revIn, 297.0f, 0.76f, 0.24f);
        const float r2 = rev2.process(revIn, 419.0f, 0.78f, 0.22f);
        const float r3 = rev3.process(revIn, 563.0f, 0.74f, 0.26f);

        const float gate = (u < 0.78f) ? 1.0f : clampf(1.0f - (u - 0.78f) / 0.12f, 0.0f, 1.0f);
        const float gatedRevL = revHpfL.process(revBandL.process(r1 * 0.5f + r2 * 0.4f + r3 * 0.35f)) * gate;
        const float gatedRevR = revHpfR.process(revBandR.process(r1 * 0.35f + r2 * 0.5f + r3 * 0.4f)) * gate;

        const float topL = topClipL.process(punch * 0.72f + klaplong * 0.52f + gatedRevL * (0.68f + 0.25f * agg), 1.45f + 0.90f * drv, 0.90f, 0.03f);
        const float topR = topClipR.process(punch * 0.72f + klaplong * 0.52f + gatedRevR * (0.68f + 0.25f * agg), 1.45f + 0.90f * drv, 0.90f, 0.03f);

        const float subFreq = baseHz + baseHz * 0.8f * std::exp(-t * 60.0f);
        subPhase += kTwoPi * subFreq / kSampleRate;
        const float sub = subLpf.process(shapeCleanControlledSub(std::sin(subPhase) * gate * std::exp(-u * 0.95f), bSat)) * 0.88f;

        out[i * 2] = std::tanh((sub * 0.74f + topL * 0.70f) * 1.06f);
        out[i * 2 + 1] = std::tanh((sub * 0.74f + topR * 0.70f) * 1.06f);
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 7. FRENCHCORE_MELODIC (Dr. Peacock / Sefa — Authentic 2-Stage Frenchcore!)
// Stage 1 (0..32%): ultra-tight woody Peacock tok punch + snappy low-end drop
// Stage 2 (34..96%): clean singing pitched offbeat reverse-tail locked to song chord!
// Surgical -7 dB mud notch (290 Hz) + -6 dB vocal window (1.7 kHz) = ZERO mud!
// ============================================================================
std::vector<float> synthFrenchcoreMelodic(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad mudScoop, vocalPocket, tokSnap, clickAir, subLpf, topHpf, topLpf;
    mudScoop.configure(BiquadType::PEAK, 290.0f, 1.35f, -7.0f);
    vocalPocket.configure(BiquadType::PEAK, 1700.0f, 1.10f, -6.2f);
    tokSnap.configure(BiquadType::PEAK, 680.0f, 2.4f, 5.5f + 2.5f * agg);
    clickAir.configure(BiquadType::PEAK, 3900.0f, 1.8f, 5.0f + 2.5f * agg);
    subLpf.configure(BiquadType::LPF, 128.0f, 0.707f);
    topHpf.configure(BiquadType::HPF, 155.0f, 0.707f);
    topLpf.configure(BiquadType::LPF, 7600.0f, 0.80f);

    double punchPh = 0.0, tailPh = 0.0, tokPh = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Stage 1: Crisp woody Peacock punch (0..32% of beat)
        const float punchFreq = baseHz + 920.0f * std::exp(-t * 165.0f);
        punchPh += kTwoPi * punchFreq / kSampleRate;
        const float punchEnv = std::min(1.0f, t / 0.0015f) * std::exp(-u * 6.8f);
        const float punchBody = std::sin(punchPh) * punchEnv;

        const float tokHz = 320.0f + 2300.0f * std::exp(-t * 170.0f);
        tokPh += kTwoPi * tokHz / kSampleRate;
        const float tok = std::sin(tokPh) * std::exp(-t * 98.0f) * 0.90f;

        // Stage 2: Clean harmonic offbeat reverse-tail (34..96% of beat -> iconic Peacock bounce!)
        tailPh += kTwoPi * baseHz / kSampleRate;
        const float revGate = clampf((u - 0.24f) / 0.16f, 0.0f, 1.0f) * clampf((0.97f - u) / 0.09f, 0.0f, 1.0f);
        const float revCurve = std::sin(kPi * clampf((u - 0.24f) / 0.73f, 0.0f, 1.0f));
        const float tailEnv = revGate * (0.45f + 0.55f * revCurve);
        const float tailFund = std::sin(tailPh) * tailEnv;
        const float tailHarm = (std::sin(tailPh * 2.0) * 0.36f + std::sin(tailPh * 3.0) * 0.16f) * tailEnv;

        const float cleanSub = subLpf.process(shapeCleanControlledSub(punchBody * 0.92f + tailFund * 0.88f, bSat)) * 0.92f;

        float rawTop = (punchBody * 0.55f + tok * 0.95f) + (tailFund * 0.42f + tailHarm * 0.85f);
        float top = fastTanh(rawTop * (1.85f + 1.55f * drv) + 0.04f) - 0.03f;
        top = topLpf.process(topHpf.process(clickAir.process(vocalPocket.process(mudScoop.process(tokSnap.process(top))))));

        const float y = fastTanh((cleanSub * 0.80f + top * 0.64f) * 1.04f);
        out[i * 2] = y;
        out[i * 2 + 1] = y;
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 8. SCHRANZ_INDUSTRIAL (Klangkuenstler / O.B.I.)
// ============================================================================
std::vector<float> synthSchranzIndustrial(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;
    XorShift32 rng(p.seed ^ 0x53434852u);

    CombResonator rumbleComb1(4096), rumbleComb2(4096);
    Biquad rumbleLpf, subLpf, metalBpf;
    rumbleLpf.configure(BiquadType::LPF, 132.0f, 1.1f);
    subLpf.configure(BiquadType::LPF, 135.0f, 0.707f);
    metalBpf.configure(BiquadType::BPF_PEAK, 1850.0f, 3.2f);

    double kickPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        const float kFreq = baseHz + 360.0f * std::exp(-t * 68.0f);
        kickPhase += kTwoPi * kFreq / kSampleRate;
        const float thump = subLpf.process(shapeCleanControlledSub(std::sin(kickPhase) * std::exp(-u * 3.0f), bSat));

        const float r1 = rumbleComb1.process(thump, 1320.0f, 0.70f, 0.45f);
        const float r2 = rumbleComb2.process(thump, 1980.0f, 0.68f, 0.48f);
        const float rumbleSidechain = clampf((u - 0.16f) * 3.8f, 0.0f, 1.0f);
        const float rumble = rumbleLpf.process(shapeCleanControlledSub((r1 + r2) * 0.95f, bSat * 0.85f)) * rumbleSidechain * 0.78f;

        const float sub16 = std::fmod(u * 4.0f, 1.0f);
        const float accent16 = (u > 0.22f) ? 1.0f : 0.3f;
        const float percNoise = rng.nextBipolar() * std::exp(-sub16 * 16.0f) * accent16;
        const float metalGrit = hardClipAsym(metalBpf.process(percNoise), 2.5f + 2.2f * drv, 0.75f, 0.85f, 0.04f);

        const float core = thump * 0.80f + rumble * 0.65f;
        out[i * 2] = std::tanh((core + metalGrit * (0.34f + 0.24f * agg)) * 1.06f);
        out[i * 2 + 1] = std::tanh((core + metalGrit * (0.28f + 0.30f * agg)) * 1.06f);
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 9. TECHNO_ROLLING (Charlotte de Witte / Amelie Lens / Adam Beyer — Vraie Techno!)
// Analog TR-909 punchy sub kick + clean hypnotic offbeat dark warehouse rumble!
// Zero harsh distortion — deep, warm, hypnotic Berlin/Awakenings club weight!
// ============================================================================
std::vector<float> synthTechnoRolling(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    CombResonator rumA(4096), rumB(4096);
    Biquad subLpf, rumLpf, clickBpf;
    subLpf.configure(BiquadType::LPF, 138.0f, 0.707f);
    rumLpf.configure(BiquadType::LPF, 118.0f, 1.05f);
    clickBpf.configure(BiquadType::BPF_PEAK, 1950.0f, 2.2f);

    const float subRoot = clampf(baseHz, 43.0f, 58.0f);
    double kickPh = 0.0, clickPh = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Classic warm 909 sine pitch sweep: 210 Hz -> subRoot (tight, club-ready punch)
        const float f = subRoot + 175.0f * std::exp(-t * 62.0f);
        kickPh += kTwoPi * f / kSampleRate;
        const float kEnv = std::min(1.0f, t / 0.002f) * std::exp(-u * 4.6f);
        const float kickRaw = std::sin(kickPh) * kEnv;
        const float punchSub = subLpf.process(shapeCleanControlledSub(kickRaw, bSat * 0.70f));

        // Crisp analog 909 beater click + warm chest thump (0..18 ms)
        clickPh += kTwoPi * (1650.0f + 1250.0f * std::exp(-t * 175.0f)) / kSampleRate;
        const float click = clickBpf.process(std::sin(clickPh) * std::exp(-t * 130.0f)) * (0.52f + 0.28f * drv);

        // Hypnotic tempo-synced Berlin warehouse rumble (tucked cleanly -6 dB under kick so vocals breathe!)
        const float r1 = rumA.process(kickRaw, 1580.0f, 0.62f, 0.56f);
        const float r2 = rumB.process(kickRaw, 2240.0f, 0.60f, 0.58f);
        const float rumDuck = clampf((u - 0.24f) * 3.4f, 0.0f, 1.0f) * clampf((1.0f - u) * 5.0f, 0.0f, 1.0f);
        const float rumble = rumLpf.process(shapeCleanControlledSub((r1 + r2) * 0.95f, bSat * 0.60f))
                           * rumDuck * (0.34f + 0.24f * agg);

        const float mono = punchSub * 0.88f + rumble * 0.46f + click * 0.60f;
        const float y = std::tanh(mono * 1.03f);
        out[i * 2] = y;
        out[i * 2 + 1] = y;
    }

    sanitizeAndFinalizeKick(out, 0.92f);
    return out;
}

// ============================================================================
// 10. HARD_TECHNO_RAVE (Sara Landry / Nico Moreno / I Hate Models — Vraie Hard Techno!)
// Chest-slamming industrial 909 punch + heavy pumping warehouse rumble +
// crisp metallic transient & controlled gritty upper harmonics!
// ============================================================================
std::vector<float> synthHardTechnoRave(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;
    XorShift32 rng(p.seed ^ 0x48544543u);

    CombResonator rum1(4096), rum2(4096), metalRoom(2048);
    Biquad subLpf, rumLpf, punchMid, topHpf, gritPeak;
    subLpf.configure(BiquadType::LPF, 130.0f, 0.707f);
    rumLpf.configure(BiquadType::LPF, 128.0f, 1.18f);
    punchMid.configure(BiquadType::PEAK, 320.0f, 1.8f, 5.5f + 3.0f * agg);
    topHpf.configure(BiquadType::HPF, 175.0f, 0.707f);
    gritPeak.configure(BiquadType::PEAK, 2600.0f, 1.8f, 6.0f + 3.5f * agg);

    double kickPh = 0.0, snapPh = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Aggressive Nico Moreno / Sara Landry pitch slam: 440 -> baseHz
        const float f = baseHz + 390.0f * std::exp(-t * 72.0f);
        kickPh += kTwoPi * f / kSampleRate;
        const float kEnv = std::min(1.0f, t / 0.002f) * std::exp(-u * 2.8f);
        const float kRaw = std::sin(kickPh) * kEnv;

        const float cleanSub = subLpf.process(shapeCleanControlledSub(kRaw, bSat));

        // Pumping industrial warehouse reverb rumble (massive groove between beats!)
        const float r1 = rum1.process(kRaw, 1180.0f, 0.74f, 0.42f);
        const float r2 = rum2.process(kRaw, 1790.0f, 0.72f, 0.45f);
        const float rumEnv = clampf((u - 0.16f) * 3.8f, 0.0f, 1.0f) * (1.0f - 0.18f * u);
        const float rumbleSub = rumLpf.process(shapeCleanControlledSub((r1 + r2) * 1.10f, bSat * 0.90f))
                              * rumEnv * (0.62f + 0.28f * agg);

        // Industrial warehouse metallic slam on upper layer (> 175 Hz)
        snapPh += kTwoPi * (280.0f + 2600.0f * std::exp(-t * 125.0f)) / kSampleRate;
        const float snap = (std::sin(snapPh) * 0.70f + rng.nextBipolar() * 0.38f) * std::exp(-t * 82.0f);
        const float roomHit = metalRoom.process(snap, 460.0f, 0.62f, 0.30f) * std::exp(-u * 2.2f);
        float top = topHpf.process(punchMid.process(kRaw * 0.65f + snap + roomHit * 0.45f));
        top = gritPeak.process(softClipTanh(top * (2.2f + 2.0f * drv)));

        const float l = std::tanh((cleanSub * 0.78f + rumbleSub * 0.68f + top * (0.62f + 0.20f * agg)) * 1.06f);
        const float r = std::tanh((cleanSub * 0.78f + rumbleSub * 0.68f + (top * 0.96f + roomHit * 0.14f) * (0.62f + 0.20f * agg)) * 1.06f);
        out[i * 2] = l;
        out[i * 2 + 1] = r;
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 11. GABBER_MILLENNIUM (Angerfist / Neophyte / Tha Playah — Vrai Gabber 909!)
// Authentic overdriven TR-909 Gabber kick: classic Mackie desk tube bark
// (550 Hz + 1.4 kHz crunch) cleanly split above a tight punchy 909 sub!
// ============================================================================
std::vector<float> synthGabberMillennium(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad mackieBark, mackieBite, vocalDip, subLpf, topHpf;
    mackieBark.configure(BiquadType::PEAK, 540.0f, 1.8f, 8.5f + 3.5f * agg);
    mackieBite.configure(BiquadType::PEAK, 1450.0f, 2.0f, 6.5f + 3.5f * agg);
    vocalDip.configure(BiquadType::PEAK, 2400.0f, 1.4f, -3.5f);
    subLpf.configure(BiquadType::LPF, 128.0f, 0.707f);
    topHpf.configure(BiquadType::HPF, 140.0f, 0.707f);
    OversampledClipper2x deskClip;

    double ph = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Classic 909 Gabber pitch envelope: 380 Hz -> baseHz
        const float freq = baseHz + 330.0f * std::exp(-t * 78.0f);
        ph += kTwoPi * freq / kSampleRate;

        const float env = std::min(1.0f, t / 0.0018f) * std::exp(-u * 1.40f);
        const float sine909 = std::sin(ph) * env;
        const float click909 = std::sin(kTwoPi * 1900.0f * t) * std::exp(-t * 160.0f) * 0.65f;

        // Clean controlled 909 sub + classic Mackie desk mid-bark overdrive!
        const float cleanSub = subLpf.process(shapeCleanControlledSub(sine909, bSat)) * 0.88f;

        float bark = mackieBark.process(sine909 + click909);
        bark = deskClip.process(bark, 2.6f + 2.8f * drv, 0.92f, 0.07f);
        bark = topHpf.process(vocalDip.process(mackieBite.process(bark)));

        const float y = std::tanh((cleanSub * 0.74f + bark * (0.68f + 0.18f * agg)) * 1.06f);
        out[i * 2] = y;
        out[i * 2 + 1] = y;
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 12. ACID_303_TECHNO (999999999 / Alignment)
// Tight punchy Techno 909 kick with crisp sub and clean mid pocket for 303 acid!
// ============================================================================
std::vector<float> synthAcid303Techno(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad subLpf, midScoop, clickPeak;
    subLpf.configure(BiquadType::LPF, 135.0f, 0.707f);
    midScoop.configure(BiquadType::PEAK, 680.0f, 1.3f, -4.5f); // clean pocket for TB-303 resonance!
    clickPeak.configure(BiquadType::PEAK, 2800.0f, 2.0f, 6.0f + 3.0f * agg);

    double ph = 0.0;
    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        const float freq = baseHz + 260.0f * std::exp(-t * 82.0f);
        ph += kTwoPi * freq / kSampleRate;
        const float env = std::min(1.0f, t / 0.002f) * std::exp(-u * 3.1f);
        const float raw = std::sin(ph) * env;
        const float click = std::sin(kTwoPi * 2400.0f * t) * std::exp(-t * 150.0f) * (0.55f + 0.25f * drv);

        const float sub = subLpf.process(shapeCleanControlledSub(raw, bSat * 0.85f)) * 0.92f;
        const float top = clickPeak.process(midScoop.process(softClipTanh((raw + click) * (1.6f + 1.1f * drv)))) * 0.55f;

        const float y = fastTanh((sub * 0.84f + top) * 1.04f);
        out[i * 2] = y;
        out[i * 2 + 1] = y;
    }

    sanitizeAndFinalizeKick(out, 0.93f);
    return out;
}

// ============================================================================
// 13. HARDTEK_BOUNCE (Darktek / Maissouille — Tribecore Kick + Elastic Offbeat Bass!)
// Beats 0..42%: Ultra-tight snappy Hardtek punch kick (clean woody click + tight sub)
// Beats 46..96%: FAT elastic offbeat bounce bass with resonant low-pass squelch pluck!
// ============================================================================
std::vector<float> synthHardtekBounce(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad kickSubLpf, clickPeak, bounceLpf, bounceHpf, mudNotch;
    kickSubLpf.configure(BiquadType::LPF, 138.0f, 0.707f);
    clickPeak.configure(BiquadType::PEAK, 2100.0f, 2.2f, 6.5f + 2.5f * drv);
    bounceHpf.configure(BiquadType::HPF, 38.0f, 0.707f);
    mudNotch.configure(BiquadType::PEAK, 310.0f, 1.5f, -5.0f);

    double kickPh = 0.0, clickPh = 0.0, bassPh = 0.0, bassSawPh = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // PART 1 (0..44%): Razor-tight Darktek / Maissouille Tribecore Punch Kick!
        const float kFreq = baseHz + 580.0f * std::exp(-t * 140.0f);
        kickPh += kTwoPi * kFreq / kSampleRate;
        const float kGate = (u < 0.44f) ? 1.0f : clampf(1.0f - (u - 0.44f) / 0.05f, 0.0f, 1.0f);
        const float kEnv = std::min(1.0f, t / 0.0014f) * std::exp(-u * 6.2f) * kGate;
        const float kRaw = std::sin(kickPh) * kEnv;
        const float kSub = kickSubLpf.process(shapeCleanControlledSub(kRaw, bSat * 0.85f)) * 0.96f;

        clickPh += kTwoPi * (360.0f + 2200.0f * std::exp(-t * 165.0f)) / kSampleRate;
        const float kClick = clickPeak.process(std::sin(clickPh) * std::exp(-t * 125.0f)) * (0.58f + 0.24f * drv);

        // PART 2 (45..96%): Fat Elastic Offbeat Bounce Bass (the unmistakable Hardtek BOOM-BA!)
        float bounceBass = 0.0f;
        if (u >= 0.44f && u <= 0.98f) {
            const float bu = (u - 0.44f) / 0.54f; // 0..1 inside offbeat half!
            const float bEnv = std::min(1.0f, bu / 0.09f) * std::sin(kPi * clampf(bu, 0.0f, 1.0f));
            const float bPitch = baseHz * (1.0f + 0.14f * std::exp(-bu * 9.0f));
            bassPh += kTwoPi * bPitch / kSampleRate;
            bassSawPh = std::fmod(bassSawPh + (bPitch * 2.0f) / kSampleRate, 1.0);

            // Dynamic squelchy rubber filter pluck (820 Hz -> 170 Hz)
            if ((i & 15u) == 0u) {
                const float pluckCut = 155.0f + (580.0f + 420.0f * agg) * std::exp(-bu * 5.2f);
                bounceLpf.configure(BiquadType::LPF, pluckCut, 1.65f + 1.15f * agg);
            }
            const float subSine = shapeCleanControlledSub(std::sin(bassPh) * bEnv, bSat * 0.70f);
            const float sawHarm = (2.0f * static_cast<float>(bassSawPh) - 1.0f) * bEnv;
            const float rubber = bounceLpf.process(sawHarm * 0.72f + subSine * 0.45f);
            bounceBass = bounceHpf.process(mudNotch.process(subSine * 0.78f + fastTanh(rubber * (1.6f + 1.1f * drv)) * 0.58f));
        }

        const float y = fastTanh((kSub * 0.88f + kClick * 0.50f + bounceBass * 0.90f) * 1.04f);
        out[i * 2] = y;
        out[i * 2 + 1] = y;
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 14. REBELION_PVC_ZAAG (Rebelion Overdose — Hollow PVC Klaplong -> Scooped Zaag!)
// Iconic hollow PVC pipe resonance tok + razor-sharp scooped metallic Overdose tail!
// ============================================================================
std::vector<float> synthRebelionPvcZaag(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad pvc1, pvc2, mudNotch, subLpf, tailHpfL, tailHpfR, presenceL, presenceR;
    pvc1.configure(BiquadType::BPF_PEAK, 780.0f, 7.5f);
    pvc2.configure(BiquadType::BPF_PEAK, 1520.0f, 6.2f);
    mudNotch.configure(BiquadType::PEAK, 265.0f, 1.5f, -8.5f); // razor-clean Rebelion scoop!
    subLpf.configure(BiquadType::LPF, 120.0f, 0.707f);
    tailHpfL.configure(BiquadType::HPF, 340.0f, 0.85f);
    tailHpfR.configure(BiquadType::HPF, 340.0f, 0.85f);
    presenceL.configure(BiquadType::PEAK, 2500.0f, 2.0f, 6.0f + 3.0f * agg);
    presenceR.configure(BiquadType::PEAK, 2500.0f, 2.0f, 6.0f + 3.0f * agg);

    CombResonator pvcTube(512);
    OversampledClipper2x clipL, clipR;
    double subPh = 0.0, tokPh = 0.0, saw1 = 0.0, saw2 = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Stage 1 (0..32%): Hollow resonant PVC pipe klaplong tok!
        const float tokHz = 290.0f + 2100.0f * std::exp(-t * 145.0f);
        tokPh += kTwoPi * tokHz / kSampleRate;
        const float tokRaw = std::sin(tokPh) * std::exp(-t * 78.0f);
        const float pvcPipe = (pvc1.process(tokRaw) * 2.4f + pvc2.process(tokRaw) * 1.9f + pvcTube.process(tokRaw, 56.0f, 0.62f, 0.25f) * 1.1f)
                            * (0.72f + 0.35f * agg);

        // Stage 2 (12..98%): Wide scooped Rebelion Zaag/Overdose tail + clean stadium sub!
        const float subFreq = baseHz + (baseHz * 1.1f) * std::exp(-t * 88.0f);
        subPh += kTwoPi * subFreq / kSampleRate;
        const float tailGate = clampf((u - 0.08f) / 0.08f, 0.0f, 1.0f) * clampf((0.98f - u) / 0.06f, 0.0f, 1.0f);
        const float subEnv = std::min(1.0f, t / 0.0020f) * std::max(std::exp(-u * 3.6f), tailGate * 0.98f);
        const float cleanSub = subLpf.process(shapeCleanControlledSub(std::sin(subPh) * subEnv, bSat)) * 0.98f;

        const float sawHz = baseHz * 2.0f;
        saw1 = std::fmod(saw1 + (sawHz * 0.994f) / kSampleRate, 1.0);
        saw2 = std::fmod(saw2 + (sawHz * 1.006f) / kSampleRate, 1.0);
        const float rawBuzzL = (2.0f * static_cast<float>(saw1) - 1.0f) * tailGate;
        const float rawBuzzR = (2.0f * static_cast<float>(saw2) - 1.0f) * tailGate;

        float topL = tailHpfL.process(presenceL.process(clipL.process(rawBuzzL, 2.8f + 2.6f * drv, 0.90f, 0.05f)));
        float topR = tailHpfR.process(presenceR.process(clipR.process(rawBuzzR, 2.8f + 2.6f * drv, 0.90f, 0.05f)));
        const float pvcClean = mudNotch.process(fastTanh(pvcPipe * 1.25f));

        out[i * 2] = fastTanh((cleanSub * 0.98f + pvcClean * 0.62f + topL * (0.76f + 0.24f * agg)) * 1.26f);
        out[i * 2 + 1] = fastTanh((cleanSub * 0.98f + pvcClean * 0.62f + topR * (0.76f + 0.24f * agg)) * 1.26f);
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

// ============================================================================
// 15. COSY_UPTEMPO (Cosy Nightmare — Dark Euphoric Melodic Uptempo Kick!)
// Silky glassy hollow tok + elastic pitched sub-bounce + zero harsh static fizz!
// Engineered to make emotional vocals & dark euphoric leads sound MASSIVE!
// ============================================================================
std::vector<float> synthCosyUptempo(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad hollowRing, mudScoop, vocalPocket, airSheen, subLpf, topHpf, topLpf;
    hollowRing.configure(BiquadType::PEAK, 980.0f, 3.6f, 8.0f + 3.2f * agg);
    mudScoop.configure(BiquadType::PEAK, 275.0f, 1.4f, -7.2f);
    vocalPocket.configure(BiquadType::PEAK, 1850.0f, 1.15f, -5.0f);
    airSheen.configure(BiquadType::PEAK, 3600.0f, 2.0f, 4.5f + 2.5f * agg);
    subLpf.configure(BiquadType::LPF, 125.0f, 0.707f);
    topHpf.configure(BiquadType::HPF, 175.0f, 0.707f);
    topLpf.configure(BiquadType::LPF, 7800.0f, 0.85f);

    OversampledClipper2x topClip;
    double bodyPh = 0.0, tokPh = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Smooth melodic pitch-bounce contour (punches down to root -> elastic lift on offbeat!)
        const float bounceBump = (u > 0.26f)
            ? std::sin(kPi * clampf((u - 0.26f) / 0.72f, 0.0f, 1.0f)) * 0.42f * agg
            : 0.0f;
        const float freq = baseHz * (1.0f + bounceBump) + 640.0f * std::exp(-t * 145.0f);
        bodyPh += kTwoPi * freq / kSampleRate;

        // Glassy hollow Cosy Nightmare tok (0..20 ms)
        const float tokHz = 340.0f + 1650.0f * std::exp(-t * 148.0f);
        tokPh += kTwoPi * tokHz / kSampleRate;
        const float tok = hollowRing.process(std::sin(tokPh) * std::exp(-t * 82.0f)) * 0.88f;

        const float env = std::min(1.0f, t / 0.002f) * (1.0f - 0.14f * u) * std::exp(-u * 0.95f);
        const float rawBody = std::sin(bodyPh) * env;
        const float cleanSub = subLpf.process(shapeCleanControlledSub(rawBody, bSat * 0.85f)) * 0.95f;

        float top = topClip.process(rawBody * 0.62f + tok * 0.92f, 1.85f + 1.50f * drv, 0.88f, 0.03f);
        top = topLpf.process(topHpf.process(airSheen.process(vocalPocket.process(mudScoop.process(top)))));

        const float y = fastTanh((cleanSub * 0.88f + top * 0.66f) * 1.12f);
        out[i * 2] = y;
        out[i * 2 + 1] = y;
    }

    sanitizeAndFinalizeKick(out, 0.94f);
    return out;
}

}  // namespace

std::vector<float> HardcoreSynth::renderKick(const KickParams& params) {
    const float bpm = clampf(params.bpm, 100.0f, 260.0f);
    const float lenBeats = clampf(params.lengthBeats, 0.15f, 2.0f);
    const float durSec = (60.0f / bpm) * lenBeats;
    const size_t nFrames = std::max<size_t>(512, static_cast<size_t>(std::round(durSec * kSampleRate)));

    const float pitchedHz = clampf(
        params.rootHz * std::pow(2.0f, params.pitchSemitoneOffset / 12.0f),
        38.0f,
        120.0f
    );

    switch (params.type) {
        case KickType::PIEP_UOK: return synthPiepUok(params, nFrames, pitchedHz);
        case KickType::KRACH_NOISEFLOW: return synthKrachNoiseflow(params, nFrames, pitchedHz);
        case KickType::ZAAG_CHAINSAW: return synthZaagChainsaw(params, nFrames, pitchedHz);
        case KickType::UPTEMPO_BOUNCE: return synthUptempoBounce(params, nFrames, pitchedHz);
        case KickType::ZAAG_PIEP_HYBRID: return synthZaagPiepHybrid(params, nFrames, pitchedHz);
        case KickType::RAWSTYLE_GATED: return synthRawstyleGated(params, nFrames, pitchedHz);
        case KickType::FRENCHCORE_MELODIC: return synthFrenchcoreMelodic(params, nFrames, pitchedHz);
        case KickType::SCHRANZ_INDUSTRIAL: return synthSchranzIndustrial(params, nFrames, pitchedHz);
        case KickType::TECHNO_ROLLING: return synthTechnoRolling(params, nFrames, pitchedHz);
        case KickType::HARD_TECHNO_RAVE: return synthHardTechnoRave(params, nFrames, pitchedHz);
        case KickType::GABBER_MILLENNIUM: return synthGabberMillennium(params, nFrames, pitchedHz);
        case KickType::ACID_303_TECHNO: return synthAcid303Techno(params, nFrames, pitchedHz);
        case KickType::HARDTEK_BOUNCE: return synthHardtekBounce(params, nFrames, pitchedHz);
        case KickType::REBELION_PVC_ZAAG: return synthRebelionPvcZaag(params, nFrames, pitchedHz);
        case KickType::COSY_UPTEMPO: return synthCosyUptempo(params, nFrames, pitchedHz);
    }
    return synthZaagChainsaw(params, nFrames, pitchedHz);
}

std::vector<float> HardcoreSynth::renderHardcoreHat(float durationSec, bool isOpen, uint32_t seed) {
    const size_t nFrames = std::max<size_t>(256, static_cast<size_t>(clampf(durationSec, 0.025f, 0.4f) * kSampleRate));
    std::vector<float> out(nFrames * 2, 0.0f);
    XorShift32 rng(seed);

    Biquad hpf, peak;
    hpf.configure(BiquadType::HPF, isOpen ? 6800.0f : 8600.0f, 1.1f);
    peak.configure(BiquadType::PEAK, 10200.0f, 2.4f, 4.5f);

    const float freqs[6] = {205.3f, 304.4f, 369.6f, 522.7f, 540.0f, 800.0f};
    double ph[6] = {};
    const float decay = isOpen ? 16.0f : 56.0f;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        float metallic = 0.0f;
        for (int k = 0; k < 6; ++k) {
            ph[k] = std::fmod(ph[k] + freqs[k] / kSampleRate, 1.0);
            metallic += (ph[k] < 0.5 ? 1.0f : -1.0f) * 0.16f;
        }
        const float src = (metallic * 0.55f + rng.nextBipolar() * 0.55f) * std::exp(-t * decay);
        const float y = std::tanh(peak.process(hpf.process(src)) * 1.5f) * 0.36f;
        out[i * 2] = y;
        out[i * 2 + 1] = y * 0.96f;
    }
    return out;
}

std::vector<float> HardcoreSynth::render909Clap(float durationSec, uint32_t seed) {
    const size_t nFrames = std::max<size_t>(256, static_cast<size_t>(clampf(durationSec, 0.08f, 0.35f) * kSampleRate));
    std::vector<float> out(nFrames * 2, 0.0f);
    XorShift32 rngL(seed), rngR(seed ^ 0x51A7u);

    Biquad bpfL, bpfR, hpfL, hpfR;
    bpfL.configure(BiquadType::BPF_PEAK, 1380.0f, 1.45f);
    bpfR.configure(BiquadType::BPF_PEAK, 1440.0f, 1.45f);
    hpfL.configure(BiquadType::HPF, 650.0f, 0.8f);
    hpfR.configure(BiquadType::HPF, 650.0f, 0.8f);

    // Classic TR-909 4-burst flam + stereo warehouse reverb tail
    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        float burstEnv = 0.0f;
        if (t < 0.036f) {
            const float flam = std::fmod(t, 0.011f);
            burstEnv = std::exp(-flam * 260.0f);
        } else {
            burstEnv = 0.82f * std::exp(-(t - 0.036f) * 28.0f);
        }
        const float l = hpfL.process(bpfL.process(rngL.nextBipolar())) * burstEnv * 1.45f;
        const float r = hpfR.process(bpfR.process(rngR.nextBipolar())) * burstEnv * 1.45f;
        out[i * 2] = clampf(std::tanh(l), -0.85f, 0.85f) * 0.55f;
        out[i * 2 + 1] = clampf(std::tanh(r), -0.85f, 0.85f) * 0.55f;
    }
    return out;
}

std::vector<float> HardcoreSynth::render909Ride(float durationSec, uint32_t seed) {
    const size_t nFrames = std::max<size_t>(256, static_cast<size_t>(clampf(durationSec, 0.08f, 0.45f) * kSampleRate));
    std::vector<float> out(nFrames * 2, 0.0f);
    XorShift32 rng(seed);

    Biquad hpf, shimmer;
    hpf.configure(BiquadType::HPF, 5200.0f, 0.9f);
    shimmer.configure(BiquadType::PEAK, 8800.0f, 2.2f, 5.0f);

    const float freqs[5] = {331.0f, 477.0f, 619.0f, 843.0f, 1187.0f};
    double ph[5] = {};
    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        float m = 0.0f;
        for (int k = 0; k < 5; ++k) {
            ph[k] = std::fmod(ph[k] + freqs[k] / kSampleRate, 1.0);
            m += (ph[k] < 0.5 ? 1.0f : -1.0f) * 0.18f;
        }
        const float src = (m * 0.65f + rng.nextBipolar() * 0.35f) * std::exp(-t * 11.5f);
        const float y = shimmer.process(hpf.process(src)) * 0.32f;
        out[i * 2] = y;
        out[i * 2 + 1] = y * 0.94f;
    }
    return out;
}

// ============================================================================
// Harmony-Safe TB-303 Acid Rave 16th-Note Riff (Root / Fifth / Octave -> ZERO clash!)
// ============================================================================
std::vector<float> HardcoreSynth::renderAcid303Riff(
    float rootHz,
    float bpm,
    int bars,
    float aggression,
    uint32_t seed
) {
    const float clampedBpm = clampf(bpm, 120.0f, 230.0f);
    const float beatSec = 60.0f / clampedBpm;
    const float stepSec = beatSec * 0.25f;
    const int totalSteps = std::max(16, bars * 16);
    const size_t totalFrames = static_cast<size_t>(std::llround(totalSteps * stepSec * kSampleRate));
    std::vector<float> out(totalFrames * 2, 0.0f);

    // Strictly consonant Root/Fifth/Octave hypnotic 303 groove -> NEVER clashes with song chords!
    static constexpr std::array<int, 16> kPattern = {
        0, 0, 12, 0,  7, 0, 12, 7,  0, 0, 12, 0,  7, 12, 7, 0
    };
    static constexpr std::array<int, 16> kAccent = {
        1, 0, 1, 0,   0, 0, 1, 0,   1, 0, 1, 0,   0, 1, 0, 0
    };

    const float acidBaseHz = clampf(rootHz * 2.0f, 75.0f, 155.0f);
    const float agg = clampf(aggression, 0.15f, 1.0f);

    Biquad resLpfL, resLpfR, hpfL, hpfR, vocalDipL, vocalDipR;
    hpfL.configure(BiquadType::HPF, 220.0f, 0.8f);
    hpfR.configure(BiquadType::HPF, 220.0f, 0.8f);
    vocalDipL.configure(BiquadType::PEAK, 2000.0f, 1.15f, -5.5f);
    vocalDipR.configure(BiquadType::PEAK, 2000.0f, 1.15f, -5.5f);

    double phL = 0.0, phR = 0.0;
    float curHz = acidBaseHz;

    for (size_t i = 0; i < totalFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float stepPos = t / stepSec;
        const int stepIdx = static_cast<int>(std::floor(stepPos)) % 16;
        const float stepFrac = stepPos - std::floor(stepPos);
        const float barProg = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, totalFrames));

        const float targetHz = acidBaseHz * std::pow(2.0f, static_cast<float>(kPattern[stepIdx]) / 12.0f);
        curHz += (targetHz - curHz) * 0.008f;

        phL = std::fmod(phL + curHz / kSampleRate, 1.0);
        phR = std::fmod(phR + (curHz * 1.004f) / kSampleRate, 1.0);
        const float sawL = 2.0f * static_cast<float>(phL) - 1.0f;
        const float sawR = 2.0f * static_cast<float>(phR) - 1.0f;

        const bool accented = (kAccent[stepIdx] != 0);
        const float env = std::min(1.0f, stepFrac * 18.0f) * std::exp(-stepFrac * (accented ? 2.6f : 4.6f));

        if (i % 24 == 0) {
            const float macroSweep = 0.35f + 0.65f * std::sin(kPi * barProg);
            const float cut = clampf(
                360.0f + (1550.0f + 1750.0f * agg) * env * (accented ? 1.25f : 0.72f) * macroSweep,
                280.0f,
                5400.0f
            );
            const float q = 3.6f + 3.4f * agg + (accented ? 1.2f : 0.0f);
            resLpfL.configure(BiquadType::LPF, cut, q);
            resLpfR.configure(BiquadType::LPF, cut * 1.03f, q);
        }

        const float beatFrac = std::fmod(t / beatSec, 1.0f);
        const float pump = 0.18f + 0.82f * clampf(beatFrac * 2.4f, 0.0f, 1.0f);

        const float yL = vocalDipL.process(hpfL.process(fastTanh(resLpfL.process(sawL) * (1.5f + 1.2f * agg)))) * env * pump * 0.38f;
        const float yR = vocalDipR.process(hpfR.process(fastTanh(resLpfR.process(sawR) * (1.5f + 1.2f * agg)))) * env * pump * 0.38f;
        out[i * 2] = yL;
        out[i * 2 + 1] = yR;
    }
    return out;
}

// ============================================================================
// Iconic Darktek / Maissouille Consonant Offbeat Hardtek Rave Organ/Brass Stab
// Strictly Root + Fifth + Octave -> bouncy, punchy, 100% harmonic!
// ============================================================================
std::vector<float> HardcoreSynth::renderHardtekOffbeatStab(
    float rootHz,
    float durationSec,
    float aggression
) {
    const float dur = clampf(durationSec, 0.07f, 0.35f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    const float baseFreq = clampf(rootHz * 4.0f, 175.0f, 350.0f);
    const float agg = clampf(aggression, 0.15f, 1.0f);

    Biquad hpfL, hpfR, lpfL, lpfR, vocDipL, vocDipR;
    hpfL.configure(BiquadType::HPF, 340.0f, 0.8f);
    hpfR.configure(BiquadType::HPF, 340.0f, 0.8f);
    vocDipL.configure(BiquadType::PEAK, 1950.0f, 1.2f, -4.5f);
    vocDipR.configure(BiquadType::PEAK, 1950.0f, 1.2f, -4.5f);

    double ph1 = 0.0, ph2 = 0.0, ph3 = 0.0, ph4 = 0.0;
    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / dur;

        ph1 = std::fmod(ph1 + (baseFreq * 0.996f) / kSampleRate, 1.0);
        ph2 = std::fmod(ph2 + (baseFreq * 1.004f) / kSampleRate, 1.0);
        ph3 = std::fmod(ph3 + (baseFreq * 1.498f) / kSampleRate, 1.0); // perfect fifth
        ph4 = std::fmod(ph4 + (baseFreq * 2.002f) / kSampleRate, 1.0); // octave

        if ((i & 15u) == 0u) {
            const float cut = clampf(850.0f + (2600.0f + 1600.0f * agg) * std::exp(-u * 6.5f), 600.0f, 5600.0f);
            lpfL.configure(BiquadType::LPF, cut, 1.8f);
            lpfR.configure(BiquadType::LPF, cut * 1.04f, 1.8f);
        }

        const float env = std::min(1.0f, t / 0.0025f) * std::exp(-u * 4.4f);
        const float l = ((2.0f * static_cast<float>(ph1) - 1.0f) * 0.55f
                       + (2.0f * static_cast<float>(ph3) - 1.0f) * 0.42f
                       + (2.0f * static_cast<float>(ph4) - 1.0f) * 0.36f);
        const float r = ((2.0f * static_cast<float>(ph2) - 1.0f) * 0.55f
                       + (2.0f * static_cast<float>(ph3) - 1.0f) * 0.42f
                       + (2.0f * static_cast<float>(ph4) - 1.0f) * 0.36f);

        const float m = 0.5f * (l + r) * 0.65f;
        const float s = 0.5f * (l - r) * 1.40f;
        out[i * 2] = vocDipL.process(hpfL.process(lpfL.process(fastTanh((m + s) * 1.35f)))) * env * 0.48f;
        out[i * 2 + 1] = vocDipR.process(hpfR.process(lpfR.process(fastTanh((m - s) * 1.35f)))) * env * 0.48f;
    }
    return out;
}

// ============================================================================
// Harmony-Safe Euphoric Stereo Supersaw Shimmer (Dr. Peacock / Sefa / Cosy)
// Uses pure consonant Root + Fifth + Octave wide-stereo shimmer so it SOARS
// effortlessly over ANY song progression without EVER clashing with vocals/chords!
// ============================================================================
std::vector<float> HardcoreSynth::renderEuphoricChordLayer(
    float rootHz,
    bool /*isMinor*/,
    float bpm,
    int bars
) {
    const float clampedBpm = clampf(bpm, 140.0f, 230.0f);
    const float beatSec = 60.0f / clampedBpm;
    const float barSec = beatSec * 4.0f;
    const int nBars = std::max(4, bars);
    const size_t totalFrames = static_cast<size_t>(std::llround(nBars * barSec * kSampleRate));
    std::vector<float> out(totalFrames * 2, 0.0f);

    // Pure open-fifth + double-octave wide stereo shimmer (universally consonant in major & minor!)
    static constexpr std::array<int, 3> kOpenNotes = {0, 7, 12};

    const float padBaseHz = clampf(rootHz * 4.0f, 165.0f, 310.0f);
    Biquad hpfL, hpfR, lpfL, lpfR, vocPocketL, vocPocketR;
    hpfL.configure(BiquadType::HPF, 310.0f, 0.707f);
    hpfR.configure(BiquadType::HPF, 310.0f, 0.707f);
    lpfL.configure(BiquadType::LPF, 4800.0f, 0.75f);
    lpfR.configure(BiquadType::LPF, 4800.0f, 0.75f);
    vocPocketL.configure(BiquadType::PEAK, 1750.0f, 1.05f, -6.5f);
    vocPocketR.configure(BiquadType::PEAK, 1750.0f, 1.05f, -6.5f);

    std::array<double, 12> ph{};
    static constexpr std::array<float, 4> kDetune = {0.992f, 0.997f, 1.003f, 1.008f};
    std::array<float, 3> noteFreqs{};
    for (int n = 0; n < 3; ++n) {
        noteFreqs[n] = padBaseHz * std::pow(2.0f, static_cast<float>(kOpenNotes[n]) / 12.0f);
    }

    for (size_t i = 0; i < totalFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float beatFrac = std::fmod(t / beatSec, 1.0f);
        // Deep pumping offbeat Frenchcore sidechain breath!
        const float sc = 0.14f + 0.86f * clampf((beatFrac - 0.12f) * 2.4f, 0.0f, 1.0f);

        float sL = 0.0f, sR = 0.0f;
        for (int n = 0; n < 3; ++n) {
            const float fNote = noteFreqs[n];
            for (int v = 0; v < 4; ++v) {
                const int idx = n * 4 + v;
                ph[idx] = std::fmod(ph[idx] + (fNote * kDetune[v]) / kSampleRate, 1.0);
                const float saw = 2.0f * static_cast<float>(ph[idx]) - 1.0f;
                if (v % 2 == 0) sL += saw * 0.07f;
                else sR += saw * 0.07f;
            }
        }

        // Push supersaw wide to the stereo sides so center vocals own 100% of the middle!
        const float m = 0.5f * (sL + sR) * 0.42f;
        const float s = 0.5f * (sL - sR) * 1.55f;
        out[i * 2] = vocPocketL.process(lpfL.process(hpfL.process(m + s))) * sc * 0.46f;
        out[i * 2 + 1] = vocPocketR.process(lpfR.process(hpfR.process(m - s))) * sc * 0.46f;
    }
    return out;
}

// ============================================================================
// Classic Gabber / Hardcore Detuned Hoover Rave Stab (Angerfist / Neophyte)
// ============================================================================
std::vector<float> HardcoreSynth::renderRaveHooverStab(
    float rootHz,
    float durationSec,
    float aggression
) {
    const float dur = clampf(durationSec, 0.15f, 1.6f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    const float baseFreq = clampf(rootHz * 4.0f, 165.0f, 330.0f);
    const float agg = clampf(aggression, 0.1f, 1.0f);

    Biquad hpfL, hpfR, lpfL, lpfR;
    hpfL.configure(BiquadType::HPF, 280.0f, 0.8f);
    hpfR.configure(BiquadType::HPF, 280.0f, 0.8f);
    lpfL.configure(BiquadType::LPF, 4600.0f, 1.5f);
    lpfR.configure(BiquadType::LPF, 4600.0f, 1.5f);

    double ph1 = 0.0, ph2 = 0.0, ph3 = 0.0, ph4 = 0.0;
    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / dur;
        const float bend = 1.0f + 0.42f * std::exp(-u * 6.5f) - 0.12f * u;
        const float f = baseFreq * bend;
        ph1 = std::fmod(ph1 + (f * 0.991f) / kSampleRate, 1.0);
        ph2 = std::fmod(ph2 + (f * 1.009f) / kSampleRate, 1.0);
        ph3 = std::fmod(ph3 + (f * 1.498f) / kSampleRate, 1.0);
        ph4 = std::fmod(ph4 + (f * 2.004f) / kSampleRate, 1.0);

        const float env = std::min(1.0f, u * 30.0f) * std::exp(-u * 2.8f);
        const float l = ((2.0f * static_cast<float>(ph1) - 1.0f) + (2.0f * static_cast<float>(ph3) - 1.0f) * 0.65f + (2.0f * static_cast<float>(ph4) - 1.0f) * 0.45f) * 0.35f;
        const float r = ((2.0f * static_cast<float>(ph2) - 1.0f) + (2.0f * static_cast<float>(ph3) - 1.0f) * 0.65f + (2.0f * static_cast<float>(ph4) - 1.0f) * 0.45f) * 0.35f;

        out[i * 2] = fastTanh(lpfL.process(hpfL.process(l)) * (1.7f + 1.3f * agg)) * env * 0.52f;
        out[i * 2 + 1] = fastTanh(lpfR.process(hpfR.process(r)) * (1.7f + 1.3f * agg)) * env * 0.52f;
    }
    return out;
}

// ============================================================================
// Festival Reverse-Crash Suck-In (leading straight into Beat 1 of Drops/Switches!)
// ============================================================================
std::vector<float> HardcoreSynth::renderReverseCrash(float durationSec, uint32_t seed) {
    const float dur = clampf(durationSec, 0.25f, 2.0f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    XorShift32 rngL(seed), rngR(seed ^ 0xA7C3u);

    Biquad hpfL, hpfR, airL, airR;
    hpfL.configure(BiquadType::HPF, 2800.0f, 0.85f);
    hpfR.configure(BiquadType::HPF, 2950.0f, 0.85f);
    airL.configure(BiquadType::PEAK, 7800.0f, 1.8f, 5.5f);
    airR.configure(BiquadType::PEAK, 8200.0f, 1.8f, 5.5f);

    for (size_t i = 0; i < nFrames; ++i) {
        const float u = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, nFrames));
        // Steep exponential suck-in peaking right at the final millisecond before Beat 1!
        const float env = std::pow(u, 3.2f) * clampf((1.0f - u) * 70.0f, 0.0f, 1.0f);
        const float l = airL.process(hpfL.process(rngL.nextBipolar())) * env * 0.48f;
        const float r = airR.process(hpfR.process(rngR.nextBipolar())) * env * 0.48f;
        out[i * 2] = clampf(l, -0.82f, 0.82f);
        out[i * 2 + 1] = clampf(r, -0.82f, 0.82f);
    }
    return out;
}

std::vector<float> HardcoreSynth::renderKickPreviewLoop(
    KickType type,
    float rootHz,
    float bpm,
    float drive,
    float aggression,
    float bassSaturation
) {
    const float clampedBpm = clampf(bpm, 124.0f, 240.0f);
    const float beatSec = 60.0f / clampedBpm;
    const size_t beatFrames = static_cast<size_t>(std::round(beatSec * kSampleRate));
    const size_t totalFrames = beatFrames * 4;
    std::vector<float> out(totalFrames * 2, 0.0f);

    KickParams baseP;
    baseP.type = type;
    baseP.rootHz = clampf(rootHz, 41.2f, 73.5f);
    baseP.bpm = clampedBpm;
    baseP.drive = clampf(drive, 0.1f, 1.0f);
    baseP.aggression = clampf(aggression, 0.1f, 1.0f);
    baseP.bassSaturation = clampf(bassSaturation, 0.0f, 1.0f);
    baseP.lengthBeats = 1.0f;

    const auto k0 = renderKick(baseP);
    KickParams pitchedP = baseP;
    pitchedP.pitchSemitoneOffset = (type == KickType::TECHNO_ROLLING || type == KickType::HARD_TECHNO_RAVE) ? 0.0f : 3.0f;
    const auto kTurn = renderKick(pitchedP);

    const auto hat = renderHardcoreHat(beatSec * 0.42f, true, 909u);
    const auto clap = render909Clap(0.14f, 9091u);

    for (int b = 0; b < 4; ++b) {
        const auto& k = (b == 3) ? kTurn : k0;
        const size_t start = static_cast<size_t>(b) * beatFrames;
        const size_t kFrames = k.size() / 2;
        for (size_t i = 0; i < kFrames && (start + i) < totalFrames; ++i) {
            out[(start + i) * 2] += k[i * 2];
            out[(start + i) * 2 + 1] += k[i * 2 + 1];
        }

        const size_t hatStart = start + beatFrames / 2;
        const size_t hatFrames = hat.size() / 2;
        for (size_t i = 0; i < hatFrames && (hatStart + i) < totalFrames; ++i) {
            out[(hatStart + i) * 2] += hat[i * 2] * 0.42f;
            out[(hatStart + i) * 2 + 1] += hat[i * 2 + 1] * 0.42f;
        }

        if ((b == 1 || b == 3) && (type == KickType::TECHNO_ROLLING || type == KickType::HARD_TECHNO_RAVE ||
                                   type == KickType::ACID_303_TECHNO || type == KickType::GABBER_MILLENNIUM ||
                                   type == KickType::HARDTEK_BOUNCE)) {
            const size_t cFrames = clap.size() / 2;
            for (size_t i = 0; i < cFrames && (start + i) < totalFrames; ++i) {
                out[(start + i) * 2] += clap[i * 2] * 0.55f;
                out[(start + i) * 2 + 1] += clap[i * 2 + 1] * 0.55f;
            }
        }
    }

    for (size_t i = 0; i < totalFrames; ++i) {
        out[i * 2] = clampf(fastTanh(out[i * 2] * 1.02f), -0.96f, 0.96f);
        out[i * 2 + 1] = clampf(fastTanh(out[i * 2 + 1] * 1.02f), -0.96f, 0.96f);
    }
    return out;
}

std::vector<float> HardcoreSynth::renderIndustrialScreech(
    float rootHz,
    float durationSec,
    float aggression,
    int variant
) {
    const float dur = clampf(durationSec, 0.12f, 2.5f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    const float agg = clampf(aggression, 0.1f, 1.0f);

    const float baseFreq = clampf(rootHz * 8.0f, 330.0f, 660.0f);

    Biquad bpfL, bpfR, hpfL, hpfR;
    hpfL.configure(BiquadType::HPF, 420.0f, 0.8f);
    hpfR.configure(BiquadType::HPF, 420.0f, 0.8f);
    OversampledClipper2x clipL, clipR;

    double ph1 = 0.0, ph2 = 0.0, ph3 = 0.0;
    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / dur;

        const float bend = (variant % 2 == 0)
            ? (1.0f + 1.65f * std::exp(-u * 5.8f) - 0.22f * u)
            : (0.88f + 0.92f * u * u + 0.06f * std::sin(kTwoPi * 16.0f * t));

        const float f1 = baseFreq * bend;
        const float f2 = f1 * 1.016f;
        const float f3 = f1 * 1.498f;

        ph1 = std::fmod(ph1 + f1 / kSampleRate, 1.0);
        ph2 = std::fmod(ph2 + f2 / kSampleRate, 1.0);
        ph3 = std::fmod(ph3 + f3 / kSampleRate, 1.0);

        const float sL = (2.0f * static_cast<float>(ph1) - 1.0f) * 0.55f
                       + (ph3 < 0.32 ? 1.0f : -1.0f) * 0.45f;
        const float sR = (2.0f * static_cast<float>(ph2) - 1.0f) * 0.55f
                       + (ph3 < 0.68 ? 1.0f : -1.0f) * 0.45f;

        if (i % 32 == 0) {
            const float cutL = clampf(950.0f + 3200.0f * (1.0f - u) + 520.0f * std::sin(kTwoPi * 7.0f * t), 550.0f, 6400.0f);
            const float cutR = clampf(cutL * 1.07f, 550.0f, 6800.0f);
            bpfL.configure(BiquadType::BPF_PEAK, cutL, 4.4f + 2.8f * agg);
            bpfR.configure(BiquadType::BPF_PEAK, cutR, 4.4f + 2.8f * agg);
        }

        const float env = std::min(1.0f, u * 24.0f) * clampf(1.0f - (u - 0.72f) / 0.28f, 0.0f, 1.0f);
        float yL = hpfL.process(wavefold(bpfL.process(sL) * 2.2f + sL * 0.30f, 2.0f + 2.0f * agg));
        float yR = hpfR.process(wavefold(bpfR.process(sR) * 2.2f + sR * 0.30f, 2.0f + 2.0f * agg));

        out[i * 2] = clipL.process(yL * env, 1.55f, 0.86f, 0.03f);
        out[i * 2 + 1] = clipR.process(yR * env, 1.55f, 0.86f, 0.03f);
    }
    return out;
}

std::vector<float> HardcoreSynth::renderSnareBuildupRoll(
    float bpm,
    float durationSec,
    float rootHz,
    float aggression,
    float preDropBreathBeats
) {
    const float clampedBpm = clampf(bpm, 120.0f, 240.0f);
    const float dur = clampf(durationSec, 0.5f, 24.0f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    const float beatSec = 60.0f / clampedBpm;

    // Leave exact Pre-Drop Vocal Solo Breath (0..2 beats of dead drum silence right before Beat 1!)
    const float breathSec = std::min(dur * 0.28f, std::max(0.0f, preDropBreathBeats) * beatSec);
    const float activeDur = std::max(0.25f, dur - breathSec);

    XorShift32 rngL(0xB01D0001u), rngR(0xB01D0002u);

    float tCursor = 0.0f;
    while (tCursor < activeDur - 0.015f) {
        const float progress = clampf(tCursor / activeDur, 0.0f, 1.0f);
        float stepBeats = 1.0f;
        if (progress > 0.84f) stepBeats = 0.125f;
        else if (progress > 0.58f) stepBeats = 0.25f;
        else if (progress > 0.28f) stepBeats = 0.5f;

        const size_t hitStart = static_cast<size_t>(tCursor * kSampleRate);
        const float hitDur = std::min(0.11f, stepBeats * beatSec * 0.88f);
        const size_t hitFrames = static_cast<size_t>(hitDur * kSampleRate);

        const float pitchMul = std::pow(2.0f, (progress * 12.0f) / 12.0f);
        const float bodyHz = clampf(rootHz * 3.0f * pitchMul, 155.0f, 820.0f);
        const float amp = 0.34f + 0.44f * progress;

        double ph = 0.0;
        for (size_t j = 0; j < hitFrames && (hitStart + j) < nFrames; ++j) {
            const float lt = static_cast<float>(j) / kSampleRate;
            const float f = bodyHz + 320.0f * std::exp(-lt * 95.0f);
            ph += kTwoPi * f / kSampleRate;
            const float env = std::exp(-lt * (30.0f + 26.0f * progress));
            const float tone = std::sin(ph) * 0.58f;
            const float snapL = rngL.nextBipolar() * (0.36f + 0.24f * aggression);
            const float snapR = rngR.nextBipolar() * (0.36f + 0.24f * aggression);
            out[(hitStart + j) * 2] += fastTanh((tone + snapL) * (1.45f + 0.85f * progress)) * env * amp;
            out[(hitStart + j) * 2 + 1] += fastTanh((tone + snapR) * (1.45f + 0.85f * progress)) * env * amp;
        }
        tCursor += stepBeats * beatSec;
    }

    // Crisp studio bandpass + hard cutoff right at the Pre-Drop Vocal Solo Breath!
    Biquad hpfL, hpfR, lpfL, lpfR;
    lpfL.configure(BiquadType::LPF, 8500.0f, 0.75f);
    lpfR.configure(BiquadType::LPF, 8500.0f, 0.75f);
    const size_t cutoffFrame = static_cast<size_t>(activeDur * kSampleRate);
    for (size_t i = 0; i < nFrames; ++i) {
        if (i % 64 == 0) {
            const float p = clampf(static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, cutoffFrame)), 0.0f, 1.0f);
            const float cut = 220.0f + 1350.0f * p * p;
            hpfL.configure(BiquadType::HPF, cut, 1.15f);
            hpfR.configure(BiquadType::HPF, cut, 1.15f);
        }
        const float gate = (i < cutoffFrame) ? 1.0f : clampf(1.0f - static_cast<float>(i - cutoffFrame) / 440.0f, 0.0f, 1.0f);
        out[i * 2] = clampf(lpfL.process(hpfL.process(out[i * 2])) * gate, -0.88f, 0.88f);
        out[i * 2 + 1] = clampf(lpfR.process(hpfR.process(out[i * 2 + 1])) * gate, -0.88f, 0.88f);
    }
    return out;
}

std::vector<float> HardcoreSynth::renderNoiseRiser(
    float durationSec,
    float rootHz,
    float aggression,
    float preDropBreathSec
) {
    const float dur = clampf(durationSec, 0.5f, 24.0f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    XorShift32 rngL(0x52495345u), rngR(0x52495346u);

    const float breathSec = std::min(dur * 0.28f, std::max(0.0f, preDropBreathSec));
    const size_t activeFrames = std::max<size_t>(441, static_cast<size_t>((dur - breathSec) * kSampleRate));

    Biquad bpfL, bpfR;
    double tonePh = 0.0;
    for (size_t i = 0; i < activeFrames && i < nFrames; ++i) {
        const float u = static_cast<float>(i) / static_cast<float>(activeFrames);
        if (i % 64 == 0) {
            const float cut = 400.0f * std::pow(18.0f, u);
            bpfL.configure(BiquadType::BPF_PEAK, cut, 2.2f + 1.2f * aggression);
            bpfR.configure(BiquadType::BPF_PEAK, cut * 1.05f, 2.2f + 1.2f * aggression);
        }
        // Subtle harmonic octave shimmer (clean sine — zero harsh buzzing saw!)
        const float toneHz = clampf(rootHz * 4.0f * std::pow(2.0f, u * 2.0f), 175.0f, 2400.0f);
        tonePh += kTwoPi * toneHz / kSampleRate;
        const float shimmer = std::sin(tonePh) * 0.14f;

        const float env = u * u * clampf((1.0f - u) * 45.0f, 0.0f, 1.0f);
        const float nL = bpfL.process(rngL.nextBipolar() * 0.85f + shimmer) * env * 0.52f;
        const float nR = bpfR.process(rngR.nextBipolar() * 0.85f + shimmer) * env * 0.52f;
        out[i * 2] = clampf(nL, -0.80f, 0.80f);
        out[i * 2 + 1] = clampf(nR, -0.80f, 0.80f);
    }
    return out;
}

std::vector<float> HardcoreSynth::renderSubImpact(float rootHz, float durationSec) {
    const float dur = clampf(durationSec, 0.35f, 3.0f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    XorShift32 rngL(0x149A0701u), rngR(0x149A0702u);

    Biquad crashHpfL, crashHpfR;
    crashHpfL.configure(BiquadType::HPF, 4200.0f, 0.9f);
    crashHpfR.configure(BiquadType::HPF, 4400.0f, 0.9f);
    double ph = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / dur;
        const float subHz = clampf(rootHz * (1.7f * std::exp(-u * 4.5f) + 0.65f), 32.0f, 130.0f);
        ph += kTwoPi * subHz / kSampleRate;

        const float sub = fastTanh(std::sin(ph) * 1.40f) * std::exp(-u * 3.8f) * 0.58f;
        const float cL = crashHpfL.process(rngL.nextBipolar()) * std::exp(-u * 4.2f) * 0.38f;
        const float cR = crashHpfR.process(rngR.nextBipolar()) * std::exp(-u * 4.2f) * 0.38f;
        out[i * 2] = clampf(sub + cL, -0.88f, 0.88f);
        out[i * 2 + 1] = clampf(sub + cR, -0.88f, 0.88f);
    }
    return out;
}

}  // namespace zaagforge
