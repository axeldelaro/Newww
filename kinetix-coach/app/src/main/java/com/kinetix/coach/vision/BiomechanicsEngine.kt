package com.kinetix.coach.vision

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class FormQuality { GOOD, CAUTION, FAULT }

enum class DetectionStrictness(val labelFr: String, val descFr: String) {
    ADAPTIVE("Adaptatif", "Fluide sur toutes les variantes et angles caméra"),
    STRICT("Strict", "Amplitude compétition et seuils exigeants")
}

enum class PushupVariant(val labelFr: String) {
    STANDARD("Classique"),
    WIDE("Large"),
    DIAMOND("Diamant"),
    KNEE("Genoux"),
    PIKE("Piquée"),
    ARCHER("Archer")
}

data class PosePoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
    val visibility: Float = 1f
)

data class JointAngleBadge(
    val landmarkIndex: Int,
    val x: Float,
    val y: Float,
    val degrees: Int,
    val quality: FormQuality,
    val parentIndex: Int = -1,
    val childIndex: Int = -1,
    val targetRangeText: String = "",
    val targetDegHint: Int? = null
)

data class AngleCorrection(
    val jointIndex: Int,
    val jointLabelFr: String,
    val currentDeg: Int,
    val targetMinDeg: Int,
    val targetMaxDeg: Int,
    val deltaDeg: Int,
    val severity: FormQuality,
    val arrowDx: Float,
    val arrowDy: Float,
    val ghostAnchorIndex: Int = -1,
    val ghostTargetX: Float = Float.NaN,
    val ghostTargetY: Float = Float.NaN,
    val shortBadgeFr: String,
    val instructionFr: String,
    val voiceCueFr: String
)

data class BiomechanicsFrame(
    val exercise: ExerciseType,
    val landmarks: List<PosePoint>,
    val repCount: Int,
    val isometricHoldSec: Float,
    val phase: MovementPhase,
    val romPct: Int,
    val formScore: Int,
    val formQuality: FormQuality,
    val feedbackFr: String,
    val voiceCueFr: String?,
    val angleBadges: List<JointAngleBadge>,
    val avgFormScore: Int,
    val avgRomPct: Int,
    val topFaults: List<String>,
    val poseDetected: Boolean,
    val worldLandmarks: List<PosePoint> = emptyList(),
    val viewpointInfo: ViewpointInfo = ViewpointInfo(),
    val corrections: List<AngleCorrection> = emptyList(),
    val repCompletedNow: Boolean = false,
    val completedRepRomPct: Int = 0,
    val completedRepDurationSec: Float = 0f,
    val isometricDeltaSec: Float = 0f,
    val detectedVariantFr: String? = null,
    val isStabilizing: Boolean = false,
    val vbt: VbtMetrics = VbtMetrics()
)

/**
 * Pure-Kotlin real-time 3D multi-angle biomechanics analyzer for all 16 KINETIX exercises.
 * Uses MediaPipe 33-landmark screen & 3D metric world coordinates with adaptive Casiez One-Euro
 * filtering, viewpoint-invariant 3D joint geometry, multi-signal fused depth tracking,
 * automatic variant recognition, bilateral symmetry checks, degree-exact corrections, and VBT.
 */
class BiomechanicsEngine(
    initialExercise: ExerciseType = ExerciseType.SQUAT,
    private val emaAlpha: Float = 0.42f,
    private val useOneEuroFilter: Boolean = emaAlpha < 0.999f,
    initialStrictness: DetectionStrictness = DetectionStrictness.ADAPTIVE
) {
    companion object {
        // MediaPipe Pose Landmark indices
        const val NOSE = 0
        const val LEFT_EAR = 7
        const val RIGHT_EAR = 8
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_ELBOW = 13
        const val RIGHT_ELBOW = 14
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28

        private const val SYMMETRY_THRESHOLD_DEG = 13f
        // 25 ms allows fast touch-and-go explosive push-ups at 25-30 FPS (33-40 ms/frame) while
        // minRepDurationMs (300 ms live / 180 ms deterministic) still rejects 1-frame spikes.
        private const val MIN_BOTTOM_DWELL_MS = 25L
        private const val POSTURE_ABORT_GRACE_FRAMES = 4
        private const val PUSHUP_WINDOW_MS = 4200L

        /** Classic 2D screen-space joint angle in degrees (0..180). */
        fun angleDegrees(a: PosePoint, b: PosePoint, c: PosePoint): Float {
            val v1x = a.x - b.x
            val v1y = a.y - b.y
            val v2x = c.x - b.x
            val v2y = c.y - b.y
            val rad = atan2(v2y, v2x) - atan2(v1y, v1x)
            var deg = abs(rad * 180.0 / PI).toFloat()
            if (deg > 180f) deg = 360f - deg
            return deg
        }

        /** True 3D metric joint angle via dot product (SO(3) rotation-invariant across all camera angles). */
        fun angleDegrees3D(a: PosePoint, b: PosePoint, c: PosePoint): Float {
            val v1x = a.x - b.x
            val v1y = a.y - b.y
            val v1z = a.z - b.z
            val v2x = c.x - b.x
            val v2y = c.y - b.y
            val v2z = c.z - b.z
            val m1 = sqrt((v1x * v1x + v1y * v1y + v1z * v1z).toDouble()).toFloat()
            val m2 = sqrt((v2x * v2x + v2y * v2y + v2z * v2z).toDouble()).toFloat()
            if (m1 < 1e-5f || m2 < 1e-5f) return 180f
            val dot = v1x * v2x + v1y * v2y + v1z * v2z
            val cosTheta = (dot / (m1 * m2)).coerceIn(-1f, 1f)
            return (acos(cosTheta.toDouble()) * 180.0 / PI).toFloat()
        }

        /** 2D angle of segment (bottom -> top) relative to vertical axis (0° = straight vertical). */
        fun verticalTiltDegrees(top: PosePoint, bottom: PosePoint): Float {
            val dx = abs(top.x - bottom.x)
            val dy = abs(bottom.y - top.y).coerceAtLeast(0.0001f)
            return (atan2(dx.toDouble(), dy.toDouble()) * 180.0 / PI).toFloat()
        }

        /**
         * True 3D inclination against gravity-up axis (0, -1, 0) in MediaPipe world coordinates.
         * Completely invariant to camera yaw azimuth (0°..360°): a 45° forward torso lean reads
         * 45° whether filmed from Front, 3/4, Side Profile, or Back.
         */
        fun verticalTilt3D(top: PosePoint, bottom: PosePoint): Float {
            val dx = top.x - bottom.x
            val dy = top.y - bottom.y
            val dz = top.z - bottom.z
            val len = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            if (len < 1e-5f) return 0f
            // In MediaPipe world space, +Y points down (gravity), so vertical UP is (0, -1, 0)
            val cosUp = (-dy / len).coerceIn(-1f, 1f)
            return (acos(cosUp.toDouble()) * 180.0 / PI).toFloat()
        }

        /** 2D signed body line alignment (180° = straight; <180° = hip sag; >180° = hip pike). */
        fun bodyLineAlignmentDegrees(shoulder: PosePoint, hip: PosePoint, ankle: PosePoint): Float {
            val raw = angleDegrees(shoulder, hip, ankle)
            val dx = ankle.x - shoulder.x
            val dy = ankle.y - shoulder.y
            val denom = (dx * dx + dy * dy).coerceAtLeast(0.0001f)
            val t = ((hip.x - shoulder.x) * dx + (hip.y - shoulder.y) * dy) / denom
            val expectedHipY = shoulder.y + t * dy
            return if (hip.y > expectedHipY) raw else 360f - raw
        }

        /**
         * True 3D hip sag / pike alignment independent of camera yaw angle.
         * Returns ~180° when straight, <180° when hips sag downward (+Y), >180° when hips pike upward (-Y).
         */
        fun bodyLineAlignment3D(shoulder: PosePoint, hip: PosePoint, ankle: PosePoint): Float {
            val raw = angleDegrees3D(shoulder, hip, ankle)
            val abX = ankle.x - shoulder.x
            val abY = ankle.y - shoulder.y
            val abZ = ankle.z - shoulder.z
            val abLenSq = (abX * abX + abY * abY + abZ * abZ).coerceAtLeast(1e-5f)
            val t = (((hip.x - shoulder.x) * abX +
                (hip.y - shoulder.y) * abY +
                (hip.z - shoulder.z) * abZ) / abLenSq).coerceIn(0f, 1f)
            val expectedHipY = shoulder.y + t * abY
            val diffY = hip.y - expectedHipY
            return when {
                abs(diffY) < 0.002f -> 180f
                diffY > 0f -> raw // Hip dropped toward floor (+Y): sag (< 180°)
                else -> 360f - raw // Hip lifted above line (-Y): pike (> 180°)
            }
        }

        /** Unit lateral axis pointing from LEFT_HIP to RIGHT_HIP in 3D world space. */
        fun computePelvisRightAxis(leftHip: PosePoint, rightHip: PosePoint): PosePoint {
            val dx = rightHip.x - leftHip.x
            val dy = rightHip.y - leftHip.y
            val dz = rightHip.z - leftHip.z
            val len = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            return if (len < 1e-5f) {
                PosePoint(1f, 0f, 0f)
            } else {
                PosePoint(dx / len, dy / len, dz / len)
            }
        }

        /**
         * True 3D frontal-plane medial knee collapse (valgus) angle in degrees, projected onto
         * the athlete's anatomical lateral axis (`pelvisRightAxis` pointing Left Hip -> Right Hip).
         * Positive values indicate inward (medial) knee collapse; <= 0° indicates neutral/outward tracking.
         * Works accurately from Front, 3/4, and Back camera viewpoints.
         */
        fun frontalKneeValgus3D(
            hip: PosePoint,
            knee: PosePoint,
            ankle: PosePoint,
            pelvisRightAxis: PosePoint,
            isLeftLeg: Boolean
        ): Float {
            val haX = ankle.x - hip.x
            val haY = ankle.y - hip.y
            val haZ = ankle.z - hip.z
            val haLenSq = (haX * haX + haY * haY + haZ * haZ).coerceAtLeast(1e-5f)
            val haLen = sqrt(haLenSq.toDouble()).toFloat().coerceAtLeast(0.20f)
            val t = (((knee.x - hip.x) * haX +
                (knee.y - hip.y) * haY +
                (knee.z - hip.z) * haZ) / haLenSq).coerceIn(0.15f, 0.85f)

            val expX = hip.x + t * haX
            val expY = hip.y + t * haY
            val expZ = hip.z + t * haZ

            val devX = knee.x - expX
            val devY = knee.y - expY
            val devZ = knee.z - expZ

            // Signed projection along anatomical Left->Right axis
            val lateralProj = devX * pelvisRightAxis.x + devY * pelvisRightAxis.y + devZ * pelvisRightAxis.z
            // Medial direction: +axis for left leg (toward right), -axis for right leg (toward left)
            val medialMeters = if (isLeftLeg) lateralProj else -lateralProj
            val halfLeg = (haLen * 0.5f).coerceAtLeast(0.15f)
            return (atan2(medialMeters.toDouble(), halfLeg.toDouble()) * 180.0 / PI).toFloat()
        }

        fun dist2D(a: PosePoint, b: PosePoint): Float = hypot(a.x - b.x, a.y - b.y)

        fun dist3D(a: PosePoint, b: PosePoint): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            return sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        }

        fun midpoint(a: PosePoint, b: PosePoint): PosePoint = PosePoint(
            x = (a.x + b.x) * 0.5f,
            y = (a.y + b.y) * 0.5f,
            z = (a.z + b.z) * 0.5f,
            visibility = minOf(a.visibility, b.visibility)
        )
    }

    var currentExercise: ExerciseType = initialExercise
        private set

    var strictness: DetectionStrictness = initialStrictness
        private set

    private val sanityFilter = PoseSanityFilter()
    private val viewpointTracker = CameraViewpointTracker()
    private val vbtEngine = VbtEngine()
    private val screenSmoother = PoseOneEuroSmoother()
    private val worldSmoother = PoseOneEuroSmoother()
    private var legacySmoothedScreen: MutableList<PosePoint>? = null
    private var legacySmoothedWorld: MutableList<PosePoint>? = null

    private data class PushupHeightSample(
        val timestampMs: Long,
        val normHeight: Float,
        val wristY: Float,
        val elbowDeg: Float
    )

    private var repCount: Int = 0
    private var phase: MovementPhase = MovementPhase.READY
    private var reachedBottom: Boolean = false
    private var bottomEnteredTsMs: Long = 0L
    private var burpeeStage: Int = 0 // 0=stand, 1=down/plank
    private var burpeeBottomEnteredTsMs: Long = 0L
    private var plankHoldMs: Long = 0L
    private var lastFrameTsMs: Long = 0L
    private var repStartTsMs: Long = 0L
    private var lastMotionGuard: MotionGuardState = MotionGuardState()
    private var postureGateRejectStreak: Int = 0

    // Self-healing multi-signal vertical drop & variant latch trackers
    private val pushupHeightWindow = ArrayDeque<PushupHeightSample>(128)
    private var adaptivePushupTopHeight: Float = 0f
    private var pushupStraightArmStreak: Int = 0
    private var adaptiveDipTopHeight: Float = 0f
    private var latchedPushupVariant: PushupVariant? = null

    // Session metrics accumulators
    private var formScoreSum: Long = 0L
    private var formScoreSamples: Int = 0
    private var repRomPeak: Int = 0
    private var romSum: Long = 0L
    private var romSamples: Int = 0
    private val faultCounts = linkedMapOf<String, Int>()
    private var currentRepFault: String? = null

    val isMidRep: Boolean
        get() = phase == MovementPhase.ECCENTRIC ||
            phase == MovementPhase.BOTTOM ||
            phase == MovementPhase.CONCENTRIC

    val hasActiveSetReps: Boolean
        get() = repCount > 0 || (currentExercise.isIsometric && plankHoldMs >= 2000L)

    val currentMotionGuard: MotionGuardState
        get() = lastMotionGuard

    fun setAthleteHeightCm(heightCm: Int) {
        vbtEngine.setAthleteHeightCm(heightCm)
    }

    fun setStrictness(mode: DetectionStrictness) {
        strictness = mode
    }

    fun setExercise(exercise: ExerciseType) {
        currentExercise = exercise
        resetSession()
    }

    /**
     * Switches to a new exercise in AUTO mode while preserving One-Euro smoother, sanity filter,
     * and viewpoint tracker state so the drawn skeleton never jumps or teleports on an auto-switch.
     */
    fun switchExerciseSmoothly(exercise: ExerciseType) {
        currentExercise = exercise
        resetExerciseCountersOnly()
    }

    /**
     * Resets temporal landmark smoothers, sanity gate, and viewpoint tracker without discarding
     * current set rep counts (used when flipping Front <-> Back camera).
     */
    fun resetTrackingFilters() {
        sanityFilter.reset()
        viewpointTracker.reset()
        screenSmoother.reset()
        worldSmoother.reset()
        legacySmoothedScreen = null
        legacySmoothedWorld = null
        lastFrameTsMs = 0L
        lastMotionGuard = MotionGuardState()
        postureGateRejectStreak = 0
    }

    fun resetSession() {
        resetTrackingFilters()
        resetExerciseCountersOnly()
    }

    private fun resetExerciseCountersOnly() {
        repCount = 0
        phase = MovementPhase.READY
        reachedBottom = false
        bottomEnteredTsMs = 0L
        burpeeStage = 0
        burpeeBottomEnteredTsMs = 0L
        plankHoldMs = 0L
        repStartTsMs = 0L
        postureGateRejectStreak = 0
        pushupHeightWindow.clear()
        adaptivePushupTopHeight = 0f
        pushupStraightArmStreak = 0
        adaptiveDipTopHeight = 0f
        latchedPushupVariant = null
        vbtEngine.resetSet()
        formScoreSum = 0L
        formScoreSamples = 0
        repRomPeak = 0
        romSum = 0L
        romSamples = 0
        faultCounts.clear()
        currentRepFault = null
    }

    private fun abortIncompleteRepCycle() {
        phase = MovementPhase.READY
        reachedBottom = false
        bottomEnteredTsMs = 0L
        repStartTsMs = 0L
        repRomPeak = 0
        currentRepFault = null
    }

    fun emptyFrame(): BiomechanicsFrame = BiomechanicsFrame(
        exercise = currentExercise,
        landmarks = emptyList(),
        repCount = repCount,
        isometricHoldSec = plankHoldMs / 1000f,
        phase = phase,
        romPct = 0,
        formScore = 100,
        formQuality = FormQuality.GOOD,
        feedbackFr = "Placez votre corps entier dans le cadre (analyse 3D multi-angles).",
        voiceCueFr = null,
        angleBadges = emptyList(),
        avgFormScore = computeAvgForm(),
        avgRomPct = computeAvgRom(),
        topFaults = computeTopFaults(),
        poseDetected = false,
        worldLandmarks = emptyList(),
        viewpointInfo = ViewpointInfo(),
        corrections = emptyList(),
        isStabilizing = false,
        vbt = vbtEngine.currentMetrics()
    )

    /** Backward-compatible 2D-only entry point. */
    fun processFrame(rawScreenLandmarks: List<PosePoint>, timestampMs: Long): BiomechanicsFrame =
        processFrame(rawScreenLandmarks, emptyList(), timestampMs)

    /** Full 3D multi-angle entry point accepting both 2D screen and 3D world landmarks. */
    fun processFrame(
        rawScreenLandmarks: List<PosePoint>,
        rawWorldLandmarks: List<PosePoint>,
        timestampMs: Long
    ): BiomechanicsFrame {
        if (rawScreenLandmarks.size < 29) {
            lastFrameTsMs = timestampMs
            return emptyFrame()
        }

        val isDeterministicPassThrough = emaAlpha >= 0.999f && !useOneEuroFilter
        val preScreen: List<PosePoint>
        val preWorld: List<PosePoint>
        val motionGuard: MotionGuardState

        if (useOneEuroFilter) {
            val sanity = sanityFilter.filter(rawScreenLandmarks, rawWorldLandmarks, timestampMs)
            preScreen = sanity.screenPoints
            preWorld = sanity.worldPoints
            motionGuard = sanity.motionGuard
        } else {
            preScreen = rawScreenLandmarks
            preWorld = rawWorldLandmarks
            motionGuard = MotionGuardState()
        }
        lastMotionGuard = motionGuard

        val screenPts = smoothLandmarks(preScreen, timestampMs, isWorld = false)
        val hasWorld = preWorld.size >= 29
        val worldPts = if (hasWorld) smoothLandmarks(preWorld, timestampMs, isWorld = true) else emptyList()

        val dtMs = if (lastFrameTsMs > 0L) (timestampMs - lastFrameTsMs).coerceIn(0L, 250L) else 0L
        lastFrameTsMs = timestampMs

        val viewpointInfo = if (isDeterministicPassThrough) {
            CameraViewpointDetector.detect(screenPts, worldPts)
        } else {
            viewpointTracker.update(screenPts, worldPts)
        }

        // Freeze rep counting & posture fault spam while athlete walks/repositions camera
        if (motionGuard.isUnstableTransition) {
            abortIncompleteRepCycle()
            burpeeStage = 0
            burpeeBottomEnteredTsMs = 0L
            postureGateRejectStreak = 0
            return BiomechanicsFrame(
                exercise = currentExercise,
                landmarks = screenPts,
                repCount = repCount,
                isometricHoldSec = plankHoldMs / 1000f,
                phase = MovementPhase.READY,
                romPct = 0,
                formScore = 100,
                formQuality = FormQuality.GOOD,
                feedbackFr = "Stabilisation du cadrage…",
                voiceCueFr = null,
                angleBadges = emptyList(),
                avgFormScore = computeAvgForm(),
                avgRomPct = computeAvgRom(),
                topFaults = computeTopFaults(),
                poseDetected = true,
                worldLandmarks = worldPts,
                viewpointInfo = viewpointInfo,
                corrections = emptyList(),
                isStabilizing = true,
                vbt = vbtEngine.currentMetrics()
            )
        }

        val use3D = viewpointInfo.is3DActive && worldPts.size >= 29
        val geomPts = if (use3D) worldPts else screenPts

        val useLeft = viewpointInfo.dominantSideLeft
        val bothVisible = viewpointInfo.bothSidesVisible

        // Dominant side indices (for drawing screen-space badges/ghosts on the clearest visible side)
        val shIdx = if (useLeft) LEFT_SHOULDER else RIGHT_SHOULDER
        val elIdx = if (useLeft) LEFT_ELBOW else RIGHT_ELBOW
        val wrIdx = if (useLeft) LEFT_WRIST else RIGHT_WRIST
        val hpIdx = if (useLeft) LEFT_HIP else RIGHT_HIP
        val knIdx = if (useLeft) LEFT_KNEE else RIGHT_KNEE
        val anIdx = if (useLeft) LEFT_ANKLE else RIGHT_ANKLE

        // Screen landmarks for overlay badges & ghost coordinates
        val shS = screenPts[shIdx]
        val elS = screenPts[elIdx]
        val wrS = screenPts[wrIdx]
        val hpS = screenPts[hpIdx]
        val knS = screenPts[knIdx]
        val anS = screenPts[anIdx]

        // Joint angle helper dispatching to true 3D or 2D fallback
        fun jointAngle(a: Int, b: Int, c: Int): Float =
            if (use3D) angleDegrees3D(geomPts[a], geomPts[b], geomPts[c])
            else angleDegrees(geomPts[a], geomPts[b], geomPts[c])

        fun bodyAlign(shI: Int, hpI: Int, anI: Int): Float =
            if (use3D) bodyLineAlignment3D(geomPts[shI], geomPts[hpI], geomPts[anI])
            else bodyLineAlignmentDegrees(geomPts[shI], geomPts[hpI], geomPts[anI])

        // Independent left & right joint angles (3D when available)
        val leftKnee = jointAngle(LEFT_HIP, LEFT_KNEE, LEFT_ANKLE)
        val rightKnee = jointAngle(RIGHT_HIP, RIGHT_KNEE, RIGHT_ANKLE)
        val leftHip = jointAngle(LEFT_SHOULDER, LEFT_HIP, LEFT_KNEE)
        val rightHip = jointAngle(RIGHT_SHOULDER, RIGHT_HIP, RIGHT_KNEE)
        val leftElbow = jointAngle(LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST)
        val rightElbow = jointAngle(RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST)
        val leftShoulder = jointAngle(LEFT_HIP, LEFT_SHOULDER, LEFT_ELBOW)
        val rightShoulder = jointAngle(RIGHT_HIP, RIGHT_SHOULDER, RIGHT_ELBOW)
        val leftArmFull = jointAngle(LEFT_HIP, LEFT_SHOULDER, LEFT_WRIST)
        val rightArmFull = jointAngle(RIGHT_HIP, RIGHT_SHOULDER, RIGHT_WRIST)
        val leftAlign = bodyAlign(LEFT_SHOULDER, LEFT_HIP, LEFT_ANKLE)
        val rightAlign = bodyAlign(RIGHT_SHOULDER, RIGHT_HIP, RIGHT_ANKLE)

        // Visibility weights per limb
        val lLegW = minOf(screenPts[LEFT_HIP].visibility, screenPts[LEFT_KNEE].visibility, screenPts[LEFT_ANKLE].visibility)
        val rLegW = minOf(screenPts[RIGHT_HIP].visibility, screenPts[RIGHT_KNEE].visibility, screenPts[RIGHT_ANKLE].visibility)
        val lArmW = minOf(screenPts[LEFT_SHOULDER].visibility, screenPts[LEFT_ELBOW].visibility, screenPts[LEFT_WRIST].visibility)
        val rArmW = minOf(screenPts[RIGHT_SHOULDER].visibility, screenPts[RIGHT_ELBOW].visibility, screenPts[RIGHT_WRIST].visibility)
        val lTorsoW = minOf(screenPts[LEFT_SHOULDER].visibility, screenPts[LEFT_HIP].visibility)
        val rTorsoW = minOf(screenPts[RIGHT_SHOULDER].visibility, screenPts[RIGHT_HIP].visibility)

        val kneeAngle = fuseSide(leftKnee, rightKnee, lLegW, rLegW, useLeft, bothVisible)
        val hipAngle = fuseSide(leftHip, rightHip, lTorsoW, rTorsoW, useLeft, bothVisible)
        val elbowAngle = fuseSide(leftElbow, rightElbow, lArmW, rArmW, useLeft, bothVisible)
        val shoulderAngle = fuseSide(leftShoulder, rightShoulder, lArmW, rArmW, useLeft, bothVisible)
        val armFullAngle = fuseSide(leftArmFull, rightArmFull, lArmW, rArmW, useLeft, bothVisible)
        val bodyLineAlign = fuseSide(leftAlign, rightAlign, minOf(lTorsoW, lLegW), minOf(rTorsoW, rLegW), useLeft, bothVisible)

        val torsoTilt = if (use3D) {
            if (bothVisible) {
                verticalTilt3D(
                    midpoint(worldPts[LEFT_SHOULDER], worldPts[RIGHT_SHOULDER]),
                    midpoint(worldPts[LEFT_HIP], worldPts[RIGHT_HIP])
                )
            } else {
                verticalTilt3D(worldPts[shIdx], worldPts[hpIdx])
            }
        } else {
            verticalTiltDegrees(shS, hpS)
        }

        val fullBodyTilt = if (use3D) {
            if (bothVisible) {
                verticalTilt3D(
                    midpoint(worldPts[LEFT_SHOULDER], worldPts[RIGHT_SHOULDER]),
                    midpoint(worldPts[LEFT_ANKLE], worldPts[RIGHT_ANKLE])
                )
            } else {
                verticalTilt3D(worldPts[shIdx], worldPts[anIdx])
            }
        } else {
            verticalTiltDegrees(shS, anS)
        }

        // Screen-space foreshortened horizontal floor posture detector:
        // Essential when phone is propped on floor in front of user (tilted 15-45° upward) or in 2D front view!
        val midShSAll = midpoint(screenPts[LEFT_SHOULDER], screenPts[RIGHT_SHOULDER])
        val midHpSAll = midpoint(screenPts[LEFT_HIP], screenPts[RIGHT_HIP])
        val bothArmsVisGlobal = lArmW >= 0.40f && rArmW >= 0.40f
        val activeWrSY = if (bothArmsVisGlobal) {
            (screenPts[LEFT_WRIST].y + screenPts[RIGHT_WRIST].y) * 0.5f
        } else if (lArmW >= rArmW) {
            screenPts[LEFT_WRIST].y
        } else {
            screenPts[RIGHT_WRIST].y
        }
        val activeShSY = if (bothArmsVisGlobal) midShSAll.y else shS.y
        val shWidth2DGlobal = dist2D(screenPts[LEFT_SHOULDER], screenPts[RIGHT_SHOULDER]).coerceAtLeast(0.05f)
        val torsoVert2DGlobal = abs(midHpSAll.y - midShSAll.y)
        val frontFloorHorizontal = (torsoVert2DGlobal / shWidth2DGlobal) < 0.90f &&
            midShSAll.y >= 0.22f &&
            activeWrSY >= activeShSY - 0.12f

        val pelvisAxis = if (use3D) computePelvisRightAxis(worldPts[LEFT_HIP], worldPts[RIGHT_HIP]) else PosePoint(1f, 0f, 0f)

        var score = 100
        var romPct = 0
        var feedback = "Posture maîtrisée — continuez."
        var faultMsg: String? = null
        var voiceCue: String? = null
        var repCompletedNow = false
        var completedRepRom = 0
        var completedRepDurSec = 0f
        var isometricDeltaSec = 0f
        var detectedVariantFr: String? = null

        val isStrict = strictness == DetectionStrictness.STRICT

        val badges = mutableListOf<JointAngleBadge>()
        val corrections = mutableListOf<AngleCorrection>()

        fun addSymmetryCorrectionIfNeeded(
            leftDeg: Float,
            rightDeg: Float,
            leftW: Float,
            rightW: Float,
            active: Boolean,
            jointLabel: String,
            leftJointIdx: Int,
            rightJointIdx: Int
        ) {
            if (!bothVisible || !active) return
            if (leftW < 0.55f || rightW < 0.55f) return
            val diff = abs(leftDeg - rightDeg)
            if (diff >= SYMMETRY_THRESHOLD_DEG) {
                val delta = diff.roundToInt()
                val offIdx = if (leftDeg > rightDeg) leftJointIdx else rightJointIdx
                score -= 10
                val msg = "Équilibrez gauche (${leftDeg.roundToInt()}°) et droite (${rightDeg.roundToInt()}°)"
                corrections.add(
                    AngleCorrection(
                        jointIndex = offIdx,
                        jointLabelFr = "Symétrie $jointLabel",
                        currentDeg = delta,
                        targetMinDeg = 0,
                        targetMaxDeg = 10,
                        deltaDeg = delta,
                        severity = FormQuality.CAUTION,
                        arrowDx = 0f,
                        arrowDy = 1f,
                        shortBadgeFr = "SYMÉTRIE ±$delta°",
                        instructionFr = msg,
                        voiceCueFr = "Équilibrez gauche et droite"
                    )
                )
            }
        }

        // Physical posture sanity gate: prevent out-of-position phantom reps & fault spam
        // while allowing ALL real-world camera placements (front-floor tilted phone, 3/4, side profile).
        val postureGateFeedback: String? = when (currentExercise) {
            ExerciseType.PUSHUP -> {
                val pushupBodyOk = fullBodyTilt >= 24f || torsoTilt >= 26f || frontFloorHorizontal
                // Allow chest to descend slightly below wrist perspective line on low floor cameras (-0.12f)
                val wristsNotOverhead = activeWrSY >= activeShSY - 0.12f
                if (!pushupBodyOk || !wristsNotOverhead) {
                    "Mettez-vous en position pompes au sol."
                } else {
                    null
                }
            }
            ExerciseType.SQUAT,
            ExerciseType.LUNGE,
            ExerciseType.BULGARIAN_SQUAT,
            ExerciseType.DEADLIFT,
            ExerciseType.OVERHEAD_PRESS,
            ExerciseType.BICEP_CURL,
            ExerciseType.LATERAL_RAISE,
            ExerciseType.JUMPING_JACK -> {
                if (fullBodyTilt >= 68f && torsoTilt >= 65f) {
                    "Redressez-vous debout pour cet exercice."
                } else {
                    null
                }
            }
            ExerciseType.GLUTE_BRIDGE,
            ExerciseType.LEG_RAISE -> {
                val frontFloorSupine = (torsoVert2DGlobal / shWidth2DGlobal) < 0.85f && midShSAll.y >= 0.48f
                if (torsoTilt < 32f && !frontFloorSupine) {
                    "Allongez-vous sur le dos pour cet exercice."
                } else {
                    null
                }
            }
            else -> null
        }

        if (postureGateFeedback != null) {
            postureGateRejectStreak++
            // If mid-rep, tolerate brief 1-3 frame MediaPipe jitter before aborting an active rep!
            val shouldRejectNow = !isMidRep || postureGateRejectStreak >= POSTURE_ABORT_GRACE_FRAMES
            if (shouldRejectNow) {
                abortIncompleteRepCycle()
                return BiomechanicsFrame(
                    exercise = currentExercise,
                    landmarks = screenPts,
                    repCount = repCount,
                    isometricHoldSec = plankHoldMs / 1000f,
                    phase = MovementPhase.READY,
                    romPct = 0,
                    formScore = 100,
                    formQuality = FormQuality.GOOD,
                    feedbackFr = postureGateFeedback,
                    voiceCueFr = null,
                    angleBadges = emptyList(),
                    avgFormScore = computeAvgForm(),
                    avgRomPct = computeAvgRom(),
                    topFaults = computeTopFaults(),
                    poseDetected = true,
                    worldLandmarks = worldPts,
                    viewpointInfo = viewpointInfo,
                    corrections = emptyList(),
                    isStabilizing = false,
                    vbt = vbtEngine.currentMetrics()
                )
            }
        } else {
            postureGateRejectStreak = 0
        }

        when (currentExercise) {
            ExerciseType.SQUAT -> {
                romPct = mapRange(kneeAngle, 168f, if (isStrict) 82f else 90f)
                var kneeTargetHint: Int? = null
                var hipTargetHint: Int? = null

                // 1. Torso forward lean check (3D yaw-invariant)
                if (torsoTilt > 44f) {
                    val delta = (torsoTilt - 44f).roundToInt().coerceAtLeast(1)
                    score -= 22
                    faultMsg = "Redressez la poitrine (buste trop penché)"
                    hipTargetHint = 40
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 35f)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = shIdx,
                            jointLabelFr = "Buste",
                            currentDeg = torsoTilt.roundToInt(),
                            targetMinDeg = 0,
                            targetMaxDeg = 44,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = adx,
                            arrowDy = ady,
                            ghostAnchorIndex = hpIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "BUSTE +$delta°",
                            instructionFr = "Redressez le buste de $delta° (${torsoTilt.roundToInt()}° → ≤44°)",
                            voiceCueFr = "Redressez le buste de $delta degrés"
                        )
                    )
                }

                // 2. Knee valgus check (3D medial collapse projected onto pelvis lateral axis + 2D fallback)
                var valgusDetected = false
                if (use3D && bothVisible && kneeAngle < 138f && lLegW >= 0.45f && rLegW >= 0.45f) {
                    val lValgus = frontalKneeValgus3D(
                        worldPts[LEFT_HIP], worldPts[LEFT_KNEE], worldPts[LEFT_ANKLE], pelvisAxis, isLeftLeg = true
                    )
                    val rValgus = frontalKneeValgus3D(
                        worldPts[RIGHT_HIP], worldPts[RIGHT_KNEE], worldPts[RIGHT_ANKLE], pelvisAxis, isLeftLeg = false
                    )
                    val maxValgus = maxOf(lValgus, rValgus)
                    if (maxValgus >= 10f) {
                        valgusDetected = true
                        val leftWorse = lValgus >= rValgus
                        val kIdx = if (leftWorse) LEFT_KNEE else RIGHT_KNEE
                        val aIdx = if (leftWorse) LEFT_ANKLE else RIGHT_ANKLE
                        val hIdx = if (leftWorse) LEFT_HIP else RIGHT_HIP
                        val kScr = screenPts[kIdx]
                        val aScr = screenPts[aIdx]
                        val midX = (screenPts[LEFT_HIP].x + screenPts[RIGHT_HIP].x) * 0.5f
                        val outDx = outwardScreenDirection(kScr.x, aScr.x, midX)
                        val delta = maxValgus.roundToInt()
                        score -= 38
                        faultMsg = "Poussez les genoux vers l'extérieur (valgus)"
                        corrections.add(
                            AngleCorrection(
                                jointIndex = kIdx,
                                jointLabelFr = "Valgus genou",
                                currentDeg = delta,
                                targetMinDeg = 0,
                                targetMaxDeg = 8,
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = outDx,
                                arrowDy = 0f,
                                ghostAnchorIndex = hIdx,
                                ghostTargetX = aScr.x,
                                ghostTargetY = kScr.y,
                                shortBadgeFr = "VALGUS $delta°",
                                instructionFr = "Poussez le genou vers l'extérieur ($delta° de valgus)",
                                voiceCueFr = "Poussez les genoux vers l'extérieur"
                            )
                        )
                    }
                } else if (!use3D && kneeAngle < 135f) {
                    val kneeSpan = abs(screenPts[LEFT_KNEE].x - screenPts[RIGHT_KNEE].x)
                    val ankleSpan = abs(screenPts[LEFT_ANKLE].x - screenPts[RIGHT_ANKLE].x)
                    if (ankleSpan > 0.08f && kneeSpan < ankleSpan * 0.72f) {
                        valgusDetected = true
                        val rDev = abs(screenPts[RIGHT_ANKLE].x - screenPts[RIGHT_KNEE].x)
                        val lDev = abs(screenPts[LEFT_ANKLE].x - screenPts[LEFT_KNEE].x)
                        val kIdx = if (rDev >= lDev) RIGHT_KNEE else LEFT_KNEE
                        val aIdx = if (rDev >= lDev) RIGHT_ANKLE else LEFT_ANKLE
                        val hIdx = if (rDev >= lDev) RIGHT_HIP else LEFT_HIP
                        val kScr = screenPts[kIdx]
                        val aScr = screenPts[aIdx]
                        val midX = (screenPts[LEFT_ANKLE].x + screenPts[RIGHT_ANKLE].x) * 0.5f
                        val outDx = outwardScreenDirection(kScr.x, aScr.x, midX)
                        val approxDeg = ((1f - kneeSpan / ankleSpan) * 25f).roundToInt().coerceIn(8, 25)
                        score -= 38
                        faultMsg = "Poussez les genoux vers l'extérieur (valgus)"
                        corrections.add(
                            AngleCorrection(
                                jointIndex = kIdx,
                                jointLabelFr = "Valgus genou",
                                currentDeg = approxDeg,
                                targetMinDeg = 0,
                                targetMaxDeg = 8,
                                deltaDeg = approxDeg,
                                severity = FormQuality.FAULT,
                                arrowDx = outDx,
                                arrowDy = 0f,
                                ghostAnchorIndex = hIdx,
                                ghostTargetX = aScr.x,
                                ghostTargetY = kScr.y,
                                shortBadgeFr = "VALGUS $approxDeg°",
                                instructionFr = "Poussez les genoux dans l'axe des orteils (valgus)",
                                voiceCueFr = "Poussez les genoux vers l'extérieur"
                            )
                        )
                    }
                }

                // 3. Multi-signal cycle update: knee angle + hip-to-knee vertical parallel drop in ADAPTIVE
                val midHipY = if (bothVisible) (geomPts[LEFT_HIP].y + geomPts[RIGHT_HIP].y) * 0.5f else geomPts[hpIdx].y
                val midKneeY = if (bothVisible) (geomPts[LEFT_KNEE].y + geomPts[RIGHT_KNEE].y) * 0.5f else geomPts[knIdx].y
                val thighLen = if (use3D) {
                    dist3D(geomPts[hpIdx], geomPts[knIdx]).coerceAtLeast(0.24f)
                } else {
                    dist2D(hpS, knS).coerceAtLeast(0.08f)
                }
                val hipNearParallel = kneeAngle < 126f && (midKneeY - midHipY) <= thighLen * 0.22f
                val effectiveSquatMetric = if (!isStrict && hipNearParallel) minOf(kneeAngle, 104f) else kneeAngle
                if (!isStrict && hipNearParallel && romPct < 88) {
                    romPct = maxOf(romPct, 88)
                }

                val cycle = updateDownUpCycle(
                    metric = effectiveSquatMetric,
                    startThreshold = if (isStrict) 152f else 148f,
                    bottomThreshold = if (isStrict) 98f else 108f,
                    finishThreshold = if (isStrict) 156f else 150f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                // 4. Precision depth angle guidance when descending / paused above parallel
                if (!reachedBottom && kneeAngle in 98.5f..132f && !valgusDetected) {
                    val delta = (kneeAngle - 95f).roundToInt().coerceAtLeast(1)
                    score -= 4
                    kneeTargetHint = 95
                    val (gx, gy) = squatDepthGhost(knS, hpS)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = knIdx,
                            jointLabelFr = "Genou",
                            currentDeg = kneeAngle.roundToInt(),
                            targetMinDeg = 70,
                            targetMaxDeg = 95,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = 1f,
                            ghostAnchorIndex = knIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "GENOU -$delta°",
                            instructionFr = "Descendez encore $delta° (${kneeAngle.roundToInt()}° → ≤95°)",
                            voiceCueFr = "Descendez encore $delta degrés"
                        )
                    )
                }

                // 5. Bilateral symmetry check
                addSymmetryCorrectionIfNeeded(
                    leftKnee, rightKnee, lLegW, rLegW,
                    active = kneeAngle < 140f,
                    jointLabel = "genoux",
                    leftJointIdx = LEFT_KNEE,
                    rightJointIdx = RIGHT_KNEE
                )

                if (repCompletedNow) {
                    voiceCue = if (completedRepRom < 75) "Descendez un peu plus bas" else "$repCount"
                }

                val kneeBadgeQuality = when {
                    valgusDetected -> FormQuality.FAULT
                    kneeTargetHint != null -> FormQuality.CAUTION
                    else -> scoreToQuality(score)
                }
                badges.add(
                    JointAngleBadge(
                        landmarkIndex = knIdx,
                        x = knS.x,
                        y = knS.y,
                        degrees = kneeAngle.roundToInt(),
                        quality = kneeBadgeQuality,
                        parentIndex = hpIdx,
                        childIndex = anIdx,
                        targetRangeText = if (isStrict) "≤95°" else "≤108°",
                        targetDegHint = kneeTargetHint
                    )
                )
                badges.add(
                    JointAngleBadge(
                        landmarkIndex = hpIdx,
                        x = hpS.x,
                        y = hpS.y,
                        degrees = hipAngle.roundToInt(),
                        quality = if (torsoTilt > 44f) FormQuality.FAULT else FormQuality.GOOD,
                        parentIndex = shIdx,
                        childIndex = knIdx,
                        targetRangeText = "≤44° buste",
                        targetDegHint = hipTargetHint
                    )
                )
                feedback = faultMsg ?: when (phase) {
                    MovementPhase.BOTTOM -> if (kneeAngle <= 96f) {
                        "Profondeur complète (${kneeAngle.roundToInt()}°) — poussez !"
                    } else {
                        "Parallèle validée (${kneeAngle.roundToInt()}°) — poussez !"
                    }
                    MovementPhase.ECCENTRIC -> corrections.firstOrNull()?.instructionFr ?: "Contrôlez la descente, dos neutre."
                    MovementPhase.CONCENTRIC -> "Poussez fort dans tout le pied."
                    else -> "Prêt — descendez genoux dans l'axe."
                }
            }

            ExerciseType.PUSHUP -> {
                // 1. Cross-checked 3D + 2D elbow angles (immune to MediaPipe prone 3D flattening in side profile)
                val bothArmsVis = lArmW >= 0.40f && rArmW >= 0.40f
                val isProfileView = viewpointInfo.viewpoint == CameraViewpoint.PROFILE_LEFT ||
                    viewpointInfo.viewpoint == CameraViewpoint.PROFILE_RIGHT

                val leftElbow2D = angleDegrees(screenPts[LEFT_SHOULDER], screenPts[LEFT_ELBOW], screenPts[LEFT_WRIST])
                val rightElbow2D = angleDegrees(screenPts[RIGHT_SHOULDER], screenPts[RIGHT_ELBOW], screenPts[RIGHT_WRIST])

                val effLeftElbow = if (use3D && isProfileView && useLeft && !isStrict) {
                    minOf(leftElbow, leftElbow2D * 1.04f)
                } else {
                    leftElbow
                }
                val effRightElbow = if (use3D && isProfileView && !useLeft && !isStrict) {
                    minOf(rightElbow, rightElbow2D * 1.04f)
                } else {
                    rightElbow
                }
                val effElbowAngle = fuseSide(effLeftElbow, effRightElbow, lArmW, rArmW, useLeft, bothVisible)
                val minElbow = minOf(effLeftElbow, effRightElbow)
                val maxElbow = maxOf(effLeftElbow, effRightElbow)
                val bestElbow = if (bothArmsVis) minElbow else if (lArmW >= rArmW) effLeftElbow else effRightElbow

                // 2. Auto-detect Push-up Variant cleanly across 3D, 2D & tilted front-floor viewpoints
                val isProneBody = torsoTilt >= 26f || fullBodyTilt >= 24f || frontFloorHorizontal
                val leftAnkleAboveKnee = screenPts[LEFT_ANKLE].visibility >= 0.35f &&
                    screenPts[LEFT_KNEE].visibility >= 0.35f &&
                    geomPts[LEFT_ANKLE].y < geomPts[LEFT_KNEE].y - 0.03f
                val rightAnkleAboveKnee = screenPts[RIGHT_ANKLE].visibility >= 0.35f &&
                    screenPts[RIGHT_KNEE].visibility >= 0.35f &&
                    geomPts[RIGHT_ANKLE].y < geomPts[RIGHT_KNEE].y - 0.03f
                val ankleLifted = when {
                    lLegW >= 0.35f && rLegW >= 0.35f -> leftAnkleAboveKnee || rightAnkleAboveKnee
                    useLeft -> leftAnkleAboveKnee
                    else -> rightAnkleAboveKnee
                }
                val legsVisibleEnoughForKneeCheck = maxOf(lLegW, rLegW) >= 0.42f
                val isKnee = isProneBody && legsVisibleEnoughForKneeCheck && (ankleLifted || kneeAngle < 125f)

                val midWristYGeom = if (bothArmsVis) {
                    (geomPts[LEFT_WRIST].y + geomPts[RIGHT_WRIST].y) * 0.5f
                } else {
                    geomPts[wrIdx].y
                }
                val midShYGeom = if (bothArmsVis) {
                    (geomPts[LEFT_SHOULDER].y + geomPts[RIGHT_SHOULDER].y) * 0.5f
                } else {
                    geomPts[shIdx].y
                }
                val handsNearFloor = midWristYGeom >= midShYGeom - (if (use3D) 0.08f else 0.05f)
                val isPike = !isKnee && legsVisibleEnoughForKneeCheck && hipAngle < 125f && bodyLineAlign > 205f && handsNearFloor

                val isArcherNow = bothArmsVis && maxElbow > 145f && minElbow < 115f
                if (isArcherNow) {
                    latchedPushupVariant = PushupVariant.ARCHER
                } else if (phase == MovementPhase.READY && maxElbow > 148f && minElbow > 148f) {
                    latchedPushupVariant = null
                }
                val isArcher = isArcherNow || (latchedPushupVariant == PushupVariant.ARCHER && phase != MovementPhase.READY)

                val canMeasureHandSpan = (use3D && lArmW >= 0.35f && rArmW >= 0.35f) ||
                    (bothVisible && lArmW >= 0.40f && rArmW >= 0.40f)
                val shWidthPushup = if (use3D) {
                    dist3D(worldPts[LEFT_SHOULDER], worldPts[RIGHT_SHOULDER]).coerceAtLeast(0.18f)
                } else {
                    dist2D(screenPts[LEFT_SHOULDER], screenPts[RIGHT_SHOULDER]).coerceAtLeast(0.05f)
                }
                val wristSpanPushup = if (use3D) {
                    dist3D(worldPts[LEFT_WRIST], worldPts[RIGHT_WRIST])
                } else {
                    dist2D(screenPts[LEFT_WRIST], screenPts[RIGHT_WRIST])
                }
                val wristRatio = if (canMeasureHandSpan) wristSpanPushup / shWidthPushup else 1.0f
                val isWide = canMeasureHandSpan && wristRatio > 1.55f
                val isDiamond = canMeasureHandSpan && wristRatio < 0.55f

                val pushupVariant = when {
                    isPike -> PushupVariant.PIKE
                    isArcher -> PushupVariant.ARCHER
                    isKnee -> PushupVariant.KNEE
                    isWide -> PushupVariant.WIDE
                    isDiamond -> PushupVariant.DIAMOND
                    else -> PushupVariant.STANDARD
                }
                detectedVariantFr = pushupVariant.labelFr

                // 3. Occlusion- & angle-safe body alignment check (never spams false hip faults on front-floor push-ups!)
                val effectiveAlign = if (isKnee) {
                    val lKneeAlign = bodyAlign(LEFT_SHOULDER, LEFT_HIP, LEFT_KNEE)
                    val rKneeAlign = bodyAlign(RIGHT_SHOULDER, RIGHT_HIP, RIGHT_KNEE)
                    fuseSide(lKneeAlign, rKneeAlign, minOf(lTorsoW, lLegW), minOf(rTorsoW, rLegW), useLeft, bothVisible)
                } else {
                    bodyLineAlign
                }
                val alignEndIdx = if (isKnee) knIdx else anIdx
                val alignEndS = if (isKnee) knS else anS
                val alignVis = if (isKnee) {
                    maxOf(
                        minOf(lTorsoW, screenPts[LEFT_KNEE].visibility),
                        minOf(rTorsoW, screenPts[RIGHT_KNEE].visibility)
                    )
                } else {
                    maxOf(minOf(lTorsoW, lLegW), minOf(rTorsoW, rLegW))
                }
                val canJudgeBodyAlign = !isPike &&
                    alignVis >= 0.45f &&
                    (viewpointInfo.azimuthDeg >= 24 || (use3D && alignVis >= 0.65f))

                var elbowHint: Int? = null
                var hipHint: Int? = null

                if (canJudgeBodyAlign) {
                    if (effectiveAlign < 155f) {
                        val delta = (172f - effectiveAlign).roundToInt().coerceAtLeast(1)
                        score -= 28
                        faultMsg = "Gainez les abdos : ne creusez pas le bas du dos"
                        hipHint = 175
                        val (gx, gy) = bodyLineGhost(shS, hpS, alignEndS)
                        corrections.add(
                            AngleCorrection(
                                jointIndex = hpIdx,
                                jointLabelFr = "Hanches",
                                currentDeg = effectiveAlign.roundToInt(),
                                targetMinDeg = 160,
                                targetMaxDeg = 200,
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = 0f,
                                arrowDy = -1f,
                                ghostAnchorIndex = shIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "HANCHES +$delta°",
                                instructionFr = "Remontez les hanches de $delta° (${effectiveAlign.roundToInt()}° → 162–198°)",
                                voiceCueFr = "Gainez les abdos, remontez les hanches"
                            )
                        )
                    } else if (effectiveAlign > 205f) {
                        val delta = (effectiveAlign - 188f).roundToInt().coerceAtLeast(1)
                        score -= 20
                        faultMsg = "Abaissez légèrement les hanches"
                        hipHint = 180
                        val (gx, gy) = bodyLineGhost(shS, hpS, alignEndS)
                        corrections.add(
                            AngleCorrection(
                                jointIndex = hpIdx,
                                jointLabelFr = "Hanches",
                                currentDeg = effectiveAlign.roundToInt(),
                                targetMinDeg = 160,
                                targetMaxDeg = 200,
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = 0f,
                                arrowDy = 1f,
                                ghostAnchorIndex = shIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "HANCHES -$delta°",
                                instructionFr = "Abaissez les hanches de $delta° (${effectiveAlign.roundToInt()}° → 162–198°)",
                                voiceCueFr = "Abaissez les hanches pour aligner le corps"
                            )
                        )
                    }
                }

                // Elbow flare check (exempt Wide, Pike, and Archer variants where wider abduction is expected)
                if (!isWide && !isPike && !isArcher && use3D && bothVisible && effElbowAngle < 125f && shoulderAngle > 78f) {
                    val delta = (shoulderAngle - 55f).roundToInt().coerceAtLeast(1)
                    score -= 12
                    corrections.add(
                        AngleCorrection(
                            jointIndex = elIdx,
                            jointLabelFr = "Coudes",
                            currentDeg = shoulderAngle.roundToInt(),
                            targetMinDeg = 35,
                            targetMaxDeg = 60,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = 1f,
                            shortBadgeFr = "COUDES -$delta°",
                            instructionFr = "Rentrez les coudes vers les côtes (${shoulderAngle.roundToInt()}° → ~45°)",
                            voiceCueFr = "Rentrez les coudes à 45 degrés"
                        )
                    )
                }

                // 4. Bulletproof 3-Signal Fused Push-Up Depth (Zero Lockup, All Angles, All Variants):
                //    Signal A: Active Elbow Flexion (3D + 2D profile cross-check)
                //    Signal B: Camera-Tilt-Invariant 3D Arm Chord Compression (self-normalized, zero calibration)
                //    Signal C: Sliding-Window Screen Vertical Chest+Head Drop (works even with occluded elbows on floor)
                val activeElbow = when {
                    isArcher -> bestElbow
                    !isStrict && !bothVisible -> bestElbow
                    !isStrict && bothArmsVis -> 0.35f * effElbowAngle + 0.65f * minElbow
                    else -> effElbowAngle
                }
                val elbowBottomTarget = when {
                    isStrict && isWide -> 106f
                    isStrict -> 95f
                    isWide -> 116f
                    else -> 108f
                }
                val elbowProgress = ((148f - activeElbow) / (148f - elbowBottomTarget).coerceAtLeast(10f))
                    .coerceIn(0f, 1.25f)

                // Signal B: Self-normalized 3D arm compression ratio (shoulder-to-wrist chord / anatomical arm length)
                val bestArmVis = maxOf(lArmW, rArmW)
                val armCompProgress = if (use3D && bestArmVis >= 0.42f) {
                    fun compForSide(sI: Int, eI: Int, wI: Int): Float {
                        val totalLen = (dist3D(worldPts[sI], worldPts[eI]) + dist3D(worldPts[eI], worldPts[wI]))
                            .coerceAtLeast(0.24f)
                        val chord = dist3D(worldPts[sI], worldPts[wI])
                        val ratio = (chord / totalLen).coerceIn(0.40f, 1.0f)
                        val span = if (isStrict) 0.22f else 0.16f
                        return ((0.96f - ratio) / span).coerceIn(0f, 1.25f)
                    }
                    val lComp = compForSide(LEFT_SHOULDER, LEFT_ELBOW, LEFT_WRIST)
                    val rComp = compForSide(RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST)
                    when {
                        isArcher && bothArmsVis -> maxOf(lComp, rComp)
                        bothArmsVis -> 0.40f * ((lComp + rComp) * 0.5f) + 0.60f * maxOf(lComp, rComp)
                        lArmW >= rArmW -> lComp
                        else -> rComp
                    }
                } else {
                    0f
                }

                // Signal C: Anti-lockup sliding-window screen-space Chest+Head vertical drop relative to planted wrists
                val noseS = screenPts[NOSE]
                val chestHeadY = if (noseS.visibility >= 0.35f) {
                    0.65f * activeShSY + 0.35f * noseS.y
                } else {
                    activeShSY
                }
                val refScale2D = maxOf(shWidth2DGlobal, dist2D(midShSAll, midHpSAll), 0.14f)
                val currentScreenHeight = ((activeWrSY - chestHeadY) / refScale2D).coerceIn(-0.35f, 2.20f)
                val inPushupAttitude = isProneBody || isPike

                if (inPushupAttitude) {
                    pushupHeightWindow.addLast(
                        PushupHeightSample(
                            timestampMs = timestampMs,
                            normHeight = currentScreenHeight,
                            wristY = activeWrSY,
                            elbowDeg = activeElbow
                        )
                    )
                    while (pushupHeightWindow.size > 120 ||
                        (pushupHeightWindow.size > 2 && (timestampMs - pushupHeightWindow.first().timestampMs) > PUSHUP_WINDOW_MS)
                    ) {
                        pushupHeightWindow.removeFirst()
                    }

                    // Track sustained straight-arm top frames to self-heal if user just stepped down from standing!
                    if (activeElbow >= 148f && bestArmVis >= 0.45f) {
                        pushupStraightArmStreak++
                    } else {
                        pushupStraightArmStreak = 0
                    }

                    if (pushupStraightArmStreak >= 4) {
                        // Purge any stale higher samples from before settling into push-up plank
                        val maxAllowed = currentScreenHeight + 0.18f
                        while (pushupHeightWindow.size > 4 && pushupHeightWindow.first().normHeight > maxAllowed) {
                            pushupHeightWindow.removeFirst()
                        }
                    }

                    var maxStraightH = Float.NEGATIVE_INFINITY
                    var maxAnyH = Float.NEGATIVE_INFINITY
                    for (s in pushupHeightWindow) {
                        if (s.normHeight > maxAnyH) maxAnyH = s.normHeight
                        if (s.elbowDeg >= 134f && s.normHeight > maxStraightH) maxStraightH = s.normHeight
                    }
                    val windowTop = if (maxStraightH.isFinite() && maxStraightH > 0.10f) maxStraightH else maxAnyH

                    if (windowTop.isFinite() && windowTop > 0.08f) {
                        adaptivePushupTopHeight = if (adaptivePushupTopHeight <= 0.01f) {
                            windowTop
                        } else if (pushupStraightArmStreak >= 3 && currentScreenHeight < adaptivePushupTopHeight) {
                            // Fast convergence (~0.3s) whenever arms are visibly straight at top: ZERO permanent lockup!
                            adaptivePushupTopHeight * 0.72f + currentScreenHeight * 0.28f
                        } else {
                            // Track sliding window peak cleanly
                            maxOf(currentScreenHeight, adaptivePushupTopHeight * 0.96f + windowTop * 0.04f)
                                .coerceAtMost(windowTop)
                        }
                    }
                }

                // Verify wrists stayed planted on floor (prevents waving hands from faking chest drop)
                var minWrY = activeWrSY
                var maxWrY = activeWrSY
                for (s in pushupHeightWindow) {
                    if ((timestampMs - s.timestampMs) <= 1500L) {
                        if (s.wristY < minWrY) minWrY = s.wristY
                        if (s.wristY > maxWrY) maxWrY = s.wristY
                    }
                }
                val handsPlanted = (maxWrY - minWrY) <= 0.14f

                var chestDropProgress = if (inPushupAttitude && handsPlanted && adaptivePushupTopHeight > 0.14f) {
                    val dropDenom = maxOf(adaptivePushupTopHeight * 0.34f, 0.16f)
                    ((adaptivePushupTopHeight - currentScreenHeight) / dropDenom).coerceIn(0f, 1.20f)
                } else {
                    0f
                }

                // Straight-arm lockout guard: if arms are clearly straight and uncompressed, NEVER allow
                // a stale height baseline to keep pushupDepth stuck in BOTTOM!
                if (activeElbow >= 156f && bestArmVis >= 0.55f && armCompProgress < 0.15f) {
                    chestDropProgress = minOf(chestDropProgress, 0.16f)
                }

                val pushupDepth = if (isStrict) {
                    maxOf(elbowProgress, if (bestArmVis >= 0.50f) armCompProgress * 0.85f else chestDropProgress * 0.88f)
                } else {
                    maxOf(elbowProgress, armCompProgress * 0.94f, chestDropProgress * 0.96f)
                }
                romPct = (pushupDepth * 100f).roundToInt().coerceIn(0, 100)

                val peakThresh = if (isStrict) 0.80f else 0.62f
                val finishThresh = if (isStrict) 0.26f else 0.34f
                val cycle = updateProgressCycle(
                    progress = pushupDepth,
                    startThreshold = 0.22f,
                    peakThreshold = peakThresh,
                    finishThreshold = finishThresh,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                val activeElIdx = if (isArcher && bothArmsVis) {
                    if (effLeftElbow <= effRightElbow) LEFT_ELBOW else RIGHT_ELBOW
                } else {
                    elIdx
                }
                val activeShIdx = if (activeElIdx == LEFT_ELBOW) LEFT_SHOULDER else RIGHT_SHOULDER
                val activeWrIdx = if (activeElIdx == LEFT_ELBOW) LEFT_WRIST else RIGHT_WRIST
                val activeElS = screenPts[activeElIdx]

                // Gentle depth coaching without blocking rep counting
                if (!reachedBottom && pushupDepth in 0.28f..(peakThresh - 0.04f) && activeElbow in (elbowBottomTarget + 4f)..134f) {
                    val targetDeg = elbowBottomTarget.roundToInt()
                    val delta = (activeElbow - elbowBottomTarget).roundToInt().coerceAtLeast(1)
                    elbowHint = targetDeg
                    corrections.add(
                        AngleCorrection(
                            jointIndex = activeElIdx,
                            jointLabelFr = "Coude",
                            currentDeg = activeElbow.roundToInt(),
                            targetMinDeg = 75,
                            targetMaxDeg = targetDeg,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = 1f,
                            shortBadgeFr = "COUDE -$delta°",
                            instructionFr = "Descendez encore $delta° (${activeElbow.roundToInt()}° → ≤$targetDeg°)",
                            voiceCueFr = if (isPike) "Descendez la tête vers les mains" else "Descendez la poitrine plus bas"
                        )
                    )
                }

                if (!isArcher) {
                    addSymmetryCorrectionIfNeeded(
                        effLeftElbow, effRightElbow, lArmW, rArmW,
                        active = activeElbow < 138f,
                        jointLabel = "coudes",
                        leftJointIdx = LEFT_ELBOW,
                        rightJointIdx = RIGHT_ELBOW
                    )
                }

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeElIdx, activeElS.x, activeElS.y, activeElbow.roundToInt(),
                        if (elbowHint != null) FormQuality.CAUTION else scoreToQuality(score),
                        parentIndex = activeShIdx,
                        childIndex = activeWrIdx,
                        targetRangeText = "≤${elbowBottomTarget.roundToInt()}°",
                        targetDegHint = elbowHint
                    )
                )
                if (canJudgeBodyAlign || isPike) {
                    badges.add(
                        JointAngleBadge(
                            hpIdx, hpS.x, hpS.y,
                            if (isPike) hipAngle.roundToInt() else effectiveAlign.roundToInt(),
                            if (isPike || effectiveAlign in 155f..205f) FormQuality.GOOD else FormQuality.FAULT,
                            parentIndex = shIdx,
                            childIndex = alignEndIdx,
                            targetRangeText = if (isPike) "V inversé" else "160–200°",
                            targetDegHint = hipHint
                        )
                    )
                }
                feedback = faultMsg ?: when (phase) {
                    MovementPhase.BOTTOM -> if (isPike) {
                        "Pompe piquée validée — repoussez fort !"
                    } else {
                        "Amplitude ${pushupVariant.labelFr} validée — repoussez !"
                    }
                    else -> when (pushupVariant) {
                        PushupVariant.PIKE -> "Piquée détectée — descendez la tête entre les mains."
                        PushupVariant.ARCHER -> "Archer détectée — fléchissez le bras actif en contrôle."
                        PushupVariant.KNEE -> "Pompes genoux (${effectiveAlign.roundToInt()}°) — buste gainé."
                        PushupVariant.WIDE -> "Prise large — poitrine vers le sol, amplitude fluide."
                        PushupVariant.DIAMOND -> "Prise diamant — coudes près des côtes, triceps engagés."
                        PushupVariant.STANDARD -> if (canJudgeBodyAlign) {
                            "Corps aligné (${effectiveAlign.roundToInt()}°), coudes contrôlés."
                        } else {
                            "Pompes détectées — contrôlez la descente et poussez fort."
                        }
                    }
                }
            }

            ExerciseType.LUNGE, ExerciseType.BULGARIAN_SQUAT -> {
                val activeLeft = when {
                    lLegW >= 0.45f && rLegW >= 0.45f -> leftKnee <= rightKnee
                    else -> useLeft
                }
                val minKnee = if (lLegW >= 0.45f && rLegW >= 0.45f) minOf(leftKnee, rightKnee) else kneeAngle
                val activeKnIdx = if (activeLeft) LEFT_KNEE else RIGHT_KNEE
                val activeHpIdx = if (activeLeft) LEFT_HIP else RIGHT_HIP
                val activeAnIdx = if (activeLeft) LEFT_ANKLE else RIGHT_ANKLE
                val activeKnS = screenPts[activeKnIdx]

                romPct = mapRange(minKnee, 162f, if (isStrict) 88f else 96f)
                val maxTilt = if (currentExercise == ExerciseType.BULGARIAN_SQUAT) 34f else 28f
                var kneeHint: Int? = null

                if (torsoTilt > maxTilt) {
                    val delta = (torsoTilt - maxTilt).roundToInt().coerceAtLeast(1)
                    score -= 22
                    faultMsg = "Redressez le buste, regard droit devant"
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, maxTilt - 6f)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = shIdx,
                            jointLabelFr = "Buste",
                            currentDeg = torsoTilt.roundToInt(),
                            targetMinDeg = 0,
                            targetMaxDeg = maxTilt.roundToInt(),
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = adx,
                            arrowDy = ady,
                            ghostAnchorIndex = hpIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "BUSTE +$delta°",
                            instructionFr = "Redressez le buste de $delta° (${torsoTilt.roundToInt()}° → ≤${maxTilt.roundToInt()}°)",
                            voiceCueFr = "Redressez le buste"
                        )
                    )
                }

                // 3D front-knee valgus check
                if (use3D && bothVisible && minKnee < 135f) {
                    val vDeg = frontalKneeValgus3D(
                        worldPts[activeHpIdx], worldPts[activeKnIdx], worldPts[activeAnIdx], pelvisAxis, activeLeft
                    )
                    if (vDeg >= 10f) {
                        val delta = vDeg.roundToInt()
                        score -= 24
                        faultMsg = "Stabilisez le genou avant dans l'axe du pied"
                        val outDx = outwardScreenDirection(
                            activeKnS.x,
                            screenPts[activeAnIdx].x,
                            (screenPts[LEFT_HIP].x + screenPts[RIGHT_HIP].x) * 0.5f
                        )
                        corrections.add(
                            AngleCorrection(
                                jointIndex = activeKnIdx,
                                jointLabelFr = "Genou avant",
                                currentDeg = delta,
                                targetMinDeg = 0,
                                targetMaxDeg = 8,
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = outDx,
                                arrowDy = 0f,
                                ghostAnchorIndex = activeHpIdx,
                                ghostTargetX = screenPts[activeAnIdx].x,
                                ghostTargetY = activeKnS.y,
                                shortBadgeFr = "VALGUS $delta°",
                                instructionFr = "Alignez le genou avant vers l'extérieur ($delta°)",
                                voiceCueFr = "Alignez le genou avant avec le pied"
                            )
                        )
                    }
                }

                val bottomThresh = if (isStrict) 100f else 110f
                val cycle = updateDownUpCycle(
                    metric = minKnee,
                    startThreshold = if (isStrict) 148f else 142f,
                    bottomThreshold = bottomThresh,
                    finishThreshold = if (isStrict) 154f else 144f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (!reachedBottom && minKnee in (bottomThresh + 2f)..132f) {
                    val delta = (minKnee - 95f).roundToInt().coerceAtLeast(1)
                    kneeHint = 95
                    corrections.add(
                        AngleCorrection(
                            jointIndex = activeKnIdx,
                            jointLabelFr = "Genou",
                            currentDeg = minKnee.roundToInt(),
                            targetMinDeg = 75,
                            targetMaxDeg = 95,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = 1f,
                            shortBadgeFr = "GENOU -$delta°",
                            instructionFr = "Descendez encore $delta° (${minKnee.roundToInt()}° → ≤95°)",
                            voiceCueFr = "Descendez encore $delta degrés"
                        )
                    )
                }

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeKnIdx, activeKnS.x, activeKnS.y, minKnee.roundToInt(),
                        if (kneeHint != null) FormQuality.CAUTION else scoreToQuality(score),
                        parentIndex = activeHpIdx,
                        childIndex = activeAnIdx,
                        targetRangeText = if (isStrict) "≤100°" else "≤110°",
                        targetDegHint = kneeHint
                    )
                )
                feedback = faultMsg ?: "Amplitude ${romPct}% — contrôlez la stabilité du genou."
            }

            ExerciseType.DEADLIFT -> {
                romPct = mapRange(hipAngle, 168f, if (isStrict) 88f else 102f)
                var hipHint: Int? = null
                var kneeHint: Int? = null

                if (kneeAngle < 115f && hipAngle > 110f) {
                    val delta = (125f - kneeAngle).roundToInt().coerceAtLeast(1)
                    score -= 18
                    faultMsg = "Initiez par les hanches (évitez de squatter la barre)"
                    kneeHint = 125
                    corrections.add(
                        AngleCorrection(
                            jointIndex = hpIdx,
                            jointLabelFr = "Hanches",
                            currentDeg = kneeAngle.roundToInt(),
                            targetMinDeg = 120,
                            targetMaxDeg = 165,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = 0f,
                            arrowDy = -1f,
                            shortBadgeFr = "CHARNIÈRE +$delta°",
                            instructionFr = "Montez les hanches et dépliez les genoux (+$delta° → ≥120°)",
                            voiceCueFr = "Poussez les hanches en arrière, ne squattez pas"
                        )
                    )
                }
                // Fixed 3D-compatible hyperextension check via signed bodyLineAlign (>192°) instead of impossible 3D hipAngle > 185°
                if (bodyLineAlign > 192f && torsoTilt > 10f && hipAngle > 158f) {
                    val cur = bodyLineAlign.roundToInt()
                    val delta = (cur - 180).coerceAtLeast(5)
                    score -= 22
                    faultMsg = "Stoppez droit — ne cambrez pas en arrière"
                    hipHint = 178
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 4f)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = hpIdx,
                            jointLabelFr = "Lombaires",
                            currentDeg = cur,
                            targetMinDeg = 165,
                            targetMaxDeg = 182,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = adx,
                            arrowDy = ady,
                            ghostAnchorIndex = hpIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "CAMBRURE -$delta°",
                            instructionFr = "Stoppez droit à 180° sans cambrer (-$delta°)",
                            voiceCueFr = "Stoppez droit, ne cambrez pas en arrière"
                        )
                    )
                }

                val cycle = updateDownUpCycle(
                    metric = hipAngle,
                    startThreshold = 150f,
                    bottomThreshold = if (isStrict) 105f else 116f,
                    finishThreshold = if (isStrict) 162f else 154f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                addSymmetryCorrectionIfNeeded(
                    leftHip, rightHip, lTorsoW, rTorsoW,
                    active = hipAngle < 145f,
                    jointLabel = "hanches",
                    leftJointIdx = LEFT_HIP,
                    rightJointIdx = RIGHT_HIP
                )

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, hipAngle.roundToInt(), scoreToQuality(score),
                        parentIndex = shIdx, childIndex = knIdx, targetRangeText = "90–175°", targetDegHint = hipHint
                    )
                )
                badges.add(
                    JointAngleBadge(
                        knIdx, knS.x, knS.y, kneeAngle.roundToInt(),
                        if (kneeHint != null) FormQuality.CAUTION else FormQuality.GOOD,
                        parentIndex = hpIdx, childIndex = anIdx, targetRangeText = "≥120°", targetDegHint = kneeHint
                    )
                )
                feedback = faultMsg ?: when (phase) {
                    MovementPhase.BOTTOM -> "Charnière verrouillée — poussez les hanches !"
                    else -> "Dos neutre, barre proche des tibias."
                }
            }

            ExerciseType.OVERHEAD_PRESS -> {
                val overheadOk = if (use3D) {
                    if (bothVisible) {
                        ((worldPts[LEFT_WRIST].y + worldPts[RIGHT_WRIST].y) * 0.5f) <
                            ((worldPts[LEFT_SHOULDER].y + worldPts[RIGHT_SHOULDER].y) * 0.5f)
                    } else {
                        worldPts[wrIdx].y < worldPts[shIdx].y
                    }
                } else {
                    wrS.y < shS.y
                }
                romPct = if (overheadOk) mapRange(elbowAngle, 85f, if (isStrict) 165f else 155f) else mapRange(elbowAngle, 85f, 165f) / 2
                var elbowHint: Int? = null

                if (torsoTilt > 18f) {
                    val delta = (torsoTilt - 18f).roundToInt().coerceAtLeast(1)
                    score -= 25
                    faultMsg = "Serrez fessiers et abdos — évitez de cambrer"
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 8f)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = shIdx,
                            jointLabelFr = "Buste",
                            currentDeg = torsoTilt.roundToInt(),
                            targetMinDeg = 0,
                            targetMaxDeg = 18,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = adx,
                            arrowDy = ady,
                            ghostAnchorIndex = hpIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "BUSTE -$delta°",
                            instructionFr = "Gainez le tronc : redressez de $delta° (${torsoTilt.roundToInt()}° → ≤18°)",
                            voiceCueFr = "Serrez les abdos, ne cambrez pas"
                        )
                    )
                }

                val topThresh = if (isStrict) 155f else 146f
                val cycle = updateUpDownCycle(
                    metric = elbowAngle,
                    startThreshold = if (isStrict) 100f else 106f,
                    topThreshold = topThresh,
                    finishThreshold = if (isStrict) 95f else 110f,
                    extraTopCondition = overheadOk,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (overheadOk && !reachedBottom && elbowAngle in 118f..(topThresh - 2f)) {
                    val delta = (topThresh + 6f - elbowAngle).roundToInt().coerceAtLeast(1)
                    elbowHint = (topThresh + 6f).roundToInt()
                    val (gx, gy) = straightenLimbGhost(shS, elS, wrS)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = elIdx,
                            jointLabelFr = "Verrouillage",
                            currentDeg = elbowAngle.roundToInt(),
                            targetMinDeg = topThresh.roundToInt(),
                            targetMaxDeg = 180,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = -1f,
                            ghostAnchorIndex = elIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "COUDE +$delta°",
                            instructionFr = "Verrouillez les coudes (+$delta° : ${elbowAngle.roundToInt()}° → ≥${topThresh.roundToInt()}°)",
                            voiceCueFr = "Verrouillez les bras en haut"
                        )
                    )
                }

                addSymmetryCorrectionIfNeeded(
                    leftElbow, rightElbow, lArmW, rArmW,
                    active = elbowAngle > 105f,
                    jointLabel = "bras",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        elIdx, elS.x, elS.y, elbowAngle.roundToInt(),
                        if (elbowHint != null) FormQuality.CAUTION else scoreToQuality(score),
                        parentIndex = shIdx, childIndex = wrIdx, targetRangeText = "≥${topThresh.roundToInt()}°", targetDegHint = elbowHint
                    )
                )
                feedback = faultMsg ?: "Poussée verticale stricte — verrouillez au-dessus."
            }

            ExerciseType.BICEP_CURL -> {
                val bothArmsVis = lArmW >= 0.45f && rArmW >= 0.45f
                val isAlternating = bothVisible && bothArmsVis && abs(leftElbow - rightElbow) > 35f
                val activeLeftCurl = if (isAlternating) leftElbow <= rightElbow else useLeft
                val activeCurlElbow = if (isAlternating) minOf(leftElbow, rightElbow) else elbowAngle
                val activeCurlShAngle = if (isAlternating) (if (activeLeftCurl) leftShoulder else rightShoulder) else shoulderAngle
                val activeElIdx = if (isAlternating) (if (activeLeftCurl) LEFT_ELBOW else RIGHT_ELBOW) else elIdx
                val activeShIdx = if (activeElIdx == LEFT_ELBOW) LEFT_SHOULDER else RIGHT_SHOULDER
                val activeWrIdx = if (activeElIdx == LEFT_ELBOW) LEFT_WRIST else RIGHT_WRIST
                val activeElS = screenPts[activeElIdx]
                val activeShS = screenPts[activeShIdx]
                if (isAlternating) detectedVariantFr = "Alterné"

                romPct = mapRange(activeCurlElbow, 155f, if (isStrict) 48f else 62f)
                val elbowDrift3D = use3D && activeCurlShAngle > 34f && activeCurlElbow < 110f
                val torsoLen2D = dist2D(shS, hpS).coerceAtLeast(0.08f)
                val elbowDrift2D = !use3D && abs(activeElS.x - activeShS.x) > torsoLen2D * 0.42f && activeCurlElbow < 105f
                if (elbowDrift3D || elbowDrift2D) {
                    val delta = (activeCurlShAngle - 25f).roundToInt().coerceAtLeast(8)
                    score -= 24
                    faultMsg = "Gardez le coude fixe près des côtes"
                    val pullDx = if (activeElS.x >= activeShS.x) -1f else 1f
                    corrections.add(
                        AngleCorrection(
                            jointIndex = activeElIdx,
                            jointLabelFr = "Coude",
                            currentDeg = activeCurlShAngle.roundToInt(),
                            targetMinDeg = 0,
                            targetMaxDeg = 28,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = pullDx,
                            arrowDy = 0.25f,
                            ghostAnchorIndex = activeShIdx,
                            ghostTargetX = activeShS.x,
                            ghostTargetY = activeElS.y,
                            shortBadgeFr = "COUDE -$delta°",
                            instructionFr = "Reculez le coude près des côtes (${activeCurlShAngle.roundToInt()}° → ≤28°)",
                            voiceCueFr = "Gardez les coudes collés aux côtes"
                        )
                    )
                }
                if (torsoTilt > 16f) {
                    val delta = (torsoTilt - 16f).roundToInt().coerceAtLeast(1)
                    score -= 20
                    faultMsg = "Ne balancez pas le buste en arrière"
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 6f)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = shIdx,
                            jointLabelFr = "Buste",
                            currentDeg = torsoTilt.roundToInt(),
                            targetMinDeg = 0,
                            targetMaxDeg = 16,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = adx,
                            arrowDy = ady,
                            ghostAnchorIndex = hpIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "BUSTE -$delta°",
                            instructionFr = "Stabilisez le buste (-$delta° : ${torsoTilt.roundToInt()}° → ≤16°)",
                            voiceCueFr = "Ne balancez pas le buste"
                        )
                    )
                }

                val cycle = updateDownUpCycle(
                    metric = activeCurlElbow,
                    startThreshold = if (isStrict) 140f else 136f,
                    bottomThreshold = if (isStrict) 58f else 74f,
                    finishThreshold = if (isStrict) 148f else 136f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (!isAlternating) {
                    addSymmetryCorrectionIfNeeded(
                        leftElbow, rightElbow, lArmW, rArmW,
                        active = activeCurlElbow < 125f,
                        jointLabel = "bras",
                        leftJointIdx = LEFT_ELBOW,
                        rightJointIdx = RIGHT_ELBOW
                    )
                }

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeElIdx, activeElS.x, activeElS.y, activeCurlElbow.roundToInt(), scoreToQuality(score),
                        parentIndex = activeShIdx,
                        childIndex = activeWrIdx,
                        targetRangeText = if (isStrict) "≤58°" else "≤74°"
                    )
                )
                feedback = faultMsg ?: if (isAlternating) {
                    "Curl alterné propre (${activeCurlElbow.roundToInt()}°) — coude fixe."
                } else {
                    "Flexion propre (${activeCurlElbow.roundToInt()}°), contrôlez la descente."
                }
            }

            ExerciseType.TRICEP_DIP -> {
                val bothArmsVis = lArmW >= 0.45f && rArmW >= 0.45f
                val dipElbowBottom = if (isStrict) 95f else 106f
                val dipElbowFinish = if (isStrict) 155f else 146f
                val dipElbowProgress = ((148f - elbowAngle) / (148f - dipElbowBottom).coerceAtLeast(10f)).coerceIn(0f, 1.25f)

                // Adaptive vertical shoulder drop relative to planted wrists (bench & parallel dips)
                val midWrYDip = if (bothArmsVis) (geomPts[LEFT_WRIST].y + geomPts[RIGHT_WRIST].y) * 0.5f else geomPts[wrIdx].y
                val midShYDip = if (bothArmsVis) (geomPts[LEFT_SHOULDER].y + geomPts[RIGHT_SHOULDER].y) * 0.5f else geomPts[shIdx].y
                val dipTorsoLen = if (use3D) {
                    dist3D(geomPts[shIdx], geomPts[hpIdx]).coerceAtLeast(0.28f)
                } else {
                    dist2D(shS, hpS).coerceAtLeast(0.10f)
                }
                val currentDipHeight = ((midWrYDip - midShYDip) / dipTorsoLen).coerceAtLeast(0f)
                if (torsoTilt <= 48f && currentDipHeight > 0.08f) {
                    if (adaptiveDipTopHeight <= 0.01f || currentDipHeight > adaptiveDipTopHeight) {
                        adaptiveDipTopHeight = currentDipHeight
                    } else if (elbowAngle >= 146f || phase == MovementPhase.READY) {
                        adaptiveDipTopHeight = adaptiveDipTopHeight * 0.78f + currentDipHeight * 0.22f
                    }
                }
                var dipDropProgress = if (adaptiveDipTopHeight > 0.16f && torsoTilt <= 48f) {
                    ((adaptiveDipTopHeight - currentDipHeight) / (adaptiveDipTopHeight * 0.32f)).coerceIn(0f, 1.2f)
                } else {
                    0f
                }
                if (elbowAngle >= 156f && maxOf(lArmW, rArmW) >= 0.55f) {
                    dipDropProgress = minOf(dipDropProgress, 0.16f)
                }

                val dipDepth = if (isStrict) dipElbowProgress else maxOf(dipElbowProgress, dipDropProgress * 0.94f)
                romPct = (dipDepth * 100f).roundToInt().coerceIn(0, 100)
                var elbowHint: Int? = null
                if (torsoTilt > 38f) {
                    val delta = (torsoTilt - 38f).roundToInt().coerceAtLeast(1)
                    score -= 15
                    faultMsg = "Gardez le buste contrôlé, épaules abaissées"
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 24f)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = shIdx,
                            jointLabelFr = "Buste",
                            currentDeg = torsoTilt.roundToInt(),
                            targetMinDeg = 0,
                            targetMaxDeg = 38,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = adx,
                            arrowDy = ady,
                            ghostAnchorIndex = hpIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "BUSTE -$delta°",
                            instructionFr = "Redressez le buste de $delta° (${torsoTilt.roundToInt()}° → ≤38°)",
                            voiceCueFr = "Contrôlez l'inclinaison, épaules basses"
                        )
                    )
                }

                // Shoulder shrug check when ears and shoulders visible in 3D
                if (use3D && worldPts[LEFT_EAR].visibility >= 0.45f && worldPts[RIGHT_EAR].visibility >= 0.45f) {
                    val earY = (worldPts[LEFT_EAR].y + worldPts[RIGHT_EAR].y) * 0.5f
                    val shY = (worldPts[LEFT_SHOULDER].y + worldPts[RIGHT_SHOULDER].y) * 0.5f
                    if (shY - earY < 0.08f) {
                        score -= 12
                        corrections.add(
                            AngleCorrection(
                                jointIndex = shIdx,
                                jointLabelFr = "Épaules",
                                currentDeg = ((shY - earY) * 100f).roundToInt(),
                                targetMinDeg = 12,
                                targetMaxDeg = 25,
                                deltaDeg = 10,
                                severity = FormQuality.CAUTION,
                                arrowDx = 0f,
                                arrowDy = 1f,
                                shortBadgeFr = "ÉPAULES BAS",
                                instructionFr = "Abaissez les épaules loin des oreilles",
                                voiceCueFr = "Abaissez les épaules"
                            )
                        )
                    }
                }

                val cycle = if (isStrict) {
                    updateDownUpCycle(
                        metric = elbowAngle,
                        startThreshold = 148f,
                        bottomThreshold = dipElbowBottom,
                        finishThreshold = dipElbowFinish,
                        currentRom = romPct,
                        fault = faultMsg,
                        timestampMs = timestampMs
                    )
                } else {
                    updateProgressCycle(
                        progress = dipDepth,
                        startThreshold = 0.20f,
                        peakThreshold = 0.65f,
                        finishThreshold = 0.24f,
                        currentRom = romPct,
                        fault = faultMsg,
                        timestampMs = timestampMs
                    )
                }
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (!reachedBottom && elbowAngle in (dipElbowBottom + 3f)..128f) {
                    val targetDeg = dipElbowBottom.roundToInt()
                    val delta = (elbowAngle - dipElbowBottom).roundToInt().coerceAtLeast(1)
                    elbowHint = targetDeg
                    corrections.add(
                        AngleCorrection(
                            jointIndex = elIdx,
                            jointLabelFr = "Coude",
                            currentDeg = elbowAngle.roundToInt(),
                            targetMinDeg = 80,
                            targetMaxDeg = targetDeg,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = 1f,
                            shortBadgeFr = "COUDE -$delta°",
                            instructionFr = "Descendez encore $delta° (${elbowAngle.roundToInt()}° → ≤$targetDeg°)",
                            voiceCueFr = "Descendez jusqu'à 90 degrés"
                        )
                    )
                }

                addSymmetryCorrectionIfNeeded(
                    leftElbow, rightElbow, lArmW, rArmW,
                    active = elbowAngle < 140f,
                    jointLabel = "coudes",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        elIdx, elS.x, elS.y, elbowAngle.roundToInt(),
                        if (elbowHint != null) FormQuality.CAUTION else scoreToQuality(score),
                        parentIndex = shIdx,
                        childIndex = wrIdx,
                        targetRangeText = "≤${dipElbowBottom.roundToInt()}°",
                        targetDegHint = elbowHint
                    )
                )
                feedback = faultMsg ?: "Descendez contrôlé puis verrouillez proprement."
            }

            ExerciseType.PLANK -> {
                val shSpanPlank = if (use3D) {
                    dist3D(worldPts[LEFT_SHOULDER], worldPts[RIGHT_SHOULDER]).coerceAtLeast(0.18f)
                } else {
                    dist2D(screenPts[LEFT_SHOULDER], screenPts[RIGHT_SHOULDER]).coerceAtLeast(0.06f)
                }
                val isSidePlank = bothVisible &&
                    abs(geomPts[LEFT_SHOULDER].y - geomPts[RIGHT_SHOULDER].y) > shSpanPlank * 0.50f
                val isForearmPlank = !isSidePlank && elbowAngle in 55f..120f
                detectedVariantFr = when {
                    isSidePlank -> "Latérale"
                    isForearmPlank -> "Avant-bras"
                    else -> "Haute"
                }

                val isHorizontal = if (use3D) {
                    fullBodyTilt >= (if (isStrict) 55f else 42f) || isSidePlank || (!isStrict && frontFloorHorizontal)
                } else {
                    val classicHoriz = abs(shS.y - anS.y) < abs(shS.x - anS.x) * 0.78f
                    val bodyLen2D = dist2D(shS, anS).coerceAtLeast(0.05f)
                    val frontViewPlank = !isStrict &&
                        (frontFloorHorizontal ||
                            (abs(shS.y - anS.y) < maxOf(bodyLen2D * 0.35f, 0.28f) && abs(shS.y - hpS.y) < 0.16f))
                    classicHoriz || frontViewPlank || isSidePlank
                }

                val validMin = if (isStrict) 160f else 155f
                val validMax = if (isStrict) 195f else 202f
                val aligned = isHorizontal && bodyLineAlign in validMin..validMax
                var hipHint: Int? = null
                if (aligned) {
                    plankHoldMs += dtMs
                    isometricDeltaSec = dtMs / 1000f
                    phase = MovementPhase.HOLD
                    romPct = 100
                    val inOptimalGreen = bodyLineAlign in 166f..194f
                    score = if (inOptimalGreen) {
                        (98 - (abs(180f - bodyLineAlign) * 0.7f).roundToInt()).coerceIn(84, 100)
                    } else {
                        (82 - (abs(180f - bodyLineAlign) * 0.4f).roundToInt()).coerceIn(72, 81)
                    }
                    feedback = if (inOptimalGreen) {
                        "Alignement parfait (${bodyLineAlign.roundToInt()}°) — maintenez !"
                    } else {
                        "Planche validée (${bodyLineAlign.roundToInt()}°) — affinez l'alignement."
                    }
                    val sec = (plankHoldMs / 1000L).toInt()
                    if (sec > 0 && sec % 15 == 0 && (plankHoldMs - dtMs) / 1000L < sec) {
                        voiceCue = "$sec secondes, tenez bon !"
                    }
                } else {
                    phase = MovementPhase.PAUSE
                    romPct = 40
                    if (!isHorizontal) {
                        score = 60
                        feedback = "Mettez-vous en position planche horizontale."
                    } else if (bodyLineAlign < validMin) {
                        val delta = (172f - bodyLineAlign).roundToInt().coerceAtLeast(1)
                        score = 52
                        hipHint = 175
                        faultMsg = "Remontez les hanches — bas du dos creusé"
                        feedback = faultMsg
                        val (gx, gy) = bodyLineGhost(shS, hpS, anS)
                        corrections.add(
                            AngleCorrection(
                                jointIndex = hpIdx,
                                jointLabelFr = "Hanches",
                                currentDeg = bodyLineAlign.roundToInt(),
                                targetMinDeg = 166,
                                targetMaxDeg = 194,
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = 0f,
                                arrowDy = -1f,
                                ghostAnchorIndex = shIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "HANCHES +$delta°",
                                instructionFr = "Remontez les hanches de $delta° (${bodyLineAlign.roundToInt()}° → 166–194°)",
                                voiceCueFr = "Remontez les hanches, bas du dos creusé"
                            )
                        )
                    } else {
                        val delta = (bodyLineAlign - 185f).roundToInt().coerceAtLeast(1)
                        score = 58
                        hipHint = 180
                        faultMsg = "Abaissez les hanches — fesses trop hautes"
                        feedback = faultMsg
                        val (gx, gy) = bodyLineGhost(shS, hpS, anS)
                        corrections.add(
                            AngleCorrection(
                                jointIndex = hpIdx,
                                jointLabelFr = "Hanches",
                                currentDeg = bodyLineAlign.roundToInt(),
                                targetMinDeg = 166,
                                targetMaxDeg = 194,
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = 0f,
                                arrowDy = 1f,
                                ghostAnchorIndex = shIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "HANCHES -$delta°",
                                instructionFr = "Abaissez les hanches de $delta° (${bodyLineAlign.roundToInt()}° → 166–194°)",
                                voiceCueFr = "Abaissez les hanches"
                            )
                        )
                    }
                }
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, bodyLineAlign.roundToInt(), scoreToQuality(score),
                        parentIndex = shIdx, childIndex = anIdx, targetRangeText = "166–194°", targetDegHint = hipHint
                    )
                )
                badges.add(
                    JointAngleBadge(
                        shIdx, shS.x, shS.y, shoulderAngle.roundToInt(), FormQuality.GOOD,
                        parentIndex = hpIdx, childIndex = elIdx, targetRangeText = "80–100°"
                    )
                )
            }

            ExerciseType.BURPEE -> {
                val bodyHorizontal = if (use3D) {
                    torsoTilt >= (if (isStrict) 55f else 44f) || (!isStrict && frontFloorHorizontal)
                } else {
                    abs(shS.y - hpS.y) < abs(shS.x - hpS.x) * 0.60f || (!isStrict && frontFloorHorizontal)
                }
                val standAngleThresh = if (isStrict) 155f else 145f
                val standingTall = hipAngle > standAngleThresh && kneeAngle > standAngleThresh && shS.y < hpS.y
                when (burpeeStage) {
                    0 -> {
                        phase = if (kneeAngle < 130f) MovementPhase.ECCENTRIC else MovementPhase.READY
                        romPct = if (bodyHorizontal) 75 else mapRange(kneeAngle, 165f, 90f) / 2
                        if (kneeAngle < 130f && repStartTsMs == 0L) repStartTsMs = timestampMs
                        if (bodyHorizontal) {
                            burpeeStage = 1
                            burpeeBottomEnteredTsMs = timestampMs
                            phase = MovementPhase.BOTTOM
                            if (repStartTsMs == 0L) repStartTsMs = timestampMs
                        }
                    }
                    1 -> {
                        phase = MovementPhase.BOTTOM
                        romPct = 100
                        val dwellMs = if (burpeeBottomEnteredTsMs > 0L) timestampMs - burpeeBottomEnteredTsMs else 0L
                        val repDurMs = if (repStartTsMs > 0L) timestampMs - repStartTsMs else 0L
                        if (standingTall) {
                            if (dwellMs >= MIN_BOTTOM_DWELL_MS && repDurMs >= minRepDurationMs()) {
                                repCount++
                                recordRom(100)
                                repCompletedNow = true
                                completedRepRom = 100
                                completedRepDurSec = computeRepDurationSec(timestampMs)
                                voiceCue = "$repCount"
                            }
                            repStartTsMs = 0L
                            burpeeBottomEnteredTsMs = 0L
                            burpeeStage = 0
                            phase = MovementPhase.READY
                        } else if (!bodyHorizontal && shS.y < hpS.y) {
                            if (dwellMs >= MIN_BOTTOM_DWELL_MS) {
                                phase = MovementPhase.CONCENTRIC
                            } else {
                                burpeeStage = 0
                                burpeeBottomEnteredTsMs = 0L
                                repStartTsMs = 0L
                                phase = MovementPhase.READY
                            }
                        }
                    }
                }
                if (bodyHorizontal && bodyLineAlign < 152f) {
                    val delta = (168f - bodyLineAlign).roundToInt().coerceAtLeast(1)
                    score -= 22
                    faultMsg = "Gainez le bassin en position planche"
                    val (gx, gy) = bodyLineGhost(shS, hpS, anS)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = hpIdx,
                            jointLabelFr = "Gainage",
                            currentDeg = bodyLineAlign.roundToInt(),
                            targetMinDeg = 160,
                            targetMaxDeg = 195,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = 0f,
                            arrowDy = -1f,
                            ghostAnchorIndex = shIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "BASSIN +$delta°",
                            instructionFr = "Remontez le bassin de $delta° en planche (${bodyLineAlign.roundToInt()}° → ≥160°)",
                            voiceCueFr = "Gainez le bassin en planche"
                        )
                    )
                }
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, hipAngle.roundToInt(), scoreToQuality(score),
                        parentIndex = shIdx, childIndex = knIdx, targetRangeText = "160–180°"
                    )
                )
                feedback = faultMsg ?: when (burpeeStage) {
                    1 -> "Remontez explosif et sautez !"
                    else -> "Descendez en planche gainée puis enchaînez."
                }
            }

            ExerciseType.JUMPING_JACK -> {
                val shoulderW = if (use3D) {
                    dist3D(worldPts[LEFT_SHOULDER], worldPts[RIGHT_SHOULDER]).coerceAtLeast(0.18f)
                } else {
                    dist2D(screenPts[LEFT_SHOULDER], screenPts[RIGHT_SHOULDER]).coerceAtLeast(0.06f)
                }
                val ankleW = if (use3D) {
                    dist3D(worldPts[LEFT_ANKLE], worldPts[RIGHT_ANKLE])
                } else {
                    dist2D(screenPts[LEFT_ANKLE], screenPts[RIGHT_ANKLE])
                }
                val anklesClearlyVisible = lLegW >= 0.45f && rLegW >= 0.45f &&
                    viewpointInfo.viewpoint != CameraViewpoint.PROFILE_LEFT &&
                    viewpointInfo.viewpoint != CameraViewpoint.PROFILE_RIGHT
                val legsSpread = ankleW > shoulderW * 1.15f
                romPct = mapRange(armFullAngle, 22f, if (isStrict) 152f else 142f)

                if (anklesClearlyVisible && armFullAngle > 130f && !legsSpread) {
                    score -= 14
                    faultMsg = "Écartez davantage les jambes en synchronisant"
                    corrections.add(
                        AngleCorrection(
                            jointIndex = anIdx,
                            jointLabelFr = "Appuis",
                            currentDeg = ((ankleW / shoulderW) * 100f).roundToInt(),
                            targetMinDeg = 115,
                            targetMaxDeg = 180,
                            deltaDeg = 20,
                            severity = FormQuality.CAUTION,
                            arrowDx = if (useLeft) -1f else 1f,
                            arrowDy = 0f,
                            shortBadgeFr = "PIEDS +LARGE",
                            instructionFr = "Écartez davantage les pieds au sommet des bras",
                            voiceCueFr = "Écartez davantage les jambes"
                        )
                    )
                }
                // Never block rep counting on legsSpread when in ADAPTIVE or when ankles are occluded / side-view
                val requireLegsGate = isStrict && anklesClearlyVisible
                val cycle = updateUpDownCycle(
                    metric = armFullAngle,
                    startThreshold = if (isStrict) 45f else 46f,
                    topThreshold = if (isStrict) 138f else 128f,
                    finishThreshold = if (isStrict) 42f else 48f,
                    extraTopCondition = if (requireLegsGate) legsSpread else true,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                addSymmetryCorrectionIfNeeded(
                    leftArmFull, rightArmFull, lArmW, rArmW,
                    active = armFullAngle > 60f,
                    jointLabel = "bras",
                    leftJointIdx = LEFT_SHOULDER,
                    rightJointIdx = RIGHT_SHOULDER
                )

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        shIdx, shS.x, shS.y, armFullAngle.roundToInt(), scoreToQuality(score),
                        parentIndex = hpIdx, childIndex = wrIdx, targetRangeText = if (isStrict) "≥138°" else "≥128°"
                    )
                )
                feedback = faultMsg ?: "Rythme fluide — bras hauts et pieds écartés."
            }

            ExerciseType.PULL_UP -> {
                val noseY = geomPts[NOSE].y
                val midWristY = (geomPts[LEFT_WRIST].y + geomPts[RIGHT_WRIST].y) * 0.5f
                val chinMargin = if (use3D) 0.06f else 0.035f
                val chinOverHands = noseY < midWristY + chinMargin
                val bottomThresh = if (isStrict) 74f else 92f
                val finishThresh = if (isStrict) 155f else 142f
                val startThresh = if (isStrict) 148f else 140f

                // Wide-grip pull-ups reach chin-over-bar at ~95-110° elbows -> fuse chinOverHands with elbow flexion
                val effectivePullMetric = if (chinOverHands && elbowAngle < 122f) {
                    minOf(elbowAngle, bottomThresh - 2f)
                } else {
                    elbowAngle
                }
                romPct = maxOf(
                    mapRange(elbowAngle, 160f, if (isStrict) 58f else 76f),
                    if (chinOverHands && elbowAngle < 122f) 96 else 0
                )

                val cycle = updateDownUpCycle(
                    metric = effectivePullMetric,
                    startThreshold = startThresh,
                    bottomThreshold = bottomThresh,
                    finishThreshold = finishThresh,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (reachedBottom && !chinOverHands && elbowAngle < bottomThresh + 12f) {
                    val delta = (elbowAngle - 62f).roundToInt().coerceAtLeast(8)
                    score -= 15
                    faultMsg = "Tirez la poitrine plus haut vers la barre"
                    corrections.add(
                        AngleCorrection(
                            jointIndex = shIdx,
                            jointLabelFr = "Tirage",
                            currentDeg = elbowAngle.roundToInt(),
                            targetMinDeg = 45,
                            targetMaxDeg = bottomThresh.roundToInt(),
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = -1f,
                            shortBadgeFr = "TIREZ +HAUT",
                            instructionFr = "Tirez le menton au-dessus des mains (encore -$delta° coude)",
                            voiceCueFr = "Tirez la poitrine plus haut"
                        )
                    )
                }

                addSymmetryCorrectionIfNeeded(
                    leftElbow, rightElbow, lArmW, rArmW,
                    active = elbowAngle < 135f,
                    jointLabel = "bras",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        elIdx, elS.x, elS.y, elbowAngle.roundToInt(), scoreToQuality(score),
                        parentIndex = shIdx, childIndex = wrIdx, targetRangeText = "≤${bottomThresh.roundToInt()}°"
                    )
                )
                feedback = faultMsg ?: "Tirez les coudes vers les hanches, amplitude complète."
            }

            ExerciseType.BENT_ROW -> {
                val bothArmsVis = lArmW >= 0.45f && rArmW >= 0.45f
                val isUnilateral = bothVisible && bothArmsVis && abs(leftElbow - rightElbow) > 32f
                val activeLeftRow = if (isUnilateral) leftElbow <= rightElbow else useLeft
                val activeRowElbow = if (isUnilateral) minOf(leftElbow, rightElbow) else elbowAngle
                val activeElIdx = if (isUnilateral) (if (activeLeftRow) LEFT_ELBOW else RIGHT_ELBOW) else elIdx
                val activeShIdx = if (activeElIdx == LEFT_ELBOW) LEFT_SHOULDER else RIGHT_SHOULDER
                val activeWrIdx = if (activeElIdx == LEFT_ELBOW) LEFT_WRIST else RIGHT_WRIST
                val activeElS = screenPts[activeElIdx]
                if (isUnilateral) detectedVariantFr = "Unilatéral"

                romPct = mapRange(activeRowElbow, 155f, if (isStrict) 72f else 88f)
                var torsoHint: Int? = null
                if (torsoTilt < 24f) {
                    val delta = (40f - torsoTilt).roundToInt().coerceAtLeast(1)
                    score -= 22
                    torsoHint = 45
                    faultMsg = "Penchez davantage le buste (35–55°)"
                    val (gx, gy) = hingeTorsoGhost(hpS, shS, 45f)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = shIdx,
                            jointLabelFr = "Buste",
                            currentDeg = torsoTilt.roundToInt(),
                            targetMinDeg = 35,
                            targetMaxDeg = 55,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = 0f,
                            arrowDy = 1f,
                            ghostAnchorIndex = hpIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "BUSTE +$delta°",
                            instructionFr = "Penchez le buste de $delta° (${torsoTilt.roundToInt()}° → 35–55°)",
                            voiceCueFr = "Penchez davantage le buste"
                        )
                    )
                } else if (torsoTilt > 80f) {
                    val delta = (torsoTilt - 55f).roundToInt().coerceAtLeast(1)
                    score -= 16
                    torsoHint = 50
                    faultMsg = "Relevez légèrement le buste, dos plat"
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 50f)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = shIdx,
                            jointLabelFr = "Buste",
                            currentDeg = torsoTilt.roundToInt(),
                            targetMinDeg = 35,
                            targetMaxDeg = 55,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = adx,
                            arrowDy = ady,
                            ghostAnchorIndex = hpIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "BUSTE -$delta°",
                            instructionFr = "Relevez le buste de $delta° (${torsoTilt.roundToInt()}° → 35–55°)",
                            voiceCueFr = "Relevez légèrement le buste"
                        )
                    )
                }

                val rowBottomThresh = if (isStrict) 82f else 98f
                val cycle = updateDownUpCycle(
                    metric = activeRowElbow,
                    startThreshold = if (isStrict) 145f else 138f,
                    bottomThreshold = rowBottomThresh,
                    finishThreshold = if (isStrict) 150f else 140f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (!isUnilateral) {
                    addSymmetryCorrectionIfNeeded(
                        leftElbow, rightElbow, lArmW, rArmW,
                        active = activeRowElbow < 135f,
                        jointLabel = "tirage",
                        leftJointIdx = LEFT_ELBOW,
                        rightJointIdx = RIGHT_ELBOW
                    )
                }

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeElIdx, activeElS.x, activeElS.y, activeRowElbow.roundToInt(), scoreToQuality(score),
                        parentIndex = activeShIdx,
                        childIndex = activeWrIdx,
                        targetRangeText = "≤${rowBottomThresh.roundToInt()}°"
                    )
                )
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, torsoTilt.roundToInt(),
                        if (torsoTilt in 24f..80f) FormQuality.GOOD else FormQuality.FAULT,
                        parentIndex = shIdx, childIndex = knIdx, targetRangeText = "35–55°", targetDegHint = torsoHint
                    )
                )
                feedback = faultMsg ?: if (isUnilateral) {
                    "Rowing unilatéral (${torsoTilt.roundToInt()}°) — tirez le coude vers la hanche."
                } else {
                    "Buste fixe (${torsoTilt.roundToInt()}°) — serrez les omoplates."
                }
            }

            ExerciseType.GLUTE_BRIDGE -> {
                romPct = mapRange(hipAngle, if (isStrict) 115f else 128f, if (isStrict) 172f else 164f)
                var hipHint: Int? = null
                val supineAlign = if (use3D) {
                    bodyLineAlignment3D(geomPts[shIdx], geomPts[hpIdx], geomPts[knIdx])
                } else {
                    bodyLineAlignmentDegrees(shS, hpS, knS)
                }
                if (supineAlign > 194f) {
                    val cur = supineAlign.roundToInt()
                    val delta = (cur - 180).coerceAtLeast(6)
                    score -= 20
                    hipHint = 175
                    faultMsg = "Ne cambrez pas les lombaires en haut"
                    corrections.add(
                        AngleCorrection(
                            jointIndex = hpIdx,
                            jointLabelFr = "Bassin",
                            currentDeg = cur,
                            targetMinDeg = 165,
                            targetMaxDeg = 182,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = 0f,
                            arrowDy = 1f,
                            shortBadgeFr = "BASSIN -$delta°",
                            instructionFr = "Stoppez l'extension à 175° sans cambrer (-$delta°)",
                            voiceCueFr = "Ne cambrez pas le bas du dos"
                        )
                    )
                }

                val bridgeTopThresh = if (isStrict) 162f else 156f
                val cycle = updateUpDownCycle(
                    metric = hipAngle,
                    startThreshold = if (isStrict) 128f else 136f,
                    topThreshold = bridgeTopThresh,
                    finishThreshold = if (isStrict) 125f else 136f,
                    extraTopCondition = true,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (!reachedBottom && hipAngle in 138f..(bridgeTopThresh - 2f)) {
                    val targetDeg = bridgeTopThresh.roundToInt()
                    val delta = (bridgeTopThresh - hipAngle).roundToInt().coerceAtLeast(1)
                    hipHint = targetDeg
                    corrections.add(
                        AngleCorrection(
                            jointIndex = hpIdx,
                            jointLabelFr = "Hanches",
                            currentDeg = hipAngle.roundToInt(),
                            targetMinDeg = targetDeg,
                            targetMaxDeg = 180,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = -1f,
                            shortBadgeFr = "HANCHES +$delta°",
                            instructionFr = "Montez encore les hanches (+$delta° : ${hipAngle.roundToInt()}° → ≥$targetDeg°)",
                            voiceCueFr = "Montez les hanches plus haut"
                        )
                    )
                }

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, hipAngle.roundToInt(),
                        if (hipHint != null && score < 82) FormQuality.FAULT else scoreToQuality(score),
                        parentIndex = shIdx, childIndex = knIdx, targetRangeText = "≥${bridgeTopThresh.roundToInt()}°", targetDegHint = hipHint
                    )
                )
                feedback = faultMsg ?: "Extension complète des hanches (${hipAngle.roundToInt()}°), serrez 1s."
            }

            ExerciseType.LEG_RAISE -> {
                romPct = mapRange(hipAngle, if (isStrict) 168f else 156f, if (isStrict) 92f else 102f)
                var kneeHint: Int? = null
                if (kneeAngle < 135f) {
                    val delta = (155f - kneeAngle).roundToInt().coerceAtLeast(1)
                    score -= 18
                    kneeHint = 155
                    faultMsg = "Gardez les jambes plus tendues"
                    val (gx, gy) = straightenLimbGhost(hpS, knS, anS)
                    val dx = gx - anS.x
                    val dy = gy - anS.y
                    val n = hypot(dx, dy).coerceAtLeast(0.001f)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = knIdx,
                            jointLabelFr = "Genoux",
                            currentDeg = kneeAngle.roundToInt(),
                            targetMinDeg = 150,
                            targetMaxDeg = 180,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = dx / n,
                            arrowDy = dy / n,
                            ghostAnchorIndex = knIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "GENOU +$delta°",
                            instructionFr = "Tendez davantage les jambes (+$delta° : ${kneeAngle.roundToInt()}° → ≥150°)",
                            voiceCueFr = "Tendez davantage les jambes"
                        )
                    )
                }

                val legBottomThresh = if (isStrict) 104f else 112f
                val cycle = updateDownUpCycle(
                    metric = hipAngle,
                    startThreshold = if (isStrict) 152f else 145f,
                    bottomThreshold = legBottomThresh,
                    finishThreshold = if (isStrict) 156f else 145f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, hipAngle.roundToInt(), scoreToQuality(score),
                        parentIndex = shIdx, childIndex = knIdx, targetRangeText = "≤${legBottomThresh.roundToInt()}°"
                    )
                )
                badges.add(
                    JointAngleBadge(
                        knIdx, knS.x, knS.y, kneeAngle.roundToInt(),
                        if (kneeHint != null) FormQuality.FAULT else FormQuality.GOOD,
                        parentIndex = hpIdx, childIndex = anIdx, targetRangeText = "≥150°", targetDegHint = kneeHint
                    )
                )
                feedback = faultMsg ?: "Contrôlez la descente, lombaires collées au sol."
            }

            ExerciseType.LATERAL_RAISE -> {
                romPct = mapRange(shoulderAngle, if (isStrict) 15f else 24f, if (isStrict) 90f else 84f)
                var shHint: Int? = null
                if (shoulderAngle > 108f) {
                    val delta = (shoulderAngle - 92f).roundToInt().coerceAtLeast(1)
                    score -= 18
                    shHint = 90
                    faultMsg = "Stoppez à l'horizontale (90°) sans hausser les trapèzes"
                    corrections.add(
                        AngleCorrection(
                            jointIndex = shIdx,
                            jointLabelFr = "Épaule",
                            currentDeg = shoulderAngle.roundToInt(),
                            targetMinDeg = 80,
                            targetMaxDeg = 95,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = 1f,
                            shortBadgeFr = "ÉPAULE -$delta°",
                            instructionFr = "Stoppez à 90° (-$delta° : ${shoulderAngle.roundToInt()}° → 85–95°)",
                            voiceCueFr = "Stoppez à 90 degrés, épaules basses"
                        )
                    )
                }
                if (torsoTilt > 15f) {
                    val delta = (torsoTilt - 15f).roundToInt().coerceAtLeast(1)
                    score -= 20
                    faultMsg = "Ne balancez pas le buste"
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 6f)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = shIdx,
                            jointLabelFr = "Buste",
                            currentDeg = torsoTilt.roundToInt(),
                            targetMinDeg = 0,
                            targetMaxDeg = 15,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = adx,
                            arrowDy = ady,
                            ghostAnchorIndex = hpIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "BUSTE -$delta°",
                            instructionFr = "Stabilisez le buste (-$delta° : ${torsoTilt.roundToInt()}° → ≤15°)",
                            voiceCueFr = "Ne balancez pas le buste"
                        )
                    )
                }

                val latTopThresh = if (isStrict) 78f else 72f
                val cycle = updateUpDownCycle(
                    metric = shoulderAngle,
                    startThreshold = if (isStrict) 28f else 34f,
                    topThreshold = latTopThresh,
                    finishThreshold = if (isStrict) 26f else 34f,
                    extraTopCondition = true,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                addSymmetryCorrectionIfNeeded(
                    leftShoulder, rightShoulder, lArmW, rArmW,
                    active = shoulderAngle > 35f,
                    jointLabel = "épaules",
                    leftJointIdx = LEFT_SHOULDER,
                    rightJointIdx = RIGHT_SHOULDER
                )

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        shIdx, shS.x, shS.y, shoulderAngle.roundToInt(), scoreToQuality(score),
                        parentIndex = hpIdx, childIndex = elIdx, targetRangeText = "${latTopThresh.roundToInt()}–95°", targetDegHint = shHint
                    )
                )
                feedback = faultMsg ?: "Élevez jusqu'à 90° (${shoulderAngle.roundToInt()}°), épaules basses."
            }
        }

        // Update Velocity-Based Training (VBT) telemetry (concentric m/s, 3-phase tempo, velocity loss %, RIR)
        val vbtMetrics = vbtEngine.onFrame(
            exercise = currentExercise,
            phase = phase,
            repCompletedNow = repCompletedNow,
            screenPts = screenPts,
            worldPts = worldPts,
            use3D = use3D,
            timestampMs = timestampMs
        )
        if (repCompletedNow && vbtMetrics.alertVoiceCueFr != null) {
            voiceCue = "$repCount. ${vbtMetrics.alertVoiceCueFr}"
        }

        // Order corrections: FAULT first, then largest angle delta
        val orderedCorrections = corrections.sortedWith(
            compareBy<AngleCorrection> {
                when (it.severity) {
                    FormQuality.FAULT -> 0
                    FormQuality.CAUTION -> 1
                    FormQuality.GOOD -> 2
                }
            }.thenByDescending { abs(it.deltaDeg) }
        )

        val clampedScore = score.coerceIn(0, 100)
        formScoreSum += clampedScore
        formScoreSamples++
        if (faultMsg != null) {
            faultCounts[faultMsg] = (faultCounts[faultMsg] ?: 0) + 1
            if (voiceCue == null) voiceCue = orderedCorrections.firstOrNull()?.voiceCueFr ?: faultMsg
        } else if (voiceCue == null && orderedCorrections.isNotEmpty() && orderedCorrections.first().severity == FormQuality.FAULT) {
            voiceCue = orderedCorrections.first().voiceCueFr
        }

        return BiomechanicsFrame(
            exercise = currentExercise,
            landmarks = screenPts,
            repCount = repCount,
            isometricHoldSec = plankHoldMs / 1000f,
            phase = phase,
            romPct = romPct.coerceIn(0, 100),
            formScore = clampedScore,
            formQuality = scoreToQuality(clampedScore),
            feedbackFr = feedback,
            voiceCueFr = voiceCue,
            angleBadges = badges,
            avgFormScore = computeAvgForm(),
            avgRomPct = computeAvgRom(),
            topFaults = computeTopFaults(),
            poseDetected = true,
            worldLandmarks = worldPts,
            viewpointInfo = viewpointInfo,
            corrections = orderedCorrections,
            repCompletedNow = repCompletedNow,
            completedRepRomPct = completedRepRom,
            completedRepDurationSec = completedRepDurSec,
            isometricDeltaSec = isometricDeltaSec,
            detectedVariantFr = detectedVariantFr,
            vbt = vbtMetrics
        )
    }

    private data class CycleResult(
        val completed: Boolean,
        val completedRom: Int,
        val durationSec: Float
    )

    private fun minRepDurationMs(): Long = 180L

    private fun completeRepOrAbort(timestampMs: Long, checkBottomDwell: Boolean): CycleResult {
        val dwellOk = !checkBottomDwell ||
            (bottomEnteredTsMs > 0L && (timestampMs - bottomEnteredTsMs) >= MIN_BOTTOM_DWELL_MS)
        val durOk = repStartTsMs > 0L && (timestampMs - repStartTsMs) >= minRepDurationMs()
        if (dwellOk && durOk) {
            val peakRom = repRomPeak
            val durSec = computeRepDurationSec(timestampMs)
            repCount++
            recordRom(peakRom)
            abortIncompleteRepCycle()
            return CycleResult(true, peakRom, durSec)
        }
        abortIncompleteRepCycle()
        return CycleResult(false, 0, 0f)
    }

    /**
     * Multi-signal normalized progress cycle (0.0 = rest/top -> 1.0 = full bottom/peak -> 0.0 = rep completed).
     * Used by fused multi-signal exercises (Push-ups, Dips) to work seamlessly across all variants & camera angles.
     */
    private fun updateProgressCycle(
        progress: Float,
        startThreshold: Float,
        peakThreshold: Float,
        finishThreshold: Float,
        currentRom: Int,
        fault: String?,
        timestampMs: Long
    ): CycleResult {
        if (currentRom > repRomPeak) repRomPeak = currentRom
        if (fault != null) currentRepFault = fault

        when (phase) {
            MovementPhase.READY -> {
                if (progress >= startThreshold) {
                    val hitPeak = progress >= peakThreshold
                    phase = if (hitPeak) MovementPhase.BOTTOM else MovementPhase.ECCENTRIC
                    reachedBottom = hitPeak
                    bottomEnteredTsMs = if (hitPeak) timestampMs else 0L
                    repRomPeak = currentRom
                    repStartTsMs = timestampMs
                    currentRepFault = fault
                }
            }
            MovementPhase.ECCENTRIC -> {
                if (progress >= peakThreshold) {
                    phase = MovementPhase.BOTTOM
                    reachedBottom = true
                    bottomEnteredTsMs = timestampMs
                } else if (progress < startThreshold * 0.60f) {
                    abortIncompleteRepCycle()
                }
            }
            MovementPhase.BOTTOM -> {
                val dwellMs = if (bottomEnteredTsMs > 0L) timestampMs - bottomEnteredTsMs else 0L
                if (progress <= finishThreshold && reachedBottom) {
                    return completeRepOrAbort(timestampMs, checkBottomDwell = true)
                } else if (progress < peakThreshold - 0.12f) {
                    if (dwellMs >= MIN_BOTTOM_DWELL_MS) {
                        phase = MovementPhase.CONCENTRIC
                    } else {
                        reachedBottom = false
                        bottomEnteredTsMs = 0L
                        phase = MovementPhase.ECCENTRIC
                    }
                }
            }
            MovementPhase.CONCENTRIC -> {
                if (progress <= finishThreshold && reachedBottom) {
                    return completeRepOrAbort(timestampMs, checkBottomDwell = false)
                } else if (progress >= peakThreshold) {
                    phase = MovementPhase.BOTTOM
                    if (bottomEnteredTsMs == 0L) bottomEnteredTsMs = timestampMs
                }
            }
            else -> phase = MovementPhase.READY
        }
        return CycleResult(false, 0, 0f)
    }

    /** State machine for movements whose active bottom phase decreases the primary angle (Squat, Curl, Pull-up...). */
    private fun updateDownUpCycle(
        metric: Float,
        startThreshold: Float,
        bottomThreshold: Float,
        finishThreshold: Float,
        currentRom: Int,
        fault: String?,
        timestampMs: Long
    ): CycleResult {
        if (currentRom > repRomPeak) repRomPeak = currentRom
        if (fault != null) currentRepFault = fault
        val abortThreshold = maxOf(startThreshold, finishThreshold) + 4f

        when (phase) {
            MovementPhase.READY -> {
                if (metric < startThreshold) {
                    val hitBottom = metric <= bottomThreshold
                    phase = if (hitBottom) MovementPhase.BOTTOM else MovementPhase.ECCENTRIC
                    reachedBottom = hitBottom
                    bottomEnteredTsMs = if (hitBottom) timestampMs else 0L
                    repRomPeak = currentRom
                    repStartTsMs = timestampMs
                    currentRepFault = fault
                }
            }
            MovementPhase.ECCENTRIC -> {
                if (metric <= bottomThreshold) {
                    phase = MovementPhase.BOTTOM
                    reachedBottom = true
                    bottomEnteredTsMs = timestampMs
                } else if (metric >= abortThreshold) {
                    abortIncompleteRepCycle()
                }
            }
            MovementPhase.BOTTOM -> {
                val dwellMs = if (bottomEnteredTsMs > 0L) timestampMs - bottomEnteredTsMs else 0L
                if (metric >= finishThreshold && reachedBottom) {
                    return completeRepOrAbort(timestampMs, checkBottomDwell = true)
                } else if (metric > bottomThreshold + 10f) {
                    if (dwellMs >= MIN_BOTTOM_DWELL_MS) {
                        phase = MovementPhase.CONCENTRIC
                    } else {
                        reachedBottom = false
                        bottomEnteredTsMs = 0L
                        phase = MovementPhase.ECCENTRIC
                    }
                }
            }
            MovementPhase.CONCENTRIC -> {
                if (metric >= finishThreshold && reachedBottom) {
                    return completeRepOrAbort(timestampMs, checkBottomDwell = false)
                }
            }
            else -> phase = MovementPhase.READY
        }
        return CycleResult(false, 0, 0f)
    }

    /** State machine for movements whose active peak phase increases the primary angle (OHP, Lateral Raise, Glute Bridge...). */
    private fun updateUpDownCycle(
        metric: Float,
        startThreshold: Float,
        topThreshold: Float,
        finishThreshold: Float,
        extraTopCondition: Boolean,
        currentRom: Int,
        fault: String?,
        timestampMs: Long
    ): CycleResult {
        if (currentRom > repRomPeak) repRomPeak = currentRom
        if (fault != null) currentRepFault = fault
        val abortThreshold = minOf(startThreshold, finishThreshold) - 4f

        when (phase) {
            MovementPhase.READY -> {
                if (metric > startThreshold) {
                    val hitTop = metric >= topThreshold && extraTopCondition
                    phase = if (hitTop) MovementPhase.BOTTOM else MovementPhase.CONCENTRIC
                    reachedBottom = hitTop
                    bottomEnteredTsMs = if (hitTop) timestampMs else 0L
                    repRomPeak = currentRom
                    repStartTsMs = timestampMs
                    currentRepFault = fault
                }
            }
            MovementPhase.CONCENTRIC -> {
                if (metric >= topThreshold && extraTopCondition) {
                    phase = MovementPhase.BOTTOM
                    reachedBottom = true
                    bottomEnteredTsMs = timestampMs
                } else if (metric <= abortThreshold) {
                    abortIncompleteRepCycle()
                }
            }
            MovementPhase.BOTTOM -> {
                val dwellMs = if (bottomEnteredTsMs > 0L) timestampMs - bottomEnteredTsMs else 0L
                if (metric <= finishThreshold && reachedBottom) {
                    return completeRepOrAbort(timestampMs, checkBottomDwell = true)
                } else if (metric < topThreshold - 10f) {
                    if (dwellMs >= MIN_BOTTOM_DWELL_MS) {
                        phase = MovementPhase.ECCENTRIC
                    } else {
                        reachedBottom = false
                        bottomEnteredTsMs = 0L
                        phase = MovementPhase.CONCENTRIC
                    }
                }
            }
            MovementPhase.ECCENTRIC -> {
                if (metric <= finishThreshold && reachedBottom) {
                    return completeRepOrAbort(timestampMs, checkBottomDwell = false)
                }
            }
            else -> phase = MovementPhase.READY
        }
        return CycleResult(false, 0, 0f)
    }

    private fun computeRepDurationSec(nowMs: Long): Float {
        if (repStartTsMs <= 0L || nowMs <= repStartTsMs) return 2.4f
        return ((nowMs - repStartTsMs) / 1000f).coerceIn(0.6f, 12.0f)
    }

    private fun fuseSide(
        leftVal: Float,
        rightVal: Float,
        leftWeight: Float,
        rightWeight: Float,
        useLeft: Boolean,
        bothVisible: Boolean
    ): Float {
        if (!bothVisible) return if (useLeft) leftVal else rightVal
        val lw = leftWeight.coerceAtLeast(0.05f)
        val rw = rightWeight.coerceAtLeast(0.05f)
        return (leftVal * lw + rightVal * rw) / (lw + rw)
    }

    private fun smoothLandmarks(raw: List<PosePoint>, timestampMs: Long, isWorld: Boolean): List<PosePoint> {
        // Explicit pass-through mode when emaAlpha >= 0.999 (used in deterministic keyframe unit tests)
        if (emaAlpha >= 0.999f && !useOneEuroFilter) {
            return raw
        }
        if (useOneEuroFilter) {
            return if (isWorld) {
                worldSmoother.smooth(raw, timestampMs)
            } else {
                screenSmoother.smooth(raw, timestampMs)
            }
        }
        val prev = if (isWorld) legacySmoothedWorld else legacySmoothedScreen
        if (prev == null || prev.size != raw.size) {
            val copy = raw.toMutableList()
            if (isWorld) legacySmoothedWorld = copy else legacySmoothedScreen = copy
            return copy
        }
        val a = emaAlpha
        val inv = 1f - a
        for (i in raw.indices) {
            val r = raw[i]
            val p = prev[i]
            prev[i] = PosePoint(
                x = a * r.x + inv * p.x,
                y = a * r.y + inv * p.y,
                z = a * r.z + inv * p.z,
                visibility = r.visibility
            )
        }
        return prev.toList()
    }

    private data class GhostVector(val targetX: Float, val targetY: Float, val arrowDx: Float, val arrowDy: Float)

    private fun uprightTorsoGhost(hipS: PosePoint, shS: PosePoint, targetTiltDeg: Float): GhostVector {
        val len = dist2D(hipS, shS).coerceAtLeast(0.14f)
        val dx = shS.x - hipS.x
        if (abs(dx) < 0.015f) {
            // Frontal view: forward lean foreshortens screen Y -> pull chest straight UP
            val ty = (hipS.y - (len * 1.25f).coerceAtLeast(0.18f)).coerceIn(0.04f, 0.96f)
            return GhostVector(hipS.x, ty, 0f, -1f)
        }
        val sign = if (dx >= 0f) 1f else -1f
        val rad = targetTiltDeg * PI / 180.0
        val tx = (hipS.x + sign * len * sin(rad).toFloat()).coerceIn(0.03f, 0.97f)
        val ty = (hipS.y - len * cos(rad).toFloat()).coerceIn(0.03f, 0.97f)
        val ax = tx - shS.x
        val ay = ty - shS.y
        val norm = hypot(ax, ay).coerceAtLeast(0.001f)
        return GhostVector(tx, ty, ax / norm, ay / norm)
    }

    private fun hingeTorsoGhost(hipS: PosePoint, shS: PosePoint, targetTiltDeg: Float): Pair<Float, Float> {
        val len = dist2D(hipS, shS).coerceAtLeast(0.14f)
        val sign = if (shS.x >= hipS.x) 1f else -1f
        val rad = targetTiltDeg * PI / 180.0
        val tx = (hipS.x + sign * len * sin(rad).toFloat()).coerceIn(0.03f, 0.97f)
        val ty = (hipS.y - len * cos(rad).toFloat()).coerceIn(0.03f, 0.97f)
        return tx to ty
    }

    private fun bodyLineGhost(shS: PosePoint, hpS: PosePoint, anS: PosePoint): Pair<Float, Float> {
        val dx = anS.x - shS.x
        val dy = anS.y - shS.y
        val denom = (dx * dx + dy * dy).coerceAtLeast(0.0001f)
        val t = (((hpS.x - shS.x) * dx + (hpS.y - shS.y) * dy) / denom).coerceIn(0.2f, 0.8f)
        val gx = (shS.x + t * dx).coerceIn(0.03f, 0.97f)
        val gy = (shS.y + t * dy).coerceIn(0.03f, 0.97f)
        return gx to gy
    }

    private fun squatDepthGhost(knS: PosePoint, hpS: PosePoint): Pair<Float, Float> {
        val thighLen = dist2D(knS, hpS).coerceAtLeast(0.12f)
        val sign = if (hpS.x <= knS.x) -1f else 1f
        val gx = (knS.x + sign * thighLen * 0.96f).coerceIn(0.03f, 0.97f)
        val gy = (knS.y - thighLen * 0.06f).coerceIn(0.03f, 0.97f)
        return gx to gy
    }

    private fun straightenLimbGhost(anchorS: PosePoint, midS: PosePoint, endS: PosePoint): Pair<Float, Float> {
        val seg1X = midS.x - anchorS.x
        val seg1Y = midS.y - anchorS.y
        val len1 = hypot(seg1X, seg1Y).coerceAtLeast(0.001f)
        val len2 = dist2D(midS, endS).coerceAtLeast(0.08f)
        val gx = (midS.x + (seg1X / len1) * len2).coerceIn(0.03f, 0.97f)
        val gy = (midS.y + (seg1Y / len1) * len2).coerceIn(0.03f, 0.97f)
        return gx to gy
    }

    private fun outwardScreenDirection(kneeX: Float, ankleX: Float, midX: Float): Float {
        val toAnkle = ankleX - kneeX
        return when {
            abs(toAnkle) > 0.015f -> if (toAnkle > 0f) 1f else -1f
            kneeX >= midX -> 1f
            else -> -1f
        }
    }

    private fun mapRange(value: Float, startVal: Float, endVal: Float): Int {
        if (abs(endVal - startVal) < 0.001f) return 0
        val pct = ((value - startVal) / (endVal - startVal)) * 100f
        return pct.roundToInt().coerceIn(0, 100)
    }

    private fun scoreToQuality(score: Int): FormQuality = when {
        score >= 82 -> FormQuality.GOOD
        score >= 65 -> FormQuality.CAUTION
        else -> FormQuality.FAULT
    }

    private fun recordRom(peak: Int) {
        romSum += peak.coerceIn(0, 100)
        romSamples++
    }

    private fun computeAvgForm(): Int =
        if (formScoreSamples > 0) (formScoreSum / formScoreSamples).toInt().coerceIn(0, 100) else 100

    private fun computeAvgRom(): Int =
        if (romSamples > 0) (romSum / romSamples).toInt().coerceIn(0, 100) else if (currentExercise.isIsometric && plankHoldMs > 0) 100 else 0

    private fun computeTopFaults(): List<String> =
        faultCounts.entries
            .filter { it.value >= 3 }
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
}
