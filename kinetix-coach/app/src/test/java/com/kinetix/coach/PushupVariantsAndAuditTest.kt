package com.kinetix.coach

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import com.kinetix.coach.vision.AutoExerciseDetector
import com.kinetix.coach.vision.BiomechanicsEngine
import com.kinetix.coach.vision.DetectionStrictness
import com.kinetix.coach.vision.PosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PushupVariantsAndAuditTest {

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

    /**
     * Builds an exact 3D push-up skeleton with controllable left/right elbow angles,
     * wrist spread multiplier (relative to shoulder width), vertical chest drop, knee-pushup mode, or pike mode.
     */
    private fun buildPushup3D(
        leftElbowDeg: Float,
        rightElbowDeg: Float = leftElbowDeg,
        wristSpreadRatio: Float = 1.15f,
        depthFrac: Float = ((165f - minOf(leftElbowDeg, rightElbowDeg)) / 75f).coerceIn(0f, 1f),
        kneePushup: Boolean = false,
        pikePushup: Boolean = false
    ): List<PosePoint> {
        val pts = blank3D()
        val shHalfW = 0.19f
        val hipHalfW = 0.13f
        val floorY = 0.44f
        val shZ = -0.50f
        val shY = if (pikePushup) {
            0.08f + depthFrac * 0.24f
        } else {
            -0.04f + depthFrac * 0.28f
        }

        val hipZ: Float
        val hipY: Float
        val kneeZ: Float
        val kneeY: Float
        val ankleZ: Float
        val ankleY: Float

        when {
            pikePushup -> {
                // High inverted-V hips (hipAngle ~ 95°, bodyLineAlign ~ 250°)
                hipZ = 0.05f
                hipY = -0.40f
                kneeZ = 0.42f
                kneeY = 0.02f
                ankleZ = 0.72f
                ankleY = floorY
            }
            kneePushup -> {
                // Straight shoulder -> hip -> knee line, with shins angled UP (ankles lifted off ground)
                kneeZ = 0.48f
                kneeY = floorY
                hipZ = shZ + 0.52f * (kneeZ - shZ)
                hipY = shY + 0.52f * (kneeY - shY)
                ankleZ = kneeZ + 0.22f
                ankleY = kneeY - 0.28f // ankles clearly lifted above knees -> knee bend ~90°
            }
            else -> {
                // Straight plank body line shoulder -> hip -> knee -> ankle
                ankleZ = 0.78f
                ankleY = floorY - 0.03f
                hipZ = shZ + 0.44f * (ankleZ - shZ)
                hipY = shY + 0.44f * (ankleY - shY)
                kneeZ = shZ + 0.72f * (ankleZ - shZ)
                kneeY = shY + 0.72f * (ankleY - shY)
            }
        }

        val wristHalfX = shHalfW * wristSpreadRatio
        val lSh = PosePoint(+shHalfW, shY, shZ, 1f)
        val rSh = PosePoint(-shHalfW, shY, shZ, 1f)
        val lWr = PosePoint(+wristHalfX, floorY, shZ - 0.02f, 1f)
        val rWr = PosePoint(-wristHalfX, floorY, shZ - 0.02f, 1f)

        // Exact 3D elbow placement so angleDegrees3D(shoulder, elbow, wrist) == targetDeg!
        fun solveElbow(sh: PosePoint, wr: PosePoint, targetDeg: Float, signX: Float): PosePoint {
            val upperLen = 0.30f
            val dx = wr.x - sh.x
            val dy = wr.y - sh.y
            val dz = wr.z - sh.z
            val chord = kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat().coerceAtLeast(0.05f)
            val ux = dx / chord
            val uy = dy / chord
            val uz = dz / chord

            // Use an isosceles triangle with vertex angle = targetDeg
            val halfRad = (targetDeg.coerceIn(45f, 178f) * 0.5) * PI / 180.0
            val midX = (sh.x + wr.x) * 0.5f
            val midY = (sh.y + wr.y) * 0.5f
            val midZ = (sh.z + wr.z) * 0.5f
            val outDist = ((chord * 0.5f) / kotlin.math.tan(halfRad).toFloat()).coerceIn(0.005f, upperLen)

            // Outward-and-back perpendicular vector in XZ/Y plane
            var px = signX * 0.65f
            var py = 0f
            var pz = 0.76f
            val dot = px * ux + py * uy + pz * uz
            px -= dot * ux
            py -= dot * uy
            pz -= dot * uz
            val pLen = kotlin.math.sqrt((px * px + py * py + pz * pz).toDouble()).toFloat().coerceAtLeast(1e-4f)
            return PosePoint(
                x = midX + (px / pLen) * outDist,
                y = midY + (py / pLen) * outDist,
                z = midZ + (pz / pLen) * outDist,
                visibility = 1f
            )
        }

        val lEl = solveElbow(lSh, lWr, leftElbowDeg, +1f)
        val rEl = solveElbow(rSh, rWr, rightElbowDeg, -1f)

        pts[BiomechanicsEngine.NOSE] = PosePoint(0f, shY - 0.05f, shZ - 0.18f, 1f)
        pts[BiomechanicsEngine.LEFT_SHOULDER] = lSh
        pts[BiomechanicsEngine.RIGHT_SHOULDER] = rSh
        pts[BiomechanicsEngine.LEFT_ELBOW] = lEl
        pts[BiomechanicsEngine.RIGHT_ELBOW] = rEl
        pts[BiomechanicsEngine.LEFT_WRIST] = lWr
        pts[BiomechanicsEngine.RIGHT_WRIST] = rWr
        pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+hipHalfW, hipY, hipZ, 1f)
        pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-hipHalfW, hipY, hipZ, 1f)
        pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+hipHalfW, kneeY, kneeZ, 1f)
        pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-hipHalfW, kneeY, kneeZ, 1f)
        pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+hipHalfW, ankleY, ankleZ, 1f)
        pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-hipHalfW, ankleY, ankleZ, 1f)
        return pts
    }

    /** Synthesizes a 2D pose with an exact target angle at the primary joint. */
    private fun makeAngle2D(
        aIdx: Int,
        bIdx: Int,
        cIdx: Int,
        angleDeg: Float,
        bPos: PosePoint = PosePoint(0.50f, 0.50f),
        armA: Pair<Float, Float> = 0f to -0.22f,
        lenC: Float = 0.22f
    ): MutableList<PosePoint> {
        val pts = MutableList(33) { PosePoint(0.50f, 0.50f, 0f, 1f) }
        val a = PosePoint(bPos.x + armA.first, bPos.y + armA.second, 0f, 1f)
        val baseAngle = kotlin.math.atan2(armA.second.toDouble(), armA.first.toDouble())
        val rad = baseAngle + angleDeg * PI / 180.0
        val c = PosePoint(
            x = bPos.x + (lenC * cos(rad)).toFloat(),
            y = bPos.y + (lenC * sin(rad)).toFloat(),
            z = 0f,
            visibility = 1f
        )
        pts[aIdx] = a
        pts[bIdx] = bPos
        pts[cIdx] = c
        return pts
    }

    @Test
    fun pushup_allSixVariantsCountAccuratelyAndClassifyCorrectly() {
        // 1. CLASSIQUE (Standard)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP, emaAlpha = 1.0f)
            var ts = 1000L
            var variant: String? = null
            repeat(3) {
                for (deg in listOf(165f, 138f, 108f, 94f, 132f, 165f)) {
                    ts += 100L
                    val (scr, wld) = rotateAndProject(buildPushup3D(deg, wristSpreadRatio = 1.15f), yawDeg = 35f)
                    val f = engine.processFrame(scr, wld, ts)
                    if (deg < 110f) variant = f.detectedVariantFr
                }
            }
            val last = engine.processFrame(rotateAndProject(buildPushup3D(165f)).first, rotateAndProject(buildPushup3D(165f)).second, ts + 100L)
            assertEquals("Standard push-ups should count 3 reps", 3, last.repCount)
            assertEquals("Classique", variant)
        }

        // 2. LARGE (Wide: wrists > 1.55x shoulder width, elbows bottom at ~115° + chest drop, NO flare fault!)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP, emaAlpha = 1.0f)
            var ts = 1000L
            var bottomVariant: String? = null
            var hasFlareFault = false
            repeat(3) {
                for ((deg, dFrac) in listOf(165f to 0f, 140f to 0.35f, 115f to 0.85f, 138f to 0.35f, 165f to 0f)) {
                    ts += 100L
                    val (scr, wld) = rotateAndProject(
                        buildPushup3D(deg, wristSpreadRatio = 1.85f, depthFrac = dFrac),
                        yawDeg = 20f
                    )
                    val f = engine.processFrame(scr, wld, ts)
                    if (deg <= 116f) {
                        bottomVariant = f.detectedVariantFr
                        if (f.corrections.any { it.jointLabelFr.equals("Coudes", ignoreCase = true) }) {
                            hasFlareFault = true
                        }
                    }
                }
            }
            val (scrTop, wldTop) = rotateAndProject(buildPushup3D(165f, wristSpreadRatio = 1.85f, depthFrac = 0f), yawDeg = 20f)
            val last = engine.processFrame(scrTop, wldTop, ts + 100L)
            assertEquals("Wide push-ups with 115° elbows must count 3 reps", 3, last.repCount)
            assertEquals("Large", bottomVariant)
            assertFalse("Wide push-ups must NOT flag elbow flare fault", hasFlareFault)
        }

        // 3. DIAMANT (Diamond: wrists < 0.55x shoulder width)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP, emaAlpha = 1.0f)
            var ts = 1000L
            var variant: String? = null
            repeat(2) {
                for (deg in listOf(164f, 135f, 102f, 136f, 164f)) {
                    ts += 100L
                    val (scr, wld) = rotateAndProject(buildPushup3D(deg, wristSpreadRatio = 0.30f), yawDeg = 20f)
                    val f = engine.processFrame(scr, wld, ts)
                    if (deg <= 105f) variant = f.detectedVariantFr
                }
            }
            val (scrTop, wldTop) = rotateAndProject(buildPushup3D(164f, wristSpreadRatio = 0.30f), yawDeg = 20f)
            val last = engine.processFrame(scrTop, wldTop, ts + 100L)
            assertEquals("Diamond push-ups must count 2 reps", 2, last.repCount)
            assertEquals("Diamant", variant)
        }

        // 4. GENOUX (Knee push-ups: bent knees & lifted ankles, alignment on shoulder-hip-knee -> NO hip fault!)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP, emaAlpha = 1.0f)
            var ts = 1000L
            var variant: String? = null
            var hipFault = false
            repeat(2) {
                for (deg in listOf(165f, 136f, 104f, 136f, 165f)) {
                    ts += 100L
                    val (scr, wld) = rotateAndProject(buildPushup3D(deg, kneePushup = true), yawDeg = 45f)
                    val f = engine.processFrame(scr, wld, ts)
                    if (deg <= 106f) {
                        variant = f.detectedVariantFr
                        if (f.corrections.any { it.jointLabelFr.equals("Hanches", ignoreCase = true) }) {
                            hipFault = true
                        }
                    }
                }
            }
            val (scrTop, wldTop) = rotateAndProject(buildPushup3D(165f, kneePushup = true), yawDeg = 45f)
            val last = engine.processFrame(scrTop, wldTop, ts + 100L)
            assertEquals("Knee push-ups must count 2 reps", 2, last.repCount)
            assertEquals("Genoux", variant)
            assertFalse("Knee push-ups with straight shoulder-hip-knee must NOT emit hip fault", hipFault)
        }

        // 5. PIQUÉE (Pike push-ups: inverted V high hips -> NO "hanches trop hautes" error!)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP, emaAlpha = 1.0f)
            var ts = 1000L
            var variant: String? = null
            var hipFault = false
            repeat(2) {
                for (deg in listOf(165f, 134f, 100f, 134f, 165f)) {
                    ts += 100L
                    val (scr, wld) = rotateAndProject(buildPushup3D(deg, pikePushup = true), yawDeg = 45f)
                    val f = engine.processFrame(scr, wld, ts)
                    if (deg <= 105f) {
                        variant = f.detectedVariantFr
                        if (f.corrections.any { it.jointLabelFr.equals("Hanches", ignoreCase = true) }) {
                            hipFault = true
                        }
                    }
                }
            }
            val (scrTop, wldTop) = rotateAndProject(buildPushup3D(165f, pikePushup = true), yawDeg = 45f)
            val last = engine.processFrame(scrTop, wldTop, ts + 100L)
            assertEquals("Pike push-ups must count 2 reps", 2, last.repCount)
            assertEquals("Piquée", variant)
            assertFalse("Pike push-ups must NOT flag elevated hips as an error", hipFault)
        }

        // 6. ARCHER (1 arm bent deeply <115°, 1 arm straight >145° -> NO asymmetry penalty!)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PUSHUP, emaAlpha = 1.0f)
            var ts = 1000L
            var variant: String? = null
            var symFault = false
            repeat(2) {
                val pairs = listOf(165f to 165f, 132f to 164f, 96f to 162f, 132f to 164f, 165f to 165f)
                for ((lDeg, rDeg) in pairs) {
                    ts += 100L
                    val (scr, wld) = rotateAndProject(
                        buildPushup3D(leftElbowDeg = lDeg, rightElbowDeg = rDeg, wristSpreadRatio = 1.40f),
                        yawDeg = 10f
                    )
                    val f = engine.processFrame(scr, wld, ts)
                    if (lDeg < 110f) {
                        variant = f.detectedVariantFr
                        if (f.corrections.any { it.shortBadgeFr.contains("SYMÉTRIE") }) {
                            symFault = true
                        }
                    }
                }
            }
            val (scrTop, wldTop) = rotateAndProject(buildPushup3D(165f, 165f, wristSpreadRatio = 1.40f), yawDeg = 10f)
            val last = engine.processFrame(scrTop, wldTop, ts + 100L)
            assertEquals("Archer push-ups must count 2 reps", 2, last.repCount)
            assertEquals("Archer", variant)
            assertFalse("Archer push-ups must NOT flag asymmetry fault", symFault)
        }
    }

    @Test
    fun autoExerciseDetector_classifiesWidePushupsAsPushupNotPlank() {
        val detector = AutoExerciseDetector()
        var ts = 1000L
        var lastDetected: ExerciseType? = null
        for (i in 0 until 66) {
            ts += 33L
            val phase = (0.5f - 0.5f * cos(2.0 * PI * (i / 28.0))).toFloat()
            val elbowDeg = 162f - phase * 46f // bottoms around 116° (classic wide push-up!)
            val world = buildPushup3D(
                leftElbowDeg = elbowDeg,
                rightElbowDeg = elbowDeg,
                wristSpreadRatio = 1.80f,
                depthFrac = phase
            )
            val (scr, rotW) = rotateAndProject(world, yawDeg = 35f)
            lastDetected = detector.processFrame(scr, rotW, ts).detectedExercise
        }
        assertEquals("Wide push-ups must auto-detect as PUSHUP (not PLANK)", ExerciseType.PUSHUP, lastDetected)
    }

    @Test
    fun auditedExercises_realWorldEdgeCasesCountCleanlyInAdaptiveMode() {
        // 1. SQUAT parallel 104° + natural 152° top
        run {
            val engine = BiomechanicsEngine(ExerciseType.SQUAT, emaAlpha = 1.0f, initialStrictness = DetectionStrictness.ADAPTIVE)
            var ts = 1000L
            for (deg in listOf(152f, 130f, 104f, 130f, 152f, 130f, 104f, 152f)) {
                ts += 100L
                val pts = makeAngle2D(
                    BiomechanicsEngine.LEFT_HIP,
                    BiomechanicsEngine.LEFT_KNEE,
                    BiomechanicsEngine.LEFT_ANKLE,
                    angleDeg = deg,
                    bPos = PosePoint(0.50f, 0.65f),
                    armA = 0f to 0.25f,
                    lenC = 0.25f
                )
                // Swap so ankle is below knee and hip is above
                val ankle = PosePoint(0.50f, 0.90f)
                val knee = PosePoint(0.50f, 0.65f)
                val rad = deg * PI / 180.0
                val hip = PosePoint(knee.x - (0.25f * sin(rad)).toFloat(), knee.y + (0.25f * cos(rad)).toFloat())
                val sh = PosePoint(hip.x + 0.05f, hip.y - 0.26f)
                pts[BiomechanicsEngine.LEFT_ANKLE] = ankle
                pts[BiomechanicsEngine.RIGHT_ANKLE] = ankle
                pts[BiomechanicsEngine.LEFT_KNEE] = knee
                pts[BiomechanicsEngine.RIGHT_KNEE] = knee
                pts[BiomechanicsEngine.LEFT_HIP] = hip
                pts[BiomechanicsEngine.RIGHT_HIP] = hip
                pts[BiomechanicsEngine.LEFT_SHOULDER] = sh
                pts[BiomechanicsEngine.RIGHT_SHOULDER] = sh
                engine.processFrame(pts, ts)
            }
            assertEquals("Parallel 104° squat with 152° top must count 2 reps in ADAPTIVE", 2, engine.emptyFrame().repCount)
        }

        // 2. BULGARIAN_SQUAT constant tension (145° top -> 106° bottom -> 145° top)
        run {
            val engine = BiomechanicsEngine(ExerciseType.BULGARIAN_SQUAT, emaAlpha = 1.0f)
            var ts = 1000L
            for (deg in listOf(145f, 125f, 106f, 125f, 145f)) {
                ts += 100L
                val pts = MutableList(33) { PosePoint(0.50f, 0.50f) }
                val ankle = PosePoint(0.50f, 0.90f)
                val knee = PosePoint(0.50f, 0.65f)
                val rad = deg * PI / 180.0
                val hip = PosePoint(knee.x - (0.25f * sin(rad)).toFloat(), knee.y + (0.25f * cos(rad)).toFloat())
                val sh = PosePoint(hip.x + 0.04f, hip.y - 0.26f)
                pts[BiomechanicsEngine.LEFT_ANKLE] = ankle
                pts[BiomechanicsEngine.RIGHT_ANKLE] = ankle
                pts[BiomechanicsEngine.LEFT_KNEE] = knee
                pts[BiomechanicsEngine.RIGHT_KNEE] = knee
                pts[BiomechanicsEngine.LEFT_HIP] = hip
                pts[BiomechanicsEngine.RIGHT_HIP] = hip
                pts[BiomechanicsEngine.LEFT_SHOULDER] = sh
                pts[BiomechanicsEngine.RIGHT_SHOULDER] = sh
                engine.processFrame(pts, ts)
            }
            assertEquals("Bulgarian constant-tension 145° top must count 1 rep", 1, engine.emptyFrame().repCount)
        }

        // 3. BICEP_CURL alternating 1-arm curl (left curls while right stays straight, then right curls!)
        run {
            val engine = BiomechanicsEngine(ExerciseType.BICEP_CURL, emaAlpha = 1.0f)
            var ts = 1000L
            var sawAlternatingVariant = false
            var sawSymmetryFault = false
            val seq = listOf(
                152f to 156f, 110f to 156f, 68f to 156f, 110f to 156f, 148f to 156f,
                154f to 110f, 154f to 66f, 154f to 110f, 154f to 148f
            )
            for ((lDeg, rDeg) in seq) {
                ts += 100L
                val pts = blank3D()
                val lSh = PosePoint(0.18f, -0.48f, 0f)
                val rSh = PosePoint(-0.18f, -0.48f, 0f)
                val lEl = PosePoint(0.18f, -0.22f, 0f)
                val rEl = PosePoint(-0.18f, -0.22f, 0f)
                fun wrist(el: PosePoint, deg: Float): PosePoint {
                    val rad = deg * PI / 180.0
                    return PosePoint(el.x, el.y - (0.24f * cos(rad)).toFloat(), -(0.24f * sin(rad)).toFloat())
                }
                pts[BiomechanicsEngine.NOSE] = PosePoint(0f, -0.64f, -0.10f)
                pts[BiomechanicsEngine.LEFT_SHOULDER] = lSh
                pts[BiomechanicsEngine.RIGHT_SHOULDER] = rSh
                pts[BiomechanicsEngine.LEFT_ELBOW] = lEl
                pts[BiomechanicsEngine.RIGHT_ELBOW] = rEl
                pts[BiomechanicsEngine.LEFT_WRIST] = wrist(lEl, lDeg)
                pts[BiomechanicsEngine.RIGHT_WRIST] = wrist(rEl, rDeg)
                pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(0.13f, 0f, 0f)
                pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-0.13f, 0f, 0f)
                pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(0.13f, 0.42f, 0f)
                pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-0.13f, 0.42f, 0f)
                pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(0.13f, 0.84f, 0f)
                pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-0.13f, 0.84f, 0f)
                val (scr, wld) = rotateAndProject(pts, yawDeg = 10f)
                val f = engine.processFrame(scr, wld, ts)
                if (f.detectedVariantFr == "Alterné") sawAlternatingVariant = true
                if (f.corrections.any { it.shortBadgeFr.contains("SYMÉTRIE") }) sawSymmetryFault = true
            }
            assertEquals("Alternating 1-arm curls must count both reps", 2, engine.emptyFrame().repCount)
            assertTrue("Expected 'Alterné' variant badge on alternating curls", sawAlternatingVariant)
            assertFalse("Alternating curls must NOT emit symmetry fault", sawSymmetryFault)
        }

        // 4. JUMPING_JACK side-view without ankle spread blocking
        run {
            val engine = BiomechanicsEngine(ExerciseType.JUMPING_JACK, emaAlpha = 1.0f)
            var ts = 1000L
            for (armDeg in listOf(28f, 80f, 134f, 85f, 34f)) {
                ts += 100L
                val pts = MutableList(33) { PosePoint(0.50f, 0.50f) }
                val hip = PosePoint(0.50f, 0.58f)
                val sh = PosePoint(0.50f, 0.32f)
                val rad = armDeg * PI / 180.0
                val wr = PosePoint(sh.x + (0.28f * sin(rad)).toFloat(), sh.y + (0.28f * cos(rad)).toFloat())
                pts[BiomechanicsEngine.LEFT_HIP] = hip
                pts[BiomechanicsEngine.RIGHT_HIP] = hip
                pts[BiomechanicsEngine.LEFT_SHOULDER] = sh
                pts[BiomechanicsEngine.RIGHT_SHOULDER] = sh
                pts[BiomechanicsEngine.LEFT_WRIST] = wr
                pts[BiomechanicsEngine.RIGHT_WRIST] = wr
                // Ankles together (simulating side profile overlap)
                pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(0.50f, 0.90f)
                pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(0.50f, 0.90f)
                engine.processFrame(pts, ts)
            }
            assertEquals("Side-view Jumping Jack must count without ankle spread blocking", 1, engine.emptyFrame().repCount)
        }

        // 5. PULL_UP wide-grip (102° elbow + chin over hands + active hang 144°)
        run {
            val engine = BiomechanicsEngine(ExerciseType.PULL_UP, emaAlpha = 1.0f)
            var ts = 1000L
            for ((deg, chinOver) in listOf(155f to false, 125f to false, 102f to true, 125f to false, 144f to false)) {
                ts += 100L
                val pts = makeAngle2D(
                    BiomechanicsEngine.LEFT_SHOULDER,
                    BiomechanicsEngine.LEFT_ELBOW,
                    BiomechanicsEngine.LEFT_WRIST,
                    angleDeg = deg,
                    bPos = PosePoint(0.35f, 0.35f),
                    armA = 0.10f to 0.12f,
                    lenC = 0.16f
                )
                pts[BiomechanicsEngine.RIGHT_SHOULDER] = pts[BiomechanicsEngine.LEFT_SHOULDER]
                pts[BiomechanicsEngine.RIGHT_ELBOW] = pts[BiomechanicsEngine.LEFT_ELBOW]
                pts[BiomechanicsEngine.RIGHT_WRIST] = pts[BiomechanicsEngine.LEFT_WRIST]
                val wrY = pts[BiomechanicsEngine.LEFT_WRIST].y
                pts[BiomechanicsEngine.NOSE] = PosePoint(0.50f, if (chinOver) wrY - 0.02f else wrY + 0.18f)
                engine.processFrame(pts, ts)
            }
            assertEquals("Wide-grip pull-up with chin over bar must count 1 rep", 1, engine.emptyFrame().repCount)
        }

        // 6. GLUTE_BRIDGE starting at 134° -> 160° -> 134°
        run {
            val engine = BiomechanicsEngine(ExerciseType.GLUTE_BRIDGE, emaAlpha = 1.0f)
            var ts = 1000L
            for (deg in listOf(134f, 148f, 160f, 146f, 134f)) {
                ts += 100L
                val pts = makeAngle2D(
                    BiomechanicsEngine.LEFT_SHOULDER,
                    BiomechanicsEngine.LEFT_HIP,
                    BiomechanicsEngine.LEFT_KNEE,
                    angleDeg = deg,
                    bPos = PosePoint(0.50f, 0.70f),
                    armA = -0.25f to 0.05f,
                    lenC = 0.25f
                )
                pts[BiomechanicsEngine.RIGHT_SHOULDER] = pts[BiomechanicsEngine.LEFT_SHOULDER]
                pts[BiomechanicsEngine.RIGHT_HIP] = pts[BiomechanicsEngine.LEFT_HIP]
                pts[BiomechanicsEngine.RIGHT_KNEE] = pts[BiomechanicsEngine.LEFT_KNEE]
                engine.processFrame(pts, ts)
            }
            assertEquals("Glute bridge starting at 134° must count 1 rep", 1, engine.emptyFrame().repCount)
        }

        // 7. LEG_RAISE hovering feet at 146° -> 108° -> 146°
        run {
            val engine = BiomechanicsEngine(ExerciseType.LEG_RAISE, emaAlpha = 1.0f)
            var ts = 1000L
            for (deg in listOf(146f, 128f, 108f, 128f, 146f)) {
                ts += 100L
                val pts = makeAngle2D(
                    BiomechanicsEngine.LEFT_SHOULDER,
                    BiomechanicsEngine.LEFT_HIP,
                    BiomechanicsEngine.LEFT_KNEE,
                    angleDeg = deg,
                    bPos = PosePoint(0.50f, 0.75f),
                    armA = -0.28f to 0f,
                    lenC = 0.22f
                )
                // Keep straight knee (~175°)
                val kn = pts[BiomechanicsEngine.LEFT_KNEE]
                val hp = pts[BiomechanicsEngine.LEFT_HIP]
                val ank = PosePoint(kn.x + (kn.x - hp.x), kn.y + (kn.y - hp.y))
                pts[BiomechanicsEngine.LEFT_ANKLE] = ank
                pts[BiomechanicsEngine.RIGHT_SHOULDER] = pts[BiomechanicsEngine.LEFT_SHOULDER]
                pts[BiomechanicsEngine.RIGHT_HIP] = pts[BiomechanicsEngine.LEFT_HIP]
                pts[BiomechanicsEngine.RIGHT_KNEE] = kn
                pts[BiomechanicsEngine.RIGHT_ANKLE] = ank
                engine.processFrame(pts, ts)
            }
            assertEquals("Leg raise with hovering feet (146° start/finish) must count 1 rep", 1, engine.emptyFrame().repCount)
        }

        // 8. LATERAL_RAISE constant tension (32° -> 76° -> 32°)
        run {
            val engine = BiomechanicsEngine(ExerciseType.LATERAL_RAISE, emaAlpha = 1.0f)
            var ts = 1000L
            for (deg in listOf(32f, 54f, 76f, 52f, 32f)) {
                ts += 100L
                val pts = MutableList(33) { PosePoint(0.50f, 0.50f) }
                val sh = PosePoint(0.50f, 0.30f)
                val hp = PosePoint(0.50f, 0.62f)
                val rad = deg * PI / 180.0
                val el = PosePoint(sh.x + (0.20f * sin(rad)).toFloat(), sh.y + (0.20f * cos(rad)).toFloat())
                pts[BiomechanicsEngine.LEFT_SHOULDER] = sh
                pts[BiomechanicsEngine.RIGHT_SHOULDER] = sh
                pts[BiomechanicsEngine.LEFT_HIP] = hp
                pts[BiomechanicsEngine.RIGHT_HIP] = hp
                pts[BiomechanicsEngine.LEFT_ELBOW] = el
                pts[BiomechanicsEngine.RIGHT_ELBOW] = el
                engine.processFrame(pts, ts)
            }
            assertEquals("Lateral raise starting at 32° must count 1 rep", 1, engine.emptyFrame().repCount)
        }

        // 9. PLANK adaptive band (156° still holds, 148° pauses) & STRICT rejects 156°
        run {
            val adaptive = BiomechanicsEngine(ExerciseType.PLANK, emaAlpha = 1.0f, initialStrictness = DetectionStrictness.ADAPTIVE)
            val strict = BiomechanicsEngine(ExerciseType.PLANK, emaAlpha = 1.0f, initialStrictness = DetectionStrictness.STRICT)
            val pts = MutableList(33) { PosePoint(0.50f, 0.50f) }
            pts[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(0.20f, 0.50f)
            pts[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(0.20f, 0.50f)
            // hipY = 0.56 -> angle ~157.4° (inside ADAPTIVE 155..202, outside STRICT 160..195)
            pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(0.50f, 0.56f)
            pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(0.50f, 0.56f)
            pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(0.80f, 0.50f)
            pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(0.80f, 0.50f)
            pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(0.20f, 0.65f)
            pts[BiomechanicsEngine.LEFT_WRIST] = PosePoint(0.08f, 0.65f)

            val fAdapt = adaptive.processFrame(pts, 1000L)
            val fStrict = strict.processFrame(pts, 1000L)
            assertEquals("ADAPTIVE plank should hold at ~157°", MovementPhase.HOLD, fAdapt.phase)
            assertEquals("STRICT plank should pause at ~157°", MovementPhase.PAUSE, fStrict.phase)
        }
    }
}
