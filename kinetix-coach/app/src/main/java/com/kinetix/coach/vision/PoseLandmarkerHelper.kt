package com.kinetix.coach.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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

class PoseLandmarkerHelper(
    private val context: Context,
    private val onResult: (points: List<PosePoint>, imageWidth: Int, imageHeight: Int, timestampMs: Long) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "KinetixPoseHelper"
        private const val MODEL_ASSET_PATH = "models/pose_landmarker_full.task"
    }

    @Volatile
    private var poseLandmarker: PoseLandmarker? = null

    @Volatile
    var activeDelegateLabel: String = "CPU"
        private set

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
                .setMinPoseDetectionConfidence(0.50f)
                .setMinPosePresenceConfidence(0.50f)
                .setMinTrackingConfidence(0.50f)
                .setResultListener { result: PoseLandmarkerResult, input: MPImage ->
                    val firstPose = result.landmarks().firstOrNull()
                    if (firstPose != null && firstPose.isNotEmpty()) {
                        val pts = firstPose.map { lm ->
                            PosePoint(
                                x = lm.x(),
                                y = lm.y(),
                                z = lm.z(),
                                visibility = lm.visibility().orElse(1f)
                            )
                        }
                        onResult(pts, input.width, input.height, result.timestampMs())
                    } else {
                        onResult(emptyList(), input.width, input.height, result.timestampMs())
                    }
                }
                .setErrorListener { err: RuntimeException ->
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
            Log.i(TAG, "PoseLandmarker initialized on $activeDelegateLabel")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Delegate $delegate failed: ${t.message}")
            false
        }
    }

    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val landmarker = poseLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }

        try {
            val rawBitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
            val matrix = Matrix().apply {
                if (rotation != 0f) postRotate(rotation)
                if (isFrontCamera) {
                    postScale(-1f, 1f)
                }
            }
            val orientedBitmap = Bitmap.createBitmap(
                rawBitmap,
                0,
                0,
                rawBitmap.width,
                rawBitmap.height,
                matrix,
                false
            )
            if (orientedBitmap !== rawBitmap) {
                rawBitmap.recycle()
            }

            val now = SystemClock.uptimeMillis()
            val ts = if (now > lastTimestampMs) now else lastTimestampMs + 1L
            lastTimestampMs = ts

            val mpImage = BitmapImageBuilder(orientedBitmap).build()
            landmarker.detectAsync(mpImage, ts)
        } catch (e: Exception) {
            Log.w(TAG, "Frame drop: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    fun close() {
        try {
            poseLandmarker?.close()
        } catch (_: Throwable) {
        }
        poseLandmarker = null
    }
}
