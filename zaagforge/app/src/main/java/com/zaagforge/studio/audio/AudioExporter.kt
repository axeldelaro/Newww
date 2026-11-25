package com.zaagforge.studio.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import com.zaagforge.studio.dsp.NativeDspBridge
import com.zaagforge.studio.model.ExportFormat
import com.zaagforge.studio.model.ExportedRemix
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Zero-OOM streaming exporter for ZAAGFORGE remixes into public `Music/ZaagForge/` via Scoped Storage
 * (`MediaStore.Audio`) as M4A (AAC 256 kbps hardware MediaCodec + MediaMuxer) or 16-bit 44.1 kHz WAV.
 * Pulls small 32 KB chunks directly from the C++ native remix buffer -> zero Java heap spike!
 */
object AudioExporter {
    const val EXPORT_RELATIVE_DIR = "Music/ZaagForge/"

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9._\\- ]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .take(54)
        return cleaned.ifBlank { "ZaagForge_Remix" }
    }

    fun exportSessionRemix(
        context: Context,
        totalRemixFrames: Int,
        sampleRate: Int,
        baseName: String,
        format: ExportFormat
    ): ExportedRemix {
        if (totalRemixFrames <= 0) {
            throw IllegalStateException("Aucun remix rendu en mémoire.")
        }
        val safeBase = sanitizeFileName(baseName)
        val displayName = "${safeBase}.${format.extension}"

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, EXPORT_RELATIVE_DIR)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Impossible de créer le fichier dans $EXPORT_RELATIVE_DIR")

        var bytesWritten = 0L
        try {
            when (format) {
                ExportFormat.WAV -> {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        bytesWritten = writeNativeWavStream(totalRemixFrames, sampleRate, out)
                    } ?: throw IllegalStateException("Flux de sortie WAV indisponible.")
                }
                ExportFormat.M4A -> {
                    val tempM4a = File(context.cacheDir, "export_${System.currentTimeMillis()}.m4a")
                    try {
                        encodeNativeAacM4a(totalRemixFrames, sampleRate, tempM4a)
                        bytesWritten = tempM4a.length()
                        resolver.openOutputStream(itemUri)?.use { out ->
                            FileInputStream(tempM4a).use { inp -> inp.copyTo(out) }
                        } ?: throw IllegalStateException("Flux de sortie M4A indisponible.")
                    } finally {
                        tempM4a.delete()
                    }
                }
            }

            val doneValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(itemUri, doneValues, null, null)

            return ExportedRemix(
                id = itemUri.lastPathSegment ?: System.currentTimeMillis().toString(),
                displayName = displayName,
                uriString = itemUri.toString(),
                sizeBytes = bytesWritten,
                dateAddedSec = System.currentTimeMillis() / 1000L,
                format = format
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw t
        }
    }

    fun listExportedRemixes(context: Context): List<ExportedRemix> {
        val list = ArrayList<ExportedRemix>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%ZaagForge%")
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: continue
                    val size = c.getLong(sizeCol)
                    val date = c.getLong(dateCol)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    val fmt = if (name.endsWith(".wav", ignoreCase = true)) ExportFormat.WAV else ExportFormat.M4A
                    list.add(
                        ExportedRemix(
                            id = id.toString(),
                            displayName = name,
                            uriString = uri.toString(),
                            sizeBytes = size,
                            dateAddedSec = date,
                            format = fmt
                        )
                    )
                }
            }
        }
        return list
    }

    /** Pure-Kotlin testable WAV header + 16-bit stereo PCM builder. */
    fun buildWavHeader(numStereoFrames: Int, sampleRate: Int): ByteArray {
        val channels = 2
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataBytes = numStereoFrames * blockAlign
        val riffChunkSize = 36 + dataBytes

        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(riffChunkSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        return buf.array()
    }

    fun buildWavBytes(stereoPcm: FloatArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + stereoPcm.size * 2)
        val frames = stereoPcm.size / 2
        out.write(buildWavHeader(frames, sampleRate))
        val byteBuf = ByteArray(16384 * 2)
        var idx = 0
        val totalSamples = frames * 2
        while (idx < totalSamples) {
            val count = minOf(16384, totalSamples - idx)
            var b = 0
            for (i in 0 until count) {
                val clamped = stereoPcm[idx + i].coerceIn(-1f, 1f)
                val s = (clamped * 32767f).roundToInt().coerceIn(-32768, 32767)
                byteBuf[b++] = (s and 0xFF).toByte()
                byteBuf[b++] = ((s ushr 8) and 0xFF).toByte()
            }
            out.write(byteBuf, 0, b)
            idx += count
        }
        return out.toByteArray()
    }

    private fun writeNativeWavStream(totalFrames: Int, sampleRate: Int, out: OutputStream): Long {
        out.write(buildWavHeader(totalFrames, sampleRate))

        val chunkFrames = 8192
        val shortBuf = ShortArray(chunkFrames * 2)
        val byteBuf = ByteArray(chunkFrames * 4)
        var frameCursor = 0
        var writtenFrames = 0L

        while (frameCursor < totalFrames) {
            val req = minOf(chunkFrames, totalFrames - frameCursor)
            val got = NativeDspBridge.nativeSessionReadPcm16Chunk(true, frameCursor, req, shortBuf)
            if (got <= 0) break
            var b = 0
            for (i in 0 until got * 2) {
                val s = shortBuf[i].toInt()
                byteBuf[b++] = (s and 0xFF).toByte()
                byteBuf[b++] = ((s ushr 8) and 0xFF).toByte()
            }
            out.write(byteBuf, 0, b)
            frameCursor += got
            writtenFrames += got
        }
        out.flush()
        return 44L + writtenFrames * 4L
    }

    private fun encodeNativeAacM4a(totalFrames: Int, sampleRate: Int, outFile: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32_768)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var muxerTrack = -1

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var frameCursor = 0
            var inputDone = false
            var outputDone = false
            val timeoutUs = 10_000L
            var scratch = ShortArray(8192)

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val maxFrames = inBuf.capacity() / 4
                        val remaining = totalFrames - frameCursor
                        if (remaining <= 0) {
                            val eosPtsUs = frameCursor.toLong() * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inIdx, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val reqFrames = minOf(maxFrames, remaining)
                            if (scratch.size < reqFrames * 2) {
                                scratch = ShortArray(reqFrames * 2)
                            }
                            val gotFrames = NativeDspBridge.nativeSessionReadPcm16Chunk(true, frameCursor, reqFrames, scratch)
                            if (gotFrames <= 0) {
                                val eosPtsUs = frameCursor.toLong() * 1_000_000L / sampleRate
                                codec.queueInputBuffer(inIdx, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val ptsUs = frameCursor.toLong() * 1_000_000L / sampleRate
                                val shortBuf = inBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                shortBuf.put(scratch, 0, gotFrames * 2)
                                frameCursor += gotFrames
                                codec.queueInputBuffer(inIdx, 0, gotFrames * 4, ptsUs, 0)
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerTrack = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(muxerTrack, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
