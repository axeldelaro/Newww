#include <jni.h>
#include <android/log.h>

#include "dsp_utils.h"
#include "hardcore_kicks.h"
#include "music_analyzer.h"
#include "remix_engine.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <locale>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#define LOG_TAG "ZaagForgeDSP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Lock-free live progress (0..100) and instant cooperative cancel (< 5 ms!)
std::atomic<int> gProgressPercent{0};
std::atomic<bool> gCancelRequested{false};

// ============================================================================
// Zero-OOM Native C++ Audio Session Store + Warped Audio Cache
// Pre-reserves exact track size ONCE (ZERO O(N^2) reallocations on 3-8 min MP3!)
// and caches warped PCM keyed by (timeScale, pitchSemitones) for instant tweaks!
// ============================================================================
struct NativeAudioSession {
    std::mutex mutex;
    std::vector<float> originalPcm; // Interleaved stereo 44.1 kHz Float32
    std::vector<float> remixPcm;    // Interleaved stereo 44.1 kHz Float32
    zaagforge::AnalysisResult lastAnalysis;

    // Instant re-render WSOLA cache
    std::vector<float> cachedWarpedPcm;
    float cachedTimeScale = -1.0f;
    float cachedPitchSt = -99.0f;

    // Streaming linear resampler state during MediaCodec decode
    int decodeSrcRate = 44100;
    int decodeSrcChannels = 2;
    double resampleCursor = 0.0;
    float prevL = 0.0f;
    float prevR = 0.0f;
    bool hasPrevSample = false;

    static constexpr size_t kMaxSessionFrames = 44100u * 480u; // up to 8 minutes

    void clearWarpedCache() {
        cachedWarpedPcm.clear();
        cachedWarpedPcm.shrink_to_fit();
        cachedTimeScale = -1.0f;
        cachedPitchSt = -99.0f;
    }

    // Geometric 2x capacity growth guard (NEVER exact-size reserve per chunk!)
    void ensureGeometricCapacity(size_t extraFloats) {
        const size_t need = originalPcm.size() + extraFloats;
        if (need > originalPcm.capacity()) {
            const size_t maxFloats = kMaxSessionFrames * 2u;
            const size_t doubled = std::max<size_t>(need, std::max<size_t>(44100u * 2u * 60u, originalPcm.capacity() * 2u));
            originalPcm.reserve(std::min(maxFloats, doubled));
        }
    }
};

NativeAudioSession gSession;

std::string escapeJson(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 4);
    for (char c : s) {
        if (c == '"') out += "\\\"";
        else if (c == '\\') out += "\\\\";
        else out += c;
    }
    return out;
}

std::string serializeSections(const std::vector<zaagforge::SectionMarker>& sections) {
    std::ostringstream oss;
    oss.imbue(std::locale::classic());
    oss << std::fixed << std::setprecision(5) << "[";
    for (size_t i = 0; i < sections.size(); ++i) {
        if (i > 0) oss << ",";
        oss << "{\"startFrac\":" << sections[i].startFrac
            << ",\"endFrac\":" << sections[i].endFrac
            << ",\"type\":" << static_cast<int>(sections[i].type)
            << ",\"label\":\"" << escapeJson(sections[i].label) << "\""
            << ",\"startBar\":" << sections[i].startBar
            << ",\"endBar\":" << sections[i].endBar << "}";
    }
    oss << "]";
    return oss.str();
}

std::string serializeAnalysisJson(const zaagforge::AnalysisResult& res, size_t totalFrames) {
    std::ostringstream json;
    json.imbue(std::locale::classic());
    json << std::fixed << std::setprecision(4)
         << "{"
         << "\"bpm\":" << res.bpm
         << ",\"bpmConfidence\":" << res.bpmConfidence
         << ",\"beatOffsetSec\":" << res.beatOffsetSec
         << ",\"downbeatBeatIndex\":" << res.downbeatBeatIndex
         << ",\"downbeatSec\":" << res.downbeatSec
         << ",\"totalBars\":" << res.totalBars
         << ",\"chorus1Bar\":" << res.chorus1Bar
         << ",\"chorus2Bar\":" << res.chorus2Bar
         << ",\"chorus1Sec\":" << res.chorus1Sec
         << ",\"chorus2Sec\":" << res.chorus2Sec
         << ",\"keyIndex\":" << res.keyIndex
         << ",\"isMinor\":" << (res.isMinor ? "true" : "false")
         << ",\"rootMidi\":" << res.rootMidi
         << ",\"rootHz\":" << res.rootHz
         << ",\"keyName\":\"" << escapeJson(res.keyName) << "\""
         << ",\"durationSec\":" << res.durationSec
         << ",\"avgRmsDb\":" << res.avgRmsDb
         << ",\"totalFrames\":" << totalFrames
         << ",\"sections\":" << serializeSections(res.sections)
         << "}";
    return json.str();
}

jfloatArray vectorToJFloatArray(JNIEnv* env, const std::vector<float>& vec) {
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(vec.size()));
    if (!arr) return nullptr;
    if (!vec.empty()) {
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(vec.size()), vec.data());
    }
    return arr;
}

jobject buildAnalysisOutputObj(JNIEnv* env, const zaagforge::AnalysisResult& res, size_t totalFrames) {
    jclass outCls = env->FindClass("com/zaagforge/studio/dsp/NativeAnalysisOutput");
    if (!outCls) return nullptr;
    jmethodID ctor = env->GetMethodID(outCls, "<init>", "([FLjava/lang/String;)V");
    if (!ctor) return nullptr;
    jfloatArray peaksArr = vectorToJFloatArray(env, res.waveformPeaks);
    jstring metaStr = env->NewStringUTF(serializeAnalysisJson(res, totalFrames).c_str());
    return env->NewObject(outCls, ctor, peaksArr, metaStr);
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionGetProgress(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    return static_cast<jint>(gProgressPercent.load(std::memory_order_relaxed));
}

JNIEXPORT void JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionRequestCancel(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    gCancelRequested.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionBeginDecode(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint srcSampleRate,
    jint srcChannels,
    jint estimatedFrames
) {
    gCancelRequested.store(false, std::memory_order_relaxed);
    gProgressPercent.store(0, std::memory_order_relaxed);

    std::lock_guard<std::mutex> lock(gSession.mutex);
    gSession.originalPcm.clear();
    gSession.originalPcm.shrink_to_fit();
    gSession.remixPcm.clear();
    gSession.remixPcm.shrink_to_fit();
    gSession.clearWarpedCache();

    // Pre-reserve exact track size ONCE (default 4.5 min if unknown -> ZERO O(N^2) realloc copies!)
    const size_t estF = (estimatedFrames > 44100)
        ? std::min<size_t>(NativeAudioSession::kMaxSessionFrames, static_cast<size_t>(estimatedFrames) + 44100u)
        : (44100u * 270u);
    gSession.originalPcm.reserve(estF * 2u);

    gSession.decodeSrcRate = std::max(8000, static_cast<int>(srcSampleRate));
    gSession.decodeSrcChannels = std::max(1, static_cast<int>(srcChannels));
    gSession.resampleCursor = 0.0;
    gSession.prevL = 0.0f;
    gSession.prevR = 0.0f;
    gSession.hasPrevSample = false;
}

JNIEXPORT void JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionUpdateFormat(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint srcSampleRate,
    jint srcChannels
) {
    std::lock_guard<std::mutex> lock(gSession.mutex);
    if (srcSampleRate >= 8000) gSession.decodeSrcRate = static_cast<int>(srcSampleRate);
    if (srcChannels >= 1) gSession.decodeSrcChannels = static_cast<int>(srcChannels);
}

JNIEXPORT jboolean JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionAppendPcm16(
    JNIEnv* env,
    jclass /*clazz*/,
    jshortArray pcm16Chunk,
    jint sampleCount
) {
    if (!pcm16Chunk || sampleCount <= 0) return JNI_TRUE;
    if (gCancelRequested.load(std::memory_order_relaxed)) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(gSession.mutex);
    const size_t curFrames = gSession.originalPcm.size() / 2;
    if (curFrames >= NativeAudioSession::kMaxSessionFrames) {
        return JNI_FALSE;
    }

    const int ch = std::max(1, gSession.decodeSrcChannels);
    const int srcFrames = static_cast<int>(sampleCount) / ch;
    if (srcFrames <= 0) return JNI_TRUE;

    jshort* ptr = env->GetShortArrayElements(pcm16Chunk, nullptr);
    if (!ptr) return JNI_FALSE;

    constexpr float kInv32768 = 1.0f / 32768.0f;
    const int srcRate = gSession.decodeSrcRate;

    if (srcRate == 44100) {
        const size_t avail = NativeAudioSession::kMaxSessionFrames - curFrames;
        const size_t take = std::min(static_cast<size_t>(srcFrames), avail);
        gSession.ensureGeometricCapacity(take * 2);
        const size_t oldSz = gSession.originalPcm.size();
        gSession.originalPcm.resize(oldSz + take * 2);
        float* dst = gSession.originalPcm.data() + oldSz;

        if (ch == 2) {
            const size_t nSamp = take * 2;
            for (size_t i = 0; i < nSamp; ++i) {
                dst[i] = static_cast<float>(ptr[i]) * kInv32768;
            }
        } else if (ch == 1) {
            for (size_t i = 0; i < take; ++i) {
                const float v = static_cast<float>(ptr[i]) * kInv32768;
                dst[i * 2] = v;
                dst[i * 2 + 1] = v;
            }
        } else {
            for (size_t i = 0; i < take; ++i) {
                dst[i * 2] = static_cast<float>(ptr[i * ch]) * kInv32768;
                dst[i * 2 + 1] = static_cast<float>(ptr[i * ch + 1]) * kInv32768;
            }
        }
    } else {
        const double ratio = static_cast<double>(srcRate) / 44100.0;
        const size_t estOut = static_cast<size_t>(std::ceil(static_cast<double>(srcFrames) / ratio)) + 4;
        gSession.ensureGeometricCapacity(estOut * 2);

        auto sampleAt = [&](int idx, float& outL, float& outR) {
            if (idx < 0 && gSession.hasPrevSample) {
                outL = gSession.prevL;
                outR = gSession.prevR;
            } else {
                const int ci = std::max(0, std::min(srcFrames - 1, idx));
                outL = static_cast<float>(ptr[ci * ch]) * kInv32768;
                outR = (ch >= 2) ? (static_cast<float>(ptr[ci * ch + 1]) * kInv32768) : outL;
            }
        };

        double pos = gSession.resampleCursor;
        while (pos < static_cast<double>(srcFrames - 1) &&
               (gSession.originalPcm.size() / 2) < NativeAudioSession::kMaxSessionFrames) {
            const int i0 = static_cast<int>(std::floor(pos));
            const float frac = static_cast<float>(pos - static_cast<double>(i0));
            const float inv = 1.0f - frac;
            float l0, r0, l1, r1;
            sampleAt(i0, l0, r0);
            sampleAt(i0 + 1, l1, r1);
            gSession.originalPcm.push_back(l0 * inv + l1 * frac);
            gSession.originalPcm.push_back(r0 * inv + r1 * frac);
            pos += ratio;
        }
        gSession.resampleCursor = pos - static_cast<double>(srcFrames);
        gSession.prevL = static_cast<float>(ptr[(srcFrames - 1) * ch]) * kInv32768;
        gSession.prevR = (ch >= 2) ? (static_cast<float>(ptr[(srcFrames - 1) * ch + 1]) * kInv32768) : gSession.prevL;
        gSession.hasPrevSample = true;
    }

    env->ReleaseShortArrayElements(pcm16Chunk, ptr, JNI_ABORT);
    return (gSession.originalPcm.size() / 2 < NativeAudioSession::kMaxSessionFrames) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionFinalizeAndAnalyze(
    JNIEnv* env,
    jclass /*clazz*/
) {
    if (gCancelRequested.load(std::memory_order_relaxed)) return nullptr;
    std::lock_guard<std::mutex> lock(gSession.mutex);
    const size_t totalFrames = gSession.originalPcm.size() / 2;
    if (totalFrames == 0) return nullptr;

    gProgressPercent.store(82, std::memory_order_relaxed);
    gSession.clearWarpedCache();
    gSession.lastAnalysis = zaagforge::MusicAnalyzer::analyze(
        gSession.originalPcm.data(),
        totalFrames,
        zaagforge::kSampleRate,
        240
    );
    gProgressPercent.store(100, std::memory_order_relaxed);
    return buildAnalysisOutputObj(env, gSession.lastAnalysis, totalFrames);
}

JNIEXPORT jobject JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionLoadDemo(
    JNIEnv* env,
    jclass /*clazz*/,
    jint style,
    jfloat durationSec
) {
    gCancelRequested.store(false, std::memory_order_relaxed);
    gProgressPercent.store(20, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(gSession.mutex);
    gSession.remixPcm.clear();
    gSession.remixPcm.shrink_to_fit();
    gSession.clearWarpedCache();
    gSession.originalPcm = zaagforge::MusicAnalyzer::generateDemoTrack(style, durationSec);
    const size_t totalFrames = gSession.originalPcm.size() / 2;

    gProgressPercent.store(65, std::memory_order_relaxed);
    gSession.lastAnalysis = zaagforge::MusicAnalyzer::analyze(
        gSession.originalPcm.data(),
        totalFrames,
        zaagforge::kSampleRate,
        240
    );
    gProgressPercent.store(100, std::memory_order_relaxed);
    return buildAnalysisOutputObj(env, gSession.lastAnalysis, totalFrames);
}

JNIEXPORT jstring JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionPreviewSections(
    JNIEnv* env,
    jclass /*clazz*/,
    jint dropMode,
    jint buildupBars,
    jboolean enableFakeDrop,
    jint drop1BarNudge,
    jint drop2BarNudge,
    jfloat customDrop1Frac,
    jfloat customDrop2Frac,
    jfloat sourceBpmOverride,
    jfloat gridPhaseNudgeMs,
    jint gridBeatShift
) {
    std::lock_guard<std::mutex> lock(gSession.mutex);
    zaagforge::ArrangementOptions opts;
    opts.dropMode = dropMode;
    opts.buildupBars = buildupBars;
    opts.enableFakeDrop = (enableFakeDrop == JNI_TRUE);
    opts.drop1BarNudge = drop1BarNudge;
    opts.drop2BarNudge = drop2BarNudge;
    opts.customDrop1Frac = customDrop1Frac;
    opts.customDrop2Frac = customDrop2Frac;
    opts.bpmOverride = sourceBpmOverride;
    opts.gridPhaseNudgeMs = gridPhaseNudgeMs;
    opts.gridBeatShift = gridBeatShift;

    const auto sections = zaagforge::MusicAnalyzer::buildArrangement(gSession.lastAnalysis, opts);
    return env->NewStringUTF(serializeSections(sections).c_str());
}

JNIEXPORT jobject JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionRenderRemix(
    JNIEnv* env,
    jclass /*clazz*/,
    jfloat targetBpm,
    jint genreTheme,
    jint tempoSyncMode,
    jint drop1Kick,
    jint drop2Kick,
    jfloat drive,
    jfloat zaagAggression,
    jfloat bassSaturation,
    jfloat originalMix,
    jfloat vocalClarity,
    jfloat epicVocalBoost,
    jfloat vocalSidechain,
    jfloat kickLevel,
    jfloat bassCutHz,
    jfloat synthLayerAmount,
    jfloat percGrooveAmount,
    jint masterMode,
    jfloat vocalPitchSemitones,
    jfloat stutterAmount,
    jboolean enableFakeDrop,
    jboolean enableScreeches,
    jboolean enablePitchKickMelody,
    jboolean enableTripletRolls,
    jint kickSwitchBars,
    jfloat sourceBpmOverride,
    jfloat gridPhaseNudgeMs,
    jint gridBeatShift,
    jint dropMode,
    jint buildupBars,
    jint drop1BarNudge,
    jint drop2BarNudge,
    jfloat customDrop1Frac,
    jfloat customDrop2Frac
) {
    gCancelRequested.store(false, std::memory_order_relaxed);
    gProgressPercent.store(2, std::memory_order_relaxed);

    std::lock_guard<std::mutex> lock(gSession.mutex);
    const size_t totalFrames = gSession.originalPcm.size() / 2;
    if (totalFrames == 0 || gCancelRequested.load(std::memory_order_relaxed)) return nullptr;

    const auto tWallStart = std::chrono::steady_clock::now();

    zaagforge::RemixConfig cfg;
    cfg.targetBpm = targetBpm;
    cfg.genreTheme = static_cast<zaagforge::RemixGenreTheme>(std::max(0, std::min(5, static_cast<int>(genreTheme))));
    cfg.tempoSyncMode = static_cast<zaagforge::TempoSyncMode>(std::max(0, std::min(2, static_cast<int>(tempoSyncMode))));
    cfg.drop1Kick = static_cast<zaagforge::KickType>(std::max(0, std::min(zaagforge::kNumKickTypes - 1, static_cast<int>(drop1Kick))));
    cfg.drop2Kick = static_cast<zaagforge::KickType>(std::max(0, std::min(zaagforge::kNumKickTypes - 1, static_cast<int>(drop2Kick))));
    cfg.drive = drive;
    cfg.zaagAggression = zaagAggression;
    cfg.bassSaturation = bassSaturation;
    cfg.originalMix = originalMix;
    cfg.vocalClarity = vocalClarity;
    cfg.epicVocalBoost = epicVocalBoost;
    cfg.vocalSidechain = vocalSidechain;
    cfg.kickLevel = kickLevel;
    cfg.bassCutHz = bassCutHz;
    cfg.synthLayerAmount = synthLayerAmount;
    cfg.percGrooveAmount = percGrooveAmount;
    cfg.masterMode = masterMode;
    cfg.vocalPitchSemitones = vocalPitchSemitones;
    cfg.stutterAmount = stutterAmount;
    cfg.enableFakeDrop = (enableFakeDrop == JNI_TRUE);
    cfg.enableScreeches = (enableScreeches == JNI_TRUE);
    cfg.enablePitchKickMelody = (enablePitchKickMelody == JNI_TRUE);
    cfg.enableTripletRolls = (enableTripletRolls == JNI_TRUE);
    cfg.kickSwitchBars = kickSwitchBars;
    cfg.sourceBpmOverride = sourceBpmOverride;
    cfg.gridPhaseNudgeMs = gridPhaseNudgeMs;
    cfg.gridBeatShift = gridBeatShift;
    cfg.dropMode = dropMode;
    cfg.buildupBars = buildupBars;
    cfg.drop1BarNudge = drop1BarNudge;
    cfg.drop2BarNudge = drop2BarNudge;
    cfg.customDrop1Frac = customDrop1Frac;
    cfg.customDrop2Frac = customDrop2Frac;

    // Smart WSOLA Cache: re-uses warped track instantly when user adjusts kicks/mix/drops at same speed & pitch!
    const float srcBpm = (cfg.sourceBpmOverride > 55.0f) ? cfg.sourceBpmOverride : gSession.lastAnalysis.bpm;
    const auto sync = zaagforge::RemixEngine::computeNeverSlowTempoSync(srcBpm, cfg.targetBpm, cfg.tempoSyncMode);
    const float timeScale = 1.0f / sync.speedRatio;
    const bool needsWarp = (std::fabs(timeScale - 1.0f) >= 0.004f || std::fabs(cfg.vocalPitchSemitones) >= 0.05f);

    const float* preWarpedPtr = nullptr;
    size_t preWarpedFrames = 0;
    int renderProgressStart = 12;

    if (needsWarp) {
        const bool cacheHit = !gSession.cachedWarpedPcm.empty() &&
            std::fabs(gSession.cachedTimeScale - timeScale) < 0.002f &&
            std::fabs(gSession.cachedPitchSt - cfg.vocalPitchSemitones) < 0.05f;
        if (!cacheHit) {
            gSession.clearWarpedCache();
            auto warped = zaagforge::RemixEngine::timeStretchAndPitchShift(
                gSession.originalPcm.data(),
                totalFrames,
                timeScale,
                cfg.vocalPitchSemitones,
                &gProgressPercent,
                4,
                38,
                &gCancelRequested
            );
            if (gCancelRequested.load(std::memory_order_relaxed) || warped.empty()) {
                return nullptr;
            }
            gSession.cachedWarpedPcm = std::move(warped);
            gSession.cachedTimeScale = timeScale;
            gSession.cachedPitchSt = cfg.vocalPitchSemitones;
            renderProgressStart = 38;
        }
        preWarpedPtr = gSession.cachedWarpedPcm.data();
        preWarpedFrames = gSession.cachedWarpedPcm.size() / 2;
    }

    auto out = zaagforge::RemixEngine::renderRemix(
        gSession.originalPcm.data(),
        totalFrames,
        gSession.lastAnalysis,
        cfg,
        preWarpedPtr,
        preWarpedFrames,
        &gProgressPercent,
        renderProgressStart,
        &gCancelRequested
    );

    if (out.cancelled || gCancelRequested.load(std::memory_order_relaxed) || out.stereoPcm.empty()) {
        return nullptr;
    }

    const auto tWallEnd = std::chrono::steady_clock::now();
    const float totalMs = std::chrono::duration<float, std::milli>(tWallEnd - tWallStart).count();

    const size_t remixFrames = out.stereoPcm.size() / 2;
    gSession.remixPcm = std::move(out.stereoPcm);
    gProgressPercent.store(100, std::memory_order_relaxed);

    LOGI("Rendered v3.5 ultra-turbo remix in %.0f ms (speed=%.2fx, bpm=%.1f, theme=%d, frames=%zu)",
         totalMs, out.tempoStretchRatio, out.effectiveTargetBpm, genreTheme, remixFrames);

    std::ostringstream json;
    json.imbue(std::locale::classic());
    json << std::fixed << std::setprecision(4)
         << "{"
         << "\"sourceBpm\":" << out.sourceBpm
         << ",\"effectiveTargetBpm\":" << out.effectiveTargetBpm
         << ",\"tempoStretchRatio\":" << out.tempoStretchRatio
         << ",\"rootHz\":" << out.rootHz
         << ",\"keyName\":\"" << escapeJson(out.keyName) << "\""
         << ",\"durationSec\":" << out.durationSec
         << ",\"peakDb\":" << out.peakDb
         << ",\"rmsDb\":" << out.rmsDb
         << ",\"renderTimeMs\":" << totalMs
         << ",\"totalFrames\":" << remixFrames
         << ",\"sections\":" << serializeSections(out.sections)
         << "}";

    jclass outCls = env->FindClass("com/zaagforge/studio/dsp/NativeRemixMetadata");
    if (!outCls) return nullptr;
    jmethodID ctor = env->GetMethodID(outCls, "<init>", "([FLjava/lang/String;)V");
    if (!ctor) return nullptr;

    jfloatArray peaksArr = vectorToJFloatArray(env, out.waveformPeaks);
    jstring metaStr = env->NewStringUTF(json.str().c_str());
    return env->NewObject(outCls, ctor, peaksArr, metaStr);
}

JNIEXPORT jint JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionGetFrameCount(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jboolean isRemix
) {
    std::lock_guard<std::mutex> lock(gSession.mutex);
    const auto& buf = (isRemix == JNI_TRUE) ? gSession.remixPcm : gSession.originalPcm;
    return static_cast<jint>(buf.size() / 2);
}

JNIEXPORT jint JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionReadFloatChunk(
    JNIEnv* env,
    jclass /*clazz*/,
    jboolean isRemix,
    jint startFrame,
    jint numFrames,
    jfloatArray outBuf
) {
    if (!outBuf || numFrames <= 0 || startFrame < 0) return 0;
    std::lock_guard<std::mutex> lock(gSession.mutex);
    const auto& buf = (isRemix == JNI_TRUE) ? gSession.remixPcm : gSession.originalPcm;
    const size_t totalFrames = buf.size() / 2;
    const size_t sf = static_cast<size_t>(startFrame);
    if (sf >= totalFrames) return 0;

    const size_t maxOutFrames = static_cast<size_t>(env->GetArrayLength(outBuf) / 2);
    const size_t count = std::min({static_cast<size_t>(numFrames), totalFrames - sf, maxOutFrames});
    if (count > 0) {
        env->SetFloatArrayRegion(outBuf, 0, static_cast<jsize>(count * 2), buf.data() + sf * 2);
    }
    return static_cast<jint>(count);
}

JNIEXPORT jint JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionReadPcm16Chunk(
    JNIEnv* env,
    jclass /*clazz*/,
    jboolean isRemix,
    jint startFrame,
    jint numFrames,
    jshortArray outBuf
) {
    if (!outBuf || numFrames <= 0 || startFrame < 0) return 0;
    std::lock_guard<std::mutex> lock(gSession.mutex);
    const auto& buf = (isRemix == JNI_TRUE) ? gSession.remixPcm : gSession.originalPcm;
    const size_t totalFrames = buf.size() / 2;
    const size_t sf = static_cast<size_t>(startFrame);
    if (sf >= totalFrames) return 0;

    const size_t maxOutFrames = static_cast<size_t>(env->GetArrayLength(outBuf) / 2);
    const size_t count = std::min({static_cast<size_t>(numFrames), totalFrames - sf, maxOutFrames});
    if (count == 0) return 0;

    std::vector<jshort> tmp(count * 2);
    for (size_t i = 0; i < count * 2; ++i) {
        const float v = zaagforge::clampf(buf[sf * 2 + i], -1.0f, 1.0f);
        tmp[i] = static_cast<jshort>(std::llround(v * 32767.0f));
    }
    env->SetShortArrayRegion(outBuf, 0, static_cast<jsize>(count * 2), tmp.data());
    return static_cast<jint>(count);
}

JNIEXPORT void JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionClear(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    gCancelRequested.store(true, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(gSession.mutex);
    gSession.originalPcm.clear();
    gSession.originalPcm.shrink_to_fit();
    gSession.remixPcm.clear();
    gSession.remixPcm.shrink_to_fit();
    gSession.clearWarpedCache();
}

JNIEXPORT jfloatArray JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSynthesizeKickPreview(
    JNIEnv* env,
    jclass /*clazz*/,
    jint kickType,
    jfloat rootHz,
    jfloat bpm,
    jfloat drive,
    jfloat aggression,
    jfloat bassSaturation
) {
    const auto kt = static_cast<zaagforge::KickType>(std::max(0, std::min(zaagforge::kNumKickTypes - 1, static_cast<int>(kickType))));
    const auto loop = zaagforge::HardcoreSynth::renderKickPreviewLoop(kt, rootHz, bpm, drive, aggression, bassSaturation);
    return vectorToJFloatArray(env, loop);
}

}  // extern "C"
