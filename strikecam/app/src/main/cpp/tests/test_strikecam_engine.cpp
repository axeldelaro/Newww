#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "../arcade_synth.h"
#include "../beatmap_generator.h"
#include "../live_beat_tracker.h"

namespace {

int gTestsRun = 0;
int gTestsPassed = 0;

#define EXPECT_TRUE(cond, msg)                                                              \
    do {                                                                                    \
        if (!(cond)) {                                                                      \
            std::cerr << "  [FAIL] " << (msg) << " (line " << __LINE__ << ")\n";            \
            return false;                                                                   \
        }                                                                                   \
    } while (0)

#define RUN_TEST(fn)                                                                        \
    do {                                                                                    \
        ++gTestsRun;                                                                        \
        std::cout << "[TEST] " << #fn << "... ";                                            \
        if (fn()) {                                                                         \
            ++gTestsPassed;                                                                 \
            std::cout << "PASS\n";                                                          \
        } else {                                                                            \
            std::cout << "FAILED\n";                                                        \
        }                                                                                   \
    } while (0)

// Generate a synthetic rhythmic click/kick+snare+hihat track at an exact BPM with a drop section
std::vector<float> makeSyntheticPulseTrack(float bpm, float durationSec, int sampleRate = 44100) {
    const size_t frames = static_cast<size_t>(durationSec * static_cast<float>(sampleRate));
    std::vector<float> pcm(frames * 2, 0.0f);
    const float beatSec = 60.0f / bpm;
    constexpr float kTwoPi = 6.28318530717958647692f;
    uint32_t rng = 1234567u;
    auto randf = [&]() -> float {
        rng = rng * 1664525u + 1013904223u;
        return (static_cast<float>(rng & 0xFFFFu) / 32768.0f) - 1.0f;
    };

    for (size_t i = 0; i < frames; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(sampleRate);
        const float beatPos = t / beatSec;
        const int beatIdx = static_cast<int>(beatPos);
        const float frac = beatPos - static_cast<float>(beatIdx);
        const float localT = frac * beatSec;

        // Quiet intro (<25%), loud drop (30%..75%), medium outro
        const float prog = static_cast<float>(i) / static_cast<float>(frames);
        const float energyScale = (prog >= 0.30f && prog <= 0.75f) ? 1.0f : 0.42f;

        // Kick on every beat (60..140 Hz)
        const float kickFreq = 55.0f + 100.0f * std::exp(-localT * 40.0f);
        const float kick = std::sin(localT * kTwoPi * kickFreq) * std::exp(-localT * 18.0f) * 0.80f;

        // Snare on beats 1 & 3 (mid band 400..1800 Hz)
        float snare = 0.0f;
        if ((beatIdx % 2) == 1) {
            snare = (std::sin(localT * kTwoPi * 700.0f) * 0.4f + randf() * 0.3f) * std::exp(-localT * 26.0f) * 0.55f;
        }

        // High transient on off-beat half-step during drop
        float hihat = 0.0f;
        if (frac >= 0.5f) {
            float ht = (frac - 0.5f) * beatSec;
            hihat = std::sin(ht * kTwoPi * 5800.0f) * std::exp(-ht * 55.0f) * 0.35f;
        }

        float s = std::clamp((kick + snare + hihat) * energyScale, -0.95f, 0.95f);
        pcm[i * 2] = s;
        pcm[i * 2 + 1] = s * 0.96f;
    }
    return pcm;
}

bool testBpmDetection140And200() {
    {
        auto track140 = makeSyntheticPulseTrack(140.0f, 24.0f, 44100);
        auto t0 = std::chrono::steady_clock::now();
        auto analysis = strikecam::analyzeAudioSpectrum(track140.data(), track140.size(), 44100, 2);
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                      std::chrono::steady_clock::now() - t0
                  ).count();
        std::cout << "(140->" << analysis.bpm << " BPM, " << ms << "ms) ";
        EXPECT_TRUE(std::abs(analysis.bpm - 140.0f) <= 2.0f, "Expected BPM near 140");
        EXPECT_TRUE(!analysis.drops.empty(), "Expected at least 1 drop section in 140 BPM track");
        EXPECT_TRUE(ms < 800, "Analysis must complete in < 800ms");
    }
    {
        auto track200 = makeSyntheticPulseTrack(200.0f, 24.0f, 44100);
        auto analysis = strikecam::analyzeAudioSpectrum(track200.data(), track200.size(), 44100, 2);
        std::cout << "(200->" << analysis.bpm << " BPM) ";
        EXPECT_TRUE(std::abs(analysis.bpm - 200.0f) <= 2.5f, "Expected BPM near 200");
        EXPECT_TRUE(!analysis.drops.empty(), "Expected at least 1 drop section in 200 BPM track");
    }
    return true;
}

bool verifyPlayabilityConstraints(const strikecam::BeatmapResult& bm, strikecam::Difficulty diff) {
    EXPECT_TRUE(!bm.targets.empty(), "Beatmap targets should not be empty");

    // Verify chronological ordering & start lead-in
    for (size_t i = 0; i < bm.targets.size(); ++i) {
        const auto& t = bm.targets[i];
        EXPECT_TRUE(std::isfinite(t.timeMs) && t.timeMs >= 1400.0f, "Target must have >= 1400ms lead-in");
        EXPECT_TRUE(t.normX >= 0.10f && t.normX <= 0.90f, "normX in safe range");
        EXPECT_TRUE(t.normY >= 0.15f && t.normY <= 0.85f, "normY in safe range");
        if (i > 0) {
            EXPECT_TRUE(t.timeMs >= bm.targets[i - 1].timeMs - 0.01f, "Targets must be sorted by timeMs");
        }
    }

    // Collect walls and verify zero punch/slash inside wall exclusion zone
    std::vector<strikecam::BeatTarget> walls;
    for (const auto& t : bm.targets) {
        if (strikecam::isDodgeTarget(t.type)) {
            EXPECT_TRUE(t.durationMs >= 400.0f, "Dodge wall must have real physical duration");
            walls.push_back(t);
        }
    }
    EXPECT_TRUE(!walls.empty(), "Expected at least 1 dodge wall");

    for (size_t i = 1; i < walls.size(); ++i) {
        float gap = walls[i].timeMs - (walls[i - 1].timeMs + walls[i - 1].durationMs);
        EXPECT_TRUE(gap >= 1800.0f, "Consecutive dodge walls must have >= 1800ms recovery gap");
    }

    float minSameHandExpected = 560.0f;
    if (diff == strikecam::Difficulty::PRO) minSameHandExpected = 380.0f;
    else if (diff == strikecam::Difficulty::EXPERT) minSameHandExpected = 255.0f;
    else if (diff == strikecam::Difficulty::UPTEMPO_INSANE) minSameHandExpected = 165.0f;

    float lastLeft = -10000.0f;
    float lastRight = -10000.0f;
    int jabCount = 0;
    int slashCount = 0;
    int doubleCount = 0;

    for (const auto& t : bm.targets) {
        if (strikecam::isDodgeTarget(t.type)) continue;

        for (const auto& w : walls) {
            bool overlaps = (t.timeMs >= w.timeMs - 300.0f) && (t.timeMs <= w.timeMs + w.durationMs + 260.0f);
            EXPECT_TRUE(!overlaps, "Strike must NEVER overlap a dodge wall safety zone");
        }

        if (t.type == strikecam::TargetType::JAB_LEFT || t.type == strikecam::TargetType::JAB_RIGHT) {
            ++jabCount;
        } else if (strikecam::isSlashTarget(t.type)) {
            ++slashCount;
        } else if (t.type == strikecam::TargetType::DOUBLE_STRIKE) {
            ++doubleCount;
        }

        if (t.hand == strikecam::TargetHand::LEFT || t.hand == strikecam::TargetHand::BOTH) {
            EXPECT_TRUE((t.timeMs - lastLeft) >= minSameHandExpected - 2.0f, "Left hand minimum spacing violated");
            lastLeft = t.timeMs;
        }
        if (t.hand == strikecam::TargetHand::RIGHT || t.hand == strikecam::TargetHand::BOTH) {
            EXPECT_TRUE((t.timeMs - lastRight) >= minSameHandExpected - 2.0f, "Right hand minimum spacing violated");
            lastRight = t.timeMs;
        }
    }

    EXPECT_TRUE(jabCount > 0, "Beatmap must include jabs");
    EXPECT_TRUE(slashCount > 0, "Beatmap must include directional slashes");
    if (diff != strikecam::Difficulty::CHILL) {
        EXPECT_TRUE(doubleCount > 0, "PRO/EXPERT/UPTEMPO beatmap must include DOUBLE_STRIKE");
    }
    return true;
}

bool testBeatmapDifficultiesAndPlayability() {
    auto track = makeSyntheticPulseTrack(150.0f, 38.0f, 44100);
    auto analysis = strikecam::analyzeAudioSpectrum(track.data(), track.size(), 44100, 2);

    auto bmChill = strikecam::generateBeatmapFromAnalysis(analysis, strikecam::Difficulty::CHILL);
    auto bmPro = strikecam::generateBeatmapFromAnalysis(analysis, strikecam::Difficulty::PRO);
    auto bmExpert = strikecam::generateBeatmapFromAnalysis(analysis, strikecam::Difficulty::EXPERT);
    auto bmInsane = strikecam::generateBeatmapFromAnalysis(analysis, strikecam::Difficulty::UPTEMPO_INSANE);

    std::cout << "(counts: " << bmChill.targets.size() << " < " << bmPro.targets.size()
              << " < " << bmExpert.targets.size() << " < " << bmInsane.targets.size() << ") ";

    EXPECT_TRUE(verifyPlayabilityConstraints(bmChill, strikecam::Difficulty::CHILL), "CHILL constraints failed");
    EXPECT_TRUE(verifyPlayabilityConstraints(bmPro, strikecam::Difficulty::PRO), "PRO constraints failed");
    EXPECT_TRUE(verifyPlayabilityConstraints(bmExpert, strikecam::Difficulty::EXPERT), "EXPERT constraints failed");
    EXPECT_TRUE(verifyPlayabilityConstraints(bmInsane, strikecam::Difficulty::UPTEMPO_INSANE), "UPTEMPO constraints failed");

    EXPECT_TRUE(bmChill.targets.size() < bmPro.targets.size(), "Density ordering CHILL < PRO");
    EXPECT_TRUE(bmPro.targets.size() < bmExpert.targets.size(), "Density ordering PRO < EXPERT");
    EXPECT_TRUE(bmExpert.targets.size() < bmInsane.targets.size(), "Density ordering EXPERT < UPTEMPO_INSANE");
    return true;
}

bool testAllFiveBuiltInSynthTracks() {
    for (int id = 0; id < strikecam::kBuiltInTrackCount; ++id) {
        auto meta = strikecam::getBuiltInTrackMeta(id);
        auto t0 = std::chrono::steady_clock::now();
        auto stereo = strikecam::synthesizeBuiltInTrack(id, strikecam::kSynthSampleRate);
        auto synthMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                           std::chrono::steady_clock::now() - t0
                       ).count();

        const size_t expectedFloats = static_cast<size_t>(meta.durationSec * strikecam::kSynthSampleRate) * 2;
        EXPECT_TRUE(stereo.size() == expectedFloats, "Unexpected stereo buffer length");

        float peak = 0.0f;
        double sumSq = 0.0;
        double diffLR = 0.0;
        for (size_t i = 0; i < stereo.size(); i += 2) {
            float l = stereo[i];
            float r = stereo[i + 1];
            EXPECT_TRUE(std::isfinite(l) && std::isfinite(r), "Synth sample must be finite (no NaN/Inf)");
            peak = std::max(peak, std::max(std::abs(l), std::abs(r)));
            sumSq += static_cast<double>(l * l + r * r);
            diffLR += std::abs(static_cast<double>(l - r));
        }
        float rms = static_cast<float>(std::sqrt(sumSq / static_cast<double>(stereo.size())));
        float stereoSep = static_cast<float>(diffLR / static_cast<double>(stereo.size() / 2));

        EXPECT_TRUE(peak <= 0.98f, "Master peak must be <= 0.98f");
        EXPECT_TRUE(peak >= 0.45f, "Track must have punchy amplitude");
        EXPECT_TRUE(rms >= 0.08f, "Track must have rich energy");
        EXPECT_TRUE(stereoSep > 0.002f, "Track must have genuine stereo width");

        // Verify BPM + beatmap generation speed on the full ~64s stereo track!
        auto bt0 = std::chrono::steady_clock::now();
        auto bm = strikecam::generateBeatmap(
            stereo.data(), stereo.size(), strikecam::kSynthSampleRate, 2, strikecam::Difficulty::PRO
        );
        auto bmMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - bt0
                    ).count();

        std::cout << "[" << meta.name << " " << meta.bpm << "->" << bm.bpm
                  << " peak=" << peak << " synth=" << synthMs << "ms fft=" << bmMs << "ms] ";

        EXPECT_TRUE(bmMs < 800, "Beatmap generation on full track must complete in < 0.8s");
        EXPECT_TRUE(std::abs(bm.bpm - meta.bpm) <= 3.0f, "Detected BPM should closely match built-in track BPM");
        EXPECT_TRUE(verifyPlayabilityConstraints(bm, strikecam::Difficulty::PRO), "Built-in track beatmap playability failed");
    }
    return true;
}

bool testAllArcadeSfxBuffers() {
    for (int id = 0; id < strikecam::kSfxCount; ++id) {
        auto sfx = strikecam::synthesizeSfx(id, strikecam::kSynthSampleRate);
        EXPECT_TRUE(sfx.size() >= 2000, "SFX buffer must not be empty");
        float peak = 0.0f;
        for (float s : sfx) {
            EXPECT_TRUE(std::isfinite(s), "SFX sample must be finite");
            peak = std::max(peak, std::abs(s));
        }
        EXPECT_TRUE(peak <= 0.98f && peak >= 0.15f, "SFX peak should be clean and audible");
        auto pcm16 = strikecam::floatToPcm16(sfx);
        EXPECT_TRUE(pcm16.size() == sfx.size(), "PCM16 conversion size mismatch");
    }
    return true;
}

bool testLiveBeatTrackerAudioAndTapSync() {
    // 1. Real-time onset detection on a 150 BPM kick pulse stream (every 400 ms)
    constexpr int sr = 44100;
    constexpr size_t chunkSamples = 1024; // ~23.22 ms per chunk
    strikecam::LiveBeatTracker tracker(sr, 120.0f);
    tracker.reset(0.0);

    const double beatMs = 400.0; // 150 BPM
    int detectedBeats = 0;
    constexpr float kTwoPi = 6.28318530717958647692f;

    std::vector<float> chunk(chunkSamples);
    for (int c = 0; c < 360; ++c) { // ~8.36 seconds
        double chunkStartMs = (static_cast<double>(c * chunkSamples) / static_cast<double>(sr)) * 1000.0;
        for (size_t i = 0; i < chunkSamples; ++i) {
            double tMs = chunkStartMs + (static_cast<double>(i) / static_cast<double>(sr)) * 1000.0;
            double modMs = std::fmod(tMs, beatMs);
            float localSec = static_cast<float>(modMs / 1000.0);
            float kick = std::sin(localSec * kTwoPi * 80.0f) * std::exp(-localSec * 28.0f) * 0.85f;
            chunk[i] = kick;
        }
        auto ev = tracker.processAudioChunk(chunk.data(), chunk.size(), chunkStartMs);
        if (ev.triggered) {
            ++detectedBeats;
        }
    }

    std::cout << "(detected=" << detectedBeats << ", estBpm=" << tracker.getBpm() << ") ";
    EXPECT_TRUE(detectedBeats >= 14 && detectedBeats <= 24, "Expected clean onset pulse detection (~20 beats)");
    EXPECT_TRUE(std::abs(tracker.getBpm() - 150.0f) <= 6.0f, "Live BPM should converge near 150 BPM");

    // 2. Tap-Sync + Phase Nudge + Grid Beat Polling
    tracker.reset(1000.0);
    // Tap at 160 BPM = 375ms intervals
    tracker.registerTap(1000.0);
    tracker.registerTap(1375.0);
    tracker.registerTap(1750.0);
    float tappedBpm = tracker.registerTap(2125.0);
    EXPECT_TRUE(std::abs(tappedBpm - 160.0f) <= 1.0f, "Tap-Sync should lock to 160 BPM");

    tracker.nudgePhaseMs(50.0);
    EXPECT_TRUE(std::abs(tracker.getPhaseOffsetMs() - 50.0) < 0.01, "Phase nudge should offset by +50ms");

    // Poll grid across 4 beats (1500 ms)
    int gridTriggers = 0;
    for (double t = 2125.0; t <= 3750.0; t += 16.0) {
        auto gev = tracker.pollGridBeat(t, 0.0);
        if (gev.triggered) ++gridTriggers;
    }
    EXPECT_TRUE(gridTriggers >= 3 && gridTriggers <= 5, "Expected 4 scheduled grid beats");
    return true;
}

} // namespace

int main() {
    std::cout << "=== StrikeCam Arcade C++20 Native Engine Tests ===\n";
    RUN_TEST(testBpmDetection140And200);
    RUN_TEST(testBeatmapDifficultiesAndPlayability);
    RUN_TEST(testAllFiveBuiltInSynthTracks);
    RUN_TEST(testAllArcadeSfxBuffers);
    RUN_TEST(testLiveBeatTrackerAudioAndTapSync);

    std::cout << "Summary: " << gTestsPassed << "/" << gTestsRun << " tests passed.\n";
    return (gTestsPassed == gTestsRun) ? 0 : 1;
}
