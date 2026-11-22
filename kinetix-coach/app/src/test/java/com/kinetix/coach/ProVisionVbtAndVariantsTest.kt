package com.kinetix.coach

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import com.kinetix.coach.vision.AngleCorrection
import com.kinetix.coach.vision.AutoExerciseDetector
import com.kinetix.coach.vision.BiomechanicsEngine
import com.kinetix.coach.vision.BufferedPoseFrame
import com.kinetix.coach.vision.FormQuality
import com.kinetix.coach.vision.PosePoint
import com.kinetix.coach.vision.PostureFeedbackCoach
import com.kinetix.coach.vision.RawPostureIssue
import com.kinetix.coach.vision.VelocityEstimator
import com.kinetix.coach.vision.VelocityFatigueZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class ProVisionVbtAndVariantsTest {

    private fun blank3D(): MutableList<PosePoint> = MutableList(33) { PosePoint(0f, 0f, 0f, 1f) }

    private fun rotateAndProject(
        world: List<PosePoint>,
        yawDeg: Float = 35f
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
                y = 0.52f + wp.y * 1.05f / depth,
                z = wp.z,
                visibility = wp.visibility
            )
        }
        return screen to rotW
    }

    private fun solveElbow3D(sh: PosePoint, wr: PosePoint, targetDeg: Float, signX: Float): PosePoint {
        val dx = wr.x - sh.x
        val dy = wr.y - sh.y
        val dz = wr.z - sh.z
        val chord = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat().coerceAtLeast(0.05f)
        val ux = dx / chord
        val uy = dy / chord
        val uz = dz / chord
        val halfRad = (targetDeg.coerceIn(45f, 178f) * 0.5) * PI / 180.0
        val midX = (sh.x + wr.x) * 0.5f
        val midY = (sh.y + wr.y) * 0.5f
        val midZ = (sh.z + wr.z) * 0.5f
        val outDist = ((chord * 0.5f) / tan(halfRad).toFloat()).coerceIn(0.005f, 0.32f)
        var px = signX * 0.65f
        var py = 0f
        var pz = 0.76f
        val dot = px * ux + py * uy + pz * uz
        px -= dot * ux
        py -= dot * uy
        pz -= dot * uz
        val pLen = sqrt((px * px + py * py + pz * pz).toDouble()).toFloat().coerceAtLeast(1e-4f)
        return PosePoint(
            x = midX + (px / pLen) * outDist,
            y = midY + (py / pLen) * outDist,
            z = midZ + (pz / pLen) * outDist,
            visibility = 1f
        )
    }

    private fun buildPushup3D(
        leftElbowDeg: Float,
        rightElbowDeg: Float = leftElbowDeg,
        wristSpreadRatio: Float = 1.15f,
        depthFrac: Float = ((165f - minOf(leftElbowDeg, rightElbowDeg)) / 75f).coerceIn(0f, 1f),
        incline: Boolean = false,
        decline: Boolean = false,
        farSideOccluded: Boolean = false
    ): List<PosePoint> {
        val pts = blank3D()
        val shHalfW = 0.19f
        val hipHalfW = 0.13f
        val floorY = 0.44f
        val shZ = -0.50f

        val wrY: Float
        val shY: Float
        val ankleY: Float
        val ankleZ: Float

        when {
            incline -> {
                // Hands on 50cm bench, feet on floor -> body inclined ~42°
                wrY = floorY - 0.42f
                shY = wrY - 0.36f + depthFrac * 0.24f
                ankleY = floorY
                ankleZ = 0.55f
            }
            decline -> {
                // Feet elevated 45cm on bench, hands on floor
                wrY = floorY
                shY = 0.04f + depthFrac * 0.26f
                ankleY = floorY - 0.46f
                ankleZ = 0.74f
            }
            else -> {
                wrY = floorY
                shY = -0.04f + depthFrac * 0.32f
                ankleY = floorY - 0.03f
                ankleZ = 0.78f
            }
        }

        val hipZ = shZ + 0.44f * (ankleZ - shZ)
        val hipY = shY + 0.44f * (ankleY - shY)
        val kneeZ = shZ + 0.72f * (ankleZ - shZ)
        val kneeY = shY + 0.72f * (ankleY - shY)

        val wristHalfX = shHalfW * wristSpreadRatio
        val rVis = if (farSideOccluded) 0.15f else 1f
        val lSh = PosePoint(+shHalfW, shY, shZ, 1f)
        val rSh = PosePoint(-shHalfW, shY, shZ, rVis)
        val lWr = PosePoint(+wristHalfX, wrY, shZ - 0.02f, 1f)
        val rWr = PosePoint(-wristHalfX, wrY, shZ - 0.02f, rVis)
        val lEl = solveElbow3D(lSh, lWr, leftElbowDeg, +1f)
        val rEl = solveElbow3D(rSh, rWr, rightElbowDeg, -1f).copy(visibility = rVis)

        pts[BiomechanicsEngine.NOSE] = PosePoint(0f, shY - 0.05f, shZ - 0.18f, 1f)
        pts[BiomechanicsEngine.LEFT_SHOULDER] = lSh
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = rSh
        pts[BiomechanicsEngine.LEFT_ELBOW] = lEl
        pts[BiomechanicsEngine.RIGHT_ELBOW] = rEl
        pts[BiomechanicsEngine.LEFT_WRIST] = lWr
        pts[BiomechanicsEngine.RIGHT_WRIST] = rWr
        pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+hipHalfW, hipY, hipZ, 1f)
        pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-hipHalfW, hipY, hipZ, rVis)
        pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+hipHalfW, kneeY, kneeZ, 1f)
        pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-hipHalfW, kneeY, kneeZ, rVis)
        pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+hipHalfW, ankleY, ankleZ, 1f)
        pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-hipHalfW, ankleY, ankleZ, rVis)
        return pts
    }

    private fun buildSquat3D(
        leftKneeDeg: Float,
        rightKneeDeg: Float = leftKneeDeg,
        torsoLeanDeg: Float = 20f,
        ankleHalfWidth: Float = 0.14f,
        rightAnkleLiftY: Float = 0f
    ): List<PosePoint> {
        val pts = blank3D()
        val hipW = 0.13f
        val shW = 0.19f
        val thigh = 0.42f
        val shin = 0.42f
        val torso = 0.50f

        fun leg(xHip: Float, xAnk: Float, deg: Float, liftY: Float): Triple<PosePoint, PosePoint, PosePoint> {
            val bendFrac = ((175f - deg) / 90f).coerceIn(0f, 1f)
            val shinTilt = (10f * bendFrac) * PI / 180.0
            val kRad = deg * PI / 180.0
            val shinY = cos(shinTilt).toFloat()
            val shinZ = sin(shinTilt).toFloat()
            val hipDirY = (shinY * cos(kRad) - shinZ * sin(kRad)).toFloat()
            val hipDirZ = (shinY * sin(kRad) + shinZ * cos(kRad)).toFloat()
            val hip = PosePoint(xHip, 0f, 0f, 1f)
            val knee = PosePoint(xAnk, -thigh * hipDirY, -thigh * hipDirZ, 1f)
            val ankle = PosePoint(xAnk, knee.y + shin * shinY - liftY, knee.z + shin * shinZ, 1f)
            return Triple(hip, knee, ankle)
        }

        val (lHip, lKnee, lAnkle) = leg(+hipW, +ankleHalfWidth, leftKneeDeg, 0f)
        val (rHip, rKnee, rAnkle) = leg(-hipW, -ankleHalfWidth, rightKneeDeg, rightAnkleLiftY)

        val leanRad = torsoLeanDeg * PI / 180.0
        val shY = -(torso * cos(leanRad)).toFloat()
        val shZ = -(torso * sin(leanRad)).toFloat()
        val lSh = PosePoint(+shW, shY, shZ, 1f)
        val rSh = PosePoint(-shW, shY, shZ, 1f)

        pts[BiomechanicsEngine.NOSE] = PosePoint(0f, shY - 0.16f, shZ - 0.14f, 1f)
        pts[BiomechanicsEngine.LEFT_SHOULDER] = lSh
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = rSh
        pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(+shW + 0.04f, shY + 0.24f, shZ - 0.04f, 1f)
        pts[BiomechanicsEngine.RIGHT_ELBOW] = PosePoint(-shW - 0.04f, shY + 0.24f, shZ - 0.04f, 1f)
        pts[BiomechanicsEngine.LEFT_WRIST] = PosePoint(+shW + 0.04f, shY + 0.44f, shZ - 0.12f, 1f)
        pts[BiomechanicsEngine.RIGHT_WRIST] = PosePoint(-shW - 0.04f, shY + 0.44f, shZ - 0.12f, 1f)
        pts[BiomechanicsEngine.LEFT_HIP] = lHip
        pts[BiomechanicsEngine.RIGHT_HIP] = rHip
        pts[BiomechanicsEngine.LEFT_KNEE] = lKnee
        pts[BiomechanicsEngine.RIGHT_KNEE] = rKnee
        pts[BiomechanicsEngine.LEFT_ANKLE] = lAnkle
        pts[BiomechanicsEngine.RIGHT_ANKLE] = rAnkle
        return pts
    }

    // -------------------------------------------------------------------------
    // 1. REAL-WORLD PUSH-UP STRESS TESTS (Front view, Occluded Profile, Deadlock, 9 Variants, Fast Plank->Pushup)
    // -------------------------------------------------------------------------

    @Test
    fun pushup_liveFrontViewAndOccludedProfile_countAllRepsWithoutFalseStabilizationOrDeadlock() {
        // 1A. Pure FRONT view (0° yaw) with live One-Euro + PoseSanityFilter (previously aborted by bodyScaleRate!)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
            var ts = 1000L
            var stabilizingFrames = 0
            repeat(5) {
                for (i in 0 until 28) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 27.0))).toFloat()
                    val elbow = 165f - p * 75f
                    val (scr, wld) = rotateAndProject(buildPushup3D(elbow, depthFrac = p), yawDeg = 0f)
                    val f = engine.processFrame(scr, wld, ts)
                    if (f.isStabilizing) stabilizingFrames++
                }
            }
            assertEquals("Front-view live push-ups must NOT falsely trigger stabilization", 0, stabilizingFrames)
            assertEquals("Front-view live push-ups must count all 5 reps", 5, engine.emptyFrame().repCount)
        }

        // 1B. Pure SIDE PROFILE (90° yaw) with far shoulder/hip occluded (vis = 0.15)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
            var ts = 1000L
            var stabilizingFrames = 0
            repeat(4) {
                for (i in 0 until 28) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 27.0))).toFloat()
                    val elbow = 166f - p * 76f
                    val (scr, wld) = rotateAndProject(
                        buildPushup3D(elbow, depthFrac = p, farSideOccluded = true),
                        yawDeg = 90f
                    )
                    val f = engine.processFrame(scr, wld, ts)
                    if (f.isStabilizing) stabilizingFrames++
                }
            }
            assertEquals("Occluded far-side profile push-ups must NOT trigger stabilization", 0, stabilizingFrames)
            assertEquals("Occluded profile push-ups must count all 4 reps", 4, engine.emptyFrame().repCount)
        }

        // 1C. Setup transition height spike MUST NOT deadlock adaptivePushupTopHeight in BOTTOM!
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
            var ts = 1000L
            // Inject an artificially high setup frame (hands far below shoulders as athlete drops down)
            val highSetup = buildPushup3D(168f, depthFrac = 0f).toMutableList()
            highSetup[BiomechanicsEngine.LEFT_WRIST] = highSetup[BiomechanicsEngine.LEFT_WRIST].copy(y = 0.78f)
            highSetup[BiomechanicsEngine.RIGHT_WRIST] = highSetup[BiomechanicsEngine.RIGHT_WRIST].copy(y = 0.78f)
            repeat(6) {
                ts += 33L
                val (s, w) = rotateAndProject(highSetup, yawDeg = 35f)
                engine.processFrame(s, w, ts)
            }
            // Now perform 4 normal floor push-ups
            repeat(4) {
                for (i in 0 until 28) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 27.0))).toFloat()
                    val (s, w) = rotateAndProject(buildPushup3D(165f - p * 75f, depthFrac = p), yawDeg = 35f)
                    engine.processFrame(s, w, ts)
                }
            }
            assertEquals("Setup height spike must NEVER deadlock push-up counter", 4, engine.emptyFrame().repCount)
        }
    }

    @Test
    fun pushup_newInclineDeclineCloseVariantsAndAutoDetectionWorkCleanly() {
        // 1. Incline push-ups (hands on bench)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP, emaAlpha = 1.0f)
            val det = AutoExerciseDetector()
            var ts = 1000L
            var variant: String? = null
            var detected: ExerciseType? = null
            repeat(3) {
                for (i in 0 until 26) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 25.0))).toFloat()
                    val (s, w) = rotateAndProject(buildPushup3D(165f - p * 70f, depthFrac = p, incline = true), yawDeg = 45f)
                    val f = engine.processFrame(s, w, ts)
                    if (p > 0.5f) variant = f.detectedVariantFr
                    detected = det.processFrame(s, w, ts).detectedExercise
                }
            }
            assertEquals("Incline push-ups should count 3 reps", 3, engine.emptyFrame().repCount)
            assertEquals("Inclinée", variant)
            assertEquals("AutoExerciseDetector must classify incline push-ups as PUSHUP", ExerciseType.PUSHUP, detected)
        }

        // 2. Decline push-ups (feet elevated)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP, emaAlpha = 1.0f)
            var ts = 1000L
            var variant: String? = null
            repeat(2) {
                for (deg in listOf(165f, 134f, 96f, 134f, 165f)) {
                    ts += 100L
                    val (s, w) = rotateAndProject(buildPushup3D(deg, decline = true), yawDeg = 45f)
                    val f = engine.processFrame(s, w, ts)
                    if (deg < 110f) variant = f.detectedVariantFr
                }
            }
            assertEquals("Decline push-ups should count 2 reps", 2, engine.emptyFrame().repCount)
            assertEquals("Déclinée", variant)
        }

        // 3. Close-grip (Serrée) push-ups (wristSpreadRatio = 0.70)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP, emaAlpha = 1.0f)
            var ts = 1000L
            var variant: String? = null
            repeat(2) {
                for (deg in listOf(165f, 135f, 98f, 135f, 165f)) {
                    ts += 100L
                    val (s, w) = rotateAndProject(buildPushup3D(deg, wristSpreadRatio = 0.70f), yawDeg = 20f)
                    val f = engine.processFrame(s, w, ts)
                    if (deg < 110f) variant = f.detectedVariantFr
                }
            }
            assertEquals("Close push-ups should count 2 reps", 2, engine.emptyFrame().repCount)
            assertEquals("Serrée", variant)
        }

        // 4. Deep chest-to-floor push-ups (shoulders right at wrist height!) NEVER falsely trigger BURPEE and wake up quickly from PLANK!
        run {
            val det = AutoExerciseDetector()
            var ts = 1000L
            // Hold plank setup for 1.8s so AutoExerciseDetector locks onto PLANK first
            val (sTop, wTop) = rotateAndProject(buildPushup3D(166f, depthFrac = 0f), yawDeg = 45f)
            repeat(55) {
                ts += 33L
                det.processFrame(sTop, wTop, ts, hasActiveSetReps = false)
            }
            assertEquals("Setup pause should lock onto PLANK first", ExerciseType.PLANK, det.currentLockedExercise)

            // Now immediately do 2 deep chest-to-floor push-ups (even with hasActiveSetReps = true!)
            var lastDet: ExerciseType? = null
            var everBurpee = false
            for (i in 0 until 56) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 26.0))).toFloat()
                // Deep push-up where shoulders drop to floorY - 0.02f (almost touching wrists!)
                val world = buildPushup3D(166f - p * 84f, depthFrac = p).toMutableList()
                if (p > 0.75f) {
                    world[BiomechanicsEngine.LEFT_SHOULDER] = world[BiomechanicsEngine.LEFT_SHOULDER].copy(y = 0.42f)
                    world[BiomechanicsEngine.RIGHT_SHOULDER] = world[BiomechanicsEngine.RIGHT_SHOULDER].copy(y = 0.42f)
                }
                val (s, w) = rotateAndProject(world, yawDeg = 45f)
                val res = det.processFrame(s, w, ts, hasActiveSetReps = true)
                lastDet = res.detectedExercise
                if (lastDet == ExerciseType.BURPEE) everBurpee = true
            }
            assertFalse("Deep push-ups must NEVER falsely trigger BURPEE", everBurpee)
            assertEquals("Starting push-ups from Plank setup must rapidly unlock to PUSHUP", ExerciseType.PUSHUP, lastDet)
        }
    }

    // -------------------------------------------------------------------------
    // 2. ALL-16-EXERCISE VARIANTS & BIOMECHANICS AUDIT FIXES
    // -------------------------------------------------------------------------

    @Test
    fun allExerciseVariantsAndFixedEdgeCases_detectAndCountAccurately() {
        // A. SUMO SQUAT classified as SQUAT (not LUNGE!) in AutoExerciseDetector AND variant == "Sumo"
        run {
            val engine = BiomechanicsEngine(ExerciseType.SQUAT, emaAlpha = 1.0f)
            val det = AutoExerciseDetector()
            var ts = 1000L
            var variant: String? = null
            var detected: ExerciseType? = null
            repeat(3) {
                for (i in 0 until 26) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 25.0))).toFloat()
                    val (s, w) = rotateAndProject(
                        buildSquat3D(leftKneeDeg = 170f - p * 82f, torsoLeanDeg = 22f, ankleHalfWidth = 0.34f),
                        yawDeg = 15f
                    )
                    val f = engine.processFrame(s, w, ts)
                    if (p > 0.6f) variant = f.detectedVariantFr
                    detected = det.processFrame(s, w, ts).detectedExercise
                }
            }
            assertEquals("Sumo", variant)
            assertEquals("Sumo Squat must auto-detect as SQUAT (never LUNGE!)", ExerciseType.SQUAT, detected)
            assertEquals("Sumo Squat should count 3 reps", 3, engine.emptyFrame().repCount)
        }

        // B. BULGARIAN SQUAT with pre-bent elevated rear leg (85°) tracks GROUNDED front leg cleanly!
        run {
            val engine = BiomechanicsEngine(ExerciseType.BULGARIAN_SQUAT, emaAlpha = 1.0f)
            var ts = 1000L
            repeat(2) {
                for (frontKnee in listOf(164f, 130f, 94f, 130f, 164f)) {
                    ts += 100L
                    // Left leg is grounded front leg (liftY = 0); Right leg is elevated rear leg bent at 85°!
                    val (s, w) = rotateAndProject(
                        buildSquat3D(leftKneeDeg = frontKnee, rightKneeDeg = 85f, torsoLeanDeg = 15f, rightAnkleLiftY = 0.28f),
                        yawDeg = 35f
                    )
                    engine.processFrame(s, w, ts)
                }
            }
            assertEquals("Bulgarian squat must track grounded front leg (not pre-bent rear leg) and count 2 reps", 2, engine.emptyFrame().repCount)
        }

        // C. UNILATERAL / ALTERNATING OVERHEAD PRESS counts cleanly without symmetry fault!
        run {
            val engine = BiomechanicsEngine(ExerciseType.OVERHEAD_PRESS, emaAlpha = 1.0f)
            var ts = 1000L
            var sawUnilateralVariant = false
            var sawSymFault = false
            val seq = listOf(0f to 0f, 0.5f to 0f, 1f to 0f, 0.5f to 0f, 0f to 0f, 0f to 0.5f, 0f to 1f, 0f to 0.5f, 0f to 0f)
            for ((lProg, rProg) in seq) {
                ts += 120L
                val pts = buildSquat3D(172f, torsoLeanDeg = 4f).toMutableList()
                val shY = pts[BiomechanicsEngine.LEFT_SHOULDER].y
                val shW = 0.19f
                fun arm(signX: Float, p: Float): Pair<PosePoint, PosePoint> {
                    val el = PosePoint(signX * (shW + 0.14f * (1f - p) + 0.03f * p), shY + 0.06f * (1f - p) - 0.24f * p, -0.04f, 1f)
                    val wr = PosePoint(signX * (shW + 0.04f * (1f - p) + 0.02f * p), shY - 0.08f * (1f - p) - 0.52f * p, -0.04f, 1f)
                    return el to wr
                }
                val (lEl, lWr) = arm(+1f, lProg)
                val (rEl, rWr) = arm(-1f, rProg)
                pts[BiomechanicsEngine.LEFT_ELBOW] = lEl
                pts[BiomechanicsEngine.LEFT_WRIST] = lWr
                pts[BiomechanicsEngine.RIGHT_ELBOW] = rEl
                pts[BiomechanicsEngine.RIGHT_WRIST] = rWr
                val (s, w) = rotateAndProject(pts, yawDeg = 5f)
                val f = engine.processFrame(s, w, ts)
                if (f.detectedVariantFr == "Unilatéral" || f.detectedVariantFr == "Alterné") sawUnilateralVariant = true
                if (f.corrections.any { it.shortBadgeFr.contains("SYMÉTRIE") }) sawSymFault = true
            }
            assertEquals("Alternating 1-arm OHP must count both reps", 2, engine.emptyFrame().repCount)
            assertTrue("Expected unilateral/alternating OHP variant badge", sawUnilateralVariant)
            assertFalse("Unilateral OHP must NOT emit symmetry fault", sawSymFault)
        }

        // D. HANGING LEG RAISE passes posture gate & detects "Suspendu" variant!
        run {
            val engine = BiomechanicsEngine(ExerciseType.LEG_RAISE, emaAlpha = 1.0f)
            var ts = 1000L
            var variant: String? = null
            repeat(2) {
                for (hipDeg in listOf(166f, 130f, 94f, 130f, 166f)) {
                    ts += 100L
                    val pts = blank3D()
                    pts[BiomechanicsEngine.NOSE] = PosePoint(0f, -0.66f, -0.06f, 1f)
                    pts[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(+0.19f, -0.50f, 0f, 1f)
                    pts[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(-0.19f, -0.50f, 0f, 1f)
                    pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(+0.19f, -0.78f, 0f, 1f)
                    pts[BiomechanicsEngine.RIGHT_ELBOW] = PosePoint(-0.19f, -0.78f, 0f, 1f)
                    pts[BiomechanicsEngine.LEFT_WRIST] = PosePoint(+0.19f, -1.04f, 0f, 1f)
                    pts[BiomechanicsEngine.RIGHT_WRIST] = PosePoint(-0.19f, -1.04f, 0f, 1f)
                    pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+0.13f, 0f, 0f, 1f)
                    pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-0.13f, 0f, 0f, 1f)
                    val liftRad = (180f - hipDeg) * PI / 180.0
                    val kY = (0.42f * cos(liftRad)).toFloat()
                    val kZ = -(0.42f * sin(liftRad)).toFloat()
                    val aY = (0.84f * cos(liftRad)).toFloat()
                    val aZ = -(0.84f * sin(liftRad)).toFloat()
                    pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+0.13f, kY, kZ, 1f)
                    pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-0.13f, kY, kZ, 1f)
                    pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+0.13f, aY, aZ, 1f)
                    pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-0.13f, aY, aZ, 1f)
                    val (s, w) = rotateAndProject(pts, yawDeg = 45f)
                    val f = engine.processFrame(s, w, ts)
                    variant = f.detectedVariantFr
                }
            }
            assertEquals("Hanging leg raises must count 2 reps", 2, engine.emptyFrame().repCount)
            assertEquals("Suspendu", variant)
        }

        // E. SINGLE-LEG GLUTE BRIDGE counts cleanly using grounded leg & detects "Unilatéral"
        run {
            val engine = BiomechanicsEngine(ExerciseType.GLUTE_BRIDGE, emaAlpha = 1.0f)
            var ts = 1000L
            var variant: String? = null
            repeat(2) {
                for (upFrac in listOf(0f, 0.5f, 1f, 0.5f, 0f)) {
                    ts += 100L
                    val pts = blank3D()
                    val floorY = 0.26f
                    val hipY = floorY - upFrac * 0.25f
                    pts[BiomechanicsEngine.NOSE] = PosePoint(0f, floorY - 0.12f, -0.66f, 1f)
                    pts[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(+0.18f, floorY - 0.03f, -0.50f, 1f)
                    pts[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(-0.18f, floorY - 0.03f, -0.50f, 1f)
                    pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+0.13f, hipY, 0f, 1f)
                    pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-0.13f, hipY, 0f, 1f)
                    // Left leg grounded driving bridge; Right leg lifted high in air (90° hip flexion!)
                    pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+0.14f, floorY - 0.32f, 0.36f, 1f)
                    pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+0.14f, floorY, 0.44f, 1f)
                    pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-0.14f, hipY - 0.36f, 0.10f, 1f)
                    pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-0.14f, hipY - 0.72f, 0.18f, 1f)
                    val (s, w) = rotateAndProject(pts, yawDeg = 45f)
                    val f = engine.processFrame(s, w, ts)
                    variant = f.detectedVariantFr
                }
            }
            assertEquals("Single-leg glute bridge must count 2 reps using grounded leg", 2, engine.emptyFrame().repCount)
            assertEquals("Unilatéral", variant)
        }

        // F. ALTERNATING LATERAL RAISE counts cleanly without symmetry penalty!
        run {
            val engine = BiomechanicsEngine(ExerciseType.LATERAL_RAISE, emaAlpha = 1.0f)
            var ts = 1000L
            var sawSymFault = false
            var variant: String? = null
            val seq = listOf(18f to 18f, 55f to 18f, 88f to 18f, 50f to 18f, 18f to 18f, 18f to 55f, 18f to 88f, 18f to 50f, 18f to 18f)
            for ((lAbd, rAbd) in seq) {
                ts += 100L
                val pts = buildSquat3D(172f, torsoLeanDeg = 4f).toMutableList()
                val lSh = pts[BiomechanicsEngine.LEFT_SHOULDER]
                val rSh = pts[BiomechanicsEngine.RIGHT_SHOULDER]
                val lRad = lAbd * PI / 180.0
                val rRad = rAbd * PI / 180.0
                pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(lSh.x + (0.28f * sin(lRad)).toFloat(), lSh.y + (0.28f * cos(lRad)).toFloat(), lSh.z, 1f)
                pts[BiomechanicsEngine.LEFT_WRIST] = PosePoint(lSh.x + (0.54f * sin(lRad)).toFloat(), lSh.y + (0.54f * cos(lRad)).toFloat(), lSh.z, 1f)
                pts[BiomechanicsEngine.RIGHT_ELBOW] = PosePoint(rSh.x - (0.28f * sin(rRad)).toFloat(), rSh.y + (0.28f * cos(rRad)).toFloat(), rSh.z, 1f)
                pts[BiomechanicsEngine.RIGHT_WRIST] = PosePoint(rSh.x - (0.54f * sin(rRad)).toFloat(), rSh.y + (0.54f * cos(rRad)).toFloat(), rSh.z, 1f)
                val (s, w) = rotateAndProject(pts, yawDeg = 5f)
                val f = engine.processFrame(s, w, ts)
                if (maxOf(lAbd, rAbd) > 70f) variant = f.detectedVariantFr
                if (f.corrections.any { it.shortBadgeFr.contains("SYMÉTRIE") }) sawSymFault = true
            }
            assertEquals("Alternating 1-arm lateral raises must count both reps", 2, engine.emptyFrame().repCount)
            assertTrue("Expected Unilatéral or Alterné variant, got $variant", variant == "Unilatéral" || variant == "Alterné")
            assertFalse("Unilateral lateral raise must NOT emit symmetry fault", sawSymFault)
        }

        // G. PENDLAY ROW (74° horizontal torso) classifies as "Pendlay" and does NOT emit false "relevez le buste" fault!
        run {
            val engine = BiomechanicsEngine(ExerciseType.BENT_ROW, emaAlpha = 1.0f)
            var ts = 1000L
            var variant: String? = null
            var torsoFault = false
            val pts = buildSquat3D(152f, torsoLeanDeg = 74f).toMutableList()
            val (s, w) = rotateAndProject(pts, yawDeg = 90f)
            val f = engine.processFrame(s, w, ts + 100L)
            variant = f.detectedVariantFr
            torsoFault = f.corrections.any { it.jointLabelFr.equals("Buste", ignoreCase = true) }
            assertEquals("Pendlay", variant)
            assertFalse("Pendlay row at 74° must NOT emit torso lean fault", torsoFault)
        }

        // H. DEADLIFT SUMO & RDL variants classify cleanly
        run {
            val sumoEngine = BiomechanicsEngine(ExerciseType.DEADLIFT, emaAlpha = 1.0f)
            val (sSumo, wSumo) = rotateAndProject(buildSquat3D(110f, torsoLeanDeg = 46f, ankleHalfWidth = 0.34f), yawDeg = 15f)
            val fSumo = sumoEngine.processFrame(sSumo, wSumo, 1000L)
            assertEquals("Sumo", fSumo.detectedVariantFr)
            assertFalse("Sumo deadlift at 110° knee must NOT emit squatting-bar fault", fSumo.corrections.any { it.shortBadgeFr.contains("CHARNIÈRE") })

            val rdlEngine = BiomechanicsEngine(ExerciseType.DEADLIFT, emaAlpha = 1.0f)
            val (sRdl, wRdl) = rotateAndProject(buildSquat3D(164f, torsoLeanDeg = 60f, ankleHalfWidth = 0.14f), yawDeg = 45f)
            val fRdl = rdlEngine.processFrame(sRdl, wRdl, 1000L)
            assertEquals("Roumain (RDL)", fRdl.detectedVariantFr)
        }
    }

    // -------------------------------------------------------------------------
    // 3. ANTI-SPAM POSTURE COACHING GATE TESTS
    // -------------------------------------------------------------------------

    @Test
    fun postureFeedbackCoach_blocksGlitchSpam_limitsVoiceFrequency_andEliminatesMidDescentNagging() {
        val coach = PostureFeedbackCoach()
        val dummyFault = RawPostureIssue(
            key = "SQUAT_TORSO",
            correction = AngleCorrection(
                jointIndex = BiomechanicsEngine.LEFT_SHOULDER,
                jointLabelFr = "Buste",
                currentDeg = 54,
                targetMinDeg = 0,
                targetMaxDeg = 44,
                deltaDeg = 10,
                severity = FormQuality.FAULT,
                arrowDx = 0f,
                arrowDy = -1f,
                shortBadgeFr = "BUSTE +10°",
                instructionFr = "Redressez le buste de 10°",
                voiceCueFr = "Redressez le buste"
            ),
            scorePenalty = 22,
            feedbackMsgFr = "Redressez la poitrine (buste trop penché)"
        )

        // 1. Brief 165ms glitch (5 frames @ 33ms < 360ms FAULT_ONSET_MS) -> ZERO corrections, ZERO penalty, ZERO voice!
        var ts = 1000L
        repeat(5) {
            ts += 33L
            val res = coach.evaluate(listOf(dummyFault), ts, repCount = 0, isIsometric = false, deterministicPassThrough = false)
            assertTrue("Sub-onset 165ms glitch must NOT emit visual correction", res.confirmedCorrections.isEmpty())
            assertEquals("Sub-onset glitch must NOT penalize form score", 0, res.totalScorePenalty)
            assertNull("Sub-onset glitch must NOT emit voice cue", res.postureVoiceCueFr)
        }

        // 2. Sustained fault over 45 seconds (1350 frames @ 33ms) -> AT MOST 3 posture voice cues!
        val longCoach = PostureFeedbackCoach()
        var voiceCount = 0
        var lastVoiceTs = Long.MIN_VALUE
        var tsLong = 1000L
        for (frame in 0 until 1350) {
            tsLong += 33L
            val simulatedRep = frame / 90 // ~3s per rep -> 15 reps
            val res = longCoach.evaluate(listOf(dummyFault), tsLong, repCount = simulatedRep, isIsometric = false, deterministicPassThrough = false)
            if (res.postureVoiceCueFr != null) {
                voiceCount++
                if (lastVoiceTs != Long.MIN_VALUE) {
                    assertTrue(
                        "Same-key posture voice cue must respect >= 20s cooldown, gap=${tsLong - lastVoiceTs}ms",
                        (tsLong - lastVoiceTs) >= PostureFeedbackCoach.VOICE_SAME_KEY_COOLDOWN_MS
                    )
                }
                lastVoiceTs = tsLong
            }
        }
        assertTrue("45s continuous fault must speak calmly (1..3 times total), spoke $voiceCount times", voiceCount in 1..3)

        // 3. Normal smooth live Squat descent emits ZERO mid-descent "Descendez encore" spam!
        val liveSquat = BiomechanicsEngine(ExerciseType.SQUAT)
        var tsSq = 1000L
        var midDescentDepthWarnings = 0
        repeat(3) {
            for (i in 0 until 36) {
                tsSq += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                val kneeDeg = 172f - p * 84f // smooth full squat down to 88°
                val (s, w) = rotateAndProject(buildSquat3D(kneeDeg, torsoLeanDeg = 22f), yawDeg = 45f)
                val f = liveSquat.processFrame(s, w, tsSq)
                if (f.corrections.any { it.jointLabelFr.equals("Genou", ignoreCase = true) }) {
                    midDescentDepthWarnings++
                }
            }
        }
        assertEquals("Clean 3-rep live squat set must count 3 reps", 3, liveSquat.emptyFrame().repCount)
        assertEquals("Smooth normal descent must produce ZERO mid-descent depth nagging!", 0, midDescentDepthWarnings)
    }

    // -------------------------------------------------------------------------
    // 4. VBT PROGRESSIVE VELOCITY & ESTIMATED MAX REPS ENGINE TESTS
    // -------------------------------------------------------------------------

    @Test
    fun velocityEstimator_tracksProgressiveSlowdown_convergesEstimatedMaxReps_andTracksCrossSetReadiness() {
        val vbt = VelocityEstimator(ExerciseType.PUSHUP)
        var ts = 1000L

        // Simulate Set 1 of Push-ups slowing down progressively as fatigue mounts:
        // Rep 1..9 concentric durations rising 0.48s -> 1.05s over ~0.34m displacement -> velocities ~0.71 down to ~0.32 m/s
        val set1ConcentricDurations = listOf(0.50f, 0.48f, 0.52f, 0.57f, 0.63f, 0.70f, 0.78f, 0.88f, 1.02f)
        var lastSnap = vbt.currentTelemetry()

        set1ConcentricDurations.forEachIndexed { idx, concSec ->
            ts += 2200L
            lastSnap = vbt.recordRep(
                concentricDurationSec = concSec,
                eccentricDurationSec = 1.10f,
                totalDurationSec = concSec + 1.10f,
                measuredDisplacementMeters = 0.34f,
                peakInstantVelocityMps = (0.34f / concSec) * 1.25f,
                romPct = 95,
                timestampMs = ts
            )
            if (idx < 2) {
                assertEquals("Reps 1..2 on Set 1 should calibrate", VelocityFatigueZone.CALIBRATING, lastSnap.fatigueZone)
                assertNull("No premature Max Rep estimate before Rep 3 on Set 1", lastSnap.estimatedMaxReps)
            } else {
                assertNotNull("Rep ${idx + 1} must provide estimatedMaxReps", lastSnap.estimatedMaxReps)
                assertTrue("Estimated max reps must be >= completed reps", lastSnap.estimatedMaxReps!! >= idx + 1)
            }
        }

        // At Rep 9 (0.33 m/s vs 0.69 m/s baseline -> ~50% velocity loss), athlete is right near muscular failure!
        assertTrue("Expected high velocity loss (>= 40%), got ${lastSnap.velocityLossPct}%", lastSnap.velocityLossPct >= 40)
        assertEquals("Should reach NEAR_FAILURE zone at ~50% velocity loss", VelocityFatigueZone.NEAR_FAILURE, lastSnap.fatigueZone)
        assertTrue(
            "Estimated max reps at ~50% VL on rep 9 should converge tightly in 9..12, got ${lastSnap.estimatedMaxReps}",
            lastSnap.estimatedMaxReps in 9..12
        )
        assertTrue("RIR near failure should be 0..2, got ${lastSnap.estimatedRir}", (lastSnap.estimatedRir ?: 99) <= 2)
        assertTrue("High confidence expected after 9-rep fatigue curve, got ${lastSnap.confidencePct}%", lastSnap.confidencePct >= 75)

        // Complete Set 1 -> start Set 2 fatigued (~15% slower baseline)
        val set1Summary = vbt.completeSet()
        assertNotNull(set1Summary)
        assertEquals(9, set1Summary!!.repCount)

        val set2ConcDurations = listOf(0.58f, 0.62f, 0.69f, 0.78f, 0.92f)
        var set2Snap = vbt.currentTelemetry()
        for (concSec in set2ConcDurations) {
            ts += 2200L
            set2Snap = vbt.recordRep(
                concentricDurationSec = concSec,
                eccentricDurationSec = 1.10f,
                totalDurationSec = concSec + 1.10f,
                measuredDisplacementMeters = 0.34f,
                peakInstantVelocityMps = (0.34f / concSec) * 1.25f,
                romPct = 95,
                timestampMs = ts
            )
        }
        assertEquals("Completed sets list must preserve Set 1 summary", 1, set2Snap.completedSets.size)
        assertTrue("Set 2 starting 15% slower must report reduced readiness (< 92%), got ${set2Snap.readinessPct}%", set2Snap.readinessPct < 92)
        assertNotNull("Set 2 must estimate reduced set max reps", set2Snap.estimatedMaxReps)
        assertTrue(
            "Fatigued Set 2 estimated max (${set2Snap.estimatedMaxReps}) should be lower than fresh Set 1 max (${set1Summary.estimatedMaxReps})",
            set2Snap.estimatedMaxReps!! <= (set1Summary.estimatedMaxReps ?: 12)
        )
    }

    @Test
    fun endToEndBiomechanicsEngine_measuresProgressiveSpeedAndEstimatesMaxRepsDuringLiveSquatSet() {
        val engine = BiomechanicsEngine(ExerciseType.SQUAT)
        var ts = 1000L
        var lastFrame = engine.emptyFrame()

        // Perform 7 progressively slowing live 3D squats: eccentric stays ~1.0s, concentric slows from 0.45s -> 1.15s!
        // Also insert a 1.5s top pause between reps to prove pauses DO NOT corrupt concentric velocity!
        val concentricStepsList = listOf(14, 14, 16, 19, 23, 28, 35) // @ 33ms/frame -> 0.46s to 1.15s concentric
        for (concSteps in concentricStepsList) {
            // 1.0s smooth eccentric (30 frames)
            for (i in 0..30) {
                ts += 33L
                val deg = 170f - 82f * (i / 30f)
                val (s, w) = rotateAndProject(buildSquat3D(deg, torsoLeanDeg = 20f), yawDeg = 45f)
                lastFrame = engine.processFrame(s, w, ts)
            }
            // Brief 100ms bottom dwell (3 frames)
            repeat(3) {
                ts += 33L
                val (s, w) = rotateAndProject(buildSquat3D(88f, torsoLeanDeg = 20f), yawDeg = 45f)
                lastFrame = engine.processFrame(s, w, ts)
            }
            // Progressive concentric ascent
            for (i in 1..concSteps) {
                ts += 33L
                val deg = 88f + 82f * (i / concSteps.toFloat())
                val (s, w) = rotateAndProject(buildSquat3D(deg, torsoLeanDeg = 20f), yawDeg = 45f)
                lastFrame = engine.processFrame(s, w, ts)
            }
            // 1.5s standing rest pause at top (45 frames) — MUST NOT pollute concentric velocity!
            repeat(45) {
                ts += 33L
                val (s, w) = rotateAndProject(buildSquat3D(170f, torsoLeanDeg = 12f), yawDeg = 45f)
                lastFrame = engine.processFrame(s, w, ts)
            }
        }

        assertEquals("Must count all 7 squats cleanly", 7, lastFrame.repCount)
        val vbt = lastFrame.velocityTelemetry
        assertEquals("Must record velocity for all 7 reps", 7, vbt.repVelocitiesMps.size)
        assertTrue(
            "First rep velocity (${vbt.repVelocitiesMps.first()}) must be clearly faster than final rep (${vbt.repVelocitiesMps.last()})",
            vbt.repVelocitiesMps.first() > vbt.repVelocitiesMps.last() * 1.35f
        )
        assertTrue("Velocity loss must clearly register progressive fatigue (>= 25%), got ${vbt.velocityLossPct}%", vbt.velocityLossPct >= 25)
        assertNotNull("Must produce live estimated max reps", vbt.estimatedMaxReps)
        assertTrue(
            "Estimated max reps (${vbt.estimatedMaxReps}) must be realistic (>= 7 and <= 15)",
            vbt.estimatedMaxReps in 7..15
        )
        assertTrue("Negative deceleration slope expected, got ${vbt.speedTrendSlopeMpsPerRep}", vbt.speedTrendSlopeMpsPerRep < -0.01f)
    }

    // -------------------------------------------------------------------------
    // 7. REAL-WORLD SETUP / INSTALLATION TIME FALSE-REP PROTECTION
    // -------------------------------------------------------------------------

    @Test
    fun setupInstallationGuard_blocksFalseRepsDuringPhonePlacementGettingDownAndRestPreview() {
        // 7A. Placing phone on floor & standing back up in SQUAT mode (enters frame crouched 82° -> stands 172°) -> 0 fake squats!
        run {
            val engine = BiomechanicsEngine(ExerciseType.SQUAT)
            var ts = 1000L
            // Athlete already crouched placing phone on floor (15 frames) then stands up (15 frames)
            for (i in 0 until 30) {
                ts += 33L
                val deg = if (i < 15) 82f else 82f + 90f * ((i - 15) / 14f)
                val (s, w) = rotateAndProject(buildSquat3D(deg, torsoLeanDeg = 22f), yawDeg = 35f)
                engine.processFrame(s, w, ts)
            }
            assertEquals("Standing up after placing phone on floor must count 0 fake squats", 0, engine.emptyFrame().repCount)

            // Now perform 3 genuine squats from standing -> MUST count all 3 cleanly!
            repeat(3) {
                for (i in 0 until 36) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                    val (s, w) = rotateAndProject(buildSquat3D(172f - p * 86f, torsoLeanDeg = 12f + p * 16f), yawDeg = 35f)
                    engine.processFrame(s, w, ts)
                }
            }
            assertEquals("After setup stand-up, 3 real squats must count cleanly", 3, engine.emptyFrame().repCount)
        }

        // 7B. Getting down from standing -> bent elbows (108°) adjusting hands on floor -> extending into top plank (166°) -> 0 fake push-ups!
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
            var ts = 1000L
            // 10 frames standing upright (out of posture)
            repeat(10) {
                ts += 33L
                val (s, w) = rotateAndProject(buildSquat3D(172f, torsoLeanDeg = 6f), yawDeg = 35f)
                engine.processFrame(s, w, ts)
            }
            // 18 frames kneeling/getting down with bent elbows (108°) while shifting hand width on floor, then locking out to 166°
            for (i in 0 until 26) {
                ts += 33L
                val deg = if (i < 12) 108f else 108f + 58f * ((i - 12) / 13f).coerceIn(0f, 1f)
                val depth = ((166f - deg) / 76f).coerceIn(0f, 1f)
                val handSpread = if (i < 12) 0.75f + (i / 12f) * 0.75f else 1.15f
                val (s, w) = rotateAndProject(
                    buildPushup3D(deg, depthFrac = depth, wristSpreadRatio = handSpread),
                    yawDeg = 35f
                )
                engine.processFrame(s, w, ts)
            }
            assertEquals("Getting down + adjusting hands with bent elbows must count 0 fake push-ups", 0, engine.emptyFrame().repCount)

            // Hold brief top plank (5 frames) then perform 4 clean push-ups -> MUST count all 4!
            repeat(5) {
                ts += 33L
                val (s, w) = rotateAndProject(buildPushup3D(166f, depthFrac = 0f), yawDeg = 35f)
                engine.processFrame(s, w, ts)
            }
            repeat(4) {
                for (i in 0 until 36) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                    val (s, w) = rotateAndProject(buildPushup3D(166f - p * 78f, depthFrac = p), yawDeg = 35f)
                    engine.processFrame(s, w, ts)
                }
            }
            assertEquals("After setup settle, all 4 real push-ups must count cleanly", 4, engine.emptyFrame().repCount)
        }

        // 7C. previewPoseOnly during Setup Countdown / Rest Timer renders live skeleton with ZERO reps or faults!
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
            var ts = 1000L
            // Simulate doing full movement during 5s setup countdown or rest timer
            repeat(3) {
                for (i in 0 until 32) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 31.0))).toFloat()
                    val (s, w) = rotateAndProject(buildPushup3D(166f - p * 80f, depthFrac = p), yawDeg = 35f)
                    val frame = engine.previewPoseOnly(s, w, ts, "Installation (3s)")
                    assertTrue("Preview mode must still detect & smooth skeleton for framing", frame.poseDetected)
                    assertEquals("Preview mode must emit 0 posture fault corrections", 0, frame.corrections.size)
                }
            }
            assertEquals("Setup/Rest preview mode must count 0 reps", 0, engine.emptyFrame().repCount)

            // Arm after countdown ends -> real set starts at 0 and counts 3 real push-ups cleanly
            engine.armAfterSetup()
            repeat(3) {
                for (i in 0 until 36) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                    val (s, w) = rotateAndProject(buildPushup3D(166f - p * 78f, depthFrac = p), yawDeg = 35f)
                    engine.processFrame(s, w, ts)
                }
            }
            assertEquals("Real push-ups immediately after armAfterSetup must count all 3 reps", 3, engine.emptyFrame().repCount)
        }

        // 7D. Getting up from floor after placing phone in BURPEE mode -> 0 fake burpees!
        run {
            val engine = BiomechanicsEngine(ExerciseType.BURPEE)
            var ts = 1000L
            // Start horizontal/prone on floor (12 frames) then stand up (15 frames)
            repeat(12) {
                ts += 33L
                val (s, w) = rotateAndProject(buildPushup3D(165f, depthFrac = 0f), yawDeg = 45f)
                engine.processFrame(s, w, ts)
            }
            repeat(15) {
                ts += 33L
                val (s, w) = rotateAndProject(buildSquat3D(172f, torsoLeanDeg = 6f), yawDeg = 45f)
                engine.processFrame(s, w, ts)
            }
            assertEquals("Standing up from floor in BURPEE mode must count 0 fake burpees", 0, engine.emptyFrame().repCount)
        }
    }

    // -------------------------------------------------------------------------
    // 8. RETROACTIVE AUTO-SWITCH REPLAY (ZERO LOST REP #1) & MONOCULAR PUSH-UP SELF-CALIBRATION
    // -------------------------------------------------------------------------

    @Test
    fun autoSwitchRetroactiveReplay_recoversFirstRepWithoutDroppingCount() {
        // 8A. Full Rep #1 already completed just as AUTO confirms SQUAT -> PUSHUP
        run {
            val engine = BiomechanicsEngine(ExerciseType.SQUAT)
            val history = ArrayList<BufferedPoseFrame>()
            var ts = 1000L

            // Brief plank hold + complete Rep #1 while engine is still on default SQUAT
            repeat(6) {
                ts += 33L
                val (s, w) = rotateAndProject(buildPushup3D(164f, depthFrac = 0f), yawDeg = 35f)
                history.add(BufferedPoseFrame(s, w, ts))
                engine.processFrame(s, w, ts)
            }
            for (i in 0 until 36) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                val (s, w) = rotateAndProject(buildPushup3D(164f - p * 78f, depthFrac = p), yawDeg = 35f)
                history.add(BufferedPoseFrame(s, w, ts))
                engine.processFrame(s, w, ts)
            }
            assertEquals("SQUAT engine must not have counted push-ups as squats", 0, engine.emptyFrame().repCount)

            // AUTO switches to PUSHUP and retroactively replays buffered history -> Rep #1 recovered!
            val replayed = engine.switchExerciseWithReplay(ExerciseType.PUSHUP, history)
            assertNotNull("Replay must return latest frame", replayed)
            assertEquals("Retroactive AUTO replay must immediately recover Rep #1", 1, replayed!!.repCount)

            // Athlete continues with 3 more push-ups -> total MUST be 4/4!
            repeat(3) {
                for (i in 0 until 36) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                    val (s, w) = rotateAndProject(buildPushup3D(164f - p * 78f, depthFrac = p), yawDeg = 35f)
                    engine.processFrame(s, w, ts)
                }
            }
            assertEquals("Rep #1 recovered + 3 live push-ups must equal 4", 4, engine.emptyFrame().repCount)
        }

        // 8B. Mid-rep AUTO switch right at the bottom of Rep #1 (still descending/bottoming when AUTO locks in)
        run {
            val engine = BiomechanicsEngine(ExerciseType.SQUAT)
            val history = ArrayList<BufferedPoseFrame>()
            var ts = 2000L

            repeat(5) {
                ts += 33L
                val (s, w) = rotateAndProject(buildPushup3D(164f, depthFrac = 0f), yawDeg = 35f)
                history.add(BufferedPoseFrame(s, w, ts))
                engine.processFrame(s, w, ts)
            }
            // Descend to bottom of Rep #1 (first half of cosine)
            for (i in 0 until 18) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                val (s, w) = rotateAndProject(buildPushup3D(164f - p * 78f, depthFrac = p), yawDeg = 35f)
                history.add(BufferedPoseFrame(s, w, ts))
                engine.processFrame(s, w, ts)
            }

            // AUTO switches mid-rep right at the bottom -> replay syncs ECCENTRIC/BOTTOM state!
            val midFrame = engine.switchExerciseWithReplay(ExerciseType.PUSHUP, history)
            assertNotNull(midFrame)
            assertEquals("Rep #1 not finished yet at bottom", 0, midFrame!!.repCount)

            // Finish pushing up on Rep #1 (second half of cosine)
            for (i in 18 until 36) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                val (s, w) = rotateAndProject(buildPushup3D(164f - p * 78f, depthFrac = p), yawDeg = 35f)
                engine.processFrame(s, w, ts)
            }
            assertEquals("Finishing Rep #1 after mid-rep AUTO switch must count 1", 1, engine.emptyFrame().repCount)

            // 2 more reps -> total 3/3!
            repeat(2) {
                for (i in 0 until 36) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                    val (s, w) = rotateAndProject(buildPushup3D(164f - p * 78f, depthFrac = p), yawDeg = 35f)
                    engine.processFrame(s, w, ts)
                }
            }
            assertEquals("Mid-rep AUTO switch + 2 more reps must equal 3", 3, engine.emptyFrame().repCount)
        }
    }

    @Test
    fun monocularPushup3DBias_selfCalibratesTopElbowAndCountsEveryRepCleanly() {
        // Real front/oblique floor cameras frequently under-read straight locked push-up arms at ~134°
        // instead of ideal 165° due to monocular 3D foreshortening. Previously, elbowProgress stayed > 0.34f
        // and trapped reps in CONCENTRIC (or blocked requiresNeutralStart).
        val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
        var ts = 1000L

        // Initial plank top with monocular-biased 134° straight arms (10 frames)
        repeat(10) {
            ts += 33L
            val (s, w) = rotateAndProject(buildPushup3D(134f, depthFrac = 0f), yawDeg = 20f)
            engine.processFrame(s, w, ts)
        }

        // Perform 4 continuous smooth push-ups oscillating 134° (top) -> 88° (bottom) -> 134° (top)
        repeat(4) {
            for (i in 0 until 36) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                val elbowDeg = 134f - p * 46f
                val (s, w) = rotateAndProject(buildPushup3D(elbowDeg, depthFrac = p * 0.88f), yawDeg = 20f)
                engine.processFrame(s, w, ts)
            }
        }

        assertEquals(
            "Self-calibrating top-elbow lockout must count all 4 monocular-biased (134° top) push-ups",
            4,
            engine.emptyFrame().repCount
        )
    }

    // -------------------------------------------------------------------------
    // 9. REAL-WORLD PULL-UP LIFECYCLE (GRAB BAR + 3 REPS + DROP & WALK AWAY LOWERING ARMS)
    //    & CROSS-EXERCISE WALKING / GESTURE FALSE-REP IMMUNITY
    // -------------------------------------------------------------------------

    private fun buildPullupOrReach3D(
        wristY: Float,
        shoulderY: Float,
        elbowDeg: Float,
        wristXHalf: Float = 0.28f
    ): List<PosePoint> {
        val pts = blank3D()
        val shW = 0.19f
        val hipY = shoulderY + 0.50f
        val kneeY = hipY + 0.42f
        val ankleY = kneeY + 0.42f

        val lSh = PosePoint(+shW, shoulderY, 0f, 1f)
        val rSh = PosePoint(-shW, shoulderY, 0f, 1f)
        val lWr = PosePoint(+wristXHalf, wristY, -0.02f, 1f)
        val rWr = PosePoint(-wristXHalf, wristY, -0.02f, 1f)

        // Exact triangle placing elbow so angle(shoulder, elbow, wrist) == elbowDeg
        val rad = (elbowDeg.coerceIn(50f, 178f) * 0.5) * PI / 180.0
        val dx = lWr.x - lSh.x
        val dy = lWr.y - lSh.y
        val chord = sqrt((dx * dx + dy * dy).toDouble()).toFloat().coerceAtLeast(0.06f)
        val perpOut = ((chord * 0.5f) / tan(rad).toFloat()).coerceIn(0.01f, 0.30f)

        val lEl = PosePoint((lSh.x + lWr.x) * 0.5f + perpOut, (lSh.y + lWr.y) * 0.5f + 0.04f, -0.04f, 1f)
        val rEl = PosePoint((rSh.x + rWr.x) * 0.5f - perpOut, (rSh.y + rWr.y) * 0.5f + 0.04f, -0.04f, 1f)

        pts[BiomechanicsEngine.NOSE] = PosePoint(0f, shoulderY - 0.16f, -0.06f, 1f)
        pts[BiomechanicsEngine.LEFT_SHOULDER] = lSh
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = rSh
        pts[BiomechanicsEngine.LEFT_ELBOW] = lEl
        pts[BiomechanicsEngine.RIGHT_ELBOW] = rEl
        pts[BiomechanicsEngine.LEFT_WRIST] = lWr
        pts[BiomechanicsEngine.RIGHT_WRIST] = rWr
        pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+0.13f, hipY, 0f, 1f)
        pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-0.13f, hipY, 0f, 1f)
        pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+0.13f, kneeY, 0.02f, 1f)
        pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-0.13f, kneeY, 0.02f, 1f)
        pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+0.13f, ankleY, 0.04f, 1f)
        pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-0.13f, ankleY, 0.04f, 1f)
        return pts
    }

    @Test
    fun pullupRealWorldFullLifecycle_blocksGrabBarAndWalkAwayLoweringArms_countsOnlyRealReps() {
        val engine = BiomechanicsEngine(ExerciseType.PULL_UP)
        var ts = 1000L

        val standShY = -0.35f
        val barY = -0.88f

        // PHASE 1: Athlete stands under bar with arms down, reaches UP bending elbows past face (78°),
        // then straightens arms overhead (166°) to grab the pull-up bar -> MUST count 0 fake reps!
        for (i in 0 until 32) {
            ts += 33L
            val t = i / 31f
            val wrY = (standShY + 0.45f) + (barY - (standShY + 0.45f)) * t
            val elbowDeg = 166f - 88f * sin(PI * t).toFloat() // 166° -> 78° mid-reach -> 166° grabbing bar
            val (s, w) = rotateAndProject(buildPullupOrReach3D(wristY = wrY, shoulderY = standShY, elbowDeg = elbowDeg), yawDeg = 20f)
            engine.processFrame(s, w, ts)
        }
        assertEquals("Reaching up to grab the pull-up bar must NEVER count a fake rep", 0, engine.emptyFrame().repCount)

        // PHASE 2: Settle briefly in dead hang (~0.65s = 20 frames) then perform 3 genuine pull-ups
        // (wrists FIXED at barY, shoulders physically rising UP 0.34 m toward the bar!)
        repeat(20) {
            ts += 33L
            val (s, w) = rotateAndProject(buildPullupOrReach3D(wristY = barY, shoulderY = standShY, elbowDeg = 164f), yawDeg = 20f)
            engine.processFrame(s, w, ts)
        }
        assertEquals("Hanging still before pulling must still be 0", 0, engine.emptyFrame().repCount)

        repeat(3) {
            for (i in 0 until 40) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 39.0))).toFloat()
                val shY = standShY - p * 0.36f // shoulders rise UP toward fixed barY!
                val elbowDeg = 164f - p * 98f  // 164° -> 66° -> 164°
                val (s, w) = rotateAndProject(buildPullupOrReach3D(wristY = barY, shoulderY = shY, elbowDeg = elbowDeg), yawDeg = 20f)
                engine.processFrame(s, w, ts)
            }
        }
        assertEquals("All 3 genuine pull-ups must count cleanly", 3, engine.emptyFrame().repCount)

        // PHASE 3: Athlete drops off bar & lowers arms in place (bending elbows past face 164° -> 76° -> 166° at hips)
        // -> MUST stay at 3 (ZERO phantom rep from lowering arms!)
        for (i in 0 until 32) {
            ts += 33L
            val t = i / 31f
            val wrY = barY + ((standShY + 0.48f) - barY) * t
            val elbowDeg = 164f - 88f * sin(PI * t).toFloat()
            val (s, w) = rotateAndProject(buildPullupOrReach3D(wristY = wrY, shoulderY = standShY, elbowDeg = elbowDeg), yawDeg = 20f)
            engine.processFrame(s, w, ts)
        }
        assertEquals("Lowering arms after dropping off bar must NEVER count a phantom rep", 3, engine.emptyFrame().repCount)

        // PHASE 4: Athlete walks casually laterally across frame while swinging/lowering arms -> STILL 3!
        for (i in 0 until 30) {
            ts += 33L
            val t = i / 29f
            val shiftX = t * 0.25f // casual walk across frame
            val elbowDeg = 162f - 80f * sin(PI * t).toFloat()
            val (rawS, w) = rotateAndProject(buildPullupOrReach3D(wristY = standShY + 0.32f, shoulderY = standShY, elbowDeg = elbowDeg), yawDeg = 20f)
            val walkedS = rawS.map { it.copy(x = it.x + shiftX) }
            engine.processFrame(walkedS, w, ts)
        }
        assertEquals("Walking away after pull-ups must NEVER count a phantom rep", 3, engine.emptyFrame().repCount)
    }

    @Test
    fun antiFalseRepCrossExerciseWalkingAndGestures_blocksPhantomRepsAcrossUpperAndLowerBody() {
        val standShY = -0.35f

        // A. BENT_ROW: Standing completely upright (5° tilt) bending/straightening arms (checking phone) -> 0 fake rows!
        run {
            val engine = BiomechanicsEngine(ExerciseType.BENT_ROW)
            var ts = 1000L
            repeat(2) {
                for (i in 0 until 32) {
                    ts += 33L
                    val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 31.0))).toFloat()
                    val (s, w) = rotateAndProject(
                        buildPullupOrReach3D(wristY = standShY + 0.35f - p * 0.20f, shoulderY = standShY, elbowDeg = 162f - p * 85f),
                        yawDeg = 35f
                    )
                    engine.processFrame(s, w, ts)
                }
            }
            assertEquals("Standing upright bending elbows must count 0 fake Bent Rows", 0, engine.emptyFrame().repCount)
        }

        // B. TRICEP_DIP: Walking casually across frame while bending arms -> 0 fake dips!
        run {
            val engine = BiomechanicsEngine(ExerciseType.TRICEP_DIP)
            var ts = 1000L
            for (i in 0 until 36) {
                ts += 33L
                val t = i / 35f
                val p = (0.5f - 0.5f * cos(2.0 * PI * t)).toFloat()
                val (rawS, w) = rotateAndProject(
                    buildPullupOrReach3D(wristY = standShY + 0.30f - p * 0.14f, shoulderY = standShY, elbowDeg = 164f - p * 78f),
                    yawDeg = 35f
                )
                val walkedS = rawS.map { it.copy(x = it.x + t * 0.24f) }
                engine.processFrame(walkedS, w, ts)
            }
            assertEquals("Walking while bending arms must count 0 fake Tricep Dips", 0, engine.emptyFrame().repCount)
        }

        // C. BICEP_CURL: Reaching arms overhead to grab a pull-up bar -> 0 fake curls!
        run {
            val engine = BiomechanicsEngine(ExerciseType.BICEP_CURL)
            var ts = 1000L
            for (i in 0 until 36) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                val (s, w) = rotateAndProject(
                    buildPullupOrReach3D(wristY = -0.85f, shoulderY = standShY, elbowDeg = 160f - p * 95f),
                    yawDeg = 35f
                )
                engine.processFrame(s, w, ts)
            }
            assertEquals("Bending arms overhead on a bar must count 0 fake Bicep Curls", 0, engine.emptyFrame().repCount)
        }

        // D. OVERHEAD_PRESS: Arms dangling low near hips bending/straightening -> 0 fake OHP!
        run {
            val engine = BiomechanicsEngine(ExerciseType.OVERHEAD_PRESS)
            var ts = 1000L
            for (i in 0 until 36) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i / 35.0))).toFloat()
                val (s, w) = rotateAndProject(
                    buildPullupOrReach3D(wristY = standShY + 0.44f, shoulderY = standShY, elbowDeg = 90f + p * 75f),
                    yawDeg = 25f
                )
                engine.processFrame(s, w, ts)
            }
            assertEquals("Hands dangling near hips must count 0 fake Overhead Presses", 0, engine.emptyFrame().repCount)
        }
    }
}
