package com.kinetix.coach.vision

import com.kinetix.coach.data.ExerciseType
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

enum class VelocityFatigueZone(val labelFr: String, val shortLabelFr: String) {
    CALIBRATING("Calibrage vitesse…", "CALIB."),
    EXPLOSIVE("Explosif · Fraîcheur", "EXPLOSIF"),
    OPTIMAL("Zone optimale", "OPTIMAL"),
    FATIGUE("Fatigue progressive", "FATIGUE"),
    NEAR_FAILURE("Proche de l'échec", "LIMITE")
}

data class RepVelocitySample(
    val repNumber: Int,
    val setNumber: Int,
    val meanConcentricVelocityMps: Float,
    val peakConcentricVelocityMps: Float,
    val concentricDurationSec: Float,
    val eccentricDurationSec: Float,
    val totalDurationSec: Float,
    val romPct: Int,
    val velocityLossPct: Int,
    val timestampMs: Long
)

data class SetVelocitySummary(
    val setNumber: Int,
    val exercise: ExerciseType,
    val repCount: Int,
    val baselineVelocityMps: Float,
    val lastVelocityMps: Float,
    val avgVelocityMps: Float,
    val velocityLossPct: Int,
    val estimatedMaxReps: Int?,
    val estimatedRir: Int?
)

data class VelocityTelemetry(
    val isSupported: Boolean = true,
    val currentLiveVelocityMps: Float = 0f,
    val lastRepVelocityMps: Float = 0f,
    val baselineVelocityMps: Float = 0f,
    val sessionFreshBaselineMps: Float = 0f,
    val readinessPct: Int = 100,
    val velocityLossPct: Int = 0,
    val speedTrendSlopeMpsPerRep: Float = 0f,
    val estimatedMaxReps: Int? = null,
    val estimatedFreshMaxReps: Int? = null,
    val estimatedRir: Int? = null,
    val estimatedRpe: Float? = null,
    val confidencePct: Int = 0,
    val fatigueZone: VelocityFatigueZone = VelocityFatigueZone.CALIBRATING,
    val statusFr: String = "Calibrage vitesse (3 reps)…",
    val repVelocitiesMps: List<Float> = emptyList(),
    val repSamples: List<RepVelocitySample> = emptyList(),
    val completedSets: List<SetVelocitySummary> = emptyList()
)

/**
 * Velocity-Based Training (VBT) real-time progressive execution speed analyzer & Max Rep estimator.
 *
 * Measures mean & peak concentric velocity (m/s) per repetition, tracks progressive intra-set
 * velocity loss (VL%) and inter-set readiness across sets, and blends weighted linear regression
 * on the velocity-decay curve with sports-science velocity-loss-to-failure curves
 * (González-Badillo, Sánchez-Medina, Pareja-Blanco) to estimate:
 * - Estimated Max Reps for the active set (`estimatedMaxReps`)
 * - Estimated Fresh Max Reps capacity (`estimatedFreshMaxReps`)
 * - Reps In Reserve (`estimatedRir`) & RPE (`estimatedRpe`)
 * - Live neuromuscular fatigue zone
 */
class VelocityEstimator(
    initialExercise: ExerciseType = ExerciseType.SQUAT
) {
    data class ExerciseVbtSpec(
        val nominalRomMeters: Float,
        val mvtRatio: Float, // Terminal velocity / fresh baseline velocity at muscular failure (0 RIR)
        val isConcentricFirst: Boolean,
        val minPhysicalMps: Float = 0.08f,
        val maxPhysicalMps: Float = 2.40f
    )

    companion object {
        private val SPECS: Map<ExerciseType, ExerciseVbtSpec> = mapOf(
            ExerciseType.SQUAT to ExerciseVbtSpec(nominalRomMeters = 0.46f, mvtRatio = 0.50f, isConcentricFirst = false),
            ExerciseType.PUSHUP to ExerciseVbtSpec(nominalRomMeters = 0.34f, mvtRatio = 0.46f, isConcentricFirst = false),
            ExerciseType.LUNGE to ExerciseVbtSpec(nominalRomMeters = 0.38f, mvtRatio = 0.50f, isConcentricFirst = false),
            ExerciseType.BULGARIAN_SQUAT to ExerciseVbtSpec(nominalRomMeters = 0.40f, mvtRatio = 0.50f, isConcentricFirst = false),
            ExerciseType.DEADLIFT to ExerciseVbtSpec(nominalRomMeters = 0.48f, mvtRatio = 0.52f, isConcentricFirst = false),
            ExerciseType.OVERHEAD_PRESS to ExerciseVbtSpec(nominalRomMeters = 0.45f, mvtRatio = 0.45f, isConcentricFirst = true),
            ExerciseType.BICEP_CURL to ExerciseVbtSpec(nominalRomMeters = 0.38f, mvtRatio = 0.47f, isConcentricFirst = true),
            ExerciseType.TRICEP_DIP to ExerciseVbtSpec(nominalRomMeters = 0.34f, mvtRatio = 0.46f, isConcentricFirst = false),
            ExerciseType.PLANK to ExerciseVbtSpec(nominalRomMeters = 0.0f, mvtRatio = 0.50f, isConcentricFirst = false),
            ExerciseType.BURPEE to ExerciseVbtSpec(nominalRomMeters = 0.78f, mvtRatio = 0.52f, isConcentricFirst = false),
            ExerciseType.JUMPING_JACK to ExerciseVbtSpec(nominalRomMeters = 0.55f, mvtRatio = 0.52f, isConcentricFirst = true),
            ExerciseType.PULL_UP to ExerciseVbtSpec(nominalRomMeters = 0.52f, mvtRatio = 0.44f, isConcentricFirst = true),
            ExerciseType.BENT_ROW to ExerciseVbtSpec(nominalRomMeters = 0.36f, mvtRatio = 0.47f, isConcentricFirst = true),
            ExerciseType.GLUTE_BRIDGE to ExerciseVbtSpec(nominalRomMeters = 0.24f, mvtRatio = 0.48f, isConcentricFirst = true),
            ExerciseType.LEG_RAISE to ExerciseVbtSpec(nominalRomMeters = 0.62f, mvtRatio = 0.48f, isConcentricFirst = true),
            ExerciseType.LATERAL_RAISE to ExerciseVbtSpec(nominalRomMeters = 0.44f, mvtRatio = 0.48f, isConcentricFirst = true)
        )

        fun specFor(exercise: ExerciseType): ExerciseVbtSpec =
            SPECS[exercise] ?: ExerciseVbtSpec(0.40f, 0.48f, isConcentricFirst = false)
    }

    private data class ExerciseSessionHistory(
        var freshBaselineMps: Float = 0f,
        var freshBestEstMaxReps: Int? = null,
        var lastKnownSlope: Float = 0f,
        var nextSetNumber: Int = 1,
        val completedSets: MutableList<SetVelocitySummary> = mutableListOf()
    )

    private var exercise: ExerciseType = initialExercise
    private val historyByExercise = linkedMapOf<ExerciseType, ExerciseSessionHistory>()

    private val currentSetSamples = mutableListOf<RepVelocitySample>()
    private var currentSetBaselineMps: Float = 0f
    private var liveVelocityMps: Float = 0f
    private var dampedEstimatedMaxReps: Float? = null
    private var latestTelemetry: VelocityTelemetry = buildTelemetry()

    fun currentTelemetry(): VelocityTelemetry = latestTelemetry

    fun setExercise(newExercise: ExerciseType, clearSessionHistory: Boolean = false) {
        if (clearSessionHistory) {
            historyByExercise.clear()
        }
        exercise = newExercise
        clearCurrentSetOnly()
        latestTelemetry = buildTelemetry()
    }

    fun resetSession() {
        historyByExercise.clear()
        clearCurrentSetOnly()
        latestTelemetry = buildTelemetry()
    }

    /**
     * Archives the current set's velocity summary and advances to the next set while preserving
     * fresh session baseline velocity so Set 2+ automatically track cumulative fatigue & readiness.
     */
    fun completeSet(): SetVelocitySummary? {
        if (exercise.isIsometric || currentSetSamples.isEmpty()) {
            clearCurrentSetOnly()
            latestTelemetry = buildTelemetry()
            return null
        }

        val hist = historyFor(exercise)
        val snap = latestTelemetry
        val avgV = currentSetSamples.map { it.meanConcentricVelocityMps }.average().toFloat()
        val summary = SetVelocitySummary(
            setNumber = hist.nextSetNumber,
            exercise = exercise,
            repCount = currentSetSamples.size,
            baselineVelocityMps = currentSetBaselineMps,
            lastVelocityMps = currentSetSamples.last().meanConcentricVelocityMps,
            avgVelocityMps = avgV,
            velocityLossPct = snap.velocityLossPct,
            estimatedMaxReps = snap.estimatedMaxReps,
            estimatedRir = snap.estimatedRir
        )
        hist.completedSets.add(summary)
        hist.nextSetNumber++

        if (currentSetBaselineMps > hist.freshBaselineMps) {
            hist.freshBaselineMps = currentSetBaselineMps
        }
        if (snap.speedTrendSlopeMpsPerRep < -0.004f) {
            hist.lastKnownSlope = snap.speedTrendSlopeMpsPerRep
        }
        val freshCandidate = snap.estimatedFreshMaxReps ?: snap.estimatedMaxReps
        if (freshCandidate != null && (hist.freshBestEstMaxReps == null || snap.confidencePct >= 55)) {
            hist.freshBestEstMaxReps = freshCandidate
        }

        clearCurrentSetOnly()
        latestTelemetry = buildTelemetry()
        return summary
    }

    fun updateLiveVelocity(instantVelocityMps: Float) {
        if (exercise.isIsometric) {
            liveVelocityMps = 0f
            return
        }
        val spec = specFor(exercise)
        val clamped = if (instantVelocityMps.isFinite() && instantVelocityMps > 0f) {
            instantVelocityMps.coerceIn(0f, spec.maxPhysicalMps)
        } else {
            0f
        }
        liveVelocityMps = if (clamped == 0f) {
            liveVelocityMps * 0.65f
        } else {
            liveVelocityMps * 0.55f + clamped * 0.45f
        }
        if (liveVelocityMps < 0.02f) liveVelocityMps = 0f
        latestTelemetry = latestTelemetry.copy(currentLiveVelocityMps = liveVelocityMps)
    }

    /**
     * Records a completed dynamic repetition's concentric execution velocity and updates
     * the live fatigue curve and estimated max reps.
     */
    fun recordRep(
        concentricDurationSec: Float,
        eccentricDurationSec: Float,
        totalDurationSec: Float,
        measuredDisplacementMeters: Float,
        peakInstantVelocityMps: Float,
        romPct: Int,
        timestampMs: Long
    ): VelocityTelemetry {
        if (exercise.isIsometric) {
            latestTelemetry = buildTelemetry()
            return latestTelemetry
        }

        val spec = specFor(exercise)
        val safeConcSec = concentricDurationSec.coerceIn(0.18f, 8.0f)
        val safeEccSec = eccentricDurationSec.coerceIn(0.15f, 10.0f)
        val safeTotalSec = totalDurationSec.coerceIn(0.35f, 14.0f)
        val romFrac = (romPct.coerceIn(35, 100) / 100f)

        // Blend measured metric displacement with ROM-calibrated physiological displacement
        // so 3D depth noise or 2D foreshortening never injects bogus speed spikes.
        val expectedDisp = spec.nominalRomMeters * romFrac
        val effectiveDisp = if (measuredDisplacementMeters.isFinite() && measuredDisplacementMeters > 0.04f) {
            val boundedMeasured = measuredDisplacementMeters.coerceIn(expectedDisp * 0.55f, expectedDisp * 1.55f)
            boundedMeasured * 0.65f + expectedDisp * 0.35f
        } else {
            expectedDisp
        }

        val meanVel = (effectiveDisp / safeConcSec).coerceIn(spec.minPhysicalMps, spec.maxPhysicalMps)
        val peakVel = if (peakInstantVelocityMps.isFinite() && peakInstantVelocityMps > meanVel) {
            peakInstantVelocityMps.coerceIn(meanVel, minOf(spec.maxPhysicalMps, meanVel * 1.85f))
        } else {
            (meanVel * 1.28f).coerceAtMost(spec.maxPhysicalMps)
        }

        val hist = historyFor(exercise)
        val repNum = currentSetSamples.size + 1

        // Establish set baseline across first 1..3 reps (handles rep-1 groove-finding / potentiation)
        if (repNum == 1) {
            currentSetBaselineMps = meanVel
        } else if (repNum <= 3 && meanVel > currentSetBaselineMps) {
            currentSetBaselineMps = meanVel
        } else if (currentSetBaselineMps <= 0f) {
            currentSetBaselineMps = meanVel
        }

        if (currentSetBaselineMps > hist.freshBaselineMps) {
            hist.freshBaselineMps = currentSetBaselineMps
        }

        val refBase = maxOf(currentSetBaselineMps, 0.05f)
        val repVlPct = (((refBase - meanVel) / refBase) * 100f).roundToInt().coerceIn(0, 100)

        currentSetSamples.add(
            RepVelocitySample(
                repNumber = repNum,
                setNumber = hist.nextSetNumber,
                meanConcentricVelocityMps = meanVel,
                peakConcentricVelocityMps = peakVel,
                concentricDurationSec = safeConcSec,
                eccentricDurationSec = safeEccSec,
                totalDurationSec = safeTotalSec,
                romPct = romPct.coerceIn(0, 100),
                velocityLossPct = repVlPct,
                timestampMs = timestampMs
            )
        )

        liveVelocityMps = meanVel
        latestTelemetry = buildTelemetry()
        return latestTelemetry
    }

    private fun clearCurrentSetOnly() {
        currentSetSamples.clear()
        currentSetBaselineMps = 0f
        liveVelocityMps = 0f
        dampedEstimatedMaxReps = null
    }

    private fun historyFor(ex: ExerciseType): ExerciseSessionHistory =
        historyByExercise.getOrPut(ex) { ExerciseSessionHistory() }

    private fun buildTelemetry(): VelocityTelemetry {
        if (exercise.isIsometric) {
            return VelocityTelemetry(
                isSupported = false,
                fatigueZone = VelocityFatigueZone.OPTIMAL,
                statusFr = "Exercice isométrique · Chrono actif"
            )
        }

        val spec = specFor(exercise)
        val hist = historyFor(exercise)
        val n = currentSetSamples.size
        val freshBase = maxOf(hist.freshBaselineMps, currentSetBaselineMps)
        val readinessPct = if (freshBase > 0.05f && currentSetBaselineMps > 0.05f) {
            ((currentSetBaselineMps / freshBase) * 100f).roundToInt().coerceIn(45, 110)
        } else {
            100
        }

        if (n == 0) {
            val status = if (hist.completedSets.isNotEmpty() && hist.freshBestEstMaxReps != null) {
                "Série ${hist.nextSetNumber} · Max réf. ~${hist.freshBestEstMaxReps} reps"
            } else {
                "Calibrage vitesse (3 reps)…"
            }
            return VelocityTelemetry(
                isSupported = true,
                currentLiveVelocityMps = liveVelocityMps,
                sessionFreshBaselineMps = freshBase,
                readinessPct = 100,
                estimatedFreshMaxReps = hist.freshBestEstMaxReps,
                fatigueZone = VelocityFatigueZone.CALIBRATING,
                statusFr = status,
                completedSets = hist.completedSets.toList()
            )
        }

        val velocities = currentSetSamples.map { it.meanConcentricVelocityMps }
        val lastV = velocities.last()
        val smoothedCurrentV = if (n >= 2) {
            lastV * 0.68f + velocities[n - 2] * 0.32f
        } else {
            lastV
        }

        // Intra-set velocity loss relative to set baseline
        val setBase = maxOf(currentSetBaselineMps, 0.05f)
        val setVlFraction = ((setBase - smoothedCurrentV) / setBase).coerceIn(0f, 1f)
        val setVlPct = (setVlFraction * 100f).roundToInt().coerceIn(0, 100)

        // Terminal failure velocity is anchored to fresh baseline so Set 2/3 cumulative fatigue
        // naturally reduces remaining reps before hitting neuromuscular failure!
        val effectiveAnchorBase = if (hist.completedSets.isNotEmpty() && freshBase > setBase) {
            freshBase * 0.70f + setBase * 0.30f
        } else {
            setBase
        }
        val vFail = (effectiveAnchorBase * spec.mvtRatio).coerceAtLeast(spec.minPhysicalMps)
        val totalVlToFailSpan = (1f - spec.mvtRatio).coerceAtLeast(0.25f)
        val effectiveVlFromAnchor = ((effectiveAnchorBase - smoothedCurrentV) / effectiveAnchorBase).coerceIn(0f, 1f)
        val effectiveVlPct = (effectiveVlFromAnchor * 100f).roundToInt().coerceIn(0, 100)

        // Calibration phase (first 2 reps on Set 1, or rep 1 on Set 2+)
        val minRepsToEstimate = if (hist.completedSets.isNotEmpty() && hist.lastKnownSlope < -0.004f) 2 else 3
        if (n < minRepsToEstimate) {
            return VelocityTelemetry(
                isSupported = true,
                currentLiveVelocityMps = liveVelocityMps,
                lastRepVelocityMps = lastV,
                baselineVelocityMps = currentSetBaselineMps,
                sessionFreshBaselineMps = freshBase,
                readinessPct = readinessPct,
                velocityLossPct = setVlPct,
                estimatedFreshMaxReps = hist.freshBestEstMaxReps,
                confidencePct = 0,
                fatigueZone = VelocityFatigueZone.CALIBRATING,
                statusFr = "Calibrage vitesse ($n/$minRepsToEstimate reps)…",
                repVelocitiesMps = velocities,
                repSamples = currentSetSamples.toList(),
                completedSets = hist.completedSets.toList()
            )
        }

        // Weighted linear regression on velocities starting from peak baseline rep onward
        val peakIdx = velocities.take(minOf(3, n)).indices.maxByOrNull { velocities[it] } ?: 0
        val regSlice = velocities.subList(peakIdx, n)
        val (slope, rSquared) = if (regSlice.size >= 2) {
            weightedLinearRegression(regSlice)
        } else {
            hist.lastKnownSlope to 0.35f
        }

        val effectiveSlope = when {
            slope < -0.003f -> slope
            hist.completedSets.isNotEmpty() && hist.lastKnownSlope < -0.004f && setVlPct >= 8 ->
                hist.lastKnownSlope * 0.55f + slope * 0.45f
            else -> slope
        }

        val vlRatio = (effectiveVlFromAnchor / totalVlToFailSpan).coerceIn(0f, 1.35f)

        val rawEstMax: Float
        val confidence: Int
        val fatigueZone: VelocityFatigueZone
        val statusFr: String

        when {
            // 1. At or beyond failure terminal velocity
            smoothedCurrentV <= vFail * 1.04f || vlRatio >= 0.96f -> {
                val extra = if (smoothedCurrentV > vFail) 1f else 0f
                rawEstMax = n + extra
                confidence = (86 + (rSquared * 10f).roundToInt()).coerceIn(84, 96)
                fatigueZone = VelocityFatigueZone.NEAR_FAILURE
                statusFr = "Échec imminent · RIR ${extra.roundToInt()}"
            }

            // 2. Measurable progressive fatigue deceleration
            effectiveVlPct >= 7 || effectiveSlope < -0.006f -> {
                // Model A: Weighted OLS extrapolation to vFail
                val estFromReg: Float? = if (effectiveSlope < -0.0045f) {
                    val remaining = ((smoothedCurrentV - vFail) / (-effectiveSlope)).coerceIn(0f, 38f)
                    n + remaining
                } else {
                    null
                }

                // Model B: Sports-science empirical VL% completion curve
                // (Sánchez-Medina & González-Badillo: completion% ≈ (VL / VL_fail)^0.82)
                val estFromVl: Float? = if (vlRatio >= 0.10f) {
                    val completionFrac = vlRatio.coerceIn(0.10f, 0.97f).toDouble().pow(0.82).toFloat()
                    (n / completionFrac).coerceIn(n.toFloat(), n + 40f)
                } else {
                    null
                }

                rawEstMax = when {
                    estFromReg != null && estFromVl != null -> {
                        // Trust regression more when linearity R² is high and sample size >= 4
                        val regWeight = (0.48f + 0.22f * rSquared + if (n >= 5) 0.08f else 0f).coerceIn(0.45f, 0.72f)
                        estFromReg * regWeight + estFromVl * (1f - regWeight)
                    }
                    estFromReg != null -> estFromReg
                    estFromVl != null -> estFromVl
                    else -> n + 8f
                }

                val sampleFactor = ((n - 2).toFloat() / 6f).coerceIn(0.15f, 1f)
                val vlFactor = (vlRatio / 0.65f).coerceIn(0.20f, 1f)
                confidence = (42f + 22f * sampleFactor + 20f * vlFactor + 12f * rSquared)
                    .roundToInt()
                    .coerceIn(45, 95)

                val prelimRir = (rawEstMax.roundToInt() - n).coerceAtLeast(0)
                fatigueZone = when {
                    prelimRir <= 1 || effectiveVlPct >= 40 -> VelocityFatigueZone.NEAR_FAILURE
                    prelimRir <= 3 || effectiveVlPct >= 25 -> VelocityFatigueZone.FATIGUE
                    effectiveVlPct >= 10 -> VelocityFatigueZone.OPTIMAL
                    else -> VelocityFatigueZone.EXPLOSIVE
                }
                statusFr = when (fatigueZone) {
                    VelocityFatigueZone.NEAR_FAILURE -> "Zone limite · $prelimRir rep(s) en réserve"
                    VelocityFatigueZone.FATIGUE -> "Ralentissement -$effectiveVlPct% · Proche max"
                    VelocityFatigueZone.OPTIMAL -> "Fatigue optimale -$effectiveVlPct% · Bonne vitesse"
                    else -> "Explosif · Forte réserve"
                }
            }

            // 3. Athlete still maintaining explosive / flat speed (minimal fatigue yet)
            else -> {
                val conservativeExtra = (6 + (n / 2)).coerceIn(6, 15)
                rawEstMax = (n + conservativeExtra).toFloat()
                confidence = (32 + n * 3).coerceIn(35, 52)
                fatigueZone = VelocityFatigueZone.EXPLOSIVE
                statusFr = "Vitesse stable · Réserve élevée"
            }
        }

        // Smooth rep-to-rep estimate to avoid visual jumpiness while never under-shooting current reps
        val damped = if (dampedEstimatedMaxReps != null && n >= 4) {
            val prev = dampedEstimatedMaxReps!!
            // Allow quicker adaptation when fatigue accelerates sharply
            val alpha = if (fatigueZone == VelocityFatigueZone.NEAR_FAILURE) 0.78f else 0.64f
            (alpha * rawEstMax + (1f - alpha) * prev).coerceAtLeast(n.toFloat())
        } else {
            rawEstMax.coerceAtLeast(n.toFloat())
        }
        dampedEstimatedMaxReps = damped

        val estMaxInt = damped.roundToInt().coerceIn(n, n + 40)
        val rir = (estMaxInt - n).coerceAtLeast(0)
        val rpe = mapRirToRpe(rir)

        // Fresh max estimate scales current set max by readiness factor if athlete started Set 2+ fatigued
        val estFreshMax = if (hist.completedSets.isNotEmpty() && readinessPct in 55..94) {
            val scaled = (estMaxInt / (readinessPct / 100f)).roundToInt().coerceAtLeast(estMaxInt)
            val prevBest = hist.freshBestEstMaxReps
            if (prevBest != null) ((prevBest * 0.55f + scaled * 0.45f).roundToInt()) else scaled
        } else {
            estMaxInt
        }

        return VelocityTelemetry(
            isSupported = true,
            currentLiveVelocityMps = liveVelocityMps,
            lastRepVelocityMps = lastV,
            baselineVelocityMps = currentSetBaselineMps,
            sessionFreshBaselineMps = freshBase,
            readinessPct = readinessPct,
            velocityLossPct = effectiveVlPct,
            speedTrendSlopeMpsPerRep = effectiveSlope,
            estimatedMaxReps = estMaxInt,
            estimatedFreshMaxReps = estFreshMax,
            estimatedRir = rir,
            estimatedRpe = rpe,
            confidencePct = confidence,
            fatigueZone = fatigueZone,
            statusFr = statusFr,
            repVelocitiesMps = velocities,
            repSamples = currentSetSamples.toList(),
            completedSets = hist.completedSets.toList()
        )
    }

    /**
     * Weighted Ordinary Least Squares regression of y(x) for x = 0..m-1, giving higher weight
     * to more recent repetitions so late-set neuromuscular deceleration is captured promptly.
     * Returns (slopePerRep, rSquared).
     */
    private fun weightedLinearRegression(values: List<Float>): Pair<Float, Float> {
        val m = values.size
        if (m < 2) return 0f to 0f

        var sumW = 0.0
        var sumWX = 0.0
        var sumWY = 0.0
        for (i in 0 until m) {
            val w = 1.0 + 0.40 * (i.toDouble() / (m - 1).coerceAtLeast(1))
            val x = i.toDouble()
            val y = values[i].toDouble()
            sumW += w
            sumWX += w * x
            sumWY += w * y
        }
        val meanX = sumWX / sumW
        val meanY = sumWY / sumW

        var covXY = 0.0
        var varX = 0.0
        var varY = 0.0
        for (i in 0 until m) {
            val w = 1.0 + 0.40 * (i.toDouble() / (m - 1).coerceAtLeast(1))
            val dx = i.toDouble() - meanX
            val dy = values[i].toDouble() - meanY
            covXY += w * dx * dy
            varX += w * dx * dx
            varY += w * dy * dy
        }
        if (varX < 1e-6) return 0f to 0f
        val slope = (covXY / varX).toFloat()
        val rSq = if (varY > 1e-6) {
            ((covXY * covXY) / (varX * varY)).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        return slope to rSq
    }

    private fun mapRirToRpe(rir: Int): Float = when {
        rir <= 0 -> 10.0f
        rir == 1 -> 9.0f
        rir == 2 -> 8.0f
        rir == 3 -> 7.5f
        rir == 4 -> 7.0f
        rir == 5 -> 6.5f
        rir <= 7 -> 6.0f
        else -> 5.5f
    }
}
