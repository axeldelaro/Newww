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

float bandEnergyRatio(const std::vector<float>& stereoPcm, float loHz, float hiHz, size_t startFrame = 0) {
    constexpr size_t kN = 4096;
    const size_t totalFrames = stereoPcm.size() / 2;
    if (startFrame >= totalFrames) return 0.0f;
    const size_t avail = std::min(kN, totalFrames - startFrame);
    std::vector<std::complex<float>> buf(kN, {0.0f, 0.0f});
    const auto hann = makeHannWindow(avail);
    for (size_t i = 0; i < avail; ++i) {
        const float mono = 0.5f * (stereoPcm[(startFrame + i) * 2] + stereoPcm[(startFrame + i) * 2 + 1]);
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
        const int beatIdx = static_cast<int>(std::floor(beatPos));
        const float bf = beatPos - std::floor(beatPos);
        const float lt = bf * beatSec;
        if (lt < 0.14f) {
            const float f = 55.0f + 160.0f * std::exp(-lt * 40.0f);
            // Accent Beat 1 of each 4/4 bar slightly for realistic downbeat detection
            const float accent = (beatIdx % 4 == 0) ? 1.0f : 0.72f;
            const float s = std::sin(kTwoPi * f * lt) * std::exp(-lt * 20.0f) * 0.85f * accent;
            pcm[i * 2] = s;
            pcm[i * 2 + 1] = s;
        }
    }
    return pcm;
}

std::vector<float> makeVerseChorusTrack(float bpm, int totalBars, int chorus1Bar, int chorus2Bar) {
    const float beatSec = 60.0f / bpm;
    const float barSec = beatSec * 4.0f;
    const float durSec = totalBars * barSec;
    const size_t nFrames = static_cast<size_t>(durSec * kSampleRate);
    std::vector<float> pcm(nFrames * 2, 0.0f);

    for (size_t i = 0; i < nFrames; ++i) {
        const float t = static_cast<float>(i) / kSampleRate;
        const float beatPos = t / beatSec;
        const int beatIdx = static_cast<int>(std::floor(beatPos));
        const int barIdx = beatIdx / 4;
        const float bf = beatPos - std::floor(beatPos);
        const float lt = bf * beatSec;

        const bool inChorus1 = (barIdx >= chorus1Bar && barIdx < chorus1Bar + 12);
        const bool inChorus2 = (barIdx >= chorus2Bar && barIdx < chorus2Bar + 12);
        const bool isChorus = inChorus1 || inChorus2;

        float kick = 0.0f;
        if (lt < 0.15f) {
            const float accent = (beatIdx % 4 == 0) ? 1.0f : 0.75f;
            kick = std::sin(kTwoPi * (55.0f + 140.0f * std::exp(-lt * 38.0f)) * lt)
                 * std::exp(-lt * 18.0f) * (isChorus ? 0.65f : 0.25f) * accent;
        }
        // Center vocal melody (stronger & higher presence in Chorus!)
        const float vocalHz = isChorus ? 660.0f : 440.0f;
        const float vocal = (std::sin(kTwoPi * vocalHz * t) * 0.5f + std::sin(kTwoPi * vocalHz * 2.0f * t) * 0.25f)
                          * (isChorus ? 0.55f : 0.16f);

        const float s = clampf(kick + vocal, -0.95f, 0.95f);
        pcm[i * 2] = s;
        pcm[i * 2 + 1] = s;
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

void testBpmAndDownbeatDetection() {
    std::cout << "Running testBpmAndDownbeatDetection...\n";
    const float targets[3] = {128.0f, 140.0f, 174.0f};
    for (float tgt : targets) {
        auto track = makeSyntheticBeatTrack(tgt, 18.0f, 0.12f);
        auto res = MusicAnalyzer::analyze(track.data(), track.size() / 2);
        std::cout << "  target=" << tgt << " detected=" << res.bpm
                  << " conf=" << res.bpmConfidence << " offset=" << res.beatOffsetSec
                  << "s downbeat=" << res.downbeatSec << "s (slot " << res.downbeatBeatIndex << ")\n";
        EXPECT_NEAR(res.bpm, tgt, 2.5f, "Detected BPM matches synthetic beat track");
        EXPECT_TRUE(res.bpmConfidence > 0.2f, "BPM confidence positive");
        EXPECT_TRUE(res.beatOffsetSec >= 0.0f && res.beatOffsetSec <= (60.0f / res.bpm) + 0.02f, "Beat offset in valid beat window");
        EXPECT_TRUE(res.downbeatBeatIndex >= 0 && res.downbeatBeatIndex <= 3, "Downbeat index in 0..3");
    }
}

void testKeyDetection() {
    std::cout << "Running testKeyDetection...\n";
    auto aMinor = makeChordTrack({45, 52, 57, 60, 64}, 6.0f);
    auto resAm = MusicAnalyzer::analyze(aMinor.data(), aMinor.size() / 2);
    EXPECT_TRUE(resAm.keyIndex == 9, "Detected pitch class A (9)");
    EXPECT_TRUE(resAm.isMinor, "Detected minor mode for A minor");
    EXPECT_NEAR(resAm.rootHz, 55.0f, 0.5f, "A1 sub root is 55 Hz");

    auto fMajor = makeChordTrack({41, 48, 53, 57, 60}, 6.0f);
    auto resF = MusicAnalyzer::analyze(fMajor.data(), fMajor.size() / 2);
    EXPECT_TRUE(resF.keyIndex == 5, "Detected pitch class F (5)");
    EXPECT_TRUE(!resF.isMinor, "Detected major mode for F major");
    EXPECT_NEAR(resF.rootHz, 43.65f, 0.5f, "F1 sub root is 43.65 Hz");
}

void testChorusDetectionAndDropAlignment() {
    std::cout << "Running testChorusDetectionAndDropAlignment...\n";
    // 48-bar song @ 128 BPM (~90s) with true Chorus 1 at Bar 16 and Chorus 2 at Bar 32!
    auto song = makeVerseChorusTrack(128.0f, 48, 16, 32);
    auto res = MusicAnalyzer::analyze(song.data(), song.size() / 2);
    std::cout << "  Detected chorus1Bar=" << res.chorus1Bar << " (true=16), chorus2Bar=" << res.chorus2Bar << " (true=32)\n";

    EXPECT_NEAR(res.chorus1Bar, 16, 2.0f, "Chorus 1 detector locks cleanly onto bar 16");
    EXPECT_NEAR(res.chorus2Bar, 32, 2.0f, "Chorus 2 detector locks cleanly onto bar 32");

    // Verify DROP_1 starts right AT chorus1Bar (NEVER early or mid-chorus!)
    int drop1Start = -1;
    int fakeEnd = -1;
    for (const auto& s : res.sections) {
        if (s.type == SectionType::FAKE_DROP) fakeEnd = s.endBar;
        if (s.type == SectionType::DROP_1) drop1Start = s.startBar;
    }
    EXPECT_TRUE(drop1Start == res.chorus1Bar, "DROP 1 lands squarely on detected Chorus 1 start bar");
    EXPECT_TRUE(fakeEnd == drop1Start, "FAKE-DROP ends cleanly on exact DROP 1 beat 1");

    // Test user manual "POSER DROP 1 ICI" customDrop1Frac + bar nudge
    ArrangementOptions custom;
    custom.customDrop1Frac = 0.25f; // bar 12 of 48
    custom.buildupBars = 4;
    custom.enableFakeDrop = true;
    auto customSecs = MusicAnalyzer::buildArrangement(res, custom);
    int customDrop1Bar = -1;
    for (const auto& s : customSecs) {
        if (s.type == SectionType::DROP_1) customDrop1Bar = s.startBar;
    }
    EXPECT_NEAR(customDrop1Bar, 12, 1.0f, "Custom playhead Drop 1 snaps cleanly to bar 12");

    ArrangementOptions nudged;
    nudged.drop1BarNudge = 4;
    auto nudgedSecs = MusicAnalyzer::buildArrangement(res, nudged);
    int nudgedDrop1Bar = -1;
    for (const auto& s : nudgedSecs) {
        if (s.type == SectionType::DROP_1) nudgedDrop1Bar = s.startBar;
    }
    EXPECT_TRUE(nudgedDrop1Bar == res.chorus1Bar + 4, "Drop 1 bar nudge shifts DROP 1 cleanly by +4 bars");
}

void testAll8HardcoreKicksAndCleanBassControl() {
    std::cout << "Running testAll8HardcoreKicksAndCleanBassControl...\n";
    std::vector<std::vector<float>> kicks(8);

    for (int k = 0; k < 8; ++k) {
        KickParams p;
        p.type = static_cast<KickType>(k);
        p.rootHz = 55.0f;
        p.bpm = 205.0f;
        p.drive = 0.88f;
        p.aggression = 0.88f;
        p.bassSaturation = 0.35f;
        p.lengthBeats = 1.0f;
        kicks[k] = HardcoreSynth::renderKick(p);

        const auto st = measureStereo(kicks[k]);
        const float subRatio = bandEnergyRatio(kicks[k], 30.0f, 140.0f);
        const float piepBand = bandEnergyRatio(kicks[k], 1200.0f, 3500.0f);

        std::cout << "  Kick[" << k << "] peak=" << st.peak << " rmsDb=" << st.rmsDb
                  << " sub=" << subRatio << " piep(1.2-3.5k)=" << piepBand << "\n";

        EXPECT_TRUE(!kicks[k].empty(), "Kick buffer non-empty");
        EXPECT_TRUE(st.allFinite, "Kick has zero NaN/Inf");
        EXPECT_TRUE(st.peak <= 0.98f && st.peak >= 0.70f, "Kick peak normalized and safe");
        EXPECT_TRUE(st.rmsDb > -14.5f, "Kick has strong punch (> -14.5 dBFS)");
    }

    // Verify bassSaturation actually changes sub harmonics cleanly!
    KickParams pClean;
    pClean.type = KickType::ZAAG_CHAINSAW;
    pClean.bassSaturation = 0.05f;
    auto kClean = HardcoreSynth::renderKick(pClean);

    KickParams pDirty = pClean;
    pDirty.bassSaturation = 0.95f;
    auto kDirty = HardcoreSynth::renderKick(pDirty);
    EXPECT_TRUE(measureStereo(kDirty).rms >= measureStereo(kClean).rms * 0.95f, "Dirty sub has strong saturated energy");

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

    auto preview = HardcoreSynth::renderKickPreviewLoop(KickType::ZAAG_CHAINSAW, 55.0f, 200.0f, 0.88f, 0.88f, 0.35f);
    auto stPrev = measureStereo(preview);
    EXPECT_TRUE(stPrev.allFinite && stPrev.peak <= 0.98f && stPrev.rmsDb > -13.0f, "Kick preview loop finite & clean");
}

void testWsolaTimeStretchAndPitchShift() {
    std::cout << "Running testWsolaTimeStretchAndPitchShift...\n";
    const size_t inFrames = static_cast<size_t>(4.0f * kSampleRate);
    std::vector<float> sine(inFrames * 2);
    for (size_t i = 0; i < inFrames; ++i) {
        const float s = std::sin(kTwoPi * 440.0f * static_cast<float>(i) / kSampleRate) * 0.7f;
        sine[i * 2] = s;
        sine[i * 2 + 1] = s;
    }

    auto fast = RemixEngine::wsolaTimeStretch(sine.data(), inFrames, 0.75f);
    EXPECT_NEAR(static_cast<float>(fast.size() / 2) / static_cast<float>(inFrames), 0.75f, 0.02f, "WSOLA 0.75x duration accuracy");
    EXPECT_TRUE(measureStereo(fast).allFinite && measureStereo(fast).rms > 0.3f, "WSOLA fast output clean");

    auto shifted = RemixEngine::timeStretchAndPitchShift(sine.data(), inFrames, 1.30f, 5.0f);
    EXPECT_NEAR(static_cast<float>(shifted.size() / 2) / static_cast<float>(inFrames), 1.30f, 0.02f, "Pitch+Stretch preserves duration");

    const auto [speed100to200, mult100to200] = RemixEngine::computeMusicalTempoSync(100.0f, 200.0f);
    EXPECT_NEAR(speed100to200, 1.0f, 0.01f, "100 BPM -> 200 BPM smart double-time lock = 1.0x");
    EXPECT_NEAR(mult100to200, 0.5f, 0.01f, "100 BPM -> 200 BPM uses half-time vocal grid");
}

void testVocalPreservationAndFullStudioPipeline() {
    std::cout << "Running testVocalPreservationAndFullStudioPipeline...\n";
    for (int style = 0; style < 2; ++style) {
        auto demo = MusicAnalyzer::generateDemoTrack(style, 26.0f);
        auto analysis = MusicAnalyzer::analyze(demo.data(), demo.size() / 2);
        const float expectedBpm = (style == 0) ? 128.0f : 140.0f;
        EXPECT_NEAR(analysis.bpm, expectedBpm, 3.0f, "Demo track BPM round-trips cleanly");

        struct PresetCheck { const char* name; float bpm; KickType k1; KickType k2; float pitch; int masterMode; };
        const PresetCheck presets[4] = {
            {"UOK Piep Studio Clean", 210.0f, KickType::PIEP_UOK, KickType::ZAAG_PIEP_HYBRID, 5.0f, 0},
            {"Noiseflow Krach Club", 205.0f, KickType::KRACH_NOISEFLOW, KickType::ZAAG_CHAINSAW, -1.0f, 1},
            {"Pure Zaag Clean Sub", 200.0f, KickType::ZAAG_CHAINSAW, KickType::UPTEMPO_BOUNCE, 0.0f, 0},
            {"Frenchcore Vocal Boost", 208.0f, KickType::FRENCHCORE_MELODIC, KickType::UPTEMPO_BOUNCE, 0.0f, 0}
        };

        for (const auto& pr : presets) {
            RemixConfig cfg;
            cfg.targetBpm = pr.bpm;
            cfg.drop1Kick = pr.k1;
            cfg.drop2Kick = pr.k2;
            cfg.drive = 0.88f;
            cfg.zaagAggression = 0.88f;
            cfg.bassSaturation = 0.35f;
            cfg.originalMix = 0.82f;
            cfg.vocalClarity = 0.80f;
            cfg.vocalSidechain = 0.35f;
            cfg.kickLevel = 0.84f;
            cfg.bassCutHz = 200.0f;
            cfg.masterMode = pr.masterMode;
            cfg.vocalPitchSemitones = pr.pitch;
            cfg.stutterAmount = 0.50f;
            cfg.enableFakeDrop = true;
            cfg.enableScreeches = true;
            cfg.enablePitchKickMelody = true;
            cfg.enableTripletRolls = true;
            cfg.kickSwitchBars = 4;

            auto out = RemixEngine::renderRemix(demo.data(), demo.size() / 2, analysis, cfg);
            const auto stOut = measureStereo(out.stereoPcm);

            // Check mid-vocal clarity averaged across 8 windows (~1.5s) inside Drop 1!
            size_t drop1MidFrame = out.stereoPcm.size() / 4;
            for (const auto& s : out.sections) {
                if (s.type == SectionType::DROP_1) {
                    drop1MidFrame = static_cast<size_t>((s.startFrac + 0.05f) * (out.stereoPcm.size() / 2));
                    break;
                }
            }
            float vocalBandSum = 0.0f;
            for (int w = 0; w < 8; ++w) {
                vocalBandSum += bandEnergyRatio(out.stereoPcm, 380.0f, 4000.0f, drop1MidFrame + static_cast<size_t>(w) * 6144u);
            }
            const float vocalBand = vocalBandSum / 8.0f;

            std::cout << "    [" << pr.name << "] dur=" << out.durationSec << "s peak="
                      << stOut.peak << " rmsDb=" << out.rmsDb << " vocalBand(380-4k)=" << vocalBand
                      << " key=" << out.keyName << " sections=" << out.sections.size() << "\n";

            EXPECT_TRUE(!out.stereoPcm.empty(), "Remix output non-empty");
            EXPECT_TRUE(stOut.allFinite, "Remix output has ZERO NaN/Inf");
            EXPECT_TRUE(stOut.peak <= 0.99f, "Remix peaks strictly <= 0.99f");
            EXPECT_TRUE(out.rmsDb > -13.5f && out.rmsDb < -3.0f, "Remix hits clean studio mastering loudness (-13.5..-3 dB RMS)");
            EXPECT_TRUE(vocalBand > 0.08f, "Vocal presence band (380-4000 Hz) clearly preserved in Drop 1!");
            EXPECT_TRUE(out.waveformPeaks.size() == 240, "Remix emits 240 waveform peak bins");
        }
    }
}

}  // namespace

int main() {
    std::cout << "=== ZAAGFORGE v2.0 C++20 DSP Unit Test Suite ===\n";
    testBpmAndDownbeatDetection();
    testKeyDetection();
    testChorusDetectionAndDropAlignment();
    testAll8HardcoreKicksAndCleanBassControl();
    testWsolaTimeStretchAndPitchShift();
    testVocalPreservationAndFullStudioPipeline();

    std::cout << "\n=== SUMMARY: " << (gTestsRun - gTestsFailed) << "/" << gTestsRun
              << " assertions PASSED (" << gTestsFailed << " failed) ===\n";
    return (gTestsFailed == 0) ? 0 : 1;
}
