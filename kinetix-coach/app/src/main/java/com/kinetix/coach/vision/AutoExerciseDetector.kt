package com.kinetix.coach.vision

import com.kinetix.coach.data.ExerciseType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class BodyPosture {
    STANDING,
    PRONE_FLOOR,
    SUPINE_FLOOR,
    SUSPENDED,
    DYNAMIC_TRANSITION
}

data class AutoDetectResult(
    val detectedExercise: ExerciseType?,
    val confidence: Float,
    val candidateExercise: ExerciseType?,
    val isLocked: Boolean,
    val statusFr: String,
    val scores: Map<ExerciseType, Float> = emptyMap()
)

/**
 * Viewpoint-invariant real-time automatic exercise detector across all 16 KINETIX exercises.
 * Maintains a sliding temporal ring buffer (~2.4 s / up to 72 frames) of normalized 3D posture
 * and kinematic features, applies continuous confidence scoring across all 16 exercises, and uses
 * multi-frame stability hysteresis to prevent flicker while enabling fast posture-family switches.
 */
class AutoExerciseDetector(
    private val maxBufferFrames: Int = 72,
    private val maxWindowMs: Long = 2600L,
    private val minFramesForDetection: Int = 10,
    private val confirmThreshold: Float = 0.66f,
    private val confirmConsecutiveFrames: Int = 10,
    private val fastPostureSwitchFrames: Int = 6
) {
    private data class FrameSample(
        val timestampMs: Long,
        val posture: BodyPosture,
        val kneeDeg: Float,
        val hipDeg: Float,
        val elbowDeg: Float,
        val shoulderDeg: Float,
        val armFullDeg: Float,
        val torsoTiltDeg: Float,
        val bodyTiltDeg: Float,
        val bodyAlignDeg: Float,
        val hipAboveAnklesTl: Float,
        val shAboveAnklesTl: Float,
        val wristAboveShTl: Float,
        val wristAboveHipTl: Float,
        val ankleAboveHipTl: Float,
        val footDistTl: Float,
        val ankleYDiffTl: Float,
        val ankleToShoulderRatio: Float,
        val screenShY: Float,
        val screenWristY: Float
    )

    private val buffer = ArrayDeque<FrameSample>(maxBufferFrames + 1)
    private val screenSmoother = PoseOneEuroSmoother(minCutoff = 1.4f, beta = 0.06f)
    private val worldSmoother = PoseOneEuroSmoother(minCutoff = 1.4f, beta = 0.06f)

    private var lockedExercise: ExerciseType? = null
    private var lockedConfidence: Float = 0f
    private var pendingCandidate: ExerciseType? = null
    private var pendingStreak: Int = 0

    fun reset(initialLocked: ExerciseType? = null) {
        buffer.clear()
        screenSmoother.reset()
        worldSmoother.reset()
        lockedExercise = initialLocked
        lockedConfidence = if (initialLocked != null) 0.70f else 0f
        pendingCandidate = null
        pendingStreak = 0
    }

    fun processFrame(
        rawScreenPoints: List<PosePoint>,
        rawWorldPoints: List<PosePoint> = emptyList(),
        timestampMs: Long
    ): AutoDetectResult {
        if (rawScreenPoints.size < 29) {
            return AutoDetectResult(
                detectedExercise = lockedExercise,
                confidence = lockedConfidence,
                candidateExercise = pendingCandidate,
                isLocked = lockedExercise != null,
                statusFr = "Placez-vous dans le cadre…"
            )
        }

        val screenPts = screenSmoother.smooth(rawScreenPoints, timestampMs)
        val hasWorld = rawWorldPoints.size >= 29
        val worldPts = if (hasWorld) worldSmoother.smooth(rawWorldPoints, timestampMs) else emptyList()
        val vp = CameraViewpointDetector.detect(screenPts, worldPts)
        val use3D = vp.is3DActive && worldPts.size >= 29
        val g = if (use3D) worldPts else screenPts

        val sample = extractSample(screenPts, g, vp, use3D, timestampMs)
        buffer.addLast(sample)
        while (buffer.size > maxBufferFrames) {
            buffer.removeFirst()
        }
        while (buffer.size > minFramesForDetection && (timestampMs - buffer.first().timestampMs) > maxWindowMs) {
            buffer.removeFirst()
        }

        if (buffer.size < minFramesForDetection) {
            return AutoDetectResult(
                detectedExercise = lockedExercise,
                confidence = lockedConfidence,
                candidateExercise = null,
                isLocked = lockedExercise != null,
                statusFr = "Calibration du mouvement…"
            )
        }

        val scores = scoreAllExercises()
        val sorted = scores.entries.sortedByDescending { it.value }
        val top = sorted[0]
        val second = sorted.getOrElse(1) { top }
        val margin = top.value - second.value

        val topEx = top.key
        val topConf = top.value

        if (topConf >= confirmThreshold && margin >= 0.06f) {
            if (topEx == lockedExercise) {
                lockedConfidence = (lockedConfidence * 0.7f + topConf * 0.3f).coerceIn(0f, 1f)
                pendingCandidate = topEx
                pendingStreak = confirmConsecutiveFrames
            } else {
                if (pendingCandidate == topEx) {
                    pendingStreak++
                } else {
                    pendingCandidate = topEx
                    pendingStreak = 1
                }
                val isCrossPostureSwitch = lockedExercise != null &&
                    postureFamily(lockedExercise!!) != postureFamily(topEx) &&
                    topConf >= 0.72f
                val requiredStreak = if (isCrossPostureSwitch) fastPostureSwitchFrames else confirmConsecutiveFrames
                if (pendingStreak >= requiredStreak) {
                    lockedExercise = topEx
                    lockedConfidence = topConf
                }
            }
        } else {
            // Decay pending streak gently on ambiguous transition frames
            if (pendingStreak > 0) pendingStreak = (pendingStreak - 1).coerceAtLeast(0)
            if (topEx == lockedExercise && topConf >= 0.50f) {
                lockedConfidence = (lockedConfidence * 0.85f + topConf * 0.15f).coerceIn(0f, 1f)
            }
        }

        val statusFr = when {
            lockedExercise != null -> "AUTO · ${lockedExercise!!.labelFr} ${(lockedConfidence * 100f).roundToInt()}%"
            pendingCandidate != null && topConf >= 0.50f -> "Détection : ${pendingCandidate!!.labelFr}…"
            else -> "En attente de mouvement…"
        }

        return AutoDetectResult(
            detectedExercise = lockedExercise,
            confidence = if (lockedExercise != null) lockedConfidence else topConf,
            candidateExercise = if (topConf >= 0.45f) topEx else null,
            isLocked = lockedExercise != null,
            statusFr = statusFr,
            scores = scores
        )
    }

    private fun extractSample(
        screenPts: List<PosePoint>,
        g: List<PosePoint>,
        vp: ViewpointInfo,
        use3D: Boolean,
        timestampMs: Long
    ): FrameSample {
        fun ang(a: Int, b: Int, c: Int): Float =
            if (use3D) BiomechanicsEngine.angleDegrees3D(g[a], g[b], g[c])
            else BiomechanicsEngine.angleDegrees(g[a], g[b], g[c])

        fun dist(a: PosePoint, b: PosePoint): Float =
            if (use3D) BiomechanicsEngine.dist3D(a, b) else BiomechanicsEngine.dist2D(a, b)

        val lSh = g[BiomechanicsEngine.LEFT_SHOULDER]
        val rSh = g[BiomechanicsEngine.RIGHT_SHOULDER]
        val lHip = g[BiomechanicsEngine.LEFT_HIP]
        val rHip = g[BiomechanicsEngine.RIGHT_HIP]
        val lKnee = g[BiomechanicsEngine.LEFT_KNEE]
        val rKnee = g[BiomechanicsEngine.RIGHT_KNEE]
        val lAnkle = g[BiomechanicsEngine.LEFT_ANKLE]
        val rAnkle = g[BiomechanicsEngine.RIGHT_ANKLE]
        val lWrist = g[BiomechanicsEngine.LEFT_WRIST]
        val rWrist = g[BiomechanicsEngine.RIGHT_WRIST]

        val midSh = BiomechanicsEngine.midpoint(lSh, rSh)
        val midHip = BiomechanicsEngine.midpoint(lHip, rHip)
        val midKnee = BiomechanicsEngine.midpoint(lKnee, rKnee)
        val midAnkle = BiomechanicsEngine.midpoint(lAnkle, rAnkle)
        val midWrist = BiomechanicsEngine.midpoint(lWrist, rWrist)

        val torsoLen = dist(midSh, midHip).coerceAtLeast(if (use3D) 0.25f else 0.08f)

        val leftKnee = ang(BiomechanicsEngine.LEFT_HIP, BiomechanicsEngine.LEFT_KNEE, BiomechanicsEngine.LEFT_ANKLE)
        val rightKnee = ang(BiomechanicsEngine.RIGHT_HIP, BiomechanicsEngine.RIGHT_KNEE, BiomechanicsEngine.RIGHT_ANKLE)
        val leftHip = ang(BiomechanicsEngine.LEFT_SHOULDER, BiomechanicsEngine.LEFT_HIP, BiomechanicsEngine.LEFT_KNEE)
        val rightHip = ang(BiomechanicsEngine.RIGHT_SHOULDER, BiomechanicsEngine.RIGHT_HIP, BiomechanicsEngine.RIGHT_KNEE)
        val leftElbow = ang(BiomechanicsEngine.LEFT_SHOULDER, BiomechanicsEngine.LEFT_ELBOW, BiomechanicsEngine.LEFT_WRIST)
        val rightElbow = ang(BiomechanicsEngine.RIGHT_SHOULDER, BiomechanicsEngine.RIGHT_ELBOW, BiomechanicsEngine.RIGHT_WRIST)
        val leftSh = ang(BiomechanicsEngine.LEFT_HIP, BiomechanicsEngine.LEFT_SHOULDER, BiomechanicsEngine.LEFT_ELBOW)
        val rightSh = ang(BiomechanicsEngine.RIGHT_HIP, BiomechanicsEngine.RIGHT_SHOULDER, BiomechanicsEngine.RIGHT_ELBOW)
        val leftArm = ang(BiomechanicsEngine.LEFT_HIP, BiomechanicsEngine.LEFT_SHOULDER, BiomechanicsEngine.LEFT_WRIST)
        val rightArm = ang(BiomechanicsEngine.RIGHT_HIP, BiomechanicsEngine.RIGHT_SHOULDER, BiomechanicsEngine.RIGHT_WRIST)

        val useLeft = vp.dominantSideLeft
        val both = vp.bothSidesVisible

        // For split lower-body movements (lunge/bulgarian), minOf tracks active bending knee cleanly
        val kneeDeg = if (both) minOf(leftKnee, rightKnee) * 0.6f + ((leftKnee + rightKnee) * 0.5f) * 0.4f else if (useLeft) leftKnee else rightKnee
        val hipDeg = if (both) (leftHip + rightHip) * 0.5f else if (useLeft) leftHip else rightHip
        val elbowDeg = if (both) (leftElbow + rightElbow) * 0.5f else if (useLeft) leftElbow else rightElbow
        val shoulderDeg = if (both) (leftSh + rightSh) * 0.5f else if (useLeft) leftSh else rightSh
        val armFullDeg = if (both) (leftArm + rightArm) * 0.5f else if (useLeft) leftArm else rightArm

        val torsoTiltDeg = if (use3D) {
            BiomechanicsEngine.verticalTilt3D(midSh, midHip)
        } else {
            BiomechanicsEngine.verticalTiltDegrees(midSh, midHip)
        }
        val bodyTiltDeg = if (use3D) {
            BiomechanicsEngine.verticalTilt3D(midSh, midAnkle)
        } else {
            BiomechanicsEngine.verticalTiltDegrees(midSh, midAnkle)
        }
        val bodyAlignDeg = if (use3D) {
            BiomechanicsEngine.bodyLineAlignment3D(midSh, midHip, midAnkle)
        } else {
            BiomechanicsEngine.bodyLineAlignmentDegrees(midSh, midHip, midAnkle)
        }

        // Torso-length-normalized relative vertical heights (+Y is down in MediaPipe)
        val hipAboveAnklesTl = (midAnkle.y - midHip.y) / torsoLen
        val shAboveAnklesTl = (midAnkle.y - midSh.y) / torsoLen
        val wristAboveShTl = (midSh.y - midWrist.y) / torsoLen
        val wristAboveHipTl = (midHip.y - midWrist.y) / torsoLen
        val ankleAboveHipTl = (midHip.y - midAnkle.y) / torsoLen
        val ankleAboveShTl = (midSh.y - midAnkle.y) / torsoLen
        val kneeAboveShTl = (midSh.y - midKnee.y) / torsoLen

        val footDistTl = dist(lAnkle, rAnkle) / torsoLen
        val ankleYDiffTl = abs(lAnkle.y - rAnkle.y) / torsoLen
        val shWidth = dist(lSh, rSh).coerceAtLeast(torsoLen * 0.45f)
        val ankleToShoulderRatio = dist(lAnkle, rAnkle) / shWidth

        val screenMidShY = (screenPts[BiomechanicsEngine.LEFT_SHOULDER].y + screenPts[BiomechanicsEngine.RIGHT_SHOULDER].y) * 0.5f
        val screenMidWristY = (screenPts[BiomechanicsEngine.LEFT_WRIST].y + screenPts[BiomechanicsEngine.RIGHT_WRIST].y) * 0.5f

        val posture = classifyPosture(
            bodyTiltDeg = bodyTiltDeg,
            torsoTiltDeg = torsoTiltDeg,
            wristAboveShTl = wristAboveShTl,
            wristAboveHipTl = wristAboveHipTl,
            kneeAboveShTl = kneeAboveShTl,
            ankleAboveHipTl = ankleAboveHipTl,
            hipAboveAnklesTl = hipAboveAnklesTl,
            shAboveAnklesTl = shAboveAnklesTl,
            kneeDeg = kneeDeg
        )

        return FrameSample(
            timestampMs = timestampMs,
            posture = posture,
            kneeDeg = kneeDeg,
            hipDeg = hipDeg,
            elbowDeg = elbowDeg,
            shoulderDeg = shoulderDeg,
            armFullDeg = armFullDeg,
            torsoTiltDeg = torsoTiltDeg,
            bodyTiltDeg = bodyTiltDeg,
            bodyAlignDeg = bodyAlignDeg,
            hipAboveAnklesTl = hipAboveAnklesTl,
            shAboveAnklesTl = shAboveAnklesTl,
            wristAboveShTl = wristAboveShTl,
            wristAboveHipTl = wristAboveHipTl,
            ankleAboveHipTl = ankleAboveShTl,
            footDistTl = footDistTl,
            ankleYDiffTl = ankleYDiffTl,
            ankleToShoulderRatio = ankleToShoulderRatio,
            screenShY = screenMidShY,
            screenWristY = screenMidWristY
        )
    }

    private fun classifyPosture(
        bodyTiltDeg: Float,
        torsoTiltDeg: Float,
        wristAboveShTl: Float,
        wristAboveHipTl: Float,
        kneeAboveShTl: Float,
        ankleAboveHipTl: Float,
        hipAboveAnklesTl: Float,
        shAboveAnklesTl: Float,
        kneeDeg: Float
    ): BodyPosture {
        // Supine floor: shoulders down on floor, knees raised above shoulders (glute bridge) OR torso flat & legs rising (leg raise)
        val looksSupineBridge = torsoTiltDeg >= 45f && kneeAboveShTl >= 0.10f && kneeDeg <= 135f && abs(shAboveAnklesTl) < 0.70f
        val looksSupineLegRaise = torsoTiltDeg >= 62f && ankleAboveHipTl >= -0.25f && wristAboveShTl >= -0.30f && wristAboveHipTl <= 0.45f && shAboveAnklesTl <= 0.40f
        if (looksSupineBridge || looksSupineLegRaise) {
            return BodyPosture.SUPINE_FLOOR
        }

        // Prone floor: horizontal body with hands supporting below shoulders toward floor level
        val looksProne = (bodyTiltDeg >= 52f || torsoTiltDeg >= 58f) &&
            wristAboveShTl <= -0.15f &&
            hipAboveAnklesTl <= 0.95f &&
            shAboveAnklesTl <= 1.25f
        if (looksProne) {
            return BodyPosture.PRONE_FLOOR
        }

        // Suspended high hands (pull-up hang)
        if (torsoTiltDeg < 35f && wristAboveShTl >= 0.35f) {
            return BodyPosture.SUSPENDED
        }

        return BodyPosture.STANDING
    }

    private fun scoreAllExercises(): Map<ExerciseType, Float> {
        val n = buffer.size.toFloat()

        var standingCount = 0
        var proneCount = 0
        var supineCount = 0
        var suspendedCount = 0

        val knees = FloatArray(buffer.size)
        val hips = FloatArray(buffer.size)
        val elbows = FloatArray(buffer.size)
        val shoulders = FloatArray(buffer.size)
        val armsFull = FloatArray(buffer.size)
        val torsoTilts = FloatArray(buffer.size)
        val bodyTilts = FloatArray(buffer.size)
        val bodyAligns = FloatArray(buffer.size)
        val hipAboveAnk = FloatArray(buffer.size)
        val shAboveAnk = FloatArray(buffer.size)
        val wristAboveSh = FloatArray(buffer.size)
        val wristAboveHip = FloatArray(buffer.size)
        val ankleAboveHip = FloatArray(buffer.size)
        val footDists = FloatArray(buffer.size)
        val ankleYDiffs = FloatArray(buffer.size)
        val ankleShRatios = FloatArray(buffer.size)
        val screenShYs = FloatArray(buffer.size)
        val screenWristYs = FloatArray(buffer.size)

        buffer.forEachIndexed { i, s ->
            when (s.posture) {
                BodyPosture.STANDING -> standingCount++
                BodyPosture.PRONE_FLOOR -> proneCount++
                BodyPosture.SUPINE_FLOOR -> supineCount++
                BodyPosture.SUSPENDED -> suspendedCount++
                BodyPosture.DYNAMIC_TRANSITION -> Unit
            }
            knees[i] = s.kneeDeg
            hips[i] = s.hipDeg
            elbows[i] = s.elbowDeg
            shoulders[i] = s.shoulderDeg
            armsFull[i] = s.armFullDeg
            torsoTilts[i] = s.torsoTiltDeg
            bodyTilts[i] = s.bodyTiltDeg
            bodyAligns[i] = s.bodyAlignDeg
            hipAboveAnk[i] = s.hipAboveAnklesTl
            shAboveAnk[i] = s.shAboveAnklesTl
            wristAboveSh[i] = s.wristAboveShTl
            wristAboveHip[i] = s.wristAboveHipTl
            ankleAboveHip[i] = s.ankleAboveHipTl
            footDists[i] = s.footDistTl
            ankleYDiffs[i] = s.ankleYDiffTl
            ankleShRatios[i] = s.ankleToShoulderRatio
            screenShYs[i] = s.screenShY
            screenWristYs[i] = s.screenWristY
        }

        val standingRatio = (standingCount + suspendedCount) / n
        val proneRatio = proneCount / n
        val supineRatio = supineCount / n

        val kneeS = stats(knees)
        val hipS = stats(hips)
        val elbowS = stats(elbows)
        val shS = stats(shoulders)
        val armS = stats(armsFull)
        val torsoS = stats(torsoTilts)
        val bodyTiltS = stats(bodyTilts)
        val alignS = stats(bodyAligns)
        val hipAnkS = stats(hipAboveAnk)
        val shAnkS = stats(shAboveAnk)
        val wrShS = stats(wristAboveSh)
        val wrHipS = stats(wristAboveHip)
        val ankHipS = stats(ankleAboveHip)
        val footS = stats(footDists)
        val ankYDiffS = stats(ankleYDiffs)
        val ankRatioS = stats(ankleShRatios)
        val scrShS = stats(screenShYs)
        val scrWrS = stats(screenWristYs)

        val scrShMotionRatio = scrShS.range / (scrShS.range + scrWrS.range + 0.0001f)

        val scores = LinkedHashMap<ExerciseType, Float>(16)

        // 1. SQUAT: Standing bilateral deep knee bend, symmetric feet, moderate torso lean
        scores[ExerciseType.SQUAT] = product(
            rampUp(standingRatio, 0.55f, 0.85f),
            rampUp(kneeS.range, 24f, 55f),
            rampDown(kneeS.min, 112f, 138f),
            rampUp(hipAnkS.range, 0.16f, 0.42f),
            rampDown(footS.mean, 0.72f, 1.05f), // narrow/shoulder stance, not split lunge
            rampDown(ankYDiffS.mean, 0.14f, 0.28f), // both feet on floor
            rampDown(torsoS.mean, 38f, 56f),
            rampUp(kneeS.range / (hipS.range + 1f), 0.50f, 0.82f), // knees bend well vs pure deadlift
            rampDown(proneRatio, 0.05f, 0.20f)
        )

        // 2. LUNGE: Split stance fore-aft (wide 3D foot distance), both feet near floor, knee bend
        scores[ExerciseType.LUNGE] = product(
            rampUp(standingRatio, 0.55f, 0.85f),
            rampUp(kneeS.range, 22f, 50f),
            rampDown(kneeS.min, 112f, 138f),
            rampUp(footS.mean, 0.78f, 1.15f), // clear split stance
            rampDown(ankYDiffS.mean, 0.16f, 0.30f), // rear foot on floor (not elevated like Bulgarian)
            rampDown(torsoS.mean, 26f, 44f),
            rampDown(proneRatio, 0.05f, 0.20f)
        )

        // 3. BULGARIAN_SQUAT: Split stance + rear foot clearly elevated off floor
        scores[ExerciseType.BULGARIAN_SQUAT] = product(
            rampUp(standingRatio, 0.55f, 0.85f),
            rampUp(kneeS.range, 20f, 48f),
            rampDown(kneeS.min, 115f, 140f),
            rampUp(footS.mean, 0.58f, 0.95f),
            rampUp(ankYDiffS.mean, 0.24f, 0.48f), // rear foot elevated on bench
            rampDown(torsoS.mean, 34f, 52f),
            rampDown(proneRatio, 0.05f, 0.20f)
        )

        // 4. DEADLIFT: Dominant hip hinge, torso tilts strongly, knees less flexed, straight arms below hips
        scores[ExerciseType.DEADLIFT] = product(
            rampUp(standingRatio, 0.55f, 0.85f),
            rampUp(hipS.range, 26f, 55f),
            rampDown(hipS.min, 115f, 138f),
            rampUp(torsoS.max, 34f, 55f),
            rampUp(torsoS.range, 18f, 40f),
            rampUp(kneeS.min, 108f, 132f), // knees stay high compared to squat
            rampUp(hipS.range / (kneeS.range + 1f), 1.10f, 1.60f),
            rampDown(wrHipS.mean, 0.10f, 0.35f),
            rampUp(elbowS.mean, 130f, 155f),
            rampDown(elbowS.range, 28f, 50f),
            rampDown(proneRatio, 0.05f, 0.20f)
        )

        // 5. PUSHUP: Prone horizontal + dynamic elbow flexion/extension
        scores[ExerciseType.PUSHUP] = product(
            rampUp(proneRatio, 0.55f, 0.85f),
            rampUp(bodyTiltS.mean, 48f, 68f),
            rampUp(elbowS.range, 22f, 50f),
            rampDown(elbowS.min, 115f, 140f),
            rampDown(wrShS.mean, -0.10f, 0.15f),
            rampDown(standingRatio, 0.12f, 0.30f)
        )

        // 6. PLANK: Prone horizontal + isometric stability (very low joint excursion)
        scores[ExerciseType.PLANK] = product(
            rampUp(proneRatio, 0.65f, 0.90f),
            rampUp(bodyTiltS.mean, 52f, 70f),
            band(alignS.mean, 130f, 155f, 200f, 225f),
            rampDown(elbowS.range, 12f, 24f),
            rampDown(hipS.range, 12f, 24f),
            rampDown(kneeS.range, 12f, 24f),
            rampDown(bodyTiltS.range, 10f, 22f),
            rampDown(standingRatio, 0.05f, 0.20f)
        )

        // 7. BURPEE: Dynamic standing <-> prone transition with huge vertical excursion
        val hasBothPostures = minOf(standingRatio, proneRatio)
        scores[ExerciseType.BURPEE] = product(
            rampUp(hasBothPostures, 0.12f, 0.30f),
            rampUp(bodyTiltS.range, 34f, 60f),
            rampUp(shAnkS.range, 0.55f, 1.10f)
        )

        // 8. OVERHEAD_PRESS: Standing upright, dynamic elbows pressing wrists high above shoulders, shoulders static
        scores[ExerciseType.OVERHEAD_PRESS] = product(
            rampUp(standingRatio, 0.70f, 0.92f),
            rampDown(torsoS.mean, 18f, 34f),
            rampDown(kneeS.range, 16f, 32f),
            rampUp(elbowS.range, 30f, 62f),
            rampUp(elbowS.max, 132f, 158f),
            rampDown(elbowS.min, 105f, 130f),
            rampUp(wrShS.max, 0.45f, 0.85f),
            rampUp(wrShS.range, 0.30f, 0.65f),
            rampDown(scrShMotionRatio, 0.32f, 0.55f), // wrists move, shoulders stay still
            rampDown(footS.range, 0.24f, 0.45f)
        )

        // 9. BICEP_CURL: Standing upright, dynamic elbow flexion, upper arms tucked close to ribs, wrists stay low
        scores[ExerciseType.BICEP_CURL] = product(
            rampUp(standingRatio, 0.70f, 0.92f),
            rampDown(torsoS.mean, 18f, 32f),
            rampDown(kneeS.range, 16f, 32f),
            rampUp(elbowS.range, 32f, 65f),
            rampDown(elbowS.min, 85f, 112f),
            rampUp(elbowS.max, 122f, 148f),
            rampDown(shS.max, 38f, 60f),
            rampDown(shS.range, 22f, 42f),
            rampDown(wrShS.max, 0.22f, 0.45f),
            rampDown(scrShMotionRatio, 0.35f, 0.58f)
        )

        // 10. TRICEP_DIP: Upright/slight lean, wrists anchored near hips, dynamic elbows, shoulders move vertically
        scores[ExerciseType.TRICEP_DIP] = product(
            rampUp(standingRatio, 0.55f, 0.85f),
            rampDown(torsoS.mean, 34f, 50f),
            rampDown(kneeS.range, 18f, 36f),
            rampUp(elbowS.range, 26f, 55f),
            rampDown(elbowS.min, 108f, 130f),
            rampDown(wrShS.mean, -0.30f, -0.08f),
            band(wrHipS.mean, -0.55f, -0.35f, 0.28f, 0.50f),
            rampUp(scrShMotionRatio, 0.42f, 0.68f) // body dips down/up while hands stay planted
        )

        // 11. JUMPING_JACK: Standing upright, high arm abduction + dynamic leg abduction
        scores[ExerciseType.JUMPING_JACK] = product(
            rampUp(standingRatio, 0.70f, 0.92f),
            rampDown(torsoS.mean, 18f, 32f),
            rampUp(armS.range, 48f, 95f),
            rampUp(armS.max, 112f, 142f),
            rampUp(elbowS.mean, 110f, 140f),
            rampUp(footS.range, 0.28f, 0.62f),
            rampUp(ankRatioS.max, 1.02f, 1.32f),
            rampDown(kneeS.range, 22f, 42f)
        )

        // 12. PULL_UP: Upright body, hands fixed high overhead, dynamic elbows pulling body upward
        scores[ExerciseType.PULL_UP] = product(
            rampUp(standingRatio, 0.65f, 0.90f),
            rampDown(torsoS.mean, 24f, 42f),
            rampDown(kneeS.range, 22f, 42f),
            rampUp(elbowS.range, 30f, 62f),
            rampDown(elbowS.min, 98f, 122f),
            rampUp(wrShS.max, 0.55f, 0.95f),
            rampUp(wrShS.mean, 0.15f, 0.45f),
            rampUp(scrShMotionRatio, 0.45f, 0.72f) // body pulls toward anchored hands
        )

        // 13. BENT_ROW: Sustained forward torso hinge + dynamic elbow rowing pull
        scores[ExerciseType.BENT_ROW] = product(
            rampUp(standingRatio, 0.55f, 0.85f),
            band(torsoS.mean, 20f, 32f, 68f, 84f),
            rampUp(torsoS.min, 16f, 28f),
            rampDown(torsoS.range, 22f, 38f), // torso held fixed unlike deadlift
            rampDown(kneeS.range, 18f, 34f),
            rampUp(elbowS.range, 26f, 55f),
            rampDown(elbowS.min, 102f, 126f),
            rampDown(wrShS.mean, -0.10f, 0.12f)
        )

        // 14. GLUTE_BRIDGE: Supine on floor, knees bent, dynamic hip extension
        scores[ExerciseType.GLUTE_BRIDGE] = product(
            rampUp(supineRatio, 0.45f, 0.80f),
            band(kneeS.mean, 40f, 65f, 118f, 138f),
            rampUp(hipS.range, 18f, 42f),
            rampUp(hipS.max, 140f, 162f),
            rampDown(ankHipS.range, 0.28f, 0.52f) // feet stay grounded
        )

        // 15. LEG_RAISE: Supine flat on floor, straight legs lifting dynamically
        scores[ExerciseType.LEG_RAISE] = product(
            rampUp(supineRatio, 0.45f, 0.80f),
            rampUp(torsoS.mean, 56f, 74f),
            rampUp(kneeS.mean, 120f, 148f),
            rampUp(hipS.range, 24f, 55f),
            rampDown(hipS.min, 115f, 142f),
            rampUp(ankHipS.range, 0.40f, 0.85f) // ankles rise high
        )

        // 16. LATERAL_RAISE: Standing upright, arms abduct to ~90° with straight elbows, stationary legs
        scores[ExerciseType.LATERAL_RAISE] = product(
            rampUp(standingRatio, 0.70f, 0.92f),
            rampDown(torsoS.mean, 18f, 32f),
            rampDown(kneeS.range, 16f, 32f),
            rampUp(shS.range, 28f, 58f),
            band(shS.max, 55f, 74f, 104f, 124f),
            rampDown(shS.min, 36f, 56f),
            rampUp(elbowS.mean, 118f, 145f),
            rampDown(elbowS.range, 26f, 46f),
            rampDown(wrShS.max, 0.22f, 0.46f),
            rampDown(footS.range, 0.20f, 0.38f)
        )

        return scores
    }

    private fun postureFamily(ex: ExerciseType): Int = when (ex) {
        ExerciseType.PUSHUP, ExerciseType.PLANK -> 1
        ExerciseType.GLUTE_BRIDGE, ExerciseType.LEG_RAISE -> 2
        ExerciseType.BURPEE -> 3
        else -> 0
    }

    private data class SeriesStats(
        val min: Float,
        val max: Float,
        val range: Float,
        val mean: Float,
        val stdDev: Float
    )

    private fun stats(values: FloatArray): SeriesStats {
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        var sum = 0.0
        for (v in values) {
            if (v < mn) mn = v
            if (v > mx) mx = v
            sum += v
        }
        val mean = (sum / values.size).toFloat()
        var varSum = 0.0
        for (v in values) {
            val d = v - mean
            varSum += d * d
        }
        val std = sqrt(varSum / values.size).toFloat()
        return SeriesStats(mn, mx, (mx - mn).coerceAtLeast(0f), mean, std)
    }

    private fun rampUp(v: Float, lo: Float, hi: Float): Float =
        ((v - lo) / (hi - lo).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)

    private fun rampDown(v: Float, opt: Float, zeroAt: Float): Float =
        ((zeroAt - v) / (zeroAt - opt).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)

    private fun band(v: Float, lo: Float, optLo: Float, optHi: Float, hi: Float): Float = when {
        v <= lo || v >= hi -> 0f
        v < optLo -> rampUp(v, lo, optLo)
        v > optHi -> rampDown(v, optHi, hi)
        else -> 1f
    }

    /** Geometric-weighted fusion preventing a single mild imperfection from zeroing an otherwise strong signature while still zeroing invalid postures. */
    private fun product(vararg factors: Float): Float {
        var prod = 1.0
        for (f in factors) {
            if (f <= 0.001f) return 0f
            prod *= f.toDouble()
        }
        // Gentle root calibration keeps scores interpretable in [0..1] across differing factor counts
        val calibrated = Math.pow(prod, 1.0 / (1.0 + (factors.size - 3).coerceAtLeast(0) * 0.22))
        return calibrated.toFloat().coerceIn(0f, 1f)
    }
}
