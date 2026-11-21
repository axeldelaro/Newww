package com.newww.luminaterrarium.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.newww.luminaterrarium.model.AmbientSoundscape
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthétiseur audio PCM 100 % hors-ligne (zéro fichier audio externe) :
 * - Ambiances continues apaisantes (pluie, forêt, feu, carillon zen)
 * - Effets sonores tactiles doux (caresse, tapotement verre, brume, repas, luciole)
 */
class AmbientSoundEngine {

    private val sampleRate = 22050
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ambientJob: Job? = null
    private var currentSoundscape: AmbientSoundscape = AmbientSoundscape.SILENT

    fun setSoundscape(soundscape: AmbientSoundscape) {
        if (currentSoundscape == soundscape && ambientJob?.isActive == true) return
        currentSoundscape = soundscape
        ambientJob?.cancel()
        if (soundscape == AmbientSoundscape.SILENT) return

        ambientJob = scope.launch {
            runAmbientLoop(soundscape)
        }
    }

    fun stopAmbient() {
        ambientJob?.cancel()
        ambientJob = null
    }

    fun release() {
        stopAmbient()
        scope.cancel()
    }

    fun playSfx(kind: SfxKind, enabled: Boolean) {
        if (!enabled) return
        scope.launch {
            runCatching {
                val samples = synthesizeSfx(kind)
                playOneShot(samples)
            }
        }
    }

    enum class SfxKind { PET, GLASS_TAP, FEED, MIST, CATCH_ORB, EVOLVE, LULLABY }

    private suspend fun runAmbientLoop(kind: AmbientSoundscape) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = runCatching {
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
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return

        try {
            track.play()
            val chunk = ShortArray(2048)
            var lp1 = 0.0
            var lp2 = 0.0
            var phase = 0.0
            var chimeEnv = 0.0
            var chimeFreq = 523.25
            var chimePhase = 0.0
            var sampleIndex = 0L
            val pentatonic = doubleArrayOf(392.0, 440.0, 523.25, 587.33, 659.25, 783.99)

            while (currentCoroutineContext().isActive) {
                for (i in chunk.indices) {
                    val white = Random.nextDouble() * 2.0 - 1.0
                    val sample = when (kind) {
                        AmbientSoundscape.SOFT_RAIN -> {
                            lp1 += 0.14 * (white - lp1)
                            lp2 += 0.03 * (lp1 - lp2)
                            val drop = if (Random.nextFloat() < 0.0005f) (Random.nextDouble() * 0.45) else 0.0
                            (lp1 * 0.45 + lp2 * 0.55 + drop) * 0.22
                        }
                        AmbientSoundscape.NIGHT_FOREST -> {
                            lp1 += 0.025 * (white - lp1)
                            val breeze = (0.55 + 0.45 * sin(sampleIndex * 0.00018)) * lp1 * 0.40
                            val cricketGate = if ((sampleIndex / 14000L) % 5L == 0L) 1.0 else 0.0
                            val cricket = cricketGate * sin(sampleIndex * 0.85) * sin(sampleIndex * 0.025) * 0.035
                            (breeze + cricket) * 0.42
                        }
                        AmbientSoundscape.COZY_FIRE -> {
                            lp1 += 0.04 * (white - lp1)
                            val crackle = if (Random.nextFloat() < 0.0007f) (Random.nextDouble() - 0.5) * 0.75 else 0.0
                            val rumble = sin(phase) * 0.08
                            phase += 2.0 * PI * 68.0 / sampleRate
                            (lp1 * 0.55 + crackle + rumble) * 0.28
                        }
                        AmbientSoundscape.WIND_CHIMES -> {
                            if (sampleIndex % 32000L == 0L && Random.nextFloat() < 0.72f) {
                                chimeEnv = 0.35
                                chimeFreq = pentatonic[Random.nextInt(pentatonic.size)]
                            }
                            lp1 += 0.02 * (white - lp1)
                            val bell = sin(chimePhase) * chimeEnv + sin(chimePhase * 2.0) * chimeEnv * 0.25
                            chimePhase += 2.0 * PI * chimeFreq / sampleRate
                            chimeEnv *= 0.99985
                            (bell + lp1 * 0.08) * 0.38
                        }
                        AmbientSoundscape.SILENT -> 0.0
                    }
                    sampleIndex++
                    chunk[i] = (sample.coerceIn(-0.95, 0.95) * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(chunk, 0, chunk.size)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun synthesizeSfx(kind: SfxKind): ShortArray {
        val durationSec = when (kind) {
            SfxKind.EVOLVE -> 0.75
            SfxKind.LULLABY -> 0.65
            SfxKind.MIST -> 0.35
            else -> 0.22
        }
        val len = (sampleRate * durationSec).toInt()
        val out = ShortArray(len)
        var lp = 0.0
        for (i in 0 until len) {
            val t = i.toDouble() / sampleRate
            val env = exp(-t * when (kind) {
                SfxKind.EVOLVE -> 3.0
                SfxKind.LULLABY -> 3.5
                SfxKind.MIST -> 5.0
                else -> 12.0
            })
            val v = when (kind) {
                SfxKind.PET -> (sin(2 * PI * (520 + t * 240) * t) * 0.4 + sin(2 * PI * 780 * t) * 0.2) * env * 0.35
                SfxKind.GLASS_TAP -> (sin(2 * PI * 1480 * t) * 0.5 + sin(2 * PI * 2220 * t) * 0.3) * exp(-t * 28.0) * 0.35
                SfxKind.FEED -> {
                    val f = if (t < 0.09) 440.0 else 660.0
                    sin(2 * PI * f * t) * env * 0.35
                }
                SfxKind.MIST -> {
                    val w = Random.nextDouble() * 2.0 - 1.0
                    lp += 0.32 * (w - lp)
                    (w - lp * 0.5) * env * 0.28
                }
                SfxKind.CATCH_ORB -> (sin(2 * PI * (680 + t * 520) * t)) * env * 0.38
                SfxKind.LULLABY -> {
                    val f = when {
                        t < 0.2 -> 392.0
                        t < 0.4 -> 493.88
                        else -> 587.33
                    }
                    sin(2 * PI * f * t) * env * 0.32
                }
                SfxKind.EVOLVE -> {
                    val f = when {
                        t < 0.18 -> 523.25
                        t < 0.36 -> 659.25
                        t < 0.54 -> 783.99
                        else -> 1046.5
                    }
                    (sin(2 * PI * f * t) + 0.3 * sin(4 * PI * f * t)) * env * 0.32
                }
            }
            out[i] = (v.coerceIn(-0.95, 0.95) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun playOneShot(samples: ShortArray) {
        val byteLen = samples.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
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
            .setBufferSizeInBytes(byteLen.coerceAtLeast(2048))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track.play()
        Thread.sleep(((samples.size.toDouble() / sampleRate) * 1000).toLong() + 40L)
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}
