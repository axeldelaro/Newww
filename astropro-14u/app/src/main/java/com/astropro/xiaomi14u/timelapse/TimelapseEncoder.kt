package com.astropro.xiaomi14u.timelapse

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.io.FileInputStream

/**
 * Local 100% offline H.264 MP4 Timelapse Encoder using Android MediaCodec Surface input + MediaMuxer.
 *
 * Uses COLOR_FormatSurface + Surface.lockCanvas() so frames bypass vendor YUV color-stride quirks
 * cleanly across Qualcomm Snapdragon / Xiaomi HyperOS hardware encoders, and exports directly into
 * Movies/AstroPro14U via MediaStore.
 */
class TimelapseEncoder(
    private val context: Context,
    private val width: Int = 1920,
    private val height: Int = 1440,
    private val fps: Int = 24,
    private val bitRateBps: Int = 18_000_000
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var tempFile: File? = null
    private var frameCount = 0
    private var encodedOutputFrames = 0L
    private val bufferInfo = MediaCodec.BufferInfo()
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    fun start(sessionName: String): Boolean {
        return try {
            val wEven = (width / 16) * 16
            val hEven = (height / 16) * 16
            val outFile = File(context.cacheDir, "${sessionName}.mp4")
            tempFile = outFile

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, wEven, hEven).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surf = encoder.createInputSurface()
            encoder.start()

            codec = encoder
            inputSurface = surf
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrackIndex = -1
            muxerStarted = false
            frameCount = 0
            encodedOutputFrames = 0L
            true
        } catch (_: Throwable) {
            abort()
            false
        }
    }

    fun appendFrame(bitmap: Bitmap): Boolean {
        val surf = inputSurface ?: return false
        val encoder = codec ?: return false
        return try {
            drainEncoder(encoder, endOfStream = false)
            val canvas = surf.lockCanvas(null)
            try {
                canvas.drawColor(Color.BLACK)
                val dst = Rect(0, 0, canvas.width, canvas.height)
                canvas.drawBitmap(bitmap, null, dst, drawPaint)
            } finally {
                surf.unlockCanvasAndPost(canvas)
            }
            frameCount++
            drainEncoder(encoder, endOfStream = false)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Finalizes the H.264 MP4 stream and copies it into Movies/AstroPro14U via MediaStore.
     * Returns the display name / path on success, or null if encoding failed.
     */
    fun finishAndSaveToGallery(displayName: String): String? {
        val encoder = codec ?: return null
        return try {
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, endOfStream = true)
            releaseCodecAndMuxer()

            val file = tempFile ?: return null
            if (!file.exists() || file.length() == 0L) return null

            val fileName = if (displayName.endsWith(".mp4")) displayName else "$displayName.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/AstroPro14U")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return file.absolutePath
            resolver.openOutputStream(uri)?.use { os ->
                FileInputStream(file).use { fis -> fis.copyTo(os) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            "Movies/AstroPro14U/$fileName"
        } catch (_: Throwable) {
            releaseCodecAndMuxer()
            null
        }
    }

    fun abort() {
        releaseCodecAndMuxer()
        tempFile?.delete()
        tempFile = null
    }

    private fun drainEncoder(encoder: MediaCodec, endOfStream: Boolean) {
        val m = muxer ?: return
        var tries = 0
        while (tries < 200) {
            tries++
            val status = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 20_000L else 0L)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrackIndex = m.addTrack(encoder.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                }
                status >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(status)
                    if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            val ptsUs = (encodedOutputFrames * 1_000_000L) / fps.coerceAtLeast(1)
                            encodedOutputFrames++
                            bufferInfo.presentationTimeUs = ptsUs
                            m.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(status, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    private fun releaseCodecAndMuxer() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        if (muxerStarted) {
            runCatching { muxer?.stop() }
        }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        videoTrackIndex = -1
    }
}
