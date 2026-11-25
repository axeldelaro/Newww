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
// 1. PIEP_UOK (Unicorn On Ketamine / GPF)
// ============================================================================
std::vector<float> synthPiepUok(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad piepBpf, formant1, formant2, subLpf, topHpf, postPeak, topSafetyHpf;
    const float piepCenter = 1650.0f + 1100.0f * agg;
    const float piepQ = 9.0f + 3.0f * agg;
    piepBpf.configure(BiquadType::BPF_PEAK, piepCenter, piepQ);
    formant1.configure(BiquadType::PEAK, 850.0f, 3.8f, 10.0f + 4.5f * agg);
    formant2.configure(BiquadType::PEAK, 2300.0f, 4.5f, 11.0f + 5.0f * agg);
    subLpf.configure(BiquadType::LPF, 120.0f, 0.707f);
    topHpf.configure(BiquadType::HPF, 300.0f, 0.85f);
    topSafetyHpf.configure(BiquadType::HPF, 155.0f, 0.707f);
    postPeak.configure(BiquadType::PEAK, piepCenter * 0.95f, 3.2f, 7.5f + 4.0f * agg);

    OversampledClipper2x topClipper;
    double piepPhase = 0.0, subPhase = 0.0, chirpPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        const float sweepFreq = 520.0f + 3680.0f * std::exp(-t * (135.0f + 65.0f * agg));
        piepPhase += kTwoPi * sweepFreq / kSampleRate;

        const float chirpFreq = piepCenter * (1.0f + 0.35f * std::exp(-t * 90.0f));
        chirpPhase += kTwoPi * chirpFreq / kSampleRate;

        const float normPhase = static_cast<float>(std::fmod(piepPhase / kTwoPi, 1.0));
        const float sawPiep = 2.0f * normPhase - 1.0f;
        const float piepSrc = (std::sin(piepPhase) * 0.65f + sawPiep * 0.45f + std::sin(chirpPhase) * 0.40f)
                            * std::exp(-u * (1.85f - 0.35f * agg));

        float body = piepBpf.process(piepSrc) * (4.0f + 2.6f * agg) + piepSrc * 0.50f;
        body = formant1.process(body);
        body = formant2.process(body);

        float s1 = wavefold(body, 2.0f + 2.4f * drv);
        float s2 = hardClipAsym(s1, 2.4f + 2.2f * drv, 0.82f, 0.96f, 0.07f);
        float s3 = postPeak.process(s2);
        float piepLayer = topSafetyHpf.process(topClipper.process(topHpf.process(s3), 1.85f + 1.85f * drv, 0.92f, 0.05f));

        const float subFreq = baseHz + (baseHz * 1.40f) * std::exp(-t * 98.0f);
        subPhase += kTwoPi * subFreq / kSampleRate;
        const float subEnv = std::min(1.0f, t / 0.008f) * std::exp(-u * 2.0f);
        const float subClean = subLpf.process(shapeCleanControlledSub(std::sin(subPhase) * subEnv, bSat)) * 0.86f;

        const float stereoDiff = std::sin(chirpPhase * 0.5) * piepLayer * 0.06f;
        const float monoCore = subClean * 0.72f + piepLayer * (0.64f + 0.22f * agg);

        out[i * 2] = std::tanh((monoCore + stereoDiff) * 1.06f);
        out[i * 2 + 1] = std::tanh((monoCore - stereoDiff) * 1.06f);
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
// 7. FRENCHCORE_MELODIC (Sefa / Dr. Peacock / Billx — True Euphoric Frenchcore!)
// Signature snappy hollow Frenchcore tok + bouncy offbeat reverse-tail swell +
// crystal-clean pitched harmonic bass with dedicated vocal/melody pocket!
// ============================================================================
std::vector<float> synthFrenchcoreMelodic(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float bSat = clampf(p.bassSaturation, 0.0f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad tokBody, vocalPocket, lowPunch, clickAir, subLpf, topHpf;
    tokBody.configure(BiquadType::PEAK, 420.0f, 2.2f, 6.5f + 2.5f * agg);
    vocalPocket.configure(BiquadType::PEAK, 1650.0f, 1.1f, -5.5f); // huge clean window for vocals & violins!
    lowPunch.configure(BiquadType::PEAK, std::max(78.0f, baseHz * 2.0f), 1.5f, 4.5f);
    clickAir.configure(BiquadType::PEAK, 3800.0f, 1.9f, 6.0f + 3.0f * agg);
    subLpf.configure(BiquadType::LPF, 128.0f, 0.707f);
    topHpf.configure(BiquadType::HPF, 140.0f, 0.707f);

    double phase = 0.0, harmPhase = 0.0, tokPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Tight Sefa/Peacock pitch drop locking rapidly onto musical bass note!
        const float freq = baseHz + 820.0f * std::exp(-t * 150.0f);
        phase += kTwoPi * freq / kSampleRate;
        harmPhase += kTwoPi * (freq * 2.0f) / kSampleRate;

        // Iconic woody Frenchcore tok transient (0..22 ms)
        const float tokHz = 280.0f + 2400.0f * std::exp(-t * 155.0f);
        tokPhase += kTwoPi * tokHz / kSampleRate;
        const float tok = std::sin(tokPhase) * std::exp(-t * 88.0f) * 0.85f;

        // Bouncy Frenchcore tail envelope (tight dip right after tok -> huge offbeat musical swell!)
        const float attackPunch = std::exp(-u * 5.2f);
        const float offbeatSwell = std::sin(kPi * clampf((u - 0.14f) / 0.84f, 0.0f, 1.0f)) * 0.92f;
        const float env = std::min(1.0f, t / 0.002f) * std::max(attackPunch, offbeatSwell);

        const float cleanSub = subLpf.process(shapeCleanControlledSub(std::sin(phase) * env, bSat)) * 0.90f;
        const float rawMid = (std::sin(phase) * 0.72f + std::sin(harmPhase) * 0.34f) * env + tok;
        float top = softClipTanh(rawMid * (2.0f + 1.8f * drv) + 0.05f) - 0.04f;
        top = topHpf.process(clickAir.process(vocalPocket.process(tokBody.process(lowPunch.process(top)))));

        const float y = std::tanh((cleanSub * 0.78f + top * 0.68f) * 1.05f);
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

        const float y = std::tanh((sub * 0.84f + top) * 1.04f);
        out[i * 2] = y;
        out[i * 2 + 1] = y;
    }

    sanitizeAndFinalizeKick(out, 0.93f);
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
// Key-Locked TB-303 Acid Rave 16th-Note Riff (Sara Landry / 999999999 / Nico Moreno)
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

    // Acid minor/phrygian 16-step hypnotic pattern (semitones from root + octave jumps)
    static constexpr std::array<int, 16> kPattern = {
        0, 0, 12, 0,  3, 0, 7, 12,  0, 1, 0, 12,  7, 3, 12, 10
    };
    static constexpr std::array<int, 16> kAccent = {
        1, 0, 1, 0,   0, 0, 1, 1,   0, 1, 0, 1,   0, 0, 1, 0
    };

    const float acidBaseHz = clampf(rootHz * 2.0f, 75.0f, 155.0f);
    const float agg = clampf(aggression, 0.15f, 1.0f);

    Biquad resLpfL, resLpfR, hpfL, hpfR, vocalDipL, vocalDipR;
    hpfL.configure(BiquadType::HPF, 210.0f, 0.8f); // stay strictly above sub kick!
    hpfR.configure(BiquadType::HPF, 210.0f, 0.8f);
    vocalDipL.configure(BiquadType::PEAK, 2100.0f, 1.2f, -4.0f); // leave clean space for vocals!
    vocalDipR.configure(BiquadType::PEAK, 2100.0f, 1.2f, -4.0f);

    double phL = 0.0, phR = 0.0;
    float curHz = acidBaseHz;

    for (size_t i = 0; i < totalFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float stepPos = t / stepSec;
        const int stepIdx = static_cast<int>(std::floor(stepPos)) % 16;
        const float stepFrac = stepPos - std::floor(stepPos);
        const float barProg = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, totalFrames));

        const float targetHz = acidBaseHz * std::pow(2.0f, static_cast<float>(kPattern[stepIdx]) / 12.0f);
        // Smooth 303 slide glide on accented notes
        curHz += (targetHz - curHz) * 0.008f;

        phL = std::fmod(phL + curHz / kSampleRate, 1.0);
        phR = std::fmod(phR + (curHz * 1.004f) / kSampleRate, 1.0);
        const float sawL = 2.0f * static_cast<float>(phL) - 1.0f;
        const float sawR = 2.0f * static_cast<float>(phR) - 1.0f;

        const bool accented = (kAccent[stepIdx] != 0);
        const float env = std::min(1.0f, stepFrac * 16.0f) * std::exp(-stepFrac * (accented ? 2.4f : 4.2f));

        if (i % 24 == 0) {
            const float macroSweep = 0.35f + 0.65f * std::sin(kPi * barProg);
            const float cut = clampf(
                360.0f + (1650.0f + 1900.0f * agg) * env * (accented ? 1.25f : 0.72f) * macroSweep,
                280.0f,
                5800.0f
            );
            const float q = 3.8f + 3.8f * agg + (accented ? 1.4f : 0.0f);
            resLpfL.configure(BiquadType::LPF, cut, q);
            resLpfR.configure(BiquadType::LPF, cut * 1.03f, q);
        }

        // Duck gently on beat 1/2/3/4 kicks so groove pumps cleanly
        const float beatFrac = std::fmod(t / beatSec, 1.0f);
        const float pump = 0.25f + 0.75f * clampf(beatFrac * 2.5f, 0.0f, 1.0f);

        const float yL = vocalDipL.process(hpfL.process(std::tanh(resLpfL.process(sawL) * (1.6f + 1.4f * agg)))) * env * pump * 0.42f;
        const float yR = vocalDipR.process(hpfR.process(std::tanh(resLpfR.process(sawR) * (1.6f + 1.4f * agg)))) * env * pump * 0.42f;
        out[i * 2] = yL;
        out[i * 2 + 1] = yR;
    }
    return out;
}

// ============================================================================
// Euphoric Supersaw Frenchcore Chord Layer (Sefa / Dr. Peacock)
// Wide stereo orchestral supersaw pads locked to the song's key with a surgical
// center vocal pocket so epic drops soar while lyrics stay 100% upfront!
// ============================================================================
std::vector<float> HardcoreSynth::renderEuphoricChordLayer(
    float rootHz,
    bool isMinor,
    float bpm,
    int bars
) {
    const float clampedBpm = clampf(bpm, 140.0f, 230.0f);
    const float beatSec = 60.0f / clampedBpm;
    const float barSec = beatSec * 4.0f;
    const int nBars = std::max(4, bars);
    const size_t totalFrames = static_cast<size_t>(std::llround(nBars * barSec * kSampleRate));
    std::vector<float> out(totalFrames * 2, 0.0f);

    // Classic euphoric 4-bar progression (i - VI - III - VII in minor, I - V - vi - IV in major)
    const std::array<std::array<int, 3>, 4> prog = isMinor
        ? std::array<std::array<int, 3>, 4>{{
            {0, 3, 7}, {-4, 0, 3}, {3, 7, 10}, {-2, 2, 5}
        }}
        : std::array<std::array<int, 3>, 4>{{
            {0, 4, 7}, {7, 11, 14}, {9, 12, 16}, {5, 9, 12}
        }};

    const float padBaseHz = clampf(rootHz * 4.0f, 165.0f, 310.0f);
    Biquad hpfL, hpfR, lpfL, lpfR, vocPocketL, vocPocketR;
    hpfL.configure(BiquadType::HPF, 260.0f, 0.707f);
    hpfR.configure(BiquadType::HPF, 260.0f, 0.707f);
    lpfL.configure(BiquadType::LPF, 5200.0f, 0.8f);
    lpfR.configure(BiquadType::LPF, 5200.0f, 0.8f);
    // Carve clean 1.6 kHz pocket so vocals float effortlessly above the euphoric chords!
    vocPocketL.configure(BiquadType::PEAK, 1650.0f, 1.1f, -5.0f);
    vocPocketR.configure(BiquadType::PEAK, 1650.0f, 1.1f, -5.0f);

    std::array<double, 12> ph{};
    static constexpr std::array<float, 4> kDetune = {0.992f, 0.997f, 1.003f, 1.008f};

    for (size_t i = 0; i < totalFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const int barIdx = static_cast<int>(std::floor(t / barSec)) % 4;
        const float beatFrac = std::fmod(t / beatSec, 1.0f);
        const float sc = 0.28f + 0.72f * clampf(beatFrac * 2.2f, 0.0f, 1.0f);

        float sL = 0.0f, sR = 0.0f;
        for (int n = 0; n < 3; ++n) {
            const float fNote = padBaseHz * std::pow(2.0f, static_cast<float>(prog[barIdx][n]) / 12.0f);
            for (int v = 0; v < 4; ++v) {
                const int idx = n * 4 + v;
                ph[idx] = std::fmod(ph[idx] + (fNote * kDetune[v]) / kSampleRate, 1.0);
                const float saw = 2.0f * static_cast<float>(ph[idx]) - 1.0f;
                if (v % 2 == 0) sL += saw * 0.075f;
                else sR += saw * 0.075f;
            }
        }

        // Wide stereo euphoric shimmer (pushes chords to the sides so center vocals own the middle!)
        const float m = 0.5f * (sL + sR) * 0.65f;
        const float s = 0.5f * (sL - sR) * 1.45f;
        out[i * 2] = vocPocketL.process(lpfL.process(hpfL.process(m + s))) * sc * 0.52f;
        out[i * 2 + 1] = vocPocketR.process(lpfR.process(hpfR.process(m - s))) * sc * 0.52f;
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
    hpfL.configure(BiquadType::HPF, 260.0f, 0.8f);
    hpfR.configure(BiquadType::HPF, 260.0f, 0.8f);
    lpfL.configure(BiquadType::LPF, 4800.0f, 1.6f);
    lpfR.configure(BiquadType::LPF, 4800.0f, 1.6f);

    double ph1 = 0.0, ph2 = 0.0, ph3 = 0.0, ph4 = 0.0;
    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / dur;
        // Classic PWM Hoover pitch swoop
        const float bend = 1.0f + 0.42f * std::exp(-u * 6.5f) - 0.12f * u;
        const float f = baseFreq * bend;
        ph1 = std::fmod(ph1 + (f * 0.991f) / kSampleRate, 1.0);
        ph2 = std::fmod(ph2 + (f * 1.009f) / kSampleRate, 1.0);
        ph3 = std::fmod(ph3 + (f * 1.498f) / kSampleRate, 1.0); // fifth
        ph4 = std::fmod(ph4 + (f * 2.004f) / kSampleRate, 1.0); // octave

        const float env = std::min(1.0f, u * 30.0f) * std::exp(-u * 2.6f);
        const float l = ((2.0f * static_cast<float>(ph1) - 1.0f) + (2.0f * static_cast<float>(ph3) - 1.0f) * 0.65f + (2.0f * static_cast<float>(ph4) - 1.0f) * 0.45f) * 0.35f;
        const float r = ((2.0f * static_cast<float>(ph2) - 1.0f) + (2.0f * static_cast<float>(ph3) - 1.0f) * 0.65f + (2.0f * static_cast<float>(ph4) - 1.0f) * 0.45f) * 0.35f;

        out[i * 2] = std::tanh(lpfL.process(hpfL.process(l)) * (1.8f + 1.4f * agg)) * env * 0.56f;
        out[i * 2 + 1] = std::tanh(lpfR.process(hpfR.process(r)) * (1.8f + 1.4f * agg)) * env * 0.56f;
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
    const float clampedBpm = clampf(bpm, 125.0f, 240.0f);
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

        if ((b == 1 || b == 3) && (type == KickType::TECHNO_ROLLING || type == KickType::HARD_TECHNO_RAVE || type == KickType::ACID_303_TECHNO || type == KickType::GABBER_MILLENNIUM)) {
            const size_t cFrames = clap.size() / 2;
            for (size_t i = 0; i < cFrames && (start + i) < totalFrames; ++i) {
                out[(start + i) * 2] += clap[i * 2] * 0.55f;
                out[(start + i) * 2 + 1] += clap[i * 2 + 1] * 0.55f;
            }
        }
    }

    for (size_t i = 0; i < totalFrames; ++i) {
        out[i * 2] = clampf(std::tanh(out[i * 2] * 1.02f), -0.96f, 0.96f);
        out[i * 2 + 1] = clampf(std::tanh(out[i * 2 + 1] * 1.02f), -0.96f, 0.96f);
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
    hpfL.configure(BiquadType::HPF, 360.0f, 0.8f);
    hpfR.configure(BiquadType::HPF, 360.0f, 0.8f);
    OversampledClipper2x clipL, clipR;

    double ph1 = 0.0, ph2 = 0.0, ph3 = 0.0;
    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / dur;

        const float bend = (variant % 2 == 0)
            ? (1.0f + 1.85f * std::exp(-u * 5.2f) - 0.25f * u)
            : (0.85f + 0.95f * u * u + 0.08f * std::sin(kTwoPi * 18.0f * t));

        const float f1 = baseFreq * bend;
        const float f2 = f1 * 1.018f;
        const float f3 = f1 * 1.498f;

        ph1 = std::fmod(ph1 + f1 / kSampleRate, 1.0);
        ph2 = std::fmod(ph2 + f2 / kSampleRate, 1.0);
        ph3 = std::fmod(ph3 + f3 / kSampleRate, 1.0);

        const float sL = (2.0f * static_cast<float>(ph1) - 1.0f) * 0.55f
                       + (ph3 < 0.32 ? 1.0f : -1.0f) * 0.45f;
        const float sR = (2.0f * static_cast<float>(ph2) - 1.0f) * 0.55f
                       + (ph3 < 0.68 ? 1.0f : -1.0f) * 0.45f;

        if (i % 32 == 0) {
            const float cutL = clampf(900.0f + 3600.0f * (1.0f - u) + 600.0f * std::sin(kTwoPi * 7.0f * t), 500.0f, 7000.0f);
            const float cutR = clampf(cutL * 1.08f, 500.0f, 7500.0f);
            bpfL.configure(BiquadType::BPF_PEAK, cutL, 4.8f + 3.2f * agg);
            bpfR.configure(BiquadType::BPF_PEAK, cutR, 4.8f + 3.2f * agg);
        }

        const float env = std::min(1.0f, u * 22.0f) * clampf(1.0f - (u - 0.72f) / 0.28f, 0.0f, 1.0f);
        float yL = hpfL.process(wavefold(bpfL.process(sL) * 2.4f + sL * 0.35f, 2.2f + 2.4f * agg));
        float yR = hpfR.process(wavefold(bpfR.process(sR) * 2.4f + sR * 0.35f, 2.2f + 2.4f * agg));

        out[i * 2] = clipL.process(yL * env, 1.65f, 0.88f, 0.04f);
        out[i * 2 + 1] = clipR.process(yR * env, 1.65f, 0.88f, 0.04f);
    }
    return out;
}

std::vector<float> HardcoreSynth::renderSnareBuildupRoll(
    float bpm,
    float durationSec,
    float rootHz,
    float aggression
) {
    const float clampedBpm = clampf(bpm, 120.0f, 240.0f);
    const float dur = clampf(durationSec, 0.5f, 24.0f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    const float beatSec = 60.0f / clampedBpm;
    XorShift32 rng(0xB01D0001u);

    float tCursor = 0.0f;
    while (tCursor < dur - 0.02f) {
        const float progress = tCursor / dur;
        float stepBeats = 1.0f;
        if (progress > 0.82f) stepBeats = 0.125f;
        else if (progress > 0.56f) stepBeats = 0.25f;
        else if (progress > 0.28f) stepBeats = 0.5f;

        const size_t hitStart = static_cast<size_t>(tCursor * kSampleRate);
        const float hitDur = std::min(0.14f, stepBeats * beatSec * 0.92f);
        const size_t hitFrames = static_cast<size_t>(hitDur * kSampleRate);

        const float pitchMul = std::pow(2.0f, (progress * 14.0f) / 12.0f);
        const float bodyHz = clampf(rootHz * 3.0f * pitchMul, 140.0f, 920.0f);
        const float amp = 0.38f + 0.48f * progress;

        double ph = 0.0;
        for (size_t j = 0; j < hitFrames && (hitStart + j) < nFrames; ++j) {
            const float lt = static_cast<float>(j) / kSampleRate;
            const float f = bodyHz + 380.0f * std::exp(-lt * 80.0f);
            ph += kTwoPi * f / kSampleRate;
            const float env = std::exp(-lt * (24.0f + 22.0f * progress));
            const float tone = std::sin(ph) * 0.62f;
            const float snap = rng.nextBipolar() * (0.42f + 0.32f * aggression);
            const float s = std::tanh((tone + snap) * (1.8f + 1.4f * progress)) * env * amp;
            out[(hitStart + j) * 2] += s;
            out[(hitStart + j) * 2 + 1] += s;
        }
        tCursor += stepBeats * beatSec;
    }

    Biquad hpfL, hpfR;
    for (size_t i = 0; i < nFrames; ++i) {
        if (i % 64 == 0) {
            const float p = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, nFrames));
            const float cut = 150.0f + 1150.0f * p * p;
            hpfL.configure(BiquadType::HPF, cut, 1.25f);
            hpfR.configure(BiquadType::HPF, cut, 1.25f);
        }
        out[i * 2] = clampf(std::tanh(hpfL.process(out[i * 2]) * 1.15f), -0.92f, 0.92f);
        out[i * 2 + 1] = clampf(std::tanh(hpfR.process(out[i * 2 + 1]) * 1.15f), -0.92f, 0.92f);
    }
    return out;
}

std::vector<float> HardcoreSynth::renderNoiseRiser(float durationSec, float rootHz, float aggression) {
    const float dur = clampf(durationSec, 0.5f, 24.0f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    XorShift32 rngL(0x52495345u), rngR(0x52495346u);

    Biquad bpfL, bpfR;
    double tonePh = 0.0;
    for (size_t i = 0; i < nFrames; ++i) {
        const float u = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, nFrames));
        if (i % 64 == 0) {
            const float cut = 320.0f * std::pow(26.0f, u);
            bpfL.configure(BiquadType::BPF_PEAK, cut, 2.8f + 1.8f * aggression);
            bpfR.configure(BiquadType::BPF_PEAK, cut * 1.04f, 2.8f + 1.8f * aggression);
        }
        const float toneHz = clampf(rootHz * 4.0f * std::pow(2.0f, u * 2.0f), 160.0f, 2800.0f);
        tonePh = std::fmod(tonePh + toneHz / kSampleRate, 1.0);
        const float siren = (2.0f * static_cast<float>(tonePh) - 1.0f) * 0.22f;

        const float env = u * u * clampf((1.0f - u) * 35.0f, 0.0f, 1.0f);
        const float nL = bpfL.process(rngL.nextBipolar() + siren) * env * 0.65f;
        const float nR = bpfR.process(rngR.nextBipolar() + siren) * env * 0.65f;
        out[i * 2] = clampf(std::tanh(nL * 1.45f), -0.88f, 0.88f);
        out[i * 2 + 1] = clampf(std::tanh(nR * 1.45f), -0.88f, 0.88f);
    }
    return out;
}

std::vector<float> HardcoreSynth::renderSubImpact(float rootHz, float durationSec) {
    const float dur = clampf(durationSec, 0.35f, 3.0f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    XorShift32 rng(0x149A0701u);

    Biquad crashHpf;
    crashHpf.configure(BiquadType::HPF, 3600.0f, 0.9f);
    double ph = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / dur;
        const float subHz = clampf(rootHz * (1.7f * std::exp(-u * 4.5f) + 0.65f), 32.0f, 130.0f);
        ph += kTwoPi * subHz / kSampleRate;

        const float sub = std::tanh(std::sin(ph) * 1.45f) * std::exp(-u * 3.5f) * 0.62f;
        const float crash = crashHpf.process(rng.nextBipolar()) * std::exp(-u * 4.2f) * 0.36f;
        out[i * 2] = clampf(sub + crash, -0.88f, 0.88f);
        out[i * 2 + 1] = clampf(sub + crash * 0.92f, -0.88f, 0.88f);
    }
    return out;
}

}  // namespace zaagforge
