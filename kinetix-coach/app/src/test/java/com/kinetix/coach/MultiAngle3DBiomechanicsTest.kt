package com.kinetix.coach

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import com.kinetix.coach.vision.BiomechanicsEngine
import com.kinetix.coach.vision.CameraViewpoint
import com.kinetix.coach.vision.CameraViewpointDetector
import com.kinetix.coach.vision.FormQuality
import com.kinetix.coach.vision.PoseOneEuroSmoother
import com.kinetix.coach.vision.PosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MultiAngle3DBiomechanicsTest {

    /**
     * Rotates a 33-landmark 3D world pose around the vertical Y-axis by [yawDeg]
     * (0° = front facing camera, 45° = 3/4, 90° = side profile, 135° = rear 3/4, 180° = back)
     * and generates matching normalized 2D screen projections.
     */
    private fun rotateAndProject3D(
        baseWorld: List<PosePoint>,
        yawDeg: Float,
        cameraDistMeters: Float = 2.6f
    ): Pair<List<PosePoint>, List<PosePoint>> {
        val rad = yawDeg * PI / 180.0
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        // Rotate world points around hip origin (0, 0, 0)
        val rotatedWorld = baseWorld.map { p ->
            val rx = p.x * c + p.z * s
            val rz = -p.x * s + p.z * c
            PosePoint(x = rx, y = p.y, z = rz, visibility = p.visibility)
        }

        // Perspective project onto normalized [0..1] screen coordinates
        val focal = 1.05f
        val screen = rotatedWorld.map { wp ->
            val depth = (cameraDistMeters + wp.z).coerceAtLeast(0.6f)
            val sx = 0.50f + (wp.x * focal / depth)
            val sy = 0.54f + (wp.y * focal / depth)
            PosePoint(x = sx, y = sy, z = wp.z, visibility = wp.visibility)
        }
        return screen to rotatedWorld
    }

    /**
     * Builds a physically accurate 3D squat skeleton (in meters, +Y down, -Z forward toward camera
     * at yaw = 0°) with exact [leftKneeDeg], [rightKneeDeg], [torsoLeanDeg], and optional medial [valgusMeters].
     */
    private fun buildBase3DSquat(
        leftKneeDeg: Float,
        rightKneeDeg: Float = leftKneeDeg,
        torsoLeanDeg: Float = 25f,
        valgusMeters: Float = 0f
    ): List<PosePoint> {
        val pts = MutableList(33) { PosePoint(0f, 0f, 0f, 1f) }
        val hipHalfW = 0.13f
        val shHalfW = 0.19f
        val thighLen = 0.42f
        val shinLen = 0.42f
        val torsoLen = 0.50f

        fun placeLeg(hipX: Float, kneeDeg: Float, isLeft: Boolean): Triple<PosePoint, PosePoint, PosePoint> {
            // Ankle is on floor; shin tilts slightly forward (-Z) by 12° when bending deeply
            val bendFrac = ((175f - kneeDeg) / 90f).coerceIn(0f, 1f)
            val shinTiltRad = (10f * bendFrac) * PI / 180.0
            val kneeRadFromShin = kneeDeg * PI / 180.0

            // Build leg relative to hip at (hipX, 0, 0):
            // Vector knee->ankle points downward (+Y) and slightly back (+Z)
            val shinDirY = cos(shinTiltRad).toFloat()
            val shinDirZ = sin(shinTiltRad).toFloat()

            // Vector knee->hip rotated by kneeDeg from knee->ankle in sagittal YZ plane
            // So dot(u_hip, u_ankle) = cos(kneeDeg) exactly!
            val hipDirY = (shinDirY * cos(kneeRadFromShin) - shinDirZ * sin(kneeRadFromShin)).toFloat()
            val hipDirZ = (shinDirY * sin(kneeRadFromShin) + shinDirZ * cos(kneeRadFromShin)).toFloat()

            // Hip is at (hipX, 0, 0), so knee = hip - thighLen * hipDir
            val medialSign = if (isLeft) -1f else 1f // L_HIP is +X, R_HIP is -X -> medial is toward 0
            val knee = PosePoint(
                x = hipX + medialSign * valgusMeters,
                y = -thighLen * hipDirY,
                z = -thighLen * hipDirZ,
                visibility = 1f
            )
            val ankle = PosePoint(
                x = hipX,
                y = knee.y + shinLen * shinDirY,
                z = knee.z + shinLen * shinDirZ,
                visibility = 1f
            )
            val hip = PosePoint(hipX, 0f, 0f, 1f)
            return Triple(hip, knee, ankle)
        }

        // Facing camera (-Z): anatomical LEFT is +X, anatomical RIGHT is -X
        val (lHip, lKnee, lAnkle) = placeLeg(+hipHalfW, leftKneeDeg, isLeft = true)
        val (rHip, rKnee, rAnkle) = placeLeg(-hipHalfW, rightKneeDeg, isLeft = false)

        val leanRad = torsoLeanDeg * PI / 180.0
        val shY = -(torsoLen * cos(leanRad)).toFloat()
        val shZ = -(torsoLen * sin(leanRad)).toFloat() // forward lean goes toward -Z

        val lSh = PosePoint(+shHalfW, shY, shZ, 1f)
        val rSh = PosePoint(-shHalfW, shY, shZ, 1f)
        val nose = PosePoint(0f, shY - 0.16f, shZ - 0.14f, 1f)
        val lEar = PosePoint(+0.08f, shY - 0.15f, shZ - 0.02f, 1f)
        val rEar = PosePoint(-0.08f, shY - 0.15f, shZ - 0.02f, 1f)

        val lEl = PosePoint(+shHalfW + 0.03f, shY + 0.24f, shZ - 0.04f, 1f)
        val rEl = PosePoint(-shHalfW - 0.03f, shY + 0.24f, shZ - 0.04f, 1f)
        val lWr = PosePoint(+shHalfW + 0.02f, shY + 0.44f, shZ - 0.12f, 1f)
        val rWr = PosePoint(-shHalfW - 0.02f, shY + 0.44f, shZ - 0.12f, 1f)

        pts[BiomechanicsEngine.NOSE] = nose
        pts[BiomechanicsEngine.LEFT_EAR] = lEar
        pts[BiomechanicsEngine.RIGHT_EAR] = rEar
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
        // Heels and toes for foot forward-direction vector
        pts[29] = lAnkle.copy(y = lAnkle.y + 0.05f, z = lAnkle.z + 0.04f)
        pts[30] = rAnkle.copy(y = rAnkle.y + 0.05f, z = rAnkle.z + 0.04f)
        pts[31] = lAnkle.copy(y = lAnkle.y + 0.05f, z = lAnkle.z - 0.16f)
        pts[32] = rAnkle.copy(y = rAnkle.y + 0.05f, z = rAnkle.z - 0.16f)
        return pts
    }

    /** Builds a 3D horizontal plank skeleton (head toward -Z, ankles toward +Z) with exact hip sag/pike offset. */
    private fun buildBase3DPlank(hipDropMeters: Float): List<PosePoint> {
        val pts = MutableList(33) { PosePoint(0f, 0f, 0f, 1f) }
        val shHalfW = 0.19f
        val hipHalfW = 0.13f

        // Body extends horizontally along Z axis; floor is around y = +0.35m
        val shY = 0.0f
        val shZ = -0.52f
        val anY = 0.10f
        val anZ = +0.78f
        val tHip = 0.42f
        val lineHipY = shY + tHip * (anY - shY)
        val hipY = lineHipY + hipDropMeters
        val hipZ = shZ + tHip * (anZ - shZ)

        pts[BiomechanicsEngine.NOSE] = PosePoint(0f, shY - 0.04f, shZ - 0.18f, 1f)
        pts[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(+shHalfW, shY, shZ, 1f)
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(-shHalfW, shY, shZ, 1f)
        pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(+shHalfW, shY + 0.28f, shZ, 1f)
        pts[BiomechanicsEngine.RIGHT_ELBOW] = PosePoint(-shHalfW, shY + 0.28f, shZ, 1f)
        pts[BiomechanicsEngine.LEFT_WRIST] = PosePoint(+shHalfW, shY + 0.30f, shZ - 0.22f, 1f)
        pts[BiomechanicsEngine.RIGHT_WRIST] = PosePoint(-shHalfW, shY + 0.30f, shZ - 0.22f, 1f)
        pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+hipHalfW, hipY, hipZ, 1f)
        pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-hipHalfW, hipY, hipZ, 1f)
        pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+hipHalfW, (hipY + anY) * 0.5f, (hipZ + anZ) * 0.5f, 1f)
        pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-hipHalfW, (hipY + anY) * 0.5f, (hipZ + anZ) * 0.5f, 1f)
        pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+hipHalfW, anY, anZ, 1f)
        pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-hipHalfW, anY, anZ, 1f)
        return pts
    }

    @Test
    fun multiAngle3DSquat_invariantAcrossAllFiveCameraAnglesAndCountsThreeReps() {
        val anglesToTest = listOf(
            0f to CameraViewpoint.FRONT,
            45f to CameraViewpoint.OBLIQUE_LEFT,
            90f to CameraViewpoint.PROFILE_LEFT,
            135f to CameraViewpoint.OBLIQUE_LEFT,
            180f to CameraViewpoint.BACK
        )

        // Smooth 30fps squat trajectory going 172° -> 90° -> 172°
        val repTrajectory = buildList {
            for (i in 0..12) add(172f - (82f * i / 12f))
            repeat(3) { add(90f) }
            for (i in 1..12) add(90f + (82f * i / 12f))
            repeat(3) { add(172f) }
        }

        for ((yawDeg, expectedViewpoint) in anglesToTest) {
            // 1. Verify exact 90° static measurement & viewpoint classification
            val (screen90, world90) = rotateAndProject3D(
                buildBase3DSquat(leftKneeDeg = 90f, torsoLeanDeg = 25f),
                yawDeg = yawDeg
            )
            val vp = CameraViewpointDetector.detect(screen90, world90)
            assertEquals("Viewpoint mismatch at yaw=$yawDeg°", expectedViewpoint, vp.viewpoint)
            assertTrue("Expected 3D active at yaw=$yawDeg°", vp.is3DActive)
            assertEquals("Azimuth mismatch at yaw=$yawDeg°", yawDeg.toInt(), vp.azimuthDeg, 4)

            val staticEngine = BiomechanicsEngine(ExerciseType.SQUAT, emaAlpha = 1.0f)
            val staticFrame = staticEngine.processFrame(screen90, world90, 1000L)
            val kneeBadge = staticFrame.angleBadges.first {
                it.landmarkIndex == BiomechanicsEngine.LEFT_KNEE || it.landmarkIndex == BiomechanicsEngine.RIGHT_KNEE
            }
            assertEquals(
                "3D knee angle must be 90°±2° at yaw=$yawDeg°, got ${kneeBadge.degrees}°",
                90f,
                kneeBadge.degrees.toFloat(),
                2.0f
            )
            val torsoTilt3D = BiomechanicsEngine.verticalTilt3D(
                BiomechanicsEngine.midpoint(world90[BiomechanicsEngine.LEFT_SHOULDER], world90[BiomechanicsEngine.RIGHT_SHOULDER]),
                BiomechanicsEngine.midpoint(world90[BiomechanicsEngine.LEFT_HIP], world90[BiomechanicsEngine.RIGHT_HIP])
            )
            assertEquals("3D torso tilt must be 25°±1° at yaw=$yawDeg°", 25f, torsoTilt3D, 1.0f)

            // 2. Verify full 3-rep cycle with real One-Euro filter enabled at 30fps!
            val liveEngine = BiomechanicsEngine(ExerciseType.SQUAT) // default One-Euro filter active
            var ts = 1000L
            var lastFrame = liveEngine.emptyFrame()
            repeat(3) {
                for (deg in repTrajectory) {
                    ts += 33L
                    val (scr, wld) = rotateAndProject3D(
                        buildBase3DSquat(leftKneeDeg = deg, torsoLeanDeg = 25f),
                        yawDeg = yawDeg
                    )
                    lastFrame = liveEngine.processFrame(scr, wld, ts)
                }
            }
            assertEquals("Expected 3 reps counted at yaw=$yawDeg°", 3, lastFrame.repCount)
            assertTrue("Expected high form score at yaw=$yawDeg°, got ${lastFrame.avgFormScore}", lastFrame.avgFormScore >= 88)
        }
    }

    @Test
    fun angleCorrectionPrecision_shallowSquatLeanSagPlankValgusAndSymmetry() {
        // 1. Shallow squat at 118° -> delta ~23°, downward arrow (arrowDy > 0)
        val squatEngine = BiomechanicsEngine(ExerciseType.SQUAT, emaAlpha = 1.0f)
        val (scrShallow, wldShallow) = rotateAndProject3D(buildBase3DSquat(118f, torsoLeanDeg = 22f), yawDeg = 45f)
        val shallowFrame = squatEngine.processFrame(scrShallow, wldShallow, 1000L)
        val depthCorr = shallowFrame.corrections.firstOrNull { it.jointLabelFr.equals("Genou", ignoreCase = true) }
        assertNotNull("Expected precision depth correction at 118° squat", depthCorr)
        assertEquals(23, depthCorr!!.deltaDeg)
        assertEquals(95, depthCorr.targetMaxDeg)
        assertTrue("Expected downward arrow (arrowDy > 0.7), got ${depthCorr.arrowDy}", depthCorr.arrowDy > 0.7f)

        // 2. Excessive torso lean at 55° -> upright correction (arrowDy < 0)
        val leanEngine = BiomechanicsEngine(ExerciseType.SQUAT, emaAlpha = 1.0f)
        val (scrLean, wldLean) = rotateAndProject3D(buildBase3DSquat(92f, torsoLeanDeg = 55f), yawDeg = 45f)
        val leanFrame = leanEngine.processFrame(scrLean, wldLean, 1000L)
        val leanCorr = leanFrame.corrections.firstOrNull { it.jointLabelFr.equals("Buste", ignoreCase = true) }
        assertNotNull("Expected torso lean correction at 55°", leanCorr)
        assertTrue("Expected delta ~11°, got ${leanCorr!!.deltaDeg}", leanCorr.deltaDeg in 9..13)
        assertTrue("Expected upward arrow (arrowDy < -0.4), got ${leanCorr.arrowDy}", leanCorr.arrowDy < -0.4f)
        assertTrue("Expected valid ghost torso target", leanCorr.ghostTargetX.isFinite() && leanCorr.ghostTargetY.isFinite())

        // 3. Sagging plank (~148°) from BOTH 3/4 (45°) and Profile (90°) -> upward hip correction (arrowDy < 0)
        for (yaw in listOf(45f, 90f)) {
            val plankEngine = BiomechanicsEngine(ExerciseType.PLANK, emaAlpha = 1.0f)
            // Compute exact hip drop producing ~148° hip alignment
            val (scrSag, wldSag) = rotateAndProject3D(buildBase3DPlank(hipDropMeters = 0.18f), yawDeg = yaw)
            val sagFrame = plankEngine.processFrame(scrSag, wldSag, 1000L)
            assertEquals("Sagging plank must pause timer at yaw=$yaw°", MovementPhase.PAUSE, sagFrame.phase)
            val hipCorr = sagFrame.corrections.firstOrNull { it.jointLabelFr.equals("Hanches", ignoreCase = true) }
            assertNotNull("Expected hip-up correction on sagging plank at yaw=$yaw°", hipCorr)
            assertEquals(FormQuality.FAULT, hipCorr!!.severity)
            assertTrue("Expected upward hip arrow (arrowDy < -0.7) at yaw=$yaw°", hipCorr.arrowDy < -0.7f)
            assertTrue("Expected alignment < 160°, got ${hipCorr.currentDeg}°", hipCorr.currentDeg < 160)
        }

        // 4. 3D Knee Valgus at Front (0°) AND 3/4 (45°) -> outward knee arrow (|arrowDx| > 0.7)
        for (yaw in listOf(0f, 45f)) {
            val valgusEngine = BiomechanicsEngine(ExerciseType.SQUAT, emaAlpha = 1.0f)
            val (scrValgus, wldValgus) = rotateAndProject3D(
                buildBase3DSquat(leftKneeDeg = 95f, torsoLeanDeg = 20f, valgusMeters = 0.095f),
                yawDeg = yaw
            )
            val valgusFrame = valgusEngine.processFrame(scrValgus, wldValgus, 1000L)
            val valgusCorr = valgusFrame.corrections.firstOrNull { it.shortBadgeFr.contains("VALGUS") }
            assertNotNull("Expected 3D valgus correction at yaw=$yaw°", valgusCorr)
            assertEquals(FormQuality.FAULT, valgusCorr!!.severity)
            assertTrue("Expected outward lateral arrow (|arrowDx| > 0.7) at yaw=$yaw°", abs(valgusCorr.arrowDx) > 0.7f)
        }

        // 5. Bilateral asymmetry (left 90° vs right 116° in front view) -> symmetry correction
        val symEngine = BiomechanicsEngine(ExerciseType.SQUAT, emaAlpha = 1.0f)
        val (scrSym, wldSym) = rotateAndProject3D(
            buildBase3DSquat(leftKneeDeg = 90f, rightKneeDeg = 116f, torsoLeanDeg = 20f),
            yawDeg = 0f
        )
        val symFrame = symEngine.processFrame(scrSym, wldSym, 1000L)
        assertTrue(
            "Expected bilateral symmetry correction, got ${symFrame.corrections.map { it.shortBadgeFr }}",
            symFrame.corrections.any { it.shortBadgeFr.contains("SYMÉTRIE") }
        )
    }

    @Test
    fun oneEuroFilter_suppressesStaticAngularJitterWhileTrackingFastMovement() {
        val smoother = PoseOneEuroSmoother(minCutoff = 1.25f, beta = 0.08f, dCutoff = 1.0f)
        val base = buildBase3DSquat(95f, torsoLeanDeg = 20f)
        val rawAngles = mutableListOf<Float>()
        val filteredAngles = mutableListOf<Float>()

        var ts = 1000L
        for (frameIdx in 0 until 45) {
            ts += 33L
            val jitter = if (frameIdx % 2 == 0) 0.012f else -0.012f
            val noisy = base.mapIndexed { idx, p ->
                if (idx == BiomechanicsEngine.LEFT_KNEE) p.copy(z = p.z + jitter, y = p.y - jitter * 0.5f) else p
            }
            val smoothed = smoother.smooth(noisy, ts)
            if (frameIdx >= 15) {
                rawAngles.add(
                    BiomechanicsEngine.angleDegrees3D(
                        noisy[BiomechanicsEngine.LEFT_HIP],
                        noisy[BiomechanicsEngine.LEFT_KNEE],
                        noisy[BiomechanicsEngine.LEFT_ANKLE]
                    )
                )
                filteredAngles.add(
                    BiomechanicsEngine.angleDegrees3D(
                        smoothed[BiomechanicsEngine.LEFT_HIP],
                        smoothed[BiomechanicsEngine.LEFT_KNEE],
                        smoothed[BiomechanicsEngine.LEFT_ANKLE]
                    )
                )
            }
        }

        val rawSpread = rawAngles.max() - rawAngles.min()
        val filteredSpread = filteredAngles.max() - filteredAngles.min()
        assertTrue("Raw jitter should exceed 2°, was $rawSpread°", rawSpread > 2.0f)
        assertTrue("One-Euro must slash static jitter (< 0.8° peak-to-peak), got $filteredSpread°", filteredSpread < 0.8f)

        // Verify zero-lag responsiveness during rapid lift (100° -> 165° in 0.4s)
        var lastAngle = 0f
        for (step in 1..12) {
            ts += 33L
            val targetDeg = 95f + (70f * step / 12f)
            val rapid = buildBase3DSquat(targetDeg, torsoLeanDeg = 20f)
            val sm = smoother.smooth(rapid, ts)
            lastAngle = BiomechanicsEngine.angleDegrees3D(
                sm[BiomechanicsEngine.LEFT_HIP],
                sm[BiomechanicsEngine.LEFT_KNEE],
                sm[BiomechanicsEngine.LEFT_ANKLE]
            )
        }
        assertTrue("One-Euro should track explosive extension closely (>150°), got $lastAngle°", lastAngle > 150f)
    }

    @Test
    fun framingDetector_flagsClippedAnklesAndClearsWhenCentered() {
        val (wellFramed, _) = rotateAndProject3D(buildBase3DSquat(165f, torsoLeanDeg = 5f), yawDeg = 0f)
        val ok = CameraViewpointDetector.analyzeFraming(wellFramed)
        assertTrue("Expected well-framed standing pose", ok.isWellFramed)
        assertNull(ok.warningFr)

        val clippedAnkles = wellFramed.toMutableList()
        clippedAnkles[BiomechanicsEngine.LEFT_ANKLE] = clippedAnkles[BiomechanicsEngine.LEFT_ANKLE].copy(y = 0.99f)
        clippedAnkles[BiomechanicsEngine.RIGHT_ANKLE] = clippedAnkles[BiomechanicsEngine.RIGHT_ANKLE].copy(y = 0.99f)
        val warn = CameraViewpointDetector.analyzeFraming(clippedAnkles)
        assertTrue(!warn.isWellFramed)
        assertTrue(warn.warningFr?.contains("chevilles", ignoreCase = true) == true)
    }

    private fun assertEquals(message: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$message: expected $expected ± $tolerance, got $actual", abs(expected - actual) <= tolerance)
    }
}
