#include "hardcore_kicks.h"
#include "dsp_utils.h"

#include <algorithm>
#include <cmath>

namespace zaagforge {

namespace {

// Clean micro fade-in/out to prevent clicks at kick boundaries
void applyBoundaryFades(std::vector<float>& stereo, size_t fadeInFrames = 12, size_t fadeOutFrames = 96) {
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

void sanitizeAndFinalizeKick(std::vector<float>& stereo, float targetPeak = 0.96f) {
    const size_t totalFrames = stereo.size() / 2;
    if (totalFrames == 0) return;

    // Remove any DC offset introduced by asymmetric clipping
    Biquad dcL, dcR;
    dcL.configure(BiquadType::HPF, 24.0f, 0.707f);
    dcR.configure(BiquadType::HPF, 24.0f, 0.707f);

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
        s = clampf(sanitize(s * norm), -0.98f, 0.98f);
    }
    applyBoundaryFades(stereo, 8, 88);
}

// ============================================================================
// 1. PIEP_UOK (Unicorn On Ketamine / GPF)
// Ultra-fast 4200 -> 520 Hz pitch sweep, hyper-resonant BPF (1.6-2.8 kHz Q=9-12),
// nasal formants (850 Hz + 2300 Hz), 3-stage asymmetric wavefold/hard-clip,
// tight mono sub punch (~rootHz).
// ============================================================================
std::vector<float> synthPiepUok(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad piepBpf, formant1, formant2, subLpf, topHpf, postPeak;
    const float piepCenter = 1650.0f + 1100.0f * agg; // 1.65 - 2.75 kHz
    const float piepQ = 9.0f + 3.0f * agg;            // Q = 9..12
    piepBpf.configure(BiquadType::BPF_PEAK, piepCenter, piepQ);
    formant1.configure(BiquadType::PEAK, 850.0f, 3.8f, 11.0f + 5.0f * agg);
    formant2.configure(BiquadType::PEAK, 2300.0f, 4.5f, 12.0f + 6.0f * agg);
    subLpf.configure(BiquadType::LPF, 125.0f, 0.707f);
    topHpf.configure(BiquadType::HPF, 340.0f, 0.85f);
    postPeak.configure(BiquadType::PEAK, piepCenter * 0.95f, 3.2f, 8.0f + 4.0f * agg);

    OversampledClipper2x clipper;
    double piepPhase = 0.0;
    double subPhase = 0.0;
    double chirpPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Ultra-fast exponential pitch sweep: 4200 Hz -> 520 Hz
        const float sweepFreq = 520.0f + 3680.0f * std::exp(-t * (135.0f + 65.0f * agg));
        piepPhase += kTwoPi * sweepFreq / kSampleRate;

        // Secondary resonant scream harmonics (hollow nasal piep buzz)
        const float chirpFreq = piepCenter * (1.0f + 0.35f * std::exp(-t * 90.0f));
        chirpPhase += kTwoPi * chirpFreq / kSampleRate;

        // Raw piep oscillator: bright impulse-rich saw + sine chirp
        const float normPhase = static_cast<float>(std::fmod(piepPhase / kTwoPi, 1.0));
        const float sawPiep = 2.0f * normPhase - 1.0f;
        const float piepSrc = (std::sin(piepPhase) * 0.65f + sawPiep * 0.45f + std::sin(chirpPhase) * 0.40f)
                            * std::exp(-u * (1.85f - 0.35f * agg));

        // Hyper-resonant bandpass + dual nasal formant shaping
        float body = piepBpf.process(piepSrc) * (4.2f + 2.8f * agg) + piepSrc * 0.55f;
        body = formant1.process(body);
        body = formant2.process(body);

        // 3-Stage Asymmetric Wavefold + Hard-Clip chain
        float s1 = wavefold(body, 2.2f + 2.6f * drv);
        float s2 = hardClipAsym(s1, 2.6f + 2.4f * drv, 0.82f, 0.98f, 0.09f);
        float s3 = postPeak.process(s2);
        float piepLayer = clipper.process(topHpf.process(s3), 2.0f + 2.2f * drv, 0.96f, 0.07f);

        // Tight phase-aligned mono sub punch (~52 Hz or key-locked rootHz)
        const float subFreq = baseHz + (baseHz * 1.45f) * std::exp(-t * 95.0f);
        subPhase += kTwoPi * subFreq / kSampleRate;
        // Brief punch pause during first 10ms transient so the piep cracks cleanly, then full sub slam
        const float subEnv = std::min(1.0f, t / 0.008f) * std::exp(-u * 2.1f);
        const float subRaw = std::tanh(std::sin(subPhase) * (1.6f + 1.1f * drv)) * subEnv;
        const float subClean = subLpf.process(subRaw) * 0.92f;

        // Micro stereo sheen on upper piep harmonics only
        const float stereoDiff = std::sin(chirpPhase * 0.5) * piepLayer * 0.07f;
        const float monoCore = subClean * 0.78f + piepLayer * (0.76f + 0.24f * agg);

        out[i * 2] = std::tanh((monoCore + stereoDiff) * 1.35f);
        out[i * 2 + 1] = std::tanh((monoCore - stereoDiff) * 1.35f);
    }

    sanitizeAndFinalizeKick(out, 0.96f);
    return out;
}

// ============================================================================
// 2. KRACH_NOISEFLOW (Noiseflow / Deutscher Krach)
// Inharmonic FM metallic transient (1.0x / 1.414x / 2.73x), high-feedback short
// metallic comb resonator (2.3-4.0 ms), brutal wavefold/bit-crunch mid-wall,
// distorted industrial sub tail.
// ============================================================================
std::vector<float> synthKrachNoiseflow(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;
    XorShift32 rng(p.seed ^ 0x4B524348u);

    CombResonator combL(512), combR(512);
    Biquad metallicBpf, subLpf, midHpf, crunchPeak;
    metallicBpf.configure(BiquadType::PEAK, 2100.0f + 1200.0f * agg, 2.8f, 10.0f + 5.0f * agg);
    subLpf.configure(BiquadType::LPF, 135.0f, 0.707f);
    midHpf.configure(BiquadType::HPF, 220.0f, 0.85f);
    crunchPeak.configure(BiquadType::PEAK, 3400.0f, 2.2f, 8.0f + 4.0f * agg);

    OversampledClipper2x clipL, clipR;

    // Comb delay in 2.3..4.0 ms -> metallic industrial clang
    const float delayMsL = 2.3f + (1.0f - agg) * 1.55f;
    const float delayMsR = delayMsL * 1.045f; // subtle industrial stereo metallic spread
    const float delaySampL = delayMsL * 0.001f * kSampleRate;
    const float delaySampR = delayMsR * 0.001f * kSampleRate;
    const float combFb = 0.80f + 0.11f * agg;

    double phCar = 0.0, phMod1 = 0.0, phMod2 = 0.0, phSub = 0.0;
    float heldCrushL = 0.0f, heldCrushR = 0.0f;
    const int srReduce = (agg > 0.65f) ? 3 : 2;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Inharmonic FM metallic transient: 1.0x / 1.414x / 2.73x ratios
        const float f0 = 260.0f + 1650.0f * std::exp(-t * 58.0f);
        phMod1 += kTwoPi * (f0 * 1.41421356f) / kSampleRate;
        phMod2 += kTwoPi * (f0 * 2.73f) / kSampleRate;
        const float fmIndex = (5.5f + 5.0f * agg) * std::exp(-t * 16.0f) + 1.4f * agg;
        phCar += kTwoPi * f0 / kSampleRate;

        const float fmMetal = std::sin(phCar + fmIndex * (std::sin(phMod1) * 0.65f + std::sin(phMod2) * 0.55f));
        const float noiseSnap = rng.nextBipolar() * std::exp(-t * 75.0f) * 0.85f;
        const float excite = (fmMetal * std::exp(-u * 1.4f) + noiseSnap) * 0.85f;

        // High-feedback short metallic comb resonators (L & R)
        float clangL = combL.process(excite, delaySampL, combFb, 0.16f) + excite * 0.55f;
        float clangR = combR.process(excite, delaySampR, combFb, 0.16f) + excite * 0.55f;

        // Brutal wavefold + bit-crunch mid-wall
        clangL = wavefold(metallicBpf.process(clangL), 2.8f + 3.6f * drv);
        clangR = wavefold(metallicBpf.process(clangR), 2.8f + 3.6f * drv);

        if (i % static_cast<size_t>(srReduce) == 0) {
            const float bits = 6.5f - 2.2f * agg; // 4.3..6.5 bit industrial crunch
            heldCrushL = bitCrushSample(clangL, bits);
            heldCrushR = bitCrushSample(clangR, bits);
        }
        const float wallL = midHpf.process(crunchPeak.process(clangL * 0.62f + heldCrushL * 0.48f));
        const float wallR = midHpf.process(crunchPeak.process(clangR * 0.62f + heldCrushR * 0.48f));

        // Distorted industrial sub tail locked to rootHz
        const float subFreq = baseHz + (baseHz * 2.1f) * std::exp(-t * 72.0f);
        phSub += kTwoPi * subFreq / kSampleRate;
        const float subEnv = std::exp(-u * 1.65f);
        const float subDist = hardClipAsym(std::sin(phSub) * subEnv, 2.6f + 2.0f * drv, 0.86f, 0.96f, 0.08f);
        const float subMono = subLpf.process(subDist) * 0.95f;

        const float mixL = subMono * 0.82f + wallL * (0.80f + 0.25f * agg);
        const float mixR = subMono * 0.82f + wallR * (0.80f + 0.25f * agg);

        out[i * 2] = clipL.process(mixL, 1.9f + 1.8f * drv, 0.96f, 0.05f);
        out[i * 2 + 1] = clipR.process(mixR, 1.9f + 1.8f * drv, 0.96f, 0.05f);
    }

    sanitizeAndFinalizeKick(out, 0.96f);
    return out;
}

// ============================================================================
// 3. ZAAG_CHAINSAW (Pure Zaagkick)
// Phase-aligned clean mono sine sub (< 110 Hz) fused under pitch-swept HPF
// sawtooth wall pushed through 3 cascaded stages: resonant mid bell (450 Hz +
// 1.6 kHz) -> asymmetric hard-clip/wavefold -> formant comb -> 3-band OTT
// compression -> brickwall clipper!
// ============================================================================
std::vector<float> synthZaagChainsaw(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad zaagHpf, bellLow, bellHigh, subScoop, subLpf, airShelf;
    zaagHpf.configure(BiquadType::HPF, 180.0f, 0.85f);
    bellLow.configure(BiquadType::PEAK, 450.0f, 2.4f, 9.5f + 4.5f * agg);
    bellHigh.configure(BiquadType::PEAK, 1600.0f, 2.2f, 11.0f + 5.0f * agg);
    subScoop.configure(BiquadType::NOTCH, 245.0f, 2.2f);
    subLpf.configure(BiquadType::LPF, 110.0f, 0.707f);
    airShelf.configure(BiquadType::HIGH_SHELF, 4200.0f, 0.707f, 5.5f + 3.5f * agg);

    CombResonator formantComb(512);
    Ott3Band ott;
    ott.configure(125.0f, 2400.0f);
    OversampledClipper2x clipL, clipR;

    const float combDelay = (1.55f + 0.55f * (1.0f - agg)) * 0.001f * kSampleRate;

    double subPhase = 0.0;
    double sawPh1 = 0.0, sawPh2 = 0.0, sawPh3 = 0.0;
    double tokPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Phase-aligned clean mono sine sub (< 110 Hz)
        const float subSweep = baseHz + std::min(55.0f, baseHz) * std::exp(-t * 90.0f);
        subPhase += kTwoPi * subSweep / kSampleRate;
        const float subEnv = std::min(1.0f, t / 0.004f) * (1.0f - 0.35f * u) * std::exp(-u * 1.15f);
        const float cleanSub = subLpf.process(std::sin(subPhase) * subEnv) * 1.05f;

        // Crisp initial tok click (0..18 ms)
        const float tokFreq = 180.0f + 2200.0f * std::exp(-t * 140.0f);
        tokPhase += kTwoPi * tokFreq / kSampleRate;
        const float tok = std::sin(tokPhase) * std::exp(-t * 95.0f) * 0.75f;

        // Pitch-swept high-passed sawtooth wall (chainsaw buzz!)
        const float sawBase = baseHz * 2.0f + 240.0f * std::exp(-t * 36.0f);
        sawPh1 = std::fmod(sawPh1 + sawBase / kSampleRate, 1.0);
        sawPh2 = std::fmod(sawPh2 + (sawBase * 1.007f) / kSampleRate, 1.0);
        sawPh3 = std::fmod(sawPh3 + (sawBase * 1.995f) / kSampleRate, 1.0);
        const float sawWall = ((2.0f * static_cast<float>(sawPh1) - 1.0f) * 0.55f
                             + (2.0f * static_cast<float>(sawPh2) - 1.0f) * 0.45f
                             + (2.0f * static_cast<float>(sawPh3) - 1.0f) * 0.35f)
                            * (0.95f - 0.25f * u);

        // Stage 1: HPF saw wall + resonant mid bells (450 Hz + 1.6 kHz) -> asym hard-clip/wavefold
        float z = zaagHpf.process(sawWall + tok);
        z = bellLow.process(z);
        z = bellHigh.process(z);
        z = hardClipAsym(z, 3.0f + 3.2f * drv, 0.78f, 0.98f, 0.11f);
        z = wavefold(z, 1.8f + 2.2f * agg);

        // Stage 2: Formant comb + sub-scoop -> 2nd asymmetric clip
        const float combed = formantComb.process(z, combDelay, 0.62f + 0.18f * agg, 0.22f);
        z = subScoop.process(z * 0.65f + combed * 0.55f);
        z = hardClipAsym(z, 2.2f + 2.0f * drv, 0.85f, 0.95f, -0.07f);
        z = airShelf.process(z);

        // Subtle chainsaw stereo micro-width on upper buzz only
        const float buzzL = z + (2.0f * static_cast<float>(sawPh2) - 1.0f) * 0.08f;
        const float buzzR = z - (2.0f * static_cast<float>(sawPh2) - 1.0f) * 0.08f;

        // Fuse phase-aligned mono sub under chainsaw wall
        float l = cleanSub * 0.88f + buzzL * (0.72f + 0.26f * agg);
        float r = cleanSub * 0.88f + buzzR * (0.72f + 0.26f * agg);

        // Stage 3: 3-band OTT multiband compression -> oversampled brickwall clipper
        ott.processStereo(l, r, 0.72f, 1.0f, 1.08f);
        out[i * 2] = clipL.process(l, 1.75f + 1.45f * drv, 0.96f, 0.04f);
        out[i * 2 + 1] = clipR.process(r, 1.75f + 1.45f * drv, 0.96f, 0.04f);
    }

    sanitizeAndFinalizeKick(out, 0.96f);
    return out;
}

// ============================================================================
// 4. UPTEMPO_BOUNCE (Raw Bounce Uptempo)
// Crisp hollow tok transient + pitch-bouncing resonant low-mid tail
// (110 -> rootHz -> 92 Hz bounce!) + heavy saturation.
// ============================================================================
std::vector<float> synthUptempoBounce(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad tokPeak, bounceRes, subTight, hiCrunch;
    tokPeak.configure(BiquadType::PEAK, 960.0f, 3.6f, 10.0f + 4.0f * agg);
    bounceRes.configure(BiquadType::PEAK, 190.0f, 2.4f, 6.5f + 3.5f * agg);
    subTight.configure(BiquadType::HPF, 34.0f, 0.707f);
    hiCrunch.configure(BiquadType::PEAK, 2400.0f, 1.8f, 6.0f + 4.0f * agg);
    OversampledClipper2x clipper;

    double bodyPhase = 0.0;
    double tokPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Signature Uptempo pitch bounce: 115 Hz -> drops to rootHz (~55-62 Hz) -> bounces UP to ~94 Hz!
        float tailHz = baseHz;
        if (u < 0.28f) {
            const float a = u / 0.28f;
            tailHz = 112.0f + (baseHz - 112.0f) * (1.0f - std::exp(-a * 4.5f));
        } else {
            const float b = (u - 0.28f) / 0.72f;
            // Smooth resonant upward bounce hump peaking around u=0.68
            const float bounceBump = std::sin(kPi * clampf(b * 1.15f, 0.0f, 1.0f));
            const float targetTop = std::max(baseHz * 1.58f, 92.0f);
            tailHz = baseHz + (targetTop - baseHz) * bounceBump * (0.65f + 0.35f * agg);
        }
        // Plus fast attack punch sweep
        const float freq = tailHz + 520.0f * std::exp(-t * 115.0f);
        bodyPhase += kTwoPi * freq / kSampleRate;

        // Crisp hollow tok transient
        const float tokHz = 260.0f + 1350.0f * std::exp(-t * 135.0f);
        tokPhase += kTwoPi * tokHz / kSampleRate;
        const float tok = tokPeak.process(std::sin(tokPhase) * std::exp(-t * 68.0f)) * 0.95f;

        // Tail envelope with slight duck after tok for bouncy pocket
        const float tailEnv = (0.35f + 0.65f * clampf((u - 0.06f) * 5.0f, 0.0f, 1.0f))
                            * (1.0f - 0.22f * u) * std::exp(-u * 1.25f);
        const float body = bounceRes.process(std::sin(bodyPhase) * tailEnv);

        float x = tok * 0.90f + body * 1.15f;
        x = hardClipAsym(x, 2.8f + 3.2f * drv, 0.80f, 0.96f, 0.08f);
        x = hiCrunch.process(x);
        x = subTight.process(x);
        const float y = clipper.process(x, 1.8f + 1.6f * drv, 0.96f, 0.04f);

        out[i * 2] = y;
        out[i * 2 + 1] = y;
    }

    sanitizeAndFinalizeKick(out, 0.96f);
    return out;
}

// ============================================================================
// 5. ZAAG_PIEP_HYBRID
// Screeching Piep attack morphing seamlessly into buzzing sawtooth Zaag tail!
// ============================================================================
std::vector<float> synthZaagPiepHybrid(const KickParams& p, size_t nFrames, float baseHz) {
    auto piep = synthPiepUok(p, nFrames, baseHz);
    auto zaag = synthZaagChainsaw(p, nFrames, baseHz);
    std::vector<float> out(nFrames * 2, 0.0f);

    OversampledClipper2x clipL, clipR;
    for (size_t i = 0; i < nFrames; ++i) {
        const float u = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, nFrames));
        // Piep dominates punch/attack (0..26%), morphs smoothly (20%..38%) into chainsaw Zaag tail
        const float zaagWeight = clampf((u - 0.18f) / 0.20f, 0.0f, 1.0f);
        const float smoothZaag = zaagWeight * zaagWeight * (3.0f - 2.0f * zaagWeight);
        const float piepWeight = 1.0f - smoothZaag * 0.78f; // keep subtle piep formant edge

        const float l = piep[i * 2] * piepWeight + zaag[i * 2] * (0.25f + 0.88f * smoothZaag);
        const float r = piep[i * 2 + 1] * piepWeight + zaag[i * 2 + 1] * (0.25f + 0.88f * smoothZaag);

        out[i * 2] = clipL.process(l, 1.35f, 0.96f, 0.03f);
        out[i * 2 + 1] = clipR.process(r, 1.35f, 0.96f, 0.03f);
    }

    sanitizeAndFinalizeKick(out, 0.96f);
    return out;
}

// ============================================================================
// 6. RAWSTYLE_GATED
// Distorted punch + gated industrial metallic reverb tail + sharp klaplong snap!
// ============================================================================
std::vector<float> synthRawstyleGated(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;
    XorShift32 rng(p.seed ^ 0x52415747u);

    CombResonator rev1(2048), rev2(2048), rev3(2048);
    Biquad klapBpf, revBand, punchEq, subLpf;
    klapBpf.configure(BiquadType::BPF_PEAK, 2450.0f, 4.2f);
    revBand.configure(BiquadType::LPF, 2800.0f + 1600.0f * agg, 0.9f);
    punchEq.configure(BiquadType::PEAK, 720.0f, 2.2f, 8.5f + 4.0f * agg);
    subLpf.configure(BiquadType::LPF, 145.0f, 0.707f);
    OversampledClipper2x clipL, clipR;

    double punchPhase = 0.0, subPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Hard Rawstyle tok punch
        const float pFreq = baseHz * 1.8f + 1750.0f * std::exp(-t * 95.0f);
        punchPhase += kTwoPi * pFreq / kSampleRate;
        const float punch = punchEq.process(std::sin(punchPhase) * std::exp(-t * 36.0f));

        // Sharp klaplong transient snap (crisp resonant pop at onset)
        const float klapExc = (rng.nextBipolar() * 0.7f + std::sin(punchPhase * 2.5)) * std::exp(-t * 115.0f);
        const float klaplong = klapBpf.process(klapExc) * (2.6f + 1.8f * agg);

        // Industrial multi-comb metallic reverb excited by punch + klap
        const float revIn = hardClipAsym(punch + klaplong * 0.6f, 2.8f + 2.2f * drv, 0.85f, 0.95f, 0.06f);
        const float r1 = rev1.process(revIn, 297.0f, 0.76f, 0.24f);
        const float r2 = rev2.process(revIn, 419.0f, 0.78f, 0.22f);
        const float r3 = rev3.process(revIn, 563.0f, 0.74f, 0.26f);

        // Tight industrial gate slams shut cleanly at u = 0.80..0.90!
        const float gate = (u < 0.78f) ? 1.0f : clampf(1.0f - (u - 0.78f) / 0.12f, 0.0f, 1.0f);
        const float gatedRevL = revBand.process((r1 * 0.5f + r2 * 0.4f + r3 * 0.35f)) * gate;
        const float gatedRevR = revBand.process((r1 * 0.35f + r2 * 0.5f + r3 * 0.4f)) * gate;

        // Heavy Rawstyle sub tail
        const float subFreq = baseHz + baseHz * 0.8f * std::exp(-t * 60.0f);
        subPhase += kTwoPi * subFreq / kSampleRate;
        const float sub = subLpf.process(std::tanh(std::sin(subPhase) * (2.2f + 1.5f * drv)) * gate * std::exp(-u * 0.95f));

        const float l = sub * 0.90f + punch * 0.82f + klaplong * 0.55f + gatedRevL * (0.72f + 0.28f * agg);
        const float r = sub * 0.90f + punch * 0.82f + klaplong * 0.55f + gatedRevR * (0.72f + 0.28f * agg);

        out[i * 2] = clipL.process(l, 1.8f + 1.5f * drv, 0.96f, 0.04f);
        out[i * 2 + 1] = clipR.process(r, 1.8f + 1.5f * drv, 0.96f, 0.04f);
    }

    sanitizeAndFinalizeKick(out, 0.96f);
    return out;
}

// ============================================================================
// 7. FRENCHCORE_MELODIC
// Driving pitched hardcore kick with warm harmonic crunch preserving melodic leads.
// ============================================================================
std::vector<float> synthFrenchcoreMelodic(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;

    Biquad melodyPocket, lowPunch, clickPeak;
    // Carve gentle 1.1 kHz pocket so euphoric Frenchcore leads shine cleanly over the kick!
    melodyPocket.configure(BiquadType::PEAK, 1150.0f, 1.2f, -4.5f);
    lowPunch.configure(BiquadType::PEAK, std::max(70.0f, baseHz * 2.0f), 1.6f, 5.5f);
    clickPeak.configure(BiquadType::PEAK, 3200.0f, 2.0f, 6.0f + 3.5f * agg);
    OversampledClipper2x clipper;

    double phase = 0.0;
    double harmPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Tight Frenchcore pitch curve locking quickly onto exact musical rootHz!
        const float freq = baseHz + 780.0f * std::exp(-t * 145.0f);
        phase += kTwoPi * freq / kSampleRate;
        harmPhase += kTwoPi * (freq * 2.0f) / kSampleRate;

        // Crisp Frenchcore click
        const float click = std::sin(kTwoPi * (2600.0f * t) * std::exp(-t * 60.0f)) * std::exp(-t * 165.0f) * 0.72f;

        // Strong pitched musical body with warm 2nd harmonic
        const float env = std::min(1.0f, t / 0.002f) * std::exp(-u * 1.45f);
        const float body = (std::sin(phase) * 0.88f + std::sin(harmPhase) * 0.24f) * env;

        // Warm tube-style asymmetric saturation
        float x = softClipTanh((body + click) * (2.6f + 2.4f * drv) + 0.09f) - 0.08f;
        x = lowPunch.process(x);
        x = melodyPocket.process(x);
        x = clickPeak.process(x);
        const float y = clipper.process(x, 1.55f + 1.15f * drv, 0.95f, 0.03f);

        out[i * 2] = y;
        out[i * 2 + 1] = y;
    }

    sanitizeAndFinalizeKick(out, 0.95f);
    return out;
}

// ============================================================================
// 8. SCHRANZ_INDUSTRIAL
// Dark rolling industrial rumble kick + metallic distorted percussion texture.
// ============================================================================
std::vector<float> synthSchranzIndustrial(const KickParams& p, size_t nFrames, float baseHz) {
    std::vector<float> out(nFrames * 2, 0.0f);
    const float drv = clampf(p.drive, 0.1f, 1.0f);
    const float agg = clampf(p.aggression, 0.1f, 1.0f);
    const float durSec = static_cast<float>(nFrames) / kSampleRate;
    XorShift32 rng(p.seed ^ 0x53434852u);

    CombResonator rumbleComb1(4096), rumbleComb2(4096);
    Biquad rumbleLpf, darkTilt, metalBpf;
    rumbleLpf.configure(BiquadType::LPF, 138.0f, 1.25f);
    darkTilt.configure(BiquadType::LOW_SHELF, 220.0f, 0.8f, 5.5f);
    metalBpf.configure(BiquadType::BPF_PEAK, 1850.0f, 3.2f);
    OversampledClipper2x clipL, clipR;

    double kickPhase = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / std::max(durSec, 0.01f);

        // Deep 909-inspired distorted industrial thump
        const float kFreq = baseHz + 360.0f * std::exp(-t * 68.0f);
        kickPhase += kTwoPi * kFreq / kSampleRate;
        const float thump = std::tanh(std::sin(kickPhase) * (2.6f + 2.0f * drv)) * std::exp(-u * 3.1f);

        // Dark rolling industrial rumble tail (delayed comb-reverb sub sidechained against kick onset!)
        const float r1 = rumbleComb1.process(thump, 1320.0f, 0.72f, 0.45f);
        const float r2 = rumbleComb2.process(thump, 1980.0f, 0.70f, 0.48f);
        const float rumbleSidechain = clampf((u - 0.15f) * 3.8f, 0.0f, 1.0f);
        const float rumble = rumbleLpf.process(std::tanh((r1 + r2) * 2.2f)) * rumbleSidechain * 0.95f;

        // 16th-note Schranz metallic industrial percussion grit (4 pulses per beat)
        const float sub16 = std::fmod(u * 4.0f, 1.0f);
        const float accent16 = (u > 0.22f) ? 1.0f : 0.3f;
        const float percNoise = rng.nextBipolar() * std::exp(-sub16 * 16.0f) * accent16;
        const float metalGrit = hardClipAsym(metalBpf.process(percNoise), 3.0f + 2.5f * agg, 0.75f, 0.85f, 0.04f);

        const float core = darkTilt.process(thump * 0.95f + rumble * 0.88f);
        const float l = core + metalGrit * (0.28f + 0.22f * agg);
        const float r = core + metalGrit * (0.22f + 0.28f * agg);

        out[i * 2] = clipL.process(l, 1.75f + 1.35f * drv, 0.96f, 0.04f);
        out[i * 2 + 1] = clipR.process(r, 1.75f + 1.35f * drv, 0.96f, 0.04f);
    }

    sanitizeAndFinalizeKick(out, 0.96f);
    return out;
}

}  // namespace

std::vector<float> HardcoreSynth::renderKick(const KickParams& params) {
    const float bpm = clampf(params.bpm, 100.0f, 260.0f);
    const float lenBeats = clampf(params.lengthBeats, 0.15f, 2.0f);
    const float durSec = (60.0f / bpm) * lenBeats;
    const size_t nFrames = std::max<size_t>(512, static_cast<size_t>(std::round(durSec * kSampleRate)));

    // Apply melodic pitch kick offset (in semitones) while keeping sub musical
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
    }
    return synthZaagChainsaw(params, nFrames, pitchedHz);
}

std::vector<float> HardcoreSynth::renderHardcoreHat(float durationSec, bool isOpen, uint32_t seed) {
    const size_t nFrames = std::max<size_t>(256, static_cast<size_t>(clampf(durationSec, 0.03f, 0.4f) * kSampleRate));
    std::vector<float> out(nFrames * 2, 0.0f);
    XorShift32 rng(seed);

    Biquad hpf, peak;
    hpf.configure(BiquadType::HPF, isOpen ? 6800.0f : 8200.0f, 1.1f);
    peak.configure(BiquadType::PEAK, 10200.0f, 2.4f, 6.0f);

    // 6-oscillator 909-style metallic square cluster + noise
    const float freqs[6] = {205.3f, 304.4f, 369.6f, 522.7f, 540.0f, 800.0f};
    double ph[6] = {};
    const float decay = isOpen ? 16.0f : 48.0f;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        float metallic = 0.0f;
        for (int k = 0; k < 6; ++k) {
            ph[k] = std::fmod(ph[k] + freqs[k] / kSampleRate, 1.0);
            metallic += (ph[k] < 0.5 ? 1.0f : -1.0f) * 0.16f;
        }
        const float src = (metallic * 0.55f + rng.nextBipolar() * 0.55f) * std::exp(-t * decay);
        const float y = std::tanh(peak.process(hpf.process(src)) * 1.8f) * 0.42f;
        out[i * 2] = y;
        out[i * 2 + 1] = y * 0.96f;
    }
    return out;
}

std::vector<float> HardcoreSynth::renderKickPreviewLoop(
    KickType type,
    float rootHz,
    float bpm,
    float drive,
    float aggression
) {
    const float clampedBpm = clampf(bpm, 140.0f, 240.0f);
    const float beatSec = 60.0f / clampedBpm;
    const size_t beatFrames = static_cast<size_t>(std::round(beatSec * kSampleRate));
    const size_t totalFrames = beatFrames * 4; // 1 full bar = 4 beats
    std::vector<float> out(totalFrames * 2, 0.0f);

    KickParams baseP;
    baseP.type = type;
    baseP.rootHz = clampf(rootHz, 41.2f, 73.5f);
    baseP.bpm = clampedBpm;
    baseP.drive = clampf(drive, 0.1f, 1.0f);
    baseP.aggression = clampf(aggression, 0.1f, 1.0f);
    baseP.lengthBeats = 1.0f;

    const auto k0 = renderKick(baseP);
    KickParams pitchedP = baseP;
    pitchedP.pitchSemitoneOffset = 3.0f; // minor third turnaround on beat 4
    const auto kTurn = renderKick(pitchedP);

    const auto hat = renderHardcoreHat(beatSec * 0.42f, true, 909u);

    for (int b = 0; b < 4; ++b) {
        const auto& k = (b == 3) ? kTurn : k0;
        const size_t start = static_cast<size_t>(b) * beatFrames;
        const size_t kFrames = k.size() / 2;
        for (size_t i = 0; i < kFrames && (start + i) < totalFrames; ++i) {
            out[(start + i) * 2] += k[i * 2];
            out[(start + i) * 2 + 1] += k[i * 2 + 1];
        }

        // Crisp offbeat 909 open hi-hat
        const size_t hatStart = start + beatFrames / 2;
        const size_t hatFrames = hat.size() / 2;
        for (size_t i = 0; i < hatFrames && (hatStart + i) < totalFrames; ++i) {
            out[(hatStart + i) * 2] += hat[i * 2] * 0.45f;
            out[(hatStart + i) * 2 + 1] += hat[i * 2 + 1] * 0.45f;
        }
    }

    OversampledClipper2x clipL, clipR;
    for (size_t i = 0; i < totalFrames; ++i) {
        out[i * 2] = clipL.process(out[i * 2], 1.08f, 0.96f, 0.0f);
        out[i * 2 + 1] = clipR.process(out[i * 2 + 1], 1.08f, 0.96f, 0.0f);
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

    // Lock screech 3-4 octaves above sub root (around 350..650 Hz base)
    const float baseFreq = clampf(rootHz * 8.0f, 330.0f, 660.0f);

    Biquad bpfL, bpfR, hpfL, hpfR;
    hpfL.configure(BiquadType::HPF, 320.0f, 0.8f);
    hpfR.configure(BiquadType::HPF, 320.0f, 0.8f);
    OversampledClipper2x clipL, clipR;

    double ph1 = 0.0, ph2 = 0.0, ph3 = 0.0;
    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / dur;

        // Variant 0: laser dive screech; Variant 1: rising psychotic acid pitch bend
        const float bend = (variant % 2 == 0)
            ? (1.0f + 1.85f * std::exp(-u * 5.2f) - 0.25f * u)
            : (0.85f + 0.95f * u * u + 0.08f * std::sin(kTwoPi * 18.0f * t));

        const float f1 = baseFreq * bend;
        const float f2 = f1 * 1.018f;      // wide detune
        const float f3 = f1 * 1.498f;      // power fifth harmonic grit

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
        float yL = hpfL.process(wavefold(bpfL.process(sL) * 2.6f + sL * 0.35f, 2.4f + 2.6f * agg));
        float yR = hpfR.process(wavefold(bpfR.process(sR) * 2.6f + sR * 0.35f, 2.4f + 2.6f * agg));

        out[i * 2] = clipL.process(yL * env, 1.8f, 0.90f, 0.05f);
        out[i * 2 + 1] = clipR.process(yR * env, 1.8f, 0.90f, 0.05f);
    }
    return out;
}

std::vector<float> HardcoreSynth::renderSnareBuildupRoll(
    float bpm,
    float durationSec,
    float rootHz,
    float aggression
) {
    const float clampedBpm = clampf(bpm, 140.0f, 240.0f);
    const float dur = clampf(durationSec, 0.5f, 16.0f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    const float beatSec = 60.0f / clampedBpm;
    XorShift32 rng(0xB01D0001u);

    // Schedule accelerating hits: 1/4 -> 1/8 -> 1/16 -> 1/32
    float tCursor = 0.0f;
    while (tCursor < dur - 0.02f) {
        const float progress = tCursor / dur;
        float stepBeats = 1.0f;
        if (progress > 0.82f) stepBeats = 0.125f;      // 1/32 frantic roll
        else if (progress > 0.56f) stepBeats = 0.25f;  // 1/16 roll
        else if (progress > 0.28f) stepBeats = 0.5f;   // 1/8 roll

        const size_t hitStart = static_cast<size_t>(tCursor * kSampleRate);
        const float hitDur = std::min(0.14f, stepBeats * beatSec * 0.92f);
        const size_t hitFrames = static_cast<size_t>(hitDur * kSampleRate);

        // Pitch rises +14 semitones across the buildup!
        const float pitchMul = std::pow(2.0f, (progress * 14.0f) / 12.0f);
        const float bodyHz = clampf(rootHz * 3.0f * pitchMul, 140.0f, 920.0f);
        const float amp = 0.42f + 0.52f * progress;

        double ph = 0.0;
        for (size_t j = 0; j < hitFrames && (hitStart + j) < nFrames; ++j) {
            const float lt = static_cast<float>(j) / kSampleRate;
            const float f = bodyHz + 380.0f * std::exp(-lt * 80.0f);
            ph += kTwoPi * f / kSampleRate;
            const float env = std::exp(-lt * (24.0f + 22.0f * progress));
            const float tone = std::sin(ph) * 0.62f;
            const float snap = rng.nextBipolar() * (0.45f + 0.35f * aggression);
            const float s = std::tanh((tone + snap) * (2.0f + 1.6f * progress)) * env * amp;
            out[(hitStart + j) * 2] += s;
            out[(hitStart + j) * 2 + 1] += s;
        }
        tCursor += stepBeats * beatSec;
    }

    // Rising high-pass filter tension across the entire roll
    Biquad hpfL, hpfR;
    for (size_t i = 0; i < nFrames; ++i) {
        if (i % 64 == 0) {
            const float p = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, nFrames));
            const float cut = 140.0f + 1150.0f * p * p;
            hpfL.configure(BiquadType::HPF, cut, 1.3f);
            hpfR.configure(BiquadType::HPF, cut, 1.3f);
        }
        out[i * 2] = clampf(std::tanh(hpfL.process(out[i * 2]) * 1.2f), -0.94f, 0.94f);
        out[i * 2 + 1] = clampf(std::tanh(hpfR.process(out[i * 2 + 1]) * 1.2f), -0.94f, 0.94f);
    }
    return out;
}

std::vector<float> HardcoreSynth::renderNoiseRiser(float durationSec, float rootHz, float aggression) {
    const float dur = clampf(durationSec, 0.5f, 16.0f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    XorShift32 rngL(0x52495345u), rngR(0x52495346u);

    Biquad bpfL, bpfR;
    double tonePh = 0.0;
    for (size_t i = 0; i < nFrames; ++i) {
        const float u = static_cast<float>(i) / static_cast<float>(std::max<size_t>(1, nFrames));
        if (i % 64 == 0) {
            const float cut = 320.0f * std::pow(26.0f, u); // sweeps 320 Hz -> 8.3 kHz!
            bpfL.configure(BiquadType::BPF_PEAK, cut, 2.8f + 2.0f * aggression);
            bpfR.configure(BiquadType::BPF_PEAK, cut * 1.04f, 2.8f + 2.0f * aggression);
        }
        // Rising Shepard-like siren harmonic locked to rootHz
        const float toneHz = clampf(rootHz * 4.0f * std::pow(2.0f, u * 2.0f), 160.0f, 2800.0f);
        tonePh = std::fmod(tonePh + toneHz / kSampleRate, 1.0);
        const float siren = (2.0f * static_cast<float>(tonePh) - 1.0f) * 0.24f;

        const float env = u * u * clampf((1.0f - u) * 35.0f, 0.0f, 1.0f);
        const float nL = bpfL.process(rngL.nextBipolar() + siren) * env * 0.72f;
        const float nR = bpfR.process(rngR.nextBipolar() + siren) * env * 0.72f;
        out[i * 2] = clampf(std::tanh(nL * 1.6f), -0.90f, 0.90f);
        out[i * 2 + 1] = clampf(std::tanh(nR * 1.6f), -0.90f, 0.90f);
    }
    return out;
}

std::vector<float> HardcoreSynth::renderSubImpact(float rootHz, float durationSec) {
    const float dur = clampf(durationSec, 0.35f, 3.0f);
    const size_t nFrames = static_cast<size_t>(dur * kSampleRate);
    std::vector<float> out(nFrames * 2, 0.0f);
    XorShift32 rng(0x149A0701u);

    Biquad crashHpf;
    crashHpf.configure(BiquadType::HPF, 3400.0f, 0.9f);
    double ph = 0.0;

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float u = t / dur;
        const float subHz = clampf(rootHz * (1.8f * std::exp(-u * 4.5f) + 0.65f), 32.0f, 140.0f);
        ph += kTwoPi * subHz / kSampleRate;

        const float sub = std::tanh(std::sin(ph) * 2.1f) * std::exp(-u * 3.4f) * 0.72f;
        const float crash = crashHpf.process(rng.nextBipolar()) * std::exp(-u * 4.2f) * 0.42f;
        out[i * 2] = clampf(sub + crash, -0.92f, 0.92f);
        out[i * 2 + 1] = clampf(sub + crash * 0.92f, -0.92f, 0.92f);
    }
    return out;
}

}  // namespace zaagforge
