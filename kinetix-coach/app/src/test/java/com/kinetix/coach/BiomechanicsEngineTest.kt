package com.kinetix.coach

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import com.kinetix.coach.vision.BiomechanicsEngine
import com.kinetix.coach.vision.FormQuality
import com.kinetix.coach.vision.PosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class BiomechanicsEngineTest {

    private fun blankPose(): MutableList<PosePoint> =
        MutableList(33) { PosePoint(0.5f, 0.5f, 0f, 1f) }

    /**
     * Synthesizes a 33-landmark profile squat frame with exact target knee angle (degrees)
     * and optional torso forward lean (degrees from vertical) or frontal valgus.
     */
    private fun makeSquatPose(
        kneeAngleDeg: Float,
        torsoLeanDeg: Float = 15f,
        valgus: Boolean = false
    ): List<PosePoint> {
        val pts = blankPose()
        val ankle = PosePoint(0.50f, 0.90f, 0f, 1f)
        val knee = PosePoint(0.50f, 0.65f, 0f, 1f) // vertical shin
        // Vector from knee -> ankle is (0, +0.25). Place hip at angle kneeAngleDeg from shin vector.
        val rad = kneeAngleDeg * PI / 180.0
        val thighLen = 0.25f
        val hip = PosePoint(
            x = knee.x - (thighLen * sin(rad)).toFloat(),
            y = knee.y + (thighLen * cos(rad)).toFloat(),
            z = 0f,
            visibility = 1f
        )
        val leanRad = torsoLeanDeg * PI / 180.0
        val torsoLen = 0.28f
        val shoulder = PosePoint(
            x = hip.x + (torsoLen * sin(leanRad)).toFloat(),
            y = hip.y - (torsoLen * cos(leanRad)).toFloat(),
            z = 0f,
            visibility = 1f
        )

        pts[BiomechanicsEngine.LEFT_ANKLE] = ankle
        pts[BiomechanicsEngine.RIGHT_ANKLE] = ankle.copy(x = if (valgus) 0.70f else 0.50f)
        pts[BiomechanicsEngine.LEFT_KNEE] = knee
        pts[BiomechanicsEngine.RIGHT_KNEE] = knee.copy(x = if (valgus) 0.54f else 0.50f)
        pts[BiomechanicsEngine.LEFT_HIP] = hip
        pts[BiomechanicsEngine.RIGHT_HIP] = hip
        pts[BiomechanicsEngine.LEFT_SHOULDER] = shoulder
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = shoulder
        return pts
    }

    private fun makePlankPose(hipY: Float): List<PosePoint> {
        val pts = blankPose()
        pts[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(0.20f, 0.50f, 0f, 1f)
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(0.20f, 0.50f, 0f, 1f)
        pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(0.50f, hipY, 0f, 1f)
        pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(0.50f, hipY, 0f, 1f)
        pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(0.80f, 0.50f, 0f, 1f)
        pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(0.80f, 0.50f, 0f, 1f)
        return pts
    }

    private fun makeCurlPose(elbowAngleDeg: Float): List<PosePoint> {
        val pts = blankPose()
        val shoulder = PosePoint(0.50f, 0.30f, 0f, 1f)
        val elbow = PosePoint(0.50f, 0.55f, 0f, 1f)
        val hip = PosePoint(0.50f, 0.65f, 0f, 1f)
        val rad = elbowAngleDeg * PI / 180.0
        val forearmLen = 0.22f
        // Vector elbow -> shoulder is (0, -0.25). Place wrist at angle elbowAngleDeg from upper arm.
        val wrist = PosePoint(
            x = elbow.x + (forearmLen * sin(rad)).toFloat(),
            y = elbow.y - (forearmLen * cos(rad)).toFloat(),
            z = 0f,
            visibility = 1f
        )
        pts[BiomechanicsEngine.LEFT_SHOULDER] = shoulder
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = shoulder
        pts[BiomechanicsEngine.LEFT_ELBOW] = elbow
        pts[BiomechanicsEngine.RIGHT_ELBOW] = elbow
        pts[BiomechanicsEngine.LEFT_WRIST] = wrist
        pts[BiomechanicsEngine.RIGHT_WRIST] = wrist
        pts[BiomechanicsEngine.LEFT_HIP] = hip
        pts[BiomechanicsEngine.RIGHT_HIP] = hip
        return pts
    }

    @Test
    fun angleDegrees_computesExactRightAndStraightAngles() {
        val right = BiomechanicsEngine.angleDegrees(
            PosePoint(0f, 1f),
            PosePoint(0f, 0f),
            PosePoint(1f, 0f)
        )
        assertEquals(90f, right, 0.5f)

        val straight = BiomechanicsEngine.angleDegrees(
            PosePoint(0f, 0f),
            PosePoint(0.5f, 0f),
            PosePoint(1f, 0f)
        )
        assertEquals(180f, straight, 0.5f)
    }

    @Test
    fun squat_countsThreeCleanRepetitionsWithHighFormScore() {
        val engine = BiomechanicsEngine(initialExercise = ExerciseType.SQUAT, emaAlpha = 1.0f)
        var ts = 1000L
        val cycleAngles = listOf(170f, 140f, 110f, 86f, 115f, 145f, 170f)

        repeat(3) {
            for (angle in cycleAngles) {
                ts += 100L
                engine.processFrame(makeSquatPose(kneeAngleDeg = angle, torsoLeanDeg = 16f), ts)
            }
        }

        val last = engine.processFrame(makeSquatPose(170f, 16f), ts + 100L)
        assertEquals(3, last.repCount)
        assertTrue("Expected high avg form score, got ${last.avgFormScore}", last.avgFormScore >= 90)
        assertTrue("Expected deep ROM %, got ${last.avgRomPct}", last.avgRomPct >= 85)
    }

    @Test
    fun squat_detectsExcessiveTorsoLeanAndKneeValgusFaults() {
        val engine = BiomechanicsEngine(initialExercise = ExerciseType.SQUAT, emaAlpha = 1.0f)

        val leanFrame = engine.processFrame(
            makeSquatPose(kneeAngleDeg = 92f, torsoLeanDeg = 55f, valgus = false),
            1000L
        )
        assertTrue(leanFrame.formScore <= 80)
        assertTrue(leanFrame.feedbackFr.contains("buste", ignoreCase = true))

        val valgusFrame = engine.processFrame(
            makeSquatPose(kneeAngleDeg = 92f, torsoLeanDeg = 18f, valgus = true),
            1100L
        )
        assertEquals(FormQuality.FAULT, valgusFrame.formQuality)
        assertTrue(valgusFrame.feedbackFr.contains("valgus", ignoreCase = true))
    }

    @Test
    fun plank_accumulatesTimerOnlyWhenBodyAlignedAndPausesOnSag() {
        val engine = BiomechanicsEngine(initialExercise = ExerciseType.PLANK, emaAlpha = 1.0f)

        // Seed initial timestamp + 5 frames of 200ms aligned plank (1.0 second)
        var ts = 1000L
        engine.processFrame(makePlankPose(hipY = 0.50f), ts)
        repeat(5) {
            ts += 200L
            val f = engine.processFrame(makePlankPose(hipY = 0.50f), ts)
            assertEquals(MovementPhase.HOLD, f.phase)
        }
        val afterHold = engine.processFrame(makePlankPose(hipY = 0.50f), ts)
        assertEquals(1.0f, afterHold.isometricHoldSec, 0.05f)

        // Now sag hips downward (hipY = 0.65 -> alignment < 160°): timer must pause!
        ts += 200L
        val sagFrame = engine.processFrame(makePlankPose(hipY = 0.65f), ts)
        assertEquals(MovementPhase.PAUSE, sagFrame.phase)
        assertEquals(1.0f, sagFrame.isometricHoldSec, 0.05f)
        assertTrue(sagFrame.feedbackFr.contains("hanches", ignoreCase = true))
    }

    @Test
    fun bicepCurl_countsRepsAndResetsCleanlyOnExerciseSwitch() {
        val engine = BiomechanicsEngine(initialExercise = ExerciseType.BICEP_CURL, emaAlpha = 1.0f)
        var ts = 1000L
        val angles = listOf(160f, 120f, 75f, 48f, 95f, 155f)
        repeat(2) {
            for (deg in angles) {
                ts += 100L
                engine.processFrame(makeCurlPose(deg), ts)
            }
        }
        val frame = engine.processFrame(makeCurlPose(160f), ts + 100L)
        assertEquals(2, frame.repCount)

        engine.setExercise(ExerciseType.DEADLIFT)
        assertEquals(ExerciseType.DEADLIFT, engine.currentExercise)
        assertEquals(0, engine.emptyFrame().repCount)
    }
}
