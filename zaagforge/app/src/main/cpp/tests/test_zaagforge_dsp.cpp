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
        const float vocalHz = isChorus ? 660.0f : 440.0f;
        const float vocal = (std::sin(kTwoPi * vocalHz * t) * 0.5f + std::sin(kTwoPi * vocalHz * 2.0f * t) * 0.25f)
                          * (isChorus ? 0.55f : 0.16f);

        const float s = clampf(kick + vocal, -0.95f, 0.95f);
        pcm[i * 2] = s;
        pcm[i * 2 + 1] = s;
    }
    return pcm;
}

void testNeverSlowTempoSyncInvariant() {
    std::cout << "Running testNeverSlowTempoSyncInvariant...\n";
    const float srcBpms[7] = {88.0f, 100.0f, 124.0f, 132.0f, 140.0f, 152.0f, 174.0f};
    const float tgtBpms[6] = {138.0f, 146.0f, 156.0f, 180.0f, 200.0f, 215.0f};
    for (float s : srcBpms) {
        for (float t : tgtBpms) {
            for (int m = 0; m < 3; ++m) {
                const auto res = RemixEngine::computeNeverSlowTempoSync(s, t, static_cast<TempoSyncMode>(m));
                EXPECT_TRUE(res.speedRatio >= 0.999f, "NEVER slows down user's song (speedRatio >= 1.00x)!");
                EXPECT_TRUE(res.effectiveTargetBpm >= 115.0f && res.effectiveTargetBpm <= 250.0f, "Valid target BPM");
            }
        }
    }
    // Verify 130 BPM -> 200 BPM NO LONGER slows down to 0.76x!
    const auto r130 = RemixEngine::computeNeverSlowTempoSync(130.0f, 200.0f, TempoSyncMode::NEVER_SLOW);
    std::cout << "  130->200 BPM NEVER_SLOW: speed=" << r130.speedRatio << "x, effBpm=" << r130.effectiveTargetBpm << "\n";
    EXPECT_TRUE(r130.speedRatio >= 1.45f, "130->200 speeds up energetically instead of slowing down to 0.76x");

    // Verify 100 BPM -> 200 BPM still locks cleanly at 1.00x half-time vocals!
    const auto r100 = RemixEngine::computeNeverSlowTempoSync(100.0f, 200.0f, TempoSyncMode::NEVER_SLOW);
    EXPECT_NEAR(r100.speedRatio, 1.0f, 0.01f, "100->200 locks at exact 1.00x half-time vocals");
    EXPECT_NEAR(r100.effectiveTargetBpm, 200.0f, 0.5f, "100->200 kicks at 200 BPM");

    // Verify ORIGINAL_SPEED_LOCK keeps exact 1.00x speed!
    const auto rLock = RemixEngine::computeNeverSlowTempoSync(135.0f, 200.0f, TempoSyncMode::ORIGINAL_SPEED_LOCK);
    EXPECT_NEAR(rLock.speedRatio, 1.0f, 0.001f, "ORIGINAL_SPEED_LOCK keeps exact 1.00x");
}

void testBpmAndChorusDetection() {
    std::cout << "Running testBpmAndChorusDetection...\n";
    auto track128 = makeSyntheticBeatTrack(128.0f, 18.0f, 0.12f);
    auto res128 = MusicAnalyzer::analyze(track128.data(), track128.size() / 2);
    EXPECT_NEAR(res128.bpm, 128.0f, 2.5f, "128 BPM detected accurately");

    auto song = makeVerseChorusTrack(128.0f, 48, 16, 32);
    auto res = MusicAnalyzer::analyze(song.data(), song.size() / 2);
    std::cout << "  Chorus detector: c1=" << res.chorus1Bar << " (true=16), c2=" << res.chorus2Bar << " (true=32)\n";
    EXPECT_NEAR(res.chorus1Bar, 16, 2.0f, "Chorus 1 detected on bar 16");
    EXPECT_NEAR(res.chorus2Bar, 32, 2.0f, "Chorus 2 detected on bar 32");
}

void testAll12ArtistKicksAndProLayers() {
    std::cout << "Running testAll12ArtistKicksAndProLayers...\n";
    std::vector<std::vector<float>> kicks(kNumKickTypes);

    for (int k = 0; k < kNumKickTypes; ++k) {
        KickParams p;
        p.type = static_cast<KickType>(k);
        p.rootHz = 55.0f;
        p.bpm = (k == 8) ? 138.0f : ((k == 9 || k == 11) ? 155.0f : ((k == 10) ? 180.0f : 202.0f));
        p.drive = 0.85f;
        p.aggression = 0.85f;
        p.bassSaturation = 0.34f;
        p.lengthBeats = 1.0f;
        kicks[k] = HardcoreSynth::renderKick(p);

        const auto st = measureStereo(kicks[k]);
        std::cout << "  Kick[" << k << "] peak=" << st.peak << " rmsDb=" << st.rmsDb << "\n";
        EXPECT_TRUE(!kicks[k].empty() && st.allFinite, "Kick buffer finite & non-empty");
        EXPECT_TRUE(st.peak <= 0.98f && st.peak >= 0.70f, "Kick peak normalized");
        EXPECT_TRUE(st.rmsDb > -15.5f, "Kick has strong punch");
    }

    // Verify all pro genre layers synthesize cleanly
    auto clap = HardcoreSynth::render909Clap();
    auto ride = HardcoreSynth::render909Ride();
    auto acid = HardcoreSynth::renderAcid303Riff(55.0f, 152.0f, 4, 0.85f);
    auto euph = HardcoreSynth::renderEuphoricChordLayer(55.0f, true, 200.0f, 4);
    auto hoov = HardcoreSynth::renderRaveHooverStab(55.0f, 0.7f, 0.85f);
    EXPECT_TRUE(measureStereo(clap).allFinite && measureStereo(clap).peak > 0.1f, "909 Clap valid");
    EXPECT_TRUE(measureStereo(ride).allFinite && measureStereo(ride).peak > 0.05f, "909 Ride valid");
    EXPECT_TRUE(measureStereo(acid).allFinite && measureStereo(acid).peak > 0.1f, "TB-303 Acid Riff valid");
    EXPECT_TRUE(measureStereo(euph).allFinite && measureStereo(euph).peak > 0.1f, "Euphoric Frenchcore Pad valid");
    EXPECT_TRUE(measureStereo(hoov).allFinite && measureStereo(hoov).peak > 0.1f, "Gabber Hoover Stab valid");
}

void testAllGenresSpeedAndEpicVocals() {
    std::cout << "Running testAllGenresSpeedAndEpicVocals...\n";
    auto demo = MusicAnalyzer::generateDemoTrack(0, 28.0f);
    auto analysis = MusicAnalyzer::analyze(demo.data(), demo.size() / 2);

    struct ThemeSpec {
        const char* name;
        RemixGenreTheme theme;
        float bpm;
        KickType k1;
        KickType k2;
        TempoSyncMode syncMode;
    };
    const ThemeSpec specs[6] = {
        {"Techno Normale (Charlotte)", RemixGenreTheme::TECHNO_CLUB, 138.0f, KickType::TECHNO_ROLLING, KickType::TECHNO_ROLLING, TempoSyncMode::NEVER_SLOW},
        {"Hard Techno (Sara/Nico)", RemixGenreTheme::HARD_TECHNO, 156.0f, KickType::HARD_TECHNO_RAVE, KickType::ACID_303_TECHNO, TempoSyncMode::NEVER_SLOW},
        {"Frenchcore (Sefa/Peacock)", RemixGenreTheme::FRENCHCORE, 200.0f, KickType::FRENCHCORE_MELODIC, KickType::FRENCHCORE_MELODIC, TempoSyncMode::NEVER_SLOW},
        {"Gabber 909 (Angerfist)", RemixGenreTheme::GABBER, 180.0f, KickType::GABBER_MILLENNIUM, KickType::GABBER_MILLENNIUM, TempoSyncMode::NEVER_SLOW},
        {"UOK Piep (Original Speed)", RemixGenreTheme::UPTEMPO_ZAAG, 210.0f, KickType::PIEP_UOK, KickType::ZAAG_PIEP_HYBRID, TempoSyncMode::ORIGINAL_SPEED_LOCK},
        {"Noiseflow Krach", RemixGenreTheme::UPTEMPO_ZAAG, 205.0f, KickType::KRACH_NOISEFLOW, KickType::ZAAG_CHAINSAW, TempoSyncMode::NEVER_SLOW}
    };

    for (const auto& sp : specs) {
        RemixConfig cfg;
        cfg.genreTheme = sp.theme;
        cfg.tempoSyncMode = sp.syncMode;
        cfg.targetBpm = sp.bpm;
        cfg.drop1Kick = sp.k1;
        cfg.drop2Kick = sp.k2;
        cfg.epicVocalBoost = 0.85f;
        cfg.vocalClarity = 0.85f;
        cfg.bassSaturation = 0.34f;
        cfg.originalMix = 0.86f;
        cfg.vocalSidechain = 0.24f;
        cfg.kickLevel = 0.80f;
        cfg.synthLayerAmount = 0.65f;
        cfg.percGrooveAmount = 0.72f;
        cfg.masterMode = 0;

        auto out = RemixEngine::renderRemix(demo.data(), demo.size() / 2, analysis, cfg);
        const auto stOut = measureStereo(out.stereoPcm);

        size_t drop1Start = out.stereoPcm.size() / 4;
        for (const auto& s : out.sections) {
            if (s.type == SectionType::DROP_1) {
                drop1Start = static_cast<size_t>((s.startFrac + 0.05f) * (out.stereoPcm.size() / 2));
                break;
            }
        }
        float vocSum = 0.0f;
        for (int w = 0; w < 8; ++w) {
            vocSum += bandEnergyRatio(out.stereoPcm, 380.0f, 4000.0f, drop1Start + static_cast<size_t>(w) * 6144u);
        }
        const float vocBand = vocSum / 8.0f;

        std::cout << "  [" << sp.name << "] time=" << out.renderTimeMs << "ms speed=" << out.tempoStretchRatio
                  << "x effBpm=" << out.effectiveTargetBpm << " rms=" << out.rmsDb
                  << "dB vocBand=" << vocBand << "\n";

        EXPECT_TRUE(stOut.allFinite && stOut.peak <= 0.99f, "Output finite and brickwall safe");
        EXPECT_TRUE(out.tempoStretchRatio >= 0.999f, "Output NEVER slowed down");
        EXPECT_TRUE(out.rmsDb > -13.5f && out.rmsDb < -3.0f, "Pro studio mastering loudness");
        EXPECT_TRUE(vocBand > 0.14f, "Vocals prominently audible during epic/bass drops (> 0.14)");
        EXPECT_TRUE(out.renderTimeMs < 1500.0f, "Ultra-turbo fused C++20 render (< 1.5s for 28s track)");
    }
}

void testProgressCancelAndSeamlessBoundaries() {
    std::cout << "Running testProgressCancelAndSeamlessBoundaries...\n";
    auto demo = MusicAnalyzer::generateDemoTrack(0, 12.0f);
    auto analysis = MusicAnalyzer::analyze(demo.data(), demo.size() / 2);

    RemixConfig cfg;
    cfg.genreTheme = RemixGenreTheme::TECHNO_CLUB;
    cfg.tempoSyncMode = TempoSyncMode::ORIGINAL_SPEED_LOCK;
    cfg.targetBpm = 138.0f;
    cfg.drop1Kick = KickType::TECHNO_ROLLING;
    cfg.drop2Kick = KickType::TECHNO_ROLLING;

    // 1. Verify live progress atomic reaches 100%
    std::atomic<int> prog{0};
    std::atomic<bool> cancel{false};
    auto out = RemixEngine::renderRemix(demo.data(), demo.size() / 2, analysis, cfg, nullptr, 0, &prog, 10, &cancel);
    EXPECT_TRUE(!out.cancelled && !out.stereoPcm.empty(), "Normal render completes without cancel");
    EXPECT_TRUE(prog.load() == 100, "Progress atomic reaches 100%");

    // 2. Verify zero click/discontinuity across multi-thread slice boundaries
    const size_t nFrames = out.stereoPcm.size() / 2;
    float maxBoundaryJump = 0.0f;
    for (int div = 2; div <= 6; ++div) {
        const size_t b = nFrames / static_cast<size_t>(div);
        if (b > 2 && b + 2 < nFrames) {
            const float jumpL = std::fabs(out.stereoPcm[b * 2] - out.stereoPcm[(b - 1) * 2]);
            const float jumpR = std::fabs(out.stereoPcm[b * 2 + 1] - out.stereoPcm[(b - 1) * 2 + 1]);
            maxBoundaryJump = std::max(maxBoundaryJump, std::max(jumpL, jumpR));
        }
    }
    std::cout << "  Max inter-thread boundary delta = " << maxBoundaryJump << "\n";
    EXPECT_TRUE(maxBoundaryJump < 0.45f, "Zero seam/click at multi-thread slice boundaries");

    // 3. Verify instant cooperative cancellation
    cancel.store(true);
    auto aborted = RemixEngine::renderRemix(demo.data(), demo.size() / 2, analysis, cfg, nullptr, 0, &prog, 10, &cancel);
    EXPECT_TRUE(aborted.cancelled, "Cooperative cancel aborts render cleanly");
}

}  // namespace

int main() {
    std::cout << "=== ZAAGFORGE v3.5 Ultra-Turbo C++20 DSP Test Suite ===\n";
    testNeverSlowTempoSyncInvariant();
    testBpmAndChorusDetection();
    testAll12ArtistKicksAndProLayers();
    testAllGenresSpeedAndEpicVocals();
    testProgressCancelAndSeamlessBoundaries();

    std::cout << "\n=== SUMMARY: " << (gTestsRun - gTestsFailed) << "/" << gTestsRun
              << " assertions PASSED (" << gTestsFailed << " failed) ===\n";
    return (gTestsFailed == 0) ? 0 : 1;
}
