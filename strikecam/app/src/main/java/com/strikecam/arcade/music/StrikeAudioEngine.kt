package com.strikecam.arcade.music

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import com.strikecam.arcade.nativeengine.StrikeCamNative
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.min

data class DecodedTrackPcm(
    val stereoFloats: FloatArray,
    val stereoShorts: ShortArray,
    val sampleRate: Int,
    val durationMs: Long
)

/**
 * Sample-accurate low-latency audio engine for StrikeCam Arcade:
 * - Decodes local MP3/WAV/FLAC/M4A/OGG or cached Deezer previews via MediaExtractor + MediaCodec
 * - Plays music + real-time mixed arcade SFX (hits, misses, dodges, countdown) on a single jitter-free AudioTrack
 * - Supports variable playback speed in Survival mode
 * - Provides Spotify/Deezer/SoundCloud remote media key dispatch + optional live microphone beat stream
 */
class StrikeAudioEngine(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = StrikeCamNative.SAMPLE_RATE
        private const val MAX_DECODE_SEC = 300
    }

    private class ActiveSfx(val samples: ShortArray, var pos: Int = 0, val gain: Float = 0.85f)

    private val sfxBank = HashMap<Int, ShortArray>(8)
    private val activeSfx = CopyOnWriteArrayList<ActiveSfx>()

    @Volatile
    private var musicPcm: ShortArray = ShortArray(0)

    @Volatile
    private var musicPlayCursor: Double = 0.0

    @Volatile
    private var speedMultiplier: Float = 1.0f

    @Volatile
    private var musicPlaying: Boolean = false

    @Volatile
    private var loopMusic: Boolean = false

    @Volatile
    private var sfxMuted: Boolean = false

    @Volatile
    private var stopWorker: Boolean = false

    @Volatile
    private var micCaptureRunning: Boolean = false

    private var workerThread: Thread? = null
    private var micThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    fun warmUpSfxBank() {
        if (sfxBank.isNotEmpty() || !StrikeCamNative.isLoaded) return
        for (id in 0..7) {
            val pcm = StrikeCamNative.synthesizeSfxPcm16(id)
            if (pcm.isNotEmpty()) sfxBank[id] = pcm
        }
    }

    fun loadMusicShortPcm(pcm16Stereo: ShortArray, loop: Boolean = false) {
        musicPcm = pcm16Stereo
        musicPlayCursor = 0.0
        speedMultiplier = 1.0f
        loopMusic = loop
    }

    fun startPlayback(resetCursor: Boolean = true) {
        if (resetCursor) musicPlayCursor = 0.0
        musicPlaying = true
        ensureWorkerRunning()
    }

    fun pausePlayback() {
        musicPlaying = false
    }

    fun stopPlayback() {
        musicPlaying = false
        musicPlayCursor = 0.0
        activeSfx.clear()
    }

    fun setPlaybackSpeed(multiplier: Float) {
        speedMultiplier = multiplier.coerceIn(0.85f, 1.65f)
    }

    fun setSfxMuted(muted: Boolean) {
        sfxMuted = muted
    }

    fun currentMusicTimeMs(): Float {
        val frames = musicPlayCursor
        return ((frames / SAMPLE_RATE.toDouble()) * 1000.0).toFloat()
    }

    fun isTrackFinished(): Boolean {
        val totalFrames = musicPcm.size / 2
        if (totalFrames <= 0 || loopMusic) return false
        return musicPlayCursor >= totalFrames - 1024
    }

    fun playSfx(sfxId: Int, gain: Float = 0.85f) {
        if (sfxMuted) return
        warmUpSfxBank()
        val buf = sfxBank[sfxId] ?: return
        ensureWorkerRunning()
        if (activeSfx.size > 12) activeSfx.removeAt(0)
        activeSfx.add(ActiveSfx(buf, 0, gain))
    }

    /** Sends official Android Media Key events to control background Spotify / Deezer / SoundCloud cleanly. */
    fun dispatchExternalMediaControl(keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    @SuppressLint("MissingPermission")
    fun startLiveMicBeatCapture(onMonoChunk: (FloatArray, Double) -> Unit) {
        stopLiveMicBeatCapture()
        micCaptureRunning = true
        micThread = thread(name = "StrikeCamMicBeat", isDaemon = true) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }.getOrNull() ?: run {
                micCaptureRunning = false
                return@thread
            }

            val shortChunk = ShortArray(1024)
            val floatChunk = FloatArray(1024)
            try {
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    recorder.startRecording()
                    while (micCaptureRunning) {
                        val read = recorder.read(shortChunk, 0, shortChunk.size)
                        if (read > 0) {
                            for (i in 0 until read) {
                                floatChunk[i] = shortChunk[i] / 32768f
                            }
                            val slice = if (read == floatChunk.size) floatChunk else floatChunk.copyOf(read)
                            onMonoChunk(slice, SystemClock.elapsedRealtime().toDouble())
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
    }

    fun stopLiveMicBeatCapture() {
        micCaptureRunning = false
        runCatching { micThread?.join(200L) }
        micThread = null
    }

    fun release() {
        stopLiveMicBeatCapture()
        stopWorker = true
        musicPlaying = false
        activeSfx.clear()
        runCatching { workerThread?.join(250L) }
        workerThread = null
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        }
        audioTrack = null
    }

    private fun ensureWorkerRunning() {
        if (workerThread?.isAlive == true) return
        stopWorker = false
        workerThread = thread(name = "StrikeCamAudioMixer", isDaemon = true) {
            runMixerLoop()
        }
    }

    private fun runMixerLoop() {
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        val frames = 512
        val outBuf = ShortArray(frames * 2)

        try {
            while (!stopWorker) {
                val hasMusic = musicPlaying && musicPcm.size >= 4
                val hasSfx = activeSfx.isNotEmpty()
                if (!hasMusic && !hasSfx) {
                    Thread.sleep(16L)
                    continue
                }

                val mPcm = musicPcm
                val totalMFrames = mPcm.size / 2
                val spd = speedMultiplier.toDouble()

                for (i in 0 until frames) {
                    var mixL = 0f
                    var mixR = 0f

                    if (hasMusic && totalMFrames > 1) {
                        var cursor = musicPlayCursor
                        if (cursor >= totalMFrames - 1) {
                            if (loopMusic) {
                                cursor %= (totalMFrames - 1).toDouble()
                                musicPlayCursor = cursor
                            } else {
                                musicPlaying = false
                            }
                        }
                        if (musicPlaying) {
                            val idx0 = cursor.toInt().coerceIn(0, totalMFrames - 2)
                            val frac = (cursor - idx0).toFloat()
                            val inv = 1f - frac
                            val l0 = mPcm[idx0 * 2] / 32768f
                            val r0 = mPcm[idx0 * 2 + 1] / 32768f
                            val l1 = mPcm[(idx0 + 1) * 2] / 32768f
                            val r1 = mPcm[(idx0 + 1) * 2 + 1] / 32768f
                            mixL += (l0 * inv + l1 * frac) * 0.82f
                            mixR += (r0 * inv + r1 * frac) * 0.82f
                            musicPlayCursor = cursor + spd
                        }
                    }

                    if (activeSfx.isNotEmpty()) {
                        for (sfx in activeSfx) {
                            val p = sfx.pos
                            if (p + 1 < sfx.samples.size) {
                                mixL += (sfx.samples[p] / 32768f) * sfx.gain
                                mixR += (sfx.samples[p + 1] / 32768f) * sfx.gain
                                sfx.pos = p + 2
                            }
                        }
                        activeSfx.removeIf { it.pos + 1 >= it.samples.size }
                    }

                    outBuf[i * 2] = (mixL.coerceIn(-0.98f, 0.98f) * 32767f).toInt().toShort()
                    outBuf[i * 2 + 1] = (mixR.coerceIn(-0.98f, 0.98f) * 32767f).toInt().toShort()
                }

                track.write(outBuf, 0, outBuf.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching {
                track.pause()
                track.flush()
                track.release()
            }
        }
    }

    fun decodeAudioUri(uri: Uri): DecodedTrackPcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        return decodeExtractor(extractor)
    }

    fun decodeAudioFile(file: File): DecodedTrackPcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        return decodeExtractor(extractor)
    }

    private fun decodeExtractor(extractor: MediaExtractor): DecodedTrackPcm {
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
                throw IllegalArgumentException("Aucune piste audio trouvée.")
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val maxSrcFrames = srcRate.toLong() * MAX_DECODE_SEC
            val chunks = ArrayList<ShortArray>(256)
            var totalSrcSamples = 0L
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false

            while (!outEos) {
                if (!inEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0 || (totalSrcSamples / srcCh) >= maxSrcFrames) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val n = sb.remaining()
                            if (n > 0) {
                                val arr = ShortArray(n)
                                sb.get(arr)
                                chunks.add(arr)
                                totalSrcSamples += n
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = codec.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) srcCh = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                }
            }

            val srcFrames = min(totalSrcSamples / srcCh, maxSrcFrames).toInt()
            val rawStereo = FloatArray(srcFrames * 2)
            var fc = 0
            var cc = 0
            var accL = 0f
            var accR = 0f
            val inv = 1f / 32768f

            for (ch in chunks) {
                for (s in ch) {
                    if (fc >= srcFrames) break
                    val v = s * inv
                    if (srcCh == 1) {
                        rawStereo[fc * 2] = v
                        rawStereo[fc * 2 + 1] = v
                        fc++
                    } else {
                        if (cc == 0) accL = v else if (cc == 1) accR = v
                        cc++
                        if (cc >= srcCh) {
                            rawStereo[fc * 2] = accL
                            rawStereo[fc * 2 + 1] = accR
                            fc++
                            cc = 0
                        }
                    }
                }
                if (fc >= srcFrames) break
            }

            val floats44 = if (srcRate == SAMPLE_RATE) rawStereo else resampleLinear(rawStereo, srcRate, SAMPLE_RATE)
            val shorts44 = ShortArray(floats44.size) { i ->
                (floats44[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            val durMs = ((floats44.size / 2L) * 1000L) / SAMPLE_RATE
            return DecodedTrackPcm(floats44, shorts44, SAMPLE_RATE, durMs)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun resampleLinear(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
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
