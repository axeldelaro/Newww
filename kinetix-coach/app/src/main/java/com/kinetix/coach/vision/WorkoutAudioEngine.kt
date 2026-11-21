package com.kinetix.coach.vision

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

enum class BeepType {
    DOWN,
    HOLD,
    GO,
    COUNTDOWN,
    COMPLETE,
    WARNING
}

enum class WorkoutMusicTrack(
    val id: String,
    val labelFr: String,
    val bpm: Int,
    val subtitleFr: String
) {
    NONE("NONE", "Sans musique", 0, "Bips & coach vocal uniquement"),
    TECHNO_PULSE("TECHNO_PULSE", "Pulse 128 BPM", 128, "Kick 4/4, sub-basse & hi-hat"),
    FOCUS_SYNTH("FOCUS_SYNTH", "Focus 110 BPM", 110, "Arpège mineur hypnotique · idéal gainage"),
    HIIT_DRIVE("HIIT_DRIVE", "HIIT 142 BPM", 142, "Rythme nerveux · cardio & Tabata"),
    ZEN_BREATH("ZEN_BREATH", "Respiration 60 BPM", 60, "Pad harmonique · cycle 4s / 4s"),
    CUSTOM_LOCAL("CUSTOM_LOCAL", "Ma musique (Téléphone)", 0, "Fichier MP3 / M4A / WAV / OGG local");

    companion object {
        fun fromId(id: String): WorkoutMusicTrack =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * Pure-Kotlin 44.1 kHz 16-bit PCM procedural audio & workout music synthesizer.
 * Zero Android framework dependency — 100% deterministic & JVM unit-testable.
 */
object ProceduralAudioSynth {

    const val SAMPLE_RATE = 44100
    private const val MAX_PCM = 29500.0

    /** Generates a clean anti-click enveloped sine tone at [freqHz] for [durationMs]. */
    fun generateTonePcm(
        freqHz: Double,
        durationMs: Int,
        amplitude: Float = 0.65f,
        sampleRate: Int = SAMPLE_RATE
    ): ShortArray {
        val count = ((sampleRate * durationMs) / 1000.0).roundToInt().coerceAtLeast(1)
        val out = ShortArray(count)
        val amp = amplitude.coerceIn(0.02f, 0.95f).toDouble()
        val attackSamples = min(count / 4, (sampleRate * 0.008).roundToInt().coerceAtLeast(1))
        val releaseSamples = min(count / 3, (sampleRate * 0.022).roundToInt().coerceAtLeast(1))

        for (i in 0 until count) {
            val t = i.toDouble() / sampleRate
            val env = linearEnvelope(i, count, attackSamples, releaseSamples)
            val sample = sin(2.0 * PI * freqHz * t) * env * amp
            out[i] = softClipToPcm(sample)
        }
        return out
    }

    /** Generates crisp synthesized cadence/cue beeps for every [BeepType]. */
    fun generateBeepPcm(type: BeepType, sampleRate: Int = SAMPLE_RATE): ShortArray = when (type) {
        BeepType.DOWN -> {
            // Punchy 440 Hz 120ms sine+triangle downward cue
            val count = (sampleRate * 0.120).roundToInt()
            val out = ShortArray(count)
            var phase = 0.0
            for (i in 0 until count) {
                val frac = i.toDouble() / count
                val freq = 456.0 - 28.0 * frac
                phase += 2.0 * PI * freq / sampleRate
                val sine = sin(phase)
                val tri = triangleFromPhase(phase)
                val env = linearEnvelope(i, count, (sampleRate * 0.006).roundToInt(), (sampleRate * 0.032).roundToInt())
                out[i] = softClipToPcm((0.70 * sine + 0.30 * tri) * env * 0.72)
            }
            out
        }

        BeepType.HOLD -> {
            // Soft rhythmic 587 Hz 65ms click ("Tiens")
            val count = (sampleRate * 0.065).roundToInt()
            val out = ShortArray(count)
            for (i in 0 until count) {
                val t = i.toDouble() / sampleRate
                val attack = min(1.0, i / (sampleRate * 0.004))
                val decay = exp(-t * 42.0)
                val sig = sin(2.0 * PI * 587.33 * t) + 0.20 * sin(2.0 * PI * 1174.66 * t)
                out[i] = softClipToPcm(sig * attack * decay * 0.62)
            }
            out
        }

        BeepType.GO -> {
            // Bright double-harmonic 880 Hz -> 1175 Hz 150ms chime ("GO ! Pousse !")
            val count = (sampleRate * 0.150).roundToInt()
            val split = (sampleRate * 0.065).roundToInt()
            val out = ShortArray(count)
            for (i in 0 until count) {
                val t = i.toDouble() / sampleRate
                val sig = if (i < split) {
                    val env = linearEnvelope(i, split, (sampleRate * 0.004).roundToInt(), (sampleRate * 0.012).roundToInt())
                    (sin(2.0 * PI * 880.0 * t) + 0.25 * sin(2.0 * PI * 1760.0 * t)) * env * 0.74
                } else {
                    val j = i - split
                    val segLen = count - split
                    val env = linearEnvelope(j, segLen, (sampleRate * 0.004).roundToInt(), (sampleRate * 0.035).roundToInt())
                    (sin(2.0 * PI * 1174.66 * t) + 0.22 * sin(2.0 * PI * 2349.32 * t)) * env * 0.78
                }
                out[i] = softClipToPcm(sig)
            }
            out
        }

        BeepType.COUNTDOWN -> {
            // Crisp 660 Hz 90ms 3-2-1 tick
            val count = (sampleRate * 0.090).roundToInt()
            val out = ShortArray(count)
            for (i in 0 until count) {
                val t = i.toDouble() / sampleRate
                val env = linearEnvelope(i, count, (sampleRate * 0.005).roundToInt(), (sampleRate * 0.025).roundToInt())
                val sig = (0.85 * sin(2.0 * PI * 659.25 * t) + 0.15 * sin(2.0 * PI * 1318.5 * t)) * env * 0.70
                out[i] = softClipToPcm(sig)
            }
            out
        }

        BeepType.COMPLETE -> {
            // Ascending C5-E5-G5-C6 victory triad (440ms)
            val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50)
            val stepSamples = (sampleRate * 0.090).roundToInt()
            val tailSamples = (sampleRate * 0.080).roundToInt()
            val total = notes.size * stepSamples + tailSamples
            val out = ShortArray(total)
            for (i in 0 until total) {
                var mix = 0.0
                for (nIdx in notes.indices) {
                    val start = nIdx * stepSamples
                    if (i >= start) {
                        val dt = (i - start).toDouble() / sampleRate
                        val attack = min(1.0, (i - start) / (sampleRate * 0.006))
                        val decay = exp(-dt * (if (nIdx == notes.lastIndex) 5.5 else 9.0))
                        val f = notes[nIdx]
                        mix += (0.82 * sin(2.0 * PI * f * dt) + 0.18 * sin(2.0 * PI * f * 2.0 * dt)) * attack * decay * 0.42
                    }
                }
                out[i] = softClipToPcm(mix)
            }
            out
        }

        BeepType.WARNING -> {
            // Low 310 Hz double buzz (95ms + 50ms gap + 95ms)
            val pulseLen = (sampleRate * 0.095).roundToInt()
            val gapLen = (sampleRate * 0.050).roundToInt()
            val total = pulseLen * 2 + gapLen
            val out = ShortArray(total)
            for (i in 0 until total) {
                val inFirst = i < pulseLen
                val inSecond = i >= pulseLen + gapLen
                if (inFirst || inSecond) {
                    val localIdx = if (inFirst) i else i - (pulseLen + gapLen)
                    val t = localIdx.toDouble() / sampleRate
                    val env = linearEnvelope(localIdx, pulseLen, (sampleRate * 0.006).roundToInt(), (sampleRate * 0.018).roundToInt())
                    val sig = (0.65 * sin(2.0 * PI * 310.0 * t) +
                        0.25 * sin(2.0 * PI * 620.0 * t) +
                        0.10 * sin(2.0 * PI * 930.0 * t)) * env * 0.72
                    out[i] = softClipToPcm(sig)
                }
            }
            out
        }
    }

    /**
     * Renders 1 full musical bar (4 beats) of 16-bit mono PCM for any procedural [WorkoutMusicTrack].
     * Returns an empty array for [WorkoutMusicTrack.NONE] or [WorkoutMusicTrack.CUSTOM_LOCAL].
     */
    fun renderBeatBarPcm(
        track: WorkoutMusicTrack,
        barIndex: Int,
        finalSprint: Boolean = false,
        sampleRate: Int = SAMPLE_RATE
    ): ShortArray {
        val bpm = track.bpm
        if (bpm <= 0) return ShortArray(0)

        val beatSec = 60.0 / bpm
        val barSec = beatSec * 4.0
        val numSamples = (barSec * sampleRate).roundToInt().coerceAtLeast(1)
        val out = ShortArray(numSamples)
        val phraseBar = abs(barIndex) % 4

        when (track) {
            WorkoutMusicTrack.TECHNO_PULSE -> {
                // Punchy 4-on-the-floor kick, rolling sub-bass groove, crisp offbeat hi-hats
                val rootFreqs = doubleArrayOf(55.0, 55.0, 65.41, 49.0) // A1, A1, C2, G1
                val root = rootFreqs[phraseBar]
                val stepSec = beatSec / 4.0 // 16th note
                val bassPattern = doubleArrayOf(
                    1.0, 0.0, 1.0, 1.0,
                    0.0, 1.0, 1.189, 0.0,
                    1.0, 0.0, 1.0, 1.335,
                    0.0, 1.0, 1.498, 1.189
                )

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val beatPos = t % beatSec
                    val stepIdx = floor(t / stepSec).toInt().coerceIn(0, 15)
                    val stepLocal = t - stepIdx * stepSec

                    // 4-on-the-floor kick
                    val kick = synthKick(beatPos, startHz = 138.0, endHz = 46.0, decay = 24.0) * 0.62

                    // Rolling sub-bass
                    val mult = bassPattern[stepIdx]
                    val bass = if (mult > 0.0) {
                        val f = root * mult
                        val bEnv = min(1.0, stepLocal / 0.006) * exp(-stepLocal * 13.0)
                        (0.75 * sin(2.0 * PI * f * t) + 0.25 * triangleFromPhase(2.0 * PI * f * 2.0 * t)) * bEnv * 0.36
                    } else 0.0

                    // Crisp offbeat hi-hat (or 16th-note drive on finalSprint)
                    val hatTrigger = if (finalSprint) (stepIdx % 2 == 1 || stepIdx % 4 == 2) else (stepIdx % 4 == 2)
                    val hat = if (hatTrigger) {
                        val hEnv = exp(-stepLocal * 65.0)
                        highPassNoise(barIndex * 131071 + i) * hEnv * (if (finalSprint) 0.22 else 0.16)
                    } else 0.0

                    // Energetic synth stab on final sprint
                    val sprintStab = if (finalSprint && (stepIdx == 4 || stepIdx == 12)) {
                        val sEnv = min(1.0, stepLocal / 0.005) * exp(-stepLocal * 18.0)
                        val chord = sin(2.0 * PI * 440.0 * t) + sin(2.0 * PI * 523.25 * t) + sin(2.0 * PI * 659.25 * t)
                        chord * sEnv * 0.11
                    } else 0.0

                    out[i] = softClipToPcm(kick + bass + hat + sprintStab)
                }
            }

            WorkoutMusicTrack.FOCUS_SYNTH -> {
                // Deep hypnotic arpeggiated minor synth pulse + soft heartbeat kick
                val chords = arrayOf(
                    doubleArrayOf(220.0, 261.63, 329.63, 392.0),  // Am7
                    doubleArrayOf(174.61, 220.0, 261.63, 329.63), // Fmaj7
                    doubleArrayOf(130.81, 196.0, 261.63, 329.63), // C
                    doubleArrayOf(196.0, 246.94, 293.66, 392.0)   // G
                )
                val chord = chords[phraseBar]
                val eighthSec = beatSec / 2.0

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val eighthIdx = floor(t / eighthSec).toInt().coerceIn(0, 7)
                    val eighthLocal = t - eighthIdx * eighthSec

                    // Soft heartbeat kick on beats 0 & 2.5 (or every beat in finalSprint)
                    val kick = if (finalSprint) {
                        synthKick(t % beatSec, 110.0, 44.0, 26.0) * 0.45
                    } else {
                        val k1 = if (t < 0.22) synthKick(t, 98.0, 42.0, 22.0) else 0.0
                        val k2Start = beatSec * 2.5
                        val k2 = if (t >= k2Start && t < k2Start + 0.22) synthKick(t - k2Start, 90.0, 42.0, 25.0) else 0.0
                        (k1 + k2) * 0.42
                    }

                    // Hypnotic minor arpeggio (lifted an octave on sprint)
                    val arpFreq = chord[eighthIdx % 4] * (if (finalSprint) 2.0 else 1.0)
                    val aEnv = min(1.0, eighthLocal / 0.010) * exp(-eighthLocal * 6.5)
                    val arp = (0.65 * sin(2.0 * PI * arpFreq * t) + 0.35 * triangleFromPhase(2.0 * PI * arpFreq * t)) * aEnv * 0.30

                    // Warm sub-pad root
                    val rootHz = chord[0] * 0.5
                    val pad = sin(2.0 * PI * rootHz * t) * 0.24

                    out[i] = softClipToPcm(kick + arp + pad)
                }
            }

            WorkoutMusicTrack.HIIT_DRIVE -> {
                // High-energy rhythmic bass + snappy snare/hats (142 BPM)
                val rootFreqs = doubleArrayOf(73.42, 73.42, 87.31, 65.41) // D2, D2, F2, C2
                val root = rootFreqs[phraseBar]
                val stepSec = beatSec / 4.0

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val stepIdx = floor(t / stepSec).toInt().coerceIn(0, 15)
                    val stepLocal = t - stepIdx * stepSec

                    // Driving kick on steps 0, 6, 8, 12 (+ step 14 on sprint)
                    val kickStep = stepIdx == 0 || stepIdx == 6 || stepIdx == 8 || stepIdx == 12 || (finalSprint && stepIdx == 14)
                    val kick = if (kickStep) synthKick(stepLocal, 150.0, 48.0, 28.0) * 0.60 else 0.0

                    // Snappy snare on beats 1 & 3 (steps 4 & 12 -> wait beat 1 = step 4, beat 3 = step 12)
                    val snareStep = stepIdx == 4 || stepIdx == 12 || (finalSprint && phraseBar == 3 && stepIdx >= 13)
                    val snare = if (snareStep) {
                        val sEnv = exp(-stepLocal * 30.0)
                        val body = sin(2.0 * PI * 195.0 * stepLocal) * exp(-stepLocal * 42.0) * 0.28
                        val noise = highPassNoise(barIndex * 99991 + i) * sEnv * 0.32
                        body + noise
                    } else 0.0

                    // 8th-note driving synth bass
                    val eighthLocal = t % (beatSec / 2.0)
                    val bMult = if (stepIdx % 4 == 2) 1.5 else 1.0
                    val bEnv = min(1.0, eighthLocal / 0.004) * exp(-eighthLocal * 14.0)
                    val bass = (0.60 * sin(2.0 * PI * root * bMult * t) +
                        0.40 * triangleFromPhase(2.0 * PI * root * bMult * 2.0 * t)) * bEnv * 0.34

                    // Sizzling 16th hats
                    val hatAmp = if (stepIdx % 2 == 1) 0.16 else 0.08
                    val hat = highPassNoise(barIndex * 65537 + i * 3) * exp(-stepLocal * 78.0) * hatAmp

                    out[i] = softClipToPcm(kick + snare + bass + hat)
                }
            }

            WorkoutMusicTrack.ZEN_BREATH -> {
                // Warm calming harmonic pad + gentle 4s inhale / 4s exhale swell (60 BPM = 4s/bar)
                val isInhale = (barIndex % 2 == 0)
                val padFreqs = doubleArrayOf(110.0, 164.81, 220.0, 246.94, 277.18) // A2, E3, A3, B3, C#4 (Aadd9)

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val barFrac = t / barSec // 0..1 across 4 seconds
                    // Smooth breathing swell: inhale rises 0.35 -> 0.85, exhale settles 0.85 -> 0.35
                    val swell = if (isInhale) {
                        0.35 + 0.50 * (0.5 - 0.5 * kotlin.math.cos(PI * barFrac))
                    } else {
                        0.35 + 0.50 * (0.5 + 0.5 * kotlin.math.cos(PI * barFrac))
                    }

                    var pad = 0.0
                    for (idx in padFreqs.indices) {
                        val weight = if (idx < 2) 0.24 else 0.13
                        pad += sin(2.0 * PI * padFreqs[idx] * t) * weight
                    }
                    pad *= swell

                    // Gentle resonant chime at the start of every 4s breath inflection
                    val chime = if (t < 0.75) {
                        val cFreq = if (isInhale) 554.37 else 440.0
                        val cEnv = min(1.0, t / 0.025) * exp(-t * 5.2)
                        sin(2.0 * PI * cFreq * t) * cEnv * 0.24
                    } else 0.0

                    // Subtle encouraging pulse in the final 15s sprint
                    val sprintPulse = if (finalSprint) {
                        synthKick(t % 0.5, 92.0, 44.0, 26.0) * 0.30
                    } else 0.0

                    out[i] = softClipToPcm(pad + chime + sprintPulse)
                }
            }

            WorkoutMusicTrack.NONE, WorkoutMusicTrack.CUSTOM_LOCAL -> return ShortArray(0)
        }
        return out
    }

    private fun synthKick(t: Double, startHz: Double, endHz: Double, decay: Double): Double {
        if (t < 0.0 || t > 0.24) return 0.0
        // Analytic integral of instantaneous frequency for smooth pitch sweep
        val phase = 2.0 * PI * (endHz * t + (startHz - endHz) * (1.0 - exp(-t * 36.0)) / 36.0)
        val attack = min(1.0, t / 0.002)
        val env = exp(-t * decay)
        return sin(phase) * attack * env
    }

    private fun triangleFromPhase(phase: Double): Double {
        val norm = (phase / (2.0 * PI)) - floor(phase / (2.0 * PI))
        return 4.0 * abs(norm - 0.5) - 1.0
    }

    /** Deterministic 1-pole differenced pseudo-random noise for crisp zero-allocation hi-hats & snares. */
    private fun highPassNoise(seed: Int): Double {
        val n1 = hashNoise(seed)
        val n0 = hashNoise(seed - 1)
        return (n1 - n0) * 0.5
    }

    private fun hashNoise(n: Int): Double {
        var x = n * 747796405 + 289133645
        x = ((x ushr ((x ushr 28) + 4)) xor x) * 277803737
        x = (x ushr 22) xor x
        return ((x and 0xFFFF) / 32767.5) - 1.0
    }

    private fun linearEnvelope(index: Int, total: Int, attack: Int, release: Int): Double {
        val a = if (attack > 0 && index < attack) index.toDouble() / attack else 1.0
        val rem = total - 1 - index
        val r = if (release > 0 && rem < release) max(0.0, rem.toDouble() / release) else 1.0
        return min(a, r)
    }

    private fun softClipToPcm(sample: Double): Short {
        val shaped = tanh(sample * 0.90) * MAX_PCM
        return shaped.roundToInt().coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE - 1).toShort()
    }
}

/**
 * 100% offline workout audio & music engine combining:
 * - Zero-latency synthesized cadence beeps ([AudioTrack])
 * - Built-in procedural workout music tracks streamed in responsive 2048-sample chunks
 * - Local user MP3 / M4A / WAV / OGG playback via Android Storage Access Framework [MediaPlayer]
 * - Automatic volume ducking during rest/posture pauses and final-15s intensity boost
 */
class WorkoutAudioEngine(context: Context) {

    companion object {
        fun generateTonePcm(
            freqHz: Double,
            durationMs: Int,
            amplitude: Float = 0.65f,
            sampleRate: Int = ProceduralAudioSynth.SAMPLE_RATE
        ): ShortArray = ProceduralAudioSynth.generateTonePcm(freqHz, durationMs, amplitude, sampleRate)

        fun generateBeepPcm(
            type: BeepType,
            sampleRate: Int = ProceduralAudioSynth.SAMPLE_RATE
        ): ShortArray = ProceduralAudioSynth.generateBeepPcm(type, sampleRate)

        fun renderBeatBarPcm(
            track: WorkoutMusicTrack,
            barIndex: Int,
            finalSprint: Boolean = false,
            sampleRate: Int = ProceduralAudioSynth.SAMPLE_RATE
        ): ShortArray = ProceduralAudioSynth.renderBeatBarPcm(track, barIndex, finalSprint, sampleRate)
    }

    private val appContext = context.applicationContext
    private val beepExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kinetix-beep").apply { isDaemon = true }
    }

    private val musicRunning = AtomicBoolean(false)
    private var musicThread: Thread? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activeTrack: WorkoutMusicTrack = WorkoutMusicTrack.NONE

    @Volatile
    var beepsEnabled: Boolean = true

    @Volatile
    var musicVolume: Float = 0.72f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyMediaPlayerVolume()
        }

    @Volatile
    private var isDucked: Boolean = false

    @Volatile
    private var isMusicPaused: Boolean = false

    @Volatile
    private var isFinalSprint: Boolean = false

    @Volatile
    private var isMuted: Boolean = false

    fun setMuted(muted: Boolean) {
        isMuted = muted
        applyMediaPlayerVolume()
    }

    fun setDucked(ducked: Boolean) {
        isDucked = ducked
        applyMediaPlayerVolume()
    }

    fun setFinalSprint(sprint: Boolean) {
        isFinalSprint = sprint
    }

    fun setMusicPaused(paused: Boolean) {
        isMusicPaused = paused
        synchronized(this) {
            runCatching {
                val mp = mediaPlayer ?: return@runCatching
                if (paused && mp.isPlaying) {
                    mp.pause()
                } else if (!paused && !mp.isPlaying && activeTrack == WorkoutMusicTrack.CUSTOM_LOCAL) {
                    mp.start()
                }
            }
        }
    }

    fun playBeep(type: BeepType) {
        if (!beepsEnabled) return
        runCatching {
            beepExecutor.execute {
                playPcmBlocking(ProceduralAudioSynth.generateBeepPcm(type))
            }
        }
    }

    fun startMusic(track: WorkoutMusicTrack, customUri: Uri? = null, onCustomError: ((String) -> Unit)? = null) {
        stopMusic()
        activeTrack = track
        isMusicPaused = false
        isFinalSprint = false
        isDucked = false

        when (track) {
            WorkoutMusicTrack.NONE -> Unit
            WorkoutMusicTrack.CUSTOM_LOCAL -> {
                if (customUri == null) {
                    onCustomError?.invoke("Aucun fichier audio sélectionné")
                    return
                }
                startLocalMediaPlayer(customUri, onCustomError)
            }
            else -> startProceduralStream(track)
        }
    }

    fun stopMusic() {
        musicRunning.set(false)
        val t = musicThread
        musicThread = null
        if (t != null && t != Thread.currentThread()) {
            runCatching { t.join(250L) }
        }
        synchronized(this) {
            runCatching {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) mp.stop()
                    mp.reset()
                    mp.release()
                }
            }
            mediaPlayer = null
            activeTrack = WorkoutMusicTrack.NONE
        }
    }

    fun release() {
        stopMusic()
        runCatching { beepExecutor.shutdownNow() }
    }

    private fun startLocalMediaPlayer(uri: Uri, onError: ((String) -> Unit)?) {
        synchronized(this) {
            runCatching {
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(appContext, uri)
                    isLooping = true
                    setOnPreparedListener { player ->
                        applyMediaPlayerVolume()
                        if (!isMusicPaused) {
                            runCatching { player.start() }
                        }
                    }
                    setOnErrorListener { _, _, _ ->
                        onError?.invoke("Lecture impossible pour ce fichier audio")
                        true
                    }
                    prepareAsync()
                }
                mediaPlayer = mp
            }.onFailure { err ->
                onError?.invoke("Fichier audio inaccessible : ${err.localizedMessage ?: "erreur"}")
            }
        }
    }

    private fun startProceduralStream(track: WorkoutMusicTrack) {
        musicRunning.set(true)
        val thread = Thread({
            val sampleRate = ProceduralAudioSynth.SAMPLE_RATE
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val audioTrack = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(minBuf * 2, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return@Thread

            try {
                audioTrack.play()
                var barIdx = 0
                val chunkBuf = ShortArray(2048)

                while (musicRunning.get()) {
                    if (isMusicPaused || isMuted) {
                        Thread.sleep(40L)
                        continue
                    }
                    val barPcm = ProceduralAudioSynth.renderBeatBarPcm(
                        track = track,
                        barIndex = barIdx++,
                        finalSprint = isFinalSprint,
                        sampleRate = sampleRate
                    )
                    if (barPcm.isEmpty()) break

                    var offset = 0
                    while (offset < barPcm.size && musicRunning.get()) {
                        if (isMusicPaused || isMuted) {
                            Thread.sleep(35L)
                            continue
                        }
                        val chunkLen = min(chunkBuf.size, barPcm.size - offset)
                        val gain = effectiveMusicGain()
                        for (j in 0 until chunkLen) {
                            chunkBuf[j] = (barPcm[offset + j] * gain).roundToInt()
                                .coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE - 1)
                                .toShort()
                        }
                        audioTrack.write(chunkBuf, 0, chunkLen)
                        offset += chunkLen
                    }
                }
            } catch (_: Throwable) {
                // Safe exit on interruption or audio device transition
            } finally {
                runCatching {
                    audioTrack.pause()
                    audioTrack.flush()
                    audioTrack.stop()
                    audioTrack.release()
                }
            }
        }, "kinetix-music-${track.id}").apply {
            isDaemon = true
        }
        musicThread = thread
        thread.start()
    }

    private fun playPcmBlocking(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val sampleRate = ProceduralAudioSynth.SAMPLE_RATE
        val bytesLen = pcm.size * 2
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, bytesLen))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return

        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            val durMs = ((pcm.size * 1000L) / sampleRate) + 45L
            Thread.sleep(durMs)
        } catch (_: Throwable) {
            // Safe cleanup
        } finally {
            runCatching {
                track.stop()
                track.release()
            }
        }
    }

    private fun effectiveMusicGain(): Float {
        if (isMuted) return 0f
        val duckFactor = if (isDucked) 0.30f else 1.0f
        val sprintFactor = if (isFinalSprint) 1.12f else 1.0f
        return (musicVolume * duckFactor * sprintFactor).coerceIn(0f, 0.95f)
    }

    private fun applyMediaPlayerVolume() {
        val gain = effectiveMusicGain()
        synchronized(this) {
            runCatching { mediaPlayer?.setVolume(gain, gain) }
        }
    }
}
