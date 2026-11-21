package com.kinetix.coach.vision

import com.kinetix.coach.data.AthleteProfile
import com.kinetix.coach.data.ExerciseType
import kotlin.math.max

/**
 * Profile-calibrated physiological calorie expenditure engine for all 16 KINETIX exercises.
 * Combines Mifflin-St Jeor personal RMR -> personal resting VO2 -> Ainsworth Compendium
 * Corrected-MET intensity scaling with a biomechanics mechanical work term (m * g * deltaH / eta).
 */
object CalorieEngine {

    private const val GRAVITY = 9.80665
    private const val MUSCULAR_EFFICIENCY = 0.24
    private const val JOULES_PER_KCAL = 4184.0
    private const val STANDARD_ONE_MET_VO2 = 3.5

    data class ExerciseMetabolicSpec(
        val standardMet: Double,
        val baseDeltaHMeters: Double,
        val bodyMassFraction: Double,
        val nominalRepSec: Double
    )

    private val SPECS: Map<ExerciseType, ExerciseMetabolicSpec> = mapOf(
        ExerciseType.BURPEE to ExerciseMetabolicSpec(9.5, 0.72, 1.00, 3.2),
        ExerciseType.JUMPING_JACK to ExerciseMetabolicSpec(8.0, 0.22, 1.00, 1.1),
        ExerciseType.PULL_UP to ExerciseMetabolicSpec(7.5, 0.52, 0.95, 2.8),
        ExerciseType.DEADLIFT to ExerciseMetabolicSpec(6.5, 0.44, 0.80, 3.0),
        ExerciseType.SQUAT to ExerciseMetabolicSpec(6.2, 0.45, 0.82, 2.6),
        ExerciseType.BULGARIAN_SQUAT to ExerciseMetabolicSpec(6.0, 0.42, 0.80, 2.7),
        ExerciseType.LUNGE to ExerciseMetabolicSpec(5.8, 0.38, 0.80, 2.5),
        ExerciseType.TRICEP_DIP to ExerciseMetabolicSpec(5.8, 0.34, 0.85, 2.4),
        ExerciseType.PUSHUP to ExerciseMetabolicSpec(5.5, 0.30, 0.68, 2.2),
        ExerciseType.BENT_ROW to ExerciseMetabolicSpec(5.2, 0.28, 0.35, 2.4),
        ExerciseType.OVERHEAD_PRESS to ExerciseMetabolicSpec(5.0, 0.35, 0.30, 2.5),
        ExerciseType.GLUTE_BRIDGE to ExerciseMetabolicSpec(4.2, 0.22, 0.55, 2.4),
        ExerciseType.LEG_RAISE to ExerciseMetabolicSpec(4.2, 0.32, 0.34, 2.6),
        ExerciseType.PLANK to ExerciseMetabolicSpec(4.0, 0.00, 0.00, 1.0),
        ExerciseType.LATERAL_RAISE to ExerciseMetabolicSpec(3.6, 0.24, 0.14, 2.3),
        ExerciseType.BICEP_CURL to ExerciseMetabolicSpec(3.5, 0.22, 0.12, 2.2)
    )

    fun specFor(exercise: ExerciseType): ExerciseMetabolicSpec =
        SPECS[exercise] ?: ExerciseMetabolicSpec(5.0, 0.30, 0.50, 2.5)

    /** Mifflin-St Jeor Resting Metabolic Rate in kcal / day. */
    fun computePersonalRmrKcalPerDay(profile: AthleteProfile): Double {
        val w = profile.weightKg.toDouble().coerceIn(35.0, 220.0)
        val h = profile.heightCm.toDouble().coerceIn(120.0, 230.0)
        val a = profile.age.toDouble().coerceIn(13.0, 95.0)
        val sexOffset = if (profile.sex.equals("Femme", ignoreCase = true)) -161.0 else 5.0
        return (10.0 * w + 6.25 * h - 5.0 * a + sexOffset).coerceAtLeast(1000.0)
    }

    /** Personal resting oxygen uptake in ml O2 / kg / min derived from Mifflin-St Jeor RMR. */
    fun computePersonalRestingVo2(profile: AthleteProfile): Double {
        val w = profile.weightKg.toDouble().coerceIn(35.0, 220.0)
        val rmrKcalPerMin = computePersonalRmrKcalPerDay(profile) / 1440.0
        // 1 L O2 ≈ 5.0 kcal -> resting ml O2 / kg / min
        return (((rmrKcalPerMin / 5.0) * 1000.0) / w).coerceIn(2.0, 5.5)
    }

    /** Ainsworth Compendium Corrected-MET calibrated to the athlete's personal resting VO2. */
    fun computeCorrectedMet(exercise: ExerciseType, profile: AthleteProfile): Double {
        val spec = specFor(exercise)
        val personalVo2 = computePersonalRestingVo2(profile)
        return spec.standardMet * (STANDARD_ONE_MET_VO2 / personalVo2)
    }

    /**
     * Estimates calories burned for a single completed repetition given live ROM% and rep duration.
     */
    fun estimateIncrementalRepCalories(
        exercise: ExerciseType,
        romPct: Int,
        repDurationSec: Double,
        profile: AthleteProfile
    ): Double {
        if (exercise.isIsometric) {
            return estimateIsometricCalories(exercise, repDurationSec, profile)
        }
        val safeDurSec = if (repDurationSec.isFinite() && repDurationSec > 0.0) {
            repDurationSec.coerceIn(0.6, 12.0)
        } else {
            specFor(exercise).nominalRepSec
        }
        return computeDynamicCalories(
            exercise = exercise,
            reps = 1,
            activeDurationSec = safeDurSec,
            avgRomPct = romPct,
            profile = profile
        )
    }

    /**
     * Estimates calories burned across a full set (works for both dynamic and isometric exercises).
     */
    fun estimateSetCalories(
        exercise: ExerciseType,
        reps: Int,
        activeDurationSec: Int,
        avgRomPct: Int,
        profile: AthleteProfile
    ): Double {
        if (exercise.isIsometric) {
            return estimateIsometricCalories(exercise, activeDurationSec.toDouble(), profile)
        }
        if (reps <= 0 && activeDurationSec <= 0) return 0.0
        val safeReps = reps.coerceAtLeast(0)
        val effectiveDurSec = if (activeDurationSec > 0) {
            activeDurationSec.toDouble()
        } else {
            safeReps * specFor(exercise).nominalRepSec
        }
        return computeDynamicCalories(
            exercise = exercise,
            reps = safeReps,
            activeDurationSec = effectiveDurSec,
            avgRomPct = avgRomPct,
            profile = profile
        )
    }

    /**
     * Estimates calories burned during an isometric hold (e.g. Plank).
     */
    fun estimateIsometricCalories(
        exercise: ExerciseType,
        holdSec: Double,
        profile: AthleteProfile
    ): Double {
        if (!holdSec.isFinite() || holdSec <= 0.0) return 0.0
        val w = profile.weightKg.toDouble().coerceIn(35.0, 220.0)
        val spec = specFor(exercise)
        val restKcalPerMin = computePersonalRmrKcalPerDay(profile) / 1440.0
        val netKcalPerMin = max(0.5, spec.standardMet - 1.0) * (STANDARD_ONE_MET_VO2 * w / 200.0) * levelFactor(profile)
        val totalKcal = (restKcalPerMin + netKcalPerMin) * (holdSec / 60.0)
        return sanitize(totalKcal)
    }

    private fun computeDynamicCalories(
        exercise: ExerciseType,
        reps: Int,
        activeDurationSec: Double,
        avgRomPct: Int,
        profile: AthleteProfile
    ): Double {
        if (reps <= 0 && activeDurationSec <= 0.0) return 0.0

        val w = profile.weightKg.toDouble().coerceIn(35.0, 220.0)
        val h = profile.heightCm.toDouble().coerceIn(120.0, 230.0)
        val spec = specFor(exercise)

        val romFraction = (avgRomPct.coerceIn(10, 100)) / 100.0
        val romIntensity = 0.55 + 0.55 * romFraction

        val expectedDurSec = max(1.0, reps.coerceAtLeast(1) * spec.nominalRepSec)
        val cadenceFactor = (expectedDurSec / activeDurationSec.coerceAtLeast(0.5)).coerceIn(0.80, 1.30)

        // Personal resting rate + net MET expenditure calibrated to weight, ROM, cadence, and training level
        val restKcalPerMin = computePersonalRmrKcalPerDay(profile) / 1440.0
        val netKcalPerMin = max(0.5, spec.standardMet - 1.0) *
            (STANDARD_ONE_MET_VO2 * w / 200.0) *
            romIntensity *
            cadenceFactor *
            levelFactor(profile)
        val metabolicKcal = (restKcalPerMin + netKcalPerMin) * (activeDurationSec / 60.0)

        // Direct biomechanical work component: m * g * deltaH_com / 0.24 / 4184 per rep
        val heightScale = h / 175.0
        val deltaH = spec.baseDeltaHMeters * heightScale * romFraction
        val effectiveMassKg = w * spec.bodyMassFraction
        val mechWorkPerRepKcal = ((effectiveMassKg * GRAVITY * deltaH) / MUSCULAR_EFFICIENCY) / JOULES_PER_KCAL
        val totalMechKcal = mechWorkPerRepKcal * reps.coerceAtLeast(0)

        return sanitize(metabolicKcal * 0.78 + totalMechKcal)
    }

    private fun levelFactor(profile: AthleteProfile): Double = when {
        profile.level.contains("Débutant", ignoreCase = true) -> 1.04
        profile.level.contains("Avancé", ignoreCase = true) -> 0.99
        profile.level.contains("Compétiteur", ignoreCase = true) -> 1.02
        else -> 1.00
    }

    private fun sanitize(value: Double): Double =
        if (value.isFinite() && value > 0.0) value else 0.0
}
