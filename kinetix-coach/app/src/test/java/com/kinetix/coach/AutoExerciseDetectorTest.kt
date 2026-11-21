package com.kinetix.coach

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.vision.AutoExerciseDetector
import com.kinetix.coach.vision.BiomechanicsEngine
import com.kinetix.coach.vision.PosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class AutoExerciseDetectorTest {

    private fun rotateAndProject(
        world: List<PosePoint>,
        yawDeg: Float,
        worldOffsetY: Float = 0f
    ): Pair<List<PosePoint>, List<PosePoint>> {
        val rad = yawDeg * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val rotW = world.map { p ->
            PosePoint(
                x = p.x * c + p.z * s,
                y = p.y,
                z = -p.x * s + p.z * c,
                visibility = p.visibility
            )
        }
        val screen = rotW.map { wp ->
            val depth = (2.6f + wp.z).coerceAtLeast(0.6f)
            PosePoint(
                x = 0.50f + wp.x * 1.05f / depth,
                y = 0.52f + (wp.y + worldOffsetY) * 1.05f / depth,
                z = wp.z,
                visibility = wp.visibility
            )
        }
        return screen to rotW
    }

    private fun blank3D(): MutableList<PosePoint> = MutableList(33) { PosePoint(0f, 0f, 0f, 1f) }

    private fun standingBase(
        kneeDeg: Float = 172f,
        torsoLeanDeg: Float = 4f,
        footSplitZ: Float = 0f,
        rearAnkleRaiseY: Float = 0f,
        ankleSpreadX: Float = 0.14f,
        elbowFlexDeg: Float = 165f,
        shoulderAbductDeg: Float = 12f,
        overheadProgress: Float = -1f // >=0 overrides arms into OHP trajectory
    ): List<PosePoint> {
        val pts = blank3D()
        val hipW = 0.13f
        val shW = 0.19f
        val thigh = 0.42f
        val shin = 0.42f
        val torso = 0.50f

        val bendFrac = ((175f - kneeDeg) / 90f).coerceIn(0f, 1f)
        val shinTilt = (10f * bendFrac) * PI / 180.0
        val kRad = kneeDeg * PI / 180.0
        val shinY = cos(shinTilt).toFloat()
        val shinZ = sin(shinTilt).toFloat()
        val hipDirY = (shinY * cos(kRad) - shinZ * sin(kRad)).toFloat()
        val hipDirZ = (shinY * sin(kRad) + shinZ * cos(kRad)).toFloat()

        val lHip = PosePoint(+hipW, 0f, 0f, 1f)
        val rHip = PosePoint(-hipW, 0f, 0f, 1f)

        val lKnee = PosePoint(+ankleSpreadX, -thigh * hipDirY, -thigh * hipDirZ - footSplitZ * 0.45f, 1f)
        val lAnkle = PosePoint(+ankleSpreadX, lKnee.y + shin * shinY, lKnee.z + shin * shinZ - footSplitZ * 0.45f, 1f)

        val rKnee = PosePoint(-ankleSpreadX, -thigh * hipDirY, -thigh * hipDirZ + footSplitZ * 0.45f, 1f)
        val rAnkle = PosePoint(-ankleSpreadX, rKnee.y + shin * shinY - rearAnkleRaiseY, rKnee.z + shin * shinZ + footSplitZ * 0.55f, 1f)

        val leanRad = torsoLeanDeg * PI / 180.0
        val shY = -(torso * cos(leanRad)).toFloat()
        val shZ = -(torso * sin(leanRad)).toFloat()
        val lSh = PosePoint(+shW, shY, shZ, 1f)
        val rSh = PosePoint(-shW, shY, shZ, 1f)

        val lEl: PosePoint
        val rEl: PosePoint
        val lWr: PosePoint
        val rWr: PosePoint

        if (overheadProgress >= 0f) {
            // Overhead Press: elbows move from 78° (hands near shoulders) to 170° (hands high overhead)
            val p = overheadProgress.coerceIn(0f, 1f)
            val elY = shY + 0.06f * (1f - p) - 0.24f * p
            val elX = shW + 0.14f * (1f - p) + 0.03f * p
            val wrY = shY - 0.08f * (1f - p) - 0.52f * p
            val wrX = shW + 0.04f * (1f - p) + 0.02f * p
            lEl = PosePoint(+elX, elY, shZ - 0.04f, 1f)
            rEl = PosePoint(-elX, elY, shZ - 0.04f, 1f)
            lWr = PosePoint(+wrX, wrY, shZ - 0.04f, 1f)
            rWr = PosePoint(-wrX, wrY, shZ - 0.04f, 1f)
        } else {
            val abdRad = shoulderAbductDeg * PI / 180.0
            val upperArm = 0.28f
            val forearm = 0.26f
            // Upper arm abducted laterally in XY plane
            val uaDx = (upperArm * sin(abdRad)).toFloat()
            val uaDy = (upperArm * cos(abdRad)).toFloat()
            lEl = PosePoint(+shW + uaDx, shY + uaDy, shZ, 1f)
            rEl = PosePoint(-shW - uaDx, shY + uaDy, shZ, 1f)

            // Forearm bent forward (-Z) / up (-Y) by elbowFlexDeg relative to upper arm
            val eRad = elbowFlexDeg * PI / 180.0
            // Unit vector elbow->shoulder is (-sin(abd), -cos(abd), 0) for left arm
            val upX = -sin(abdRad).toFloat()
            val upY = -cos(abdRad).toFloat()
            // Rotate in sagittal-like plane so 180° = straight extension, 45° = full curl up toward shoulder
            val fX = upX * cos(eRad).toFloat()
            val fY = upY * cos(eRad).toFloat()
            val fZ = -sin(eRad).toFloat()
            lWr = PosePoint(lEl.x + forearm * fX, lEl.y + forearm * fY, lEl.z + forearm * fZ, 1f)
            rWr = PosePoint(rEl.x - forearm * fX, rEl.y + forearm * fY, rEl.z + forearm * fZ, 1f)
        }

        pts[BiomechanicsEngine.NOSE] = PosePoint(0f, shY - 0.16f, shZ - 0.14f, 1f)
        pts[BiomechanicsEngine.LEFT_SHOULDER] = lSh
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = rSh
        pts[BiomechanicsEngine.LEFT_ELBOW] = lEl
        pts[BiomechanicsEngine.RIGHT_ELBOW] = rEl
        pts[BiomechanicsEngine.LEFT_WRIST] = lWr
        pts[BiomechanicsEngine.RIGHT_WRIST] = rWr
        pts[BiomechanicsEngine.LEFT_HIP] = lHip
        pts[BiomechanicsEngine.RIGHT_HIP] = rHip
        pts[BiomechanicsEngine.LEFT_KNEE] = lKnee
        pts[BiomechanicsEngine.RIGHT_KNEE] = rKnee
        pts[BiomechanicsEngine.LEFT_ANKLE] = lAnkle
        pts[BiomechanicsEngine.RIGHT_ANKLE] = rAnkle
        return pts
    }

    private fun proneFloorPose(elbowFlexDeg: Float): List<PosePoint> {
        val pts = blank3D()
        val shW = 0.19f
        val hipW = 0.13f
        // Chest drops closer to floor (y=+0.45) as elbows flex from 168° down to 85°
        val depth = ((168f - elbowFlexDeg) / 85f).coerceIn(0f, 1f)
        val floorY = 0.42f
        val shY = -0.02f + depth * 0.24f
        val shZ = -0.52f
        val anY = floorY - 0.04f
        val anZ = +0.78f
        val hipY = shY + 0.42f * (anY - shY)
        val hipZ = shZ + 0.42f * (anZ - shZ)

        // Exact elbow angle controlled by placing elbow out/back between shoulder and floor wrist
        val wrY = floorY
        val wrZ = shZ - 0.02f
        val elOut = 0.14f + depth * 0.16f
        val elBack = 0.05f + depth * 0.18f
        val elY = (shY + wrY) * 0.5f - depth * 0.06f

        pts[BiomechanicsEngine.NOSE] = PosePoint(0f, shY - 0.05f, shZ - 0.18f, 1f)
        pts[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(+shW, shY, shZ, 1f)
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(-shW, shY, shZ, 1f)
        pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(+shW + elOut, elY, shZ + elBack, 1f)
        pts[BiomechanicsEngine.RIGHT_ELBOW] = PosePoint(-shW - elOut, elY, shZ + elBack, 1f)
        pts[BiomechanicsEngine.LEFT_WRIST] = PosePoint(+shW + 0.06f, wrY, wrZ, 1f)
        pts[BiomechanicsEngine.RIGHT_WRIST] = PosePoint(-shW - 0.06f, wrY, wrZ, 1f)
        pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+hipW, hipY, hipZ, 1f)
        pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-hipW, hipY, hipZ, 1f)
        pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+hipW, (hipY + anY) * 0.5f, (hipZ + anZ) * 0.5f, 1f)
        pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-hipW, (hipY + anY) * 0.5f, (hipZ + anZ) * 0.5f, 1f)
        pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+hipW, anY, anZ, 1f)
        pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-hipW, anY, anZ, 1f)
        return pts
    }

    private fun gluteBridgePose(bridgeUpFrac: Float): List<PosePoint> {
        val pts = blank3D()
        val f = bridgeUpFrac.coerceIn(0f, 1f)
        val floorY = 0.26f
        val shZ = -0.50f
        val hipZ = 0.0f
        val hipY = floorY - f * 0.25f
        val kneeZ = 0.36f
        val kneeY = floorY - 0.32f
        val ankleZ = 0.44f
        val ankleY = floorY

        pts[BiomechanicsEngine.NOSE] = PosePoint(0f, floorY - 0.12f, shZ - 0.16f, 1f)
        pts[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(+0.18f, floorY - 0.03f, shZ, 1f)
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(-0.18f, floorY - 0.03f, shZ, 1f)
        pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(+0.22f, floorY - 0.02f, -0.25f, 1f)
        pts[BiomechanicsEngine.RIGHT_ELBOW] = PosePoint(-0.22f, floorY - 0.02f, -0.25f, 1f)
        pts[BiomechanicsEngine.LEFT_WRIST] = PosePoint(+0.24f, floorY, 0.0f, 1f)
        pts[BiomechanicsEngine.RIGHT_WRIST] = PosePoint(-0.24f, floorY, 0.0f, 1f)
        pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+0.13f, hipY, hipZ, 1f)
        pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-0.13f, hipY, hipZ, 1f)
        pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+0.14f, kneeY, kneeZ, 1f)
        pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-0.14f, kneeY, kneeZ, 1f)
        pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+0.14f, ankleY, ankleZ, 1f)
        pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-0.14f, ankleY, ankleZ, 1f)
        return pts
    }

    private fun legRaisePose(legLiftDegFromFloor: Float): List<PosePoint> {
        val pts = blank3D()
        val floorY = 0.22f
        val rad = legLiftDegFromFloor * PI / 180.0
        val legLen = 0.82f
        val kneeZ = (0.42f * cos(rad)).toFloat()
        val kneeY = floorY - (0.42f * sin(rad)).toFloat()
        val anZ = (legLen * cos(rad)).toFloat()
        val anY = floorY - (legLen * sin(rad)).toFloat()

        pts[BiomechanicsEngine.NOSE] = PosePoint(0f, floorY - 0.12f, -0.66f, 1f)
        pts[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(+0.18f, floorY - 0.02f, -0.50f, 1f)
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(-0.18f, floorY - 0.02f, -0.50f, 1f)
        pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(+0.22f, floorY, -0.25f, 1f)
        pts[BiomechanicsEngine.RIGHT_ELBOW] = PosePoint(-0.22f, floorY, -0.25f, 1f)
        pts[BiomechanicsEngine.LEFT_WRIST] = PosePoint(+0.22f, floorY, 0.02f, 1f)
        pts[BiomechanicsEngine.RIGHT_WRIST] = PosePoint(-0.22f, floorY, 0.02f, 1f)
        pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+0.13f, floorY, 0f, 1f)
        pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-0.13f, floorY, 0f, 1f)
        pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+0.13f, kneeY, kneeZ, 1f)
        pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-0.13f, kneeY, kneeZ, 1f)
        pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+0.13f, anY, anZ, 1f)
        pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-0.13f, anY, anZ, 1f)
        return pts
    }

    private fun runSequence(
        detector: AutoExerciseDetector,
        frames: Int = 66,
        yawDeg: Float = 45f,
        frameBuilder: (phase01: Float) -> Pair<List<PosePoint>, Float> // world + optional screenY offset
    ): ExerciseType? {
        var ts = 1000L
        var lastDetected: ExerciseType? = null
        for (i in 0 until frames) {
            ts += 33L
            val phase = (0.5f - 0.5f * cos(2.0 * PI * (i / 28.0))).toFloat() // smooth 0..1..0 oscillation
            val (wld, offsetY) = frameBuilder(phase)
            val (scr, rotW) = rotateAndProject(wld, yawDeg = yawDeg, worldOffsetY = offsetY)
            val res = detector.processFrame(scr, rotW, ts)
            lastDetected = res.detectedExercise
        }
        return lastDetected
    }

    @Test
    fun detectsCoreTenExercisesAccuratelyUnderVaryingCameraAngles() {
        // 1. SQUAT under Front (0°), 3/4 (45°), and Profile (90°)
        for (yaw in listOf(0f, 45f, 90f)) {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = yaw) { p ->
                standingBase(kneeDeg = 170f - p * 85f, torsoLeanDeg = 6f + p * 22f) to 0f
            }
            assertEquals("Squat should be detected at yaw=$yaw°", ExerciseType.SQUAT, ex)
        }

        // 2. PUSHUP under Profile (90°) and 3/4 (45°)
        for (yaw in listOf(45f, 90f)) {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = yaw) { p ->
                proneFloorPose(elbowFlexDeg = 166f - p * 82f) to 0f
            }
            assertEquals("Pushup should be detected at yaw=$yaw°", ExerciseType.PUSHUP, ex)
        }

        // 3. PLANK isometric hold under 45° and 90°
        for (yaw in listOf(45f, 90f)) {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = yaw) { _ ->
                proneFloorPose(elbowFlexDeg = 165f) to 0f
            }
            assertEquals("Plank should be detected at yaw=$yaw°", ExerciseType.PLANK, ex)
        }

        // 4. OVERHEAD_PRESS at Front (0°)
        run {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = 0f) { p ->
                standingBase(kneeDeg = 174f, torsoLeanDeg = 4f, overheadProgress = p) to 0f
            }
            assertEquals("Overhead Press should be detected", ExerciseType.OVERHEAD_PRESS, ex)
        }

        // 5. BICEP_CURL at 3/4 (45°)
        run {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = 45f) { p ->
                standingBase(kneeDeg = 174f, torsoLeanDeg = 4f, elbowFlexDeg = 162f - p * 116f, shoulderAbductDeg = 8f) to 0f
            }
            assertEquals("Bicep Curl should be detected", ExerciseType.BICEP_CURL, ex)
        }

        // 6. LATERAL_RAISE at Front (0°)
        run {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = 0f) { p ->
                standingBase(kneeDeg = 174f, torsoLeanDeg = 4f, elbowFlexDeg = 165f, shoulderAbductDeg = 14f + p * 78f) to 0f
            }
            assertEquals("Lateral Raise should be detected", ExerciseType.LATERAL_RAISE, ex)
        }

        // 7. DEADLIFT at Profile (90°) and 3/4 (45°)
        for (yaw in listOf(45f, 90f)) {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = yaw) { p ->
                standingBase(kneeDeg = 170f - p * 28f, torsoLeanDeg = 6f + p * 64f, elbowFlexDeg = 170f) to 0f
            }
            assertEquals("Deadlift should be detected at yaw=$yaw°", ExerciseType.DEADLIFT, ex)
        }

        // 8. JUMPING_JACK at Front (0°)
        run {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = 0f) { p ->
                standingBase(
                    kneeDeg = 172f,
                    torsoLeanDeg = 3f,
                    ankleSpreadX = 0.12f + p * 0.24f,
                    elbowFlexDeg = 166f,
                    shoulderAbductDeg = 15f + p * 145f
                ) to 0f
            }
            assertEquals("Jumping Jack should be detected", ExerciseType.JUMPING_JACK, ex)
        }

        // 9. GLUTE_BRIDGE at Profile (90°)
        run {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = 90f) { p ->
                gluteBridgePose(bridgeUpFrac = p) to 0f
            }
            assertEquals("Glute Bridge should be detected", ExerciseType.GLUTE_BRIDGE, ex)
        }

        // 10. LEG_RAISE at Profile (90°)
        run {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = 90f) { p ->
                legRaisePose(legLiftDegFromFloor = 10f + p * 75f) to 0f
            }
            assertEquals("Leg Raise should be detected", ExerciseType.LEG_RAISE, ex)
        }
    }

    @Test
    fun detectsLungeBulgarianSquatBentRowAndResistsStandingNoise() {
        // LUNGE (split stance on floor)
        run {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = 45f) { p ->
                standingBase(kneeDeg = 168f - p * 78f, torsoLeanDeg = 8f, footSplitZ = 0.72f, rearAnkleRaiseY = 0f) to 0f
            }
            assertEquals(ExerciseType.LUNGE, ex)
        }

        // BULGARIAN_SQUAT (split stance + rear foot elevated 28cm)
        run {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = 45f) { p ->
                standingBase(kneeDeg = 168f - p * 78f, torsoLeanDeg = 14f, footSplitZ = 0.68f, rearAnkleRaiseY = 0.28f) to 0f
            }
            assertEquals(ExerciseType.BULGARIAN_SQUAT, ex)
        }

        // BENT_ROW (sustained 48° torso hinge + dynamic rowing elbows)
        run {
            val det = AutoExerciseDetector()
            val ex = runSequence(det, yawDeg = 90f) { p ->
                standingBase(kneeDeg = 152f, torsoLeanDeg = 48f, elbowFlexDeg = 162f - p * 86f, shoulderAbductDeg = 15f) to 0f
            }
            assertEquals(ExerciseType.BENT_ROW, ex)
        }

        // NOISE RESISTANCE: standing still with tiny jitter must NOT trigger a false exercise lock
        val idleDetector = AutoExerciseDetector()
        var ts = 1000L
        var result = idleDetector.processFrame(emptyList(), emptyList(), ts)
        for (i in 0 until 60) {
            ts += 33L
            val jitter = if (i % 2 == 0) 1.5f else -1.5f
            val (scr, wld) = rotateAndProject(
                standingBase(kneeDeg = 174f + jitter, torsoLeanDeg = 4f + jitter * 0.5f),
                yawDeg = 15f
            )
            result = idleDetector.processFrame(scr, wld, ts)
        }
        assertNull("Standing still with minor noise must NOT trigger false lock", result.detectedExercise)
        assertTrue("Confidence should remain low while idle, got ${result.confidence}", result.confidence < 0.55f)
    }

    @Test
    fun transitionsSmoothlyFromSquatToPushupMidWorkout() {
        val detector = AutoExerciseDetector()
        var ts = 1000L
        var detected: ExerciseType? = null

        // Phase 1: 55 frames of Squats (~1.8s)
        for (i in 0 until 55) {
            ts += 33L
            val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 26.0))).toFloat()
            val (scr, wld) = rotateAndProject(standingBase(kneeDeg = 170f - p * 85f, torsoLeanDeg = 8f + p * 20f), yawDeg = 45f)
            detected = detector.processFrame(scr, wld, ts).detectedExercise
        }
        assertEquals(ExerciseType.SQUAT, detected)

        // Phase 2: Transition to 75 frames of Push-ups (~2.5s)
        for (i in 0 until 75) {
            ts += 33L
            val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 26.0))).toFloat()
            val (scr, wld) = rotateAndProject(proneFloorPose(elbowFlexDeg = 166f - p * 82f), yawDeg = 45f)
            detected = detector.processFrame(scr, wld, ts).detectedExercise
        }
        assertEquals("Detector must transition smoothly from SQUAT to PUSHUP", ExerciseType.PUSHUP, detected)
    }
}
