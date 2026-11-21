package com.kinetix.coach.vision

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Real-time stability telemetry produced by [PoseSanityFilter] to guard rep counting and
 * auto-exercise switching during camera repositioning, walking across frame, or occlusion.
 */
data class MotionGuardState(
    val isUnstableTransition: Boolean = false,
    val meanCoreVisibility: Float = 1f,
    val hipHorizontalSpeed: Float = 0f,
    val bodyScaleRate: Float = 0f,
    val reasonFr: String? = null
)

data class SanityFilterResult(
    val screenPoints: List<PosePoint>,
    val worldPoints: List<PosePoint>,
    val motionGuard: MotionGuardState
)

/**
 * Stateful physics-based landmark sanity gate running BEFORE [PoseOneEuroSmoother]:
 * 1. Teleportation Spike Rejection: clamps single-frame low-confidence MediaPipe jumps.
 * 2. Brief Occlusion Hold (< 180 ms): holds last valid coordinates with decaying visibility.
 * 3. Global Horizontal Translation & Camera Zoom Guard: blocks phantom reps & switches
 *    while walking to/from the phone without blocking vertical exercise movement.
 */
class PoseSanityFilter(
    private val landmarkCount: Int = 33
) {
    companion object {
        private const val MAX_SCREEN_JUMP = 0.24f
        private const val MAX_WORLD_JUMP = 0.34f
        private const val SPIKE_DT_MAX_MS = 120L
        private const val SPIKE_VIS_THRESHOLD = 0.78f
        private const val SPIKE_BLEND_FACTOR = 0.15f

        private const val OCCLUSION_VIS_THRESHOLD = 0.30f
        private const val OCCLUSION_HOLD_MS = 180L
        private const val RESET_GAP_MS = 450L

        private const val CORE_VIS_MIN = 0.52f
        private const val HIP_HORIZ_SPEED_THRESHOLD = 0.42f
        private const val BODY_SCALE_RATE_THRESHOLD = 0.70f
        private const val SUSTAINED_MOTION_FRAMES = 3
        private const val STABILIZATION_COOLDOWN_MS = 650L
    }

    private var lastValidScreen = arrayOfNulls<PosePoint>(landmarkCount)
    private var lastValidScreenTs = LongArray(landmarkCount) { Long.MIN_VALUE }
    private var lastValidWorld = arrayOfNulls<PosePoint>(landmarkCount)
    private var lastValidWorldTs = LongArray(landmarkCount) { Long.MIN_VALUE }

    private var lastOutputScreen: List<PosePoint> = emptyList()
    private var lastOutputWorld: List<PosePoint> = emptyList()
    private var lastFrameTsMs: Long = Long.MIN_VALUE

    private var prevMidHipX: Float = Float.NaN
    private var prevBodyScale: Float = Float.NaN
    private var horizontalWalkStreak: Int = 0
    private var scaleZoomStreak: Int = 0
    private var lastUnstableTsMs: Long = Long.MIN_VALUE
    private var lastUnstableReasonFr: String? = null
    private var lastMotionGuard: MotionGuardState = MotionGuardState()

    fun reset() {
        lastValidScreen.fill(null)
        lastValidScreenTs.fill(Long.MIN_VALUE)
        lastValidWorld.fill(null)
        lastValidWorldTs.fill(Long.MIN_VALUE)
        lastOutputScreen = emptyList()
        lastOutputWorld = emptyList()
        lastFrameTsMs = Long.MIN_VALUE
        prevMidHipX = Float.NaN
        prevBodyScale = Float.NaN
        horizontalWalkStreak = 0
        scaleZoomStreak = 0
        lastUnstableTsMs = Long.MIN_VALUE
        lastUnstableReasonFr = null
        lastMotionGuard = MotionGuardState()
    }

    fun filter(
        rawScreen: List<PosePoint>,
        rawWorld: List<PosePoint>,
        timestampMs: Long
    ): SanityFilterResult {
        if (rawScreen.size < 29) {
            return SanityFilterResult(
                screenPoints = rawScreen,
                worldPoints = rawWorld,
                motionGuard = MotionGuardState(
                    isUnstableTransition = true,
                    meanCoreVisibility = 0f,
                    reasonFr = "Silhouette hors cadre"
                )
            )
        }

        if (lastFrameTsMs != Long.MIN_VALUE) {
            val gapMs = timestampMs - lastFrameTsMs
            if (gapMs > RESET_GAP_MS) {
                reset()
            } else if (gapMs <= 0L && lastOutputScreen.size == rawScreen.size) {
                return SanityFilterResult(
                    screenPoints = lastOutputScreen,
                    worldPoints = if (rawWorld.size == lastOutputWorld.size) lastOutputWorld else rawWorld,
                    motionGuard = lastMotionGuard
                )
            }
        }

        ensureCapacity(rawScreen.size, rawWorld.size)

        val dtMs = if (lastFrameTsMs != Long.MIN_VALUE) (timestampMs - lastFrameTsMs).coerceAtLeast(1L) else 0L

        // Compute raw core visibility BEFORE occlusion hold so exiting frame triggers guard immediately
        val meanCoreVis = (
            rawScreen[BiomechanicsEngine.LEFT_SHOULDER].visibility +
                rawScreen[BiomechanicsEngine.RIGHT_SHOULDER].visibility +
                rawScreen[BiomechanicsEngine.LEFT_HIP].visibility +
                rawScreen[BiomechanicsEngine.RIGHT_HIP].visibility
            ) * 0.25f

        val filteredScreen = filterPointList(
            raw = rawScreen,
            lastValid = lastValidScreen,
            lastValidTs = lastValidScreenTs,
            timestampMs = timestampMs,
            dtMs = dtMs,
            isWorld = false
        )
        val filteredWorld = if (rawWorld.size >= 29) {
            filterPointList(
                raw = rawWorld,
                lastValid = lastValidWorld,
                lastValidTs = lastValidWorldTs,
                timestampMs = timestampMs,
                dtMs = dtMs,
                isWorld = true
            )
        } else {
            emptyList()
        }

        // Global horizontal translation & camera-zoom motion guard (vertical exercise motion stays unblocked)
        val lSh = filteredScreen[BiomechanicsEngine.LEFT_SHOULDER]
        val rSh = filteredScreen[BiomechanicsEngine.RIGHT_SHOULDER]
        val lHip = filteredScreen[BiomechanicsEngine.LEFT_HIP]
        val rHip = filteredScreen[BiomechanicsEngine.RIGHT_HIP]

        val midShX = (lSh.x + rSh.x) * 0.5f
        val midShY = (lSh.y + rSh.y) * 0.5f
        val midHipX = (lHip.x + rHip.x) * 0.5f
        val midHipY = (lHip.y + rHip.y) * 0.5f
        val bodyScale = hypot(midShX - midHipX, midShY - midHipY)

        var hipHorizSpeed = 0f
        var bodyScaleRate = 0f

        if (dtMs > 0L && prevMidHipX.isFinite() && prevBodyScale.isFinite()) {
            val dtSec = (dtMs / 1000f).coerceIn(1f / 240f, 0.45f)
            hipHorizSpeed = abs(midHipX - prevMidHipX) / dtSec
            bodyScaleRate = abs(bodyScale - prevBodyScale) / prevBodyScale.coerceAtLeast(0.05f) / dtSec

            if (hipHorizSpeed > HIP_HORIZ_SPEED_THRESHOLD) {
                horizontalWalkStreak++
            } else {
                horizontalWalkStreak = 0
            }

            if (bodyScaleRate > BODY_SCALE_RATE_THRESHOLD) {
                scaleZoomStreak++
            } else {
                scaleZoomStreak = 0
            }
        } else {
            horizontalWalkStreak = 0
            scaleZoomStreak = 0
        }

        prevMidHipX = midHipX
        prevBodyScale = bodyScale
        lastFrameTsMs = timestampMs

        val unstableReason: String? = when {
            meanCoreVis < CORE_VIS_MIN -> "Visibilité faible"
            horizontalWalkStreak >= SUSTAINED_MOTION_FRAMES -> "Déplacement latéral"
            scaleZoomStreak >= SUSTAINED_MOTION_FRAMES -> "Changement de distance"
            else -> null
        }

        if (unstableReason != null) {
            lastUnstableTsMs = timestampMs
            lastUnstableReasonFr = unstableReason
        }

        val isUnstable = lastUnstableTsMs != Long.MIN_VALUE &&
            (timestampMs - lastUnstableTsMs) < STABILIZATION_COOLDOWN_MS

        if (!isUnstable) {
            lastUnstableReasonFr = null
        }

        val guard = MotionGuardState(
            isUnstableTransition = isUnstable,
            meanCoreVisibility = meanCoreVis,
            hipHorizontalSpeed = hipHorizSpeed,
            bodyScaleRate = bodyScaleRate,
            reasonFr = if (isUnstable) lastUnstableReasonFr else null
        )

        lastOutputScreen = filteredScreen
        lastOutputWorld = filteredWorld
        lastMotionGuard = guard

        return SanityFilterResult(
            screenPoints = filteredScreen,
            worldPoints = filteredWorld,
            motionGuard = guard
        )
    }

    private fun filterPointList(
        raw: List<PosePoint>,
        lastValid: Array<PosePoint?>,
        lastValidTs: LongArray,
        timestampMs: Long,
        dtMs: Long,
        isWorld: Boolean
    ): List<PosePoint> {
        val out = ArrayList<PosePoint>(raw.size)
        val maxJump = if (isWorld) MAX_WORLD_JUMP else MAX_SCREEN_JUMP

        for (i in raw.indices) {
            val pt = raw[i]
            val prev = lastValid[i]
            val prevTs = lastValidTs[i]

            if (!pt.x.isFinite() || !pt.y.isFinite() || !pt.z.isFinite()) {
                out.add(prev ?: PosePoint(0.5f, 0.5f, 0f, 0f))
                continue
            }

            // 1. Brief Occlusion Hold (< 180 ms): preserve last valid joint coordinates while decaying visibility
            if (pt.visibility < OCCLUSION_VIS_THRESHOLD) {
                if (prev != null && prevTs != Long.MIN_VALUE) {
                    val ageMs = timestampMs - prevTs
                    if (ageMs in 1L..OCCLUSION_HOLD_MS) {
                        val decayFrac = (ageMs.toFloat() / OCCLUSION_HOLD_MS.toFloat()).coerceIn(0f, 1f)
                        val decayedVis = (prev.visibility * (1f - 0.50f * decayFrac)).coerceIn(0.20f, 1f)
                        out.add(prev.copy(visibility = decayedVis))
                        continue
                    }
                }
                out.add(pt)
                continue
            }

            // 2. Teleportation Spike Rejection: clamp implausible single-frame low-confidence jumps
            if (prev != null && dtMs in 1L until SPIKE_DT_MAX_MS && pt.visibility < SPIKE_VIS_THRESHOLD) {
                val jumpDist = if (isWorld) {
                    val dx = pt.x - prev.x
                    val dy = pt.y - prev.y
                    val dz = pt.z - prev.z
                    sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                } else {
                    hypot(pt.x - prev.x, pt.y - prev.y)
                }

                if (jumpDist > maxJump) {
                    val blended = PosePoint(
                        x = prev.x + (pt.x - prev.x) * SPIKE_BLEND_FACTOR,
                        y = prev.y + (pt.y - prev.y) * SPIKE_BLEND_FACTOR,
                        z = prev.z + (pt.z - prev.z) * SPIKE_BLEND_FACTOR,
                        visibility = minOf(prev.visibility, pt.visibility)
                    )
                    out.add(blended)
                    // Do not pollute lastValid with the rejected spike
                    continue
                }
            }

            lastValid[i] = pt
            lastValidTs[i] = timestampMs
            out.add(pt)
        }
        return out
    }

    private fun ensureCapacity(screenSize: Int, worldSize: Int) {
        if (screenSize > lastValidScreen.size) {
            lastValidScreen = arrayOfNulls(screenSize)
            lastValidScreenTs = LongArray(screenSize) { Long.MIN_VALUE }
        }
        if (worldSize > lastValidWorld.size) {
            lastValidWorld = arrayOfNulls(worldSize)
            lastValidWorldTs = LongArray(worldSize) { Long.MIN_VALUE }
        }
    }
}
