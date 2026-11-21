package com.newww.auracompanion.vision

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Repères normalisés (0..1) détectés par MediaPipe Face Landmarker (478 points 3D)
 * utilisés pour piloter la déformation temps réel 60 FPS d'une photo importée.
 */
data class FaceRigLandmarks(
    val detectedByAi: Boolean,
    val leftEyeCenter: PointF,
    val rightEyeCenter: PointF,
    val eyeRadius: Float,
    val leftBrowCenter: PointF,
    val rightBrowCenter: PointF,
    val noseTip: PointF,
    val mouthCenter: PointF,
    val mouthLeft: PointF,
    val mouthRight: PointF,
    val upperLip: PointF,
    val lowerLip: PointF,
    val chinBottom: PointF,
    val leftCheek: PointF,
    val rightCheek: PointF,
    val faceBounds: RectF
) {
    companion object {
        fun fallbackPortrait(): FaceRigLandmarks = FaceRigLandmarks(
            detectedByAi = false,
            leftEyeCenter = PointF(0.39f, 0.42f),
            rightEyeCenter = PointF(0.61f, 0.42f),
            eyeRadius = 0.055f,
            leftBrowCenter = PointF(0.39f, 0.35f),
            rightBrowCenter = PointF(0.61f, 0.35f),
            noseTip = PointF(0.50f, 0.51f),
            mouthCenter = PointF(0.50f, 0.61f),
            mouthLeft = PointF(0.42f, 0.61f),
            mouthRight = PointF(0.58f, 0.61f),
            upperLip = PointF(0.50f, 0.595f),
            lowerLip = PointF(0.50f, 0.635f),
            chinBottom = PointF(0.50f, 0.72f),
            leftCheek = PointF(0.34f, 0.52f),
            rightCheek = PointF(0.66f, 0.52f),
            faceBounds = RectF(0.26f, 0.22f, 0.74f, 0.75f)
        )
    }
}

data class AnalyzedPhotoAvatar(
    val sourceBitmap: Bitmap,
    val cutoutBitmap: Bitmap?,
    val rig: FaceRigLandmarks
)

/**
 * Moteur IA Vision 100 % hors-ligne :
 * 1. Normalise & centre n'importe quelle photo fournie par l'utilisateur
 * 2. Extrait les 478 repères faciaux via MediaPipe Face Landmarker (`face_landmarker.task`)
 * 3. Segmente la silhouette via MediaPipe Selfie Segmenter (`selfie_segmenter.tflite`)
 *    pour permettre le changement de décor en temps réel derrière la vraie photo !
 */
class PhotoFaceRigAnalyzer(private val context: Context) {

    private var faceLandmarker: FaceLandmarker? = null
    private var imageSegmenter: ImageSegmenter? = null

    private fun ensureModels() {
        if (faceLandmarker == null) {
            runCatching {
                val base = BaseOptions.builder()
                    .setModelAssetPath("vision/face_landmarker.task")
                    .build()
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(0.3f)
                    .build()
                faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            }
        }
        if (imageSegmenter == null) {
            runCatching {
                val base = BaseOptions.builder()
                    .setModelAssetPath("vision/selfie_segmenter.tflite")
                    .build()
                val options = ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setOutputConfidenceMasks(true)
                    .build()
                imageSegmenter = ImageSegmenter.createFromOptions(context, options)
            }
        }
    }

    suspend fun importAndSavePhoto(uri: Uri, characterId: String): String? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val outFile = File(dir, "avatar_${characterId}_${System.currentTimeMillis()}.jpg")
        runCatching {
            val raw = decodeOrientedBitmap(uri) ?: return@withContext null
            val portrait = centerCropPortrait(raw, 768, 960)
            if (portrait !== raw) raw.recycle()
            FileOutputStream(outFile).use { fos ->
                portrait.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            portrait.recycle()
            outFile.absolutePath
        }.getOrNull()
    }

    suspend fun analyzePhotoFile(path: String): AnalyzedPhotoAvatar? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null

        ensureModels()
        val mpImage = BitmapImageBuilder(bitmap).build()

        // 1. Détection IA des 478 repères du visage
        val rig = runCatching {
            val res = faceLandmarker?.detect(mpImage)
            val pts = res?.faceLandmarks()?.firstOrNull()
            if (pts != null && pts.size >= 468) {
                fun pt(i: Int) = PointF(pts[i].x().coerceIn(0.02f, 0.98f), pts[i].y().coerceIn(0.02f, 0.98f))
                fun avg(vararg idx: Int): PointF {
                    var sx = 0f
                    var sy = 0f
                    for (k in idx) {
                        sx += pts[k].x()
                        sy += pts[k].y()
                    }
                    return PointF(sx / idx.size, sy / idx.size)
                }

                // Indices standards MediaPipe 478 Face Mesh
                val leftEye = avg(33, 133, 159, 145)
                val rightEye = avg(362, 263, 386, 374)
                val eyeSpan = max(abs(pts[133].x() - pts[33].x()), abs(pts[263].x() - pts[362].x()))
                val leftBrow = avg(70, 105, 107)
                val rightBrow = avg(300, 334, 336)
                val nose = pt(1)
                val mouthLeft = pt(61)
                val mouthRight = pt(291)
                val upperLip = pt(13)
                val lowerLip = pt(14)
                val mouthCenter = PointF((mouthLeft.x + mouthRight.x) * 0.5f, (upperLip.y + lowerLip.y) * 0.5f)
                val chin = pt(152)
                val leftCheek = avg(234, 50, 117)
                val rightCheek = avg(454, 280, 346)

                var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
                for (lm in pts) {
                    minX = min(minX, lm.x())
                    maxX = max(maxX, lm.x())
                    minY = min(minY, lm.y())
                    maxY = max(maxY, lm.y())
                }

                FaceRigLandmarks(
                    detectedByAi = true,
                    leftEyeCenter = leftEye,
                    rightEyeCenter = rightEye,
                    eyeRadius = (eyeSpan * 0.62f).coerceIn(0.025f, 0.11f),
                    leftBrowCenter = leftBrow,
                    rightBrowCenter = rightBrow,
                    noseTip = nose,
                    mouthCenter = mouthCenter,
                    mouthLeft = mouthLeft,
                    mouthRight = mouthRight,
                    upperLip = upperLip,
                    lowerLip = lowerLip,
                    chinBottom = chin,
                    leftCheek = leftCheek,
                    rightCheek = rightCheek,
                    faceBounds = RectF(minX, minY, maxX, maxY)
                )
            } else {
                FaceRigLandmarks.fallbackPortrait()
            }
        }.getOrElse { FaceRigLandmarks.fallbackPortrait() }

        // 2. Segmentation IA de la silhouette (pour changement de décor en direct)
        val cutout = runCatching {
            val segRes = imageSegmenter?.segment(mpImage)
            val masks = segRes?.confidenceMasks()
            if (masks != null && masks.isPresent && masks.get().isNotEmpty()) {
                val maskImg = masks.get().last()
                val mw = maskImg.width
                val mh = maskImg.height
                val floatBuf = ByteBufferExtractor.extract(maskImg).asFloatBuffer()
                val maskVals = FloatArray(mw * mh)
                floatBuf.get(maskVals)

                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                for (y in 0 until h) {
                    val my = ((y * mh) / h).coerceIn(0, mh - 1)
                    val rowOff = y * w
                    val mRow = my * mw
                    for (x in 0 until w) {
                        val mx = ((x * mw) / w).coerceIn(0, mw - 1)
                        val conf = maskVals[mRow + mx]
                        val alpha = ((conf - 0.25f) / 0.55f).coerceIn(0f, 1f)
                        val px = pixels[rowOff + x]
                        val a = (alpha * 255f).toInt().coerceIn(0, 255)
                        pixels[rowOff + x] = (px and 0x00FFFFFF) or (a shl 24)
                    }
                }
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                    setPixels(pixels, 0, w, 0, 0, w, h)
                }
            } else null
        }.getOrNull()

        AnalyzedPhotoAvatar(sourceBitmap = bitmap, cutoutBitmap = cutout, rig = rig)
    }

    fun release() {
        runCatching { faceLandmarker?.close() }
        runCatching { imageSegmenter?.close() }
        faceLandmarker = null
        imageSegmenter = null
    }

    private fun decodeOrientedBitmap(uri: Uri): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val maxDim = max(opts.outWidth, opts.outHeight).coerceAtLeast(1)
        var sample = 1
        while (maxDim / sample > 1536) sample *= 2

        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        if (rotation == 0f) return decoded
        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun centerCropPortrait(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val targetAspect = targetW.toFloat() / targetH.toFloat()
        val srcAspect = src.width.toFloat() / src.height.toFloat()
        val cropRect = if (srcAspect > targetAspect) {
            val newW = (src.height * targetAspect).toInt()
            val left = (src.width - newW) / 2
            Rect(left, 0, left + newW, src.height)
        } else {
            val newH = (src.width / targetAspect).toInt()
            val top = ((src.height - newH) * 0.25f).toInt().coerceAtLeast(0)
            Rect(0, top, src.width, (top + newH).coerceAtMost(src.height))
        }
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, cropRect, Rect(0, 0, targetW, targetH), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
