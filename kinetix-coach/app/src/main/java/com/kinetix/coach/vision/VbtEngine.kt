package com.kinetix.coach.vision

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Velocity-Based Training (VBT) telemetry snapshot for the active set and most recent rep.
 */
data class VbtMetrics(
    val hasData: Boolean = false,
    val lastConcentricVelocityMs: Float = 0f,
    val peakConcentricVelocityMs: Float = 0f,
    val bestSetVelocityMs: Float = 0f,
    val meanSetVelocityMs: Float = 0f,
    val velocityLossPct: Int = 0,
    val estimatedRir: Int = 4,
    val rirLabel: String = "4+",
    val eccentricSec: Float = 0f,
    val pauseSec: Float = 0f,
    val concentricSec: Float = 0f,
    val tempoLabel: String = "--",
    val zoneLabelFr: String = "EXPLOSIF",
    val zoneQuality: FormQuality = FormQuality.GOOD,
    val alertVoiceCueFr: String? = null
)

/**
 * Pure-Kotlin real-time Velocity-Based Training (VBT) engine using true kinematic trajectory
 * turnaround analysis (matching lab linear-transducer / GymAware / Vitruve methodology).
 *
 * Computes:
 * - Mean Concentric Velocity (MCV, m/s) & Peak Concentric Velocity (PCV, m/s) from 3D metric
 *   world landmarks with anthropometric stature-calibrated 2D fallback (immune to tilted floor cameras).
 * - True kinematic 3-phase rep tempo (Eccentric - Bottom/Peak Pause - Concentric, e.g. "2.1-0.3-0.7s")
 *   derived from the actual vertical turnaround extremum rather than state-machine hysteresis bands.
 * - Intra-set Velocity Loss % relative to the fastest concentric rep of the current set.
 * - Evidence-based RIR (Reps In Reserve: 4+, 3, 2, 1, 0) & non-spammy fatigue zone alerts.
 */
class VbtEngine(
    private var athleteHeightCm: Int = 175
) {
    companion object {
        // Winter biomechanics segment proportions relative to standing stature
        private const val TORSO_TO_STATURE_RATIO = 0.30f
        private const val SHOULDER_WIDTH_TO_STATURE_RATIO = 0.23f

        private const val TURNAROUND_BAND_FRAC = 0.03f
        private const val MIN_CONCENTRIC_SEC = 0.14f
        private const val MAX_CONCENTRIC_SEC = 6.0f
        private const val MIN_DISP_METERS = 0.08f
        private const val MAX_DISP_METERS = 1.15f
        private const val MIN_VELOCITY_MS = 0.08f
        private const val MAX_VELOCITY_MS = 2.60f
        private const val MAX_TRAJECTORY_SAMPLES = 420
    }

    private data class TrajectorySample(
        val timestampMs: Long,
        val worldY: Float,
        val screenY: Float,
        val metersPerScreen: Float
    )

    private var prevPhase: MovementPhase = MovementPhase.READY
    private var lastTsMs: Long = 0L

    // Fallback phase accumulators (ms)
    private var eccentricMs: Long = 0L
    private var pauseMs: Long = 0L
    private var concentricMs: Long = 0L

    // Per-rep kinematic trajectory buffer
    private val trajectory = ArrayList<TrajectorySample>(96)
    private var lastReadySample: TrajectorySample? = null

    // Set-level velocity history
    private var completedRepsInSet: Int = 0
    private var bestSetVelocityMs: Float = 0f
    private var sumSetVelocityMs: Double = 0.0
    private var announcedHypertrophyAlert: Boolean = false
    private var announcedCriticalAlert: Boolean = false

    private var latestMetrics: VbtMetrics = VbtMetrics()

    fun setAthleteHeightCm(heightCm: Int) {
        athleteHeightCm = heightCm.coerceIn(130, 225)
    }

    fun resetSet() {
        resetActiveRep()
        prevPhase = MovementPhase.READY
        lastTsMs = 0L
        lastReadySample = null
        completedRepsInSet = 0
        bestSetVelocityMs = 0f
        sumSetVelocityMs = 0.0
        announcedHypertrophyAlert = false
        announcedCriticalAlert = false
        latestMetrics = VbtMetrics()
    }

    fun currentMetrics(): VbtMetrics = latestMetrics.copy(alertVoiceCueFr = null)

    fun onFrame(
        exercise: ExerciseType,
        phase: MovementPhase,
        repCompletedNow: Boolean,
        screenPts: List<PosePoint>,
        worldPts: List<PosePoint>,
        use3D: Boolean,
        timestampMs: Long
    ): VbtMetrics {
        if (exercise.isIsometric || screenPts.size < 29) {
            prevPhase = phase
            lastTsMs = timestampMs
            return latestMetrics.copy(alertVoiceCueFr = null)
        }

        val dtMs = if (lastTsMs > 0L) (timestampMs - lastTsMs).coerceIn(0L, 250L) else 0L
        lastTsMs = timestampMs

        val driverYScreen = sampleDriverY(exercise, screenPts)
        val driverYWorld = if (use3D && worldPts.size >= 29) sampleDriverY(exercise, worldPts) else Float.NaN
        val metersPerScreenUnit = computeMetersPerScreenUnit(screenPts)
        val sample = TrajectorySample(
            timestampMs = timestampMs,
            worldY = driverYWorld,
            screenY = driverYScreen,
            metersPerScreen = metersPerScreenUnit
        )

        val inActiveRep = phase == MovementPhase.ECCENTRIC ||
            phase == MovementPhase.BOTTOM ||
            phase == MovementPhase.CONCENTRIC

        if (phase == MovementPhase.READY && !repCompletedNow) {
            lastReadySample = sample
        }

        // Start tracking cleanly when leaving READY into a new rep (seed with last READY frame)
        if (prevPhase == MovementPhase.READY && (inActiveRep || repCompletedNow)) {
            resetActiveRep()
            lastReadySample?.let { seed ->
                if ((timestampMs - seed.timestampMs) in 1L..350L) {
                    trajectory.add(seed)
                }
            }
        }

        if (inActiveRep || repCompletedNow) {
            when (phase) {
                MovementPhase.ECCENTRIC -> eccentricMs += dtMs
                MovementPhase.BOTTOM -> pauseMs += dtMs
                MovementPhase.CONCENTRIC -> concentricMs += dtMs
                else -> Unit
            }
            if (trajectory.size < MAX_TRAJECTORY_SAMPLES) {
                trajectory.add(sample)
            }
        } else if (phase == MovementPhase.READY && prevPhase != MovementPhase.READY) {
            // Incomplete rep aborted before completion
            resetActiveRep()
        }

        prevPhase = phase

        if (!repCompletedNow) {
            return latestMetrics.copy(alertVoiceCueFr = null)
        }

        val finalized = finalizeRep(exercise)
        resetActiveRep()
        return finalized
    }

    private fun finalizeRep(exercise: ExerciseType): VbtMetrics {
        // 1. Compute rep displacement in meters from world & stature-scaled screen trajectories
        var minWorldY = Float.POSITIVE_INFINITY
        var maxWorldY = Float.NEGATIVE_INFINITY
        var minScreenY = Float.POSITIVE_INFINITY
        var maxScreenY = Float.NEGATIVE_INFINITY
        var scaleSum = 0f
        var scaleCount = 0

        for (s in trajectory) {
            if (s.worldY.isFinite()) {
                if (s.worldY < minWorldY) minWorldY = s.worldY
                if (s.worldY > maxWorldY) maxWorldY = s.worldY
            }
            if (s.screenY.isFinite()) {
                if (s.screenY < minScreenY) minScreenY = s.screenY
                if (s.screenY > maxScreenY) maxScreenY = s.screenY
            }
            if (s.metersPerScreen.isFinite() && s.metersPerScreen > 0.1f) {
                scaleSum += s.metersPerScreen
                scaleCount++
            }
        }

        val avgMetersPerScreen = if (scaleCount > 0) scaleSum / scaleCount else 1.80f
        val worldDisp = if (maxWorldY.isFinite() && minWorldY.isFinite()) (maxWorldY - minWorldY).coerceAtLeast(0f) else 0f
        val screenDispMeters = if (maxScreenY.isFinite() && minScreenY.isFinite()) {
            (maxScreenY - minScreenY).coerceAtLeast(0f) * avgMetersPerScreen
        } else {
            0f
        }

        val useWorldSignal = when {
            exercise == ExerciseType.PUSHUP || exercise == ExerciseType.GLUTE_BRIDGE ->
                worldDisp >= screenDispMeters * 0.85f && worldDisp >= 0.05f
            worldDisp >= 0.06f -> true
            else -> false
        }
        val rawDispMeters = if (useWorldSignal) worldDisp else {
            if (exercise == ExerciseType.PUSHUP || exercise == ExerciseType.GLUTE_BRIDGE) {
                max(worldDisp, screenDispMeters * 0.85f)
            } else {
                screenDispMeters
            }
        }
        val dispMeters = if (rawDispMeters >= MIN_DISP_METERS) {
            rawDispMeters.coerceAtMost(MAX_DISP_METERS)
        } else {
            fallbackTypicalRomMeters(exercise)
        }

        // 2. Kinematic turnaround analysis on true driver coordinate trajectory
        //    In MediaPipe coordinates, +Y points DOWN:
        //    - Down-first exercises (Squat, Push-up, Dip, Lunge, Deadlift, Burpee): turnaround is MAX Y (lowest physical point).
        //    - Up-first exercises (OHP, Curl, Pull-up, Bent Row, Lateral Raise, Glute Bridge, Leg Raise, Jumping Jack):
        //      turnaround is MIN Y (highest physical point).
        val isDownFirst = isDownwardFirstExercise(exercise)
        var effEccSec: Float
        var effPauseSec: Float
        var effConcSec: Float
        var peakInstantVelMs = 0f

        if (trajectory.size >= 4 && rawDispMeters >= 0.04f) {
            val yValues = FloatArray(trajectory.size) { idx ->
                val s = trajectory[idx]
                if (useWorldSignal && s.worldY.isFinite()) s.worldY else s.screenY * avgMetersPerScreen
            }
            var extIdx = 0
            for (i in 1 until yValues.size) {
                val better = if (isDownFirst) yValues[i] > yValues[extIdx] else yValues[i] < yValues[extIdx]
                if (better) extIdx = i
            }
            val extY = yValues[extIdx]
            val band = max(rawDispMeters * TURNAROUND_BAND_FRAC, 0.008f)

            var pauseStartIdx = extIdx
            while (pauseStartIdx > 0 && abs(yValues[pauseStartIdx - 1] - extY) <= band) {
                pauseStartIdx--
            }
            var pauseEndIdx = extIdx
            while (pauseEndIdx < yValues.lastIndex && abs(yValues[pauseEndIdx + 1] - extY) <= band) {
                pauseEndIdx++
            }

            val tStart = trajectory.first().timestampMs
            val tPauseStart = trajectory[pauseStartIdx].timestampMs
            val tPauseEnd = trajectory[pauseEndIdx].timestampMs
            val tEnd = trajectory.last().timestampMs

            val firstLegSec = ((tPauseStart - tStart).coerceAtLeast(0L) / 1000f)
            val pauseLegSec = ((tPauseEnd - tPauseStart).coerceAtLeast(0L) / 1000f)
            val secondLegSec = ((tEnd - tPauseEnd).coerceAtLeast(0L) / 1000f)

            if (isDownFirst) {
                effEccSec = firstLegSec.coerceIn(0.10f, 12.0f)
                effPauseSec = pauseLegSec.coerceIn(0.0f, 6.0f)
                effConcSec = secondLegSec.coerceIn(MIN_CONCENTRIC_SEC, MAX_CONCENTRIC_SEC)

                // Compute peak instantaneous concentric velocity on the upward second leg
                for (i in (pauseEndIdx + 1) until yValues.size) {
                    val dt = (trajectory[i].timestampMs - trajectory[i - 1].timestampMs)
                    if (dt >= 12L) {
                        val upwardMeters = (yValues[i - 1] - yValues[i]).coerceAtLeast(0f)
                        val v = (upwardMeters / (dt / 1000f)).coerceIn(0f, MAX_VELOCITY_MS)
                        if (v > peakInstantVelMs) peakInstantVelMs = v
                    }
                }
            } else {
                effConcSec = firstLegSec.coerceIn(MIN_CONCENTRIC_SEC, MAX_CONCENTRIC_SEC)
                effPauseSec = pauseLegSec.coerceIn(0.0f, 6.0f)
                effEccSec = secondLegSec.coerceIn(0.10f, 12.0f)

                // Compute peak instantaneous concentric velocity on the upward first leg
                for (i in 1..pauseStartIdx) {
                    val dt = (trajectory[i].timestampMs - trajectory[i - 1].timestampMs)
                    if (dt >= 12L) {
                        val upwardMeters = (yValues[i - 1] - yValues[i]).coerceAtLeast(0f)
                        val v = (upwardMeters / (dt / 1000f)).coerceIn(0f, MAX_VELOCITY_MS)
                        if (v > peakInstantVelMs) peakInstantVelMs = v
                    }
                }
            }
        } else {
            effEccSec = (eccentricMs / 1000f).coerceIn(0.10f, 12.0f)
            effPauseSec = (pauseMs / 1000f).coerceIn(0.0f, 6.0f)
            effConcSec = (concentricMs / 1000f).coerceIn(MIN_CONCENTRIC_SEC, MAX_CONCENTRIC_SEC)
        }

        val meanConcVel = (dispMeters / effConcSec).coerceIn(MIN_VELOCITY_MS, MAX_VELOCITY_MS)
        val peakConcVel = max(meanConcVel * 1.16f, peakInstantVelMs).coerceIn(meanConcVel, MAX_VELOCITY_MS)

        completedRepsInSet++
        if (meanConcVel > bestSetVelocityMs) {
            bestSetVelocityMs = meanConcVel
        }
        sumSetVelocityMs += meanConcVel.toDouble()
        val meanSetVel = (sumSetVelocityMs / completedRepsInSet).toFloat()

        val velocityLossPct = if (completedRepsInSet >= 2 && bestSetVelocityMs > 0.05f) {
            (((bestSetVelocityMs - meanConcVel) / bestSetVelocityMs) * 100f)
                .roundToInt()
                .coerceIn(0, 80)
        } else {
            0
        }

        val (rir, rirLabel, zoneLabel, zoneQuality) = classifyFatigueZone(velocityLossPct)

        var voiceAlert: String? = null
        if (completedRepsInSet >= 3) {
            if (velocityLossPct >= 35 && !announcedCriticalAlert) {
                announcedCriticalAlert = true
                announcedHypertrophyAlert = true
                voiceAlert = "Perte de vitesse $velocityLossPct pour cent, proche de l'échec"
            } else if (velocityLossPct >= 20 && !announcedHypertrophyAlert) {
                announcedHypertrophyAlert = true
                voiceAlert = "Zone hypertrophie, perte $velocityLossPct pour cent"
            }
        }

        val tempoLabel = formatTempo(effEccSec, effPauseSec, effConcSec)

        latestMetrics = VbtMetrics(
            hasData = true,
            lastConcentricVelocityMs = meanConcVel,
            peakConcentricVelocityMs = peakConcVel,
            bestSetVelocityMs = bestSetVelocityMs,
            meanSetVelocityMs = meanSetVel,
            velocityLossPct = velocityLossPct,
            estimatedRir = rir,
            rirLabel = rirLabel,
            eccentricSec = effEccSec,
            pauseSec = effPauseSec,
            concentricSec = effConcSec,
            tempoLabel = tempoLabel,
            zoneLabelFr = zoneLabel,
            zoneQuality = zoneQuality,
            alertVoiceCueFr = voiceAlert
        )
        return latestMetrics
    }

    private fun resetActiveRep() {
        eccentricMs = 0L
        pauseMs = 0L
        concentricMs = 0L
        trajectory.clear()
    }

    private fun isDownwardFirstExercise(exercise: ExerciseType): Boolean = when (exercise) {
        ExerciseType.SQUAT,
        ExerciseType.LUNGE,
        ExerciseType.BULGARIAN_SQUAT,
        ExerciseType.DEADLIFT,
        ExerciseType.PUSHUP,
        ExerciseType.TRICEP_DIP,
        ExerciseType.BURPEE -> true

        ExerciseType.OVERHEAD_PRESS,
        ExerciseType.BICEP_CURL,
        ExerciseType.PULL_UP,
        ExerciseType.BENT_ROW,
        ExerciseType.LATERAL_RAISE,
        ExerciseType.GLUTE_BRIDGE,
        ExerciseType.LEG_RAISE,
        ExerciseType.JUMPING_JACK,
        ExerciseType.PLANK -> false
    }

    private fun sampleDriverY(exercise: ExerciseType, pts: List<PosePoint>): Float {
        val midShY = (pts[BiomechanicsEngine.LEFT_SHOULDER].y + pts[BiomechanicsEngine.RIGHT_SHOULDER].y) * 0.5f
        val midHipY = (pts[BiomechanicsEngine.LEFT_HIP].y + pts[BiomechanicsEngine.RIGHT_HIP].y) * 0.5f
        val midWrY = (pts[BiomechanicsEngine.LEFT_WRIST].y + pts[BiomechanicsEngine.RIGHT_WRIST].y) * 0.5f
        val midAnkY = (pts[BiomechanicsEngine.LEFT_ANKLE].y + pts[BiomechanicsEngine.RIGHT_ANKLE].y) * 0.5f

        return when (exercise) {
            ExerciseType.SQUAT,
            ExerciseType.LUNGE,
            ExerciseType.BULGARIAN_SQUAT,
            ExerciseType.GLUTE_BRIDGE -> midHipY

            ExerciseType.PUSHUP,
            ExerciseType.TRICEP_DIP,
            ExerciseType.PULL_UP,
            ExerciseType.DEADLIFT,
            ExerciseType.BURPEE -> midShY

            ExerciseType.OVERHEAD_PRESS,
            ExerciseType.BICEP_CURL,
            ExerciseType.LATERAL_RAISE,
            ExerciseType.BENT_ROW,
            ExerciseType.JUMPING_JACK -> midWrY

            ExerciseType.LEG_RAISE -> midAnkY
            ExerciseType.PLANK -> midHipY
        }
    }

    /**
     * Converts normalized 2D screen distance to approximate physical meters using the athlete's
     * stature (`athleteHeightCm`) anchored against the largest non-foreshortened body segment.
     */
    private fun computeMetersPerScreenUnit(screenPts: List<PosePoint>): Float {
        val statureM = athleteHeightCm / 100f
        val lSh = screenPts[BiomechanicsEngine.LEFT_SHOULDER]
        val rSh = screenPts[BiomechanicsEngine.RIGHT_SHOULDER]
        val lHip = screenPts[BiomechanicsEngine.LEFT_HIP]
        val rHip = screenPts[BiomechanicsEngine.RIGHT_HIP]

        val midSh = BiomechanicsEngine.midpoint(lSh, rSh)
        val midHip = BiomechanicsEngine.midpoint(lHip, rHip)
        val torsoScreen = BiomechanicsEngine.dist2D(midSh, midHip)
        val shWidthScreen = BiomechanicsEngine.dist2D(lSh, rSh)

        val fromTorso = if (torsoScreen >= 0.10f) (statureM * TORSO_TO_STATURE_RATIO) / torsoScreen else Float.NaN
        val fromShoulders = if (shWidthScreen >= 0.09f) (statureM * SHOULDER_WIDTH_TO_STATURE_RATIO) / shWidthScreen else Float.NaN

        val rawScale = when {
            fromTorso.isFinite() && fromShoulders.isFinite() -> minOf(fromTorso, fromShoulders)
            fromTorso.isFinite() -> fromTorso
            fromShoulders.isFinite() -> fromShoulders
            else -> 1.80f
        }
        return rawScale.coerceIn(0.60f, 4.50f)
    }

    private fun fallbackTypicalRomMeters(exercise: ExerciseType): Float = when (exercise) {
        ExerciseType.SQUAT, ExerciseType.LUNGE, ExerciseType.BULGARIAN_SQUAT, ExerciseType.DEADLIFT -> 0.45f
        ExerciseType.PUSHUP, ExerciseType.TRICEP_DIP -> 0.30f
        ExerciseType.PULL_UP, ExerciseType.OVERHEAD_PRESS -> 0.50f
        ExerciseType.BICEP_CURL, ExerciseType.BENT_ROW, ExerciseType.LATERAL_RAISE -> 0.36f
        ExerciseType.GLUTE_BRIDGE -> 0.24f
        ExerciseType.LEG_RAISE, ExerciseType.BURPEE, ExerciseType.JUMPING_JACK -> 0.65f
        ExerciseType.PLANK -> 0.0f
    }

    private data class FatigueClassification(
        val rir: Int,
        val rirLabel: String,
        val zoneLabelFr: String,
        val quality: FormQuality
    )

    private fun classifyFatigueZone(vlPct: Int): FatigueClassification = when {
        vlPct < 10 -> FatigueClassification(4, "4+", "EXPLOSIF", FormQuality.GOOD)
        vlPct < 20 -> FatigueClassification(3, "3", "FORCE", FormQuality.GOOD)
        vlPct < 30 -> FatigueClassification(2, "2", "HYPERTROPHIE", FormQuality.CAUTION)
        vlPct < 40 -> FatigueClassification(1, "1", "PROCHE ÉCHEC", FormQuality.CAUTION)
        else -> FatigueClassification(0, "0", "ÉCHEC TECH", FormQuality.FAULT)
    }

    private fun formatTempo(eccSec: Float, pauseSec: Float, concSec: Float): String =
        String.format(Locale.US, "%.1f-%.1f-%.1fs", eccSec, pauseSec, concSec)
}
