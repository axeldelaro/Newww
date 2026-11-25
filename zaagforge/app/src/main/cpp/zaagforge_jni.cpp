#include <jni.h>
#include <android/log.h>

#include "dsp_utils.h"
#include "hardcore_kicks.h"
#include "music_analyzer.h"
#include "remix_engine.h"

#include <algorithm>
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

// ============================================================================
// Zero-OOM Native C++ Audio Session Store
// Keeps full 3-to-8 minute stereo PCM buffers strictly in C++ native RAM
// (outside Android ART's 256 MB Java heap!), streaming tiny 8-32 KB chunks
// across JNI for playback & export -> eliminates OutOfMemoryError completely!
// ============================================================================
struct NativeAudioSession {
    std::mutex mutex;
    std::vector<float> originalPcm; // Interleaved stereo 44.1 kHz Float32
    std::vector<float> remixPcm;    // Interleaved stereo 44.1 kHz Float32
    zaagforge::AnalysisResult lastAnalysis;

    // Streaming linear resampler state during MediaCodec decode
    int decodeSrcRate = 44100;
    int decodeSrcChannels = 2;
    double resampleCursor = 0.0;
    float prevL = 0.0f;
    float prevR = 0.0f;
    bool hasPrevSample = false;

    static constexpr size_t kMaxSessionFrames = 44100u * 480u; // Up to 8 minutes cleanly!
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
    if (!outCls) {
        LOGE("Failed to find NativeAnalysisOutput");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(outCls, "<init>", "([FLjava/lang/String;)V");
    if (!ctor) {
        LOGE("Failed to find NativeAnalysisOutput constructor");
        return nullptr;
    }
    jfloatArray peaksArr = vectorToJFloatArray(env, res.waveformPeaks);
    jstring metaStr = env->NewStringUTF(serializeAnalysisJson(res, totalFrames).c_str());
    return env->NewObject(outCls, ctor, peaksArr, metaStr);
}

}  // namespace

extern "C" {

// ----------------------------------------------------------------------------
// 1. Streaming Decode -> Native C++ Original Buffer (ZERO Java FloatArray OOM!)
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionBeginDecode(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jint srcSampleRate,
    jint srcChannels
) {
    std::lock_guard<std::mutex> lock(gSession.mutex);
    gSession.originalPcm.clear();
    gSession.originalPcm.shrink_to_fit();
    gSession.remixPcm.clear();
    gSession.remixPcm.shrink_to_fit();
    gSession.originalPcm.reserve(44100u * 2u * 60u);
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

    std::lock_guard<std::mutex> lock(gSession.mutex);
    if (gSession.originalPcm.size() / 2 >= NativeAudioSession::kMaxSessionFrames) {
        return JNI_FALSE; // Reached 8-minute cap cleanly
    }

    const int ch = std::max(1, gSession.decodeSrcChannels);
    const int srcFrames = static_cast<int>(sampleCount) / ch;
    if (srcFrames <= 0) return JNI_TRUE;

    jshort* ptr = env->GetShortArrayElements(pcm16Chunk, nullptr);
    if (!ptr) return JNI_FALSE;

    constexpr float kInv32768 = 1.0f / 32768.0f;
    const int srcRate = gSession.decodeSrcRate;

    if (srcRate == 44100) {
        const size_t avail = NativeAudioSession::kMaxSessionFrames - (gSession.originalPcm.size() / 2);
        const size_t take = std::min(static_cast<size_t>(srcFrames), avail);
        gSession.originalPcm.reserve(gSession.originalPcm.size() + take * 2);
        for (size_t i = 0; i < take; ++i) {
            const float l = static_cast<float>(ptr[i * ch]) * kInv32768;
            const float r = (ch >= 2) ? (static_cast<float>(ptr[i * ch + 1]) * kInv32768) : l;
            gSession.originalPcm.push_back(l);
            gSession.originalPcm.push_back(r);
        }
    } else {
        // Streaming linear resampling directly into 44.1 kHz native stereo buffer
        const double ratio = static_cast<double>(srcRate) / 44100.0;
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
    std::lock_guard<std::mutex> lock(gSession.mutex);
    const size_t totalFrames = gSession.originalPcm.size() / 2;
    if (totalFrames == 0) return nullptr;

    gSession.lastAnalysis = zaagforge::MusicAnalyzer::analyze(
        gSession.originalPcm.data(),
        totalFrames,
        zaagforge::kSampleRate,
        240
    );
    LOGI("Session analyzed: frames=%zu, bpm=%.1f, downbeat=%.3fs, chorus1Bar=%d, chorus2Bar=%d, key=%s",
         totalFrames, gSession.lastAnalysis.bpm, gSession.lastAnalysis.downbeatSec,
         gSession.lastAnalysis.chorus1Bar, gSession.lastAnalysis.chorus2Bar,
         gSession.lastAnalysis.keyName.c_str());

    return buildAnalysisOutputObj(env, gSession.lastAnalysis, totalFrames);
}

JNIEXPORT jobject JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionLoadDemo(
    JNIEnv* env,
    jclass /*clazz*/,
    jint style,
    jfloat durationSec
) {
    std::lock_guard<std::mutex> lock(gSession.mutex);
    gSession.remixPcm.clear();
    gSession.remixPcm.shrink_to_fit();
    gSession.originalPcm = zaagforge::MusicAnalyzer::generateDemoTrack(style, durationSec);
    const size_t totalFrames = gSession.originalPcm.size() / 2;

    gSession.lastAnalysis = zaagforge::MusicAnalyzer::analyze(
        gSession.originalPcm.data(),
        totalFrames,
        zaagforge::kSampleRate,
        240
    );
    return buildAnalysisOutputObj(env, gSession.lastAnalysis, totalFrames);
}

// ----------------------------------------------------------------------------
// 2. Instantaneous Live Section Preview (updates waveform markers in < 1 ms!)
// ----------------------------------------------------------------------------
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

// ----------------------------------------------------------------------------
// 3. Zero-OOM Native Remix Render (stores remix in C++ RAM, returns ~2 KB meta!)
// ----------------------------------------------------------------------------
JNIEXPORT jobject JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSessionRenderRemix(
    JNIEnv* env,
    jclass /*clazz*/,
    jfloat targetBpm,
    jint drop1Kick,
    jint drop2Kick,
    jfloat drive,
    jfloat zaagAggression,
    jfloat bassSaturation,
    jfloat originalMix,
    jfloat vocalClarity,
    jfloat vocalSidechain,
    jfloat kickLevel,
    jfloat bassCutHz,
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
    std::lock_guard<std::mutex> lock(gSession.mutex);
    const size_t totalFrames = gSession.originalPcm.size() / 2;
    if (totalFrames == 0) return nullptr;

    // Free old remix buffer BEFORE rendering so native RAM never doubles!
    gSession.remixPcm.clear();
    gSession.remixPcm.shrink_to_fit();

    zaagforge::RemixConfig cfg;
    cfg.targetBpm = targetBpm;
    cfg.drop1Kick = static_cast<zaagforge::KickType>(std::max(0, std::min(7, static_cast<int>(drop1Kick))));
    cfg.drop2Kick = static_cast<zaagforge::KickType>(std::max(0, std::min(7, static_cast<int>(drop2Kick))));
    cfg.drive = drive;
    cfg.zaagAggression = zaagAggression;
    cfg.bassSaturation = bassSaturation;
    cfg.originalMix = originalMix;
    cfg.vocalClarity = vocalClarity;
    cfg.vocalSidechain = vocalSidechain;
    cfg.kickLevel = kickLevel;
    cfg.bassCutHz = bassCutHz;
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

    LOGI("Rendering native session remix: frames=%zu, tgt=%.1f BPM, kicks=%d->%d, bassSat=%.2f, vocalClarity=%.2f, master=%d",
         totalFrames, targetBpm, drop1Kick, drop2Kick, bassSaturation, vocalClarity, masterMode);

    auto out = zaagforge::RemixEngine::renderRemix(
        gSession.originalPcm.data(),
        totalFrames,
        gSession.lastAnalysis,
        cfg
    );

    const size_t remixFrames = out.stereoPcm.size() / 2;
    gSession.remixPcm = std::move(out.stereoPcm);

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
         << ",\"totalFrames\":" << remixFrames
         << ",\"sections\":" << serializeSections(out.sections)
         << "}";

    jclass outCls = env->FindClass("com/zaagforge/studio/dsp/NativeRemixMetadata");
    if (!outCls) {
        LOGE("Failed to find NativeRemixMetadata");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(outCls, "<init>", "([FLjava/lang/String;)V");
    if (!ctor) {
        LOGE("Failed to find NativeRemixMetadata constructor");
        return nullptr;
    }

    jfloatArray peaksArr = vectorToJFloatArray(env, out.waveformPeaks);
    jstring metaStr = env->NewStringUTF(json.str().c_str());
    return env->NewObject(outCls, ctor, peaksArr, metaStr);
}

// ----------------------------------------------------------------------------
// 4. Tiny Zero-GC Streaming Readers for Playback (8 KB) & Export (32 KB)
// ----------------------------------------------------------------------------
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
    std::lock_guard<std::mutex> lock(gSession.mutex);
    gSession.originalPcm.clear();
    gSession.originalPcm.shrink_to_fit();
    gSession.remixPcm.clear();
    gSession.remixPcm.shrink_to_fit();
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
    const auto kt = static_cast<zaagforge::KickType>(std::max(0, std::min(7, static_cast<int>(kickType))));
    const auto loop = zaagforge::HardcoreSynth::renderKickPreviewLoop(kt, rootHz, bpm, drive, aggression, bassSaturation);
    return vectorToJFloatArray(env, loop);
}

}  // extern "C"
