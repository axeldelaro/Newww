package com.astropro.xiaomi14u.nativeengine

import android.graphics.Bitmap
import java.nio.ByteBuffer

data class NativeFocusMetrics(
    val fwhm: Float = 0f,
    val hfd: Float = 0f,
    val snr: Float = 0f,
    val peakValue: Float = 0f,
    val bgMedian: Float = 0f,
    val bgStd: Float = 0f,
    val tenengrad: Float = 0f,
    val starFound: Boolean = false
)

data class BestFocusStarTarget(
    val found: Boolean = false,
    val isStar: Boolean = false,
    val normX: Float = 0.5f,
    val normY: Float = 0.5f,
    val snr: Float = 0f
)

data class NativeStackReport(
    val accepted: Boolean = false,
    val totalReceived: Int = 0,
    val totalAccepted: Int = 0,
    val totalRejected: Int = 0,
    val starsDetected: Int = 0,
    val starsMatched: Int = 0,
    val residualPx: Float = 0f,
    val sharpnessScore: Float = 0f
)

data class LabParameters(
    val autoStf: Boolean = true,
    val blackPoint: Float = 0.0f,
    val midtones: Float = 0.5f,
    val whitePoint: Float = 1.0f,
    val asinhBeta: Float = 0.0f,
    val bgRemovalStrength: Float = 0.0f,
    val bgNeutralize: Boolean = true,
    val scnrStrength: Float = 0.0f,
    val waveletFine: Float = 0.0f,
    val waveletMedium: Float = 0.0f,
    val waveletCoarse: Float = 0.0f,
    val denoiseStrength: Float = 0.0f,
    val saturation: Float = 1.0f,
    val temperature: Float = 0.0f,
    val tint: Float = 0.0f,
    val deconvolution: Float = 0.0f,
    val chromaticAberrationFix: Float = 0.65f
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        if (autoStf) 1f else 0f,
        blackPoint,
        midtones,
        whitePoint,
        asinhBeta,
        bgRemovalStrength,
        if (bgNeutralize) 1f else 0f,
        scnrStrength,
        waveletFine,
        waveletMedium,
        waveletCoarse,
        denoiseStrength,
        saturation,
        temperature,
        tint,
        deconvolution,
        chromaticAberrationFix
    )
}

enum class LabPreset(val label: String, val params: LabParameters) {
    AUTO_STF(
        label = "Auto-STF",
        params = LabParameters(
            autoStf = true,
            bgNeutralize = true,
            deconvolution = 0.25f,
            chromaticAberrationFix = 0.65f
        )
    ),
    MILKY_WAY(
        label = "Voie Lactée",
        params = LabParameters(
            autoStf = true,
            asinhBeta = 65f,
            bgRemovalStrength = 0.45f,
            bgNeutralize = true,
            scnrStrength = 0.25f,
            waveletFine = 0.15f,
            denoiseStrength = 0.20f,
            saturation = 1.25f,
            temperature = 0.08f,
            tint = 0.0f,
            deconvolution = 0.25f,
            chromaticAberrationFix = 0.70f
        )
    ),
    NEBULA(
        label = "Nébuleuse",
        params = LabParameters(
            autoStf = true,
            asinhBeta = 110f,
            bgRemovalStrength = 0.60f,
            bgNeutralize = true,
            scnrStrength = 0.75f,
            waveletMedium = 0.25f,
            denoiseStrength = 0.30f,
            saturation = 1.35f,
            temperature = 0.0f,
            tint = 0.05f,
            deconvolution = 0.30f,
            chromaticAberrationFix = 0.70f
        )
    ),
    CRISP_MOON(
        label = "Lune Ciselée",
        params = LabParameters(
            autoStf = false,
            blackPoint = 0.04f,
            midtones = 0.44f,
            whitePoint = 0.96f,
            asinhBeta = 0f,
            bgRemovalStrength = 0f,
            bgNeutralize = false,
            scnrStrength = 0f,
            waveletFine = 0.65f,
            waveletMedium = 0.40f,
            waveletCoarse = 0.10f,
            denoiseStrength = 0.08f,
            saturation = 1.05f,
            temperature = 0f,
            tint = 0f,
            deconvolution = 0.55f,
            chromaticAberrationFix = 0.75f
        )
    ),
    URBAN_SKY(
        label = "Ciel Urbain",
        params = LabParameters(
            autoStf = true,
            blackPoint = 0.08f,
            asinhBeta = 45f,
            bgRemovalStrength = 0.92f,
            bgNeutralize = true,
            scnrStrength = 0.85f,
            denoiseStrength = 0.28f,
            saturation = 1.15f,
            temperature = -0.05f,
            tint = 0f,
            deconvolution = 0.20f,
            chromaticAberrationFix = 0.65f
        )
    ),
    RESET(
        label = "Réinitialiser",
        params = LabParameters(
            autoStf = false,
            blackPoint = 0f,
            midtones = 0.5f,
            whitePoint = 1.0f,
            asinhBeta = 0f,
            bgRemovalStrength = 0f,
            bgNeutralize = false,
            scnrStrength = 0f,
            waveletFine = 0f,
            waveletMedium = 0f,
            waveletCoarse = 0f,
            denoiseStrength = 0f,
            saturation = 1.0f,
            temperature = 0f,
            tint = 0f,
            deconvolution = 0f,
            chromaticAberrationFix = 0f
        )
    )
}

class AstroNativeEngine : AutoCloseable {

    companion object {
        const val STACK_MODE_DEEP_SKY = 0
        const val STACK_MODE_LUCKY_PLANETARY = 1
        const val STACK_MODE_STAR_TRAILS_STANDARD = 2
        const val STACK_MODE_STAR_TRAILS_COMET = 3
        const val STACK_MODE_SINGLE_PRO = 4
        const val STACK_MODE_HDR_ASTRO = 5

        const val FLAG_LANDSCAPE_AUTO = 1 shl 0
        const val FLAG_DRIZZLE_2X = 1 shl 1

        const val CAL_TYPE_DARK = 0
        const val CAL_TYPE_FLAT = 1
        const val CAL_TYPE_BIAS = 2

        init {
            System.loadLibrary("astropro_native")
        }
    }

    @Volatile
    private var handle: Long = nativeCreate()

    private fun checkHandle(): Long {
        val h = handle
        check(h != 0L) { "AstroNativeEngine has been closed" }
        return h
    }

    fun analyzePreviewYuv(
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuf: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuf: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int,
        histR: IntArray, histG: IntArray, histB: IntArray,
        computeLoupe: Boolean,
        loupeNormX: Float, loupeNormY: Float,
        loupeBitmap: Bitmap?
    ): NativeFocusMetrics {
        val h = handle
        if (h == 0L) return NativeFocusMetrics()
        val outMetrics = FloatArray(8)
        nativeAnalyzePreviewYuv(
            h,
            yBuf, yRowStride, yPixelStride,
            uBuf, uRowStride, uPixelStride,
            vBuf, vRowStride, vPixelStride,
            width, height,
            histR, histG, histB,
            computeLoupe, loupeNormX, loupeNormY,
            loupeBitmap, outMetrics
        )
        return NativeFocusMetrics(
            fwhm = outMetrics[0],
            hfd = outMetrics[1],
            snr = outMetrics[2],
            peakValue = outMetrics[3],
            bgMedian = outMetrics[4],
            bgStd = outMetrics[5],
            tenengrad = outMetrics[6],
            starFound = outMetrics[7] > 0.5f
        )
    }

    fun computeFocusPeakingPoints(
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        width: Int, height: Int,
        threshold: Float = 0.14f,
        outPointsXY: FloatArray
    ): Int {
        val h = handle
        if (h == 0L) return 0
        return nativeComputeFocusPeakingPoints(
            h, yBuf, yRowStride, yPixelStride, width, height, threshold, outPointsXY
        )
    }

    fun findBestFocusStar(
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        width: Int, height: Int
    ): BestFocusStarTarget {
        val h = handle
        if (h == 0L) return BestFocusStarTarget()
        val out = FloatArray(5)
        val ok = nativeFindBestFocusStar(h, yBuf, yRowStride, yPixelStride, width, height, out)
        return BestFocusStarTarget(
            found = ok && out[0] > 0.5f,
            isStar = out[1] > 0.5f,
            normX = out[2],
            normY = out[3],
            snr = out[4]
        )
    }

    fun startStackSession(
        mode: Int,
        param1: Float = 2.5f,
        param2: Float = 3.0f,
        param3: Float = 0.0f,
        flags: Int = 0
    ) {
        nativeStartStackSession(checkHandle(), mode, param1, param2, param3, flags)
    }

    fun hasDetectedForeground(): Boolean {
        val h = handle
        if (h == 0L) return false
        return nativeHasDetectedForeground(h)
    }

    fun addRawFrame(
        rawBuf: ByteBuffer,
        width: Int, height: Int, rowStrideBytes: Int,
        cfaPattern: Int,
        blackLevel4: FloatArray,
        whiteLevel: Float,
        wbGains3: FloatArray,
        bin2x2: Boolean,
        livePreviewBitmap: Bitmap?,
        evMultiplier: Float = 1.0f
    ): NativeStackReport {
        val outStats = FloatArray(8)
        nativeAddRawFrame(
            checkHandle(), rawBuf, width, height, rowStrideBytes,
            cfaPattern, blackLevel4, whiteLevel, wbGains3, bin2x2, evMultiplier,
            livePreviewBitmap, outStats
        )
        return parseStackReport(outStats)
    }

    fun addYuvFrame(
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuf: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuf: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int, downsampleStep: Int,
        livePreviewBitmap: Bitmap?,
        evMultiplier: Float = 1.0f
    ): NativeStackReport {
        val outStats = FloatArray(8)
        nativeAddYuvFrame(
            checkHandle(),
            yBuf, yRowStride, yPixelStride,
            uBuf, uRowStride, uPixelStride,
            vBuf, vRowStride, vPixelStride,
            width, height, downsampleStep, evMultiplier,
            livePreviewBitmap, outStats
        )
        return parseStackReport(outStats)
    }

    fun finishStackSession(
        cameraModel: String,
        lensName: String,
        dateObs: String,
        modeName: String,
        focalMm: Float,
        apertureF: Float,
        exposureSec: Float,
        iso: Float,
        saveAstro32Path: String,
        outPreviewBitmap: Bitmap?
    ): Boolean = nativeFinishStackSession(
        checkHandle(),
        cameraModel, lensName, dateObs, modeName,
        focalMm, apertureF, exposureSec, iso,
        saveAstro32Path, outPreviewBitmap
    )

    fun startCalibration(
        calType: Int,
        width: Int, height: Int, channels: Int, cfa: Int,
        iso: Float, exposureSec: Float, focalMm: Float
    ) {
        nativeStartCalibration(checkHandle(), calType, width, height, channels, cfa, iso, exposureSec, focalMm)
    }

    fun addCalibrationRawFrame(
        rawBuf: ByteBuffer,
        width: Int, height: Int, rowStrideBytes: Int,
        cfa: Int, blackLevel4: FloatArray, whiteLevel: Float
    ): Int = nativeAddCalibrationRawFrame(
        checkHandle(), rawBuf, width, height, rowStrideBytes, cfa, blackLevel4, whiteLevel
    )

    fun addCalibrationYuvFrame(
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuf: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuf: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int, downsampleStep: Int
    ): Int = nativeAddCalibrationYuvFrame(
        checkHandle(),
        yBuf, yRowStride, yPixelStride,
        uBuf, uRowStride, uPixelStride,
        vBuf, vRowStride, vPixelStride,
        width, height, downsampleStep
    )

    fun finishCalibration(outputPath: String): Boolean =
        nativeFinishCalibration(checkHandle(), outputPath)

    fun loadCalibrationMaster(path: String): Boolean =
        nativeLoadCalibrationMaster(checkHandle(), path)

    fun clearCalibrationMasters(clearDark: Boolean, clearFlat: Boolean, clearBias: Boolean) {
        val h = handle
        if (h != 0L) nativeClearCalibrationMasters(h, clearDark, clearFlat, clearBias)
    }

    fun loadAstro32(path: String): Pair<Int, Int>? {
        val dims = IntArray(2)
        val ok = nativeLoadAstro32(checkHandle(), path, dims)
        return if (ok) Pair(dims[0], dims[1]) else null
    }

    fun renderLabPreview(
        params: LabParameters,
        showOriginal: Boolean,
        outBitmap: Bitmap,
        histR: IntArray? = null,
        histG: IntArray? = null,
        histB: IntArray? = null
    ): Boolean = nativeRenderLabPreview(
        checkHandle(), params.toFloatArray(), showOriginal, outBitmap, histR, histG, histB
    )

    fun exportTiff16(path: String, params: LabParameters, applyLab: Boolean = true): Boolean =
        nativeExportTiff16(checkHandle(), path, params.toFloatArray(), applyLab)

    fun exportFits(path: String, params: LabParameters, applyLab: Boolean = false, asFloat32: Boolean = true): Boolean =
        nativeExportFits(checkHandle(), path, params.toFloatArray(), applyLab, asFloat32)

    fun renderFullExportBitmap(params: LabParameters, outBitmap: Bitmap): Boolean =
        nativeRenderFullExportBitmap(checkHandle(), params.toFloatArray(), outBitmap)

    fun getActiveImageSize(): Pair<Int, Int> {
        val h = handle
        if (h == 0L) return Pair(0, 0)
        val arr = nativeGetActiveSize(h)
        return Pair(arr.getOrElse(0) { 0 }, arr.getOrElse(1) { 0 })
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            nativeDestroy(h)
        }
    }

    private fun parseStackReport(a: FloatArray) = NativeStackReport(
        accepted = a[0] > 0.5f,
        totalReceived = a[1].toInt(),
        totalAccepted = a[2].toInt(),
        totalRejected = a[3].toInt(),
        starsDetected = a[4].toInt(),
        starsMatched = a[5].toInt(),
        residualPx = a[6],
        sharpnessScore = a[7]
    )

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)

    private external fun nativeAnalyzePreviewYuv(
        handle: Long,
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuf: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuf: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int,
        histR: IntArray, histG: IntArray, histB: IntArray,
        computeLoupe: Boolean, loupeNormX: Float, loupeNormY: Float,
        loupeBitmap: Bitmap?, outMetrics: FloatArray
    )

    private external fun nativeComputeFocusPeakingPoints(
        handle: Long,
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        width: Int, height: Int,
        threshold: Float,
        outPointsXY: FloatArray
    ): Int

    private external fun nativeFindBestFocusStar(
        handle: Long,
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        width: Int, height: Int,
        outResult5: FloatArray
    ): Boolean

    private external fun nativeStartStackSession(handle: Long, mode: Int, p1: Float, p2: Float, p3: Float, flags: Int)
    private external fun nativeHasDetectedForeground(handle: Long): Boolean

    private external fun nativeAddRawFrame(
        handle: Long,
        rawBuf: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int,
        cfa: Int, blackLevel4: FloatArray, whiteLevel: Float,
        wbGains3: FloatArray, bin2x2: Boolean, evMultiplier: Float,
        livePreviewBitmap: Bitmap?, outStats: FloatArray
    )

    private external fun nativeAddYuvFrame(
        handle: Long,
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuf: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuf: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int, downsampleStep: Int, evMultiplier: Float,
        livePreviewBitmap: Bitmap?, outStats: FloatArray
    )

    private external fun nativeFinishStackSession(
        handle: Long,
        cameraModel: String, lensName: String, dateObs: String, modeName: String,
        focalMm: Float, apertureF: Float, exposureSec: Float, iso: Float,
        saveAstro32Path: String, outPreviewBitmap: Bitmap?
    ): Boolean

    private external fun nativeStartCalibration(
        handle: Long, calType: Int, width: Int, height: Int, channels: Int, cfa: Int,
        iso: Float, exposureSec: Float, focalMm: Float
    )

    private external fun nativeAddCalibrationRawFrame(
        handle: Long, rawBuf: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int,
        cfa: Int, blackLevel4: FloatArray, whiteLevel: Float
    ): Int

    private external fun nativeAddCalibrationYuvFrame(
        handle: Long,
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuf: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuf: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int, downsampleStep: Int
    ): Int

    private external fun nativeFinishCalibration(handle: Long, outputPath: String): Boolean
    private external fun nativeLoadCalibrationMaster(handle: Long, path: String): Boolean
    private external fun nativeClearCalibrationMasters(handle: Long, d: Boolean, f: Boolean, b: Boolean)

    private external fun nativeLoadAstro32(handle: Long, path: String, outDims: IntArray): Boolean
    private external fun nativeRenderLabPreview(
        handle: Long, labParams: FloatArray, showOriginal: Boolean,
        outBitmap: Bitmap, histR: IntArray?, histG: IntArray?, histB: IntArray?
    ): Boolean

    private external fun nativeExportTiff16(handle: Long, path: String, labParams: FloatArray, applyLab: Boolean): Boolean
    private external fun nativeExportFits(handle: Long, path: String, labParams: FloatArray, applyLab: Boolean, asFloat32: Boolean): Boolean
    private external fun nativeRenderFullExportBitmap(handle: Long, labParams: FloatArray, outBitmap: Bitmap): Boolean
    private external fun nativeGetActiveSize(handle: Long): IntArray
}
