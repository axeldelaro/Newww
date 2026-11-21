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
    CLOSE("Serrée"),
    DIAMOND("Diamant"),
    KNEE("Genoux"),
    PIKE("Piquée"),
    ARCHER("Archer"),
    INCLINE("Inclinée"),
    DECLINE("Déclinée")
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
    val detectedVariantId: String? = null,
    val isStabilizing: Boolean = false,
    val velocityTelemetry: VelocityTelemetry = VelocityTelemetry(),
    val completedRepVelocityMps: Float = 0f
)

/**
 * Pure-Kotlin real-time 3D multi-angle biomechanics & VBT analyzer for all 16 KINETIX exercises.
 *
 * Features:
 * - Adaptive Casiez One-Euro filtering + physics-based [PoseSanityFilter] & [CameraViewpointTracker]
 * - Viewpoint-invariant 3D SO(3) joint geometry with height-calibrated 2D fallbacks
 * - Deadlock-free multi-signal fused depth tracking across all 9 push-up variants & all 16 exercises
 * - Comprehensive variant classification via [ExerciseVariantDetector] with biomechanical adaptation
 * - Calm, non-spammy temporal persistence & cooldown coaching via [PostureFeedbackCoach]
 * - Real-time Velocity-Based Training (VBT) concentric speed & estimated Max Reps / RIR via [VelocityEstimator]
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

        private const val SYMMETRY_THRESHOLD_DEG = 14f
        private const val MIN_BOTTOM_DWELL_MS = 55L
        private const val FAST_TOUCH_BOTTOM_DWELL_MS = 25L
        private const val POSTURE_ABORT_GRACE_FRAMES = 4
        private const val PUSHUP_WINDOW_MS = 4200L
        private const val DEPTH_STALL_MIN_MS = 480L
        private const val EARLY_REVERSAL_HINT_MS = 1800L

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

            val lateralProj = devX * pelvisRightAxis.x + devY * pelvisRightAxis.y + devZ * pelvisRightAxis.z
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

    var athleteHeightMeters: Float = 1.76f

    private val sanityFilter = PoseSanityFilter()
    private val viewpointTracker = CameraViewpointTracker()
    private val screenSmoother = PoseOneEuroSmoother()
    private val worldSmoother = PoseOneEuroSmoother()
    private val variantDetector = ExerciseVariantDetector()
    private val postureCoach = PostureFeedbackCoach()
    private val velocityEstimator = VelocityEstimator(initialExercise)
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
    private var enteredBottomFromRamp: Boolean = false
    private var bottomEnteredTsMs: Long = 0L
    private var concentricLeftBottomTsMs: Long = 0L
    private var burpeeStage: Int = 0 // 0=stand, 1=down/plank
    private var burpeeBottomEnteredTsMs: Long = 0L
    private var plankHoldMs: Long = 0L
    private var lastFrameTsMs: Long = 0L
    private var repStartTsMs: Long = 0L
    private var lastMotionGuard: MotionGuardState = MotionGuardState()
    private var postureGateRejectStreak: Int = 0

    // Phase-1 / Phase-2 metric kinematic trackers for accurate concentric VBT measurement
    private var prevPrimaryPosMeters: Float = Float.NaN
    private var phase1StartPosMeters: Float = Float.NaN
    private var phase1EndPosMeters: Float = Float.NaN
    private var phase1PeakVelMps: Float = 0f
    private var phase2StartPosMeters: Float = Float.NaN
    private var phase2PeakVelMps: Float = 0f
    private var lastActiveLimbLeft: Boolean? = null

    // Anti-spam depth stall & early reversal tracker (prevents nagging while athlete smoothly descends)
    private var depthStallAnchorMetric: Float = Float.NaN
    private var depthStallStartTsMs: Long = Long.MIN_VALUE
    private var earlyReversalHintUntilTsMs: Long = Long.MIN_VALUE

    // Self-healing sliding-window vertical top references (deadlock-free across all camera angles!)
    private val pushupHeightWindow = ArrayDeque<PushupHeightSample>(128)
    private var adaptivePushupTopHeight: Float = 0f
    private var pushupStraightArmStreak: Int = 0
    private var adaptiveDipTopHeight: Float = 0f

    // Session metrics accumulators
    private var formScoreSum: Long = 0L
    private var formScoreSamples: Int = 0
    private var repRomPeak: Int = 0
    private var romSum: Long = 0L
    private var romSamples: Int = 0
    private val faultCounts = linkedMapOf<String, Int>()
    private var currentRepFault: String? = null
    private val variantCounts = linkedMapOf<String, Int>()

    val isMidRep: Boolean
        get() = phase == MovementPhase.ECCENTRIC ||
            phase == MovementPhase.BOTTOM ||
            phase == MovementPhase.CONCENTRIC

    val hasActiveSetReps: Boolean
        get() = repCount > 0 || (currentExercise.isIsometric && plankHoldMs >= 2000L)

    val currentMotionGuard: MotionGuardState
        get() = lastMotionGuard

    fun currentVelocityTelemetry(): VelocityTelemetry = velocityEstimator.currentTelemetry()

    fun dominantVariantLabel(): String? =
        variantCounts.entries.maxByOrNull { it.value }?.key

    fun setStrictness(mode: DetectionStrictness) {
        strictness = mode
    }

    fun setExercise(exercise: ExerciseType) {
        currentExercise = exercise
        velocityEstimator.setExercise(exercise, clearSessionHistory = true)
        resetSession()
    }

    /**
     * Switches to a new exercise in AUTO mode while preserving One-Euro smoother, sanity filter,
     * and viewpoint tracker state so the drawn skeleton never jumps or teleports on an auto-switch.
     */
    fun switchExerciseSmoothly(exercise: ExerciseType) {
        currentExercise = exercise
        velocityEstimator.setExercise(exercise, clearSessionHistory = false)
        resetExerciseCountersOnly()
    }

    /**
     * Archives the active set's velocity telemetry and prepares the next set while preserving
     * cross-set fresh baseline velocity for cumulative fatigue & readiness tracking.
     */
    fun completeSet(): SetVelocitySummary? {
        val summary = velocityEstimator.completeSet()
        resetExerciseCountersOnly()
        return summary
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
        prevPrimaryPosMeters = Float.NaN
        lastMotionGuard = MotionGuardState()
        postureGateRejectStreak = 0
    }

    fun resetSession() {
        resetTrackingFilters()
        velocityEstimator.resetSession()
        resetExerciseCountersOnly()
    }

    private fun resetExerciseCountersOnly() {
        repCount = 0
        phase = MovementPhase.READY
        reachedBottom = false
        enteredBottomFromRamp = false
        bottomEnteredTsMs = 0L
        concentricLeftBottomTsMs = 0L
        burpeeStage = 0
        burpeeBottomEnteredTsMs = 0L
        plankHoldMs = 0L
        repStartTsMs = 0L
        postureGateRejectStreak = 0
        prevPrimaryPosMeters = Float.NaN
        phase1StartPosMeters = Float.NaN
        phase1EndPosMeters = Float.NaN
        phase1PeakVelMps = 0f
        phase2StartPosMeters = Float.NaN
        phase2PeakVelMps = 0f
        lastActiveLimbLeft = null
        depthStallAnchorMetric = Float.NaN
        depthStallStartTsMs = Long.MIN_VALUE
        earlyReversalHintUntilTsMs = Long.MIN_VALUE
        pushupHeightWindow.clear()
        adaptivePushupTopHeight = 0f
        pushupStraightArmStreak = 0
        adaptiveDipTopHeight = 0f
        variantDetector.reset()
        postureCoach.reset()
        formScoreSum = 0L
        formScoreSamples = 0
        repRomPeak = 0
        romSum = 0L
        romSamples = 0
        faultCounts.clear()
        variantCounts.clear()
        currentRepFault = null
    }

    private fun abortIncompleteRepCycle(timestampMs: Long = 0L) {
        // If the athlete genuinely attempted a rep (>= 34% ROM) and reversed early without reaching depth,
        // briefly allow a calm depth cue instead of spamming during descent!
        if (!reachedBottom && repRomPeak >= 34 && timestampMs > 0L) {
            earlyReversalHintUntilTsMs = timestampMs + EARLY_REVERSAL_HINT_MS
        }
        phase = MovementPhase.READY
        reachedBottom = false
        enteredBottomFromRamp = false
        bottomEnteredTsMs = 0L
        concentricLeftBottomTsMs = 0L
        repStartTsMs = 0L
        repRomPeak = 0
        currentRepFault = null
        phase1StartPosMeters = Float.NaN
        phase1EndPosMeters = Float.NaN
        phase1PeakVelMps = 0f
        phase2StartPosMeters = Float.NaN
        phase2PeakVelMps = 0f
        depthStallAnchorMetric = Float.NaN
        depthStallStartTsMs = Long.MIN_VALUE
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
        velocityTelemetry = velocityEstimator.currentTelemetry()
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
            prevPrimaryPosMeters = Float.NaN
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
                velocityTelemetry = velocityEstimator.currentTelemetry()
            )
        }

        val use3D = viewpointInfo.is3DActive && worldPts.size >= 29
        val geomPts = if (use3D) worldPts else screenPts

        val useLeft = viewpointInfo.dominantSideLeft
        val bothVisible = viewpointInfo.bothSidesVisible

        val shIdx = if (useLeft) LEFT_SHOULDER else RIGHT_SHOULDER
        val elIdx = if (useLeft) LEFT_ELBOW else RIGHT_ELBOW
        val wrIdx = if (useLeft) LEFT_WRIST else RIGHT_WRIST
        val hpIdx = if (useLeft) LEFT_HIP else RIGHT_HIP
        val knIdx = if (useLeft) LEFT_KNEE else RIGHT_KNEE
        val anIdx = if (useLeft) LEFT_ANKLE else RIGHT_ANKLE

        val shS = screenPts[shIdx]
        val elS = screenPts[elIdx]
        val wrS = screenPts[wrIdx]
        val hpS = screenPts[hpIdx]
        val knS = screenPts[knIdx]
        val anS = screenPts[anIdx]

        fun jointAngle(a: Int, b: Int, c: Int): Float =
            if (use3D) angleDegrees3D(geomPts[a], geomPts[b], geomPts[c])
            else angleDegrees(geomPts[a], geomPts[b], geomPts[c])

        fun bodyAlign(shI: Int, hpI: Int, anI: Int): Float =
            if (use3D) bodyLineAlignment3D(geomPts[shI], geomPts[hpI], geomPts[anI])
            else bodyLineAlignmentDegrees(geomPts[shI], geomPts[hpI], geomPts[anI])

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

        // Compute primary-mover metric position (meters, positive = moving against gravity) & live velocity
        val primaryPosMeters = computePrimaryMoverPositionMeters(
            exercise = currentExercise,
            geomPts = geomPts,
            screenPts = screenPts,
            use3D = use3D,
            bothVisible = bothVisible,
            useLeft = useLeft
        )
        val instantSignedVelMps = if (dtMs > 0L && prevPrimaryPosMeters.isFinite()) {
            val dtSec = (dtMs / 1000f).coerceIn(1f / 240f, 0.25f)
            ((primaryPosMeters - prevPrimaryPosMeters) / dtSec).coerceIn(-4.0f, 4.0f)
        } else {
            0f
        }
        val instantSpeedMps = abs(instantSignedVelMps)
        prevPrimaryPosMeters = primaryPosMeters

        // Physical posture sanity gate (viewpoint-safe across Front, 3/4, Profile, Rear, Incline & Decline!)
        val postureGateFeedback = evaluatePostureGate(
            exercise = currentExercise,
            use3D = use3D,
            bothVisible = bothVisible,
            useLeft = useLeft,
            geomPts = geomPts,
            screenPts = screenPts,
            torsoTilt = torsoTilt,
            fullBodyTilt = fullBodyTilt,
            lArmW = lArmW,
            rArmW = rArmW,
            frontFloorHorizontal = frontFloorHorizontal,
            activeWrSY = activeWrSY,
            activeShSY = activeShSY,
            midShSAll = midShSAll,
            torsoVert2DGlobal = torsoVert2DGlobal,
            shWidth2DGlobal = shWidth2DGlobal
        )

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
                    velocityTelemetry = velocityEstimator.currentTelemetry()
                )
            }
        } else {
            postureGateRejectStreak = 0
        }

        // Detect exercise variant across ALL 16 exercises (temporally stabilized in live mode)
        val variant = variantDetector.detect(
            VariantKinematicsInput(
                exercise = currentExercise,
                phase = phase,
                use3D = use3D,
                bothVisible = bothVisible,
                useLeft = useLeft,
                screenPts = screenPts,
                geomPts = geomPts,
                lArmW = lArmW,
                rArmW = rArmW,
                lLegW = lLegW,
                rLegW = rLegW,
                leftKnee = leftKnee,
                rightKnee = rightKnee,
                kneeAngle = kneeAngle,
                leftHip = leftHip,
                rightHip = rightHip,
                hipAngle = hipAngle,
                leftElbow = leftElbow,
                rightElbow = rightElbow,
                elbowAngle = elbowAngle,
                leftShoulder = leftShoulder,
                rightShoulder = rightShoulder,
                shoulderAngle = shoulderAngle,
                armFullAngle = armFullAngle,
                torsoTilt = torsoTilt,
                fullBodyTilt = fullBodyTilt,
                bodyLineAlign = bodyLineAlign,
                verticalSpeedMps = instantSignedVelMps,
                deterministicPassThrough = isDeterministicPassThrough
            )
        )
        val detectedVariantFr = variant.labelFr
        variantCounts[detectedVariantFr] = (variantCounts[detectedVariantFr] ?: 0) + 1

        var romPct = 0
        var baseFeedback = "Posture maîtrisée — continuez."
        var repVoiceCue: String? = null
        var repCompletedNow = false
        var completedRepRom = 0
        var completedRepDurSec = 0f
        var completedRepVelMps = 0f
        var isometricDeltaSec = 0f
        var plankScoreOverride: Int? = null

        val isStrict = strictness == DetectionStrictness.STRICT
        val badges = mutableListOf<JointAngleBadge>()
        val rawIssues = mutableListOf<RawPostureIssue>()

        fun addSymmetryIssueIfNeeded(
            leftDeg: Float,
            rightDeg: Float,
            leftW: Float,
            rightW: Float,
            active: Boolean,
            jointLabel: String,
            leftJointIdx: Int,
            rightJointIdx: Int
        ) {
            if (variant.exemptsSymmetry || !bothVisible || !active) return
            if (leftW < 0.55f || rightW < 0.55f) return
            val diff = abs(leftDeg - rightDeg)
            if (diff >= SYMMETRY_THRESHOLD_DEG) {
                val delta = diff.roundToInt()
                val offIdx = if (leftDeg > rightDeg) leftJointIdx else rightJointIdx
                val msg = "Équilibrez gauche (${leftDeg.roundToInt()}°) et droite (${rightDeg.roundToInt()}°)"
                rawIssues.add(
                    RawPostureIssue(
                        key = "SYMMETRY_$jointLabel",
                        correction = AngleCorrection(
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
                        ),
                        scorePenalty = 10
                    )
                )
            }
        }

        // Helper: returns true ONLY when depth coaching should be shown (NEVER mid-smooth-descent in live mode!)
        fun shouldEmitDepthHint(trackedMetric: Float, metricTolerance: Float): Boolean {
            if (isDeterministicPassThrough) return true
            if (timestampMs <= earlyReversalHintUntilTsMs) return true
            if (depthStallAnchorMetric.isNaN() || abs(trackedMetric - depthStallAnchorMetric) > metricTolerance) {
                depthStallAnchorMetric = trackedMetric
                depthStallStartTsMs = timestampMs
                return false
            }
            return (timestampMs - depthStallStartTsMs) >= DEPTH_STALL_MIN_MS
        }

        fun recordCompletedCycle(cycle: CycleResult, activeLeftSide: Boolean? = null) {
            if (!cycle.completed) return
            repCompletedNow = true
            completedRepRom = cycle.completedRom
            completedRepDurSec = cycle.durationSec
            val spec = VelocityEstimator.specFor(currentExercise)
            val concDur = if (spec.isConcentricFirst) cycle.phase1DurationSec else cycle.phase2DurationSec
            val eccDur = if (spec.isConcentricFirst) cycle.phase2DurationSec else cycle.phase1DurationSec
            val concDisp = if (spec.isConcentricFirst) cycle.phase1DispMeters else cycle.phase2DispMeters
            val concPeakV = if (spec.isConcentricFirst) cycle.phase1PeakVelMps else cycle.phase2PeakVelMps
            val vbt = velocityEstimator.recordRep(
                concentricDurationSec = concDur,
                eccentricDurationSec = eccDur,
                totalDurationSec = cycle.durationSec,
                measuredDisplacementMeters = concDisp,
                peakInstantVelocityMps = concPeakV,
                romPct = cycle.completedRom,
                timestampMs = timestampMs
            )
            completedRepVelMps = vbt.lastRepVelocityMps
            postureCoach.notifyRepCompleted(timestampMs)
            variantDetector.onRepCompleted(activeLeftSide)
        }

        when (currentExercise) {
            ExerciseType.SQUAT -> {
                val isPistol = variant.id == "PISTOL"
                val activeSquatLeft = if (isPistol) (variant.preferredActiveLeft ?: useLeft) else useLeft
                val activeSquatKnee = if (isPistol) minOf(leftKnee, rightKnee) else kneeAngle
                val activeKnIdx = if (isPistol) (if (activeSquatLeft) LEFT_KNEE else RIGHT_KNEE) else knIdx
                val activeHpIdx = if (isPistol) (if (activeSquatLeft) LEFT_HIP else RIGHT_HIP) else hpIdx
                val activeAnIdx = if (isPistol) (if (activeSquatLeft) LEFT_ANKLE else RIGHT_ANKLE) else anIdx
                val activeKnS = screenPts[activeKnIdx]

                romPct = mapRange(activeSquatKnee, 168f, if (isStrict) 82f else 90f)
                var kneeTargetHint: Int? = null
                var hipTargetHint: Int? = null

                // 1. Torso forward lean check (3D yaw-invariant)
                val maxTorsoLean = if (variant.id == "SUMO") 48f else 44f
                if (torsoTilt > maxTorsoLean) {
                    val delta = (torsoTilt - maxTorsoLean).roundToInt().coerceAtLeast(1)
                    hipTargetHint = (maxTorsoLean - 4f).roundToInt()
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 35f)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "SQUAT_TORSO",
                            correction = AngleCorrection(
                                jointIndex = shIdx,
                                jointLabelFr = "Buste",
                                currentDeg = torsoTilt.roundToInt(),
                                targetMinDeg = 0,
                                targetMaxDeg = maxTorsoLean.roundToInt(),
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = adx,
                                arrowDy = ady,
                                ghostAnchorIndex = hpIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "BUSTE +$delta°",
                                instructionFr = "Redressez le buste de $delta° (${torsoTilt.roundToInt()}° → ≤${maxTorsoLean.roundToInt()}°)",
                                voiceCueFr = "Redressez le buste"
                            ),
                            scorePenalty = 22,
                            feedbackMsgFr = "Redressez la poitrine (buste trop penché)"
                        )
                    )
                }

                // 2. Knee valgus check (3D medial collapse projected onto pelvis lateral axis + 2D fallback)
                var valgusDetected = false
                if (!isPistol && use3D && bothVisible && activeSquatKnee < 138f && lLegW >= 0.45f && rLegW >= 0.45f) {
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
                        rawIssues.add(
                            RawPostureIssue(
                                key = "SQUAT_VALGUS",
                                correction = AngleCorrection(
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
                                ),
                                scorePenalty = 38,
                                feedbackMsgFr = "Poussez les genoux vers l'extérieur (valgus)"
                            )
                        )
                    }
                } else if (!isPistol && !use3D && activeSquatKnee < 135f) {
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
                        rawIssues.add(
                            RawPostureIssue(
                                key = "SQUAT_VALGUS",
                                correction = AngleCorrection(
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
                                ),
                                scorePenalty = 38,
                                feedbackMsgFr = "Poussez les genoux vers l'extérieur (valgus)"
                            )
                        )
                    }
                }

                // 3. Multi-signal cycle update: knee angle + hip-to-knee vertical parallel assist in ADAPTIVE
                val midHipY = if (bothVisible) (geomPts[LEFT_HIP].y + geomPts[RIGHT_HIP].y) * 0.5f else geomPts[hpIdx].y
                val midKneeY = if (bothVisible) (geomPts[LEFT_KNEE].y + geomPts[RIGHT_KNEE].y) * 0.5f else geomPts[knIdx].y
                val thighLen = if (use3D) {
                    dist3D(geomPts[hpIdx], geomPts[knIdx]).coerceAtLeast(0.24f)
                } else {
                    dist2D(hpS, knS).coerceAtLeast(0.08f)
                }
                val hipNearParallel = activeSquatKnee < 128f && (midKneeY - midHipY) <= thighLen * 0.24f
                val effectiveSquatMetric = if (!isStrict && hipNearParallel) minOf(activeSquatKnee, 104f) else activeSquatKnee
                if (!isStrict && hipNearParallel && romPct < 88) {
                    romPct = maxOf(romPct, 88)
                }

                val cycle = updateDownUpCycle(
                    metric = effectiveSquatMetric,
                    startThreshold = if (isStrict) 152f else 148f,
                    bottomThreshold = if (isStrict) 98f else 108f,
                    finishThreshold = if (isStrict) 156f else 150f,
                    currentRom = romPct,
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                recordCompletedCycle(cycle, if (isPistol) activeSquatLeft else null)

                // 4. Anti-spam depth guidance (fires ONLY on stall / early reversal in live mode!)
                if (!reachedBottom && activeSquatKnee in 98.5f..132f && !valgusDetected &&
                    shouldEmitDepthHint(activeSquatKnee, metricTolerance = 3.0f)
                ) {
                    val delta = (activeSquatKnee - 95f).roundToInt().coerceAtLeast(1)
                    kneeTargetHint = 95
                    val (gx, gy) = squatDepthGhost(activeKnS, hpS)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "SQUAT_DEPTH",
                            correction = AngleCorrection(
                                jointIndex = activeKnIdx,
                                jointLabelFr = "Genou",
                                currentDeg = activeSquatKnee.roundToInt(),
                                targetMinDeg = 70,
                                targetMaxDeg = 95,
                                deltaDeg = delta,
                                severity = FormQuality.CAUTION,
                                arrowDx = 0f,
                                arrowDy = 1f,
                                ghostAnchorIndex = activeKnIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "GENOU -$delta°",
                                instructionFr = "Descendez encore $delta° (${activeSquatKnee.roundToInt()}° → ≤95°)",
                                voiceCueFr = "Descendez encore $delta degrés"
                            ),
                            scorePenalty = 4,
                            isDepthCue = true
                        )
                    )
                }

                addSymmetryIssueIfNeeded(
                    leftKnee, rightKnee, lLegW, rLegW,
                    active = activeSquatKnee < 138f,
                    jointLabel = "genoux",
                    leftJointIdx = LEFT_KNEE,
                    rightJointIdx = RIGHT_KNEE
                )

                if (repCompletedNow) {
                    repVoiceCue = if (completedRepRom < 72) "Descendez un peu plus bas" else "$repCount"
                }

                badges.add(
                    JointAngleBadge(
                        landmarkIndex = activeKnIdx,
                        x = activeKnS.x,
                        y = activeKnS.y,
                        degrees = activeSquatKnee.roundToInt(),
                        quality = if (valgusDetected) FormQuality.FAULT else if (kneeTargetHint != null) FormQuality.CAUTION else FormQuality.GOOD,
                        parentIndex = activeHpIdx,
                        childIndex = activeAnIdx,
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
                        quality = if (torsoTilt > maxTorsoLean) FormQuality.FAULT else FormQuality.GOOD,
                        parentIndex = shIdx,
                        childIndex = knIdx,
                        targetRangeText = "≤${maxTorsoLean.roundToInt()}° buste",
                        targetDegHint = hipTargetHint
                    )
                )
                baseFeedback = when (phase) {
                    MovementPhase.BOTTOM -> if (activeSquatKnee <= 96f) {
                        "Profondeur $detectedVariantFr validée (${activeSquatKnee.roundToInt()}°) — poussez !"
                    } else {
                        "Parallèle validée (${activeSquatKnee.roundToInt()}°) — poussez !"
                    }
                    MovementPhase.ECCENTRIC -> "Squat $detectedVariantFr — contrôlez la descente, dos neutre."
                    MovementPhase.CONCENTRIC -> "Poussez explosif dans tout le pied !"
                    else -> "Squat $detectedVariantFr prêt — genoux dans l'axe."
                }
            }

            ExerciseType.PUSHUP -> {
                // 1. Cross-checked 3D + 2D elbow angles (immune to MediaPipe prone 3D flattening in side profile)
                val bothArmsVis = lArmW >= 0.40f && rArmW >= 0.40f
                val bestArmVis = maxOf(lArmW, rArmW)
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
                val bestElbow = if (bothArmsVis) minElbow else if (lArmW >= rArmW) effLeftElbow else effRightElbow

                val isKnee = variant.usesKneeAlignment
                val isPike = variant.exemptsHighHip
                val isArcher = variant.id == "ARCHER"
                val isWide = variant.id == "WIDE"
                val isIncline = variant.id == "INCLINE"

                // 2. Occlusion- & angle-safe alignment check (shoulder-hip-knee for Knee; exempt Pike & occluded front hips)
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
                    if (effectiveAlign < 154f) {
                        val delta = (172f - effectiveAlign).roundToInt().coerceAtLeast(1)
                        hipHint = 175
                        val (gx, gy) = bodyLineGhost(shS, hpS, alignEndS)
                        rawIssues.add(
                            RawPostureIssue(
                                key = "PUSHUP_HIP_SAG",
                                correction = AngleCorrection(
                                    jointIndex = hpIdx,
                                    jointLabelFr = "Hanches",
                                    currentDeg = effectiveAlign.roundToInt(),
                                    targetMinDeg = 158,
                                    targetMaxDeg = 202,
                                    deltaDeg = delta,
                                    severity = FormQuality.FAULT,
                                    arrowDx = 0f,
                                    arrowDy = -1f,
                                    ghostAnchorIndex = shIdx,
                                    ghostTargetX = gx,
                                    ghostTargetY = gy,
                                    shortBadgeFr = "HANCHES +$delta°",
                                    instructionFr = "Remontez les hanches de $delta° (${effectiveAlign.roundToInt()}° → 160–200°)",
                                    voiceCueFr = "Gainez les abdos, remontez les hanches"
                                ),
                                scorePenalty = 26,
                                feedbackMsgFr = "Gainez les abdos : ne creusez pas le bas du dos"
                            )
                        )
                    } else if (effectiveAlign > 206f) {
                        val delta = (effectiveAlign - 188f).roundToInt().coerceAtLeast(1)
                        hipHint = 180
                        val (gx, gy) = bodyLineGhost(shS, hpS, alignEndS)
                        rawIssues.add(
                            RawPostureIssue(
                                key = "PUSHUP_HIP_PIKE",
                                correction = AngleCorrection(
                                    jointIndex = hpIdx,
                                    jointLabelFr = "Hanches",
                                    currentDeg = effectiveAlign.roundToInt(),
                                    targetMinDeg = 158,
                                    targetMaxDeg = 202,
                                    deltaDeg = delta,
                                    severity = FormQuality.FAULT,
                                    arrowDx = 0f,
                                    arrowDy = 1f,
                                    ghostAnchorIndex = shIdx,
                                    ghostTargetX = gx,
                                    ghostTargetY = gy,
                                    shortBadgeFr = "HANCHES -$delta°",
                                    instructionFr = "Abaissez les hanches de $delta° (${effectiveAlign.roundToInt()}° → 160–200°)",
                                    voiceCueFr = "Abaissez les hanches pour aligner le corps"
                                ),
                                scorePenalty = 20,
                                feedbackMsgFr = "Abaissez légèrement les hanches"
                            )
                        )
                    }
                }

                // Elbow flare check (exempt Wide, Pike, Archer)
                if (!variant.exemptsElbowFlare && use3D && bothVisible && effElbowAngle < 122f && shoulderAngle > 80f) {
                    val delta = (shoulderAngle - 55f).roundToInt().coerceAtLeast(1)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "PUSHUP_FLARE",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 12
                        )
                    )
                }

                // 3. Bulletproof 3-Signal Fused Push-Up Depth Engine (Zero Lockup, All Angles, All 9 Variants):
                //    Signal A: Active Elbow Flexion (3D + 2D profile cross-check)
                //    Signal B: Camera-Tilt-Invariant 3D Arm Chord Compression (self-normalized, zero calibration)
                //    Signal C: Sliding-Window Screen Vertical Chest+Head Drop (works even with foreshortened 2D front elbows)
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
                    isIncline -> 108f
                    else -> 110f
                }
                val elbowProgress = ((148f - activeElbow) / (148f - elbowBottomTarget).coerceAtLeast(10f))
                    .coerceIn(0f, 1.25f)

                // Signal B: Self-normalized 3D arm compression ratio (shoulder-to-wrist chord / anatomical arm length)
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
                val inPushupAttitude = torsoTilt >= 22f || fullBodyTilt >= 22f || frontFloorHorizontal || isPike || isIncline
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

                    // Track sustained straight-arm top frames to self-heal immediately after stepping down from standing
                    if (activeElbow >= 148f && bestArmVis >= 0.45f) {
                        pushupStraightArmStreak++
                    } else {
                        pushupStraightArmStreak = 0
                    }

                    if (pushupStraightArmStreak >= 4) {
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
                            adaptivePushupTopHeight * 0.72f + currentScreenHeight * 0.28f
                        } else {
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

                // Straight-arm lockout guard: if arms are clearly straight and uncompressed, never allow
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

                val peakThresh = if (isStrict) 0.78f else 0.60f
                val finishThresh = if (isStrict) 0.25f else 0.34f
                val cycle = updateProgressCycle(
                    progress = pushupDepth,
                    startThreshold = 0.22f,
                    peakThreshold = peakThresh,
                    finishThreshold = finishThresh,
                    currentRom = romPct,
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                val activePushupLeft = if (isArcher && bothArmsVis) effLeftElbow <= effRightElbow else null
                recordCompletedCycle(cycle, activePushupLeft)

                val activeElIdx = if (isArcher && bothArmsVis) {
                    if (effLeftElbow <= effRightElbow) LEFT_ELBOW else RIGHT_ELBOW
                } else {
                    elIdx
                }
                val activeShIdx = if (activeElIdx == LEFT_ELBOW) LEFT_SHOULDER else RIGHT_SHOULDER
                val activeWrIdx = if (activeElIdx == LEFT_ELBOW) LEFT_WRIST else RIGHT_WRIST
                val activeElS = screenPts[activeElIdx]

                // Anti-spam depth coaching (only fires on stall / early reversal in live mode!)
                if (!reachedBottom && pushupDepth in 0.28f..(peakThresh - 0.04f) &&
                    activeElbow in (elbowBottomTarget + 4f)..134f &&
                    shouldEmitDepthHint(activeElbow, metricTolerance = 3.5f)
                ) {
                    val targetDeg = elbowBottomTarget.roundToInt()
                    val delta = (activeElbow - elbowBottomTarget).roundToInt().coerceAtLeast(1)
                    elbowHint = targetDeg
                    rawIssues.add(
                        RawPostureIssue(
                            key = "PUSHUP_DEPTH",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 4,
                            isDepthCue = true
                        )
                    )
                }

                addSymmetryIssueIfNeeded(
                    effLeftElbow, effRightElbow, lArmW, rArmW,
                    active = activeElbow < 136f,
                    jointLabel = "coudes",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeElIdx, activeElS.x, activeElS.y, activeElbow.roundToInt(),
                        if (elbowHint != null) FormQuality.CAUTION else FormQuality.GOOD,
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
                            if (isPike || effectiveAlign in 154f..206f) FormQuality.GOOD else FormQuality.FAULT,
                            parentIndex = shIdx,
                            childIndex = alignEndIdx,
                            targetRangeText = if (isPike) "V inversé" else "160–200°",
                            targetDegHint = hipHint
                        )
                    )
                }
                baseFeedback = when (phase) {
                    MovementPhase.BOTTOM -> if (isPike) {
                        "Pompe piquée validée — repoussez fort !"
                    } else {
                        "Amplitude $detectedVariantFr validée — repoussez !"
                    }
                    else -> when (variant.id) {
                        "PIKE" -> "Piquée détectée — descendez la tête entre les mains."
                        "ARCHER" -> "Archer détectée — fléchissez le bras actif en contrôle."
                        "KNEE" -> "Pompes genoux (${effectiveAlign.roundToInt()}°) — buste gainé."
                        "INCLINE" -> "Pompes inclinées — corps gainé, amplitude complète."
                        "DECLINE" -> "Pompes déclinées — gainage fort, contrôlez la descente."
                        "WIDE" -> "Prise large — poitrine vers le sol, amplitude fluide."
                        "CLOSE" -> "Prise serrée — coudes près du corps, triceps engagés."
                        "DIAMOND" -> "Prise diamant — coudes près des côtes, triceps engagés."
                        else -> if (canJudgeBodyAlign) {
                            "Pompes classiques (${effectiveAlign.roundToInt()}°) — mouvement fluide."
                        } else {
                            "Pompes détectées — contrôlez la descente et poussez fort."
                        }
                    }
                }
            }

            ExerciseType.LUNGE, ExerciseType.BULGARIAN_SQUAT -> {
                val bothLegsVis = lLegW >= 0.45f && rLegW >= 0.45f
                val isBulgarian = currentExercise == ExerciseType.BULGARIAN_SQUAT
                val activeLeft = when {
                    // For Bulgarian split squat, ALWAYS track the grounded (lower ankle = larger Y) front leg
                    // so a pre-bent elevated rear leg never hijacks rep counting!
                    isBulgarian && bothLegsVis && abs(geomPts[LEFT_ANKLE].y - geomPts[RIGHT_ANKLE].y) > (if (use3D) 0.10f else 0.04f) ->
                        geomPts[LEFT_ANKLE].y >= geomPts[RIGHT_ANKLE].y
                    variant.preferredActiveLeft != null -> variant.preferredActiveLeft
                    bothLegsVis -> leftKnee <= rightKnee
                    else -> useLeft
                }
                val activeKnee = when {
                    isBulgarian && bothLegsVis && abs(geomPts[LEFT_ANKLE].y - geomPts[RIGHT_ANKLE].y) > (if (use3D) 0.10f else 0.04f) ->
                        if (activeLeft) leftKnee else rightKnee
                    bothLegsVis -> minOf(leftKnee, rightKnee)
                    else -> kneeAngle
                }
                val activeKnIdx = if (activeLeft) LEFT_KNEE else RIGHT_KNEE
                val activeHpIdx = if (activeLeft) LEFT_HIP else RIGHT_HIP
                val activeAnIdx = if (activeLeft) LEFT_ANKLE else RIGHT_ANKLE
                val activeKnS = screenPts[activeKnIdx]

                romPct = mapRange(activeKnee, 162f, if (isStrict) 88f else 96f)
                val maxTilt = when {
                    variant.exemptsTorsoLean -> 42f // Lateral/Cossack lunge naturally leans more
                    isBulgarian -> 38f
                    else -> 30f
                }
                var kneeHint: Int? = null

                if (torsoTilt > maxTilt) {
                    val delta = (torsoTilt - maxTilt).roundToInt().coerceAtLeast(1)
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, maxTilt - 6f)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "LUNGE_TORSO",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 22,
                            feedbackMsgFr = "Redressez le buste, regard droit devant"
                        )
                    )
                }

                if (use3D && bothVisible && activeKnee < 135f) {
                    val vDeg = frontalKneeValgus3D(
                        worldPts[activeHpIdx], worldPts[activeKnIdx], worldPts[activeAnIdx], pelvisAxis, activeLeft
                    )
                    if (vDeg >= 10f) {
                        val delta = vDeg.roundToInt()
                        val outDx = outwardScreenDirection(
                            activeKnS.x,
                            screenPts[activeAnIdx].x,
                            (screenPts[LEFT_HIP].x + screenPts[RIGHT_HIP].x) * 0.5f
                        )
                        rawIssues.add(
                            RawPostureIssue(
                                key = "LUNGE_VALGUS",
                                correction = AngleCorrection(
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
                                ),
                                scorePenalty = 24,
                                feedbackMsgFr = "Stabilisez le genou avant dans l'axe du pied"
                            )
                        )
                    }
                }

                val bottomThresh = if (isStrict) 100f else 110f
                val cycle = updateDownUpCycle(
                    metric = activeKnee,
                    startThreshold = if (isStrict) 148f else 142f,
                    bottomThreshold = bottomThresh,
                    finishThreshold = if (isStrict) 154f else 144f,
                    currentRom = romPct,
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                recordCompletedCycle(cycle, activeLeft)

                if (!reachedBottom && activeKnee in (bottomThresh + 2f)..132f &&
                    shouldEmitDepthHint(activeKnee, metricTolerance = 3.0f)
                ) {
                    val delta = (activeKnee - 95f).roundToInt().coerceAtLeast(1)
                    kneeHint = 95
                    rawIssues.add(
                        RawPostureIssue(
                            key = "LUNGE_DEPTH",
                            correction = AngleCorrection(
                                jointIndex = activeKnIdx,
                                jointLabelFr = "Genou",
                                currentDeg = activeKnee.roundToInt(),
                                targetMinDeg = 75,
                                targetMaxDeg = 95,
                                deltaDeg = delta,
                                severity = FormQuality.CAUTION,
                                arrowDx = 0f,
                                arrowDy = 1f,
                                shortBadgeFr = "GENOU -$delta°",
                                instructionFr = "Descendez encore $delta° (${activeKnee.roundToInt()}° → ≤95°)",
                                voiceCueFr = "Descendez encore $delta degrés"
                            ),
                            scorePenalty = 4,
                            isDepthCue = true
                        )
                    )
                }

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeKnIdx, activeKnS.x, activeKnS.y, activeKnee.roundToInt(),
                        if (kneeHint != null) FormQuality.CAUTION else FormQuality.GOOD,
                        parentIndex = activeHpIdx,
                        childIndex = activeAnIdx,
                        targetRangeText = if (isStrict) "≤100°" else "≤110°",
                        targetDegHint = kneeHint
                    )
                )
                baseFeedback = "${currentExercise.labelFr} ($detectedVariantFr) · Amplitude $romPct%."
            }

            ExerciseType.DEADLIFT -> {
                val isSingleLeg = variant.id == "SINGLE_LEG_RDL"
                val isSumo = variant.id == "SUMO"
                val isRdl = variant.id == "RDL"
                val stanceLeft = variant.preferredActiveLeft ?: useLeft
                val activeHip = if (isSingleLeg) (if (stanceLeft) leftHip else rightHip) else hipAngle
                val activeKnee = if (isSingleLeg) (if (stanceLeft) leftKnee else rightKnee) else kneeAngle
                val activeHpIdx = if (isSingleLeg) (if (stanceLeft) LEFT_HIP else RIGHT_HIP) else hpIdx
                val activeKnIdx = if (isSingleLeg) (if (stanceLeft) LEFT_KNEE else RIGHT_KNEE) else knIdx
                val activeAnIdx = if (isSingleLeg) (if (stanceLeft) LEFT_ANKLE else RIGHT_ANKLE) else anIdx
                val activeHpS = screenPts[activeHpIdx]
                val activeKnS = screenPts[activeKnIdx]

                romPct = mapRange(activeHip, 168f, if (isStrict) 88f else 104f)
                var hipHint: Int? = null
                var kneeHint: Int? = null

                val minAllowedKnee = if (isSumo) 100f else 115f
                if (activeKnee < minAllowedKnee && activeHip > 110f) {
                    val delta = ((minAllowedKnee + 10f) - activeKnee).roundToInt().coerceAtLeast(1)
                    kneeHint = (minAllowedKnee + 10f).roundToInt()
                    rawIssues.add(
                        RawPostureIssue(
                            key = "DEADLIFT_SQUATTING",
                            correction = AngleCorrection(
                                jointIndex = activeHpIdx,
                                jointLabelFr = "Hanches",
                                currentDeg = activeKnee.roundToInt(),
                                targetMinDeg = minAllowedKnee.roundToInt(),
                                targetMaxDeg = 165,
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = 0f,
                                arrowDy = -1f,
                                shortBadgeFr = "CHARNIÈRE +$delta°",
                                instructionFr = "Montez les hanches et dépliez les genoux (+$delta° → ≥${minAllowedKnee.roundToInt()}°)",
                                voiceCueFr = "Poussez les hanches en arrière, ne squattez pas"
                            ),
                            scorePenalty = 18,
                            feedbackMsgFr = "Initiez par les hanches (évitez de squatter la barre)"
                        )
                    )
                }
                if (bodyLineAlign > 193f && torsoTilt > 10f && activeHip > 158f) {
                    val cur = bodyLineAlign.roundToInt()
                    val delta = (cur - 180).coerceAtLeast(5)
                    hipHint = 178
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 4f)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "DEADLIFT_HYPEREXT",
                            correction = AngleCorrection(
                                jointIndex = activeHpIdx,
                                jointLabelFr = "Lombaires",
                                currentDeg = cur,
                                targetMinDeg = 165,
                                targetMaxDeg = 182,
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = adx,
                                arrowDy = ady,
                                ghostAnchorIndex = activeHpIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "CAMBRURE -$delta°",
                                instructionFr = "Stoppez droit à 180° sans cambrer (-$delta°)",
                                voiceCueFr = "Stoppez droit, ne cambrez pas en arrière"
                            ),
                            scorePenalty = 22,
                            feedbackMsgFr = "Stoppez droit — ne cambrez pas en arrière"
                        )
                    )
                }

                val deadliftBottom = when {
                    isStrict -> 105f
                    isRdl || isSingleLeg -> 120f
                    else -> 116f
                }
                val cycle = updateDownUpCycle(
                    metric = activeHip,
                    startThreshold = 150f,
                    bottomThreshold = deadliftBottom,
                    finishThreshold = if (isStrict) 162f else 154f,
                    currentRom = romPct,
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                recordCompletedCycle(cycle, if (isSingleLeg) stanceLeft else null)

                addSymmetryIssueIfNeeded(
                    leftHip, rightHip, lTorsoW, rTorsoW,
                    active = activeHip < 145f,
                    jointLabel = "hanches",
                    leftJointIdx = LEFT_HIP,
                    rightJointIdx = RIGHT_HIP
                )

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeHpIdx, activeHpS.x, activeHpS.y, activeHip.roundToInt(), FormQuality.GOOD,
                        parentIndex = shIdx, childIndex = activeKnIdx, targetRangeText = "90–175°", targetDegHint = hipHint
                    )
                )
                badges.add(
                    JointAngleBadge(
                        activeKnIdx, activeKnS.x, activeKnS.y, activeKnee.roundToInt(),
                        if (kneeHint != null) FormQuality.CAUTION else FormQuality.GOOD,
                        parentIndex = activeHpIdx, childIndex = activeAnIdx, targetRangeText = "≥${minAllowedKnee.roundToInt()}°", targetDegHint = kneeHint
                    )
                )
                baseFeedback = when (phase) {
                    MovementPhase.BOTTOM -> "Charnière $detectedVariantFr verrouillée — poussez les hanches !"
                    else -> "Soulevé $detectedVariantFr · Dos neutre, barre proche des tibias."
                }
            }

            ExerciseType.OVERHEAD_PRESS -> {
                val isUnilateral = variant.usesUnilateralLimb
                val activeLeft = variant.preferredActiveLeft ?: useLeft
                val activeElbow = if (isUnilateral) maxOf(leftElbow, rightElbow) else elbowAngle
                val activeElIdx = if (isUnilateral) (if (activeLeft) LEFT_ELBOW else RIGHT_ELBOW) else elIdx
                val activeShIdx = if (activeElIdx == LEFT_ELBOW) LEFT_SHOULDER else RIGHT_SHOULDER
                val activeWrIdx = if (activeElIdx == LEFT_ELBOW) LEFT_WRIST else RIGHT_WRIST
                val activeElS = screenPts[activeElIdx]
                val activeShS = screenPts[activeShIdx]
                val activeWrS = screenPts[activeWrIdx]

                val overheadOk = if (use3D) {
                    if (isUnilateral) {
                        worldPts[activeWrIdx].y < worldPts[activeShIdx].y
                    } else if (bothVisible) {
                        ((worldPts[LEFT_WRIST].y + worldPts[RIGHT_WRIST].y) * 0.5f) <
                            ((worldPts[LEFT_SHOULDER].y + worldPts[RIGHT_SHOULDER].y) * 0.5f)
                    } else {
                        worldPts[wrIdx].y < worldPts[shIdx].y
                    }
                } else {
                    activeWrS.y < activeShS.y
                }
                romPct = if (overheadOk) mapRange(activeElbow, 85f, if (isStrict) 165f else 155f) else mapRange(activeElbow, 85f, 165f) / 2
                var elbowHint: Int? = null

                if (torsoTilt > 19f) {
                    val delta = (torsoTilt - 18f).roundToInt().coerceAtLeast(1)
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 8f)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "OHP_TORSO",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 25,
                            feedbackMsgFr = "Serrez fessiers et abdos — évitez de cambrer"
                        )
                    )
                }

                val topThresh = if (isStrict) 155f else 146f
                val cycle = updateUpDownCycle(
                    metric = activeElbow,
                    startThreshold = if (isStrict) 100f else 106f,
                    topThreshold = topThresh,
                    finishThreshold = if (isStrict) 95f else 110f,
                    extraTopCondition = overheadOk,
                    currentRom = romPct,
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                recordCompletedCycle(cycle, if (isUnilateral) activeLeft else null)

                if (overheadOk && !reachedBottom && activeElbow in 118f..(topThresh - 2f) &&
                    shouldEmitDepthHint(activeElbow, metricTolerance = 3.5f)
                ) {
                    val delta = (topThresh + 6f - activeElbow).roundToInt().coerceAtLeast(1)
                    elbowHint = (topThresh + 6f).roundToInt()
                    val (gx, gy) = straightenLimbGhost(activeShS, activeElS, activeWrS)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "OHP_LOCKOUT",
                            correction = AngleCorrection(
                                jointIndex = activeElIdx,
                                jointLabelFr = "Verrouillage",
                                currentDeg = activeElbow.roundToInt(),
                                targetMinDeg = topThresh.roundToInt(),
                                targetMaxDeg = 180,
                                deltaDeg = delta,
                                severity = FormQuality.CAUTION,
                                arrowDx = 0f,
                                arrowDy = -1f,
                                ghostAnchorIndex = activeElIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "COUDE +$delta°",
                                instructionFr = "Verrouillez les coudes (+$delta° : ${activeElbow.roundToInt()}° → ≥${topThresh.roundToInt()}°)",
                                voiceCueFr = "Verrouillez les bras en haut"
                            ),
                            scorePenalty = 4,
                            isDepthCue = true
                        )
                    )
                }

                addSymmetryIssueIfNeeded(
                    leftElbow, rightElbow, lArmW, rArmW,
                    active = activeElbow > 108f,
                    jointLabel = "bras",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeElIdx, activeElS.x, activeElS.y, activeElbow.roundToInt(),
                        if (elbowHint != null) FormQuality.CAUTION else FormQuality.GOOD,
                        parentIndex = activeShIdx, childIndex = activeWrIdx, targetRangeText = "≥${topThresh.roundToInt()}°", targetDegHint = elbowHint
                    )
                )
                baseFeedback = "Développé $detectedVariantFr — poussée verticale, verrouillez en haut."
            }

            ExerciseType.BICEP_CURL -> {
                val isAlternating = variant.usesUnilateralLimb
                val activeLeftCurl = if (isAlternating) (variant.preferredActiveLeft ?: (leftElbow <= rightElbow)) else useLeft
                val activeCurlElbow = if (isAlternating) minOf(leftElbow, rightElbow) else elbowAngle
                val activeCurlShAngle = if (isAlternating) (if (activeLeftCurl) leftShoulder else rightShoulder) else shoulderAngle
                val activeElIdx = if (isAlternating) (if (activeLeftCurl) LEFT_ELBOW else RIGHT_ELBOW) else elIdx
                val activeShIdx = if (activeElIdx == LEFT_ELBOW) LEFT_SHOULDER else RIGHT_SHOULDER
                val activeWrIdx = if (activeElIdx == LEFT_ELBOW) LEFT_WRIST else RIGHT_WRIST
                val activeElS = screenPts[activeElIdx]
                val activeShS = screenPts[activeShIdx]

                romPct = mapRange(activeCurlElbow, 155f, if (isStrict) 48f else 62f)
                val elbowDrift3D = use3D && activeCurlShAngle > 35f && activeCurlElbow < 110f
                val torsoLen2D = dist2D(shS, hpS).coerceAtLeast(0.08f)
                val elbowDrift2D = !use3D && abs(activeElS.x - activeShS.x) > torsoLen2D * 0.42f && activeCurlElbow < 105f
                if (elbowDrift3D || elbowDrift2D) {
                    val delta = (activeCurlShAngle - 25f).roundToInt().coerceAtLeast(8)
                    val pullDx = if (activeElS.x >= activeShS.x) -1f else 1f
                    rawIssues.add(
                        RawPostureIssue(
                            key = "CURL_ELBOW_DRIFT",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 24,
                            feedbackMsgFr = "Gardez le coude fixe près des côtes"
                        )
                    )
                }
                if (torsoTilt > 17f) {
                    val delta = (torsoTilt - 16f).roundToInt().coerceAtLeast(1)
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 6f)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "CURL_SWING",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 20,
                            feedbackMsgFr = "Ne balancez pas le buste en arrière"
                        )
                    )
                }

                val cycle = updateDownUpCycle(
                    metric = activeCurlElbow,
                    startThreshold = if (isStrict) 140f else 136f,
                    bottomThreshold = if (isStrict) 58f else 74f,
                    finishThreshold = if (isStrict) 148f else 136f,
                    currentRom = romPct,
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                recordCompletedCycle(cycle, if (isAlternating) activeLeftCurl else null)

                addSymmetryIssueIfNeeded(
                    leftElbow, rightElbow, lArmW, rArmW,
                    active = activeCurlElbow < 125f,
                    jointLabel = "bras",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeElIdx, activeElS.x, activeElS.y, activeCurlElbow.roundToInt(), FormQuality.GOOD,
                        parentIndex = activeShIdx,
                        childIndex = activeWrIdx,
                        targetRangeText = if (isStrict) "≤58°" else "≤74°"
                    )
                )
                baseFeedback = "Curl $detectedVariantFr (${activeCurlElbow.roundToInt()}°) — coude fixe, descente contrôlée."
            }

            ExerciseType.TRICEP_DIP -> {
                val bothArmsVis = lArmW >= 0.45f && rArmW >= 0.45f
                val dipElbowBottom = if (isStrict) 95f else 106f
                val dipElbowFinish = if (isStrict) 155f else 146f
                val dipElbowProgress = ((148f - elbowAngle) / (148f - dipElbowBottom).coerceAtLeast(10f)).coerceIn(0f, 1.25f)

                // Deadlock-free self-healing vertical shoulder drop relative to planted wrists
                val midWrYDip = if (bothArmsVis) (geomPts[LEFT_WRIST].y + geomPts[RIGHT_WRIST].y) * 0.5f else geomPts[wrIdx].y
                val midShYDip = if (bothArmsVis) (geomPts[LEFT_SHOULDER].y + geomPts[RIGHT_SHOULDER].y) * 0.5f else geomPts[shIdx].y
                val dipTorsoLen = if (use3D) {
                    dist3D(geomPts[shIdx], geomPts[hpIdx]).coerceAtLeast(0.28f)
                } else {
                    dist2D(shS, hpS).coerceAtLeast(0.10f)
                }
                val currentDipHeight = ((midWrYDip - midShYDip) / dipTorsoLen).coerceIn(0f, 1.25f)
                if (torsoTilt <= 48f && elbowAngle >= 142f && currentDipHeight in 0.14f..1.18f) {
                    adaptiveDipTopHeight = if (adaptiveDipTopHeight <= 0.01f) {
                        currentDipHeight
                    } else {
                        adaptiveDipTopHeight * 0.84f + currentDipHeight * 0.16f
                    }
                }
                val rawDipDropProgress = if (adaptiveDipTopHeight > 0.16f && torsoTilt <= 48f) {
                    ((adaptiveDipTopHeight - currentDipHeight) / (adaptiveDipTopHeight * 0.32f)).coerceIn(0f, 1.20f)
                } else {
                    0f
                }
                // Anti-deadlock rule: locked-out elbows (>= 150°) always exit BOTTOM cleanly
                val dipDropProgress = if (elbowAngle >= 150f) minOf(rawDipDropProgress, 0.16f) else rawDipDropProgress

                val dipDepth = if (isStrict) dipElbowProgress else maxOf(dipElbowProgress, dipDropProgress * 0.94f)
                romPct = (dipDepth * 100f).roundToInt().coerceIn(0, 100)
                var elbowHint: Int? = null
                val maxDipTilt = if (variant.exemptsTorsoLean) 45f else 38f
                if (torsoTilt > maxDipTilt) {
                    val delta = (torsoTilt - maxDipTilt).roundToInt().coerceAtLeast(1)
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 24f)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "DIP_TORSO",
                            correction = AngleCorrection(
                                jointIndex = shIdx,
                                jointLabelFr = "Buste",
                                currentDeg = torsoTilt.roundToInt(),
                                targetMinDeg = 0,
                                targetMaxDeg = maxDipTilt.roundToInt(),
                                deltaDeg = delta,
                                severity = FormQuality.CAUTION,
                                arrowDx = adx,
                                arrowDy = ady,
                                ghostAnchorIndex = hpIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "BUSTE -$delta°",
                                instructionFr = "Redressez le buste de $delta° (${torsoTilt.roundToInt()}° → ≤${maxDipTilt.roundToInt()}°)",
                                voiceCueFr = "Contrôlez l'inclinaison, épaules basses"
                            ),
                            scorePenalty = 15,
                            feedbackMsgFr = "Gardez le buste contrôlé, épaules abaissées"
                        )
                    )
                }

                if (use3D && worldPts[LEFT_EAR].visibility >= 0.45f && worldPts[RIGHT_EAR].visibility >= 0.45f) {
                    val earY = (worldPts[LEFT_EAR].y + worldPts[RIGHT_EAR].y) * 0.5f
                    val shY = (worldPts[LEFT_SHOULDER].y + worldPts[RIGHT_SHOULDER].y) * 0.5f
                    if (shY - earY < 0.08f) {
                        rawIssues.add(
                            RawPostureIssue(
                                key = "DIP_SHRUG",
                                correction = AngleCorrection(
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
                                ),
                                scorePenalty = 12
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
                        primaryPosMeters = primaryPosMeters,
                        instantSpeedMps = instantSpeedMps,
                        timestampMs = timestampMs
                    )
                } else {
                    updateProgressCycle(
                        progress = dipDepth,
                        startThreshold = 0.20f,
                        peakThreshold = 0.64f,
                        finishThreshold = 0.24f,
                        currentRom = romPct,
                        primaryPosMeters = primaryPosMeters,
                        instantSpeedMps = instantSpeedMps,
                        timestampMs = timestampMs
                    )
                }
                recordCompletedCycle(cycle)

                if (!reachedBottom && elbowAngle in (dipElbowBottom + 3f)..128f &&
                    shouldEmitDepthHint(elbowAngle, metricTolerance = 3.5f)
                ) {
                    val targetDeg = dipElbowBottom.roundToInt()
                    val delta = (elbowAngle - dipElbowBottom).roundToInt().coerceAtLeast(1)
                    elbowHint = targetDeg
                    rawIssues.add(
                        RawPostureIssue(
                            key = "DIP_DEPTH",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 4,
                            isDepthCue = true
                        )
                    )
                }

                addSymmetryIssueIfNeeded(
                    leftElbow, rightElbow, lArmW, rArmW,
                    active = elbowAngle < 140f,
                    jointLabel = "coudes",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        elIdx, elS.x, elS.y, elbowAngle.roundToInt(),
                        if (elbowHint != null) FormQuality.CAUTION else FormQuality.GOOD,
                        parentIndex = shIdx,
                        childIndex = wrIdx,
                        targetRangeText = "≤${dipElbowBottom.roundToInt()}°",
                        targetDegHint = elbowHint
                    )
                )
                baseFeedback = "Dips $detectedVariantFr — descendez contrôlé puis verrouillez."
            }

            ExerciseType.PLANK -> {
                val isSidePlank = variant.id == "SIDE"
                val isKneePlank = variant.usesKneeAlignment
                val isThreePoint = variant.id == "THREE_POINT"
                val groundedLeft = variant.preferredActiveLeft ?: useLeft

                val effectivePlankAlign = when {
                    isKneePlank -> {
                        val lka = bodyAlign(LEFT_SHOULDER, LEFT_HIP, LEFT_KNEE)
                        val rka = bodyAlign(RIGHT_SHOULDER, RIGHT_HIP, RIGHT_KNEE)
                        fuseSide(lka, rka, minOf(lTorsoW, lLegW), minOf(rTorsoW, rLegW), useLeft, bothVisible)
                    }
                    isThreePoint -> if (groundedLeft) leftAlign else rightAlign
                    else -> bodyLineAlign
                }
                val alignEndIdx = if (isKneePlank) knIdx else if (isThreePoint) (if (groundedLeft) LEFT_ANKLE else RIGHT_ANKLE) else anIdx
                val alignEndS = screenPts[alignEndIdx]

                val isHorizontal = if (use3D) {
                    fullBodyTilt >= (if (isStrict) 55f else 42f) || isSidePlank || (isKneePlank && torsoTilt >= 44f) || (!isStrict && frontFloorHorizontal)
                } else {
                    val classicHoriz = abs(shS.y - anS.y) < abs(shS.x - anS.x) * 0.78f
                    val bodyLen2D = dist2D(shS, anS).coerceAtLeast(0.05f)
                    val frontViewPlank = !isStrict &&
                        (frontFloorHorizontal ||
                            (abs(shS.y - anS.y) < maxOf(bodyLen2D * 0.35f, 0.28f) && abs(shS.y - hpS.y) < 0.16f))
                    classicHoriz || frontViewPlank || isSidePlank || isKneePlank
                }

                val validMin = if (isStrict) 160f else 155f
                val validMax = if (isStrict) 195f else 202f
                val aligned = isHorizontal && effectivePlankAlign in validMin..validMax
                var hipHint: Int? = null
                if (aligned) {
                    plankHoldMs += dtMs
                    isometricDeltaSec = dtMs / 1000f
                    phase = MovementPhase.HOLD
                    romPct = 100
                    val inOptimalGreen = effectivePlankAlign in 166f..194f
                    plankScoreOverride = if (inOptimalGreen) {
                        (98 - (abs(180f - effectivePlankAlign) * 0.7f).roundToInt()).coerceIn(84, 100)
                    } else {
                        (82 - (abs(180f - effectivePlankAlign) * 0.4f).roundToInt()).coerceIn(72, 81)
                    }
                    baseFeedback = if (inOptimalGreen) {
                        "Planche $detectedVariantFr parfaite (${effectivePlankAlign.roundToInt()}°) — maintenez !"
                    } else {
                        "Planche $detectedVariantFr validée (${effectivePlankAlign.roundToInt()}°) — affinez."
                    }
                    val sec = (plankHoldMs / 1000L).toInt()
                    if (sec > 0 && sec % 15 == 0 && (plankHoldMs - dtMs) / 1000L < sec) {
                        repVoiceCue = "$sec secondes, tenez bon !"
                    }
                } else {
                    phase = MovementPhase.PAUSE
                    romPct = 40
                    if (!isHorizontal) {
                        plankScoreOverride = 60
                        baseFeedback = "Mettez-vous en position planche horizontale."
                    } else if (effectivePlankAlign < validMin) {
                        val delta = (172f - effectivePlankAlign).roundToInt().coerceAtLeast(1)
                        plankScoreOverride = 52
                        hipHint = 175
                        val (gx, gy) = bodyLineGhost(shS, hpS, alignEndS)
                        rawIssues.add(
                            RawPostureIssue(
                                key = "PLANK_HIP_SAG",
                                correction = AngleCorrection(
                                    jointIndex = hpIdx,
                                    jointLabelFr = "Hanches",
                                    currentDeg = effectivePlankAlign.roundToInt(),
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
                                    instructionFr = "Remontez les hanches de $delta° (${effectivePlankAlign.roundToInt()}° → 166–194°)",
                                    voiceCueFr = "Remontez les hanches, bas du dos creusé"
                                ),
                                scorePenalty = 48,
                                feedbackMsgFr = "Remontez les hanches — bas du dos creusé"
                            )
                        )
                        baseFeedback = "Remontez les hanches — bas du dos creusé"
                    } else {
                        val delta = (effectivePlankAlign - 185f).roundToInt().coerceAtLeast(1)
                        plankScoreOverride = 58
                        hipHint = 180
                        val (gx, gy) = bodyLineGhost(shS, hpS, alignEndS)
                        rawIssues.add(
                            RawPostureIssue(
                                key = "PLANK_HIP_PIKE",
                                correction = AngleCorrection(
                                    jointIndex = hpIdx,
                                    jointLabelFr = "Hanches",
                                    currentDeg = effectivePlankAlign.roundToInt(),
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
                                    instructionFr = "Abaissez les hanches de $delta° (${effectivePlankAlign.roundToInt()}° → 166–194°)",
                                    voiceCueFr = "Abaissez les hanches"
                                ),
                                scorePenalty = 42,
                                feedbackMsgFr = "Abaissez les hanches — fesses trop hautes"
                            )
                        )
                        baseFeedback = "Abaissez les hanches — fesses trop hautes"
                    }
                }
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, effectivePlankAlign.roundToInt(),
                        if (aligned) FormQuality.GOOD else FormQuality.FAULT,
                        parentIndex = shIdx, childIndex = alignEndIdx, targetRangeText = "166–194°", targetDegHint = hipHint
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
                val shW2D = dist2D(screenPts[LEFT_SHOULDER], screenPts[RIGHT_SHOULDER]).coerceAtLeast(0.06f)
                val torsoLen2D = dist2D(shS, hpS)
                val bodyHorizontal = if (use3D) {
                    torsoTilt >= (if (isStrict) 55f else 44f) || (!isStrict && frontFloorHorizontal)
                } else {
                    abs(shS.y - hpS.y) < abs(shS.x - hpS.x) * 0.65f ||
                        (!isStrict && frontFloorHorizontal) ||
                        (torsoLen2D < shW2D * 0.95f && abs(shS.y - anS.y) < shW2D * 1.9f)
                }
                val standAngleThresh = if (isStrict) 155f else 145f
                val standingTall = hipAngle > standAngleThresh && kneeAngle > standAngleThresh && shS.y < hpS.y
                when (burpeeStage) {
                    0 -> {
                        phase = if (kneeAngle < 130f) MovementPhase.ECCENTRIC else MovementPhase.READY
                        romPct = if (bodyHorizontal) 75 else mapRange(kneeAngle, 165f, 90f) / 2
                        if (kneeAngle < 130f && repStartTsMs == 0L) {
                            repStartTsMs = timestampMs
                            phase1StartPosMeters = primaryPosMeters
                            phase1PeakVelMps = 0f
                        }
                        if (phase == MovementPhase.ECCENTRIC && instantSpeedMps > phase1PeakVelMps) {
                            phase1PeakVelMps = instantSpeedMps
                        }
                        if (bodyHorizontal) {
                            burpeeStage = 1
                            burpeeBottomEnteredTsMs = timestampMs
                            concentricLeftBottomTsMs = 0L
                            phase1EndPosMeters = primaryPosMeters
                            phase2StartPosMeters = primaryPosMeters
                            phase2PeakVelMps = 0f
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
                                val totalDur = computeRepDurationSec(timestampMs)
                                val concStart = if (concentricLeftBottomTsMs > 0L) concentricLeftBottomTsMs else burpeeBottomEnteredTsMs
                                val concDur = ((timestampMs - concStart) / 1000f).coerceIn(0.25f, totalDur)
                                val eccDur = ((burpeeBottomEnteredTsMs - repStartTsMs) / 1000f).coerceIn(0.20f, totalDur)
                                val disp2 = if (phase2StartPosMeters.isFinite()) abs(primaryPosMeters - phase2StartPosMeters) else 0.75f
                                val disp1 = if (phase1StartPosMeters.isFinite() && phase1EndPosMeters.isFinite()) abs(phase1EndPosMeters - phase1StartPosMeters) else 0.75f
                                recordCompletedCycle(
                                    CycleResult(
                                        completed = true,
                                        completedRom = 100,
                                        durationSec = totalDur,
                                        phase1DurationSec = eccDur,
                                        phase2DurationSec = concDur,
                                        phase1DispMeters = disp1,
                                        phase2DispMeters = disp2,
                                        phase1PeakVelMps = phase1PeakVelMps,
                                        phase2PeakVelMps = phase2PeakVelMps
                                    )
                                )
                                repVoiceCue = "$repCount"
                            }
                            repStartTsMs = 0L
                            burpeeBottomEnteredTsMs = 0L
                            concentricLeftBottomTsMs = 0L
                            burpeeStage = 0
                            phase = MovementPhase.READY
                        } else if (!bodyHorizontal && shS.y < hpS.y) {
                            if (dwellMs >= MIN_BOTTOM_DWELL_MS) {
                                if (concentricLeftBottomTsMs == 0L) {
                                    concentricLeftBottomTsMs = timestampMs
                                    phase2StartPosMeters = primaryPosMeters
                                    phase2PeakVelMps = 0f
                                }
                                if (instantSpeedMps > phase2PeakVelMps) phase2PeakVelMps = instantSpeedMps
                                velocityEstimator.updateLiveVelocity(instantSpeedMps)
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
                    val (gx, gy) = bodyLineGhost(shS, hpS, anS)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "BURPEE_SAG",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 22,
                            feedbackMsgFr = "Gainez le bassin en position planche"
                        )
                    )
                }
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, hipAngle.roundToInt(), FormQuality.GOOD,
                        parentIndex = shIdx, childIndex = knIdx, targetRangeText = "160–180°"
                    )
                )
                baseFeedback = when (burpeeStage) {
                    1 -> "Burpee $detectedVariantFr — remontez explosif !"
                    else -> "Burpee $detectedVariantFr — descendez en planche gainée."
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

                if (anklesClearlyVisible && armFullAngle > 130f && !legsSpread && variant.id != "LOW_IMPACT") {
                    rawIssues.add(
                        RawPostureIssue(
                            key = "JJ_LEGS",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 14,
                            feedbackMsgFr = "Écartez davantage les jambes en synchronisant"
                        )
                    )
                }
                val requireLegsGate = isStrict && anklesClearlyVisible
                val cycle = updateUpDownCycle(
                    metric = armFullAngle,
                    startThreshold = if (isStrict) 45f else 46f,
                    topThreshold = if (isStrict) 138f else 128f,
                    finishThreshold = if (isStrict) 42f else 48f,
                    extraTopCondition = if (requireLegsGate) legsSpread else true,
                    currentRom = romPct,
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                recordCompletedCycle(cycle)

                addSymmetryIssueIfNeeded(
                    leftArmFull, rightArmFull, lArmW, rArmW,
                    active = armFullAngle > 60f,
                    jointLabel = "bras",
                    leftJointIdx = LEFT_SHOULDER,
                    rightJointIdx = RIGHT_SHOULDER
                )

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        shIdx, shS.x, shS.y, armFullAngle.roundToInt(), FormQuality.GOOD,
                        parentIndex = hpIdx, childIndex = wrIdx, targetRangeText = if (isStrict) "≥138°" else "≥128°"
                    )
                )
                baseFeedback = "Jumping Jack $detectedVariantFr — rythme fluide !"
            }

            ExerciseType.PULL_UP -> {
                val isArcher = variant.id == "ARCHER"
                val activeLeft = if (isArcher) (variant.preferredActiveLeft ?: (leftElbow <= rightElbow)) else useLeft
                val activePullElbow = if (isArcher) minOf(leftElbow, rightElbow) else elbowAngle
                val activeElIdx = if (isArcher) (if (activeLeft) LEFT_ELBOW else RIGHT_ELBOW) else elIdx
                val activeShIdx = if (activeElIdx == LEFT_ELBOW) LEFT_SHOULDER else RIGHT_SHOULDER
                val activeWrIdx = if (activeElIdx == LEFT_ELBOW) LEFT_WRIST else RIGHT_WRIST
                val activeElS = screenPts[activeElIdx]

                val noseY = geomPts[NOSE].y
                val midWristY = (geomPts[LEFT_WRIST].y + geomPts[RIGHT_WRIST].y) * 0.5f
                val chinMargin = if (use3D) 0.06f else 0.035f
                val chinOverHands = noseY < midWristY + chinMargin
                val bottomThresh = if (isStrict) 74f else 92f
                val finishThresh = if (isStrict) 155f else 142f
                val startThresh = if (isStrict) 148f else 140f

                val effectivePullMetric = if (chinOverHands && activePullElbow < 122f) {
                    minOf(activePullElbow, bottomThresh - 2f)
                } else {
                    activePullElbow
                }
                romPct = maxOf(
                    mapRange(activePullElbow, 160f, if (isStrict) 58f else 76f),
                    if (chinOverHands && activePullElbow < 122f) 96 else 0
                )

                val cycle = updateDownUpCycle(
                    metric = effectivePullMetric,
                    startThreshold = startThresh,
                    bottomThreshold = bottomThresh,
                    finishThreshold = finishThresh,
                    currentRom = romPct,
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                recordCompletedCycle(cycle, if (isArcher) activeLeft else null)

                if (reachedBottom && !chinOverHands && activePullElbow < bottomThresh + 12f &&
                    shouldEmitDepthHint(activePullElbow, metricTolerance = 3.5f)
                ) {
                    val delta = (activePullElbow - 62f).roundToInt().coerceAtLeast(8)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "PULLUP_CHIN",
                            correction = AngleCorrection(
                                jointIndex = shIdx,
                                jointLabelFr = "Tirage",
                                currentDeg = activePullElbow.roundToInt(),
                                targetMinDeg = 45,
                                targetMaxDeg = bottomThresh.roundToInt(),
                                deltaDeg = delta,
                                severity = FormQuality.CAUTION,
                                arrowDx = 0f,
                                arrowDy = -1f,
                                shortBadgeFr = "TIREZ +HAUT",
                                instructionFr = "Tirez le menton au-dessus des mains (encore -$delta° coude)",
                                voiceCueFr = "Tirez la poitrine plus haut"
                            ),
                            scorePenalty = 15,
                            feedbackMsgFr = "Tirez la poitrine plus haut vers la barre",
                            isDepthCue = true
                        )
                    )
                }

                addSymmetryIssueIfNeeded(
                    leftElbow, rightElbow, lArmW, rArmW,
                    active = activePullElbow < 135f,
                    jointLabel = "bras",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeElIdx, activeElS.x, activeElS.y, activePullElbow.roundToInt(), FormQuality.GOOD,
                        parentIndex = activeShIdx, childIndex = activeWrIdx, targetRangeText = "≤${bottomThresh.roundToInt()}°"
                    )
                )
                baseFeedback = "Tractions ($detectedVariantFr) — coudes vers les hanches."
            }

            ExerciseType.BENT_ROW -> {
                val isUnilateral = variant.usesUnilateralLimb
                val activeLeftRow = if (isUnilateral) (variant.preferredActiveLeft ?: (leftElbow <= rightElbow)) else useLeft
                val activeRowElbow = if (isUnilateral) minOf(leftElbow, rightElbow) else elbowAngle
                val activeElIdx = if (isUnilateral) (if (activeLeftRow) LEFT_ELBOW else RIGHT_ELBOW) else elIdx
                val activeShIdx = if (activeElIdx == LEFT_ELBOW) LEFT_SHOULDER else RIGHT_SHOULDER
                val activeWrIdx = if (activeElIdx == LEFT_ELBOW) LEFT_WRIST else RIGHT_WRIST
                val activeElS = screenPts[activeElIdx]

                romPct = mapRange(activeRowElbow, 155f, if (isStrict) 72f else 88f)
                var torsoHint: Int? = null
                val maxRowTilt = if (variant.exemptsTorsoLean) 88f else 80f
                if (torsoTilt < 24f) {
                    val delta = (40f - torsoTilt).roundToInt().coerceAtLeast(1)
                    torsoHint = 45
                    val (gx, gy) = hingeTorsoGhost(hpS, shS, 45f)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "ROW_TOO_UPRIGHT",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 22,
                            feedbackMsgFr = "Penchez davantage le buste (35–55°)"
                        )
                    )
                } else if (torsoTilt > maxRowTilt) {
                    val delta = (torsoTilt - 55f).roundToInt().coerceAtLeast(1)
                    torsoHint = 50
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 50f)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "ROW_TOO_LOW",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 16,
                            feedbackMsgFr = "Relevez légèrement le buste, dos plat"
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
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                recordCompletedCycle(cycle, if (isUnilateral) activeLeftRow else null)

                addSymmetryIssueIfNeeded(
                    leftElbow, rightElbow, lArmW, rArmW,
                    active = activeRowElbow < 135f,
                    jointLabel = "tirage",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeElIdx, activeElS.x, activeElS.y, activeRowElbow.roundToInt(), FormQuality.GOOD,
                        parentIndex = activeShIdx,
                        childIndex = activeWrIdx,
                        targetRangeText = "≤${rowBottomThresh.roundToInt()}°"
                    )
                )
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, torsoTilt.roundToInt(),
                        if (torsoTilt in 24f..maxRowTilt) FormQuality.GOOD else FormQuality.FAULT,
                        parentIndex = shIdx, childIndex = knIdx, targetRangeText = "35–55°", targetDegHint = torsoHint
                    )
                )
                baseFeedback = "Rowing $detectedVariantFr (${torsoTilt.roundToInt()}°) — serrez les omoplates."
            }

            ExerciseType.GLUTE_BRIDGE -> {
                val isSingleLeg = variant.usesUnilateralLimb
                val groundedLeft = variant.preferredActiveLeft ?: useLeft
                val activeHip = if (isSingleLeg) (if (groundedLeft) leftHip else rightHip) else hipAngle
                val activeHpIdx = if (isSingleLeg) (if (groundedLeft) LEFT_HIP else RIGHT_HIP) else hpIdx
                val activeKnIdx = if (isSingleLeg) (if (groundedLeft) LEFT_KNEE else RIGHT_KNEE) else knIdx
                val activeHpS = screenPts[activeHpIdx]

                romPct = mapRange(activeHip, if (isStrict) 115f else 128f, if (isStrict) 172f else 164f)
                var hipHint: Int? = null
                val supineAlign = if (use3D) {
                    bodyLineAlignment3D(geomPts[shIdx], geomPts[activeHpIdx], geomPts[activeKnIdx])
                } else {
                    bodyLineAlignmentDegrees(shS, activeHpS, screenPts[activeKnIdx])
                }
                if (supineAlign > 194f) {
                    val cur = supineAlign.roundToInt()
                    val delta = (cur - 180).coerceAtLeast(6)
                    hipHint = 175
                    rawIssues.add(
                        RawPostureIssue(
                            key = "BRIDGE_HYPEREXT",
                            correction = AngleCorrection(
                                jointIndex = activeHpIdx,
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
                            ),
                            scorePenalty = 20,
                            feedbackMsgFr = "Ne cambrez pas les lombaires en haut"
                        )
                    )
                }

                val bridgeTopThresh = if (isStrict) 162f else 156f
                val cycle = updateUpDownCycle(
                    metric = activeHip,
                    startThreshold = if (isStrict) 128f else 136f,
                    topThreshold = bridgeTopThresh,
                    finishThreshold = if (isStrict) 125f else 136f,
                    extraTopCondition = true,
                    currentRom = romPct,
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                recordCompletedCycle(cycle, if (isSingleLeg) groundedLeft else null)

                if (!reachedBottom && activeHip in 138f..(bridgeTopThresh - 2f) &&
                    shouldEmitDepthHint(activeHip, metricTolerance = 3.0f)
                ) {
                    val targetDeg = bridgeTopThresh.roundToInt()
                    val delta = (bridgeTopThresh - activeHip).roundToInt().coerceAtLeast(1)
                    hipHint = targetDeg
                    rawIssues.add(
                        RawPostureIssue(
                            key = "BRIDGE_HEIGHT",
                            correction = AngleCorrection(
                                jointIndex = activeHpIdx,
                                jointLabelFr = "Hanches",
                                currentDeg = activeHip.roundToInt(),
                                targetMinDeg = targetDeg,
                                targetMaxDeg = 180,
                                deltaDeg = delta,
                                severity = FormQuality.CAUTION,
                                arrowDx = 0f,
                                arrowDy = -1f,
                                shortBadgeFr = "HANCHES +$delta°",
                                instructionFr = "Montez encore les hanches (+$delta° : ${activeHip.roundToInt()}° → ≥$targetDeg°)",
                                voiceCueFr = "Montez les hanches plus haut"
                            ),
                            scorePenalty = 4,
                            isDepthCue = true
                        )
                    )
                }

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeHpIdx, activeHpS.x, activeHpS.y, activeHip.roundToInt(), FormQuality.GOOD,
                        parentIndex = shIdx, childIndex = activeKnIdx, targetRangeText = "≥${bridgeTopThresh.roundToInt()}°", targetDegHint = hipHint
                    )
                )
                baseFeedback = "Pont fessier $detectedVariantFr (${activeHip.roundToInt()}°) — serrez en haut."
            }

            ExerciseType.LEG_RAISE -> {
                val isAlternating = variant.usesUnilateralLimb
                val isBentKneeVariant = variant.id == "BENT_KNEE"
                val activeLeft = variant.preferredActiveLeft ?: useLeft
                val activeHip = if (isAlternating) minOf(leftHip, rightHip) else hipAngle
                val activeKnee = if (isAlternating) (if (activeLeft) leftKnee else rightKnee) else kneeAngle
                val activeHpIdx = if (isAlternating) (if (activeLeft) LEFT_HIP else RIGHT_HIP) else hpIdx
                val activeKnIdx = if (isAlternating) (if (activeLeft) LEFT_KNEE else RIGHT_KNEE) else knIdx
                val activeAnIdx = if (isAlternating) (if (activeLeft) LEFT_ANKLE else RIGHT_ANKLE) else anIdx
                val activeHpS = screenPts[activeHpIdx]
                val activeKnS = screenPts[activeKnIdx]
                val activeAnS = screenPts[activeAnIdx]

                romPct = mapRange(activeHip, if (isStrict) 168f else 156f, if (isStrict) 92f else 102f)
                var kneeHint: Int? = null
                if (activeKnee < 135f && (isStrict || !isBentKneeVariant)) {
                    val delta = (155f - activeKnee).roundToInt().coerceAtLeast(1)
                    kneeHint = 155
                    val (gx, gy) = straightenLimbGhost(activeHpS, activeKnS, activeAnS)
                    val dx = gx - activeAnS.x
                    val dy = gy - activeAnS.y
                    val n = hypot(dx, dy).coerceAtLeast(0.001f)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "LEGRAISE_KNEE",
                            correction = AngleCorrection(
                                jointIndex = activeKnIdx,
                                jointLabelFr = "Genoux",
                                currentDeg = activeKnee.roundToInt(),
                                targetMinDeg = 150,
                                targetMaxDeg = 180,
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = dx / n,
                                arrowDy = dy / n,
                                ghostAnchorIndex = activeKnIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "GENOU +$delta°",
                                instructionFr = "Tendez davantage les jambes (+$delta° : ${activeKnee.roundToInt()}° → ≥150°)",
                                voiceCueFr = "Tendez davantage les jambes"
                            ),
                            scorePenalty = 18,
                            feedbackMsgFr = "Gardez les jambes plus tendues"
                        )
                    )
                }

                val legBottomThresh = if (isStrict) 104f else 112f
                val cycle = updateDownUpCycle(
                    metric = activeHip,
                    startThreshold = if (isStrict) 152f else 145f,
                    bottomThreshold = legBottomThresh,
                    finishThreshold = if (isStrict) 156f else 145f,
                    currentRom = romPct,
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                recordCompletedCycle(cycle, if (isAlternating) activeLeft else null)

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeHpIdx, activeHpS.x, activeHpS.y, activeHip.roundToInt(), FormQuality.GOOD,
                        parentIndex = shIdx, childIndex = activeKnIdx, targetRangeText = "≤${legBottomThresh.roundToInt()}°"
                    )
                )
                badges.add(
                    JointAngleBadge(
                        activeKnIdx, activeKnS.x, activeKnS.y, activeKnee.roundToInt(),
                        if (kneeHint != null) FormQuality.FAULT else FormQuality.GOOD,
                        parentIndex = activeHpIdx, childIndex = activeAnIdx, targetRangeText = if (isBentKneeVariant) "Fléchis" else "≥150°", targetDegHint = kneeHint
                    )
                )
                baseFeedback = "Relevé de jambes ($detectedVariantFr) — descente lente et contrôlée."
            }

            ExerciseType.LATERAL_RAISE -> {
                val isUnilateral = variant.usesUnilateralLimb
                val activeLeft = variant.preferredActiveLeft ?: useLeft
                val activeShoulder = if (isUnilateral) maxOf(leftShoulder, rightShoulder) else shoulderAngle
                val activeShIdx = if (isUnilateral) (if (activeLeft) LEFT_SHOULDER else RIGHT_SHOULDER) else shIdx
                val activeElIdx = if (isUnilateral) (if (activeLeft) LEFT_ELBOW else RIGHT_ELBOW) else elIdx
                val activeHpIdx = if (isUnilateral) (if (activeLeft) LEFT_HIP else RIGHT_HIP) else hpIdx
                val activeShS = screenPts[activeShIdx]

                romPct = mapRange(activeShoulder, if (isStrict) 15f else 24f, if (isStrict) 90f else 84f)
                var shHint: Int? = null
                if (activeShoulder > 108f) {
                    val delta = (activeShoulder - 92f).roundToInt().coerceAtLeast(1)
                    shHint = 90
                    rawIssues.add(
                        RawPostureIssue(
                            key = "LATRAISE_HIGH",
                            correction = AngleCorrection(
                                jointIndex = activeShIdx,
                                jointLabelFr = "Épaule",
                                currentDeg = activeShoulder.roundToInt(),
                                targetMinDeg = 80,
                                targetMaxDeg = 95,
                                deltaDeg = delta,
                                severity = FormQuality.CAUTION,
                                arrowDx = 0f,
                                arrowDy = 1f,
                                shortBadgeFr = "ÉPAULE -$delta°",
                                instructionFr = "Stoppez à 90° (-$delta° : ${activeShoulder.roundToInt()}° → 85–95°)",
                                voiceCueFr = "Stoppez à 90 degrés, épaules basses"
                            ),
                            scorePenalty = 18,
                            feedbackMsgFr = "Stoppez à l'horizontale (90°) sans hausser les trapèzes"
                        )
                    )
                }
                if (!variant.exemptsTorsoLean && torsoTilt > 17f) {
                    val delta = (torsoTilt - 15f).roundToInt().coerceAtLeast(1)
                    val (gx, gy, adx, ady) = uprightTorsoGhost(hpS, shS, 6f)
                    rawIssues.add(
                        RawPostureIssue(
                            key = "LATRAISE_SWING",
                            correction = AngleCorrection(
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
                            ),
                            scorePenalty = 20,
                            feedbackMsgFr = "Ne balancez pas le buste"
                        )
                    )
                }

                val latTopThresh = if (isStrict) 78f else 72f
                val cycle = updateUpDownCycle(
                    metric = activeShoulder,
                    startThreshold = if (isStrict) 28f else 34f,
                    topThreshold = latTopThresh,
                    finishThreshold = if (isStrict) 26f else 34f,
                    extraTopCondition = true,
                    currentRom = romPct,
                    primaryPosMeters = primaryPosMeters,
                    instantSpeedMps = instantSpeedMps,
                    timestampMs = timestampMs
                )
                recordCompletedCycle(cycle, if (isUnilateral) activeLeft else null)

                addSymmetryIssueIfNeeded(
                    leftShoulder, rightShoulder, lArmW, rArmW,
                    active = activeShoulder > 38f,
                    jointLabel = "épaules",
                    leftJointIdx = LEFT_SHOULDER,
                    rightJointIdx = RIGHT_SHOULDER
                )

                if (repCompletedNow) repVoiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        activeShIdx, activeShS.x, activeShS.y, activeShoulder.roundToInt(), FormQuality.GOOD,
                        parentIndex = activeHpIdx, childIndex = activeElIdx, targetRangeText = "${latTopThresh.roundToInt()}–95°", targetDegHint = shHint
                    )
                )
                baseFeedback = "Élévations $detectedVariantFr (${activeShoulder.roundToInt()}°), épaules basses."
            }
        }

        // Apply Pro Anti-Spam Posture Coaching Gate (temporal persistence + display hold + voice cooldowns!)
        val postureResult = postureCoach.evaluate(
            rawIssues = rawIssues,
            timestampMs = timestampMs,
            repCount = repCount,
            isIsometric = currentExercise.isIsometric,
            deterministicPassThrough = isDeterministicPassThrough
        )

        val clampedScore = (plankScoreOverride ?: (100 - postureResult.totalScorePenalty)).coerceIn(0, 100)
        formScoreSum += clampedScore
        formScoreSamples++

        for (confirmedMsg in postureResult.newlyConfirmedFaultMessages) {
            faultCounts[confirmedMsg] = (faultCounts[confirmedMsg] ?: 0) + 1
        }

        val finalFeedback = postureResult.primaryFaultMsgFr
            ?: postureResult.confirmedCorrections.firstOrNull()?.instructionFr
            ?: baseFeedback
        val finalVoiceCue = repVoiceCue ?: postureResult.postureVoiceCueFr

        // Refresh angle badge colors to reflect ONLY confirmed (non-spam!) posture quality
        val confirmedByJoint = postureResult.confirmedCorrections.associateBy { it.jointIndex }
        val finalBadges = badges.map { b ->
            val confirmedCorr = confirmedByJoint[b.landmarkIndex]
            when {
                confirmedCorr != null -> b.copy(quality = confirmedCorr.severity)
                b.quality == FormQuality.FAULT && postureResult.confirmedCorrections.none { it.severity == FormQuality.FAULT } ->
                    b.copy(quality = FormQuality.GOOD, targetDegHint = null)
                b.quality == FormQuality.CAUTION && postureResult.confirmedCorrections.isEmpty() ->
                    b.copy(quality = FormQuality.GOOD, targetDegHint = null)
                else -> b
            }
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
            feedbackFr = finalFeedback,
            voiceCueFr = finalVoiceCue,
            angleBadges = finalBadges,
            avgFormScore = computeAvgForm(),
            avgRomPct = computeAvgRom(),
            topFaults = computeTopFaults(),
            poseDetected = true,
            worldLandmarks = worldPts,
            viewpointInfo = viewpointInfo,
            corrections = postureResult.confirmedCorrections,
            repCompletedNow = repCompletedNow,
            completedRepRomPct = completedRepRom,
            completedRepDurationSec = completedRepDurSec,
            isometricDeltaSec = isometricDeltaSec,
            detectedVariantFr = detectedVariantFr,
            detectedVariantId = variant.id,
            velocityTelemetry = velocityEstimator.currentTelemetry(),
            completedRepVelocityMps = completedRepVelMps
        )
    }

    /**
     * Evaluates viewpoint-invariant physical posture sanity gates without breaking Front/3/4/Rear/Incline/Decline camera setups.
     */
    private fun evaluatePostureGate(
        exercise: ExerciseType,
        use3D: Boolean,
        bothVisible: Boolean,
        useLeft: Boolean,
        geomPts: List<PosePoint>,
        screenPts: List<PosePoint>,
        torsoTilt: Float,
        fullBodyTilt: Float,
        lArmW: Float,
        rArmW: Float,
        frontFloorHorizontal: Boolean,
        activeWrSY: Float,
        activeShSY: Float,
        midShSAll: PosePoint,
        torsoVert2DGlobal: Float,
        shWidth2DGlobal: Float
    ): String? {
        val shIdx = if (useLeft) LEFT_SHOULDER else RIGHT_SHOULDER
        val wrIdx = if (useLeft) LEFT_WRIST else RIGHT_WRIST
        val hpIdx = if (useLeft) LEFT_HIP else RIGHT_HIP
        val anIdx = if (useLeft) LEFT_ANKLE else RIGHT_ANKLE
        val bothArmsVis = lArmW >= 0.40f && rArmW >= 0.40f

        val midShG = if (bothVisible) midpoint(geomPts[LEFT_SHOULDER], geomPts[RIGHT_SHOULDER]) else geomPts[shIdx]
        val midHpG = if (bothVisible) midpoint(geomPts[LEFT_HIP], geomPts[RIGHT_HIP]) else geomPts[hpIdx]
        val midWrG = if (bothArmsVis) midpoint(geomPts[LEFT_WRIST], geomPts[RIGHT_WRIST]) else geomPts[wrIdx]
        val torsoLenG = (if (use3D) dist3D(midShG, midHpG) else dist2D(midShG, midHpG)).coerceAtLeast(if (use3D) 0.25f else 0.08f)

        return when (exercise) {
            ExerciseType.PUSHUP -> {
                if (use3D) {
                    val bodyInclinedOrProne = fullBodyTilt >= 22f || torsoTilt >= 22f || frontFloorHorizontal
                    val wristsNotHighOverhead = (midShG.y - midWrG.y) / torsoLenG <= 0.28f ||
                        activeWrSY >= activeShSY - 0.12f
                    if (!bodyInclinedOrProne || !wristsNotHighOverhead) {
                        "Mettez-vous en position pompes (sol ou inclinées)."
                    } else null
                } else {
                    val shW2D = dist2D(screenPts[LEFT_SHOULDER], screenPts[RIGHT_SHOULDER]).coerceAtLeast(0.05f)
                    val torso2D = dist2D(screenPts[shIdx], screenPts[hpIdx])
                    val bodyHeight2D = abs(screenPts[shIdx].y - screenPts[anIdx].y)
                    val frontForeshortenedProne = frontFloorHorizontal || (torso2D < shW2D * 1.15f && bodyHeight2D < shW2D * 2.35f)
                    val bodyOk = fullBodyTilt >= 22f || torsoTilt >= 22f || frontForeshortenedProne
                    val wristsNotWayAboveHead = activeWrSY >= activeShSY - 0.14f
                    if (!bodyOk || !wristsNotWayAboveHead) {
                        "Mettez-vous en position pompes (sol ou inclinées)."
                    } else null
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
                } else null
            }
            ExerciseType.GLUTE_BRIDGE -> {
                val frontFloorSupine = (torsoVert2DGlobal / shWidth2DGlobal) < 0.85f && midShSAll.y >= 0.48f
                if (torsoTilt < 32f && !frontFloorSupine) {
                    "Allongez-vous sur le dos pour cet exercice."
                } else null
            }
            ExerciseType.LEG_RAISE -> {
                // Allow BOTH lying supine floor leg raises AND hanging leg raises (wrists clearly overhead)!
                val wristsOverhead = (midShG.y - midWrG.y) / torsoLenG >= 0.30f
                val frontFloorSupine = (torsoVert2DGlobal / shWidth2DGlobal) < 0.85f && midShSAll.y >= 0.48f
                if (torsoTilt < 32f && !wristsOverhead && !frontFloorSupine) {
                    "Allongez-vous sur le dos (ou suspendez-vous à la barre)."
                } else null
            }
            ExerciseType.PULL_UP -> {
                // Prevent counting fake pull-ups when standing with hands relaxed down at waist
                val minWrY = minOf(geomPts[LEFT_WRIST].y, geomPts[RIGHT_WRIST].y)
                val shY = midShG.y
                val handsNearOrAboveShoulders = (shY - minWrY) / torsoLenG >= -0.22f
                if (!isMidRep && !handsNearOrAboveShoulders) {
                    "Saisissez la barre bras au-dessus des épaules."
                } else null
            }
            else -> null
        }
    }

    /**
     * Computes the primary moving landmark height/coordinate in meters (positive = upward against gravity)
     * using true 3D world meters when active or athlete-height-calibrated 2D scale in 2D fallback.
     */
    private fun computePrimaryMoverPositionMeters(
        exercise: ExerciseType,
        geomPts: List<PosePoint>,
        screenPts: List<PosePoint>,
        use3D: Boolean,
        bothVisible: Boolean,
        useLeft: Boolean
    ): Float {
        val shIdx = if (useLeft) LEFT_SHOULDER else RIGHT_SHOULDER
        val wrIdx = if (useLeft) LEFT_WRIST else RIGHT_WRIST
        val hpIdx = if (useLeft) LEFT_HIP else RIGHT_HIP
        val anIdx = if (useLeft) LEFT_ANKLE else RIGHT_ANKLE

        val midSh = if (bothVisible) midpoint(geomPts[LEFT_SHOULDER], geomPts[RIGHT_SHOULDER]) else geomPts[shIdx]
        val midHp = if (bothVisible) midpoint(geomPts[LEFT_HIP], geomPts[RIGHT_HIP]) else geomPts[hpIdx]
        val midWr = if (bothVisible) midpoint(geomPts[LEFT_WRIST], geomPts[RIGHT_WRIST]) else geomPts[wrIdx]
        val midAn = if (bothVisible) midpoint(geomPts[LEFT_ANKLE], geomPts[RIGHT_ANKLE]) else geomPts[anIdx]

        // Scale factor converts normalized 2D coords to meters via anatomical torso length (~29% of stature)
        val scaleToMeters = if (use3D) {
            1.0f
        } else {
            val shS = if (bothVisible) midpoint(screenPts[LEFT_SHOULDER], screenPts[RIGHT_SHOULDER]) else screenPts[shIdx]
            val hpS = if (bothVisible) midpoint(screenPts[LEFT_HIP], screenPts[RIGHT_HIP]) else screenPts[hpIdx]
            val shW2D = dist2D(screenPts[LEFT_SHOULDER], screenPts[RIGHT_SHOULDER])
            val ref2D = maxOf(dist2D(shS, hpS), shW2D * 1.35f).coerceAtLeast(0.08f)
            (athleteHeightMeters * 0.29f) / ref2D
        }

        // In MediaPipe, +Y points DOWN toward floor, so upward height = (lowerAnchor.y - movingPoint.y)
        val rawCoord = when (exercise) {
            ExerciseType.SQUAT,
            ExerciseType.LUNGE,
            ExerciseType.BULGARIAN_SQUAT,
            ExerciseType.DEADLIFT,
            ExerciseType.BURPEE -> (midAn.y - midHp.y)
            ExerciseType.PUSHUP,
            ExerciseType.TRICEP_DIP -> (midWr.y - midSh.y)
            ExerciseType.PULL_UP -> (midWr.y - midSh.y) * -1f // pulling shoulders UP toward anchored wrists
            ExerciseType.OVERHEAD_PRESS,
            ExerciseType.LATERAL_RAISE,
            ExerciseType.JUMPING_JACK,
            ExerciseType.BICEP_CURL,
            ExerciseType.BENT_ROW -> (midHp.y - midWr.y)
            ExerciseType.GLUTE_BRIDGE -> (midSh.y - midHp.y)
            ExerciseType.LEG_RAISE -> (midHp.y - midAn.y)
            ExerciseType.PLANK -> 0f
        }
        return rawCoord * scaleToMeters
    }

    private data class CycleResult(
        val completed: Boolean,
        val completedRom: Int,
        val durationSec: Float,
        val phase1DurationSec: Float = 0f,
        val phase2DurationSec: Float = 0f,
        val phase1DispMeters: Float = 0f,
        val phase2DispMeters: Float = 0f,
        val phase1PeakVelMps: Float = 0f,
        val phase2PeakVelMps: Float = 0f
    )

    private fun requiredBottomDwellMs(): Long =
        if (enteredBottomFromRamp) FAST_TOUCH_BOTTOM_DWELL_MS else MIN_BOTTOM_DWELL_MS

    private fun minRepDurationMs(): Long = when {
        emaAlpha >= 0.999f && !useOneEuroFilter -> 180L
        enteredBottomFromRamp -> 180L
        currentExercise == ExerciseType.JUMPING_JACK -> 240L
        else -> 360L
    }

    private fun completeRepOrAbort(
        timestampMs: Long,
        checkBottomDwell: Boolean,
        currentPosMeters: Float
    ): CycleResult {
        val dwellOk = !checkBottomDwell ||
            (bottomEnteredTsMs > 0L && (timestampMs - bottomEnteredTsMs) >= requiredBottomDwellMs())
        val durOk = repStartTsMs > 0L && (timestampMs - repStartTsMs) >= minRepDurationMs()
        if (dwellOk && durOk) {
            val peakRom = repRomPeak
            val totalDurSec = computeRepDurationSec(timestampMs)
            val p1EndTs = if (bottomEnteredTsMs > repStartTsMs) bottomEnteredTsMs else repStartTsMs + ((timestampMs - repStartTsMs) / 2L)
            val p2StartTs = if (concentricLeftBottomTsMs >= p1EndTs) concentricLeftBottomTsMs else p1EndTs

            val p1Dur = ((p1EndTs - repStartTsMs) / 1000f).coerceIn(0.18f, totalDurSec)
            val p2Dur = ((timestampMs - p2StartTs) / 1000f).coerceIn(0.18f, totalDurSec)
            val p1Disp = if (phase1StartPosMeters.isFinite() && phase1EndPosMeters.isFinite()) {
                abs(phase1EndPosMeters - phase1StartPosMeters)
            } else 0f
            val p2Disp = if (phase2StartPosMeters.isFinite()) {
                abs(currentPosMeters - phase2StartPosMeters)
            } else p1Disp
            val p1Peak = phase1PeakVelMps
            val p2Peak = phase2PeakVelMps

            repCount++
            recordRom(peakRom)
            reachedBottom = true // avoid triggering early-reversal hint on a valid completed rep!
            abortIncompleteRepCycle()
            return CycleResult(
                completed = true,
                completedRom = peakRom,
                durationSec = totalDurSec,
                phase1DurationSec = p1Dur,
                phase2DurationSec = p2Dur,
                phase1DispMeters = p1Disp,
                phase2DispMeters = p2Disp,
                phase1PeakVelMps = p1Peak,
                phase2PeakVelMps = p2Peak
            )
        }
        abortIncompleteRepCycle(timestampMs)
        return CycleResult(false, 0, 0f)
    }

    private fun updateProgressCycle(
        progress: Float,
        startThreshold: Float,
        peakThreshold: Float,
        finishThreshold: Float,
        currentRom: Int,
        primaryPosMeters: Float,
        instantSpeedMps: Float,
        timestampMs: Long
    ): CycleResult {
        if (currentRom > repRomPeak) repRomPeak = currentRom
        val isConcFirst = VelocityEstimator.specFor(currentExercise).isConcentricFirst

        when (phase) {
            MovementPhase.READY -> {
                if (progress >= startThreshold) {
                    val hitPeak = progress >= peakThreshold
                    phase = if (hitPeak) MovementPhase.BOTTOM else MovementPhase.ECCENTRIC
                    reachedBottom = hitPeak
                    enteredBottomFromRamp = false
                    bottomEnteredTsMs = if (hitPeak) timestampMs else 0L
                    concentricLeftBottomTsMs = 0L
                    repRomPeak = currentRom
                    repStartTsMs = timestampMs
                    phase1StartPosMeters = primaryPosMeters
                    phase1EndPosMeters = primaryPosMeters
                    phase1PeakVelMps = instantSpeedMps
                    phase2StartPosMeters = primaryPosMeters
                    phase2PeakVelMps = 0f
                }
            }
            MovementPhase.ECCENTRIC -> {
                if (instantSpeedMps > phase1PeakVelMps) phase1PeakVelMps = instantSpeedMps
                if (isConcFirst) velocityEstimator.updateLiveVelocity(instantSpeedMps)
                if (progress >= peakThreshold) {
                    phase = MovementPhase.BOTTOM
                    reachedBottom = true
                    enteredBottomFromRamp = true
                    bottomEnteredTsMs = timestampMs
                    phase1EndPosMeters = primaryPosMeters
                    phase2StartPosMeters = primaryPosMeters
                    phase2PeakVelMps = 0f
                } else if (progress < startThreshold * 0.60f) {
                    abortIncompleteRepCycle(timestampMs)
                }
            }
            MovementPhase.BOTTOM -> {
                val dwellMs = if (bottomEnteredTsMs > 0L) timestampMs - bottomEnteredTsMs else 0L
                if (progress <= finishThreshold && reachedBottom) {
                    if (instantSpeedMps > phase2PeakVelMps) phase2PeakVelMps = instantSpeedMps
                    return completeRepOrAbort(timestampMs, checkBottomDwell = true, currentPosMeters = primaryPosMeters)
                } else if (progress < peakThreshold - 0.12f) {
                    if (dwellMs >= requiredBottomDwellMs()) {
                        phase = MovementPhase.CONCENTRIC
                        concentricLeftBottomTsMs = timestampMs
                        phase2StartPosMeters = primaryPosMeters
                        phase2PeakVelMps = instantSpeedMps
                        if (!isConcFirst) velocityEstimator.updateLiveVelocity(instantSpeedMps)
                    } else {
                        reachedBottom = false
                        enteredBottomFromRamp = false
                        bottomEnteredTsMs = 0L
                        phase = MovementPhase.ECCENTRIC
                    }
                }
            }
            MovementPhase.CONCENTRIC -> {
                if (instantSpeedMps > phase2PeakVelMps) phase2PeakVelMps = instantSpeedMps
                if (!isConcFirst) velocityEstimator.updateLiveVelocity(instantSpeedMps)
                if (progress <= finishThreshold && reachedBottom) {
                    return completeRepOrAbort(timestampMs, checkBottomDwell = false, currentPosMeters = primaryPosMeters)
                } else if (progress >= peakThreshold) {
                    phase = MovementPhase.BOTTOM
                    if (bottomEnteredTsMs == 0L) bottomEnteredTsMs = timestampMs
                    phase2StartPosMeters = primaryPosMeters
                }
            }
            else -> phase = MovementPhase.READY
        }
        return CycleResult(false, 0, 0f)
    }

    private fun updateDownUpCycle(
        metric: Float,
        startThreshold: Float,
        bottomThreshold: Float,
        finishThreshold: Float,
        currentRom: Int,
        primaryPosMeters: Float,
        instantSpeedMps: Float,
        timestampMs: Long
    ): CycleResult {
        if (currentRom > repRomPeak) repRomPeak = currentRom
        val abortThreshold = maxOf(startThreshold, finishThreshold) + 4f
        val isConcFirst = VelocityEstimator.specFor(currentExercise).isConcentricFirst

        when (phase) {
            MovementPhase.READY -> {
                if (metric < startThreshold) {
                    val hitBottom = metric <= bottomThreshold
                    phase = if (hitBottom) MovementPhase.BOTTOM else MovementPhase.ECCENTRIC
                    reachedBottom = hitBottom
                    enteredBottomFromRamp = false
                    bottomEnteredTsMs = if (hitBottom) timestampMs else 0L
                    concentricLeftBottomTsMs = 0L
                    repRomPeak = currentRom
                    repStartTsMs = timestampMs
                    phase1StartPosMeters = primaryPosMeters
                    phase1EndPosMeters = primaryPosMeters
                    phase1PeakVelMps = instantSpeedMps
                    phase2StartPosMeters = primaryPosMeters
                    phase2PeakVelMps = 0f
                }
            }
            MovementPhase.ECCENTRIC -> {
                if (instantSpeedMps > phase1PeakVelMps) phase1PeakVelMps = instantSpeedMps
                if (isConcFirst) velocityEstimator.updateLiveVelocity(instantSpeedMps)
                if (metric <= bottomThreshold) {
                    phase = MovementPhase.BOTTOM
                    reachedBottom = true
                    enteredBottomFromRamp = true
                    bottomEnteredTsMs = timestampMs
                    phase1EndPosMeters = primaryPosMeters
                    phase2StartPosMeters = primaryPosMeters
                    phase2PeakVelMps = 0f
                } else if (metric >= abortThreshold) {
                    abortIncompleteRepCycle(timestampMs)
                }
            }
            MovementPhase.BOTTOM -> {
                val dwellMs = if (bottomEnteredTsMs > 0L) timestampMs - bottomEnteredTsMs else 0L
                if (metric >= finishThreshold && reachedBottom) {
                    if (instantSpeedMps > phase2PeakVelMps) phase2PeakVelMps = instantSpeedMps
                    return completeRepOrAbort(timestampMs, checkBottomDwell = true, currentPosMeters = primaryPosMeters)
                } else if (metric > bottomThreshold + 10f) {
                    if (dwellMs >= requiredBottomDwellMs()) {
                        phase = MovementPhase.CONCENTRIC
                        concentricLeftBottomTsMs = timestampMs
                        phase2StartPosMeters = primaryPosMeters
                        phase2PeakVelMps = instantSpeedMps
                        if (!isConcFirst) velocityEstimator.updateLiveVelocity(instantSpeedMps)
                    } else {
                        reachedBottom = false
                        enteredBottomFromRamp = false
                        bottomEnteredTsMs = 0L
                        phase = MovementPhase.ECCENTRIC
                    }
                }
            }
            MovementPhase.CONCENTRIC -> {
                if (instantSpeedMps > phase2PeakVelMps) phase2PeakVelMps = instantSpeedMps
                if (!isConcFirst) velocityEstimator.updateLiveVelocity(instantSpeedMps)
                if (metric >= finishThreshold && reachedBottom) {
                    return completeRepOrAbort(timestampMs, checkBottomDwell = false, currentPosMeters = primaryPosMeters)
                }
            }
            else -> phase = MovementPhase.READY
        }
        return CycleResult(false, 0, 0f)
    }

    private fun updateUpDownCycle(
        metric: Float,
        startThreshold: Float,
        topThreshold: Float,
        finishThreshold: Float,
        extraTopCondition: Boolean,
        currentRom: Int,
        primaryPosMeters: Float,
        instantSpeedMps: Float,
        timestampMs: Long
    ): CycleResult {
        if (currentRom > repRomPeak) repRomPeak = currentRom
        val abortThreshold = minOf(startThreshold, finishThreshold) - 4f
        val isConcFirst = VelocityEstimator.specFor(currentExercise).isConcentricFirst

        when (phase) {
            MovementPhase.READY -> {
                if (metric > startThreshold) {
                    val hitTop = metric >= topThreshold && extraTopCondition
                    phase = if (hitTop) MovementPhase.BOTTOM else MovementPhase.CONCENTRIC
                    reachedBottom = hitTop
                    enteredBottomFromRamp = false
                    bottomEnteredTsMs = if (hitTop) timestampMs else 0L
                    concentricLeftBottomTsMs = 0L
                    repRomPeak = currentRom
                    repStartTsMs = timestampMs
                    phase1StartPosMeters = primaryPosMeters
                    phase1EndPosMeters = primaryPosMeters
                    phase1PeakVelMps = instantSpeedMps
                    phase2StartPosMeters = primaryPosMeters
                    phase2PeakVelMps = 0f
                }
            }
            MovementPhase.CONCENTRIC -> {
                if (instantSpeedMps > phase1PeakVelMps) phase1PeakVelMps = instantSpeedMps
                if (isConcFirst) velocityEstimator.updateLiveVelocity(instantSpeedMps)
                if (metric >= topThreshold && extraTopCondition) {
                    phase = MovementPhase.BOTTOM
                    reachedBottom = true
                    enteredBottomFromRamp = true
                    bottomEnteredTsMs = timestampMs
                    phase1EndPosMeters = primaryPosMeters
                    phase2StartPosMeters = primaryPosMeters
                    phase2PeakVelMps = 0f
                } else if (metric <= abortThreshold) {
                    abortIncompleteRepCycle(timestampMs)
                }
            }
            MovementPhase.BOTTOM -> {
                val dwellMs = if (bottomEnteredTsMs > 0L) timestampMs - bottomEnteredTsMs else 0L
                if (metric <= finishThreshold && reachedBottom) {
                    if (instantSpeedMps > phase2PeakVelMps) phase2PeakVelMps = instantSpeedMps
                    return completeRepOrAbort(timestampMs, checkBottomDwell = true, currentPosMeters = primaryPosMeters)
                } else if (metric < topThreshold - 10f) {
                    if (dwellMs >= requiredBottomDwellMs()) {
                        phase = MovementPhase.ECCENTRIC
                        concentricLeftBottomTsMs = timestampMs
                        phase2StartPosMeters = primaryPosMeters
                        phase2PeakVelMps = instantSpeedMps
                        if (!isConcFirst) velocityEstimator.updateLiveVelocity(instantSpeedMps)
                    } else {
                        reachedBottom = false
                        enteredBottomFromRamp = false
                        bottomEnteredTsMs = 0L
                        phase = MovementPhase.CONCENTRIC
                    }
                }
            }
            MovementPhase.ECCENTRIC -> {
                if (instantSpeedMps > phase2PeakVelMps) phase2PeakVelMps = instantSpeedMps
                if (!isConcFirst) velocityEstimator.updateLiveVelocity(instantSpeedMps)
                if (metric <= finishThreshold && reachedBottom) {
                    return completeRepOrAbort(timestampMs, checkBottomDwell = false, currentPosMeters = primaryPosMeters)
                }
            }
            else -> phase = MovementPhase.READY
        }
        return CycleResult(false, 0, 0f)
    }

    private fun computeRepDurationSec(nowMs: Long): Float {
        if (repStartTsMs <= 0L || nowMs <= repStartTsMs) return 2.4f
        return ((nowMs - repStartTsMs) / 1000f).coerceIn(0.4f, 12.0f)
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
            .filter { it.value >= 1 }
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
}
