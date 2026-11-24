#include <jni.h>
#include <android/log.h>

#include "dsp_utils.h"
#include "hardcore_kicks.h"
#include "music_analyzer.h"
#include "remix_engine.h"

#include <iomanip>
#include <locale>
#include <sstream>
#include <string>
#include <vector>

#define LOG_TAG "ZaagForgeDSP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

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
            << ",\"label\":\"" << escapeJson(sections[i].label) << "\"}";
    }
    oss << "]";
    return oss.str();
}

jfloatArray vectorToJFloatArray(JNIEnv* env, const std::vector<float>& vec) {
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(vec.size()));
    if (!arr) return nullptr;
    if (!vec.empty()) {
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(vec.size()), vec.data());
    }
    return arr;
}

}  // namespace

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeAnalyzeTrack(
    JNIEnv* env,
    jclass /*clazz*/,
    jfloatArray stereoPcmArr,
    jint sampleRate
) {
    if (!stereoPcmArr) return nullptr;
    const jsize len = env->GetArrayLength(stereoPcmArr);
    const size_t totalFrames = static_cast<size_t>(len / 2);

    jfloat* pcmPtr = env->GetFloatArrayElements(stereoPcmArr, nullptr);
    if (!pcmPtr) return nullptr;

    const auto res = zaagforge::MusicAnalyzer::analyze(
        pcmPtr,
        totalFrames,
        static_cast<float>(sampleRate),
        240
    );
    env->ReleaseFloatArrayElements(stereoPcmArr, pcmPtr, JNI_ABORT);

    std::ostringstream json;
    json.imbue(std::locale::classic());
    json << std::fixed << std::setprecision(4)
         << "{"
         << "\"bpm\":" << res.bpm
         << ",\"bpmConfidence\":" << res.bpmConfidence
         << ",\"beatOffsetSec\":" << res.beatOffsetSec
         << ",\"keyIndex\":" << res.keyIndex
         << ",\"isMinor\":" << (res.isMinor ? "true" : "false")
         << ",\"rootMidi\":" << res.rootMidi
         << ",\"rootHz\":" << res.rootHz
         << ",\"keyName\":\"" << escapeJson(res.keyName) << "\""
         << ",\"durationSec\":" << res.durationSec
         << ",\"avgRmsDb\":" << res.avgRmsDb
         << ",\"sections\":" << serializeSections(res.sections)
         << "}";

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
    jstring metaStr = env->NewStringUTF(json.str().c_str());
    return env->NewObject(outCls, ctor, peaksArr, metaStr);
}

JNIEXPORT jobject JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeRenderRemix(
    JNIEnv* env,
    jclass /*clazz*/,
    jfloatArray stereoPcmArr,
    jint /*sampleRate*/,
    jfloat sourceBpm,
    jfloat beatOffsetSec,
    jint keyIndex,
    jboolean isMinor,
    jfloat rootHz,
    jfloat targetBpm,
    jint drop1Kick,
    jint drop2Kick,
    jfloat drive,
    jfloat zaagAggression,
    jfloat originalMix,
    jfloat vocalPitchSemitones,
    jfloat stutterAmount,
    jboolean enableFakeDrop,
    jboolean enableScreeches,
    jboolean enablePitchKickMelody,
    jboolean enableTripletRolls,
    jint kickSwitchBars,
    jfloatArray sectionStartFracs,
    jfloatArray sectionEndFracs,
    jintArray sectionTypes
) {
    if (!stereoPcmArr) return nullptr;
    const jsize len = env->GetArrayLength(stereoPcmArr);
    const size_t totalFrames = static_cast<size_t>(len / 2);

    zaagforge::AnalysisResult analysis;
    analysis.bpm = sourceBpm;
    analysis.beatOffsetSec = beatOffsetSec;
    analysis.keyIndex = keyIndex;
    analysis.isMinor = (isMinor == JNI_TRUE);
    analysis.rootHz = rootHz;
    analysis.keyName = zaagforge::formatKeyName(keyIndex, analysis.isMinor);

    if (sectionStartFracs && sectionEndFracs && sectionTypes) {
        const jsize nSec = std::min(
            env->GetArrayLength(sectionStartFracs),
            std::min(env->GetArrayLength(sectionEndFracs), env->GetArrayLength(sectionTypes))
        );
        if (nSec > 0) {
            jfloat* sf = env->GetFloatArrayElements(sectionStartFracs, nullptr);
            jfloat* ef = env->GetFloatArrayElements(sectionEndFracs, nullptr);
            jint* st = env->GetIntArrayElements(sectionTypes, nullptr);
            for (jsize i = 0; i < nSec; ++i) {
                const auto sType = static_cast<zaagforge::SectionType>(zaagforge::clampf(static_cast<float>(st[i]), 0.0f, 6.0f));
                analysis.sections.push_back({
                    sf[i],
                    ef[i],
                    sType,
                    zaagforge::MusicAnalyzer::sectionTypeName(sType)
                });
            }
            env->ReleaseFloatArrayElements(sectionStartFracs, sf, JNI_ABORT);
            env->ReleaseFloatArrayElements(sectionEndFracs, ef, JNI_ABORT);
            env->ReleaseIntArrayElements(sectionTypes, st, JNI_ABORT);
        }
    }

    zaagforge::RemixConfig cfg;
    cfg.targetBpm = targetBpm;
    cfg.drop1Kick = static_cast<zaagforge::KickType>(std::max(0, std::min(7, static_cast<int>(drop1Kick))));
    cfg.drop2Kick = static_cast<zaagforge::KickType>(std::max(0, std::min(7, static_cast<int>(drop2Kick))));
    cfg.drive = drive;
    cfg.zaagAggression = zaagAggression;
    cfg.originalMix = originalMix;
    cfg.vocalPitchSemitones = vocalPitchSemitones;
    cfg.stutterAmount = stutterAmount;
    cfg.enableFakeDrop = (enableFakeDrop == JNI_TRUE);
    cfg.enableScreeches = (enableScreeches == JNI_TRUE);
    cfg.enablePitchKickMelody = (enablePitchKickMelody == JNI_TRUE);
    cfg.enableTripletRolls = (enableTripletRolls == JNI_TRUE);
    cfg.kickSwitchBars = kickSwitchBars;

    jfloat* pcmPtr = env->GetFloatArrayElements(stereoPcmArr, nullptr);
    if (!pcmPtr) return nullptr;

    LOGI("Rendering remix: src=%.1f BPM -> tgt=%.1f BPM, kicks=%d->%d, frames=%zu",
         sourceBpm, targetBpm, drop1Kick, drop2Kick, totalFrames);

    const auto out = zaagforge::RemixEngine::renderRemix(pcmPtr, totalFrames, analysis, cfg);
    env->ReleaseFloatArrayElements(stereoPcmArr, pcmPtr, JNI_ABORT);

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
         << ",\"sections\":" << serializeSections(out.sections)
         << "}";

    jclass outCls = env->FindClass("com/zaagforge/studio/dsp/NativeRemixOutput");
    if (!outCls) {
        LOGE("Failed to find NativeRemixOutput");
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(outCls, "<init>", "([F[FLjava/lang/String;)V");
    if (!ctor) {
        LOGE("Failed to find NativeRemixOutput constructor");
        return nullptr;
    }

    jfloatArray pcmOutArr = vectorToJFloatArray(env, out.stereoPcm);
    jfloatArray peaksArr = vectorToJFloatArray(env, out.waveformPeaks);
    jstring metaStr = env->NewStringUTF(json.str().c_str());
    return env->NewObject(outCls, ctor, pcmOutArr, peaksArr, metaStr);
}

JNIEXPORT jfloatArray JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeSynthesizeKickPreview(
    JNIEnv* env,
    jclass /*clazz*/,
    jint kickType,
    jfloat rootHz,
    jfloat bpm,
    jfloat drive,
    jfloat aggression
) {
    const auto kt = static_cast<zaagforge::KickType>(std::max(0, std::min(7, static_cast<int>(kickType))));
    const auto loop = zaagforge::HardcoreSynth::renderKickPreviewLoop(kt, rootHz, bpm, drive, aggression);
    return vectorToJFloatArray(env, loop);
}

JNIEXPORT jfloatArray JNICALL
Java_com_zaagforge_studio_dsp_NativeDspBridge_nativeGenerateDemoTrack(
    JNIEnv* env,
    jclass /*clazz*/,
    jint style,
    jfloat durationSec
) {
    const auto demo = zaagforge::MusicAnalyzer::generateDemoTrack(style, durationSec);
    return vectorToJFloatArray(env, demo);
}

}  // extern "C"
