package com.strikecam.arcade.nativeengine

import com.strikecam.arcade.data.BeatTarget
import com.strikecam.arcade.data.Difficulty
import com.strikecam.arcade.data.GeneratedBeatmap
import com.strikecam.arcade.data.TargetHand
import com.strikecam.arcade.data.TargetType
import kotlin.math.roundToInt

data class LiveBeatSignal(
    val triggered: Boolean,
    val isDrop: Boolean,
    val beatIndex: Int,
    val strength: Float,
    val estimatedBpm: Float,
    val beatTimeMs: Float
)

object StrikeCamNative {
    const val SAMPLE_RATE = 44100

    const val SFX_PERFECT = 0
    const val SFX_GREAT = 1
    const val SFX_GOOD = 2
    const val SFX_DODGE_CLEAR = 3
    const val SFX_MISS = 4
    const val SFX_COMBO_MILESTONE = 5
    const val SFX_COUNTDOWN_TICK = 6
    const val SFX_COUNTDOWN_GO = 7

    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        isLoaded = try {
            System.loadLibrary("strikecam_native")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    private external fun nativeGenerateBeatmap(
        pcmData: FloatArray,
        sampleRate: Int,
        channels: Int,
        difficulty: Int
    ): FloatArray

    private external fun nativeSynthesizeTrack(trackId: Int): ShortArray

    private external fun nativeGenerateBuiltInBeatmap(trackId: Int, difficulty: Int): FloatArray

    private external fun nativeSynthesizeSfx(sfxId: Int): ShortArray

    private external fun nativeCreateLiveTracker(sampleRate: Int, initialBpm: Float): Long
    private external fun nativeDestroyLiveTracker(handle: Long)
    private external fun nativeResetLiveTracker(handle: Long, nowMs: Double)
    private external fun nativeTrackerSetBpm(handle: Long, bpm: Float)
    private external fun nativeTrackerRegisterTap(handle: Long, nowMs: Double): Float
    private external fun nativeTrackerNudgePhase(handle: Long, deltaMs: Double)
    private external fun nativeTrackerProcessAudio(handle: Long, monoPcm: FloatArray, nowMs: Double): FloatArray
    private external fun nativeTrackerPollGrid(handle: Long, nowMs: Double, leadTimeMs: Double): FloatArray

    fun generateBeatmap(
        pcmData: FloatArray,
        sampleRate: Int,
        channels: Int,
        difficulty: Difficulty
    ): GeneratedBeatmap {
        if (!isLoaded || pcmData.isEmpty()) {
            return GeneratedBeatmap(140f, 0f, 0f, 0, emptyList())
        }
        val packed = nativeGenerateBeatmap(pcmData, sampleRate, channels, difficulty.nativeId)
        return unpackBeatmap(packed)
    }

    fun generateBuiltInBeatmap(trackId: Int, difficulty: Difficulty): GeneratedBeatmap {
        if (!isLoaded) return GeneratedBeatmap(140f, 0f, 0f, 0, emptyList())
        val packed = nativeGenerateBuiltInBeatmap(trackId, difficulty.nativeId)
        return unpackBeatmap(packed)
    }

    fun synthesizeTrackPcm16(trackId: Int): ShortArray {
        if (!isLoaded) return ShortArray(0)
        return nativeSynthesizeTrack(trackId)
    }

    fun synthesizeSfxPcm16(sfxId: Int): ShortArray {
        if (!isLoaded) return ShortArray(0)
        return nativeSynthesizeSfx(sfxId)
    }

    fun createLiveTracker(sampleRate: Int, initialBpm: Float): Long {
        if (!isLoaded) return 0L
        return nativeCreateLiveTracker(sampleRate, initialBpm)
    }

    fun destroyLiveTracker(handle: Long) {
        if (isLoaded && handle != 0L) nativeDestroyLiveTracker(handle)
    }

    fun resetLiveTracker(handle: Long, nowMs: Double) {
        if (isLoaded && handle != 0L) nativeResetLiveTracker(handle, nowMs)
    }

    fun setTrackerBpm(handle: Long, bpm: Float) {
        if (isLoaded && handle != 0L) nativeTrackerSetBpm(handle, bpm)
    }

    fun registerTrackerTap(handle: Long, nowMs: Double): Float {
        if (!isLoaded || handle == 0L) return 140f
        return nativeTrackerRegisterTap(handle, nowMs)
    }

    fun nudgeTrackerPhase(handle: Long, deltaMs: Double) {
        if (isLoaded && handle != 0L) nativeTrackerNudgePhase(handle, deltaMs)
    }

    fun processTrackerAudio(handle: Long, monoPcm: FloatArray, nowMs: Double): LiveBeatSignal {
        if (!isLoaded || handle == 0L) {
            return LiveBeatSignal(false, false, 0, 0f, 140f, nowMs.toFloat())
        }
        return unpackLiveSignal(nativeTrackerProcessAudio(handle, monoPcm, nowMs))
    }

    fun pollTrackerGrid(handle: Long, nowMs: Double, leadTimeMs: Double): LiveBeatSignal {
        if (!isLoaded || handle == 0L) {
            return LiveBeatSignal(false, false, 0, 0f, 140f, nowMs.toFloat())
        }
        return unpackLiveSignal(nativeTrackerPollGrid(handle, nowMs, leadTimeMs))
    }

    internal fun unpackBeatmap(packed: FloatArray): GeneratedBeatmap {
        if (packed.size < 5) {
            return GeneratedBeatmap(140f, 0f, 0f, 0, emptyList())
        }
        val bpm = packed[0]
        val firstBeatMs = packed[1]
        val durationMs = packed[2]
        val targetCount = packed[3].roundToInt().coerceAtLeast(0)
        val dropCount = packed[4].roundToInt().coerceAtLeast(0)

        val targets = ArrayList<BeatTarget>(targetCount)
        var offset = 5
        for (i in 0 until targetCount) {
            if (offset + 7 >= packed.size) break
            targets.add(
                BeatTarget(
                    id = packed[offset].roundToInt(),
                    timeMs = packed[offset + 1],
                    type = TargetType.fromNativeId(packed[offset + 2].roundToInt()),
                    hand = TargetHand.fromNativeId(packed[offset + 3].roundToInt()),
                    normX = packed[offset + 4],
                    normY = packed[offset + 5],
                    durationMs = packed[offset + 6],
                    intensity = packed[offset + 7]
                )
            )
            offset += 8
        }
        return GeneratedBeatmap(
            bpm = bpm,
            firstBeatMs = firstBeatMs,
            durationMs = durationMs,
            dropCount = dropCount,
            targets = targets
        )
    }

    private fun unpackLiveSignal(arr: FloatArray): LiveBeatSignal {
        if (arr.size < 6) {
            return LiveBeatSignal(false, false, 0, 0f, 140f, 0f)
        }
        return LiveBeatSignal(
            triggered = arr[0] > 0.5f,
            isDrop = arr[1] > 0.5f,
            beatIndex = arr[2].roundToInt(),
            strength = arr[3],
            estimatedBpm = arr[4],
            beatTimeMs = arr[5]
        )
    }
}
