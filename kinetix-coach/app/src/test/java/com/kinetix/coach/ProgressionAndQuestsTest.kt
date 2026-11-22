package com.kinetix.coach

import com.kinetix.coach.data.AthleteProfile
import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.WorkoutSession
import com.kinetix.coach.progression.AthleteProgressState
import com.kinetix.coach.progression.AthleteRank
import com.kinetix.coach.progression.DailyQuest
import com.kinetix.coach.progression.MeritBadge
import com.kinetix.coach.progression.MeritTitle
import com.kinetix.coach.progression.ProceduralQuestEngine
import com.kinetix.coach.progression.RpgProgressionEngine
import com.kinetix.coach.rag.RagQueryProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive JVM unit tests verifying KINETIX Coach's Athlete Progression Engine,
 * Deterministic Procedural Daily Quests, Zero-Penalty Midnight Rollover, Idempotent Step
 * Tracking, Camera-Verified Rank Exams (E -> S), Merit Badges & Titles, and Coach RAG injection.
 */
class ProgressionAndQuestsTest {

    @Test
    fun proceduralQuests_deterministicPerDateAndRank_andRespectsBodyweightEquipment() {
        val day1A = ProceduralQuestEngine.generateDailyQuests("2026-11-22", AthleteRank.E, "Salle complète")
        val day1B = ProceduralQuestEngine.generateDailyQuests("2026-11-22", AthleteRank.E, "Salle complète")
        val day2 = ProceduralQuestEngine.generateDailyQuests("2026-11-23", AthleteRank.E, "Salle complète")

        assertEquals(3, day1A.size)
        assertEquals("Same date + rank must produce identical quests", day1A, day1B)
        assertNotEquals("Different dates should vary quest generation", day1A.map { it.titleFr }, day2.map { it.titleFr })

        // Verify bodyweight-only filter across 30 different dates never emits barbell/dumbbell exercises
        val weightedOnly = setOf(
            ExerciseType.DEADLIFT,
            ExerciseType.OVERHEAD_PRESS,
            ExerciseType.BENT_ROW,
            ExerciseType.BICEP_CURL,
            ExerciseType.LATERAL_RAISE
        )
        for (d in 1..30) {
            val dateKey = "2026-10-%02d".format(d)
            val quests = ProceduralQuestEngine.generateDailyQuests(dateKey, AthleteRank.C, "Poids du corps")
            assertEquals(3, quests.size)
            for (q in quests) {
                assertFalse(
                    "Bodyweight-only athlete must not receive weighted exercise ${q.exerciseType}",
                    q.exerciseType in weightedOnly
                )
            }
        }
    }

    @Test
    fun proceduralQuests_volumeAndXpScaleMonotonicallyFromRankEToS() {
        val date = "2026-11-22"
        val rankList = AthleteRank.entries
        val xpSums = rankList.map { rank ->
            ProceduralQuestEngine.generateDailyQuests(date, rank, "Poids du corps").sumOf { it.xpReward }
        }
        for (i in 0 until xpSums.lastIndex) {
            assertTrue(
                "Total daily quest XP should increase from ${rankList[i]} (${xpSums[i]}) to ${rankList[i + 1]} (${xpSums[i + 1]})",
                xpSums[i + 1] > xpSums[i]
            )
        }
    }

    @Test
    fun levelCurve_computesContinuousLevelsAndHandlesBoundaries() {
        // Level 1 needs 150 XP -> Level 2 needs 225 XP (total 375 for Level 3)
        assertEquals(Triple(1, 0, 150), RpgProgressionEngine.computeLevelFromTotalXp(0))
        assertEquals(Triple(1, 149, 150), RpgProgressionEngine.computeLevelFromTotalXp(149))
        assertEquals(Triple(2, 0, 225), RpgProgressionEngine.computeLevelFromTotalXp(150))
        assertEquals(Triple(2, 224, 225), RpgProgressionEngine.computeLevelFromTotalXp(374))
        assertEquals(Triple(3, 0, 300), RpgProgressionEngine.computeLevelFromTotalXp(375))

        // Negative XP safely clamps to Level 1 / 0 XP
        assertEquals(Triple(1, 0, 150), RpgProgressionEngine.computeLevelFromTotalXp(-50))

        // Max cap at level 99 without infinite loop
        val max = RpgProgressionEngine.computeLevelFromTotalXp(50_000_000)
        assertEquals(99, max.first)
    }

    @Test
    fun workoutSession_awardsQualityWeightedXp_updatesPalmares_andAutoCompletesMatchingQuest() {
        val date = "2026-11-22"
        val pushupQuest = DailyQuest(
            id = "${date}_0",
            dateKey = date,
            titleFr = "Pompes · Diamant",
            subtitleFr = "20 reps · Vérifié caméra",
            exerciseType = ExerciseType.PUSHUP,
            modifierLabelFr = "Diamant",
            formatLabelFr = "20 reps cumulées",
            targetAmount = 20,
            currentProgress = 0,
            unitFr = "reps",
            xpReward = 60,
            attributeKey = "force",
            completed = false
        )
        val initial = AthleteProgressState(
            todayDateKey = date,
            todayQuests = listOf(pushupQuest)
        )

        // Compare high-form + VBT session vs low-form session without VBT
        val highFormSession = WorkoutSession(
            timestampMs = 1_000L,
            exerciseType = ExerciseType.PUSHUP,
            reps = 25,
            durationSec = 45,
            avgFormScore = 94,
            avgRomPct = 90,
            topFaults = emptyList(),
            caloriesKcal = 18.0,
            peakVelocityMps = 0.65f,
            estimatedMaxReps = 30
        )
        val lowFormSession = highFormSession.copy(
            avgFormScore = 60,
            peakVelocityMps = 0f,
            estimatedMaxReps = 0
        )

        val (highState, highDelta) = RpgProgressionEngine.applyWorkoutSession(initial, highFormSession, currentDateKey = date)
        val (_, lowDelta) = RpgProgressionEngine.applyWorkoutSession(initial, lowFormSession, currentDateKey = date)

        assertTrue("High form + VBT must yield strictly more XP (${highDelta.xpGained} vs ${lowDelta.xpGained})", highDelta.xpGained > lowDelta.xpGained)
        assertEquals(25, highState.exerciseTotals[ExerciseType.PUSHUP.id])
        assertEquals(1, highState.totalWorkoutsCompleted)
        assertEquals(1, highState.totalQuestsCompleted)
        assertTrue(highState.todayQuests.first().completed)
        assertEquals(listOf("Pompes · Diamant"), highDelta.completedQuestTitles)
        assertTrue("First workout badge unlocked", MeritBadge.FIRST_WORKOUT in highDelta.newlyUnlockedBadges)
        assertTrue("Golden form badge unlocked (>=92%)", MeritBadge.GOLDEN_FORM in highDelta.newlyUnlockedBadges)
        assertTrue("Force attribute increased", highState.attributes.force > initial.attributes.force)
        assertEquals(1, highState.streakDays)
    }

    @Test
    fun workoutSession_multiExerciseBreakdownUpdatesAllTotalsAndAttributes() {
        val date = "2026-11-22"
        val base = RpgProgressionEngine.ensureUpToDateForDay(AthleteProgressState(), date)
        val session = WorkoutSession(
            timestampMs = 2_000L,
            exerciseType = ExerciseType.PUSHUP,
            reps = 40,
            durationSec = 120,
            avgFormScore = 88,
            avgRomPct = 86,
            topFaults = emptyList(),
            caloriesKcal = 25.0
        )
        val breakdown = listOf(
            Triple(ExerciseType.PUSHUP, 20, 0),
            Triple(ExerciseType.SQUAT, 20, 0),
            Triple(ExerciseType.PLANK, 0, 45)
        )
        val (updated, delta) = RpgProgressionEngine.applyWorkoutSession(
            currentState = base,
            session = session,
            perExerciseWork = breakdown,
            currentDateKey = date
        )

        assertTrue(delta.xpGained > 0)
        assertEquals(20, updated.exerciseTotals[ExerciseType.PUSHUP.id])
        assertEquals(20, updated.exerciseTotals[ExerciseType.SQUAT.id])
        assertEquals(45, updated.exerciseTotals[ExerciseType.PLANK.id])
        assertTrue(updated.attributes.force > base.attributes.force)
        assertTrue(updated.attributes.agility > base.attributes.agility)
        assertTrue(updated.attributes.endurance > base.attributes.endurance)
    }

    @Test
    fun rankExamGating_clampsRankXpUntilCameraExamPassed_andPromotesCleanly() {
        val date = "2026-11-22"
        val almostReady = RpgProgressionEngine.ensureUpToDateForDay(
            AthleteProgressState(rank = AthleteRank.E, rankXp = 470, totalXp = 470),
            date
        )
        assertFalse(almostReady.rankExamReady)

        // Workout pushes Rank XP over 500 -> MUST clamp at 500 & stay Rank E until camera exam passed!
        val session = WorkoutSession(
            timestampMs = 3_000L,
            exerciseType = ExerciseType.SQUAT,
            reps = 30,
            durationSec = 60,
            avgFormScore = 91,
            avgRomPct = 90,
            topFaults = emptyList()
        )
        val (readyState, _) = RpgProgressionEngine.applyWorkoutSession(almostReady, session, currentDateKey = date)
        assertEquals("Must NOT auto-promote without passing camera Rank Exam", AthleteRank.E, readyState.rank)
        assertEquals(500, readyState.rankXp)
        assertTrue("Rank Exam must now be unlocked", readyState.rankExamReady)

        // Passing the Rank D camera exam promotes E -> D and unlocks RANK_D badge!
        val exam = AthleteRank.E.rankExamSpec()
        assertNotNull(exam)
        val (promoted, examDelta) = RpgProgressionEngine.applyRankExamPassed(readyState, exam!!, currentDateKey = date)
        assertEquals(AthleteRank.D, promoted.rank)
        assertFalse(promoted.rankExamReady)
        assertEquals(AthleteRank.E, examDelta.rankedUpFrom)
        assertEquals(AthleteRank.D, examDelta.rankedUpTo)
        assertEquals(exam.xpBonus, examDelta.xpGained)
        assertTrue(MeritBadge.RANK_D in examDelta.newlyUnlockedBadges)

        // Re-applying the exact same exam must be a safe no-op
        val (repeatState, repeatDelta) = RpgProgressionEngine.applyRankExamPassed(promoted, exam, currentDateKey = date)
        assertEquals(promoted.rank, repeatState.rank)
        assertEquals(0, repeatDelta.xpGained)
        assertNull(repeatDelta.rankedUpTo)
    }

    @Test
    fun dailySteps_idempotentBuckets_10kMilestone_andWalkingQuestAutoCompletion() {
        val date = "2026-11-22"
        val walkQuest = DailyQuest(
            id = "${date}_2",
            dateKey = date,
            titleFr = "Marche Active",
            subtitleFr = "5 000 pas dans la journée",
            exerciseType = null,
            modifierLabelFr = "Endurance",
            formatLabelFr = "5 000 pas",
            targetAmount = 5_000,
            currentProgress = 0,
            unitFr = "pas",
            xpReward = 50,
            attributeKey = "vitality",
            completed = false
        )
        val base = AthleteProgressState(todayDateKey = date, todayQuests = listOf(walkQuest))

        // 2,500 steps -> 2 buckets (20 XP + 2 Vitality)
        val (s1, d1) = RpgProgressionEngine.applyDailySteps(base, 2_500, currentDateKey = date)
        assertEquals(20, d1.xpGained)
        assertEquals(2_500, s1.todaySteps)
        assertEquals(2, s1.todayStepBucketsAwarded)
        assertEquals(2_500L, s1.totalStepsLifetime)

        // Calling again with same or lower steps is strictly idempotent (0 XP, no double counting!)
        val (sDup, dDup) = RpgProgressionEngine.applyDailySteps(s1, 2_500, currentDateKey = date)
        assertEquals(0, dDup.xpGained)
        assertEquals(2_500L, sDup.totalStepsLifetime)

        // Reaching 5,200 steps -> +3 buckets (30 XP) + walking quest (50 XP) = 80 XP
        val (s2, d2) = RpgProgressionEngine.applyDailySteps(sDup, 5_200, currentDateKey = date)
        assertEquals(80, d2.xpGained)
        assertTrue(s2.todayQuests.first().completed)
        assertEquals(listOf("Marche Active"), d2.completedQuestTitles)

        // Reaching 10,000 steps -> +5 buckets (50 XP) + 10k bonus (100 XP) = 150 XP + badge + title
        val (s3, d3) = RpgProgressionEngine.applyDailySteps(s2, 10_000, currentDateKey = date)
        assertEquals(150, d3.xpGained)
        assertTrue(d3.stepGoal10kAwarded)
        assertTrue(s3.todayStepGoalRewarded)
        assertTrue(MeritBadge.STEPS_10K in d3.newlyUnlockedBadges)
        assertTrue(MeritTitle.ENDURANT_10K in d3.newlyUnlockedTitles)
        assertEquals(10_000L, s3.totalStepsLifetime)
    }

    @Test
    fun midnightRollover_zeroPenalty_archivesSteps_dealsFreshQuests_andResetsStreakGracefully() {
        val day1 = "2026-11-20"
        val day2 = "2026-11-21"
        val day4 = "2026-11-23" // 2 rest days gap

        val initial = RpgProgressionEngine.ensureUpToDateForDay(AthleteProgressState(), day1)
        val (afterSteps, _) = RpgProgressionEngine.applyDailySteps(initial, 6_400, currentDateKey = day1)
        val workout = WorkoutSession(
            timestampMs = 4_000L,
            exerciseType = ExerciseType.SQUAT,
            reps = 20,
            durationSec = 40,
            avgFormScore = 88,
            avgRomPct = 85,
            topFaults = emptyList()
        )
        val (endDay1, _) = RpgProgressionEngine.applyWorkoutSession(afterSteps, workout, currentDateKey = day1)
        val xpEndDay1 = endDay1.totalXp
        assertEquals(1, endDay1.streakDays)

        // Rollover to consecutive Day 2: ZERO XP lost, yesterday's 6,400 steps archived, streak preserved!
        val startDay2 = RpgProgressionEngine.ensureUpToDateForDay(endDay1, day2)
        assertEquals("STRICTLY ZERO XP PENALTY on rollover", xpEndDay1, startDay2.totalXp)
        assertEquals(0, startDay2.todaySteps)
        assertEquals(1, startDay2.streakDays)
        assertEquals(7, startDay2.recentDays.size)
        val archivedDay1 = startDay2.recentDays.firstOrNull { it.dateKey == day1 }
        assertNotNull(archivedDay1)
        assertEquals(6_400, archivedDay1!!.steps)
        assertTrue(archivedDay1.workoutDone)
        assertEquals(day2, startDay2.todayQuests.first().dateKey)

        // Rollover after 2+ rest days (Day 4): streak resets gracefully to 0, STILL ZERO XP PENALTY!
        val startDay4 = RpgProgressionEngine.ensureUpToDateForDay(startDay2, day4)
        assertEquals("STRICTLY ZERO XP PENALTY after rest gap", xpEndDay1, startDay4.totalXp)
        assertEquals(0, startDay4.streakDays)
    }

    @Test
    fun sevenDayStreak_awardsWeeklyBonusXp_andUnlocksMeritBadgeAndTitle() {
        var state = AthleteProgressState()
        val workout = WorkoutSession(
            timestampMs = 5_000L,
            exerciseType = ExerciseType.SQUAT,
            reps = 15,
            durationSec = 30,
            avgFormScore = 85,
            avgRomPct = 82,
            topFaults = emptyList()
        )

        var day7Delta = com.kinetix.coach.progression.ProgressionDelta()
        for (day in 1..7) {
            val dateKey = "2026-11-%02d".format(day)
            val (nextState, delta) = RpgProgressionEngine.applyWorkoutSession(state, workout, currentDateKey = dateKey)
            state = nextState
            if (day == 7) day7Delta = delta
        }

        assertEquals(7, state.streakDays)
        assertEquals(7, state.longestStreak)
        assertTrue("Weekly 7/7 bonus XP must trigger on day 7", day7Delta.weeklyStreakBonusAwarded)
        assertTrue(MeritBadge.STREAK_7.id in state.unlockedBadgeIds)
        assertTrue(MeritTitle.INARRETABLE.id in state.unlockedTitleIds)
        assertTrue(MeritTitle.REGULIER.id in state.unlockedTitleIds)
    }

    @Test
    fun meritTitles_cannotEquipLockedTitle_andEquipsCleanlyOnceUnlocked() {
        val date = "2026-11-22"
        val base = RpgProgressionEngine.ensureUpToDateForDay(AthleteProgressState(), date)

        // Cannot equip locked title
        val rejected = RpgProgressionEngine.equipTitle(base, MeritTitle.FORCE_PURE.id)
        assertEquals(MeritTitle.ATHLETE_KINETIX.id, rejected.equippedTitleId)

        // Perform 250 push-ups -> unlocks FORCE_PURE
        val bigPushupSession = WorkoutSession(
            timestampMs = 6_000L,
            exerciseType = ExerciseType.PUSHUP,
            reps = 250,
            durationSec = 300,
            avgFormScore = 90,
            avgRomPct = 88,
            topFaults = emptyList()
        )
        val (unlockedState, delta) = RpgProgressionEngine.applyWorkoutSession(base, bigPushupSession, currentDateKey = date)
        assertTrue(MeritTitle.FORCE_PURE in delta.newlyUnlockedTitles)

        val equipped = RpgProgressionEngine.equipTitle(unlockedState, MeritTitle.FORCE_PURE.id)
        assertEquals(MeritTitle.FORCE_PURE.id, equipped.equippedTitleId)
        assertEquals("Force Pure", equipped.equippedTitle.labelFr)
    }

    @Test
    fun manualQuestCompletion_isIdempotent_andUpdatesPalmares() {
        val date = "2026-11-22"
        val base = RpgProgressionEngine.ensureUpToDateForDay(AthleteProgressState(), date)
        val firstQuest = base.todayQuests.first()

        val (doneState, delta1) = RpgProgressionEngine.completeQuestManually(base, firstQuest.id, currentDateKey = date)
        assertEquals(firstQuest.xpReward, delta1.xpGained)
        assertTrue(doneState.todayQuests.first().completed)
        assertEquals(1, doneState.totalQuestsCompleted)
        if (firstQuest.exerciseType != null) {
            assertEquals(firstQuest.targetAmount, doneState.exerciseTotals[firstQuest.exerciseType.id])
        }

        // Calling a second time on already-completed quest must be a safe no-op
        val (repeatState, delta2) = RpgProgressionEngine.completeQuestManually(doneState, firstQuest.id, currentDateKey = date)
        assertEquals(0, delta2.xpGained)
        assertEquals(doneState.totalXp, repeatState.totalXp)
        assertEquals(1, repeatState.totalQuestsCompleted)
    }

    @Test
    fun coachRagContext_injectsProgressionSummaryCleanly() {
        val date = "2026-11-22"
        val base = RpgProgressionEngine.ensureUpToDateForDay(
            AthleteProgressState(rank = AthleteRank.C, totalXp = 2_000, streakDays = 5, lastActiveDateKey = "2026-11-21"),
            date
        )
        val (state, _) = RpgProgressionEngine.applyDailySteps(base, 7_200, currentDateKey = date)
        val contextLine = state.toCoachContextLine()
        assertTrue(contextLine.contains("Rang C"))
        assertTrue(contextLine.contains("7200/10000"))

        val prompt = RagQueryProcessor.buildCoachSystemAndUserMessage(
            userQuestion = "Quelle séance faire aujourd'hui ?",
            profile = AthleteProfile(name = "Alex"),
            ragHits = emptyList(),
            recentHistory = emptyList(),
            progressionContext = contextLine
        )
        assertTrue(prompt.contains("STATUT PROGRESSION :"))
        assertTrue(prompt.contains("Rang C"))
    }
}
