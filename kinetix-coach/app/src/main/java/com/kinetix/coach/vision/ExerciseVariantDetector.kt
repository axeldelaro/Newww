package com.kinetix.coach.vision

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import kotlin.math.abs

/**
 * Biomechanical variant classification for any of the 16 KINETIX exercises, carrying both
 * the French UI label and kinematic adaptation flags consumed by [BiomechanicsEngine].
 */
data class DetectedVariant(
    val id: String,
    val labelFr: String,
    val exemptsSymmetry: Boolean = false,
    val exemptsHighHip: Boolean = false,
    val exemptsElbowFlare: Boolean = false,
    val exemptsTorsoLean: Boolean = false,
    val usesKneeAlignment: Boolean = false,
    val usesUnilateralLimb: Boolean = false,
    val preferredActiveLeft: Boolean? = null
)

/**
 * Frame-level kinematic inputs for [ExerciseVariantDetector].
 */
data class VariantKinematicsInput(
    val exercise: ExerciseType,
    val phase: MovementPhase,
    val use3D: Boolean,
    val bothVisible: Boolean,
    val useLeft: Boolean,
    val screenPts: List<PosePoint>,
    val geomPts: List<PosePoint>,
    val lArmW: Float,
    val rArmW: Float,
    val lLegW: Float,
    val rLegW: Float,
    val leftKnee: Float,
    val rightKnee: Float,
    val kneeAngle: Float,
    val leftHip: Float,
    val rightHip: Float,
    val hipAngle: Float,
    val leftElbow: Float,
    val rightElbow: Float,
    val elbowAngle: Float,
    val leftShoulder: Float,
    val rightShoulder: Float,
    val shoulderAngle: Float,
    val armFullAngle: Float,
    val torsoTilt: Float,
    val fullBodyTilt: Float,
    val bodyLineAlign: Float,
    val verticalSpeedMps: Float = 0f,
    val deterministicPassThrough: Boolean = false
)

/**
 * Multi-exercise, viewpoint-invariant exercise variant classifier with temporal majority-vote
 * hysteresis and intra-rep variant latching so the detected variant pill is stable and accurate
 * across all 16 KINETIX exercises.
 */
class ExerciseVariantDetector {

    companion object {
        private const val WINDOW_SIZE = 9
        private const val SWITCH_CONFIRM_VOTES = 5
    }

    private val voteWindow = ArrayDeque<DetectedVariant>(WINDOW_SIZE + 1)
    private var stableVariant: DetectedVariant? = null
    private var latchedRepVariant: DetectedVariant? = null
    private var lastExercise: ExerciseType? = null

    // Cross-rep alternation trackers (detects Alterné vs Unilatéral across consecutive reps)
    private var lastActiveSideLeft: Boolean? = null
    private var sawSideAlternation: Boolean = false
    private var pushupInsideBurpeeDetected: Boolean = false
    private var jumpInsideExerciseDetected: Boolean = false
    private var kneeDipBeforePressDetected: Boolean = false

    fun reset() {
        voteWindow.clear()
        stableVariant = null
        latchedRepVariant = null
        lastExercise = null
        lastActiveSideLeft = null
        sawSideAlternation = false
        pushupInsideBurpeeDetected = false
        jumpInsideExerciseDetected = false
        kneeDipBeforePressDetected = false
    }

    fun onRepCompleted(activeSideLeft: Boolean? = null) {
        if (activeSideLeft != null) {
            if (lastActiveSideLeft != null && lastActiveSideLeft != activeSideLeft) {
                sawSideAlternation = true
            }
            lastActiveSideLeft = activeSideLeft
        }
        latchedRepVariant = null
        pushupInsideBurpeeDetected = false
        jumpInsideExerciseDetected = false
        kneeDipBeforePressDetected = false
    }

    fun detect(input: VariantKinematicsInput): DetectedVariant {
        if (lastExercise != input.exercise) {
            reset()
            lastExercise = input.exercise
        }

        val raw = classifyRaw(input)

        if (input.deterministicPassThrough) {
            stableVariant = raw
            return raw
        }

        // Dynamic intra-rep variants (Archer, ATG, Push Press, Avec Pompe, Sauté) latch cleanly until rep ends
        if (raw.id == "ARCHER" || raw.id == "PISTOL" || raw.id == "ATG" ||
            raw.id == "PUSH_PRESS" || raw.id == "BURPEE_PUSHUP" || raw.id == "JUMP"
        ) {
            latchedRepVariant = raw
        } else if (input.phase == MovementPhase.READY) {
            latchedRepVariant = null
        }

        val effectiveRaw = if (latchedRepVariant != null && input.phase != MovementPhase.READY) {
            latchedRepVariant!!
        } else {
            raw
        }

        voteWindow.addLast(effectiveRaw)
        while (voteWindow.size > WINDOW_SIZE) {
            voteWindow.removeFirst()
        }

        val current = stableVariant
        if (current == null) {
            stableVariant = effectiveRaw
            return effectiveRaw
        }

        if (effectiveRaw.id == current.id) {
            // Refresh live flags (e.g. preferredActiveLeft) while keeping stable label
            stableVariant = effectiveRaw
            return effectiveRaw
        }

        // Immediate promotion for high-confidence latched rep variants (e.g. Archer mid-rep)
        if (latchedRepVariant != null && effectiveRaw.id == latchedRepVariant!!.id) {
            stableVariant = effectiveRaw
            return effectiveRaw
        }

        // Majority vote hysteresis to eliminate single-frame variant flicker
        val votesForCandidate = voteWindow.count { it.id == effectiveRaw.id }
        if (votesForCandidate >= SWITCH_CONFIRM_VOTES) {
            stableVariant = effectiveRaw
            return effectiveRaw
        }

        return current
    }

    private fun classifyRaw(i: VariantKinematicsInput): DetectedVariant {
        val g = i.geomPts
        val s = i.screenPts
        val bothArmsVis = i.lArmW >= 0.42f && i.rArmW >= 0.42f
        val bothLegsVis = i.lLegW >= 0.40f && i.rLegW >= 0.40f

        val shWidth = if (i.use3D) {
            BiomechanicsEngine.dist3D(g[BiomechanicsEngine.LEFT_SHOULDER], g[BiomechanicsEngine.RIGHT_SHOULDER]).coerceAtLeast(0.18f)
        } else {
            BiomechanicsEngine.dist2D(s[BiomechanicsEngine.LEFT_SHOULDER], s[BiomechanicsEngine.RIGHT_SHOULDER]).coerceAtLeast(0.05f)
        }
        val midSh = if (i.bothVisible) {
            BiomechanicsEngine.midpoint(g[BiomechanicsEngine.LEFT_SHOULDER], g[BiomechanicsEngine.RIGHT_SHOULDER])
        } else {
            g[if (i.useLeft) BiomechanicsEngine.LEFT_SHOULDER else BiomechanicsEngine.RIGHT_SHOULDER]
        }
        val midHip = if (i.bothVisible) {
            BiomechanicsEngine.midpoint(g[BiomechanicsEngine.LEFT_HIP], g[BiomechanicsEngine.RIGHT_HIP])
        } else {
            g[if (i.useLeft) BiomechanicsEngine.LEFT_HIP else BiomechanicsEngine.RIGHT_HIP]
        }
        val torsoLen = if (i.use3D) {
            BiomechanicsEngine.dist3D(midSh, midHip).coerceAtLeast(0.28f)
        } else {
            BiomechanicsEngine.dist2D(midSh, midHip).coerceAtLeast(0.08f)
        }

        val canMeasureHandSpan = (i.use3D && i.lArmW >= 0.35f && i.rArmW >= 0.35f) ||
            (i.bothVisible && bothArmsVis)
        val wristSpan = if (i.use3D) {
            BiomechanicsEngine.dist3D(g[BiomechanicsEngine.LEFT_WRIST], g[BiomechanicsEngine.RIGHT_WRIST])
        } else {
            BiomechanicsEngine.dist2D(s[BiomechanicsEngine.LEFT_WRIST], s[BiomechanicsEngine.RIGHT_WRIST])
        }
        val wristRatio = if (canMeasureHandSpan) wristSpan / shWidth else 1.0f

        val canMeasureAnkleSpan = (i.use3D && i.lLegW >= 0.35f && i.rLegW >= 0.35f) ||
            (i.bothVisible && bothLegsVis)
        val ankleSpan = if (i.use3D) {
            BiomechanicsEngine.dist3D(g[BiomechanicsEngine.LEFT_ANKLE], g[BiomechanicsEngine.RIGHT_ANKLE])
        } else {
            BiomechanicsEngine.dist2D(s[BiomechanicsEngine.LEFT_ANKLE], s[BiomechanicsEngine.RIGHT_ANKLE])
        }
        val ankleRatio = if (canMeasureAnkleSpan) ankleSpan / shWidth else 1.0f

        val midWristY = if (bothArmsVis) {
            (g[BiomechanicsEngine.LEFT_WRIST].y + g[BiomechanicsEngine.RIGHT_WRIST].y) * 0.5f
        } else {
            g[if (i.useLeft) BiomechanicsEngine.LEFT_WRIST else BiomechanicsEngine.RIGHT_WRIST].y
        }
        val midAnkleY = if (bothLegsVis) {
            (g[BiomechanicsEngine.LEFT_ANKLE].y + g[BiomechanicsEngine.RIGHT_ANKLE].y) * 0.5f
        } else {
            g[if (i.useLeft) BiomechanicsEngine.LEFT_ANKLE else BiomechanicsEngine.RIGHT_ANKLE].y
        }

        return when (i.exercise) {
            ExerciseType.PUSHUP -> classifyPushup(
                i, g, s, bothArmsVis, bothLegsVis, wristRatio, canMeasureHandSpan,
                midSh.y, midWristY, midAnkleY, torsoLen
            )
            ExerciseType.SQUAT -> classifySquat(i, g, bothLegsVis, ankleRatio, canMeasureAnkleSpan, torsoLen)
            ExerciseType.LUNGE -> classifyLunge(i, bothLegsVis, ankleRatio, canMeasureAnkleSpan)
            ExerciseType.BULGARIAN_SQUAT -> classifyBulgarian(i)
            ExerciseType.DEADLIFT -> classifyDeadlift(i, g, bothLegsVis, ankleRatio, canMeasureAnkleSpan, torsoLen)
            ExerciseType.OVERHEAD_PRESS -> classifyOhp(i, bothArmsVis)
            ExerciseType.BICEP_CURL -> classifyBicepCurl(i, bothArmsVis, wristRatio, canMeasureHandSpan)
            ExerciseType.TRICEP_DIP -> classifyDip(i)
            ExerciseType.PLANK -> classifyPlank(i, g, s, shWidth, bothLegsVis, torsoLen)
            ExerciseType.BURPEE -> classifyBurpee(i, midSh.y, midWristY, torsoLen)
            ExerciseType.JUMPING_JACK -> classifyJumpingJack(i, ankleRatio, canMeasureAnkleSpan)
            ExerciseType.PULL_UP -> classifyPullUp(i, bothArmsVis, wristRatio, canMeasureHandSpan)
            ExerciseType.BENT_ROW -> classifyBentRow(i, bothArmsVis, wristRatio, canMeasureHandSpan)
            ExerciseType.GLUTE_BRIDGE -> classifyGluteBridge(i, g, bothLegsVis, ankleRatio, canMeasureAnkleSpan, torsoLen)
            ExerciseType.LEG_RAISE -> classifyLegRaise(i, bothLegsVis, midSh.y, midWristY, torsoLen)
            ExerciseType.LATERAL_RAISE -> classifyLateralRaise(i, g, bothArmsVis)
        }
    }

    private fun classifyPushup(
        i: VariantKinematicsInput,
        g: List<PosePoint>,
        s: List<PosePoint>,
        bothArmsVis: Boolean,
        bothLegsVis: Boolean,
        wristRatio: Float,
        canMeasureHandSpan: Boolean,
        midShY: Float,
        midWristY: Float,
        midAnkleY: Float,
        torsoLen: Float
    ): DetectedVariant {
        val minElbow = minOf(i.leftElbow, i.rightElbow)
        val maxElbow = maxOf(i.leftElbow, i.rightElbow)
        val isProneBody = i.torsoTilt >= 24f || i.fullBodyTilt >= 24f

        val leftAnkleAboveKnee = s[BiomechanicsEngine.LEFT_ANKLE].visibility >= 0.35f &&
            s[BiomechanicsEngine.LEFT_KNEE].visibility >= 0.35f &&
            g[BiomechanicsEngine.LEFT_ANKLE].y < g[BiomechanicsEngine.LEFT_KNEE].y - 0.03f &&
            i.leftKnee < 145f
        val rightAnkleAboveKnee = s[BiomechanicsEngine.RIGHT_ANKLE].visibility >= 0.35f &&
            s[BiomechanicsEngine.RIGHT_KNEE].visibility >= 0.35f &&
            g[BiomechanicsEngine.RIGHT_ANKLE].y < g[BiomechanicsEngine.RIGHT_KNEE].y - 0.03f &&
            i.rightKnee < 145f
        val ankleLifted = when {
            bothLegsVis -> leftAnkleAboveKnee || rightAnkleAboveKnee
            i.useLeft -> leftAnkleAboveKnee
            else -> rightAnkleAboveKnee
        }
        val isKnee = isProneBody && (ankleLifted || i.kneeAngle < 126f)

        val handsNearFloor = midWristY >= midShY - (if (i.use3D) 0.12f else 0.06f)
        val isPike = !isKnee && i.hipAngle < 126f && i.bodyLineAlign > 204f && handsNearFloor

        val isArcher = bothArmsVis && maxElbow > 144f && minElbow < 116f

        // Incline push-up: hands elevated clearly above feet on bench/box/wall, straight inclined plank
        val wristsAboveAnklesTl = (midAnkleY - midWristY) / torsoLen
        val isIncline = !isKnee && !isPike &&
            wristsAboveAnklesTl >= 0.34f &&
            i.fullBodyTilt in 22f..62f &&
            i.bodyLineAlign in 148f..212f

        // Decline push-up: feet elevated clearly above hands/shoulders (ankles higher = smaller Y)
        val anklesAboveWristsTl = (midWristY - midAnkleY) / torsoLen
        val isDecline = !isKnee && !isPike &&
            anklesAboveWristsTl >= 0.38f &&
            midAnkleY <= midShY + torsoLen * 0.14f

        val isWide = canMeasureHandSpan && wristRatio > 1.52f
        val isDiamond = canMeasureHandSpan && wristRatio < 0.55f
        val isClose = canMeasureHandSpan && wristRatio in 0.55f..0.82f

        return when {
            isPike -> DetectedVariant(
                id = "PIKE",
                labelFr = PushupVariant.PIKE.labelFr,
                exemptsHighHip = true,
                exemptsElbowFlare = true
            )
            isArcher -> DetectedVariant(
                id = "ARCHER",
                labelFr = PushupVariant.ARCHER.labelFr,
                exemptsSymmetry = true,
                exemptsElbowFlare = true,
                usesUnilateralLimb = true,
                preferredActiveLeft = i.leftElbow <= i.rightElbow
            )
            isKnee -> DetectedVariant(
                id = "KNEE",
                labelFr = PushupVariant.KNEE.labelFr,
                usesKneeAlignment = true
            )
            isDecline -> DetectedVariant(
                id = "DECLINE",
                labelFr = PushupVariant.DECLINE.labelFr
            )
            isIncline -> DetectedVariant(
                id = "INCLINE",
                labelFr = PushupVariant.INCLINE.labelFr
            )
            isWide -> DetectedVariant(
                id = "WIDE",
                labelFr = PushupVariant.WIDE.labelFr,
                exemptsElbowFlare = true
            )
            isDiamond -> DetectedVariant(
                id = "DIAMOND",
                labelFr = PushupVariant.DIAMOND.labelFr
            )
            isClose -> DetectedVariant(
                id = "CLOSE",
                labelFr = PushupVariant.CLOSE.labelFr
            )
            else -> DetectedVariant(
                id = "STANDARD",
                labelFr = PushupVariant.STANDARD.labelFr
            )
        }
    }

    private fun classifySquat(
        i: VariantKinematicsInput,
        g: List<PosePoint>,
        bothLegsVis: Boolean,
        ankleRatio: Float,
        canMeasureAnkleSpan: Boolean,
        torsoLen: Float
    ): DetectedVariant {
        val minKnee = minOf(i.leftKnee, i.rightKnee)
        val maxKnee = maxOf(i.leftKnee, i.rightKnee)
        val ankleYDiffTl = if (bothLegsVis) {
            abs(g[BiomechanicsEngine.LEFT_ANKLE].y - g[BiomechanicsEngine.RIGHT_ANKLE].y) / torsoLen
        } else 0f

        val isPistol = bothLegsVis && minKnee < 118f && maxKnee > 148f && ankleYDiffTl > 0.16f
        if (i.phase == MovementPhase.CONCENTRIC && i.verticalSpeedMps >= 1.35f) {
            jumpInsideExerciseDetected = true
        }

        return when {
            isPistol -> DetectedVariant(
                id = "PISTOL",
                labelFr = "Pistol",
                exemptsSymmetry = true,
                usesUnilateralLimb = true,
                preferredActiveLeft = i.leftKnee <= i.rightKnee
            )
            jumpInsideExerciseDetected -> DetectedVariant(
                id = "JUMP",
                labelFr = "Sauté"
            )
            i.kneeAngle < 74f -> DetectedVariant(
                id = "ATG",
                labelFr = "Profond (ATG)"
            )
            canMeasureAnkleSpan && ankleRatio > 1.48f -> DetectedVariant(
                id = "SUMO",
                labelFr = "Sumo"
            )
            canMeasureAnkleSpan && ankleRatio < 0.62f -> DetectedVariant(
                id = "NARROW",
                labelFr = "Serré"
            )
            else -> DetectedVariant(
                id = "STANDARD",
                labelFr = "Classique"
            )
        }
    }

    private fun classifyLunge(
        i: VariantKinematicsInput,
        bothLegsVis: Boolean,
        ankleRatio: Float,
        canMeasureAnkleSpan: Boolean
    ): DetectedVariant {
        val minKnee = minOf(i.leftKnee, i.rightKnee)
        val maxKnee = maxOf(i.leftKnee, i.rightKnee)
        val isLateral = bothLegsVis && canMeasureAnkleSpan &&
            ankleRatio > 1.55f && maxKnee > 148f && minKnee < 126f

        if (i.phase == MovementPhase.CONCENTRIC && i.verticalSpeedMps >= 1.30f) {
            jumpInsideExerciseDetected = true
        }

        return when {
            isLateral -> DetectedVariant(
                id = "LATERAL",
                labelFr = "Latérale",
                exemptsSymmetry = true,
                exemptsTorsoLean = true,
                usesUnilateralLimb = true,
                preferredActiveLeft = i.leftKnee <= i.rightKnee
            )
            jumpInsideExerciseDetected -> DetectedVariant(
                id = "JUMP",
                labelFr = "Sautée"
            )
            sawSideAlternation -> DetectedVariant(
                id = "ALTERNATING",
                labelFr = "Alternée"
            )
            else -> DetectedVariant(
                id = "STANDARD",
                labelFr = "Classique"
            )
        }
    }

    private fun classifyBulgarian(i: VariantKinematicsInput): DetectedVariant {
        return if (i.torsoTilt >= 22f) {
            DetectedVariant(
                id = "GLUTE_BIAS",
                labelFr = "Fessiers"
            )
        } else {
            DetectedVariant(
                id = "QUAD_BIAS",
                labelFr = "Quadriceps"
            )
        }
    }

    private fun classifyDeadlift(
        i: VariantKinematicsInput,
        g: List<PosePoint>,
        bothLegsVis: Boolean,
        ankleRatio: Float,
        canMeasureAnkleSpan: Boolean,
        torsoLen: Float
    ): DetectedVariant {
        val ankleYDiffTl = if (bothLegsVis) {
            abs(g[BiomechanicsEngine.LEFT_ANKLE].y - g[BiomechanicsEngine.RIGHT_ANKLE].y) / torsoLen
        } else 0f
        val isSingleLeg = bothLegsVis && ankleYDiffTl > 0.24f && i.torsoTilt > 22f
        val groundedLeft = g[BiomechanicsEngine.LEFT_ANKLE].y >= g[BiomechanicsEngine.RIGHT_ANKLE].y

        val isSumo = canMeasureAnkleSpan && ankleRatio > 1.48f && !isSingleLeg
        val isRdl = !isSingleLeg && !isSumo && i.hipAngle < 142f && i.kneeAngle >= 148f

        return when {
            isSingleLeg -> DetectedVariant(
                id = "SINGLE_LEG_RDL",
                labelFr = "Unilatéral",
                exemptsSymmetry = true,
                usesUnilateralLimb = true,
                preferredActiveLeft = groundedLeft
            )
            isSumo -> DetectedVariant(
                id = "SUMO",
                labelFr = "Sumo"
            )
            isRdl -> DetectedVariant(
                id = "RDL",
                labelFr = "Roumain (RDL)"
            )
            else -> DetectedVariant(
                id = "CONVENTIONAL",
                labelFr = "Conventionnel"
            )
        }
    }

    private fun classifyOhp(
        i: VariantKinematicsInput,
        bothArmsVis: Boolean
    ): DetectedVariant {
        val isAsymmetric = i.bothVisible && bothArmsVis && abs(i.leftElbow - i.rightElbow) > 32f
        val activeLeft = i.leftElbow >= i.rightElbow

        if (i.phase == MovementPhase.READY || i.phase == MovementPhase.CONCENTRIC) {
            if (i.kneeAngle in 115f..150f && i.hipAngle > 130f) {
                kneeDipBeforePressDetected = true
            }
        }
        val isSeated = i.hipAngle in 70f..120f && i.kneeAngle in 70f..120f

        return when {
            isAsymmetric -> DetectedVariant(
                id = if (sawSideAlternation) "ALTERNATING" else "UNILATERAL",
                labelFr = if (sawSideAlternation) "Alterné" else "Unilatéral",
                exemptsSymmetry = true,
                usesUnilateralLimb = true,
                preferredActiveLeft = activeLeft
            )
            isSeated -> DetectedVariant(
                id = "SEATED",
                labelFr = "Assis"
            )
            kneeDipBeforePressDetected -> DetectedVariant(
                id = "PUSH_PRESS",
                labelFr = "Push Press"
            )
            else -> DetectedVariant(
                id = "STRICT",
                labelFr = "Strict"
            )
        }
    }

    private fun classifyBicepCurl(
        i: VariantKinematicsInput,
        bothArmsVis: Boolean,
        wristRatio: Float,
        canMeasureHandSpan: Boolean
    ): DetectedVariant {
        val isAsymmetric = i.bothVisible && bothArmsVis && abs(i.leftElbow - i.rightElbow) > 35f
        val activeLeft = i.leftElbow <= i.rightElbow
        return when {
            isAsymmetric -> DetectedVariant(
                id = "ALTERNATING",
                labelFr = "Alterné",
                exemptsSymmetry = true,
                usesUnilateralLimb = true,
                preferredActiveLeft = activeLeft
            )
            canMeasureHandSpan && wristRatio > 1.42f -> DetectedVariant(
                id = "WIDE_GRIP",
                labelFr = "Prise large"
            )
            canMeasureHandSpan && wristRatio < 0.72f -> DetectedVariant(
                id = "CLOSE_GRIP",
                labelFr = "Prise serrée"
            )
            else -> DetectedVariant(
                id = "BILATERAL",
                labelFr = "Bilatéral"
            )
        }
    }

    private fun classifyDip(i: VariantKinematicsInput): DetectedVariant {
        val isBenchDip = i.hipAngle in 68f..128f && i.kneeAngle > 115f
        val isChestDip = !isBenchDip && i.torsoTilt in 22f..44f
        return when {
            isBenchDip -> DetectedVariant(
                id = "BENCH",
                labelFr = "Banc"
            )
            isChestDip -> DetectedVariant(
                id = "CHEST",
                labelFr = "Pectoraux",
                exemptsTorsoLean = true
            )
            else -> DetectedVariant(
                id = "TRICEPS",
                labelFr = "Triceps"
            )
        }
    }

    private fun classifyPlank(
        i: VariantKinematicsInput,
        g: List<PosePoint>,
        s: List<PosePoint>,
        shWidth: Float,
        bothLegsVis: Boolean,
        torsoLen: Float
    ): DetectedVariant {
        val isSidePlank = i.bothVisible &&
            abs(g[BiomechanicsEngine.LEFT_SHOULDER].y - g[BiomechanicsEngine.RIGHT_SHOULDER].y) > shWidth * 0.50f

        val leftAnkleAboveKnee = s[BiomechanicsEngine.LEFT_ANKLE].visibility >= 0.35f &&
            s[BiomechanicsEngine.LEFT_KNEE].visibility >= 0.35f &&
            g[BiomechanicsEngine.LEFT_ANKLE].y < g[BiomechanicsEngine.LEFT_KNEE].y - 0.03f &&
            g[BiomechanicsEngine.LEFT_KNEE].y >= g[BiomechanicsEngine.LEFT_HIP].y - 0.02f &&
            i.leftKnee < 145f
        val rightAnkleAboveKnee = s[BiomechanicsEngine.RIGHT_ANKLE].visibility >= 0.35f &&
            s[BiomechanicsEngine.RIGHT_KNEE].visibility >= 0.35f &&
            g[BiomechanicsEngine.RIGHT_ANKLE].y < g[BiomechanicsEngine.RIGHT_KNEE].y - 0.03f &&
            g[BiomechanicsEngine.RIGHT_KNEE].y >= g[BiomechanicsEngine.RIGHT_HIP].y - 0.02f &&
            i.rightKnee < 145f
        val isKneePlank = !isSidePlank && (
            (bothLegsVis && leftAnkleAboveKnee && rightAnkleAboveKnee) ||
                (!bothLegsVis && (if (i.useLeft) leftAnkleAboveKnee else rightAnkleAboveKnee))
            )

        val ankleYDiffTl = if (bothLegsVis) {
            abs(g[BiomechanicsEngine.LEFT_ANKLE].y - g[BiomechanicsEngine.RIGHT_ANKLE].y) / torsoLen
        } else 0f
        val isThreePoint = !isSidePlank && !isKneePlank && bothLegsVis && ankleYDiffTl > 0.25f
        val groundedLeft = g[BiomechanicsEngine.LEFT_ANKLE].y >= g[BiomechanicsEngine.RIGHT_ANKLE].y

        val isForearm = !isSidePlank && i.elbowAngle in 55f..120f

        return when {
            isSidePlank -> DetectedVariant(id = "SIDE", labelFr = "Latérale")
            isKneePlank -> DetectedVariant(id = "KNEE", labelFr = "Genoux", usesKneeAlignment = true)
            isThreePoint -> DetectedVariant(
                id = "THREE_POINT",
                labelFr = "3 Appuis",
                usesUnilateralLimb = true,
                preferredActiveLeft = groundedLeft
            )
            isForearm -> DetectedVariant(id = "FOREARM", labelFr = "Avant-bras")
            else -> DetectedVariant(id = "HIGH", labelFr = "Haute")
        }
    }

    private fun classifyBurpee(
        i: VariantKinematicsInput,
        midShY: Float,
        midWristY: Float,
        torsoLen: Float
    ): DetectedVariant {
        if (i.torsoTilt >= 48f && minOf(i.leftElbow, i.rightElbow) < 120f) {
            pushupInsideBurpeeDetected = true
        }
        val handsOverhead = (midShY - midWristY) / torsoLen > 0.35f && i.torsoTilt < 25f
        if (handsOverhead) {
            jumpInsideExerciseDetected = true
        }
        return when {
            pushupInsideBurpeeDetected -> DetectedVariant(id = "BURPEE_PUSHUP", labelFr = "Avec Pompe")
            jumpInsideExerciseDetected -> DetectedVariant(id = "JUMP", labelFr = "Complet")
            else -> DetectedVariant(id = "STANDARD", labelFr = "Classique")
        }
    }

    private fun classifyJumpingJack(
        i: VariantKinematicsInput,
        ankleRatio: Float,
        canMeasureAnkleSpan: Boolean
    ): DetectedVariant {
        val isStar = i.kneeAngle < 142f && i.armFullAngle > 110f
        val isLowImpact = canMeasureAnkleSpan && i.armFullAngle > 110f && ankleRatio < 1.15f
        return when {
            isStar -> DetectedVariant(id = "STAR", labelFr = "Étoile")
            isLowImpact -> DetectedVariant(id = "LOW_IMPACT", labelFr = "Demi-Jack")
            else -> DetectedVariant(id = "STANDARD", labelFr = "Classique")
        }
    }

    private fun classifyPullUp(
        i: VariantKinematicsInput,
        bothArmsVis: Boolean,
        wristRatio: Float,
        canMeasureHandSpan: Boolean
    ): DetectedVariant {
        val minElbow = minOf(i.leftElbow, i.rightElbow)
        val maxElbow = maxOf(i.leftElbow, i.rightElbow)
        val isArcher = i.bothVisible && bothArmsVis && maxElbow > 140f && minElbow < 112f
        val isLSit = i.hipAngle < 120f && i.kneeAngle > 135f
        return when {
            isArcher -> DetectedVariant(
                id = "ARCHER",
                labelFr = "Archer",
                exemptsSymmetry = true,
                usesUnilateralLimb = true,
                preferredActiveLeft = i.leftElbow <= i.rightElbow
            )
            isLSit -> DetectedVariant(id = "L_SIT", labelFr = "L-Sit")
            canMeasureHandSpan && wristRatio > 1.45f -> DetectedVariant(id = "WIDE", labelFr = "Prise large")
            canMeasureHandSpan && wristRatio < 0.82f -> DetectedVariant(id = "CLOSE", labelFr = "Prise serrée")
            else -> DetectedVariant(id = "STANDARD", labelFr = "Classique")
        }
    }

    private fun classifyBentRow(
        i: VariantKinematicsInput,
        bothArmsVis: Boolean,
        wristRatio: Float,
        canMeasureHandSpan: Boolean
    ): DetectedVariant {
        val isUnilateral = i.bothVisible && bothArmsVis && abs(i.leftElbow - i.rightElbow) > 32f
        val isPendlay = i.torsoTilt >= 64f
        val isYates = i.torsoTilt in 24f..38f
        return when {
            isUnilateral -> DetectedVariant(
                id = "UNILATERAL",
                labelFr = "Unilatéral",
                exemptsSymmetry = true,
                usesUnilateralLimb = true,
                preferredActiveLeft = i.leftElbow <= i.rightElbow
            )
            isPendlay -> DetectedVariant(
                id = "PENDLAY",
                labelFr = "Pendlay",
                exemptsTorsoLean = true
            )
            isYates -> DetectedVariant(id = "YATES", labelFr = "Yates")
            canMeasureHandSpan && wristRatio > 1.45f -> DetectedVariant(id = "WIDE", labelFr = "Prise large")
            else -> DetectedVariant(id = "BILATERAL", labelFr = "Bilatéral")
        }
    }

    private fun classifyGluteBridge(
        i: VariantKinematicsInput,
        g: List<PosePoint>,
        bothLegsVis: Boolean,
        ankleRatio: Float,
        canMeasureAnkleSpan: Boolean,
        torsoLen: Float
    ): DetectedVariant {
        val ankleYDiffTl = if (bothLegsVis) {
            abs(g[BiomechanicsEngine.LEFT_ANKLE].y - g[BiomechanicsEngine.RIGHT_ANKLE].y) / torsoLen
        } else 0f
        val isSingleLeg = bothLegsVis && ankleYDiffTl > 0.25f
        val groundedLeft = g[BiomechanicsEngine.LEFT_ANKLE].y >= g[BiomechanicsEngine.RIGHT_ANKLE].y
        return when {
            isSingleLeg -> DetectedVariant(
                id = "SINGLE_LEG",
                labelFr = "Unilatéral",
                exemptsSymmetry = true,
                usesUnilateralLimb = true,
                preferredActiveLeft = groundedLeft
            )
            canMeasureAnkleSpan && ankleRatio > 1.45f -> DetectedVariant(id = "SUMO", labelFr = "Pieds larges")
            else -> DetectedVariant(id = "BILATERAL", labelFr = "Bilatéral")
        }
    }

    private fun classifyLegRaise(
        i: VariantKinematicsInput,
        bothLegsVis: Boolean,
        midShY: Float,
        midWristY: Float,
        torsoLen: Float
    ): DetectedVariant {
        val wristsOverheadTl = (midShY - midWristY) / torsoLen
        val isHanging = i.torsoTilt < 28f && wristsOverheadTl > 0.30f
        val isAlternating = i.bothVisible && bothLegsVis && abs(i.leftHip - i.rightHip) > 28f
        val isBentKnee = i.kneeAngle < 106f
        return when {
            isHanging -> DetectedVariant(id = "HANGING", labelFr = "Suspendu")
            isAlternating -> DetectedVariant(
                id = "ALTERNATING",
                labelFr = "Alterné",
                exemptsSymmetry = true,
                usesUnilateralLimb = true,
                preferredActiveLeft = i.leftHip <= i.rightHip
            )
            isBentKnee -> DetectedVariant(id = "BENT_KNEE", labelFr = "Genoux fléchis")
            else -> DetectedVariant(id = "STRAIGHT_LEG", labelFr = "Jambes tendues")
        }
    }

    private fun classifyLateralRaise(
        i: VariantKinematicsInput,
        g: List<PosePoint>,
        bothArmsVis: Boolean
    ): DetectedVariant {
        val isAsymmetric = i.bothVisible && bothArmsVis && abs(i.leftShoulder - i.rightShoulder) > 28f
        val isRearDelt = i.torsoTilt >= 34f

        // In 3D, compare wrist lateral spread vs forward Z reach relative to shoulders to detect Front Raises
        var isFrontRaise = false
        if (i.use3D && bothArmsVis && i.shoulderAngle > 45f) {
            val lSh = g[BiomechanicsEngine.LEFT_SHOULDER]
            val rSh = g[BiomechanicsEngine.RIGHT_SHOULDER]
            val lWr = g[BiomechanicsEngine.LEFT_WRIST]
            val rWr = g[BiomechanicsEngine.RIGHT_WRIST]
            val shSpan = BiomechanicsEngine.dist3D(lSh, rSh).coerceAtLeast(0.18f)
            val wrSpan = BiomechanicsEngine.dist3D(lWr, rWr)
            // When arms rise straight forward, wrists stay ~shoulder-width apart rather than widening to ~3x shoulder width
            if (wrSpan < shSpan * 1.45f) {
                isFrontRaise = true
            }
        }

        return when {
            isRearDelt -> DetectedVariant(
                id = "REAR_DELT",
                labelFr = "Oiseau",
                exemptsTorsoLean = true
            )
            isAsymmetric -> DetectedVariant(
                id = if (sawSideAlternation) "ALTERNATING" else "UNILATERAL",
                labelFr = if (sawSideAlternation) "Alterné" else "Unilatéral",
                exemptsSymmetry = true,
                usesUnilateralLimb = true,
                preferredActiveLeft = i.leftShoulder >= i.rightShoulder
            )
            isFrontRaise -> DetectedVariant(id = "FRONT_RAISE", labelFr = "Frontale")
            else -> DetectedVariant(id = "BILATERAL", labelFr = "Bilatéral")
        }
    }
}
