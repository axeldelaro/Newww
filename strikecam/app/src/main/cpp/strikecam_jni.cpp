#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <vector>

#include "arcade_synth.h"
#include "beatmap_generator.h"
#include "live_beat_tracker.h"

namespace {

constexpr const char* kLogTag = "StrikeCamNative";

jfloatArray packBeatmapResult(JNIEnv* env, const strikecam::BeatmapResult& bm) {
    // Header: [bpm, firstBeatMs, durationMs, targetCount, dropCount]
    // Each target (8 floats): [id, timeMs, type, hand, normX, normY, durationMs, intensity]
    const size_t headerSize = 5;
    const size_t stride = 8;
    const size_t total = headerSize + bm.targets.size() * stride;

    std::vector<float> packed;
    packed.reserve(total);
    packed.push_back(bm.bpm);
    packed.push_back(bm.firstBeatMs);
    packed.push_back(bm.durationMs);
    packed.push_back(static_cast<float>(bm.targets.size()));
    packed.push_back(static_cast<float>(bm.drops.size()));

    for (const auto& t : bm.targets) {
        packed.push_back(static_cast<float>(t.id));
        packed.push_back(t.timeMs);
        packed.push_back(static_cast<float>(static_cast<int32_t>(t.type)));
        packed.push_back(static_cast<float>(static_cast<int32_t>(t.hand)));
        packed.push_back(t.normX);
        packed.push_back(t.normY);
        packed.push_back(t.durationMs);
        packed.push_back(t.intensity);
    }

    jfloatArray out = env->NewFloatArray(static_cast<jsize>(packed.size()));
    if (out != nullptr) {
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(packed.size()), packed.data());
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeGenerateBeatmap(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray pcmData,
    jint sampleRate,
    jint channels,
    jint difficulty
) {
    if (pcmData == nullptr) {
        return env->NewFloatArray(0);
    }
    const jsize len = env->GetArrayLength(pcmData);
    if (len <= 0) {
        return env->NewFloatArray(0);
    }

    jfloat* elems = env->GetFloatArrayElements(pcmData, nullptr);
    if (elems == nullptr) {
        return env->NewFloatArray(0);
    }

    auto diff = static_cast<strikecam::Difficulty>(
        (difficulty < 0) ? 0 : ((difficulty > 3) ? 3 : difficulty)
    );
    strikecam::BeatmapResult bm = strikecam::generateBeatmap(
        elems,
        static_cast<size_t>(len),
        static_cast<int32_t>(sampleRate),
        static_cast<int32_t>(channels),
        diff
    );
    env->ReleaseFloatArrayElements(pcmData, elems, JNI_ABORT);

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "Generated beatmap: bpm=%.1f targets=%zu drops=%zu durationMs=%.0f",
        bm.bpm, bm.targets.size(), bm.drops.size(), bm.durationMs
    );
    return packBeatmapResult(env, bm);
}

JNIEXPORT jshortArray JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeSynthesizeTrack(
    JNIEnv* env,
    jobject /* thiz */,
    jint trackId
) {
    auto floats = strikecam::synthesizeBuiltInTrack(static_cast<int32_t>(trackId), strikecam::kSynthSampleRate);
    auto pcm16 = strikecam::floatToPcm16(floats);

    jshortArray out = env->NewShortArray(static_cast<jsize>(pcm16.size()));
    if (out != nullptr) {
        env->SetShortArrayRegion(out, 0, static_cast<jsize>(pcm16.size()), pcm16.data());
    }
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeGenerateBuiltInBeatmap(
    JNIEnv* env,
    jobject /* thiz */,
    jint trackId,
    jint difficulty
) {
    auto floats = strikecam::synthesizeBuiltInTrack(static_cast<int32_t>(trackId), strikecam::kSynthSampleRate);
    auto diff = static_cast<strikecam::Difficulty>(
        (difficulty < 0) ? 0 : ((difficulty > 3) ? 3 : difficulty)
    );
    strikecam::BeatmapResult bm = strikecam::generateBeatmap(
        floats.data(),
        floats.size(),
        strikecam::kSynthSampleRate,
        2,
        diff
    );
    return packBeatmapResult(env, bm);
}

JNIEXPORT jshortArray JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeSynthesizeSfx(
    JNIEnv* env,
    jobject /* thiz */,
    jint sfxId
) {
    auto floats = strikecam::synthesizeSfx(static_cast<int32_t>(sfxId), strikecam::kSynthSampleRate);
    auto pcm16 = strikecam::floatToPcm16(floats);

    jshortArray out = env->NewShortArray(static_cast<jsize>(pcm16.size()));
    if (out != nullptr) {
        env->SetShortArrayRegion(out, 0, static_cast<jsize>(pcm16.size()), pcm16.data());
    }
    return out;
}

JNIEXPORT jlong JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeCreateLiveTracker(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint sampleRate,
    jfloat initialBpm
) {
    auto* tracker = new strikecam::LiveBeatTracker(static_cast<int32_t>(sampleRate), initialBpm);
    return reinterpret_cast<jlong>(tracker);
}

JNIEXPORT void JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeDestroyLiveTracker(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle != 0) {
        delete reinterpret_cast<strikecam::LiveBeatTracker*>(handle);
    }
}

JNIEXPORT void JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeResetLiveTracker(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jdouble nowMs
) {
    if (handle != 0) {
        reinterpret_cast<strikecam::LiveBeatTracker*>(handle)->reset(nowMs);
    }
}

JNIEXPORT void JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeTrackerSetBpm(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jfloat bpm
) {
    if (handle != 0) {
        reinterpret_cast<strikecam::LiveBeatTracker*>(handle)->setBpm(bpm);
    }
}

JNIEXPORT jfloat JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeTrackerRegisterTap(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jdouble nowMs
) {
    if (handle == 0) return 140.0f;
    return reinterpret_cast<strikecam::LiveBeatTracker*>(handle)->registerTap(nowMs);
}

JNIEXPORT void JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeTrackerNudgePhase(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle,
    jdouble deltaMs
) {
    if (handle != 0) {
        reinterpret_cast<strikecam::LiveBeatTracker*>(handle)->nudgePhaseMs(deltaMs);
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeTrackerProcessAudio(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jfloatArray monoPcm,
    jdouble nowMs
) {
    // Returns [triggered(0/1), isDrop(0/1), beatIndex, strength, estimatedBpm, beatTimeMs]
    float res[6] = {0, 0, 0, 0, 140.0f, static_cast<float>(nowMs)};
    if (handle != 0 && monoPcm != nullptr) {
        const jsize len = env->GetArrayLength(monoPcm);
        jfloat* elems = env->GetFloatArrayElements(monoPcm, nullptr);
        if (elems != nullptr) {
            auto* tracker = reinterpret_cast<strikecam::LiveBeatTracker*>(handle);
            auto ev = tracker->processAudioChunk(elems, static_cast<size_t>(len), nowMs);
            env->ReleaseFloatArrayElements(monoPcm, elems, JNI_ABORT);
            res[0] = ev.triggered ? 1.0f : 0.0f;
            res[1] = ev.isDrop ? 1.0f : 0.0f;
            res[2] = static_cast<float>(ev.beatIndex);
            res[3] = ev.strength;
            res[4] = ev.estimatedBpm;
            res[5] = static_cast<float>(ev.beatTimeMs);
        }
    }
    jfloatArray out = env->NewFloatArray(6);
    if (out != nullptr) {
        env->SetFloatArrayRegion(out, 0, 6, res);
    }
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_strikecam_arcade_nativeengine_StrikeCamNative_nativeTrackerPollGrid(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jdouble nowMs,
    jdouble leadTimeMs
) {
    float res[6] = {0, 0, 0, 0, 140.0f, static_cast<float>(nowMs)};
    if (handle != 0) {
        auto* tracker = reinterpret_cast<strikecam::LiveBeatTracker*>(handle);
        auto ev = tracker->pollGridBeat(nowMs, leadTimeMs);
        res[0] = ev.triggered ? 1.0f : 0.0f;
        res[1] = ev.isDrop ? 1.0f : 0.0f;
        res[2] = static_cast<float>(ev.beatIndex);
        res[3] = ev.strength;
        res[4] = ev.estimatedBpm;
        res[5] = static_cast<float>(ev.beatTimeMs);
    }
    jfloatArray out = env->NewFloatArray(6);
    if (out != nullptr) {
        env->SetFloatArrayRegion(out, 0, 6, res);
    }
    return out;
}

} // extern "C"
