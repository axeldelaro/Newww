package com.zaagforge.studio.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

data class DecodedAudio(
    val stereoPcm: FloatArray,
    val sampleRate: Int,
    val durationSec: Float
)

/**
 * Decodes local MP3, WAV, FLAC, M4A, or OGG files (or cached Deezer previews) via Android
 * MediaExtractor + MediaCodec into interleaved Stereo Float32 PCM at 44,100 Hz.
 */
object AudioDecoder {
    const val TARGET_SAMPLE_RATE = 44100
    // Cap at 5 minutes (44100 * 2 * 300 = ~26.4M floats) so Snapdragon RAM stays crisp & snappy
    private const val MAX_DURATION_SEC = 300

    fun decodeUri(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        return decodeExtractor(extractor)
    }

    fun decodeFile(file: File): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        return decodeExtractor(extractor)
    }

    private fun decodeExtractor(extractor: MediaExtractor): DecodedAudio {
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

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val maxSrcFrames = srcSampleRate.toLong() * MAX_DURATION_SEC
            val chunks = ArrayList<ShortArray>(256)
            var totalSrcSamples = 0L

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            val timeoutUs = 10_000L

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0 || (totalSrcSamples / srcChannels) >= maxSrcFrames) {
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
                                val arr = ShortArray(count)
                                shortBuf.get(arr)
                                chunks.add(arr)
                                totalSrcSamples += count
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
                    }
                }
            }

            if (totalSrcSamples <= 0L) {
                throw IllegalStateException("Décodage audio vide.")
            }

            val srcFrames = min(totalSrcSamples / srcChannels, maxSrcFrames).toInt()
            val rawStereo = FloatArray(srcFrames * 2)
            var frameCursor = 0
            var chCursor = 0
            var accL = 0f
            var accR = 0f
            val invScale = 1f / 32768f

            for (chunk in chunks) {
                for (s in chunk) {
                    if (frameCursor >= srcFrames) break
                    val v = s * invScale
                    if (srcChannels == 1) {
                        rawStereo[frameCursor * 2] = v
                        rawStereo[frameCursor * 2 + 1] = v
                        frameCursor++
                    } else {
                        if (chCursor == 0) accL = v
                        else if (chCursor == 1) accR = v
                        chCursor++
                        if (chCursor >= srcChannels) {
                            rawStereo[frameCursor * 2] = accL
                            rawStereo[frameCursor * 2 + 1] = accR
                            frameCursor++
                            chCursor = 0
                        }
                    }
                }
                if (frameCursor >= srcFrames) break
            }

            val finalPcm = if (srcSampleRate == TARGET_SAMPLE_RATE) {
                rawStereo
            } else {
                resampleStereoLinear(rawStereo, srcSampleRate, TARGET_SAMPLE_RATE)
            }
            val durationSec = finalPcm.size / 2f / TARGET_SAMPLE_RATE
            return DecodedAudio(finalPcm, TARGET_SAMPLE_RATE, durationSec)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

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
