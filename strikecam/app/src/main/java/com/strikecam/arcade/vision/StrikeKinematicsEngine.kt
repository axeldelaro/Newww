package com.strikecam.arcade.vision

import kotlin.math.abs
import kotlin.math.hypot

data class FistKinematics(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val z: Float = 0f,
    val vx: Float = 0f,          // Normalized screen units / sec
    val vy: Float = 0f,          // Normalized screen units / sec
    val forwardThrust: Float = 0f, // Positive when punching forward toward camera (-dz/dt)
    val speed2D: Float = 0f,
    val visible: Boolean = false,
    val trail: List<Pair<Float, Float>> = emptyList()
)

data class CalibrationBaseline(
    val headY: Float = 0.25f,
    val shoulderCenterX: Float = 0.50f,
    val shoulderCenterY: Float = 0.40f,
    val shoulderWidth: Float = 0.26f,
    val sampleCount: Int = 0
) {
    val isCalibrated: Boolean get() = sampleCount >= 5
}

data class StrikePoseSnapshot(
    val timestampMs: Long = 0L,
    val hasPose: Boolean = false,
    val landmarks: List<PosePoint> = emptyList(),
    val imageWidth: Int = 480,
    val imageHeight: Int = 640,
    val leftFist: FistKinematics = FistKinematics(x = 0.34f, y = 0.50f),
    val rightFist: FistKinematics = FistKinematics(x = 0.66f, y = 0.50f),
    val headX: Float = 0.50f,
    val headY: Float = 0.25f,
    val baseline: CalibrationBaseline = CalibrationBaseline(),
    val isDucking: Boolean = false,
    val isSlippingLeft: Boolean = false,
    val isSlippingRight: Boolean = false
)

/**
 * Computes responsive boxing fist trajectories, 2D swipe velocity vectors, 3D forward
 * punch thrust (dz), and physical duck/slip dodge states relative to standing calibration.
 */
class StrikeKinematicsEngine {
    companion object {
        private const val IDX_NOSE = 0
        private const val IDX_LEFT_SHOULDER = 11
        private const val IDX_RIGHT_SHOULDER = 12
        private const val IDX_LEFT_WRIST = 15
        private const val IDX_RIGHT_WRIST = 16
        private const val IDX_LEFT_PINKY = 17
        private const val IDX_RIGHT_PINKY = 18
        private const val IDX_LEFT_INDEX = 19
        private const val IDX_RIGHT_INDEX = 20

        private const val MIN_VISIBILITY = 0.35f
        private const val MAX_TRAIL_POINTS = 8
    }

    private val screenSmoother = PoseOneEuroSmoother(minCutoff = 1.8f, beta = 0.14f, dCutoff = 1.0f)
    private val worldSmoother = PoseOneEuroSmoother(minCutoff = 1.8f, beta = 0.14f, dCutoff = 1.0f)

    private var prevTimestampMs: Long = 0L
    private var prevLeftX = Float.NaN
    private var prevLeftY = Float.NaN
    private var prevLeftZ = Float.NaN
    private var prevRightX = Float.NaN
    private var prevRightY = Float.NaN
    private var prevRightZ = Float.NaN

    private val leftTrail = ArrayDeque<Pair<Float, Float>>(MAX_TRAIL_POINTS + 1)
    private val rightTrail = ArrayDeque<Pair<Float, Float>>(MAX_TRAIL_POINTS + 1)

    // Running standing calibration accumulators (populated during 3-2-1 countdown)
    private var calHeadYSum = 0f
    private var calShoulderXSum = 0f
    private var calShoulderYSum = 0f
    private var calShoulderWidthSum = 0f
    private var calSamples = 0
    private var baseline = CalibrationBaseline()

    fun reset(resetCalibration: Boolean = true) {
        screenSmoother.reset()
        worldSmoother.reset()
        prevTimestampMs = 0L
        prevLeftX = Float.NaN
        prevLeftY = Float.NaN
        prevLeftZ = Float.NaN
        prevRightX = Float.NaN
        prevRightY = Float.NaN
        prevRightZ = Float.NaN
        leftTrail.clear()
        rightTrail.clear()
        if (resetCalibration) {
            calHeadYSum = 0f
            calShoulderXSum = 0f
            calShoulderYSum = 0f
            calShoulderWidthSum = 0f
            calSamples = 0
            baseline = CalibrationBaseline()
        }
    }

    fun getBaseline(): CalibrationBaseline = baseline

    fun processFrame(
        rawScreen: List<PosePoint>,
        rawWorld: List<PosePoint>,
        imageWidth: Int,
        imageHeight: Int,
        timestampMs: Long,
        calibrating: Boolean
    ): StrikePoseSnapshot {
        if (rawScreen.size < 21) {
            return StrikePoseSnapshot(
                timestampMs = timestampMs,
                hasPose = false,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                baseline = baseline
            )
        }

        val screen = screenSmoother.smooth(rawScreen, timestampMs)
        val world = if (rawWorld.size >= 21) worldSmoother.smooth(rawWorld, timestampMs) else emptyList()

        val nose = screen[IDX_NOSE]
        val lShoulder = screen[IDX_LEFT_SHOULDER]
        val rShoulder = screen[IDX_RIGHT_SHOULDER]

        val shouldersValid = lShoulder.visibility >= MIN_VISIBILITY && rShoulder.visibility >= MIN_VISIBILITY
        val headValid = nose.visibility >= MIN_VISIBILITY

        val curShoulderX = if (shouldersValid) 0.5f * (lShoulder.x + rShoulder.x) else baseline.shoulderCenterX
        val curShoulderY = if (shouldersValid) 0.5f * (lShoulder.y + rShoulder.y) else baseline.shoulderCenterY
        val curShoulderW = if (shouldersValid) {
            hypot(lShoulder.x - rShoulder.x, lShoulder.y - rShoulder.y).coerceIn(0.12f, 0.55f)
        } else {
            baseline.shoulderWidth
        }
        val curHeadX = if (headValid) nose.x else curShoulderX
        val curHeadY = if (headValid) nose.y else (curShoulderY - curShoulderW * 0.48f).coerceIn(0.05f, 0.85f)

        // Accumulate standing baseline during 3-2-1 countdown (or auto-seed if player enters mid-game)
        if (shouldersValid && (calibrating || calSamples < 6)) {
            calHeadYSum += curHeadY
            calShoulderXSum += curShoulderX
            calShoulderYSum += curShoulderY
            calShoulderWidthSum += curShoulderW
            calSamples++
            baseline = CalibrationBaseline(
                headY = calHeadYSum / calSamples,
                shoulderCenterX = calShoulderXSum / calSamples,
                shoulderCenterY = calShoulderYSum / calSamples,
                shoulderWidth = (calShoulderWidthSum / calSamples).coerceIn(0.14f, 0.50f),
                sampleCount = calSamples
            )
        } else if (shouldersValid && !calibrating) {
            // Very gentle long-term drift adaptation for shoulder width only (if player takes half a step closer/farther)
            val sw = baseline.shoulderWidth * 0.992f + curShoulderW * 0.008f
            baseline = baseline.copy(shoulderWidth = sw.coerceIn(0.14f, 0.50f))
        }

        val leftFistPt = computeFistPoint(screen, IDX_LEFT_WRIST, IDX_LEFT_INDEX, IDX_LEFT_PINKY)
        val rightFistPt = computeFistPoint(screen, IDX_RIGHT_WRIST, IDX_RIGHT_INDEX, IDX_RIGHT_PINKY)

        val leftWorldZ = if (world.size > IDX_LEFT_WRIST) world[IDX_LEFT_WRIST].z else leftFistPt.z
        val rightWorldZ = if (world.size > IDX_RIGHT_WRIST) world[IDX_RIGHT_WRIST].z else rightFistPt.z

        val dtSec = if (prevTimestampMs > 0L && timestampMs > prevTimestampMs) {
            ((timestampMs - prevTimestampMs) / 1000f).coerceIn(1f / 180f, 0.25f)
        } else {
            1f / 30f
        }

        val leftKin = buildFistKinematics(
            pt = leftFistPt,
            worldZ = leftWorldZ,
            prevX = prevLeftX,
            prevY = prevLeftY,
            prevZ = prevLeftZ,
            dtSec = dtSec,
            trail = leftTrail
        )
        val rightKin = buildFistKinematics(
            pt = rightFistPt,
            worldZ = rightWorldZ,
            prevX = prevRightX,
            prevY = prevRightY,
            prevZ = prevRightZ,
            dtSec = dtSec,
            trail = rightTrail
        )

        if (leftKin.visible) {
            prevLeftX = leftKin.x
            prevLeftY = leftKin.y
            prevLeftZ = leftKin.z
        }
        if (rightKin.visible) {
            prevRightX = rightKin.x
            prevRightY = rightKin.y
            prevRightZ = rightKin.z
        }
        prevTimestampMs = timestampMs

        // Physical dodge state relative to calibrated standing posture
        val sw = baseline.shoulderWidth.coerceAtLeast(0.16f)
        val headDrop = curHeadY - baseline.headY
        val shoulderDrop = curShoulderY - baseline.shoulderCenterY
        val isDucking = headDrop >= maxOf(0.085f, sw * 0.34f) || shoulderDrop >= maxOf(0.08f, sw * 0.32f)

        val lateralShift = (0.65f * curHeadX + 0.35f * curShoulderX) - baseline.shoulderCenterX
        val slipThreshold = maxOf(0.085f, sw * 0.34f)
        val isSlippingLeft = lateralShift <= -slipThreshold
        val isSlippingRight = lateralShift >= slipThreshold

        return StrikePoseSnapshot(
            timestampMs = timestampMs,
            hasPose = true,
            landmarks = screen,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            leftFist = leftKin,
            rightFist = rightKin,
            headX = curHeadX,
            headY = curHeadY,
            baseline = baseline,
            isDucking = isDucking,
            isSlippingLeft = isSlippingLeft,
            isSlippingRight = isSlippingRight
        )
    }

    private fun computeFistPoint(
        pts: List<PosePoint>,
        wristIdx: Int,
        indexIdx: Int,
        pinkyIdx: Int
    ): PosePoint {
        val wrist = pts[wristIdx]
        val idx = pts[indexIdx]
        val pinky = pts[pinkyIdx]

        val wVis = wrist.visibility
        if (wVis < MIN_VISIBILITY) {
            return wrist
        }
        var sumW = 0.42f
        var x = wrist.x * 0.42f
        var y = wrist.y * 0.42f
        var z = wrist.z * 0.42f
        if (idx.visibility >= MIN_VISIBILITY) {
            x += idx.x * 0.34f
            y += idx.y * 0.34f
            z += idx.z * 0.34f
            sumW += 0.34f
        }
        if (pinky.visibility >= MIN_VISIBILITY) {
            x += pinky.x * 0.24f
            y += pinky.y * 0.24f
            z += pinky.z * 0.24f
            sumW += 0.24f
        }
        return PosePoint(
            x = x / sumW,
            y = y / sumW,
            z = z / sumW,
            visibility = wVis
        )
    }

    private fun buildFistKinematics(
        pt: PosePoint,
        worldZ: Float,
        prevX: Float,
        prevY: Float,
        prevZ: Float,
        dtSec: Float,
        trail: ArrayDeque<Pair<Float, Float>>
    ): FistKinematics {
        val visible = pt.visibility >= MIN_VISIBILITY
        if (!visible) {
            return FistKinematics(x = pt.x, y = pt.y, z = worldZ, visible = false, trail = trail.toList())
        }

        val vx = if (prevX.isFinite()) (pt.x - prevX) / dtSec else 0f
        val vy = if (prevY.isFinite()) (pt.y - prevY) / dtSec else 0f
        // Forward toward camera means negative z delta in MediaPipe world coordinates
        val forwardThrust = if (prevZ.isFinite()) (-(worldZ - prevZ) / dtSec).coerceIn(-6f, 6f) else 0f
        val speed2D = hypot(vx, vy)

        trail.addLast(pt.x to pt.y)
        while (trail.size > MAX_TRAIL_POINTS) {
            trail.removeFirst()
        }

        return FistKinematics(
            x = pt.x,
            y = pt.y,
            z = worldZ,
            vx = vx,
            vy = vy,
            forwardThrust = forwardThrust,
            speed2D = speed2D,
            visible = true,
            trail = trail.toList()
        )
    }

    /**
     * Helper to evaluate whether a directional punch velocity matches a Beat-Saber slash direction.
     * Returns dot product in [-1..1] against required unit vector, or 0 if speed too low.
     */
    fun computeSlashAlignment(vx: Float, vy: Float, dirX: Float, dirY: Float): Float {
        val speed = hypot(vx, vy)
        if (speed < 0.08f) return 0f
        val nx = vx / speed
        val ny = vy / speed
        val dLen = hypot(dirX, dirY).coerceAtLeast(1e-5f)
        return (nx * (dirX / dLen) + ny * (dirY / dLen)).coerceIn(-1f, 1f)
    }
}
