package com.zaagforge.studio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.zaagforge.studio.dsp.NativeDspBridge
import com.zaagforge.studio.model.TrackAnalysis
import java.io.File
import java.nio.ByteOrder

/**
 * Zero-OOM streaming audio decoder for MP3, WAV, FLAC, M4A, and OGG files:
 * Streams small 16-bit PCM chunks directly from Android MediaCodec into C++ native RAM
 * (`NativeDspBridge.nativeSessionAppendPcm16`), never allocating a giant FloatArray on ART's
 * 256 MB Java heap! Supports full 5–8 minute tracks smoothly without OOM.
 */
object AudioDecoder {
    const val TARGET_SAMPLE_RATE = 44100

    fun decodeUriToNativeSession(context: Context, uri: Uri): TrackAnalysis {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        return decodeExtractorToSession(extractor)
    }

    fun decodeFileToNativeSession(file: File): TrackAnalysis {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        return decodeExtractorToSession(extractor)
    }

    private fun decodeExtractorToSession(extractor: MediaExtractor): TrackAnalysis {
        var codec: MediaCodec? = null
        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                throw IllegalArgumentException("Aucune piste audio trouvée dans ce fichier.")
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            NativeDspBridge.nativeSessionBeginDecode(srcSampleRate, srcChannels)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            val timeoutUs = 10_000L

            // Tiny reusable scratch ShortArray (never allocates > 64 KB on Java heap!)
            var scratch = ShortArray(16384)
            var totalAppended = 0L

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIdx >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuf = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val count = shortBuf.remaining()
                            if (count > 0) {
                                if (scratch.size < count) {
                                    scratch = ShortArray(count)
                                }
                                shortBuf.get(scratch, 0, count)
                                totalAppended += count
                                val keepGoing = NativeDspBridge.nativeSessionAppendPcm16(scratch, count)
                                if (!keepGoing) {
                                    sawInputEos = true
                                    sawOutputEos = true
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = codec.outputFormat
                        if (newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            srcChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        NativeDspBridge.nativeSessionUpdateFormat(srcSampleRate, srcChannels)
                    }
                }
            }

            if (totalAppended <= 0L) {
                throw IllegalStateException("Décodage audio vide.")
            }

            return NativeDspBridge.finalizeDecodeAndAnalyze()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Pure-Kotlin stereo linear resampler kept for JVM unit verification. */
    internal fun resampleStereoLinear(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        val srcFrames = src.size / 2
        if (srcFrames <= 1 || srcRate <= 0 || srcRate == dstRate) return src
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstFrames = kotlin.math.round(srcFrames.toDouble() * dstRate.toDouble() / srcRate.toDouble()).toInt().coerceAtLeast(1)
        val out = FloatArray(dstFrames * 2)
        val maxIdx = srcFrames - 1
        for (i in 0 until dstFrames) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, maxIdx)
            val i1 = (i0 + 1).coerceAtMost(maxIdx)
            val frac = (pos - i0).toFloat()
            val inv = 1f - frac
            out[i * 2] = src[i0 * 2] * inv + src[i1 * 2] * frac
            out[i * 2 + 1] = src[i0 * 2 + 1] * inv + src[i1 * 2 + 1] * frac
        }
        return out
    }
}
