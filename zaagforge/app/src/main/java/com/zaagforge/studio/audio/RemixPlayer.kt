package com.zaagforge.studio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.zaagforge.studio.model.MeterReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/**
 * Low-latency real-time Stereo PCM player featuring:
 * - Instant zero-glitch A/B comparison toggle between ORIGINAL and REMIX buffers (synced by progress %)
 * - Interactive waveform seeking and section jumping
 * - Live L/R peak + peak-hold VU meter telemetry
 * - One-shot Kick Audition Pad playback
 */
class RemixPlayer(
    private val sampleRate: Int = AudioDecoder.TARGET_SAMPLE_RATE
) {
    private val lock = Any()

    @Volatile
    private var originalPcm: FloatArray = FloatArray(0)

    @Volatile
    private var remixPcm: FloatArray = FloatArray(0)

    @Volatile
    private var auditionPcm: FloatArray? = null

    @Volatile
    private var auditionCursorFrame: Int = 0

    @Volatile
    private var playingRemix: Boolean = true

    @Volatile
    private var isPlayingFlag: Boolean = false

    @Volatile
    private var positionFraction: Float = 0f

    @Volatile
    private var stopWorker: Boolean = false

    private var playbackThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _isRemixActive = MutableStateFlow(true)
    val isRemixActive: StateFlow<Boolean> = _isRemixActive.asStateFlow()

    private val _meter = MutableStateFlow(MeterReading())
    val meter: StateFlow<MeterReading> = _meter.asStateFlow()

    fun loadBuffers(original: FloatArray, remix: FloatArray? = null) {
        synchronized(lock) {
            originalPcm = original
            remixPcm = remix ?: FloatArray(0)
            playingRemix = remix != null && remix.isNotEmpty()
            _isRemixActive.value = playingRemix
            positionFraction = 0f
            _progress.value = 0f
        }
    }

    fun setRemixBuffer(remix: FloatArray, autoSwitchToRemix: Boolean = true) {
        synchronized(lock) {
            remixPcm = remix
            if (autoSwitchToRemix && remix.isNotEmpty()) {
                playingRemix = true
                _isRemixActive.value = true
            }
        }
    }

    fun toggleAbSource() {
        synchronized(lock) {
            val hasRemix = remixPcm.isNotEmpty()
            val hasOrig = originalPcm.isNotEmpty()
            if (!hasRemix || !hasOrig) return
            playingRemix = !playingRemix
            _isRemixActive.value = playingRemix
        }
    }

    fun setPlayRemix(useRemix: Boolean) {
        synchronized(lock) {
            if (useRemix && remixPcm.isEmpty()) return
            if (!useRemix && originalPcm.isEmpty()) return
            playingRemix = useRemix
            _isRemixActive.value = useRemix
        }
    }

    fun seekToFraction(frac: Float) {
        val clamped = frac.coerceIn(0f, 0.999f)
        positionFraction = clamped
        _progress.value = clamped
    }

    fun playAuditionLoop(stereoPcm: FloatArray) {
        if (stereoPcm.isEmpty()) return
        auditionPcm = stereoPcm
        auditionCursorFrame = 0
        ensureWorkerRunning()
    }

    fun togglePlayPause() {
        if (isPlayingFlag) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        val active = currentActiveBuffer()
        if (active.isEmpty()) return
        auditionPcm = null
        isPlayingFlag = true
        _isPlaying.value = true
        ensureWorkerRunning()
    }

    fun pause() {
        isPlayingFlag = false
        _isPlaying.value = false
        _meter.value = MeterReading()
    }

    fun release() {
        stopWorker = true
        isPlayingFlag = false
        auditionPcm = null
        _isPlaying.value = false
        runCatching { playbackThread?.join(250L) }
        playbackThread = null
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        }
        audioTrack = null
        _meter.value = MeterReading()
    }

    private fun currentActiveBuffer(): FloatArray {
        val r = remixPcm
        val o = originalPcm
        return if (playingRemix && r.isNotEmpty()) r else o
    }

    private fun ensureWorkerRunning() {
        synchronized(lock) {
            if (playbackThread?.isAlive == true) return
            stopWorker = false
            playbackThread = thread(name = "ZaagForgeAudioOut", isDaemon = true) {
                runAudioLoop()
            }
        }
    }

    private fun runAudioLoop() {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(8192)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        val chunkFrames = 1024
        val chunk = FloatArray(chunkFrames * 2)
        var holdL = 0f
        var holdR = 0f
        var meterTick = 0

        try {
            while (!stopWorker) {
                val aud = auditionPcm
                if (aud != null && aud.isNotEmpty()) {
                    val totalAudFrames = aud.size / 2
                    val startFrame = auditionCursorFrame
                    if (startFrame >= totalAudFrames) {
                        auditionPcm = null
                        if (!isPlayingFlag) {
                            _meter.value = MeterReading()
                        }
                        continue
                    }
                    val count = minOf(chunkFrames, totalAudFrames - startFrame)
                    System.arraycopy(aud, startFrame * 2, chunk, 0, count * 2)
                    auditionCursorFrame = startFrame + count
                    track.write(chunk, 0, count * 2, AudioTrack.WRITE_BLOCKING)
                    updateMeter(chunk, count, holdL, holdR).also { (hl, hr) ->
                        holdL = hl
                        holdR = hr
                    }
                    continue
                }

                if (!isPlayingFlag) {
                    holdL *= 0.85f
                    holdR *= 0.85f
                    if (holdL < 0.01f && holdR < 0.01f) {
                        if (_meter.value.leftPeak > 0f || _meter.value.rightPeak > 0f) {
                            _meter.value = MeterReading()
                        }
                    }
                    Thread.sleep(24L)
                    continue
                }

                val buf = currentActiveBuffer()
                val totalFrames = buf.size / 2
                if (totalFrames <= 0) {
                    isPlayingFlag = false
                    _isPlaying.value = false
                    continue
                }

                var framePos = (positionFraction * totalFrames).toInt().coerceIn(0, totalFrames - 1)
                val count = minOf(chunkFrames, totalFrames - framePos)
                System.arraycopy(buf, framePos * 2, chunk, 0, count * 2)
                framePos += count

                track.write(chunk, 0, count * 2, AudioTrack.WRITE_BLOCKING)

                if (framePos >= totalFrames) {
                    positionFraction = 0f
                    _progress.value = 0f
                    isPlayingFlag = false
                    _isPlaying.value = false
                } else {
                    val nextFrac = framePos.toFloat() / totalFrames.toFloat()
                    positionFraction = nextFrac
                    if (meterTick % 2 == 0) {
                        _progress.value = nextFrac
                    }
                }
                meterTick++
                val (hl, hr) = updateMeter(chunk, count, holdL, holdR)
                holdL = hl
                holdR = hr
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

    private fun updateMeter(
        chunk: FloatArray,
        frames: Int,
        prevHoldL: Float,
        prevHoldR: Float
    ): Pair<Float, Float> {
        var pkL = 0f
        var pkR = 0f
        for (i in 0 until frames) {
            val l = abs(chunk[i * 2])
            val r = abs(chunk[i * 2 + 1])
            if (l > pkL) pkL = l
            if (r > pkR) pkR = r
        }
        val nextHoldL = max(pkL, prevHoldL * 0.94f)
        val nextHoldR = max(pkR, prevHoldR * 0.94f)
        _meter.value = MeterReading(
            leftPeak = pkL.coerceIn(0f, 1f),
            rightPeak = pkR.coerceIn(0f, 1f),
            leftPeakHold = nextHoldL.coerceIn(0f, 1f),
            rightPeakHold = nextHoldR.coerceIn(0f, 1f)
        )
        return nextHoldL to nextHoldR
    }
}
