package com.kinetix.coach.vision

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

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
    val quality: FormQuality
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
    val poseDetected: Boolean
)

/**
 * Pure-Kotlin real-time biomechanics analyzer for all 16 KINETIX exercises.
 * Uses MediaPipe 33-landmark indexing with EMA smoothing (alpha = 0.42f).
 */
class BiomechanicsEngine(
    initialExercise: ExerciseType = ExerciseType.SQUAT,
    private val emaAlpha: Float = 0.42f
) {
    companion object {
        // MediaPipe Pose Landmark indices
        const val NOSE = 0
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

        /** Angle of segment (bottom -> top) relative to true vertical axis (0° = straight vertical). */
        fun verticalTiltDegrees(top: PosePoint, bottom: PosePoint): Float {
            val dx = abs(top.x - bottom.x)
            val dy = abs(bottom.y - top.y).coerceAtLeast(0.0001f)
            return (atan2(dx.toDouble(), dy.toDouble()) * 180.0 / PI).toFloat()
        }

        /** Signed line alignment around middle point (180° = perfectly straight; <180 = hip sag; >180 = hip pike). */
        fun bodyLineAlignmentDegrees(shoulder: PosePoint, hip: PosePoint, ankle: PosePoint): Float {
            val raw = angleDegrees(shoulder, hip, ankle)
            // Compute cross-product / height relative to chord shoulder-ankle
            val t = ((hip.x - shoulder.x) * (ankle.x - shoulder.x) +
                (hip.y - shoulder.y) * (ankle.y - shoulder.y)) /
                (((ankle.x - shoulder.x) * (ankle.x - shoulder.x) +
                    (ankle.y - shoulder.y) * (ankle.y - shoulder.y)).coerceAtLeast(0.0001f))
            val expectedHipY = shoulder.y + t * (ankle.y - shoulder.y)
            // In image coords, larger y = lower on screen (sagging toward ground)
            return if (hip.y > expectedHipY) raw else 360f - raw
        }

        fun dist2D(a: PosePoint, b: PosePoint): Float = hypot(a.x - b.x, a.y - b.y)
    }

    var currentExercise: ExerciseType = initialExercise
        private set

    private var smoothed: MutableList<PosePoint>? = null
    private var repCount: Int = 0
    private var phase: MovementPhase = MovementPhase.READY
    private var reachedBottom: Boolean = false
    private var burpeeStage: Int = 0 // 0=stand, 1=down/plank, 2=return stand
    private var plankHoldMs: Long = 0L
    private var lastFrameTsMs: Long = 0L

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
        smoothed = null
        repCount = 0
        phase = MovementPhase.READY
        reachedBottom = false
        burpeeStage = 0
        plankHoldMs = 0L
        lastFrameTsMs = 0L
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
        feedbackFr = "Placez votre corps entier dans le cadre (${currentExercise.cameraAngleHint}).",
        voiceCueFr = null,
        angleBadges = emptyList(),
        avgFormScore = computeAvgForm(),
        avgRomPct = computeAvgRom(),
        topFaults = computeTopFaults(),
        poseDetected = false
    )

    fun processFrame(rawLandmarks: List<PosePoint>, timestampMs: Long): BiomechanicsFrame {
        if (rawLandmarks.size < 29) {
            lastFrameTsMs = timestampMs
            return emptyFrame()
        }

        val pts = smoothLandmarks(rawLandmarks)
        val dtMs = if (lastFrameTsMs > 0L) (timestampMs - lastFrameTsMs).coerceIn(0L, 250L) else 0L
        lastFrameTsMs = timestampMs

        val leftVis = pts[LEFT_SHOULDER].visibility + pts[LEFT_HIP].visibility + pts[LEFT_KNEE].visibility
        val rightVis = pts[RIGHT_SHOULDER].visibility + pts[RIGHT_HIP].visibility + pts[RIGHT_KNEE].visibility
        val useLeft = leftVis >= rightVis

        val sh = pts[if (useLeft) LEFT_SHOULDER else RIGHT_SHOULDER]
        val el = pts[if (useLeft) LEFT_ELBOW else RIGHT_ELBOW]
        val wr = pts[if (useLeft) LEFT_WRIST else RIGHT_WRIST]
        val hp = pts[if (useLeft) LEFT_HIP else RIGHT_HIP]
        val kn = pts[if (useLeft) LEFT_KNEE else RIGHT_KNEE]
        val an = pts[if (useLeft) LEFT_ANKLE else RIGHT_ANKLE]

        val shIdx = if (useLeft) LEFT_SHOULDER else RIGHT_SHOULDER
        val elIdx = if (useLeft) LEFT_ELBOW else RIGHT_ELBOW
        val hpIdx = if (useLeft) LEFT_HIP else RIGHT_HIP
        val knIdx = if (useLeft) LEFT_KNEE else RIGHT_KNEE

        val kneeAngle = angleDegrees(hp, kn, an)
        val hipAngle = angleDegrees(sh, hp, kn)
        val elbowAngle = angleDegrees(sh, el, wr)
        val shoulderAngle = angleDegrees(hp, sh, el)
        val torsoTilt = verticalTiltDegrees(sh, hp)

        var score = 100
        var romPct = 0
        var feedback = "Posture maîtrisée — continuez."
        var faultMsg: String? = null
        var voiceCue: String? = null
        val badges = mutableListOf<JointAngleBadge>()

        when (currentExercise) {
            ExerciseType.SQUAT -> {
                romPct = mapRange(kneeAngle, 168f, 82f)
                if (torsoTilt > 44f) {
                    score -= 22
                    faultMsg = "Redressez la poitrine (buste trop penché)"
                }
                if (reachedBottom && kneeAngle < 120f && kneeAngle > 105f) {
                    score -= 10
                }
                // Check frontal valgus if both knees & ankles visible
                val kneeSpan = abs(pts[LEFT_KNEE].x - pts[RIGHT_KNEE].x)
                val ankleSpan = abs(pts[LEFT_ANKLE].x - pts[RIGHT_ANKLE].x)
                if (ankleSpan > 0.08f && kneeSpan < ankleSpan * 0.72f && kneeAngle < 130f) {
                    score -= 38
                    faultMsg = "Poussez les genoux vers l'extérieur (valgus)"
                }
                val completed = updateDownUpCycle(
                    metric = kneeAngle,
                    startThreshold = 152f,
                    bottomThreshold = 98f,
                    finishThreshold = 158f,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) {
                    voiceCue = if (repRomPeak < 75) "Descendez un peu plus bas" else "$repCount"
                }
                badges.add(JointAngleBadge(knIdx, kn.x, kn.y, kneeAngle.roundToInt(), scoreToQuality(score)))
                badges.add(JointAngleBadge(hpIdx, hp.x, hp.y, hipAngle.roundToInt(), if (torsoTilt > 44f) FormQuality.FAULT else FormQuality.GOOD))
                feedback = faultMsg ?: when (phase) {
                    MovementPhase.BOTTOM -> "Bonne profondeur (${kneeAngle.roundToInt()}°) — poussez !"
                    MovementPhase.ECCENTRIC -> "Contrôlez la descente, dos neutre."
                    MovementPhase.CONCENTRIC -> "Poussez fort dans tout le pied."
                    else -> "Prêt — descendez genoux dans l'axe."
                }
            }

            ExerciseType.PUSHUP -> {
                val align = bodyLineAlignmentDegrees(sh, hp, an)
                romPct = mapRange(elbowAngle, 165f, 82f)
                if (align < 158f) {
                    score -= 30
                    faultMsg = "Gainez les abdos : ne creusez pas le bas du dos"
                } else if (align > 202f) {
                    score -= 22
                    faultMsg = "Abaissez légèrement les hanches"
                }
                val completed = updateDownUpCycle(
                    metric = elbowAngle,
                    startThreshold = 148f,
                    bottomThreshold = 95f,
                    finishThreshold = 156f,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(elIdx, el.x, el.y, elbowAngle.roundToInt(), scoreToQuality(score)))
                badges.add(JointAngleBadge(hpIdx, hp.x, hp.y, align.roundToInt(), if (align in 160f..200f) FormQuality.GOOD else FormQuality.FAULT))
                feedback = faultMsg ?: when (phase) {
                    MovementPhase.BOTTOM -> "Amplitude validée — repoussez le sol !"
                    else -> "Corps aligné (${align.roundToInt()}°), coudes à 45°."
                }
            }

            ExerciseType.LUNGE, ExerciseType.BULGARIAN_SQUAT -> {
                val leftKnee = angleDegrees(pts[LEFT_HIP], pts[LEFT_KNEE], pts[LEFT_ANKLE])
                val rightKnee = angleDegrees(pts[RIGHT_HIP], pts[RIGHT_KNEE], pts[RIGHT_ANKLE])
                val minKnee = minOf(leftKnee, rightKnee)
                romPct = mapRange(minKnee, 165f, 88f)
                val maxTilt = if (currentExercise == ExerciseType.BULGARIAN_SQUAT) 34f else 28f
                if (torsoTilt > maxTilt) {
                    score -= 22
                    faultMsg = "Redressez le buste, regard droit devant"
                }
                val completed = updateDownUpCycle(
                    metric = minKnee,
                    startThreshold = 148f,
                    bottomThreshold = 102f,
                    finishThreshold = 155f,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(knIdx, kn.x, kn.y, minKnee.roundToInt(), scoreToQuality(score)))
                feedback = faultMsg ?: "Amplitude ${romPct}% — contrôlez la stabilité du genou."
            }

            ExerciseType.DEADLIFT -> {
                romPct = mapRange(hipAngle, 170f, 85f)
                if (kneeAngle < 115f && hipAngle > 110f) {
                    score -= 18
                    faultMsg = "Initiez par les hanches (évitez de squatter la barre)"
                }
                if (hipAngle > 185f) {
                    score -= 22
                    faultMsg = "Stoppez droit — ne cambrez pas en arrière"
                }
                val completed = updateDownUpCycle(
                    metric = hipAngle,
                    startThreshold = 150f,
                    bottomThreshold = 105f,
                    finishThreshold = 162f,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(hpIdx, hp.x, hp.y, hipAngle.roundToInt(), scoreToQuality(score)))
                badges.add(JointAngleBadge(knIdx, kn.x, kn.y, kneeAngle.roundToInt(), FormQuality.GOOD))
                feedback = faultMsg ?: when (phase) {
                    MovementPhase.BOTTOM -> "Charnière verrouillée — poussez les hanches !"
                    else -> "Dos neutre, barre proche des tibias."
                }
            }

            ExerciseType.OVERHEAD_PRESS -> {
                val leftEl = angleDegrees(pts[LEFT_SHOULDER], pts[LEFT_ELBOW], pts[LEFT_WRIST])
                val rightEl = angleDegrees(pts[RIGHT_SHOULDER], pts[RIGHT_ELBOW], pts[RIGHT_WRIST])
                val avgEl = (leftEl + rightEl) / 2f
                val overheadOk = wr.y < sh.y
                romPct = if (overheadOk) mapRange(avgEl, 75f, 165f) else mapRange(avgEl, 75f, 165f) / 2
                if (torsoTilt > 18f) {
                    score -= 25
                    faultMsg = "Serrez fessiers et abdos — évitez de cambrer"
                }
                val completed = updateUpDownCycle(
                    metric = avgEl,
                    startThreshold = 100f,
                    topThreshold = 155f,
                    finishThreshold = 95f,
                    extraTopCondition = overheadOk,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(elIdx, el.x, el.y, avgEl.roundToInt(), scoreToQuality(score)))
                feedback = faultMsg ?: "Poussée verticale stricte — verrouillez au-dessus."
            }

            ExerciseType.BICEP_CURL -> {
                romPct = mapRange(elbowAngle, 160f, 45f)
                val elbowDrift = abs(el.x - sh.x)
                val torsoLen = dist2D(sh, hp).coerceAtLeast(0.08f)
                if (elbowDrift > torsoLen * 0.42f && elbowAngle < 105f) {
                    score -= 24
                    faultMsg = "Gardez le coude fixe près des côtes"
                }
                if (torsoTilt > 16f) {
                    score -= 20
                    faultMsg = "Ne balancez pas le buste en arrière"
                }
                val completed = updateDownUpCycle(
                    metric = elbowAngle,
                    startThreshold = 140f,
                    bottomThreshold = 58f,
                    finishThreshold = 148f,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(elIdx, el.x, el.y, elbowAngle.roundToInt(), scoreToQuality(score)))
                feedback = faultMsg ?: "Flexion propre (${elbowAngle.roundToInt()}°), contrôlez la descente."
            }

            ExerciseType.TRICEP_DIP -> {
                romPct = mapRange(elbowAngle, 162f, 82f)
                if (torsoTilt > 38f) {
                    score -= 15
                    faultMsg = "Gardez le buste contrôlé, épaules abaissées"
                }
                val completed = updateDownUpCycle(
                    metric = elbowAngle,
                    startThreshold = 148f,
                    bottomThreshold = 95f,
                    finishThreshold = 155f,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(elIdx, el.x, el.y, elbowAngle.roundToInt(), scoreToQuality(score)))
                feedback = faultMsg ?: "Descendez jusqu'à 90° puis verrouillez proprement."
            }

            ExerciseType.PLANK -> {
                val align = bodyLineAlignmentDegrees(sh, hp, an)
                val isHorizontal = abs(sh.y - an.y) < abs(sh.x - an.x) * 0.75f
                val aligned = isHorizontal && align in 160f..195f
                if (aligned) {
                    plankHoldMs += dtMs
                    phase = MovementPhase.HOLD
                    romPct = 100
                    score = 98 - (abs(180f - align) * 0.8f).roundToInt().coerceAtMost(15)
                    feedback = "Alignement parfait (${align.roundToInt()}°) — maintenez !"
                    val sec = (plankHoldMs / 1000L).toInt()
                    if (sec > 0 && sec % 15 == 0 && (plankHoldMs - dtMs) / 1000L < sec) {
                        voiceCue = "$sec secondes, tenez bon !"
                    }
                } else {
                    phase = MovementPhase.PAUSE
                    romPct = 40
                    if (!isHorizontal) {
                        score = 60
                        feedback = "Mettez-vous en position planche de profil."
                    } else if (align < 160f) {
                        score = 52
                        faultMsg = "Remontez les hanches — bas du dos creusé"
                        feedback = faultMsg
                    } else {
                        score = 58
                        faultMsg = "Abaissez les hanches — fesses trop hautes"
                        feedback = faultMsg
                    }
                }
                badges.add(JointAngleBadge(hpIdx, hp.x, hp.y, align.roundToInt(), scoreToQuality(score)))
                badges.add(JointAngleBadge(shIdx, sh.x, sh.y, shoulderAngle.roundToInt(), FormQuality.GOOD))
            }

            ExerciseType.BURPEE -> {
                val bodyHorizontal = abs(sh.y - hp.y) < abs(sh.x - hp.x) * 0.55f
                val standingTall = hipAngle > 155f && kneeAngle > 155f && sh.y < hp.y
                when (burpeeStage) {
                    0 -> {
                        phase = if (kneeAngle < 130f) MovementPhase.ECCENTRIC else MovementPhase.READY
                        romPct = if (bodyHorizontal) 75 else mapRange(kneeAngle, 165f, 90f) / 2
                        if (bodyHorizontal) {
                            burpeeStage = 1
                            phase = MovementPhase.BOTTOM
                        }
                    }
                    1 -> {
                        phase = MovementPhase.BOTTOM
                        romPct = 100
                        if (standingTall) {
                            repCount++
                            recordRom(100)
                            burpeeStage = 0
                            phase = MovementPhase.READY
                            voiceCue = "$repCount"
                        } else if (!bodyHorizontal && sh.y < hp.y) {
                            phase = MovementPhase.CONCENTRIC
                        }
                    }
                }
                if (bodyHorizontal) {
                    val align = bodyLineAlignmentDegrees(sh, hp, an)
                    if (align < 152f) {
                        score -= 22
                        faultMsg = "Gainez le bassin en position planche"
                    }
                }
                badges.add(JointAngleBadge(hpIdx, hp.x, hp.y, hipAngle.roundToInt(), scoreToQuality(score)))
                feedback = faultMsg ?: when (burpeeStage) {
                    1 -> "Remontez explosif et sautez !"
                    else -> "Descendez en planche gainée puis enchaînez."
                }
            }

            ExerciseType.JUMPING_JACK -> {
                val leftArm = angleDegrees(pts[LEFT_HIP], pts[LEFT_SHOULDER], pts[LEFT_WRIST])
                val rightArm = angleDegrees(pts[RIGHT_HIP], pts[RIGHT_SHOULDER], pts[RIGHT_WRIST])
                val avgArm = (leftArm + rightArm) / 2f
                val shoulderW = dist2D(pts[LEFT_SHOULDER], pts[RIGHT_SHOULDER]).coerceAtLeast(0.06f)
                val ankleW = dist2D(pts[LEFT_ANKLE], pts[RIGHT_ANKLE])
                val legsSpread = ankleW > shoulderW * 1.15f
                romPct = mapRange(avgArm, 22f, 152f)
                if (avgArm > 130f && !legsSpread) {
                    score -= 18
                    faultMsg = "Écartez davantage les jambes en synchronisant"
                }
                val completed = updateUpDownCycle(
                    metric = avgArm,
                    startThreshold = 45f,
                    topThreshold = 138f,
                    finishThreshold = 42f,
                    extraTopCondition = legsSpread,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(shIdx, sh.x, sh.y, avgArm.roundToInt(), scoreToQuality(score)))
                feedback = faultMsg ?: "Rythme fluide — bras hauts et pieds écartés."
            }

            ExerciseType.PULL_UP -> {
                val avgEl = (
                    angleDegrees(pts[LEFT_SHOULDER], pts[LEFT_ELBOW], pts[LEFT_WRIST]) +
                        angleDegrees(pts[RIGHT_SHOULDER], pts[RIGHT_ELBOW], pts[RIGHT_WRIST])
                    ) / 2f
                val chinOverHands = pts[NOSE].y < ((pts[LEFT_WRIST].y + pts[RIGHT_WRIST].y) / 2f) + 0.03f
                romPct = mapRange(avgEl, 165f, 55f)
                if (reachedBottom && !chinOverHands && avgEl < 85f) {
                    score -= 15
                    faultMsg = "Tirez la poitrine plus haut vers la barre"
                }
                val completed = updateDownUpCycle(
                    metric = avgEl,
                    startThreshold = 148f,
                    bottomThreshold = 72f,
                    finishThreshold = 155f,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(elIdx, el.x, el.y, avgEl.roundToInt(), scoreToQuality(score)))
                feedback = faultMsg ?: "Tirez les coudes vers les hanches, amplitude complète."
            }

            ExerciseType.BENT_ROW -> {
                romPct = mapRange(elbowAngle, 160f, 68f)
                if (torsoTilt < 24f) {
                    score -= 22
                    faultMsg = "Penchez davantage le buste (35–55°)"
                } else if (torsoTilt > 80f) {
                    score -= 16
                    faultMsg = "Relevez légèrement le buste, dos plat"
                }
                val completed = updateDownUpCycle(
                    metric = elbowAngle,
                    startThreshold = 145f,
                    bottomThreshold = 82f,
                    finishThreshold = 150f,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(elIdx, el.x, el.y, elbowAngle.roundToInt(), scoreToQuality(score)))
                badges.add(JointAngleBadge(hpIdx, hp.x, hp.y, torsoTilt.roundToInt(), if (torsoTilt in 24f..80f) FormQuality.GOOD else FormQuality.FAULT))
                feedback = faultMsg ?: "Buste fixe (${torsoTilt.roundToInt()}°) — serrez les omoplates."
            }

            ExerciseType.GLUTE_BRIDGE -> {
                romPct = mapRange(hipAngle, 110f, 172f)
                if (hipAngle > 186f) {
                    score -= 20
                    faultMsg = "Ne cambrez pas les lombaires en haut"
                }
                val completed = updateUpDownCycle(
                    metric = hipAngle,
                    startThreshold = 128f,
                    topThreshold = 162f,
                    finishThreshold = 125f,
                    extraTopCondition = true,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(hpIdx, hp.x, hp.y, hipAngle.roundToInt(), scoreToQuality(score)))
                feedback = faultMsg ?: "Extension complète des hanches (${hipAngle.roundToInt()}°), serrez 1s."
            }

            ExerciseType.LEG_RAISE -> {
                romPct = mapRange(hipAngle, 170f, 92f)
                if (kneeAngle < 135f) {
                    score -= 18
                    faultMsg = "Gardez les jambes plus tendues"
                }
                val completed = updateDownUpCycle(
                    metric = hipAngle,
                    startThreshold = 152f,
                    bottomThreshold = 104f,
                    finishThreshold = 156f,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(hpIdx, hp.x, hp.y, hipAngle.roundToInt(), scoreToQuality(score)))
                feedback = faultMsg ?: "Contrôlez la descente, lombaires collées au sol."
            }

            ExerciseType.LATERAL_RAISE -> {
                val leftArm = angleDegrees(pts[LEFT_HIP], pts[LEFT_SHOULDER], pts[LEFT_ELBOW])
                val rightArm = angleDegrees(pts[RIGHT_HIP], pts[RIGHT_SHOULDER], pts[RIGHT_ELBOW])
                val avgArm = (leftArm + rightArm) / 2f
                romPct = mapRange(avgArm, 15f, 90f)
                if (avgArm > 108f) {
                    score -= 18
                    faultMsg = "Stoppez à l'horizontale (90°) sans hausser les trapèzes"
                }
                if (torsoTilt > 15f) {
                    score -= 20
                    faultMsg = "Ne balancez pas le buste"
                }
                val completed = updateUpDownCycle(
                    metric = avgArm,
                    startThreshold = 28f,
                    topThreshold = 78f,
                    finishThreshold = 26f,
                    extraTopCondition = true,
                    currentRom = romPct,
                    fault = faultMsg
                )
                if (completed) voiceCue = "$repCount"
                badges.add(JointAngleBadge(shIdx, sh.x, sh.y, avgArm.roundToInt(), scoreToQuality(score)))
                feedback = faultMsg ?: "Élevez jusqu'à 90° (${avgArm.roundToInt()}°), épaules basses."
            }
        }

        val clampedScore = score.coerceIn(0, 100)
        formScoreSum += clampedScore
        formScoreSamples++
        if (faultMsg != null) {
            faultCounts[faultMsg] = (faultCounts[faultMsg] ?: 0) + 1
            if (voiceCue == null) voiceCue = faultMsg
        }

        return BiomechanicsFrame(
            exercise = currentExercise,
            landmarks = pts,
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
            poseDetected = true
        )
    }

    /** State machine for movements whose active bottom phase decreases the primary angle (e.g. Squat, Pushup, Curl). */
    private fun updateDownUpCycle(
        metric: Float,
        startThreshold: Float,
        bottomThreshold: Float,
        finishThreshold: Float,
        currentRom: Int,
        fault: String?
    ): Boolean {
        if (currentRom > repRomPeak) repRomPeak = currentRom
        if (fault != null) currentRepFault = fault

        when (phase) {
            MovementPhase.READY -> {
                if (metric < startThreshold) {
                    phase = MovementPhase.ECCENTRIC
                    reachedBottom = false
                    repRomPeak = currentRom
                    currentRepFault = fault
                }
            }
            MovementPhase.ECCENTRIC -> {
                if (metric <= bottomThreshold) {
                    phase = MovementPhase.BOTTOM
                    reachedBottom = true
                } else if (metric >= finishThreshold) {
                    // Abandoned shallow attempt
                    phase = MovementPhase.READY
                    repRomPeak = 0
                }
            }
            MovementPhase.BOTTOM -> {
                if (metric > bottomThreshold + 12f) {
                    phase = MovementPhase.CONCENTRIC
                }
            }
            MovementPhase.CONCENTRIC -> {
                if (metric >= finishThreshold && reachedBottom) {
                    repCount++
                    recordRom(repRomPeak)
                    phase = MovementPhase.READY
                    reachedBottom = false
                    repRomPeak = 0
                    currentRepFault = null
                    return true
                }
            }
            else -> phase = MovementPhase.READY
        }
        return false
    }

    /** State machine for movements whose active peak phase increases the primary angle (e.g. OHP, Lateral Raise, Glute Bridge). */
    private fun updateUpDownCycle(
        metric: Float,
        startThreshold: Float,
        topThreshold: Float,
        finishThreshold: Float,
        extraTopCondition: Boolean,
        currentRom: Int,
        fault: String?
    ): Boolean {
        if (currentRom > repRomPeak) repRomPeak = currentRom
        if (fault != null) currentRepFault = fault

        when (phase) {
            MovementPhase.READY -> {
                if (metric > startThreshold) {
                    phase = MovementPhase.CONCENTRIC
                    reachedBottom = false
                    repRomPeak = currentRom
                    currentRepFault = fault
                }
            }
            MovementPhase.CONCENTRIC -> {
                if (metric >= topThreshold && extraTopCondition) {
                    phase = MovementPhase.BOTTOM // peak reached
                    reachedBottom = true
                } else if (metric <= finishThreshold) {
                    phase = MovementPhase.READY
                    repRomPeak = 0
                }
            }
            MovementPhase.BOTTOM -> {
                if (metric < topThreshold - 10f) {
                    phase = MovementPhase.ECCENTRIC
                }
            }
            MovementPhase.ECCENTRIC -> {
                if (metric <= finishThreshold && reachedBottom) {
                    repCount++
                    recordRom(repRomPeak)
                    phase = MovementPhase.READY
                    reachedBottom = false
                    repRomPeak = 0
                    currentRepFault = null
                    return true
                }
            }
            else -> phase = MovementPhase.READY
        }
        return false
    }

    private fun smoothLandmarks(raw: List<PosePoint>): List<PosePoint> {
        val prev = smoothed
        if (prev == null || prev.size != raw.size) {
            val copy = raw.toMutableList()
            smoothed = copy
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
