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
    val isometricDeltaSec: Float = 0f
)

/**
 * Pure-Kotlin real-time 3D multi-angle biomechanics analyzer for all 16 KINETIX exercises.
 * Uses MediaPipe 33-landmark screen & 3D metric world coordinates with adaptive Casiez One-Euro
 * filtering, viewpoint-invariant 3D joint geometry, bilateral symmetry checks, and degree-exact
 * visual/vocal angle corrections.
 */
class BiomechanicsEngine(
    initialExercise: ExerciseType = ExerciseType.SQUAT,
    private val emaAlpha: Float = 0.42f,
    private val useOneEuroFilter: Boolean = emaAlpha < 0.999f
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

    private val screenSmoother = PoseOneEuroSmoother()
    private val worldSmoother = PoseOneEuroSmoother()
    private var legacySmoothedScreen: MutableList<PosePoint>? = null
    private var legacySmoothedWorld: MutableList<PosePoint>? = null

    private var repCount: Int = 0
    private var phase: MovementPhase = MovementPhase.READY
    private var reachedBottom: Boolean = false
    private var burpeeStage: Int = 0 // 0=stand, 1=down/plank
    private var plankHoldMs: Long = 0L
    private var lastFrameTsMs: Long = 0L
    private var repStartTsMs: Long = 0L

    // Session metrics accumulators
    private var formScoreSum: Long = 0L
    private var formScoreSamples: Int = 0
    private var repRomPeak: Int = 0
    private var romSum: Long = 0L
    private var romSamples: Int = 0
    private val faultCounts = linkedMapOf<String, Int>()
    private var currentRepFault: String? = null

    fun setExercise(exercise: ExerciseType) {
        currentExercise = exercise
        resetSession()
    }

    fun resetSession() {
        screenSmoother.reset()
        worldSmoother.reset()
        legacySmoothedScreen = null
        legacySmoothedWorld = null
        repCount = 0
        phase = MovementPhase.READY
        reachedBottom = false
        burpeeStage = 0
        plankHoldMs = 0L
        lastFrameTsMs = 0L
        repStartTsMs = 0L
        formScoreSum = 0L
        formScoreSamples = 0
        repRomPeak = 0
        romSum = 0L
        romSamples = 0
        faultCounts.clear()
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
        corrections = emptyList()
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

        val screenPts = smoothLandmarks(rawScreenLandmarks, timestampMs, isWorld = false)
        val hasWorld = rawWorldLandmarks.size >= 29
        val worldPts = if (hasWorld) smoothLandmarks(rawWorldLandmarks, timestampMs, isWorld = true) else emptyList()

        val dtMs = if (lastFrameTsMs > 0L) (timestampMs - lastFrameTsMs).coerceIn(0L, 250L) else 0L
        lastFrameTsMs = timestampMs

        val viewpointInfo = CameraViewpointDetector.detect(screenPts, worldPts)
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

        when (currentExercise) {
            ExerciseType.SQUAT -> {
                romPct = mapRange(kneeAngle, 168f, 82f)
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

                // 3. Cycle update
                val cycle = updateDownUpCycle(
                    metric = kneeAngle,
                    startThreshold = 152f,
                    bottomThreshold = 98f,
                    finishThreshold = 158f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                // 4. Precision depth angle guidance when descending / paused above parallel (98°..132°)
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
                        targetRangeText = "≤95°",
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
                    MovementPhase.BOTTOM -> "Bonne profondeur (${kneeAngle.roundToInt()}°) — poussez !"
                    MovementPhase.ECCENTRIC -> corrections.firstOrNull()?.instructionFr ?: "Contrôlez la descente, dos neutre."
                    MovementPhase.CONCENTRIC -> "Poussez fort dans tout le pied."
                    else -> "Prêt — descendez genoux dans l'axe."
                }
            }

            ExerciseType.PUSHUP -> {
                romPct = mapRange(elbowAngle, 165f, 82f)
                var elbowHint: Int? = null
                var hipHint: Int? = null

                if (bodyLineAlign < 158f) {
                    val delta = (172f - bodyLineAlign).roundToInt().coerceAtLeast(1)
                    score -= 30
                    faultMsg = "Gainez les abdos : ne creusez pas le bas du dos"
                    hipHint = 175
                    val (gx, gy) = bodyLineGhost(shS, hpS, anS)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = hpIdx,
                            jointLabelFr = "Hanches",
                            currentDeg = bodyLineAlign.roundToInt(),
                            targetMinDeg = 162,
                            targetMaxDeg = 198,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = 0f,
                            arrowDy = -1f,
                            ghostAnchorIndex = shIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "HANCHES +$delta°",
                            instructionFr = "Remontez les hanches de $delta° (${bodyLineAlign.roundToInt()}° → 165–195°)",
                            voiceCueFr = "Gainez les abdos, remontez les hanches"
                        )
                    )
                } else if (bodyLineAlign > 202f) {
                    val delta = (bodyLineAlign - 188f).roundToInt().coerceAtLeast(1)
                    score -= 22
                    faultMsg = "Abaissez légèrement les hanches"
                    hipHint = 180
                    val (gx, gy) = bodyLineGhost(shS, hpS, anS)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = hpIdx,
                            jointLabelFr = "Hanches",
                            currentDeg = bodyLineAlign.roundToInt(),
                            targetMinDeg = 162,
                            targetMaxDeg = 198,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = 0f,
                            arrowDy = 1f,
                            ghostAnchorIndex = shIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "HANCHES -$delta°",
                            instructionFr = "Abaissez les hanches de $delta° (${bodyLineAlign.roundToInt()}° → 165–195°)",
                            voiceCueFr = "Abaissez les hanches pour aligner le corps"
                        )
                    )
                }

                // Elbow flare detection in 3D when shoulders visible
                if (use3D && bothVisible && elbowAngle < 125f && shoulderAngle > 78f) {
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

                val cycle = updateDownUpCycle(
                    metric = elbowAngle,
                    startThreshold = 148f,
                    bottomThreshold = 95f,
                    finishThreshold = 156f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (!reachedBottom && elbowAngle in 98f..130f) {
                    val delta = (elbowAngle - 95f).roundToInt().coerceAtLeast(1)
                    elbowHint = 95
                    corrections.add(
                        AngleCorrection(
                            jointIndex = elIdx,
                            jointLabelFr = "Coude",
                            currentDeg = elbowAngle.roundToInt(),
                            targetMinDeg = 70,
                            targetMaxDeg = 95,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = 1f,
                            shortBadgeFr = "COUDE -$delta°",
                            instructionFr = "Descendez encore $delta° (${elbowAngle.roundToInt()}° → ≤95°)",
                            voiceCueFr = "Descendez la poitrine plus bas"
                        )
                    )
                }

                addSymmetryCorrectionIfNeeded(
                    leftElbow, rightElbow, lArmW, rArmW,
                    active = elbowAngle < 138f,
                    jointLabel = "coudes",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        elIdx, elS.x, elS.y, elbowAngle.roundToInt(),
                        if (elbowHint != null) FormQuality.CAUTION else scoreToQuality(score),
                        parentIndex = shIdx, childIndex = wrIdx, targetRangeText = "≤95°", targetDegHint = elbowHint
                    )
                )
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, bodyLineAlign.roundToInt(),
                        if (bodyLineAlign in 160f..200f) FormQuality.GOOD else FormQuality.FAULT,
                        parentIndex = shIdx, childIndex = anIdx, targetRangeText = "162–198°", targetDegHint = hipHint
                    )
                )
                feedback = faultMsg ?: when (phase) {
                    MovementPhase.BOTTOM -> "Amplitude validée — repoussez le sol !"
                    else -> "Corps aligné (${bodyLineAlign.roundToInt()}°), coudes à 45°."
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

                romPct = mapRange(minKnee, 165f, 88f)
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

                val cycle = updateDownUpCycle(
                    metric = minKnee,
                    startThreshold = 148f,
                    bottomThreshold = 102f,
                    finishThreshold = 155f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (!reachedBottom && minKnee in 103f..132f) {
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
                        parentIndex = activeHpIdx, childIndex = activeAnIdx, targetRangeText = "≤95°", targetDegHint = kneeHint
                    )
                )
                feedback = faultMsg ?: "Amplitude ${romPct}% — contrôlez la stabilité du genou."
            }

            ExerciseType.DEADLIFT -> {
                romPct = mapRange(hipAngle, 170f, 85f)
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
                if (hipAngle > 185f || (phase == MovementPhase.READY && bodyLineAlign > 192f && torsoTilt > 12f)) {
                    val cur = maxOf(hipAngle, bodyLineAlign).roundToInt()
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
                    bottomThreshold = 105f,
                    finishThreshold = 162f,
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
                        parentIndex = shIdx, childIndex = knIdx, targetRangeText = "85–175°", targetDegHint = hipHint
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
                romPct = if (overheadOk) mapRange(elbowAngle, 75f, 165f) else mapRange(elbowAngle, 75f, 165f) / 2
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

                val cycle = updateUpDownCycle(
                    metric = elbowAngle,
                    startThreshold = 100f,
                    topThreshold = 155f,
                    finishThreshold = 95f,
                    extraTopCondition = overheadOk,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (overheadOk && !reachedBottom && elbowAngle in 118f..154f) {
                    val delta = (162f - elbowAngle).roundToInt().coerceAtLeast(1)
                    elbowHint = 162
                    val (gx, gy) = straightenLimbGhost(shS, elS, wrS)
                    corrections.add(
                        AngleCorrection(
                            jointIndex = elIdx,
                            jointLabelFr = "Verrouillage",
                            currentDeg = elbowAngle.roundToInt(),
                            targetMinDeg = 160,
                            targetMaxDeg = 180,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = -1f,
                            ghostAnchorIndex = elIdx,
                            ghostTargetX = gx,
                            ghostTargetY = gy,
                            shortBadgeFr = "COUDE +$delta°",
                            instructionFr = "Verrouillez les coudes (+$delta° : ${elbowAngle.roundToInt()}° → ≥160°)",
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
                        parentIndex = shIdx, childIndex = wrIdx, targetRangeText = "≥160°", targetDegHint = elbowHint
                    )
                )
                feedback = faultMsg ?: "Poussée verticale stricte — verrouillez au-dessus."
            }

            ExerciseType.BICEP_CURL -> {
                romPct = mapRange(elbowAngle, 160f, 45f)
                val elbowDrift3D = use3D && shoulderAngle > 34f && elbowAngle < 110f
                val torsoLen2D = dist2D(shS, hpS).coerceAtLeast(0.08f)
                val elbowDrift2D = !use3D && abs(elS.x - shS.x) > torsoLen2D * 0.42f && elbowAngle < 105f
                if (elbowDrift3D || elbowDrift2D) {
                    val delta = (shoulderAngle - 25f).roundToInt().coerceAtLeast(8)
                    score -= 24
                    faultMsg = "Gardez le coude fixe près des côtes"
                    val pullDx = if (elS.x >= shS.x) -1f else 1f
                    corrections.add(
                        AngleCorrection(
                            jointIndex = elIdx,
                            jointLabelFr = "Coude",
                            currentDeg = shoulderAngle.roundToInt(),
                            targetMinDeg = 0,
                            targetMaxDeg = 28,
                            deltaDeg = delta,
                            severity = FormQuality.FAULT,
                            arrowDx = pullDx,
                            arrowDy = 0.25f,
                            ghostAnchorIndex = shIdx,
                            ghostTargetX = shS.x,
                            ghostTargetY = elS.y,
                            shortBadgeFr = "COUDE -$delta°",
                            instructionFr = "Reculez le coude près des côtes (${shoulderAngle.roundToInt()}° → ≤28°)",
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
                    metric = elbowAngle,
                    startThreshold = 140f,
                    bottomThreshold = 58f,
                    finishThreshold = 148f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                addSymmetryCorrectionIfNeeded(
                    leftElbow, rightElbow, lArmW, rArmW,
                    active = elbowAngle < 125f,
                    jointLabel = "bras",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        elIdx, elS.x, elS.y, elbowAngle.roundToInt(), scoreToQuality(score),
                        parentIndex = shIdx, childIndex = wrIdx, targetRangeText = "≤55°"
                    )
                )
                feedback = faultMsg ?: "Flexion propre (${elbowAngle.roundToInt()}°), contrôlez la descente."
            }

            ExerciseType.TRICEP_DIP -> {
                romPct = mapRange(elbowAngle, 162f, 82f)
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

                val cycle = updateDownUpCycle(
                    metric = elbowAngle,
                    startThreshold = 148f,
                    bottomThreshold = 95f,
                    finishThreshold = 155f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (!reachedBottom && elbowAngle in 98f..128f) {
                    val delta = (elbowAngle - 95f).roundToInt().coerceAtLeast(1)
                    elbowHint = 95
                    corrections.add(
                        AngleCorrection(
                            jointIndex = elIdx,
                            jointLabelFr = "Coude",
                            currentDeg = elbowAngle.roundToInt(),
                            targetMinDeg = 80,
                            targetMaxDeg = 95,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = 1f,
                            shortBadgeFr = "COUDE -$delta°",
                            instructionFr = "Descendez encore $delta° (${elbowAngle.roundToInt()}° → ≤95°)",
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
                        parentIndex = shIdx, childIndex = wrIdx, targetRangeText = "≤95°", targetDegHint = elbowHint
                    )
                )
                feedback = faultMsg ?: "Descendez jusqu'à 90° puis verrouillez proprement."
            }

            ExerciseType.PLANK -> {
                val isHorizontal = if (use3D) {
                    fullBodyTilt >= 55f
                } else {
                    abs(shS.y - anS.y) < abs(shS.x - anS.x) * 0.75f
                }
                val aligned = isHorizontal && bodyLineAlign in 160f..195f
                var hipHint: Int? = null
                if (aligned) {
                    plankHoldMs += dtMs
                    isometricDeltaSec = dtMs / 1000f
                    phase = MovementPhase.HOLD
                    romPct = 100
                    score = 98 - (abs(180f - bodyLineAlign) * 0.8f).roundToInt().coerceAtMost(15)
                    feedback = "Alignement parfait (${bodyLineAlign.roundToInt()}°) — maintenez !"
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
                    } else if (bodyLineAlign < 160f) {
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
                                targetMinDeg = 165,
                                targetMaxDeg = 195,
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = 0f,
                                arrowDy = -1f,
                                ghostAnchorIndex = shIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "HANCHES +$delta°",
                                instructionFr = "Remontez les hanches de $delta° (${bodyLineAlign.roundToInt()}° → 165–195°)",
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
                                targetMinDeg = 165,
                                targetMaxDeg = 195,
                                deltaDeg = delta,
                                severity = FormQuality.FAULT,
                                arrowDx = 0f,
                                arrowDy = 1f,
                                ghostAnchorIndex = shIdx,
                                ghostTargetX = gx,
                                ghostTargetY = gy,
                                shortBadgeFr = "HANCHES -$delta°",
                                instructionFr = "Abaissez les hanches de $delta° (${bodyLineAlign.roundToInt()}° → 165–195°)",
                                voiceCueFr = "Abaissez les hanches"
                            )
                        )
                    }
                }
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, bodyLineAlign.roundToInt(), scoreToQuality(score),
                        parentIndex = shIdx, childIndex = anIdx, targetRangeText = "165–195°", targetDegHint = hipHint
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
                    torsoTilt >= 55f
                } else {
                    abs(shS.y - hpS.y) < abs(shS.x - hpS.x) * 0.55f
                }
                val standingTall = hipAngle > 155f && kneeAngle > 155f && shS.y < hpS.y
                when (burpeeStage) {
                    0 -> {
                        phase = if (kneeAngle < 130f) MovementPhase.ECCENTRIC else MovementPhase.READY
                        romPct = if (bodyHorizontal) 75 else mapRange(kneeAngle, 165f, 90f) / 2
                        if (kneeAngle < 130f && repStartTsMs == 0L) repStartTsMs = timestampMs
                        if (bodyHorizontal) {
                            burpeeStage = 1
                            phase = MovementPhase.BOTTOM
                            if (repStartTsMs == 0L) repStartTsMs = timestampMs
                        }
                    }
                    1 -> {
                        phase = MovementPhase.BOTTOM
                        romPct = 100
                        if (standingTall) {
                            repCount++
                            recordRom(100)
                            repCompletedNow = true
                            completedRepRom = 100
                            completedRepDurSec = computeRepDurationSec(timestampMs)
                            repStartTsMs = 0L
                            burpeeStage = 0
                            phase = MovementPhase.READY
                            voiceCue = "$repCount"
                        } else if (!bodyHorizontal && shS.y < hpS.y) {
                            phase = MovementPhase.CONCENTRIC
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
                val legsSpread = ankleW > shoulderW * 1.15f
                romPct = mapRange(armFullAngle, 22f, 152f)
                if (armFullAngle > 130f && !legsSpread) {
                    score -= 18
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
                val cycle = updateUpDownCycle(
                    metric = armFullAngle,
                    startThreshold = 45f,
                    topThreshold = 138f,
                    finishThreshold = 42f,
                    extraTopCondition = legsSpread,
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
                        parentIndex = hpIdx, childIndex = wrIdx, targetRangeText = "≥140°"
                    )
                )
                feedback = faultMsg ?: "Rythme fluide — bras hauts et pieds écartés."
            }

            ExerciseType.PULL_UP -> {
                val noseY = geomPts[NOSE].y
                val midWristY = (geomPts[LEFT_WRIST].y + geomPts[RIGHT_WRIST].y) * 0.5f
                val chinMargin = if (use3D) 0.05f else 0.03f
                val chinOverHands = noseY < midWristY + chinMargin
                romPct = mapRange(elbowAngle, 165f, 55f)

                val cycle = updateDownUpCycle(
                    metric = elbowAngle,
                    startThreshold = 148f,
                    bottomThreshold = 72f,
                    finishThreshold = 155f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (reachedBottom && !chinOverHands && elbowAngle < 88f) {
                    val delta = (elbowAngle - 62f).roundToInt().coerceAtLeast(8)
                    score -= 15
                    faultMsg = "Tirez la poitrine plus haut vers la barre"
                    corrections.add(
                        AngleCorrection(
                            jointIndex = shIdx,
                            jointLabelFr = "Tirage",
                            currentDeg = elbowAngle.roundToInt(),
                            targetMinDeg = 45,
                            targetMaxDeg = 70,
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
                        parentIndex = shIdx, childIndex = wrIdx, targetRangeText = "≤72°"
                    )
                )
                feedback = faultMsg ?: "Tirez les coudes vers les hanches, amplitude complète."
            }

            ExerciseType.BENT_ROW -> {
                romPct = mapRange(elbowAngle, 160f, 68f)
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

                val cycle = updateDownUpCycle(
                    metric = elbowAngle,
                    startThreshold = 145f,
                    bottomThreshold = 82f,
                    finishThreshold = 150f,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                addSymmetryCorrectionIfNeeded(
                    leftElbow, rightElbow, lArmW, rArmW,
                    active = elbowAngle < 135f,
                    jointLabel = "tirage",
                    leftJointIdx = LEFT_ELBOW,
                    rightJointIdx = RIGHT_ELBOW
                )

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        elIdx, elS.x, elS.y, elbowAngle.roundToInt(), scoreToQuality(score),
                        parentIndex = shIdx, childIndex = wrIdx, targetRangeText = "≤82°"
                    )
                )
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, torsoTilt.roundToInt(),
                        if (torsoTilt in 24f..80f) FormQuality.GOOD else FormQuality.FAULT,
                        parentIndex = shIdx, childIndex = knIdx, targetRangeText = "35–55°", targetDegHint = torsoHint
                    )
                )
                feedback = faultMsg ?: "Buste fixe (${torsoTilt.roundToInt()}°) — serrez les omoplates."
            }

            ExerciseType.GLUTE_BRIDGE -> {
                romPct = mapRange(hipAngle, 110f, 172f)
                var hipHint: Int? = null
                val supineAlign = if (use3D) {
                    bodyLineAlignment3D(geomPts[shIdx], geomPts[hpIdx], geomPts[knIdx])
                } else {
                    bodyLineAlignmentDegrees(shS, hpS, knS)
                }
                if (hipAngle > 186f || supineAlign > 194f) {
                    val cur = maxOf(hipAngle, supineAlign).roundToInt()
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

                val cycle = updateUpDownCycle(
                    metric = hipAngle,
                    startThreshold = 128f,
                    topThreshold = 162f,
                    finishThreshold = 125f,
                    extraTopCondition = true,
                    currentRom = romPct,
                    fault = faultMsg,
                    timestampMs = timestampMs
                )
                repCompletedNow = cycle.completed
                completedRepRom = cycle.completedRom
                completedRepDurSec = cycle.durationSec

                if (!reachedBottom && hipAngle in 134f..158f) {
                    val delta = (165f - hipAngle).roundToInt().coerceAtLeast(1)
                    hipHint = 165
                    corrections.add(
                        AngleCorrection(
                            jointIndex = hpIdx,
                            jointLabelFr = "Hanches",
                            currentDeg = hipAngle.roundToInt(),
                            targetMinDeg = 165,
                            targetMaxDeg = 180,
                            deltaDeg = delta,
                            severity = FormQuality.CAUTION,
                            arrowDx = 0f,
                            arrowDy = -1f,
                            shortBadgeFr = "HANCHES +$delta°",
                            instructionFr = "Montez encore les hanches (+$delta° : ${hipAngle.roundToInt()}° → ≥165°)",
                            voiceCueFr = "Montez les hanches plus haut"
                        )
                    )
                }

                if (repCompletedNow) voiceCue = "$repCount"
                badges.add(
                    JointAngleBadge(
                        hpIdx, hpS.x, hpS.y, hipAngle.roundToInt(),
                        if (hipHint != null && score < 82) FormQuality.FAULT else scoreToQuality(score),
                        parentIndex = shIdx, childIndex = knIdx, targetRangeText = "165–180°", targetDegHint = hipHint
                    )
                )
                feedback = faultMsg ?: "Extension complète des hanches (${hipAngle.roundToInt()}°), serrez 1s."
            }

            ExerciseType.LEG_RAISE -> {
                romPct = mapRange(hipAngle, 170f, 92f)
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

                val cycle = updateDownUpCycle(
                    metric = hipAngle,
                    startThreshold = 152f,
                    bottomThreshold = 104f,
                    finishThreshold = 156f,
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
                        parentIndex = shIdx, childIndex = knIdx, targetRangeText = "≤100°"
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
                romPct = mapRange(shoulderAngle, 15f, 90f)
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

                val cycle = updateUpDownCycle(
                    metric = shoulderAngle,
                    startThreshold = 28f,
                    topThreshold = 78f,
                    finishThreshold = 26f,
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
                        parentIndex = hpIdx, childIndex = elIdx, targetRangeText = "80–95°", targetDegHint = shHint
                    )
                )
                feedback = faultMsg ?: "Élevez jusqu'à 90° (${shoulderAngle.roundToInt()}°), épaules basses."
            }
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
            isometricDeltaSec = isometricDeltaSec
        )
    }

    private data class CycleResult(
        val completed: Boolean,
        val completedRom: Int,
        val durationSec: Float
    )

    /** State machine for movements whose active bottom phase decreases the primary angle (Squat, Pushup, Curl...). */
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

        when (phase) {
            MovementPhase.READY -> {
                if (metric < startThreshold) {
                    phase = MovementPhase.ECCENTRIC
                    reachedBottom = false
                    repRomPeak = currentRom
                    repStartTsMs = timestampMs
                    currentRepFault = fault
                }
            }
            MovementPhase.ECCENTRIC -> {
                if (metric <= bottomThreshold) {
                    phase = MovementPhase.BOTTOM
                    reachedBottom = true
                } else if (metric >= finishThreshold) {
                    phase = MovementPhase.READY
                    repRomPeak = 0
                    repStartTsMs = 0L
                }
            }
            MovementPhase.BOTTOM -> {
                if (metric > bottomThreshold + 12f) {
                    phase = MovementPhase.CONCENTRIC
                }
            }
            MovementPhase.CONCENTRIC -> {
                if (metric >= finishThreshold && reachedBottom) {
                    val peakRom = repRomPeak
                    val durSec = computeRepDurationSec(timestampMs)
                    repCount++
                    recordRom(peakRom)
                    phase = MovementPhase.READY
                    reachedBottom = false
                    repRomPeak = 0
                    repStartTsMs = 0L
                    currentRepFault = null
                    return CycleResult(true, peakRom, durSec)
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

        when (phase) {
            MovementPhase.READY -> {
                if (metric > startThreshold) {
                    phase = MovementPhase.CONCENTRIC
                    reachedBottom = false
                    repRomPeak = currentRom
                    repStartTsMs = timestampMs
                    currentRepFault = fault
                }
            }
            MovementPhase.CONCENTRIC -> {
                if (metric >= topThreshold && extraTopCondition) {
                    phase = MovementPhase.BOTTOM
                    reachedBottom = true
                } else if (metric <= finishThreshold) {
                    phase = MovementPhase.READY
                    repRomPeak = 0
                    repStartTsMs = 0L
                }
            }
            MovementPhase.BOTTOM -> {
                if (metric < topThreshold - 10f) {
                    phase = MovementPhase.ECCENTRIC
                }
            }
            MovementPhase.ECCENTRIC -> {
                if (metric <= finishThreshold && reachedBottom) {
                    val peakRom = repRomPeak
                    val durSec = computeRepDurationSec(timestampMs)
                    repCount++
                    recordRom(peakRom)
                    phase = MovementPhase.READY
                    reachedBottom = false
                    repRomPeak = 0
                    repStartTsMs = 0L
                    currentRepFault = null
                    return CycleResult(true, peakRom, durSec)
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
