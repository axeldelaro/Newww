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
    val tint: Float = 0.0f
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
        tint
    )
}

class AstroNativeEngine : AutoCloseable {

    companion object {
        const val STACK_MODE_DEEP_SKY = 0
        const val STACK_MODE_LUCKY_PLANETARY = 1
        const val STACK_MODE_STAR_TRAILS_STANDARD = 2
        const val STACK_MODE_STAR_TRAILS_COMET = 3
        const val STACK_MODE_SINGLE_PRO = 4

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

    fun startStackSession(mode: Int, param1: Float = 2.5f, param2: Float = 3.0f, param3: Float = 0.0f) {
        nativeStartStackSession(checkHandle(), mode, param1, param2, param3)
    }

    fun addRawFrame(
        rawBuf: ByteBuffer,
        width: Int, height: Int, rowStrideBytes: Int,
        cfaPattern: Int,
        blackLevel4: FloatArray,
        whiteLevel: Float,
        wbGains3: FloatArray,
        bin2x2: Boolean,
        livePreviewBitmap: Bitmap?
    ): NativeStackReport {
        val outStats = FloatArray(8)
        nativeAddRawFrame(
            checkHandle(), rawBuf, width, height, rowStrideBytes,
            cfaPattern, blackLevel4, whiteLevel, wbGains3, bin2x2,
            livePreviewBitmap, outStats
        )
        return parseStackReport(outStats)
    }

    fun addYuvFrame(
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuf: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuf: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int, downsampleStep: Int,
        livePreviewBitmap: Bitmap?
    ): NativeStackReport {
        val outStats = FloatArray(8)
        nativeAddYuvFrame(
            checkHandle(),
            yBuf, yRowStride, yPixelStride,
            uBuf, uRowStride, uPixelStride,
            vBuf, vRowStride, vPixelStride,
            width, height, downsampleStep,
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

    private external fun nativeStartStackSession(handle: Long, mode: Int, p1: Float, p2: Float, p3: Float)

    private external fun nativeAddRawFrame(
        handle: Long,
        rawBuf: ByteBuffer, width: Int, height: Int, rowStrideBytes: Int,
        cfa: Int, blackLevel4: FloatArray, whiteLevel: Float,
        wbGains3: FloatArray, bin2x2: Boolean,
        livePreviewBitmap: Bitmap?, outStats: FloatArray
    )

    private external fun nativeAddYuvFrame(
        handle: Long,
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuf: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuf: ByteBuffer, vRowStride: Int, vPixelStride: Int,
        width: Int, height: Int, downsampleStep: Int,
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
