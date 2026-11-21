#include "astro_types.h"

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "AstroProNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using namespace astro;

struct AstroEngineContext {
    std::mutex mtx;

    StackMode activeMode = StackMode::SINGLE_PRO;
    DeepSkyStacker deepSky;
    LuckyImagingStacker lucky;
    StarTrailsStacker starTrails;
    CalibrationBuilder calBuilder;

    bool hasBias = false;
    bool hasDark = false;
    bool hasFlat = false;
    CalibrationMaster masterBias;
    CalibrationMaster masterDark;
    CalibrationMaster masterFlat;

    FloatImageRGB activeLinear;
    FloatImageRGB labProxy;
    AstroMetadata activeMeta;

    void rebuildLabProxy(int maxEdge = 1080) {
        if (!activeLinear.valid()) {
            labProxy.clear();
            return;
        }
        const int w = activeLinear.width;
        const int h = activeLinear.height;
        const int longest = std::max(w, h);
        if (longest <= maxEdge) {
            labProxy = activeLinear;
            return;
        }
        const float scale = static_cast<float>(maxEdge) / static_cast<float>(longest);
        const int pw = std::max(16, static_cast<int>(std::round(w * scale)));
        const int ph = std::max(16, static_cast<int>(std::round(h * scale)));
        labProxy.resize(pw, ph);

        for (int py = 0; py < ph; ++py) {
            const int sy = std::clamp((py * h) / ph, 0, h - 1);
            const size_t sRow = static_cast<size_t>(sy) * w;
            const size_t dRow = static_cast<size_t>(py) * pw;
            for (int px = 0; px < pw; ++px) {
                const int sx = std::clamp((px * w) / pw, 0, w - 1);
                labProxy.r[dRow + px] = activeLinear.r[sRow + sx];
                labProxy.g[dRow + px] = activeLinear.g[sRow + sx];
                labProxy.b[dRow + px] = activeLinear.b[sRow + sx];
            }
        }
    }
};

std::string jstringToStd(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* cstr = env->GetStringUTFChars(js, nullptr);
    if (!cstr) return "";
    std::string out(cstr);
    env->ReleaseStringUTFChars(js, cstr);
    return out;
}

LabParams unpackLabParams(JNIEnv* env, jfloatArray arr) {
    LabParams p{};
    if (!arr) return p;
    const jsize len = env->GetArrayLength(arr);
    if (len < 15) return p;
    jfloat buf[15];
    env->GetFloatArrayRegion(arr, 0, 15, buf);
    p.autoStf           = buf[0] > 0.5f;
    p.blackPoint        = buf[1];
    p.midtones          = buf[2];
    p.whitePoint        = buf[3];
    p.asinhBeta         = buf[4];
    p.bgRemovalStrength = buf[5];
    p.bgNeutralize      = buf[6] > 0.5f;
    p.scnrStrength      = buf[7];
    p.waveletFine       = buf[8];
    p.waveletMedium     = buf[9];
    p.waveletCoarse     = buf[10];
    p.denoiseStrength   = buf[11];
    p.saturation        = buf[12];
    p.temperature       = buf[13];
    p.tint              = buf[14];
    return p;
}

bool renderImageToAndroidBitmap(
    JNIEnv* env,
    const FloatImageRGB& img,
    jobject bitmap,
    bool applySrgbGamma,
    jintArray histRArr = nullptr,
    jintArray histGArr = nullptr,
    jintArray histBArr = nullptr
) {
    if (!bitmap || !img.valid()) return false;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return false;
    }

    int histBins = 0;
    std::vector<int32_t> hR, hG, hB;
    if (histRArr && histGArr && histBArr) {
        histBins = env->GetArrayLength(histRArr);
        if (histBins > 0) {
            hR.resize(static_cast<size_t>(histBins));
            hG.resize(static_cast<size_t>(histBins));
            hB.resize(static_cast<size_t>(histBins));
        }
    }

    // Account for Bitmap stride (info.stride may differ from width*4)
    if (info.stride == info.width * 4) {
        renderFloatRGBToRgba8(
            img,
            static_cast<uint8_t*>(pixels),
            static_cast<int>(info.width),
            static_cast<int>(info.height),
            applySrgbGamma,
            histBins,
            histBins > 0 ? hR.data() : nullptr,
            histBins > 0 ? hG.data() : nullptr,
            histBins > 0 ? hB.data() : nullptr
        );
    } else {
        std::vector<uint8_t> packed(static_cast<size_t>(info.width) * info.height * 4);
        renderFloatRGBToRgba8(
            img, packed.data(),
            static_cast<int>(info.width), static_cast<int>(info.height),
            applySrgbGamma,
            histBins,
            histBins > 0 ? hR.data() : nullptr,
            histBins > 0 ? hG.data() : nullptr,
            histBins > 0 ? hB.data() : nullptr
        );
        uint8_t* dstBytes = static_cast<uint8_t*>(pixels);
        for (uint32_t y = 0; y < info.height; ++y) {
            std::memcpy(dstBytes + static_cast<size_t>(y) * info.stride,
                        packed.data() + static_cast<size_t>(y) * info.width * 4,
                        static_cast<size_t>(info.width) * 4);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    if (histBins > 0) {
        env->SetIntArrayRegion(histRArr, 0, histBins, hR.data());
        env->SetIntArrayRegion(histGArr, 0, histBins, hG.data());
        env->SetIntArrayRegion(histBArr, 0, histBins, hB.data());
    }
    return true;
}

void writeReportArray(JNIEnv* env, jfloatArray outStats, const FrameStackReport& rep) {
    if (!outStats || env->GetArrayLength(outStats) < 8) return;
    jfloat s[8] = {
        rep.accepted ? 1.0f : 0.0f,
        static_cast<float>(rep.totalReceived),
        static_cast<float>(rep.totalAccepted),
        static_cast<float>(rep.totalRejected),
        static_cast<float>(rep.starsDetected),
        static_cast<float>(rep.starsMatched),
        rep.residualPx,
        rep.sharpnessScore
    };
    env->SetFloatArrayRegion(outStats, 0, 8, s);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeCreate(JNIEnv*, jobject) {
    auto* ctx = new AstroEngineContext();
    LOGI("AstroPro 14U Native C++20 Engine initialized");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    delete ctx;
}

JNIEXPORT void JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeAnalyzePreviewYuv(
    JNIEnv* env, jobject, jlong /*handle*/,
    jobject yBuf, jint yRowStride, jint yPixelStride,
    jobject uBuf, jint uRowStride, jint uPixelStride,
    jobject vBuf, jint vRowStride, jint vPixelStride,
    jint width, jint height,
    jintArray histRArr, jintArray histGArr, jintArray histBArr,
    jboolean computeLoupe, jfloat loupeNormX, jfloat loupeNormY,
    jobject loupeBitmap, jfloatArray outMetricsArr
) {
    auto* yPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    auto* uPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    auto* vPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!yPtr || !uPtr || !vPtr) return;

    int histBins = (histRArr && histGArr && histBArr) ? env->GetArrayLength(histRArr) : 0;
    std::vector<int32_t> hR(histBins), hG(histBins), hB(histBins);

    uint8_t* loupePixels = nullptr;
    int loupeSize = 0;
    bool lockedBitmap = false;
    if (computeLoupe && loupeBitmap != nullptr) {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, loupeBitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
            info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 &&
            info.width == info.height &&
            info.stride == info.width * 4) {
            void* p = nullptr;
            if (AndroidBitmap_lockPixels(env, loupeBitmap, &p) == ANDROID_BITMAP_RESULT_SUCCESS && p) {
                loupePixels = static_cast<uint8_t*>(p);
                loupeSize = static_cast<int>(info.width);
                lockedBitmap = true;
            }
        }
    }

    FocusMetrics fm{};
    analyzePreviewYuv(
        yPtr, yRowStride, yPixelStride,
        uPtr, uRowStride, uPixelStride,
        vPtr, vRowStride, vPixelStride,
        width, height,
        histBins,
        histBins > 0 ? hR.data() : nullptr,
        histBins > 0 ? hG.data() : nullptr,
        histBins > 0 ? hB.data() : nullptr,
        computeLoupe == JNI_TRUE,
        loupeNormX, loupeNormY,
        loupeSize, loupePixels,
        fm
    );

    if (lockedBitmap) {
        AndroidBitmap_unlockPixels(env, loupeBitmap);
    }

    if (histBins > 0) {
        env->SetIntArrayRegion(histRArr, 0, histBins, hR.data());
        env->SetIntArrayRegion(histGArr, 0, histBins, hG.data());
        env->SetIntArrayRegion(histBArr, 0, histBins, hB.data());
    }

    if (outMetricsArr && env->GetArrayLength(outMetricsArr) >= 8) {
        jfloat m[8] = {
            fm.fwhm, fm.hfd, fm.snr, fm.peakValue,
            fm.bgMedian, fm.bgStd, fm.tenengrad,
            static_cast<float>(fm.starFound)
        };
        env->SetFloatArrayRegion(outMetricsArr, 0, 8, m);
    }
}

JNIEXPORT jint JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeComputeFocusPeakingPoints(
    JNIEnv* env, jobject, jlong /*handle*/,
    jobject yBuf, jint yRowStride, jint yPixelStride,
    jint width, jint height,
    jfloat threshold,
    jfloatArray outPointsXY
) {
    if (!yBuf || !outPointsXY) return 0;
    auto* yPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    if (!yPtr) return 0;

    const jsize arrLen = env->GetArrayLength(outPointsXY);
    const int maxPoints = static_cast<int>(arrLen / 2);
    if (maxPoints <= 0) return 0;

    std::vector<float> tmp(static_cast<size_t>(maxPoints) * 2);
    const int count = computeFocusPeakingPoints(
        yPtr, yRowStride, yPixelStride,
        width, height,
        threshold,
        maxPoints,
        tmp.data()
    );
    if (count > 0) {
        env->SetFloatArrayRegion(outPointsXY, 0, count * 2, tmp.data());
    }
    return count;
}

JNIEXPORT void JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeStartStackSession(
    JNIEnv*, jobject, jlong handle,
    jint modeInt, jfloat param1, jfloat param2, jfloat param3
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    ctx->activeMode = static_cast<StackMode>(std::clamp(modeInt, 0, 4));
    switch (ctx->activeMode) {
        case StackMode::DEEP_SKY:
            ctx->deepSky.reset(param1 > 0.5f ? param1 : 2.5f, param2 > 0.5f ? param2 : 3.0f);
            break;
        case StackMode::LUCKY_PLANETARY:
            ctx->lucky.reset(static_cast<int>(std::max(5.0f, param1)),
                             param2 > 0.01f ? param2 : 0.25f,
                             param3);
            break;
        case StackMode::STAR_TRAILS_STANDARD:
            ctx->starTrails.reset(false, 1.0f);
            break;
        case StackMode::STAR_TRAILS_COMET:
            ctx->starTrails.reset(true, param1 > 0.5f ? param1 : 0.96f);
            break;
        case StackMode::SINGLE_PRO:
            break;
    }
}

JNIEXPORT void JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeAddRawFrame(
    JNIEnv* env, jobject, jlong handle,
    jobject rawBuf, jint width, jint height, jint rowStrideBytes,
    jint cfaInt, jfloatArray blackLevel4, jfloat whiteLevel,
    jfloatArray wbGains3, jboolean bin2x2,
    jobject livePreviewBitmap, jfloatArray outStats
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx || !rawBuf) return;
    auto* rawPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(rawBuf));
    if (!rawPtr) return;

    RawParams rp{};
    rp.width = width;
    rp.height = height;
    rp.rowStrideBytes = rowStrideBytes;
    rp.cfa = static_cast<CfaPattern>(std::clamp(cfaInt, 0, 3));
    rp.whiteLevel = whiteLevel > 1.0f ? whiteLevel : 1023.0f;
    rp.bin2x2 = (bin2x2 == JNI_TRUE);
    if (blackLevel4 && env->GetArrayLength(blackLevel4) >= 4) {
        env->GetFloatArrayRegion(blackLevel4, 0, 4, rp.blackLevel);
    }
    if (wbGains3 && env->GetArrayLength(wbGains3) >= 3) {
        env->GetFloatArrayRegion(wbGains3, 0, 3, rp.wbGains);
    }

    std::lock_guard<std::mutex> lock(ctx->mtx);
    FloatImageRGB rgb;
    processRaw16ToFloatRGB(
        rawPtr, rp,
        ctx->hasBias ? &ctx->masterBias : nullptr,
        ctx->hasDark ? &ctx->masterDark : nullptr,
        ctx->hasFlat ? &ctx->masterFlat : nullptr,
        rgb
    );

    FrameStackReport rep{};
    if (ctx->activeMode == StackMode::DEEP_SKY) {
        rep = ctx->deepSky.addFrame(rgb);
        if (livePreviewBitmap != nullptr) {
            FloatImageRGB cur, stretched;
            ctx->deepSky.getCurrentImage(cur);
            LabParams lp{};
            lp.autoStf = true;
            lp.bgNeutralize = true;
            processAstroLab(cur, lp, stretched);
            renderImageToAndroidBitmap(env, stretched, livePreviewBitmap, false);
        }
    } else if (ctx->activeMode == StackMode::LUCKY_PLANETARY) {
        rep = ctx->lucky.addFrame(rgb);
        if (livePreviewBitmap != nullptr && rgb.valid()) {
            renderImageToAndroidBitmap(env, rgb, livePreviewBitmap, true);
        }
    } else if (ctx->activeMode == StackMode::STAR_TRAILS_STANDARD ||
               ctx->activeMode == StackMode::STAR_TRAILS_COMET) {
        rep = ctx->starTrails.addFrame(rgb);
        if (livePreviewBitmap != nullptr && ctx->starTrails.currentImage().valid()) {
            renderImageToAndroidBitmap(env, ctx->starTrails.currentImage(), livePreviewBitmap, true);
        }
    } else {
        ctx->activeLinear = std::move(rgb);
        ctx->rebuildLabProxy();
        rep.accepted = ctx->activeLinear.valid();
        rep.totalReceived = 1;
        rep.totalAccepted = rep.accepted ? 1 : 0;
        if (livePreviewBitmap != nullptr && ctx->labProxy.valid()) {
            FloatImageRGB stretched;
            LabParams lp{};
            lp.autoStf = true;
            lp.bgNeutralize = true;
            processAstroLab(ctx->labProxy, lp, stretched);
            renderImageToAndroidBitmap(env, stretched, livePreviewBitmap, false);
        }
    }
    writeReportArray(env, outStats, rep);
}

JNIEXPORT void JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeAddYuvFrame(
    JNIEnv* env, jobject, jlong handle,
    jobject yBuf, jint yRowStride, jint yPixelStride,
    jobject uBuf, jint uRowStride, jint uPixelStride,
    jobject vBuf, jint vRowStride, jint vPixelStride,
    jint width, jint height, jint downsampleStep,
    jobject livePreviewBitmap, jfloatArray outStats
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx || !yBuf || !uBuf || !vBuf) return;
    auto* yPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    auto* uPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    auto* vPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!yPtr || !uPtr || !vPtr) return;

    std::lock_guard<std::mutex> lock(ctx->mtx);
    FloatImageRGB rgb;
    convertYuv420ToFloatRGB(
        yPtr, yRowStride, yPixelStride,
        uPtr, uRowStride, uPixelStride,
        vPtr, vRowStride, vPixelStride,
        width, height, downsampleStep,
        (ctx->hasDark && ctx->masterDark.channels == 3) ? &ctx->masterDark : nullptr,
        (ctx->hasFlat && ctx->masterFlat.channels == 3) ? &ctx->masterFlat : nullptr,
        rgb
    );

    FrameStackReport rep{};
    if (ctx->activeMode == StackMode::DEEP_SKY) {
        rep = ctx->deepSky.addFrame(rgb);
        if (livePreviewBitmap != nullptr) {
            FloatImageRGB cur, stretched;
            ctx->deepSky.getCurrentImage(cur);
            LabParams lp{};
            lp.autoStf = true;
            lp.bgNeutralize = true;
            processAstroLab(cur, lp, stretched);
            renderImageToAndroidBitmap(env, stretched, livePreviewBitmap, false);
        }
    } else if (ctx->activeMode == StackMode::LUCKY_PLANETARY) {
        rep = ctx->lucky.addFrame(rgb);
        if (livePreviewBitmap != nullptr && rgb.valid()) {
            renderImageToAndroidBitmap(env, rgb, livePreviewBitmap, true);
        }
    } else if (ctx->activeMode == StackMode::STAR_TRAILS_STANDARD ||
               ctx->activeMode == StackMode::STAR_TRAILS_COMET) {
        rep = ctx->starTrails.addFrame(rgb);
        if (livePreviewBitmap != nullptr && ctx->starTrails.currentImage().valid()) {
            renderImageToAndroidBitmap(env, ctx->starTrails.currentImage(), livePreviewBitmap, true);
        }
    } else {
        ctx->activeLinear = std::move(rgb);
        ctx->rebuildLabProxy();
        rep.accepted = ctx->activeLinear.valid();
        rep.totalReceived = 1;
        rep.totalAccepted = rep.accepted ? 1 : 0;
        if (livePreviewBitmap != nullptr && ctx->labProxy.valid()) {
            renderImageToAndroidBitmap(env, ctx->labProxy, livePreviewBitmap, true);
        }
    }
    writeReportArray(env, outStats, rep);
}

JNIEXPORT jboolean JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeFinishStackSession(
    JNIEnv* env, jobject, jlong handle,
    jstring cameraModel, jstring lensName, jstring dateObs, jstring modeName,
    jfloat focalMm, jfloat apertureF, jfloat exposureSec, jfloat iso,
    jstring saveAstro32Path, jobject outPreviewBitmap
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(ctx->mtx);

    FloatImageRGB result;
    int stackCnt = 1;
    int rejCnt = 0;
    float avgRes = 0.0f;

    if (ctx->activeMode == StackMode::DEEP_SKY) {
        ctx->deepSky.getCurrentImage(result);
        stackCnt = ctx->deepSky.acceptedCount();
        rejCnt = ctx->deepSky.rejectedCount();
        avgRes = ctx->deepSky.avgResidual();
    } else if (ctx->activeMode == StackMode::LUCKY_PLANETARY) {
        ctx->lucky.finalize(result);
        stackCnt = std::max(1, ctx->lucky.receivedCount());
    } else if (ctx->activeMode == StackMode::STAR_TRAILS_STANDARD ||
               ctx->activeMode == StackMode::STAR_TRAILS_COMET) {
        result = ctx->starTrails.currentImage();
        stackCnt = std::max(1, ctx->starTrails.frameCount());
    } else {
        result = ctx->activeLinear;
    }

    if (!result.valid()) return JNI_FALSE;

    ctx->activeLinear = std::move(result);
    ctx->activeMeta.cameraModel = jstringToStd(env, cameraModel);
    ctx->activeMeta.lensName = jstringToStd(env, lensName);
    ctx->activeMeta.dateObs = jstringToStd(env, dateObs);
    ctx->activeMeta.modeName = jstringToStd(env, modeName);
    ctx->activeMeta.focalLengthMm = focalMm;
    ctx->activeMeta.apertureF = apertureF;
    ctx->activeMeta.exposureSec = exposureSec;
    ctx->activeMeta.iso = iso;
    ctx->activeMeta.stackCount = stackCnt;
    ctx->activeMeta.rejectedCount = rejCnt;
    ctx->activeMeta.avgResidualPx = avgRes;
    ctx->rebuildLabProxy();

    const std::string path = jstringToStd(env, saveAstro32Path);
    if (!path.empty()) {
        saveAstro32(path, ctx->activeLinear, ctx->activeMeta);
    }

    if (outPreviewBitmap != nullptr && ctx->labProxy.valid()) {
        FloatImageRGB stretched;
        LabParams lp{};
        lp.autoStf = (ctx->activeMode == StackMode::DEEP_SKY || ctx->activeMode == StackMode::SINGLE_PRO);
        lp.bgNeutralize = (ctx->activeMode == StackMode::DEEP_SKY);
        processAstroLab(ctx->labProxy, lp, stretched);
        renderImageToAndroidBitmap(env, stretched, outPreviewBitmap, !lp.autoStf);
    }
    return JNI_TRUE;
}

// ---------------- Calibration Builder & Loader ----------------

JNIEXPORT void JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeStartCalibration(
    JNIEnv*, jobject, jlong handle,
    jint calTypeInt, jint width, jint height, jint channels, jint cfaInt,
    jfloat iso, jfloat exposureSec, jfloat focalMm
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    ctx->calBuilder.reset(
        static_cast<CalibrationType>(std::clamp(calTypeInt, 0, 2)),
        width, height, channels,
        static_cast<CfaPattern>(std::clamp(cfaInt, 0, 3)),
        iso, exposureSec, focalMm
    );
}

JNIEXPORT jint JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeAddCalibrationRawFrame(
    JNIEnv* env, jobject, jlong handle,
    jobject rawBuf, jint width, jint height, jint rowStrideBytes,
    jint cfaInt, jfloatArray blackLevel4, jfloat whiteLevel
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx || !rawBuf) return 0;
    auto* rawPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(rawBuf));
    if (!rawPtr) return 0;

    RawParams rp{};
    rp.width = width;
    rp.height = height;
    rp.rowStrideBytes = rowStrideBytes;
    rp.cfa = static_cast<CfaPattern>(std::clamp(cfaInt, 0, 3));
    rp.whiteLevel = whiteLevel > 1.0f ? whiteLevel : 1023.0f;
    if (blackLevel4 && env->GetArrayLength(blackLevel4) >= 4) {
        env->GetFloatArrayRegion(blackLevel4, 0, 4, rp.blackLevel);
    }

    std::lock_guard<std::mutex> lock(ctx->mtx);
    ctx->calBuilder.addBayerFrame(rawPtr, rp);
    return ctx->calBuilder.frameCount();
}

JNIEXPORT jint JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeAddCalibrationYuvFrame(
    JNIEnv* env, jobject, jlong handle,
    jobject yBuf, jint yRowStride, jint yPixelStride,
    jobject uBuf, jint uRowStride, jint uPixelStride,
    jobject vBuf, jint vRowStride, jint vPixelStride,
    jint width, jint height, jint downsampleStep
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx || !yBuf || !uBuf || !vBuf) return 0;
    auto* yPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    auto* uPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    auto* vPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!yPtr || !uPtr || !vPtr) return 0;

    FloatImageRGB rgb;
    convertYuv420ToFloatRGB(
        yPtr, yRowStride, yPixelStride,
        uPtr, uRowStride, uPixelStride,
        vPtr, vRowStride, vPixelStride,
        width, height, downsampleStep,
        nullptr, nullptr, rgb
    );
    std::lock_guard<std::mutex> lock(ctx->mtx);
    ctx->calBuilder.addRgbFrame(rgb);
    return ctx->calBuilder.frameCount();
}

JNIEXPORT jboolean JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeFinishCalibration(
    JNIEnv* env, jobject, jlong handle, jstring outputPath
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    CalibrationMaster master = ctx->calBuilder.build(ctx->hasBias ? &ctx->masterBias : nullptr);
    if (!master.valid()) return JNI_FALSE;

    const std::string path = jstringToStd(env, outputPath);
    const bool ok = saveCalibrationMaster(path, master);
    if (ok) {
        if (master.type == CalibrationType::DARK) {
            ctx->masterDark = std::move(master);
            ctx->hasDark = true;
        } else if (master.type == CalibrationType::FLAT) {
            ctx->masterFlat = std::move(master);
            ctx->hasFlat = true;
        } else {
            ctx->masterBias = std::move(master);
            ctx->hasBias = true;
        }
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeLoadCalibrationMaster(
    JNIEnv* env, jobject, jlong handle, jstring pathStr
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx) return JNI_FALSE;
    CalibrationMaster master;
    if (!loadCalibrationMaster(jstringToStd(env, pathStr), master)) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(ctx->mtx);
    if (master.type == CalibrationType::DARK) {
        ctx->masterDark = std::move(master);
        ctx->hasDark = true;
    } else if (master.type == CalibrationType::FLAT) {
        ctx->masterFlat = std::move(master);
        ctx->hasFlat = true;
    } else {
        ctx->masterBias = std::move(master);
        ctx->hasBias = true;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeClearCalibrationMasters(
    JNIEnv*, jobject, jlong handle, jboolean clearDark, jboolean clearFlat, jboolean clearBias
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx) return;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    if (clearDark) { ctx->hasDark = false; ctx->masterDark = CalibrationMaster{}; }
    if (clearFlat) { ctx->hasFlat = false; ctx->masterFlat = CalibrationMaster{}; }
    if (clearBias) { ctx->hasBias = false; ctx->masterBias = CalibrationMaster{}; }
}

// ---------------- 16-Bit Astro Lab & Exports ----------------

JNIEXPORT jboolean JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeLoadAstro32(
    JNIEnv* env, jobject, jlong handle, jstring pathStr, jintArray outDims
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx) return JNI_FALSE;
    FloatImageRGB img;
    AstroMetadata meta;
    if (!loadAstro32(jstringToStd(env, pathStr), img, meta)) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(ctx->mtx);
    ctx->activeLinear = std::move(img);
    ctx->activeMeta = std::move(meta);
    ctx->rebuildLabProxy();

    if (outDims && env->GetArrayLength(outDims) >= 2) {
        jint d[2] = {ctx->activeLinear.width, ctx->activeLinear.height};
        env->SetIntArrayRegion(outDims, 0, 2, d);
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeRenderLabPreview(
    JNIEnv* env, jobject, jlong handle,
    jfloatArray labParamsArr, jboolean showOriginal,
    jobject outBitmap,
    jintArray histRArr, jintArray histGArr, jintArray histBArr
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx || !outBitmap) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    if (!ctx->labProxy.valid()) return JNI_FALSE;

    FloatImageRGB processed;
    if (showOriginal == JNI_TRUE) {
        processed = ctx->labProxy;
        return renderImageToAndroidBitmap(env, processed, outBitmap, true, histRArr, histGArr, histBArr)
            ? JNI_TRUE : JNI_FALSE;
    }

    const LabParams lp = unpackLabParams(env, labParamsArr);
    processAstroLab(ctx->labProxy, lp, processed);
    return renderImageToAndroidBitmap(env, processed, outBitmap, false, histRArr, histGArr, histBArr)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeExportTiff16(
    JNIEnv* env, jobject, jlong handle,
    jstring pathStr, jfloatArray labParamsArr, jboolean applyLab
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    if (!ctx->activeLinear.valid()) return JNI_FALSE;

    if (applyLab == JNI_TRUE) {
        FloatImageRGB processed;
        processAstroLab(ctx->activeLinear, unpackLabParams(env, labParamsArr), processed);
        return writeTiff16RGB(jstringToStd(env, pathStr), processed, ctx->activeMeta) ? JNI_TRUE : JNI_FALSE;
    }
    return writeTiff16RGB(jstringToStd(env, pathStr), ctx->activeLinear, ctx->activeMeta) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeExportFits(
    JNIEnv* env, jobject, jlong handle,
    jstring pathStr, jfloatArray labParamsArr, jboolean applyLab, jboolean asFloat32
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    if (!ctx->activeLinear.valid()) return JNI_FALSE;

    if (applyLab == JNI_TRUE) {
        FloatImageRGB processed;
        processAstroLab(ctx->activeLinear, unpackLabParams(env, labParamsArr), processed);
        return writeFitsImage(jstringToStd(env, pathStr), processed, ctx->activeMeta, asFloat32 == JNI_TRUE)
            ? JNI_TRUE : JNI_FALSE;
    }
    return writeFitsImage(jstringToStd(env, pathStr), ctx->activeLinear, ctx->activeMeta, asFloat32 == JNI_TRUE)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeRenderFullExportBitmap(
    JNIEnv* env, jobject, jlong handle,
    jfloatArray labParamsArr, jobject outBitmap
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    if (!ctx || !outBitmap) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(ctx->mtx);
    if (!ctx->activeLinear.valid()) return JNI_FALSE;

    FloatImageRGB processed;
    processAstroLab(ctx->activeLinear, unpackLabParams(env, labParamsArr), processed);
    return renderImageToAndroidBitmap(env, processed, outBitmap, false) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_astropro_xiaomi14u_nativeengine_AstroNativeEngine_nativeGetActiveSize(
    JNIEnv* env, jobject, jlong handle
) {
    auto* ctx = reinterpret_cast<AstroEngineContext*>(handle);
    jintArray out = env->NewIntArray(2);
    jint dims[2] = {0, 0};
    if (ctx) {
        std::lock_guard<std::mutex> lock(ctx->mtx);
        dims[0] = ctx->activeLinear.width;
        dims[1] = ctx->activeLinear.height;
    }
    env->SetIntArrayRegion(out, 0, 2, dims);
    return out;
}

} // extern "C"
