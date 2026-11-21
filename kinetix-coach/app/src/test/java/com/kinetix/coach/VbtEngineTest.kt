package com.kinetix.coach

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import com.kinetix.coach.vision.BiomechanicsEngine
import com.kinetix.coach.vision.FormQuality
import com.kinetix.coach.vision.PosePoint
import com.kinetix.coach.vision.VbtEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class VbtEngineTest {

    private fun make3DFrameWithHipY(hipY: Float): Pair<List<PosePoint>, List<PosePoint>> {
        val world = MutableList(33) { PosePoint(0f, hipY, 0f, 1f) }
        world[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(+0.19f, hipY - 0.50f, 0f, 1f)
        world[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(-0.19f, hipY - 0.50f, 0f, 1f)
        world[BiomechanicsEngine.LEFT_HIP] = PosePoint(+0.13f, hipY, 0f, 1f)
        world[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-0.13f, hipY, 0f, 1f)
        world[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+0.13f, 0.84f, 0f, 1f)
        world[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-0.13f, 0.84f, 0f, 1f)

        val screen = world.map { wp ->
            PosePoint(
                x = 0.50f + wp.x * 0.45f,
                y = 0.50f + wp.y * 0.45f,
                z = wp.z,
                visibility = 1f
            )
        }
        return screen to world
    }

    /**
     * Simulates a single Squat rep moving hipY down by [dispMeters] over [eccMs], pausing [pauseMs],
     * then driving back up over [concMs].
     */
    private fun simulateSquatRep(
        vbt: VbtEngine,
        startTsMs: Long,
        dispMeters: Float,
        eccMs: Long,
        pauseMs: Long,
        concMs: Long
    ): Pair<Long, com.kinetix.coach.vision.VbtMetrics> {
        var ts = startTsMs
        // Ready top frame
        val (sTop, wTop) = make3DFrameWithHipY(0.0f)
        vbt.onFrame(ExerciseType.SQUAT, MovementPhase.READY, false, sTop, wTop, true, ts)

        val stepMs = 50L
        val eccSteps = (eccMs / stepMs).toInt().coerceAtLeast(1)
        for (i in 1..eccSteps) {
            ts += stepMs
            val f = i.toFloat() / eccSteps
            val (s, w) = make3DFrameWithHipY(dispMeters * f)
            vbt.onFrame(ExerciseType.SQUAT, MovementPhase.ECCENTRIC, false, s, w, true, ts)
        }

        val pauseSteps = (pauseMs / stepMs).toInt().coerceAtLeast(1)
        val (sBot, wBot) = make3DFrameWithHipY(dispMeters)
        repeat(pauseSteps) {
            ts += stepMs
            vbt.onFrame(ExerciseType.SQUAT, MovementPhase.BOTTOM, false, sBot, wBot, true, ts)
        }

        val concSteps = (concMs / stepMs).toInt().coerceAtLeast(1)
        for (i in 1 until concSteps) {
            ts += stepMs
            val f = 1f - (i.toFloat() / concSteps)
            val (s, w) = make3DFrameWithHipY(dispMeters * f)
            vbt.onFrame(ExerciseType.SQUAT, MovementPhase.CONCENTRIC, false, s, w, true, ts)
        }

        ts += stepMs
        val finalMetrics = vbt.onFrame(ExerciseType.SQUAT, MovementPhase.READY, true, sTop, wTop, true, ts)
        return ts to finalMetrics
    }

    @Test
    fun vbtEngine_computesExactConcentricVelocityAndThreePhaseTempo() {
        val vbt = VbtEngine(athleteHeightCm = 180)
        // 0.50m displacement, 1.5s eccentric, 0.3s bottom pause, 0.5s concentric -> expected MCV = 0.50 / 0.50 = 1.00 m/s
        val (_, rep1) = simulateSquatRep(
            vbt = vbt,
            startTsMs = 1000L,
            dispMeters = 0.50f,
            eccMs = 1500L,
            pauseMs = 300L,
            concMs = 500L
        )
        assertTrue("VBT must mark hasData=true after rep 1", rep1.hasData)
        assertEquals("Expected concentric velocity ~1.00 m/s", 1.00f, rep1.lastConcentricVelocityMs, 0.08f)
        assertEquals("Expected eccentric duration 1.5s", 1.5f, rep1.eccentricSec, 0.1f)
        assertEquals("Expected pause duration 0.3s", 0.3f, rep1.pauseSec, 0.1f)
        assertEquals("Expected concentric duration 0.5s", 0.5f, rep1.concentricSec, 0.1f)
        assertEquals("1.5-0.3-0.5s", rep1.tempoLabel)
        assertEquals("Rep 1 has 0% velocity loss", 0, rep1.velocityLossPct)
        assertEquals("4+", rep1.rirLabel)
        assertEquals("EXPLOSIF", rep1.zoneLabelFr)
    }

    @Test
    fun vbtEngine_tracksVelocityLossRirProgressionAndNonSpammyAlerts() {
        val vbt = VbtEngine(athleteHeightCm = 175)
        var ts = 1000L

        // Rep 1: explosive 0.50m / 0.50s = 1.00 m/s -> VL 0%, RIR 4+
        val (t1, m1) = simulateSquatRep(vbt, ts, dispMeters = 0.50f, eccMs = 1200L, pauseMs = 200L, concMs = 500L)
        ts = t1 + 100L
        assertEquals(0, m1.velocityLossPct)
        assertEquals("4+", m1.rirLabel)
        assertNull(m1.alertVoiceCueFr)

        // Rep 2: 0.50m / 0.55s = 0.91 m/s -> VL ~9-10%, RIR 4+ or 3
        val (t2, m2) = simulateSquatRep(vbt, ts, dispMeters = 0.50f, eccMs = 1200L, pauseMs = 200L, concMs = 550L)
        ts = t2 + 100L
        assertTrue("Rep 2 VL should be ~8-12%, got ${m2.velocityLossPct}", m2.velocityLossPct in 6..14)
        assertNull("No fatigue alert yet at rep 2", m2.alertVoiceCueFr)

        // Rep 3: slowing down 0.50m / 0.65s = 0.77 m/s -> VL ~23%, RIR 2 (HYPERTROPHIE) -> triggers 1st voice alert!
        val (t3, m3) = simulateSquatRep(vbt, ts, dispMeters = 0.50f, eccMs = 1300L, pauseMs = 200L, concMs = 650L)
        ts = t3 + 100L
        assertTrue("Rep 3 VL should be >= 20%, got ${m3.velocityLossPct}", m3.velocityLossPct in 20..29)
        assertEquals(2, m3.estimatedRir)
        assertEquals("HYPERTROPHIE", m3.zoneLabelFr)
        assertEquals(FormQuality.CAUTION, m3.zoneQuality)
        assertNotNull("Crossing 20% VL at rep 3+ must emit hypertrophy voice alert once", m3.alertVoiceCueFr)

        // Rep 4: still in 20-34% zone (0.50m / 0.70s = 0.71 m/s -> VL ~29%) -> must NOT re-spam hypertrophy alert!
        val (t4, m4) = simulateSquatRep(vbt, ts, dispMeters = 0.50f, eccMs = 1400L, pauseMs = 200L, concMs = 700L)
        ts = t4 + 100L
        assertNull("Must NOT re-spam hypertrophy alert on rep 4", m4.alertVoiceCueFr)

        // Rep 5: heavy fatigue 0.50m / 0.90s = 0.56 m/s -> VL ~44%, RIR 0 (ÉCHEC TECH) -> triggers critical voice alert once!
        val (t5, m5) = simulateSquatRep(vbt, ts, dispMeters = 0.50f, eccMs = 1500L, pauseMs = 250L, concMs = 900L)
        ts = t5 + 100L
        assertTrue("Rep 5 VL should be >= 40%, got ${m5.velocityLossPct}", m5.velocityLossPct >= 40)
        assertEquals(0, m5.estimatedRir)
        assertEquals("0", m5.rirLabel)
        assertEquals("ÉCHEC TECH", m5.zoneLabelFr)
        assertEquals(FormQuality.FAULT, m5.zoneQuality)
        assertNotNull("Crossing >= 35% VL must emit critical fatigue voice alert once", m5.alertVoiceCueFr)

        // Rep 6: still critical -> must NOT re-spam critical alert!
        val (_, m6) = simulateSquatRep(vbt, ts, dispMeters = 0.50f, eccMs = 1500L, pauseMs = 250L, concMs = 950L)
        assertNull("Must NOT re-spam critical alert on rep 6", m6.alertVoiceCueFr)
    }

    @Test
    fun vbtEngine_isometricPlankProducesNoVelocityData() {
        val vbt = VbtEngine()
        val (s, w) = make3DFrameWithHipY(0.40f)
        val metrics = vbt.onFrame(ExerciseType.PLANK, MovementPhase.HOLD, false, s, w, true, 1000L)
        assertFalse("Isometric Plank must keep vbt.hasData = false", metrics.hasData)
    }

    @Test
    fun biomechanicsEngine_populatesLiveVbtMetricsAcrossPushupSetAndResetsCleanly() {
        val engine = BiomechanicsEngine(ExerciseType.PUSHUP)
        engine.setAthleteHeightCm(178)
        var ts = 1000L

        fun makePushupPose(depth: Float): Pair<List<PosePoint>, List<PosePoint>> {
            val pts = MutableList(33) { PosePoint(0f, 0f, 0f, 1f) }
            val shY = -0.02f + depth * 0.34f
            val floorY = 0.44f
            val shZ = -0.50f
            val anZ = 0.78f
            val hipY = shY + 0.44f * (floorY - shY)
            val hipZ = shZ + 0.44f * (anZ - shZ)
            val elOut = 0.03f + depth * 0.20f
            val elBack = 0.02f + depth * 0.22f
            val elY = (shY + floorY) * 0.5f - depth * 0.06f

            pts[BiomechanicsEngine.NOSE] = PosePoint(0f, shY - 0.05f, shZ - 0.16f, 1f)
            pts[BiomechanicsEngine.LEFT_SHOULDER] = PosePoint(+0.19f, shY, shZ, 1f)
            pts[BiomechanicsEngine.RIGHT_SHOULDER] = PosePoint(-0.19f, shY, shZ, 1f)
            pts[BiomechanicsEngine.LEFT_ELBOW] = PosePoint(+0.19f + elOut, elY, shZ + elBack, 1f)
            pts[BiomechanicsEngine.RIGHT_ELBOW] = PosePoint(-0.19f - elOut, elY, shZ + elBack, 1f)
            pts[BiomechanicsEngine.LEFT_WRIST] = PosePoint(+0.22f, floorY, shZ - 0.02f, 1f)
            pts[BiomechanicsEngine.RIGHT_WRIST] = PosePoint(-0.22f, floorY, shZ - 0.02f, 1f)
            pts[BiomechanicsEngine.LEFT_HIP] = PosePoint(+0.13f, hipY, hipZ, 1f)
            pts[BiomechanicsEngine.RIGHT_HIP] = PosePoint(-0.13f, hipY, hipZ, 1f)
            pts[BiomechanicsEngine.LEFT_KNEE] = PosePoint(+0.13f, (hipY + floorY) * 0.5f, (hipZ + anZ) * 0.5f, 1f)
            pts[BiomechanicsEngine.RIGHT_KNEE] = PosePoint(-0.13f, (hipY + floorY) * 0.5f, (hipZ + anZ) * 0.5f, 1f)
            pts[BiomechanicsEngine.LEFT_ANKLE] = PosePoint(+0.13f, floorY - 0.03f, anZ, 1f)
            pts[BiomechanicsEngine.RIGHT_ANKLE] = PosePoint(-0.13f, floorY - 0.03f, anZ, 1f)

            val yaw = 45.0 * PI / 180.0
            val c = cos(yaw).toFloat()
            val s = sin(yaw).toFloat()
            val rotW = pts.map { p -> PosePoint(p.x * c + p.z * s, p.y, -p.x * s + p.z * c, 1f) }
            val scr = rotW.map { wp ->
                val d = (2.3f + wp.z).coerceAtLeast(0.6f)
                PosePoint(0.50f + wp.x * 1.0f / d, 0.54f + wp.y * 1.0f / d, wp.z, 1f)
            }
            return scr to rotW
        }

        // Settle
        repeat(8) {
            ts += 33L
            val (s, w) = makePushupPose(0f)
            engine.processFrame(s, w, ts)
        }

        // 2 fast push-ups then 2 slower fatiguing push-ups
        var lastFrame = engine.emptyFrame()
        val repFrameCounts = listOf(26, 28, 42, 54)
        for (framesInRep in repFrameCounts) {
            for (i in 0 until framesInRep) {
                ts += 33L
                val p = (0.5f - 0.5f * cos(2.0 * PI * (i.toDouble() / (framesInRep - 1)))).toFloat()
                val (s, w) = makePushupPose(p)
                lastFrame = engine.processFrame(s, w, ts)
            }
        }

        assertEquals("Must count all 4 push-ups", 4, lastFrame.repCount)
        assertTrue("VBT must be populated after push-ups", lastFrame.vbt.hasData)
        assertTrue("Concentric velocity should be realistic (> 0.15 m/s), got ${lastFrame.vbt.lastConcentricVelocityMs}", lastFrame.vbt.lastConcentricVelocityMs > 0.15f)
        assertTrue("Velocity loss must increase on slower final reps (> 15%), got ${lastFrame.vbt.velocityLossPct}%", lastFrame.vbt.velocityLossPct >= 15)
        assertTrue("Tempo label must be formatted as X.X-X.X-X.Xs, got ${lastFrame.vbt.tempoLabel}", lastFrame.vbt.tempoLabel.endsWith("s"))

        // Verify clean reset on resetSession
        engine.resetSession()
        assertFalse("resetSession must clear VBT state", engine.emptyFrame().vbt.hasData)
    }
}
