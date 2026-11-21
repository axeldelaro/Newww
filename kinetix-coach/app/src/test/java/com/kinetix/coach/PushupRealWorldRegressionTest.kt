package com.kinetix.coach

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.vision.AutoExerciseDetector
import com.kinetix.coach.vision.BiomechanicsEngine
import com.kinetix.coach.vision.PosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Real-world regression suite reproducing every push-up & body-scan failure mode observed on
 * physical Android phones (Xiaomi 14 Ultra): standing-to-floor lockup, tilted front-floor phone,
 * foreshortened bodyScale freeze, occluded far side in profile, MediaPipe prone 3D elbow lag,
 * fast touch-and-go 33ms bottom dwell, and Plank -> Push-up auto-detect unlock.
 */
class PushupRealWorldRegressionTest {

    private fun blank3D(): MutableList<PosePoint> = MutableList(33) { PosePoint(0f, 0f, 0f, 1f) }

    /**
     * Rotates 3D pose by camera yaw AND camera pitch (simulating phone propped on floor tilted upward!),
     * then projects to normalized 2D screen coordinates.
     */
    private fun projectWithYawAndPitch(
        world: List<PosePoint>,
        yawDeg: Float = 0f,
        pitchUpDeg: Float = 0f,
        camDistMeters: Float = 1.9f,
        screenCenterY: Float = 0.58f
    ): Pair<List<PosePoint>, List<PosePoint>> {
        val yRad = yawDeg * PI / 180.0
        val cy = cos(yRad).toFloat()
        val sy = sin(yRad).toFloat()

        // Camera pitched UP by pitchUpDeg rotates world points by +pitchUpDeg around X axis in camera frame
        val pRad = pitchUpDeg * PI / 180.0
        val cp = cos(pRad).toFloat()
        val sp = sin(pRad).toFloat()

        val camWorld = world.map { p ->
            // 1. Yaw around Y
            val x1 = p.x * cy + p.z * sy
            val y1 = p.y
            val z1 = -p.x * sy + p.z * cy
            // 2. Camera tilt around X
            val y2 = y1 * cp - z1 * sp
            val z2 = y1 * sp + z1 * cp
            PosePoint(x = x1, y = y2, z = z2, visibility = p.visibility)
        }
        val screen = camWorld.map { wp ->
            val depth = (camDistMeters + wp.z).coerceAtLeast(0.55f)
            PosePoint(
                x = (0.50f + wp.x * 0.95f / depth).coerceIn(0.02f, 0.98f),
                y = (screenCenterY + wp.y * 0.95f / depth).coerceIn(0.02f, 0.98f),
                z = wp.z,
                visibility = wp.visibility
            )
        }
        return screen to camWorld
    }

    private fun buildStandingWorld(): List<PosePoint> {
        val pts = blank3D()
        pts[BiomechanicsEngine.NOSE] = PosePoint(0f, -0.66f, -0.10f, 1f)
        pts[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(+0.19f, -0.50f, 0f, 1f)
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(-0.19f, -0.50f, 0f, 1f)
        pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(+0.22f, -0.22f, 0f, 1f)
        pts[BiomechanicsEngine.RIGHT_ELBOW] = PosePoint(-0.22f, -0.22f, 0f, 1f)
        pts[BiomechanicsEngine.LEFT_WRIST] = PosePoint(+0.22f, 0.04f, -0.02f, 1f)
        pts[BiomechanicsEngine.RIGHT_WRIST] = PosePoint(-0.22f, 0.04f, -0.02f, 1f)
        pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+0.13f, 0f, 0f, 1f)
        pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-0.13f, 0f, 0f, 1f)
        pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+0.13f, 0.42f, 0f, 1f)
        pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-0.13f, 0.42f, 0f, 1f)
        pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+0.13f, 0.84f, 0f, 1f)
        pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-0.13f, 0.84f, 0f, 1f)
        return pts
    }

    private fun buildPushupWorld(
        elbowDeg: Float,
        depthFrac: Float = ((166f - elbowDeg) / 76f).coerceIn(0f, 1f),
        farSideVis: Float = 1f,
        hipAnkleVis: Float = 1f
    ): List<PosePoint> {
        val pts = blank3D()
        val shW = 0.19f
        val hipW = 0.13f
        val floorY = 0.44f
        val shZ = -0.52f
        val shY = -0.02f + depthFrac * 0.36f
        val anZ = 0.78f
        val anY = floorY - 0.03f
        val hipZ = shZ + 0.44f * (anZ - shZ)
        val hipY = shY + 0.44f * (anY - shY)
        val knZ = shZ + 0.72f * (anZ - shZ)
        val knY = shY + 0.72f * (anY - shY)

        val lSh = PosePoint(+shW, shY, shZ, 1f)
        val rSh = PosePoint(-shW, shY, shZ, farSideVis)
        val lWr = PosePoint(+shW * 1.15f, floorY, shZ - 0.02f, 1f)
        val rWr = PosePoint(-shW * 1.15f, floorY, shZ - 0.02f, farSideVis)

        fun solveElbow(sh: PosePoint, wr: PosePoint, deg: Float, signX: Float, vis: Float): PosePoint {
            val dx = wr.x - sh.x
            val dy = wr.y - sh.y
            val dz = wr.z - sh.z
            val chord = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat().coerceAtLeast(0.05f)
            val ux = dx / chord
            val uy = dy / chord
            val uz = dz / chord
            val halfRad = (deg.coerceIn(50f, 178f) * 0.5) * PI / 180.0
            val outDist = ((chord * 0.5f) / tan(halfRad).toFloat()).coerceIn(0.005f, 0.30f)
            var px = signX * 0.55f
            var py = 0f
            var pz = 0.84f
            val dot = px * ux + py * uy + pz * uz
            px -= dot * ux
            py -= dot * uy
            pz -= dot * uz
            val pLen = sqrt((px * px + py * py + pz * pz).toDouble()).toFloat().coerceAtLeast(1e-4f)
            return PosePoint(
                x = (sh.x + wr.x) * 0.5f + (px / pLen) * outDist,
                y = (sh.y + wr.y) * 0.5f + (py / pLen) * outDist,
                z = (sh.z + wr.z) * 0.5f + (pz / pLen) * outDist,
                visibility = vis
            )
        }

        pts[BiomechanicsEngine.NOSE] = PosePoint(0f, shY - 0.06f, shZ - 0.16f, 1f)
        pts[BiomechanicsEngine.LEFT_SHOULDER] = lSh
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = rSh
        pts[BiomechanicsEngine.LEFT_ELBOW] = solveElbow(lSh, lWr, elbowDeg, +1f, 1f)
        pts[BiomechanicsEngine.RIGHT_ELBOW] = solveElbow(rSh, rWr, elbowDeg, -1f, farSideVis)
        pts[BiomechanicsEngine.LEFT_WRIST] = lWr
        pts[BiomechanicsEngine.RIGHT_WRIST] = rWr
        pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+hipW, hipY, hipZ, hipAnkleVis)
        pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-hipW, hipY, hipZ, minOf(farSideVis, hipAnkleVis))
        pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+hipW, knY, knZ, hipAnkleVis)
        pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-hipW, knY, knZ, minOf(farSideVis, hipAnkleVis))
        pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+hipW, anY, anZ, hipAnkleVis)
        pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-hipW, anY, anZ, minOf(farSideVis, hipAnkleVis))
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

    // 1. Getting down from standing -> hands on floor (huge initial height) -> settling into plank -> 5 push-ups!
    @Test
    fun regression1_gettingDownFromStandingThenDoing5Pushups_neverLocksUp() {
        val engine = BiomechanicsEngine(ExerciseType.PUSHUP) // LIVE mode with PoseSanityFilter + OneEuro!
        val stand = buildStandingWorld()
        val bendHandsFloor = buildPushupWorld(elbowDeg = 170f, depthFrac = 0f).mapIndexed { i, p ->
            // Simulate crouched/bent-down transition where shoulders are way higher above wrists
            if (i == BiomechanicsEngine.LEFT_SHOULDER || i == BiomechanicsEngine.RIGHT_SHOULDER || i == BiomechanicsEngine.NOSE) {
                p.copy(y = p.y - 0.35f)
            } else p
        }
        val topPushup = buildPushupWorld(elbowDeg = 166f, depthFrac = 0f)

        var ts = 1000L
        // Step A: Standing -> bending down -> settling into top plank (30 frames = 1.0s)
        for (i in 0 until 35) {
            ts += 33L
            val w = when {
                i < 8 -> stand
                i < 16 -> lerpPose(stand, bendHandsFloor, (i - 8) / 8f)
                i < 24 -> lerpPose(bendHandsFloor, topPushup, (i - 16) / 8f)
                else -> topPushup
            }
            val (scr, camW) = projectWithYawAndPitch(w, yawDeg = 35f, pitchUpDeg = 15f)
            engine.processFrame(scr, camW, ts)
        }
        assertEquals("Getting down must count 0 phantom reps", 0, engine.emptyFrame().repCount)

        // Step B: Perform 5 clean push-ups
        repeat(5) {
            for (f in 0 until 36) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (f / 35.0))).toFloat()
                val w = buildPushupWorld(elbowDeg = 166f - p * 78f, depthFrac = p)
                val (scr, camW) = projectWithYawAndPitch(w, yawDeg = 35f, pitchUpDeg = 15f)
                engine.processFrame(scr, camW, ts)
            }
        }
        assertEquals("Must count all 5 push-ups with ZERO lockup after getting down from standing", 5, engine.emptyFrame().repCount)
    }

    // 2. Front-facing floor push-ups with phone propped on floor tilted 32° upward + hips partially occluded behind chest!
    @Test
    fun regression2_frontFloorTiltedPhoneWithOccludedHips_zeroFreezesZeroFalseFaultsCounts5Reps() {
        val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
        var ts = 1000L
        var stabilizingFrames = 0
        var falseHipFaults = 0

        // Settle 10 frames at top
        repeat(10) {
            ts += 33L
            val w = buildPushupWorld(elbowDeg = 165f, depthFrac = 0f, hipAnkleVis = 0.25f)
            val (scr, camW) = projectWithYawAndPitch(w, yawDeg = 4f, pitchUpDeg = 32f, camDistMeters = 1.6f)
            val frame = engine.processFrame(scr, camW, ts)
            if (frame.isStabilizing) stabilizingFrames++
        }

        // Perform 5 real front-floor push-ups
        repeat(5) {
            for (f in 0 until 36) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (f / 35.0))).toFloat()
                val w = buildPushupWorld(elbowDeg = 165f - p * 74f, depthFrac = p, hipAnkleVis = 0.25f)
                val (scr, camW) = projectWithYawAndPitch(w, yawDeg = 4f, pitchUpDeg = 32f, camDistMeters = 1.6f)
                val frame = engine.processFrame(scr, camW, ts)
                if (frame.isStabilizing) stabilizingFrames++
                if (frame.corrections.any { it.jointLabelFr.equals("Hanches", ignoreCase = true) }) {
                    falseHipFaults++
                }
            }
        }

        assertEquals("Front-floor tilted phone push-ups must NEVER falsely freeze in PoseSanityFilter", 0, stabilizingFrames)
        assertEquals("Occluded hips in front-floor view must NEVER spam false hip faults", 0, falseHipFaults)
        assertEquals("Front-floor tilted phone must count all 5 push-ups", 5, engine.emptyFrame().repCount)
    }

    // 3. Side-profile push-ups with occluded far shoulder & hip (vis = 0.15) + MediaPipe 3D elbow underestimation!
    @Test
    fun regression3_sideProfileOccludedFarSideAndNoisy3DElbow_zeroFreezesCounts4Reps() {
        val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
        var ts = 1000L
        var stabilizingFrames = 0

        // Settle in pure side profile (yaw = 88°, far right side occluded vis = 0.15)
        repeat(12) {
            ts += 33L
            val w = buildPushupWorld(elbowDeg = 166f, depthFrac = 0f, farSideVis = 0.15f)
            val (scr, camW) = projectWithYawAndPitch(w, yawDeg = 88f, pitchUpDeg = 8f)
            val f = engine.processFrame(scr, camW, ts)
            if (f.isStabilizing) stabilizingFrames++
        }

        repeat(4) {
            for (frameIdx in 0 until 36) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (frameIdx / 35.0))).toFloat()
                // Build true 2D screen with deep 92° bend, while simulating flattened MediaPipe 3D elbow (~126°)
                val wDeep = buildPushupWorld(elbowDeg = 166f - p * 74f, depthFrac = p, farSideVis = 0.15f)
                val wNoisy3D = buildPushupWorld(elbowDeg = 166f - p * 38f, depthFrac = p, farSideVis = 0.15f)
                val (scrDeep, _) = projectWithYawAndPitch(wDeep, yawDeg = 88f, pitchUpDeg = 8f)
                val (_, camWNoisy) = projectWithYawAndPitch(wNoisy3D, yawDeg = 88f, pitchUpDeg = 8f)
                val f = engine.processFrame(scrDeep, camWNoisy, ts)
                if (f.isStabilizing) stabilizingFrames++
            }
        }

        assertEquals("Side-profile push-ups with occluded far side must NEVER freeze", 0, stabilizingFrames)
        assertEquals("Side-profile with cross-checked 2D+3D elbows must count all 4 reps", 4, engine.emptyFrame().repCount)
    }

    // 4. Fast explosive touch-and-go push-ups (~480ms per rep, single 33ms bottom frame)
    @Test
    fun regression4_fastExplosiveTouchAndGoPushups_countsAll5Reps() {
        val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
        var ts = 1000L

        // Warm up 8 frames at top
        repeat(8) {
            ts += 33L
            val (scr, camW) = projectWithYawAndPitch(buildPushupWorld(166f, 0f), yawDeg = 45f)
            engine.processFrame(scr, camW, ts)
        }

        // 5 fast explosive reps (~15 frames each @ 33ms = 495ms per rep, crisp 1-frame turnaround at bottom!)
        repeat(5) {
            val cycle = listOf(165f, 148f, 130f, 114f, 98f, 88f, 102f, 122f, 142f, 158f, 166f, 166f, 166f, 166f, 166f)
            for (deg in cycle) {
                ts += 33L
                val p = ((166f - deg) / 78f).coerceIn(0f, 1f)
                val (scr, camW) = projectWithYawAndPitch(buildPushupWorld(deg, p), yawDeg = 45f)
                engine.processFrame(scr, camW, ts)
            }
        }
        assertEquals("Explosive touch-and-go push-ups must count all 5 reps", 5, engine.emptyFrame().repCount)
    }

    // 5. Straight-on 2D front-floor push-ups where elbows are foreshortened/unreliable (pure chest+head vertical drop!)
    @Test
    fun regression5_frontView2DOnlyChestDropPushups_passesGateAndCounts4Reps() {
        val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
        var ts = 1000L

        fun buildFront2DPushup(dropFrac: Float): List<PosePoint> {
            val pts = MutableList(33) { PosePoint(0.50f, 0.55f, 0f, 0.95f) }
            val wristY = 0.80f
            val shY = 0.44f + dropFrac * 0.28f // drops cleanly toward planted wrists (0.44 -> 0.72)
            val noseY = shY - 0.08f
            val hipY = 0.50f + dropFrac * 0.14f // foreshortened behind chest
            pts[BiomechanicsEngine.NOSE] = PosePoint(0.50f, noseY, 0f, 0.95f)
            pts[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(0.66f, shY, 0f, 0.95f)
            pts[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(0.34f, shY, 0f, 0.95f)
            // Simulated foreshortened 2D elbows staying almost straight (~148°) in pure front view!
            pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(0.70f, (shY + wristY) * 0.5f, 0f, 0.40f)
            pts[BiomechanicsEngine.RIGHT_ELBOW] = PosePoint(0.30f, (shY + wristY) * 0.5f, 0f, 0.40f)
            pts[BiomechanicsEngine.LEFT_WRIST] = PosePoint(0.72f, wristY, 0f, 0.90f)
            pts[BiomechanicsEngine.RIGHT_WRIST] = PosePoint(0.28f, wristY, 0f, 0.90f)
            pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(0.60f, hipY, 0f, 0.35f)
            pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(0.40f, hipY, 0f, 0.35f)
            pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(0.58f, 0.56f, 0f, 0.20f)
            pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(0.42f, 0.56f, 0f, 0.20f)
            pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(0.56f, 0.54f, 0f, 0.15f)
            pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(0.44f, 0.54f, 0f, 0.15f)
            return pts
        }

        repeat(10) {
            ts += 33L
            engine.processFrame(buildFront2DPushup(0f), emptyList(), ts)
        }
        repeat(4) {
            for (f in 0 until 36) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (f / 35.0))).toFloat()
                engine.processFrame(buildFront2DPushup(p), emptyList(), ts)
            }
        }
        assertEquals("Front-floor 2D chest+head drop push-ups must count all 4 reps", 4, engine.emptyFrame().repCount)
    }

    // 6. AutoExerciseDetector: Holding Plank 2.5s then starting Push-ups MUST switch immediately to PUSHUP!
    @Test
    fun regression6_autoDetector_plankToPushupUnlocksFastEvenWithActivePlankTime() {
        val detector = AutoExerciseDetector()
        var ts = 1000L
        var detected: ExerciseType? = null

        // Hold motionless plank for 2.5 seconds (76 frames @ 33ms) -> locks onto PLANK
        val (scrPlank, wldPlank) = projectWithYawAndPitch(buildPushupWorld(166f, 0f), yawDeg = 45f, pitchUpDeg = 12f)
        repeat(76) {
            ts += 33L
            detected = detector.processFrame(scrPlank, wldPlank, ts, hasActiveSetReps = false).detectedExercise
        }
        assertEquals("Should initially lock PLANK after 2.5s static hold", ExerciseType.PLANK, detected)

        // Now immediately start doing push-ups with hasActiveSetReps = true (plank timer had >= 2s!)
        var switchedToPushupFrame = -1
        for (i in 0 until 55) {
            ts += 33L
            val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 30.0))).toFloat()
            val (scr, wld) = projectWithYawAndPitch(buildPushupWorld(166f - p * 78f, p), yawDeg = 45f, pitchUpDeg = 12f)
            detected = detector.processFrame(scr, wld, ts, hasActiveSetReps = true).detectedExercise
            if (detected == ExerciseType.PUSHUP && switchedToPushupFrame < 0) {
                switchedToPushupFrame = i
            }
        }
        assertEquals("Starting push-ups from active PLANK must switch cleanly to PUSHUP", ExerciseType.PUSHUP, detected)
        assertTrue("Switch from PLANK to PUSHUP should happen fast (<45 frames), took $switchedToPushupFrame", switchedToPushupFrame in 1..45)
    }

    // 7. AutoExerciseDetector: Front-floor tilted phone push-ups cleanly auto-detect as PUSHUP (not SQUAT/BURPEE/PLANK)
    @Test
    fun regression7_autoDetector_frontFloorTiltedPhoneDetectsPushup() {
        val detector = AutoExerciseDetector()
        var ts = 1000L
        var lastDetected: ExerciseType? = null
        for (i in 0 until 72) {
            ts += 33L
            val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 30.0))).toFloat()
            val w = buildPushupWorld(elbowDeg = 165f - p * 76f, depthFrac = p, hipAnkleVis = 0.35f)
            val (scr, camW) = projectWithYawAndPitch(w, yawDeg = 8f, pitchUpDeg = 28f, camDistMeters = 1.7f)
            lastDetected = detector.processFrame(scr, camW, ts).detectedExercise
        }
        assertEquals("Front-floor tilted-phone push-ups must auto-detect as PUSHUP", ExerciseType.PUSHUP, lastDetected)
    }

    // 8. TRICEP_DIP anti-lockup after mounting parallel bars (high initial height -> normal top -> 3 dips)
    @Test
    fun regression8_tricepDipAntiLockup_counts3RepsCleanly() {
        val engine = BiomechanicsEngine(ExerciseType.TRICEP_DIP)
        var ts = 1000L

        fun buildDipPose(elbowDeg: Float, extraShoulderLift: Float = 0f): List<PosePoint> {
            val pts = blank3D()
            val depth = ((165f - elbowDeg) / 75f).coerceIn(0f, 1f)
            val wrY = 0.10f
            val shY = -0.38f + depth * 0.24f - extraShoulderLift
            val lSh = PosePoint(+0.19f, shY, 0f, 1f)
            val rSh = PosePoint(-0.19f, shY, 0f, 1f)
            val lWr = PosePoint(+0.25f, wrY, 0f, 1f)
            val rWr = PosePoint(-0.25f, wrY, 0f, 1f)
            val rad = (elbowDeg.coerceIn(60f, 178f) * 0.5) * PI / 180.0
            val chord = sqrt(((lWr.x - lSh.x) * (lWr.x - lSh.x) + (lWr.y - lSh.y) * (lWr.y - lSh.y)).toDouble()).toFloat()
            val elBack = ((chord * 0.5f) / tan(rad).toFloat()).coerceIn(0.01f, 0.28f)
            pts[BiomechanicsEngine.NOSE] = PosePoint(0f, shY - 0.18f, -0.05f, 1f)
            pts[BiomechanicsEngine.LEFT_SHOULDER] = lSh
            pts[BiomechanicsEngine.RIGHT_SHOULDER] = rSh
            pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(+0.24f, (shY + wrY) * 0.5f, +elBack, 1f)
            pts[BiomechanicsEngine.RIGHT_ELBOW] = PosePoint(-0.24f, (shY + wrY) * 0.5f, +elBack, 1f)
            pts[BiomechanicsEngine.LEFT_WRIST] = lWr
            pts[BiomechanicsEngine.RIGHT_WRIST] = rWr
            pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+0.13f, shY + 0.48f, 0.04f, 1f)
            pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-0.13f, shY + 0.48f, 0.04f, 1f)
            pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+0.13f, shY + 0.85f, 0.12f, 1f)
            pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-0.13f, shY + 0.85f, 0.12f, 1f)
            pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+0.13f, shY + 1.15f, 0.22f, 1f)
            pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-0.13f, shY + 1.15f, 0.22f, 1f)
            return pts
        }

        // Initial high mount (shrugged/elevated) then settle to normal top
        repeat(8) {
            ts += 33L
            val (scr, camW) = projectWithYawAndPitch(buildDipPose(166f, extraShoulderLift = 0.22f), yawDeg = 45f)
            engine.processFrame(scr, camW, ts)
        }
        repeat(10) {
            ts += 33L
            val (scr, camW) = projectWithYawAndPitch(buildDipPose(166f, extraShoulderLift = 0f), yawDeg = 45f)
            engine.processFrame(scr, camW, ts)
        }
        // Perform 3 dips
        repeat(3) {
            for (f in 0 until 36) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (f / 35.0))).toFloat()
                val (scr, camW) = projectWithYawAndPitch(buildDipPose(166f - p * 76f), yawDeg = 45f)
                engine.processFrame(scr, camW, ts)
            }
        }
        assertEquals("TRICEP_DIP must self-heal baseline and count all 3 reps", 3, engine.emptyFrame().repCount)
    }

    // 9. Constant-tension / soft-lockout push-ups (138° top -> 92° bottom -> 138° top)
    @Test
    fun regression9_constantTensionSoftLockoutPushups_countsAll4Reps() {
        val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
        var ts = 1000L
        repeat(8) {
            ts += 33L
            val (scr, camW) = projectWithYawAndPitch(buildPushupWorld(140f, 0.12f), yawDeg = 35f, pitchUpDeg = 12f)
            engine.processFrame(scr, camW, ts)
        }
        repeat(4) {
            for (f in 0 until 36) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (f / 35.0))).toFloat()
                val deg = 139f - p * 47f // 139° top -> 92° bottom -> 139° top
                val (scr, camW) = projectWithYawAndPitch(buildPushupWorld(deg, 0.12f + p * 0.80f), yawDeg = 35f, pitchUpDeg = 12f)
                engine.processFrame(scr, camW, ts)
            }
        }
        assertEquals("Constant-tension soft-lockout push-ups (139° top) must count all 4 reps", 4, engine.emptyFrame().repCount)
    }
}
