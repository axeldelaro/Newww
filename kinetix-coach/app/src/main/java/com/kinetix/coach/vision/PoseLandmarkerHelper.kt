package com.kinetix.coach.vision

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
        private const val TAG = "KinetixPoseHelper"
        private const val MODEL_ASSET_PATH = "models/pose_landmarker_full.task"
        private const val MIN_CONFIDENCE = 0.55f
    }

    @Volatile
    private var poseLandmarker: PoseLandmarker? = null

    @Volatile
    var activeDelegateLabel: String = "CPU"
        private set

    @Volatile
    private var isFrontCameraCurrent: Boolean = true

    private val isInferring = AtomicBoolean(false)
    private val bitmapLock = Any()

    private var reusableRawBitmap: Bitmap? = null
    private var reusableOrientedBitmap: Bitmap? = null
    private var reusableCanvas: Canvas? = null
    private val rotationMatrix = Matrix()

    private var lastTimestampMs: Long = 0L

    fun initialize() {
        close()
        // Try GPU first for 30+ FPS; automatically fall back to CPU on devices/emulators without OpenGL ES compute support
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
                .setMinPoseDetectionConfidence(MIN_CONFIDENCE)
                .setMinPosePresenceConfidence(MIN_CONFIDENCE)
                .setMinTrackingConfidence(MIN_CONFIDENCE)
                .setResultListener { result: PoseLandmarkerResult, input: MPImage ->
                    val frontCameraForFrame = isFrontCameraCurrent
                    try {
                        val firstScreen = result.landmarks().firstOrNull()
                        if (firstScreen != null && firstScreen.isNotEmpty()) {
                            // MediaPipe received un-mirrored anatomical pixels: mirror ONLY screen X for front preview alignment
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
                        // Hot-fallback to CPU if GPU throws on first frame
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
        if (landmarker == null || !isInferring.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        var dispatched = false
        try {
            isFrontCameraCurrent = isFrontCamera
            val orientedBitmap = prepareOrientedBitmap(imageProxy)

            val now = SystemClock.uptimeMillis()
            val ts = if (now > lastTimestampMs) now else lastTimestampMs + 1L
            lastTimestampMs = ts

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

    /**
     * Converts and rotates [imageProxy] into a pre-allocated reusable ARGB_8888 [Bitmap]
     * without horizontal mirroring so MediaPipe retains 100% anatomical Left/Right & 3D fidelity.
     */
    private fun prepareOrientedBitmap(imageProxy: ImageProxy): Bitmap = synchronized(bitmapLock) {
        val srcW = imageProxy.width
        val srcH = imageProxy.height
        val rotationDeg = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360

        var tempBitmapToRecycle: Bitmap? = null
        val srcBitmap: Bitmap = if (
            imageProxy.format == PixelFormat.RGBA_8888 &&
            imageProxy.planes.isNotEmpty() &&
            imageProxy.planes[0].pixelStride == 4 &&
            imageProxy.planes[0].rowStride == srcW * 4
        ) {
            val rawBmp = ensureRawBitmap(srcW, srcH)
            val buf = imageProxy.planes[0].buffer
            buf.rewind()
            rawBmp.copyPixelsFromBuffer(buf)
            rawBmp
        } else {
            imageProxy.toBitmap().also { tempBitmapToRecycle = it }
        }

        if (rotationDeg == 0) {
            if (tempBitmapToRecycle == null) {
                return@synchronized srcBitmap
            }
            val dst = ensureOrientedBitmap(srcW, srcH)
            reusableCanvas?.drawBitmap(srcBitmap, 0f, 0f, null)
            tempBitmapToRecycle?.recycle()
            return@synchronized dst
        }

        val swapDims = rotationDeg == 90 || rotationDeg == 270
        val dstW = if (swapDims) srcH else srcW
        val dstH = if (swapDims) srcW else srcH
        val dstBitmap = ensureOrientedBitmap(dstW, dstH)

        rotationMatrix.reset()
        rotationMatrix.postTranslate(-srcW * 0.5f, -srcH * 0.5f)
        rotationMatrix.postRotate(rotationDeg.toFloat())
        rotationMatrix.postTranslate(dstW * 0.5f, dstH * 0.5f)

        reusableCanvas?.drawBitmap(srcBitmap, rotationMatrix, null)
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

    private fun ensureOrientedBitmap(width: Int, height: Int): Bitmap {
        val existing = reusableOrientedBitmap
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
            reusableOrientedBitmap = bmp
            reusableCanvas = Canvas(bmp)
        }
    }

    fun close() {
        isInferring.set(false)
        try {
            poseLandmarker?.close()
        } catch (_: Throwable) {
        }
        poseLandmarker = null
        synchronized(bitmapLock) {
            reusableCanvas = null
            reusableRawBitmap?.recycle()
            reusableRawBitmap = null
            reusableOrientedBitmap?.recycle()
            reusableOrientedBitmap = null
        }
    }
}
