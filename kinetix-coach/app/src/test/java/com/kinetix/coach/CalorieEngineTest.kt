package com.kinetix.coach

import com.kinetix.coach.data.AthleteProfile
import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.vision.CalorieEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalorieEngineTest {

    private val lightAthlete = AthleteProfile(
        name = "Léa",
        age = 26,
        sex = "Femme",
        weightKg = 55f,
        heightCm = 164,
        level = "Intermédiaire"
    )

    private val avgAthlete = AthleteProfile(
        name = "Alex",
        age = 28,
        sex = "Homme",
        weightKg = 75f,
        heightCm = 178,
        level = "Intermédiaire"
    )

    private val heavyAthlete = AthleteProfile(
        name = "Marc",
        age = 30,
        sex = "Homme",
        weightKg = 98f,
        heightCm = 188,
        level = "Intermédiaire"
    )

    @Test
    fun profileWeightAndMorphology_scalesCaloriesProportionally() {
        val lightKcal = CalorieEngine.estimateSetCalories(ExerciseType.SQUAT, reps = 12, activeDurationSec = 32, avgRomPct = 95, profile = lightAthlete)
        val avgKcal = CalorieEngine.estimateSetCalories(ExerciseType.SQUAT, reps = 12, activeDurationSec = 32, avgRomPct = 95, profile = avgAthlete)
        val heavyKcal = CalorieEngine.estimateSetCalories(ExerciseType.SQUAT, reps = 12, activeDurationSec = 32, avgRomPct = 95, profile = heavyAthlete)

        assertTrue("Expected heavy ($heavyKcal) > avg ($avgKcal) > light ($lightKcal)", heavyKcal > avgKcal && avgKcal > lightKcal)
        // Heavy (98kg) vs Light (55kg) ratio ~1.78 mass ratio -> kcal ratio should scale solidly (~1.6..2.1x)
        val ratio = heavyKcal / lightKcal
        assertTrue("Expected proportional mass scaling ratio in 1.55..2.20, got $ratio", ratio in 1.55..2.20)
    }

    @Test
    fun exerciseIntensityHierarchy_burpeesExceedSquatsExceedBicepCurls() {
        val burpees = CalorieEngine.estimateSetCalories(ExerciseType.BURPEE, reps = 10, activeDurationSec = 35, avgRomPct = 95, profile = avgAthlete)
        val jacks = CalorieEngine.estimateSetCalories(ExerciseType.JUMPING_JACK, reps = 20, activeDurationSec = 35, avgRomPct = 95, profile = avgAthlete)
        val squats = CalorieEngine.estimateSetCalories(ExerciseType.SQUAT, reps = 10, activeDurationSec = 35, avgRomPct = 95, profile = avgAthlete)
        val pushups = CalorieEngine.estimateSetCalories(ExerciseType.PUSHUP, reps = 10, activeDurationSec = 35, avgRomPct = 95, profile = avgAthlete)
        val curls = CalorieEngine.estimateSetCalories(ExerciseType.BICEP_CURL, reps = 10, activeDurationSec = 35, avgRomPct = 95, profile = avgAthlete)

        assertTrue("Burpees ($burpees) > Squats ($squats)", burpees > squats)
        assertTrue("Jumping Jacks ($jacks) > Curls ($curls)", jacks > curls)
        assertTrue("Squats ($squats) > Pushups ($pushups)", squats > pushups)
        assertTrue("Pushups ($pushups) > Bicep Curls ($curls)", pushups > curls)
    }

    @Test
    fun deeperRomBurnsStrictlyMoreThanShallowRom() {
        val deepSquats = CalorieEngine.estimateSetCalories(ExerciseType.SQUAT, reps = 10, activeDurationSec = 30, avgRomPct = 98, profile = avgAthlete)
        val shallowSquats = CalorieEngine.estimateSetCalories(ExerciseType.SQUAT, reps = 10, activeDurationSec = 30, avgRomPct = 45, profile = avgAthlete)

        assertTrue("Deep ROM ($deepSquats kcal) must exceed shallow ROM ($shallowSquats kcal)", deepSquats > shallowSquats * 1.25)
    }

    @Test
    fun incrementalRepsMatchFullSetAndAllSixteenExercisesValid() {
        // 10 identical incremental reps of 3.0s each should match 10-rep 30s set estimate
        var incrementalSum = 0.0
        repeat(10) {
            incrementalSum += CalorieEngine.estimateIncrementalRepCalories(
                exercise = ExerciseType.SQUAT,
                romPct = 90,
                repDurationSec = 3.0,
                profile = avgAthlete
            )
        }
        val fullSet = CalorieEngine.estimateSetCalories(
            exercise = ExerciseType.SQUAT,
            reps = 10,
            activeDurationSec = 30,
            avgRomPct = 90,
            profile = avgAthlete
        )
        assertEquals(fullSet, incrementalSum, 0.05)

        // Isometric 60s plank should be physiologically plausible (~4.0 - 6.5 kcal for 75kg athlete)
        val plank60s = CalorieEngine.estimateIsometricCalories(ExerciseType.PLANK, holdSec = 60.0, profile = avgAthlete)
        assertTrue("60s plank kcal should be in 3.5..7.0, got $plank60s", plank60s in 3.5..7.0)

        // Every single one of the 16 exercises must produce finite positive calories
        for (ex in ExerciseType.entries) {
            val kcal = if (ex.isIsometric) {
                CalorieEngine.estimateIsometricCalories(ex, 45.0, avgAthlete)
            } else {
                CalorieEngine.estimateSetCalories(ex, 10, 30, 88, avgAthlete)
            }
            assertFalse("NaN kcal for ${ex.name}", kcal.isNaN())
            assertFalse("Infinite kcal for ${ex.name}", kcal.isInfinite())
            assertTrue("Expected positive kcal for ${ex.name}, got $kcal", kcal > 0.5)
        }
    }

    @Test
    fun edgeCases_neverReturnNaNOrNegative() {
        assertEquals(0.0, CalorieEngine.estimateSetCalories(ExerciseType.SQUAT, reps = 0, activeDurationSec = 0, avgRomPct = 0, profile = avgAthlete), 0.0001)
        assertEquals(0.0, CalorieEngine.estimateIsometricCalories(ExerciseType.PLANK, holdSec = -10.0, profile = avgAthlete), 0.0001)
        assertEquals(0.0, CalorieEngine.estimateIsometricCalories(ExerciseType.PLANK, holdSec = Double.NaN, profile = avgAthlete), 0.0001)

        val extremeProfile = AthleteProfile(age = 0, weightKg = -50f, heightCm = 0, sex = "Autre")
        val safeKcal = CalorieEngine.estimateIncrementalRepCalories(ExerciseType.DEADLIFT, romPct = -20, repDurationSec = Double.NaN, profile = extremeProfile)
        assertTrue("Extreme inputs must still produce safe finite non-negative kcal", safeKcal.isFinite() && safeKcal >= 0.0)
    }
}
