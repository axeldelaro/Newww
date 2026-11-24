package com.strikecam.arcade.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class PoseLandmarkerHelper(
    private val context: Context,
    private val onResult: (
        screenPoints: List<PosePoint>,
        worldPoints: List<PosePoint>,
        imageWidth: Int,
        imageHeight: Int,
        timestampMs: Long
    ) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "StrikeCamPose"
        private const val MODEL_ASSET_PATH = "models/pose_landmarker_full.task"
        private const val MIN_DETECTION_CONFIDENCE = 0.45f
        private const val MIN_PRESENCE_CONFIDENCE = 0.42f
        private const val MIN_TRACKING_CONFIDENCE = 0.42f
        private const val INFERENCE_WATCHDOG_MS = 1200L
    }

    @Volatile
    private var poseLandmarker: PoseLandmarker? = null

    @Volatile
    var activeDelegateLabel: String = "CPU"
        private set

    @Volatile
    private var isFrontCameraCurrent: Boolean = true

    @Volatile
    private var lastDispatchUptimeMs: Long = 0L

    private val isInferring = AtomicBoolean(false)
    private val bitmapLock = Any()

    private var reusableRawBitmap: Bitmap? = null
    private var packedRgbaBuffer: ByteBuffer? = null
    private var rowCopyBytes: ByteArray = ByteArray(0)

    // Double-buffered oriented bitmaps prevent MediaPipe GPU/CPU async pipeline races
    private val orientedBitmaps = arrayOfNulls<Bitmap>(2)
    private val orientedCanvases = arrayOfNulls<Canvas>(2)
    private var orientedSlot = 0
    private val rotationMatrix = Matrix()

    private var lastTimestampMs: Long = 0L

    fun initialize() {
        close()
        if (!tryCreate(Delegate.GPU)) {
            Log.w(TAG, "GPU delegate unavailable, falling back to CPU delegate")
            if (!tryCreate(Delegate.CPU)) {
                onError("Impossible d'initialiser MediaPipe Pose Landmarker.")
            }
        }
    }

    private fun tryCreate(delegate: Delegate): Boolean {
        return try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(delegate)
                .setModelAssetPath(MODEL_ASSET_PATH)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(MIN_DETECTION_CONFIDENCE)
                .setMinPosePresenceConfidence(MIN_PRESENCE_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                .setResultListener { result: PoseLandmarkerResult, input: MPImage ->
                    val frontCameraForFrame = isFrontCameraCurrent
                    try {
                        val firstScreen = result.landmarks().firstOrNull()
                        if (firstScreen != null && firstScreen.isNotEmpty()) {
                            // Un-mirrored anatomical inference; mirror ONLY screen X on front camera
                            val screenPts = firstScreen.map { lm ->
                                PosePoint(
                                    x = if (frontCameraForFrame) (1f - lm.x()) else lm.x(),
                                    y = lm.y(),
                                    z = lm.z(),
                                    visibility = lm.visibility().orElse(1f)
                                )
                            }
                            val firstWorld = result.worldLandmarks().firstOrNull()
                            val worldPts = if (firstWorld != null && firstWorld.size >= firstScreen.size) {
                                firstWorld.mapIndexed { idx, wlm ->
                                    PosePoint(
                                        x = wlm.x(),
                                        y = wlm.y(),
                                        z = wlm.z(),
                                        visibility = wlm.visibility().orElse(screenPts[idx].visibility)
                                    )
                                }
                            } else {
                                emptyList()
                            }
                            onResult(screenPts, worldPts, input.width, input.height, result.timestampMs())
                        } else {
                            onResult(emptyList(), emptyList(), input.width, input.height, result.timestampMs())
                        }
                    } finally {
                        isInferring.set(false)
                    }
                }
                .setErrorListener { err: RuntimeException ->
                    isInferring.set(false)
                    Log.e(TAG, "MediaPipe runtime error: ${err.message}")
                    if (delegate == Delegate.GPU) {
                        tryCreate(Delegate.CPU)
                    } else {
                        onError("Erreur vision : ${err.message}")
                    }
                }
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            activeDelegateLabel = if (delegate == Delegate.GPU) "GPU" else "CPU"
            isInferring.set(false)
            Log.i(TAG, "PoseLandmarker initialized on $activeDelegateLabel")
            true
        } catch (t: Throwable) {
            isInferring.set(false)
            Log.w(TAG, "Delegate $delegate failed: ${t.message}")
            false
        }
    }

    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val landmarker = poseLandmarker
        val now = SystemClock.uptimeMillis()
        if (isInferring.get() && lastDispatchUptimeMs > 0L && (now - lastDispatchUptimeMs) > INFERENCE_WATCHDOG_MS) {
            Log.w(TAG, "Inference watchdog reset after ${now - lastDispatchUptimeMs}ms")
            isInferring.set(false)
        }
        if (landmarker == null || !isInferring.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        var dispatched = false
        try {
            isFrontCameraCurrent = isFrontCamera
            val orientedBitmap = prepareOrientedBitmap(imageProxy)

            val ts = if (now > lastTimestampMs) now else lastTimestampMs + 1L
            lastTimestampMs = ts
            lastDispatchUptimeMs = now

            val mpImage = BitmapImageBuilder(orientedBitmap).build()
            landmarker.detectAsync(mpImage, ts)
            dispatched = true
        } catch (e: Exception) {
            Log.w(TAG, "Frame drop: ${e.message}")
        } finally {
            imageProxy.close()
            if (!dispatched) {
                isInferring.set(false)
            }
        }
    }

    private fun prepareOrientedBitmap(imageProxy: ImageProxy): Bitmap = synchronized(bitmapLock) {
        val srcW = imageProxy.width
        val srcH = imageProxy.height
        val rotationDeg = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360

        var tempBitmapToRecycle: Bitmap? = null
        val srcBitmap: Bitmap = if (
            imageProxy.format == PixelFormat.RGBA_8888 &&
            imageProxy.planes.isNotEmpty() &&
            imageProxy.planes[0].pixelStride == 4
        ) {
            val plane = imageProxy.planes[0]
            val rowStride = plane.rowStride
            val tightRowBytes = srcW * 4
            val rawBmp = ensureRawBitmap(srcW, srcH)
            val buf = plane.buffer
            buf.rewind()

            if (rowStride == tightRowBytes) {
                rawBmp.copyPixelsFromBuffer(buf)
                rawBmp
            } else if (rowStride > tightRowBytes && buf.remaining() >= rowStride * (srcH - 1) + tightRowBytes) {
                // Zero-alloc strip of Snapdragon/Adreno hardware row padding
                val packed = ensurePackedBuffer(tightRowBytes * srcH)
                if (rowCopyBytes.size < tightRowBytes) {
                    rowCopyBytes = ByteArray(tightRowBytes)
                }
                packed.clear()
                for (row in 0 until srcH) {
                    buf.position(row * rowStride)
                    buf.get(rowCopyBytes, 0, tightRowBytes)
                    packed.put(rowCopyBytes, 0, tightRowBytes)
                }
                packed.rewind()
                rawBmp.copyPixelsFromBuffer(packed)
                rawBmp
            } else {
                imageProxy.toBitmap().also { tempBitmapToRecycle = it }
            }
        } else {
            imageProxy.toBitmap().also { tempBitmapToRecycle = it }
        }

        val slot = orientedSlot
        orientedSlot = slot xor 1

        val swapDims = rotationDeg == 90 || rotationDeg == 270
        val dstW = if (swapDims) srcH else srcW
        val dstH = if (swapDims) srcW else srcH
        val dstBitmap = ensureOrientedBitmap(slot, dstW, dstH)
        val canvas = orientedCanvases[slot]

        if (rotationDeg == 0) {
            canvas?.drawBitmap(srcBitmap, 0f, 0f, null)
        } else {
            rotationMatrix.reset()
            rotationMatrix.postTranslate(-srcW * 0.5f, -srcH * 0.5f)
            rotationMatrix.postRotate(rotationDeg.toFloat())
            rotationMatrix.postTranslate(dstW * 0.5f, dstH * 0.5f)
            canvas?.drawBitmap(srcBitmap, rotationMatrix, null)
        }

        tempBitmapToRecycle?.recycle()
        return@synchronized dstBitmap
    }

    private fun ensureRawBitmap(width: Int, height: Int): Bitmap {
        val existing = reusableRawBitmap
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            reusableRawBitmap = it
        }
    }

    private fun ensurePackedBuffer(sizeBytes: Int): ByteBuffer {
        val existing = packedRgbaBuffer
        if (existing != null && existing.capacity() >= sizeBytes) {
            return existing
        }
        return ByteBuffer.allocateDirect(sizeBytes).also {
            packedRgbaBuffer = it
        }
    }

    private fun ensureOrientedBitmap(slot: Int, width: Int, height: Int): Bitmap {
        val existing = orientedBitmaps[slot]
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
            orientedBitmaps[slot] = bmp
            orientedCanvases[slot] = Canvas(bmp)
        }
    }

    fun close() {
        isInferring.set(false)
        lastDispatchUptimeMs = 0L
        try {
            poseLandmarker?.close()
        } catch (_: Throwable) {
        }
        poseLandmarker = null
        synchronized(bitmapLock) {
            reusableRawBitmap?.recycle()
            reusableRawBitmap = null
            packedRgbaBuffer = null
            rowCopyBytes = ByteArray(0)
            for (i in orientedBitmaps.indices) {
                orientedCanvases[i] = null
                orientedBitmaps[i]?.recycle()
                orientedBitmaps[i] = null
            }
        }
    }
}
