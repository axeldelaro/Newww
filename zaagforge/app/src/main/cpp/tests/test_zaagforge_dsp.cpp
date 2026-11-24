#include "../dsp_utils.h"
#include "../hardcore_kicks.h"
#include "../music_analyzer.h"
#include "../remix_engine.h"

#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

using namespace zaagforge;

namespace {

int gTestsRun = 0;
int gTestsFailed = 0;

#define EXPECT_TRUE(cond, msg) do { \
    ++gTestsRun; \
    if (!(cond)) { \
        ++gTestsFailed; \
        std::cerr << "[FAIL] " << __func__ << ": " << (msg) << " (" #cond ")\n"; \
    } \
} while (0)

#define EXPECT_NEAR(a, b, tol, msg) do { \
    ++gTestsRun; \
    const double _va = static_cast<double>(a); \
    const double _vb = static_cast<double>(b); \
    if (std::fabs(_va - _vb) > static_cast<double>(tol)) { \
        ++gTestsFailed; \
        std::cerr << "[FAIL] " << __func__ << ": " << (msg) \
                  << " got=" << _va << " expected=" << _vb << " tol=" << (tol) << "\n"; \
    } \
} while (0)

struct SignalStats {
    bool allFinite = true;
    float peak = 0.0f;
    float rms = 0.0f;
    float rmsDb = -120.0f;
};

SignalStats measureStereo(const std::vector<float>& pcm) {
    SignalStats st;
    double sumSq = 0.0;
    for (float s : pcm) {
        if (!std::isfinite(s)) {
            st.allFinite = false;
            continue;
        }
        st.peak = std::max(st.peak, std::fabs(s));
        sumSq += static_cast<double>(s) * s;
    }
    if (!pcm.empty()) {
        st.rms = static_cast<float>(std::sqrt(sumSq / static_cast<double>(pcm.size())));
        st.rmsDb = linearToDb(st.rms);
    }
    return st;
}

float bandEnergyRatio(const std::vector<float>& stereoPcm, float loHz, float hiHz) {
    constexpr size_t kN = 4096;
    const size_t totalFrames = stereoPcm.size() / 2;
    std::vector<std::complex<float>> buf(kN, {0.0f, 0.0f});
    const auto hann = makeHannWindow(std::min(kN, totalFrames));
    for (size_t i = 0; i < std::min(kN, totalFrames); ++i) {
        const float mono = 0.5f * (stereoPcm[i * 2] + stereoPcm[i * 2 + 1]);
        buf[i] = std::complex<float>(mono * hann[i], 0.0f);
    }
    fftInPlace(buf, false);

    const float binHz = kSampleRate / static_cast<float>(kN);
    double bandE = 0.0, totalE = 1e-12;
    for (size_t k = 1; k < kN / 2; ++k) {
        const float f = static_cast<float>(k) * binHz;
        const double mag2 = static_cast<double>(std::norm(buf[k]));
        totalE += mag2;
        if (f >= loHz && f <= hiHz) bandE += mag2;
    }
    return static_cast<float>(bandE / totalE);
}

std::vector<float> makeSyntheticBeatTrack(float bpm, float durationSec, float phaseOffsetSec = 0.0f) {
    const size_t nFrames = static_cast<size_t>(durationSec * kSampleRate);
    std::vector<float> pcm(nFrames * 2, 0.0f);
    const float beatSec = 60.0f / bpm;
    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate - phaseOffsetSec;
        if (t < 0.0f) continue;
        const float beatPos = t / beatSec;
        const float bf = beatPos - std::floor(beatPos);
        const float lt = bf * beatSec;
        if (lt < 0.14f) {
            const float f = 55.0f + 160.0f * std::exp(-lt * 40.0f);
            const float s = std::sin(kTwoPi * f * lt) * std::exp(-lt * 20.0f) * 0.85f;
            pcm[i * 2] = s;
            pcm[i * 2 + 1] = s;
        }
    }
    return pcm;
}

std::vector<float> makeChordTrack(const std::vector<int>& midiNotes, float durationSec) {
    const size_t nFrames = static_cast<size_t>(durationSec * kSampleRate);
    std::vector<float> pcm(nFrames * 2, 0.0f);
    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        float s = 0.0f;
        for (int m : midiNotes) {
            const float f = midiToHz(static_cast<float>(m));
            s += std::sin(kTwoPi * f * t) * 0.25f + std::sin(kTwoPi * 2.0f * f * t) * 0.08f;
        }
        pcm[i * 2] = s;
        pcm[i * 2 + 1] = s;
    }
    return pcm;
}

void testBpmDetection() {
    std::cout << "Running testBpmDetection...\n";
    const float targets[3] = {128.0f, 140.0f, 174.0f};
    for (float tgt : targets) {
        auto track = makeSyntheticBeatTrack(tgt, 18.0f, 0.12f);
        auto res = MusicAnalyzer::analyze(track.data(), track.size() / 2);
        std::cout << "  target=" << tgt << " detected=" << res.bpm
                  << " conf=" << res.bpmConfidence << " offset=" << res.beatOffsetSec << "s\n";
        EXPECT_NEAR(res.bpm, tgt, 2.5f, "Detected BPM matches synthetic beat track");
        EXPECT_TRUE(res.bpmConfidence > 0.2f, "BPM confidence positive");
        EXPECT_TRUE(res.beatOffsetSec >= 0.0f && res.beatOffsetSec <= (60.0f / res.bpm) + 0.02f, "Beat offset in valid beat window");
    }
}

void testKeyDetection() {
    std::cout << "Running testKeyDetection...\n";
    // A minor triad + bass (A2=45, E3=52, A3=57, C4=60, E4=64)
    auto aMinor = makeChordTrack({45, 52, 57, 60, 64}, 6.0f);
    auto resAm = MusicAnalyzer::analyze(aMinor.data(), aMinor.size() / 2);
    std::cout << "  A minor chord -> key=" << resAm.keyName << " rootHz=" << resAm.rootHz << "\n";
    EXPECT_TRUE(resAm.keyIndex == 9, "Detected pitch class A (9)");
    EXPECT_TRUE(resAm.isMinor, "Detected minor mode for A minor");
    EXPECT_TRUE(resAm.rootHz >= 41.0f && resAm.rootHz <= 74.0f, "Root Hz in 41..73 Hz club sub range");
    EXPECT_NEAR(resAm.rootHz, 55.0f, 0.5f, "A1 sub root is 55 Hz");

    // F major triad + bass (F2=41, C3=48, F3=53, A3=57, C4=60)
    auto fMajor = makeChordTrack({41, 48, 53, 57, 60}, 6.0f);
    auto resF = MusicAnalyzer::analyze(fMajor.data(), fMajor.size() / 2);
    std::cout << "  F major chord -> key=" << resF.keyName << " rootHz=" << resF.rootHz << "\n";
    EXPECT_TRUE(resF.keyIndex == 5, "Detected pitch class F (5)");
    EXPECT_TRUE(!resF.isMinor, "Detected major mode for F major");
    EXPECT_NEAR(resF.rootHz, 43.65f, 0.5f, "F1 sub root is 43.65 Hz");
}

void testSectionSegmentation() {
    std::cout << "Running testSectionSegmentation...\n";
    auto shortDemo = MusicAnalyzer::generateDemoTrack(0, 30.0f);
    auto resShort = MusicAnalyzer::analyze(shortDemo.data(), shortDemo.size() / 2);
    EXPECT_TRUE(resShort.sections.size() >= 4, "Short 30s track produces >= 4 arrangement sections");
    EXPECT_NEAR(resShort.sections.front().startFrac, 0.0f, 1e-4f, "First section starts at 0.0");
    EXPECT_NEAR(resShort.sections.back().endFrac, 1.0f, 1e-4f, "Last section ends at 1.0");
    bool hasFake = false, hasDrop1 = false, hasDrop2 = false;
    for (size_t i = 0; i < resShort.sections.size(); ++i) {
        const auto& s = resShort.sections[i];
        EXPECT_TRUE(s.endFrac > s.startFrac, "Section has positive duration");
        if (i > 0) EXPECT_NEAR(s.startFrac, resShort.sections[i - 1].endFrac, 1e-4f, "Sections contiguous");
        if (s.type == SectionType::FAKE_DROP) hasFake = true;
        if (s.type == SectionType::DROP_1) hasDrop1 = true;
        if (s.type == SectionType::DROP_2) hasDrop2 = true;
    }
    EXPECT_TRUE(hasFake && hasDrop1 && hasDrop2, "Short track contains FAKE_DROP, DROP_1, DROP_2");

    // Longer 75s track -> includes INTRO & MID_BREAK too
    auto longTrack = makeSyntheticBeatTrack(132.0f, 75.0f);
    auto resLong = MusicAnalyzer::analyze(longTrack.data(), longTrack.size() / 2);
    EXPECT_TRUE(resLong.sections.size() >= 6, "Long 75s track produces >= 6 arrangement sections");
}

void testAll8HardcoreKicks() {
    std::cout << "Running testAll8HardcoreKicks...\n";
    std::vector<std::vector<float>> kicks(8);

    for (int k = 0; k < 8; ++k) {
        KickParams p;
        p.type = static_cast<KickType>(k);
        p.rootHz = 55.0f;
        p.bpm = 205.0f;
        p.drive = 0.90f;
        p.aggression = 0.90f;
        p.lengthBeats = 1.0f;
        kicks[k] = HardcoreSynth::renderKick(p);

        const auto st = measureStereo(kicks[k]);
        const float subRatio = bandEnergyRatio(kicks[k], 30.0f, 140.0f);
        const float piepBand = bandEnergyRatio(kicks[k], 1200.0f, 3500.0f);
        const float midHighBand = bandEnergyRatio(kicks[k], 300.0f, 6000.0f);

        std::cout << "  Kick[" << k << "] peak=" << st.peak << " rmsDb=" << st.rmsDb
                  << " sub=" << subRatio << " piep(1.2-3.5k)=" << piepBand
                  << " midHi=" << midHighBand << "\n";

        EXPECT_TRUE(!kicks[k].empty(), "Kick buffer non-empty");
        EXPECT_TRUE(st.allFinite, "Kick has zero NaN/Inf");
        EXPECT_TRUE(st.peak <= 0.99f && st.peak >= 0.70f, "Kick peak normalized and safe");
        EXPECT_TRUE(st.rmsDb > -14.0f, "Kick has strong hardcore RMS punch (> -14 dBFS)");
    }

    // Spectral signature verification across iconic kick styles:
    const float piepMidHi = bandEnergyRatio(kicks[static_cast<int>(KickType::PIEP_UOK)], 1200.0f, 3500.0f);
    const float zaagHarmonic = bandEnergyRatio(kicks[static_cast<int>(KickType::ZAAG_CHAINSAW)], 250.0f, 6500.0f);
    const float zaagSub = bandEnergyRatio(kicks[static_cast<int>(KickType::ZAAG_CHAINSAW)], 30.0f, 140.0f);
    const float krachMetal = bandEnergyRatio(kicks[static_cast<int>(KickType::KRACH_NOISEFLOW)], 1000.0f, 5500.0f);

    EXPECT_TRUE(piepMidHi > 0.12f, "PIEP_UOK exhibits strong resonant 1.2-3.5 kHz piep energy");
    EXPECT_TRUE(zaagHarmonic > 0.15f && zaagSub > 0.10f, "ZAAG_CHAINSAW balances rich chainsaw harmonics + mono sub");
    EXPECT_TRUE(krachMetal > 0.10f, "KRACH_NOISEFLOW exhibits metallic high-mid comb energy");

    // Verify all 8 kicks are genuinely distinct waveforms
    for (int a = 0; a < 8; ++a) {
        for (int b = a + 1; b < 8; ++b) {
            const size_t n = std::min(kicks[a].size(), kicks[b].size());
            double diffSq = 0.0;
            for (size_t i = 0; i < n; ++i) {
                const double d = kicks[a][i] - kicks[b][i];
                diffSq += d * d;
            }
            const float diffRms = static_cast<float>(std::sqrt(diffSq / static_cast<double>(n)));
            EXPECT_TRUE(diffRms > 0.05f, "Every pair of hardcore kick topologies is distinct");
        }
    }

    // Verify preview loop + FX synths
    auto preview = HardcoreSynth::renderKickPreviewLoop(KickType::ZAAG_CHAINSAW, 55.0f, 200.0f, 0.9f, 0.9f);
    auto stPrev = measureStereo(preview);
    EXPECT_TRUE(stPrev.allFinite && stPrev.peak <= 0.99f && stPrev.rmsDb > -12.0f, "Kick preview loop finite & loud");

    auto screech = HardcoreSynth::renderIndustrialScreech(55.0f, 0.8f, 0.9f, 0);
    auto roll = HardcoreSynth::renderSnareBuildupRoll(205.0f, 2.5f, 55.0f, 0.85f);
    auto riser = HardcoreSynth::renderNoiseRiser(2.5f, 55.0f, 0.85f);
    auto impact = HardcoreSynth::renderSubImpact(55.0f, 1.2f);
    EXPECT_TRUE(measureStereo(screech).allFinite && measureStereo(screech).peak > 0.2f, "Screech synth valid");
    EXPECT_TRUE(measureStereo(roll).allFinite && measureStereo(roll).peak > 0.2f, "Snare buildup roll valid");
    EXPECT_TRUE(measureStereo(riser).allFinite && measureStereo(riser).peak > 0.1f, "Noise riser valid");
    EXPECT_TRUE(measureStereo(impact).allFinite && measureStereo(impact).peak > 0.3f, "Sub impact valid");
}

void testWsolaTimeStretchAndPitchShift() {
    std::cout << "Running testWsolaTimeStretchAndPitchShift...\n";
    // 440 Hz stereo sine wave, 4.0 seconds
    const size_t inFrames = static_cast<size_t>(4.0f * kSampleRate);
    std::vector<float> sine(inFrames * 2);
    for (size_t i = 0; i < inFrames; ++i) {
        const float s = std::sin(kTwoPi * 440.0f * static_cast<float>(i) / kSampleRate) * 0.7f;
        sine[i * 2] = s;
        sine[i * 2 + 1] = s;
    }

    // 1. Compress time by 0.75x (speed up 1.33x)
    auto fast = RemixEngine::wsolaTimeStretch(sine.data(), inFrames, 0.75f);
    const size_t fastFrames = fast.size() / 2;
    EXPECT_NEAR(static_cast<float>(fastFrames) / static_cast<float>(inFrames), 0.75f, 0.02f, "WSOLA 0.75x duration accuracy");
    const auto stFast = measureStereo(fast);
    EXPECT_TRUE(stFast.allFinite && stFast.peak <= 1.05f && stFast.rms > 0.3f, "WSOLA fast output clean");

    // 2. Stretch time by 1.30x + pitch shift +5 semitones (UOK chipmunk!)
    auto shifted = RemixEngine::timeStretchAndPitchShift(sine.data(), inFrames, 1.30f, 5.0f);
    const size_t shiftedFrames = shifted.size() / 2;
    EXPECT_NEAR(static_cast<float>(shiftedFrames) / static_cast<float>(inFrames), 1.30f, 0.02f, "Pitch+Stretch preserves exact target duration");
    const auto stShift = measureStereo(shifted);
    EXPECT_TRUE(stShift.allFinite && stShift.rms > 0.25f, "Pitch-shifted signal clean");

    // Check dominant frequency moved from 440 Hz -> ~587.3 Hz (+5 semitones)
    constexpr size_t kN = 4096;
    std::vector<std::complex<float>> fftBuf(kN);
    const auto hann = makeHannWindow(kN);
    const size_t start = shiftedFrames / 3;
    for (size_t i = 0; i < kN; ++i) {
        fftBuf[i] = std::complex<float>(shifted[(start + i) * 2] * hann[i], 0.0f);
    }
    fftInPlace(fftBuf, false);
    size_t maxBin = 1;
    float maxMag = 0.0f;
    for (size_t k = 1; k < kN / 2; ++k) {
        const float m = std::abs(fftBuf[k]);
        if (m > maxMag) {
            maxMag = m;
            maxBin = k;
        }
    }
    const float peakHz = static_cast<float>(maxBin) * kSampleRate / static_cast<float>(kN);
    std::cout << "  440 Hz shifted +5 st -> measured peak=" << peakHz << " Hz (expected ~587.3 Hz)\n";
    EXPECT_NEAR(peakHz, 587.3f, 25.0f, "Independent pitch shifter shifts frequency accurately");

    // 3. Smart half/double-time sync check: 100 BPM -> 200 BPM locks cleanly at 1.0x!
    const auto [speed100to200, mult100to200] = RemixEngine::computeMusicalTempoSync(100.0f, 200.0f);
    EXPECT_NEAR(speed100to200, 1.0f, 0.01f, "100 BPM -> 200 BPM smart double-time lock = 1.0x");
    EXPECT_NEAR(mult100to200, 0.5f, 0.01f, "100 BPM -> 200 BPM uses half-time vocal grid");
}

void testDemoTracksAndFullRemixPipeline() {
    std::cout << "Running testDemoTracksAndFullRemixPipeline...\n";
    for (int style = 0; style < 2; ++style) {
        auto demo = MusicAnalyzer::generateDemoTrack(style, 24.0f);
        auto stDemo = measureStereo(demo);
        EXPECT_TRUE(stDemo.allFinite && stDemo.peak <= 0.99f && stDemo.rmsDb > -18.0f, "Demo track clean and musical");

        auto analysis = MusicAnalyzer::analyze(demo.data(), demo.size() / 2);
        const float expectedBpm = (style == 0) ? 128.0f : 140.0f;
        std::cout << "  Demo[" << style << "] detected BPM=" << analysis.bpm
                  << " key=" << analysis.keyName << " rootHz=" << analysis.rootHz << "\n";
        EXPECT_NEAR(analysis.bpm, expectedBpm, 3.0f, "Demo track BPM round-trips cleanly");

        // Render 4 iconic presets end-to-end!
        struct PresetCheck { const char* name; float bpm; KickType k1; KickType k2; float pitch; };
        const PresetCheck presets[4] = {
            {"UOK Piep Madness", 210.0f, KickType::PIEP_UOK, KickType::ZAAG_PIEP_HYBRID, 5.0f},
            {"Noiseflow Krach", 205.0f, KickType::KRACH_NOISEFLOW, KickType::ZAAG_CHAINSAW, -1.0f},
            {"Pure Zaag Chainsaw", 200.0f, KickType::ZAAG_CHAINSAW, KickType::UPTEMPO_BOUNCE, 0.0f},
            {"Frenchcore Euphoric", 208.0f, KickType::FRENCHCORE_MELODIC, KickType::UPTEMPO_BOUNCE, 0.0f}
        };

        for (const auto& pr : presets) {
            RemixConfig cfg;
            cfg.targetBpm = pr.bpm;
            cfg.drop1Kick = pr.k1;
            cfg.drop2Kick = pr.k2;
            cfg.drive = 0.92f;
            cfg.zaagAggression = 0.92f;
            cfg.originalMix = 0.72f;
            cfg.vocalPitchSemitones = pr.pitch;
            cfg.stutterAmount = 0.75f;
            cfg.enableFakeDrop = true;
            cfg.enableScreeches = true;
            cfg.enablePitchKickMelody = true;
            cfg.enableTripletRolls = true;
            cfg.kickSwitchBars = 4;

            auto out = RemixEngine::renderRemix(demo.data(), demo.size() / 2, analysis, cfg);
            const auto stOut = measureStereo(out.stereoPcm);

            std::cout << "    [" << pr.name << "] dur=" << out.durationSec << "s peak="
                      << stOut.peak << " rmsDb=" << out.rmsDb << " key=" << out.keyName
                      << " sections=" << out.sections.size() << "\n";

            EXPECT_TRUE(!out.stereoPcm.empty(), "Remix output non-empty");
            EXPECT_TRUE(stOut.allFinite, "Remix output has ZERO NaN/Inf");
            EXPECT_TRUE(stOut.peak <= 0.99f, "Remix peaks strictly clamped <= 0.99f");
            EXPECT_TRUE(out.rmsDb > -12.0f, "Remix hits loud festival mastering RMS (> -12 dBFS)");
            EXPECT_TRUE(out.waveformPeaks.size() == 240, "Remix emits 240 waveform peak bins");
            EXPECT_TRUE(out.sections.size() >= 4, "Remix preserves valid section markers");
        }
    }
}

}  // namespace

int main() {
    std::cout << "=== ZAAGFORGE C++20 DSP Unit Test Suite ===\n";
    testBpmDetection();
    testKeyDetection();
    testSectionSegmentation();
    testAll8HardcoreKicks();
    testWsolaTimeStretchAndPitchShift();
    testDemoTracksAndFullRemixPipeline();

    std::cout << "\n=== SUMMARY: " << (gTestsRun - gTestsFailed) << "/" << gTestsRun
              << " assertions PASSED (" << gTestsFailed << " failed) ===\n";
    return (gTestsFailed == 0) ? 0 : 1;
}
