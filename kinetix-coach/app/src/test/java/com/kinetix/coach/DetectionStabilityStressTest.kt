package com.kinetix.coach

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import com.kinetix.coach.vision.AutoExerciseDetector
import com.kinetix.coach.vision.BiomechanicsEngine
import com.kinetix.coach.vision.CameraViewpoint
import com.kinetix.coach.vision.CameraViewpointTracker
import com.kinetix.coach.vision.PosePoint
import com.kinetix.coach.vision.PoseSanityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class DetectionStabilityStressTest {

    private fun blank3D(): MutableList<PosePoint> = MutableList(33) { PosePoint(0f, 0f, 0f, 1f) }

    private fun rotateAndProject(
        world: List<PosePoint>,
        yawDeg: Float = 45f,
        screenShiftX: Float = 0f,
        cameraDistMeters: Float = 2.6f
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
            val depth = (cameraDistMeters + wp.z).coerceAtLeast(0.6f)
            PosePoint(
                x = 0.50f + screenShiftX + wp.x * 1.05f / depth,
                y = 0.52f + wp.y * 1.05f / depth,
                z = wp.z,
                visibility = wp.visibility
            )
        }
        return screen to rotW
    }

    private fun buildStanding3D(
        kneeDeg: Float = 172f,
        torsoLeanDeg: Float = 5f,
        elbowFlexDeg: Float = 165f,
        shoulderAbductDeg: Float = 12f
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
        val lKnee = PosePoint(+hipW, -thigh * hipDirY, -thigh * hipDirZ, 1f)
        val rKnee = PosePoint(-hipW, -thigh * hipDirY, -thigh * hipDirZ, 1f)
        val lAnkle = PosePoint(+hipW, lKnee.y + shin * shinY, lKnee.z + shin * shinZ, 1f)
        val rAnkle = PosePoint(-hipW, rKnee.y + shin * shinY, rKnee.z + shin * shinZ, 1f)

        val leanRad = torsoLeanDeg * PI / 180.0
        val shY = -(torso * cos(leanRad)).toFloat()
        val shZ = -(torso * sin(leanRad)).toFloat()
        val lSh = PosePoint(+shW, shY, shZ, 1f)
        val rSh = PosePoint(-shW, shY, shZ, 1f)

        val abdRad = shoulderAbductDeg * PI / 180.0
        val uaDx = (0.28f * sin(abdRad)).toFloat()
        val uaDy = (0.28f * cos(abdRad)).toFloat()
        val lEl = PosePoint(+shW + uaDx, shY + uaDy, shZ, 1f)
        val rEl = PosePoint(-shW - uaDx, shY + uaDy, shZ, 1f)

        val eRad = elbowFlexDeg * PI / 180.0
        val upX = -sin(abdRad).toFloat()
        val upY = -cos(abdRad).toFloat()
        val fX = upX * cos(eRad).toFloat()
        val fY = upY * cos(eRad).toFloat()
        val fZ = -sin(eRad).toFloat()
        val lWr = PosePoint(lEl.x + 0.26f * fX, lEl.y + 0.26f * fY, lEl.z + 0.26f * fZ, 1f)
        val rWr = PosePoint(rEl.x - 0.26f * fX, rEl.y + 0.26f * fY, rEl.z + 0.26f * fZ, 1f)

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

    private fun buildPronePushup3D(elbowFlexDeg: Float = 165f): List<PosePoint> {
        val pts = blank3D()
        val shW = 0.19f
        val hipW = 0.13f
        val depth = ((168f - elbowFlexDeg) / 85f).coerceIn(0f, 1f)
        val floorY = 0.42f
        val shY = -0.02f + depth * 0.24f
        val shZ = -0.52f
        val anY = floorY - 0.04f
        val anZ = +0.78f
        val hipY = shY + 0.42f * (anY - shY)
        val hipZ = shZ + 0.42f * (anZ - shZ)

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

    private fun lerpPose(a: List<PosePoint>, b: List<PosePoint>, t: Float): List<PosePoint> {
        val f = t.coerceIn(0f, 1f)
        return a.indices.map { i ->
            PosePoint(
                x = a[i].x + (b[i].x - a[i].x) * f,
                y = a[i].y + (b[i].y - a[i].y) * f,
                z = a[i].z + (b[i].z - a[i].z) * f,
                visibility = a[i].visibility + (b[i].visibility - a[i].visibility) * f
            )
        }
    }

    private fun buildGluteBridge3D(bridgeUpFrac: Float): List<PosePoint> {
        val pts = blank3D()
        val f = bridgeUpFrac.coerceIn(0f, 1f)
        val floorY = 0.26f
        val shZ = -0.50f
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
        pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+0.13f, hipY, 0f, 1f)
        pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-0.13f, hipY, 0f, 1f)
        pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+0.14f, kneeY, kneeZ, 1f)
        pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-0.14f, kneeY, kneeZ, 1f)
        pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+0.14f, ankleY, ankleZ, 1f)
        pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-0.14f, ankleY, ankleZ, 1f)
        return pts
    }

    private fun buildLegRaise3D(legLiftDeg: Float): List<PosePoint> {
        val pts = blank3D()
        val floorY = 0.22f
        val rad = legLiftDeg * PI / 180.0
        val kneeZ = (0.42f * cos(rad)).toFloat()
        val kneeY = floorY - (0.42f * sin(rad)).toFloat()
        val anZ = (0.82f * cos(rad)).toFloat()
        val anY = floorY - (0.82f * sin(rad)).toFloat()

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

    // -------------------------------------------------------------------------
    // 1. Standing -> Getting down to floor for Push-ups (1-way transition)
    //    NEVER falsely triggers BURPEE and NEVER counts fake SQUAT or PUSHUP!
    // -------------------------------------------------------------------------
    @Test
    fun scenario1_oneWayGettingDownToFloor_neverTriggersBurpeeOrPhantomReps() {
        val detector = AutoExerciseDetector()
        val squatEngine = BiomechanicsEngine(ExerciseType.SQUAT)
        val pushupEngine = BiomechanicsEngine(ExerciseType.PUSHUP)

        val stand = buildStanding3D(kneeDeg = 172f, torsoLeanDeg = 5f)
        val proneTop = buildPronePushup3D(elbowFlexDeg = 165f)

        var ts = 1000L
        var maxBurpeeScore = 0f
        var everDetectedBurpee = false

        // 20 frames standing -> 20 frames getting down -> 25 frames resting in top push-up plank
        for (i in 0 until 65) {
            ts += 33L
            val world = when {
                i < 20 -> stand
                i < 40 -> lerpPose(stand, proneTop, (i - 20) / 20f)
                else -> proneTop
            }
            val (scr, rotW) = rotateAndProject(world, yawDeg = 45f)
            val det = detector.processFrame(scr, rotW, ts)
            val burpeeScore = det.scores[ExerciseType.BURPEE] ?: 0f
            if (burpeeScore > maxBurpeeScore) maxBurpeeScore = burpeeScore
            if (det.detectedExercise == ExerciseType.BURPEE) everDetectedBurpee = true

            squatEngine.processFrame(scr, rotW, ts)
            pushupEngine.processFrame(scr, rotW, ts)
        }

        assertEquals("1-way Standing -> Prone transition MUST score 0.0f for BURPEE", 0f, maxBurpeeScore, 0.001f)
        assertFalse("1-way getting down must NEVER detect BURPEE", everDetectedBurpee)
        assertEquals("Getting down onto floor must count 0 fake SQUAT reps", 0, squatEngine.emptyFrame().repCount)
        assertEquals("Getting down into top plank must count 0 fake PUSHUP reps", 0, pushupEngine.emptyFrame().repCount)

        // Reverse 1-way transition (getting UP after push-ups) must ALSO score 0.0f for BURPEE
        val upDetector = AutoExerciseDetector()
        var tsUp = 5000L
        var maxUpBurpeeScore = 0f
        for (i in 0 until 60) {
            tsUp += 33L
            val world = when {
                i < 20 -> proneTop
                i < 40 -> lerpPose(proneTop, stand, (i - 20) / 20f)
                else -> stand
            }
            val (scr, rotW) = rotateAndProject(world, yawDeg = 45f)
            val det = upDetector.processFrame(scr, rotW, tsUp)
            val score = det.scores[ExerciseType.BURPEE] ?: 0f
            if (score > maxUpBurpeeScore) maxUpBurpeeScore = score
        }
        assertEquals("1-way Prone -> Standing must score 0.0f for BURPEE", 0f, maxUpBurpeeScore, 0.001f)

        // Contrast: genuine round-trip STANDING -> PRONE -> STANDING DOES score strongly for BURPEE
        val burpeeDetector = AutoExerciseDetector()
        var tsB = 9000L
        var peakRoundTripBurpeeScore = 0f
        for (i in 0 until 66) {
            tsB += 33L
            val world = when {
                i < 16 -> stand
                i < 24 -> lerpPose(stand, proneTop, (i - 16) / 8f)
                i < 40 -> proneTop
                i < 48 -> lerpPose(proneTop, stand, (i - 40) / 8f)
                else -> stand
            }
            val (scr, rotW) = rotateAndProject(world, yawDeg = 45f)
            val det = burpeeDetector.processFrame(scr, rotW, tsB)
            val score = det.scores[ExerciseType.BURPEE] ?: 0f
            if (score > peakRoundTripBurpeeScore) peakRoundTripBurpeeScore = score
        }
        assertTrue(
            "Genuine round-trip Burpee should score strongly (> 0.65), got $peakRoundTripBurpeeScore",
            peakRoundTripBurpeeScore > 0.65f
        )
    }

    // -------------------------------------------------------------------------
    // 2. Pausing 2.2 seconds at top of Push-ups mid-set (hasActiveSetReps = true)
    //    STAYS locked on PUSHUP (NEVER flickers to PLANK!)
    // -------------------------------------------------------------------------
    @Test
    fun scenario2_pausing2_2SecondsAtTopOfPushupsMidSet_staysLockedOnPushup() {
        val detector = AutoExerciseDetector()
        var ts = 1000L
        var detected: ExerciseType? = null

        // Phase 1: Do 2 real push-ups (~2.2s) to lock onto PUSHUP naturally
        for (i in 0 until 66) {
            ts += 33L
            val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 28.0))).toFloat()
            val (scr, rotW) = rotateAndProject(buildPronePushup3D(166f - p * 82f), yawDeg = 45f)
            detected = detector.processFrame(scr, rotW, ts, hasActiveSetReps = false).detectedExercise
        }
        assertEquals("Should lock onto PUSHUP first", ExerciseType.PUSHUP, detected)

        // Phase 2: Pause completely motionless at top of push-ups for 2.2 seconds (67 frames @ 33ms = 2211ms)
        val topPlank = buildPronePushup3D(165f)
        val (scrTop, rotWTop) = rotateAndProject(topPlank, yawDeg = 45f)
        for (i in 0 until 67) {
            ts += 33L
            val res = detector.processFrame(scrTop, rotWTop, ts, isMidRep = false, hasActiveSetReps = true)
            assertEquals(
                "Must stay locked on PUSHUP during 2.2s mid-set pause (frame $i)",
                ExerciseType.PUSHUP,
                res.detectedExercise
            )
        }
    }

    // -------------------------------------------------------------------------
    // 3. Holding motionless Plank for 4.0 seconds after Push-ups cleanly switches to PLANK
    // -------------------------------------------------------------------------
    @Test
    fun scenario3_holdingMotionlessPlankFor4SecondsAfterPushups_cleanlySwitchesToPlank() {
        val detector = AutoExerciseDetector()
        var ts = 1000L
        var detected: ExerciseType? = null

        // Phase 1: Lock onto PUSHUP
        for (i in 0 until 66) {
            ts += 33L
            val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 28.0))).toFloat()
            val (scr, rotW) = rotateAndProject(buildPronePushup3D(166f - p * 82f), yawDeg = 45f)
            detected = detector.processFrame(scr, rotW, ts, hasActiveSetReps = false).detectedExercise
        }
        assertEquals(ExerciseType.PUSHUP, detected)

        // Phase 2: Hold motionless Plank for 4.0 seconds (122 frames @ 33ms = 4026ms) with hasActiveSetReps = true
        val (scrPlank, rotWPlank) = rotateAndProject(buildPronePushup3D(165f), yawDeg = 45f)
        for (i in 0 until 122) {
            ts += 33L
            detected = detector.processFrame(scrPlank, rotWPlank, ts, isMidRep = false, hasActiveSetReps = true).detectedExercise
        }
        assertEquals("After 4.0s motionless plank, must cleanly switch from PUSHUP to PLANK", ExerciseType.PLANK, detected)
    }

    // -------------------------------------------------------------------------
    // 4. Standing upright waving arms while in PUSHUP mode counts 0 phantom push-ups
    //    (+ standing in supine mode / lying flat in standing mode)
    // -------------------------------------------------------------------------
    @Test
    fun scenario4_postureGatesBlockOutOfPositionPhantomReps() {
        // 4A. Standing upright waving/bending arms in PUSHUP mode -> 0 reps!
        val pushupEngine = BiomechanicsEngine(ExerciseType.PUSHUP)
        var ts = 1000L
        repeat(3) {
            for (i in 0 until 30) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 28.0))).toFloat()
                // Upright standing person flexing elbows 165° -> 70° -> 165°
                val world = buildStanding3D(kneeDeg = 172f, torsoLeanDeg = 4f, elbowFlexDeg = 165f - p * 95f)
                val (scr, rotW) = rotateAndProject(world, yawDeg = 35f)
                pushupEngine.processFrame(scr, rotW, ts)
            }
        }
        assertEquals("Standing upright waving arms in PUSHUP mode must count 0 reps", 0, pushupEngine.emptyFrame().repCount)

        // 4B. Lying flat on floor bending knees/hips in SQUAT mode -> 0 reps!
        val squatEngine = BiomechanicsEngine(ExerciseType.SQUAT)
        var tsSq = 1000L
        repeat(3) {
            for (i in 0 until 30) {
                tsSq += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 28.0))).toFloat()
                val (scr, rotW) = rotateAndProject(buildGluteBridge3D(p), yawDeg = 90f)
                squatEngine.processFrame(scr, rotW, tsSq)
            }
        }
        assertEquals("Lying flat on floor in SQUAT mode must count 0 reps", 0, squatEngine.emptyFrame().repCount)

        // 4C. Standing upright bending hips in GLUTE_BRIDGE / LEG_RAISE mode -> 0 reps!
        val bridgeEngine = BiomechanicsEngine(ExerciseType.GLUTE_BRIDGE)
        val legRaiseEngine = BiomechanicsEngine(ExerciseType.LEG_RAISE)
        var tsSup = 1000L
        repeat(3) {
            for (i in 0 until 30) {
                tsSup += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 28.0))).toFloat()
                val world = buildStanding3D(kneeDeg = 168f - p * 70f, torsoLeanDeg = 10f)
                val (scr, rotW) = rotateAndProject(world, yawDeg = 90f)
                bridgeEngine.processFrame(scr, rotW, tsSup)
                legRaiseEngine.processFrame(scr, rotW, tsSup)
            }
        }
        assertEquals("Standing upright in GLUTE_BRIDGE mode must count 0 reps", 0, bridgeEngine.emptyFrame().repCount)
        assertEquals("Standing upright in LEG_RAISE mode must count 0 reps", 0, legRaiseEngine.emptyFrame().repCount)
    }

    // -------------------------------------------------------------------------
    // 5. Walking horizontally across frame triggers PoseSanityFilter.isUnstableTransition
    //    and blocks phantom reps & false exercise switches (while vertical squats stay unblocked!)
    // -------------------------------------------------------------------------
    @Test
    fun scenario5_horizontalWalkingTriggersMotionGuardAndBlocksRepsAndSwitches() {
        val sanityFilter = PoseSanityFilter()
        val squatEngine = BiomechanicsEngine(ExerciseType.SQUAT)
        val autoDetector = AutoExerciseDetector()
        autoDetector.reset(initialLocked = ExerciseType.SQUAT)

        var ts = 1000L
        var sawUnstable = false
        var sawStabilizingStatus = false

        // Walk horizontally across screen at ~0.75 screen-widths/sec while dipping knees
        for (i in 0 until 24) {
            ts += 33L
            val shiftX = -0.30f + i * 0.025f // 0.025 / 0.033 = 0.76 screen-widths/sec (> 0.42f)
            val knee = 170f - (if (i in 6..14) 82f else 0f)
            val (scr, rotW) = rotateAndProject(buildStanding3D(kneeDeg = knee, torsoLeanDeg = 12f), yawDeg = 30f, screenShiftX = shiftX)

            val sanity = sanityFilter.filter(scr, rotW, ts)
            if (sanity.motionGuard.isUnstableTransition) sawUnstable = true

            val frame = squatEngine.processFrame(scr, rotW, ts)
            val det = autoDetector.processFrame(
                rawScreenPoints = scr,
                rawWorldPoints = rotW,
                timestampMs = ts,
                isTransitioning = frame.isStabilizing
            )
            if (det.statusFr == "Stabilisation…") sawStabilizingStatus = true
            assertEquals("AutoDetector must never switch while stabilizing", ExerciseType.SQUAT, det.detectedExercise)
        }

        assertTrue("Walking horizontally must trigger PoseSanityFilter.isUnstableTransition", sawUnstable)
        assertTrue("AutoDetector must emit 'Stabilisation…' while transitioning", sawStabilizingStatus)
        assertEquals("Walking across frame must count 0 phantom reps", 0, squatEngine.emptyFrame().repCount)

        // Verify 650ms stabilization cooldown clears cleanly after stopping
        val (scrStill, rotWStill) = rotateAndProject(buildStanding3D(172f, 5f), yawDeg = 30f, screenShiftX = 0.25f)
        ts += 200L
        assertTrue("Cooldown must still be active at +200ms", sanityFilter.filter(scrStill, rotWStill, ts).motionGuard.isUnstableTransition)
        for (step in 1..25) {
            ts += 33L
            sanityFilter.filter(scrStill, rotWStill, ts)
        }
        assertFalse("Cooldown must clear after >650ms stationary", sanityFilter.filter(scrStill, rotWStill, ts + 33L).motionGuard.isUnstableTransition)

        // Contrast: Vertical Squat motion MUST NOT trigger isUnstableTransition!
        val verticalFilter = PoseSanityFilter()
        var tsV = 1000L
        var anyVerticalTrigger = false
        for (i in 0 until 45) {
            tsV += 33L
            val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 28.0))).toFloat()
            val (scr, rotW) = rotateAndProject(buildStanding3D(kneeDeg = 172f - p * 85f, torsoLeanDeg = 8f + p * 20f), yawDeg = 45f)
            if (verticalFilter.filter(scr, rotW, tsV).motionGuard.isUnstableTransition) {
                anyVerticalTrigger = true
            }
        }
        assertFalse("Vertical squat motion must NEVER trigger isUnstableTransition", anyVerticalTrigger)

        // Brief occlusion hold (< 180 ms) holds last valid coordinates with decaying visibility
        val occFilter = PoseSanityFilter()
        val (validScr, validWld) = rotateAndProject(buildStanding3D(165f, 5f), yawDeg = 0f)
        val validKneeY = validScr[BiomechanicsEngine.LEFT_KNEE].y
        occFilter.filter(validScr, validWld, 2000L)
        val occludedScr = validScr.toMutableList()
        occludedScr[BiomechanicsEngine.LEFT_KNEE] = PosePoint(0.95f, 0.10f, 0f, visibility = 0.12f)
        val held = occFilter.filter(occludedScr, validWld, 2066L)
        assertEquals(
            "Brief 66ms occlusion must hold previous valid knee Y instead of snapping to garbage",
            validKneeY,
            held.screenPoints[BiomechanicsEngine.LEFT_KNEE].y,
            0.001f
        )
        assertTrue(
            "Held landmark visibility should decay gently below 1.0",
            held.screenPoints[BiomechanicsEngine.LEFT_KNEE].visibility < 1.0f
        )
    }

    // -------------------------------------------------------------------------
    // 6. Single-frame 33ms MediaPipe teleportation spike (165° -> 70° -> 165°)
    //    is rejected completely -> 0 false reps!
    // -------------------------------------------------------------------------
    @Test
    fun scenario6_singleFrame33msTeleportationSpike_rejectedWithZeroFalseReps() {
        // 6A. Low-confidence teleportation spike rejected at PoseSanityFilter + OneEuro + Dwell layers
        val liveEngine = BiomechanicsEngine(ExerciseType.SQUAT)
        val (scrTall, wldTall) = rotateAndProject(buildStanding3D(kneeDeg = 165f, torsoLeanDeg = 8f), yawDeg = 45f)
        val (scrSpikeRaw, wldSpikeRaw) = rotateAndProject(buildStanding3D(kneeDeg = 70f, torsoLeanDeg = 12f), yawDeg = 45f)
        val scrSpikeLowVis = scrSpikeRaw.mapIndexed { idx, p ->
            if (idx == BiomechanicsEngine.LEFT_KNEE || idx == BiomechanicsEngine.RIGHT_KNEE) {
                p.copy(y = p.y - 0.30f, visibility = 0.55f)
            } else p
        }
        val wldSpikeLowVis = wldSpikeRaw.mapIndexed { idx, p ->
            if (idx == BiomechanicsEngine.LEFT_KNEE || idx == BiomechanicsEngine.RIGHT_KNEE) {
                p.copy(z = p.z - 0.45f, visibility = 0.55f)
            } else p
        }

        var ts = 1000L
        repeat(10) {
            ts += 33L
            liveEngine.processFrame(scrTall, wldTall, ts)
        }
        // Inject single-frame 33ms teleportation spike
        ts += 33L
        liveEngine.processFrame(scrSpikeLowVis, wldSpikeLowVis, ts)
        // Return immediately to standing 165°
        repeat(10) {
            ts += 33L
            liveEngine.processFrame(scrTall, wldTall, ts)
        }
        assertEquals("Single-frame low-vis teleportation spike must produce 0 false reps", 0, liveEngine.emptyFrame().repCount)

        // 6B. Even a high-confidence 1-frame 33ms angle glitch (165° -> 70° -> 165°) is rejected by 55ms dwell + 360ms duration!
        val deterministicEngine = BiomechanicsEngine(ExerciseType.SQUAT, emaAlpha = 1.0f)
        var tsDet = 2000L
        deterministicEngine.processFrame(scrTall, wldTall, tsDet)
        tsDet += 33L
        deterministicEngine.processFrame(scrSpikeRaw, wldSpikeRaw, tsDet)
        tsDet += 33L
        deterministicEngine.processFrame(scrTall, wldTall, tsDet)
        assertEquals("Single-frame 33ms angle spike (165°->70°->165°) must produce 0 false reps", 0, deterministicEngine.emptyFrame().repCount)
    }

    // -------------------------------------------------------------------------
    // 7. CameraViewpointTracker oscillating ±3° around 25° and 68° boundaries
    //    has 0 viewpoint flickers!
    // -------------------------------------------------------------------------
    @Test
    fun scenario7_cameraViewpointTracker_oscillatingAround25And68HasZeroFlickers() {
        val basePose = buildStanding3D(kneeDeg = 168f, torsoLeanDeg = 6f)

        // 7A. Oscillate ±3° around 25° boundary (22° <-> 28°) starting from FRONT (22°)
        run {
            val tracker = CameraViewpointTracker()
            val (s0, w0) = rotateAndProject(basePose, yawDeg = 22f)
            val initialVp = tracker.update(s0, w0).viewpoint
            assertEquals(CameraViewpoint.FRONT, initialVp)

            var flickers = 0
            var prev = initialVp
            for (i in 0 until 60) {
                val yaw = 25f + if (i % 2 == 0) 3f else -3f
                val (s, w) = rotateAndProject(basePose, yawDeg = yaw)
                val vp = tracker.update(s, w).viewpoint
                if (vp != prev) flickers++
                prev = vp
            }
            assertEquals("Oscillating 25°±3° must produce 0 viewpoint flickers", 0, flickers)
        }

        // 7B. Oscillate ±3° around 68° boundary (65° <-> 71°) starting from OBLIQUE_LEFT (65°)
        run {
            val tracker = CameraViewpointTracker()
            val (s0, w0) = rotateAndProject(basePose, yawDeg = 65f)
            val initialVp = tracker.update(s0, w0).viewpoint
            assertEquals(CameraViewpoint.OBLIQUE_LEFT, initialVp)

            var flickers = 0
            var prev = initialVp
            for (i in 0 until 60) {
                val yaw = 68f + if (i % 2 == 0) 3f else -3f
                val (s, w) = rotateAndProject(basePose, yawDeg = yaw)
                val vp = tracker.update(s, w).viewpoint
                if (vp != prev) flickers++
                prev = vp
            }
            assertEquals("Oscillating 68°±3° must produce 0 viewpoint flickers", 0, flickers)
        }

        // 7C. Confirm genuine large turn (22° -> 85°) DOES transition cleanly to PROFILE_LEFT
        run {
            val tracker = CameraViewpointTracker()
            val (s0, w0) = rotateAndProject(basePose, yawDeg = 22f)
            assertEquals(CameraViewpoint.FRONT, tracker.update(s0, w0).viewpoint)
            var lastVp = CameraViewpoint.FRONT
            val (s90, w90) = rotateAndProject(basePose, yawDeg = 88f)
            repeat(25) {
                lastVp = tracker.update(s90, w90).viewpoint
            }
            assertEquals("Genuine turn to 88° must transition cleanly to PROFILE_LEFT", CameraViewpoint.PROFILE_LEFT, lastVp)
        }
    }

    // -------------------------------------------------------------------------
    // 8. AutoExerciseDetector fixed ankleAboveHipTl accurately distinguishes
    //    LEG_RAISE vs GLUTE_BRIDGE!
    // -------------------------------------------------------------------------
    @Test
    fun scenario8_fixedAnkleAboveHipTl_distinguishesLegRaiseVsGluteBridge() {
        // 8A. GLUTE_BRIDGE: knees bent, hips thrusting, ankles grounded below hip height
        run {
            val det = AutoExerciseDetector()
            var ts = 1000L
            var lastRes = det.processFrame(emptyList(), emptyList(), ts)
            for (i in 0 until 66) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 28.0))).toFloat()
                val (scr, rotW) = rotateAndProject(buildGluteBridge3D(p), yawDeg = 90f)
                lastRes = det.processFrame(scr, rotW, ts)
            }
            assertEquals("Must classify Glute Bridge accurately", ExerciseType.GLUTE_BRIDGE, lastRes.detectedExercise)
            assertTrue(
                "Glute Bridge score must decisively beat Leg Raise score",
                (lastRes.scores[ExerciseType.GLUTE_BRIDGE] ?: 0f) > (lastRes.scores[ExerciseType.LEG_RAISE] ?: 0f) + 0.40f
            )
        }

        // 8B. LEG_RAISE: straight legs lifting ankles high above hips
        run {
            val det = AutoExerciseDetector()
            var ts = 1000L
            var lastRes = det.processFrame(emptyList(), emptyList(), ts)
            for (i in 0 until 66) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 28.0))).toFloat()
                val (scr, rotW) = rotateAndProject(buildLegRaise3D(10f + p * 75f), yawDeg = 90f)
                lastRes = det.processFrame(scr, rotW, ts)
            }
            assertEquals("Must classify Leg Raise accurately", ExerciseType.LEG_RAISE, lastRes.detectedExercise)
            assertTrue(
                "Leg Raise score must decisively beat Glute Bridge score",
                (lastRes.scores[ExerciseType.LEG_RAISE] ?: 0f) > (lastRes.scores[ExerciseType.GLUTE_BRIDGE] ?: 0f) + 0.40f
            )
        }

        // Bonus: Verify switchExerciseSmoothly resets counters WITHOUT resetting One-Euro smoother continuity
        val engine = BiomechanicsEngine(ExerciseType.SQUAT)
        var ts = 1000L
        val (scr, rotW) = rotateAndProject(buildStanding3D(kneeDeg = 165f, torsoLeanDeg = 8f), yawDeg = 45f)
        repeat(10) {
            ts += 33L
            engine.processFrame(scr, rotW, ts)
        }
        val beforeSwitch = engine.processFrame(scr, rotW, ts + 33L)
        engine.switchExerciseSmoothly(ExerciseType.DEADLIFT)
        val afterSwitch = engine.processFrame(scr, rotW, ts + 66L)
        assertEquals(ExerciseType.DEADLIFT, afterSwitch.exercise)
        assertEquals(0, afterSwitch.repCount)
        assertEquals(MovementPhase.READY, afterSwitch.phase)
        assertNotEquals(ExerciseType.SQUAT, afterSwitch.exercise)
        val hipDelta = abs(afterSwitch.landmarks[BiomechanicsEngine.LEFT_HIP].x - beforeSwitch.landmarks[BiomechanicsEngine.LEFT_HIP].x)
        assertTrue("Smooth exercise switch must preserve landmark smoother continuity (delta=$hipDelta)", hipDelta < 0.005f)
    }
}
