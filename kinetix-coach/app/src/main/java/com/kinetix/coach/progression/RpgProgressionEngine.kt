package com.kinetix.coach.progression

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.WorkoutSession
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Pure-Kotlin, zero-penalty athletic progression engine.
 * Computes continuous Level & XP curves, Rank E->S milestones with camera-verified Rank Exam gating,
 * 5 real biomechanical attributes, merit Badges & Titles, 7-day Streak tracking, idempotent daily
 * step conversions (1000-step buckets + 10k bonus), and automatic camera-verified quest completion.
 */
object RpgProgressionEngine {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    const val STEP_BUCKET_SIZE = 1_000
    const val STEP_BUCKET_XP = 10
    const val STEP_GOAL_10K_XP = 100
    const val WEEKLY_STREAK_BONUS_XP = 150

    /** Smooth progressive level curve: Level 1->2 = 150 XP, Level 2->3 = 225 XP, etc. */
    fun xpNeededForLevel(level: Int): Int {
        val safe = level.coerceAtLeast(1)
        return 150 + (safe - 1) * 75
    }

    /** Derives (level, xpInCurrentLevel, xpToNextLevel) purely from cumulative [totalXp]. */
    fun computeLevelFromTotalXp(totalXp: Int): Triple<Int, Int, Int> {
        var remaining = totalXp.coerceAtLeast(0)
        var level = 1
        while (true) {
            val needed = xpNeededForLevel(level)
            if (remaining >= needed && level < 99) {
                remaining -= needed
                level++
            } else {
                return Triple(level, remaining, needed)
            }
        }
    }

    fun todayDateKey(): String = LocalDate.now().format(DATE_FMT)

    /**
     * Ensures [state] is cleanly synchronized with [currentDateKey]:
     * - Archives yesterday's steps/activity into [recentDays] (rolling 7-day window)
     * - Resets daily step counters & generates 3 fresh deterministic quests for [currentDateKey]
     * - Preserves streak if active yesterday or today; resets streak gracefully after a 2+ day gap
     * - STRICTLY ZERO XP PENALTIES OR PUNISHMENTS!
     */
    fun ensureUpToDateForDay(
        state: AthleteProgressState,
        currentDateKey: String,
        equipmentFr: String = "Salle complète"
    ): AthleteProgressState {
        val safeDate = runCatching { LocalDate.parse(currentDateKey, DATE_FMT) }
            .getOrElse { LocalDate.now() }
        val normalizedKey = safeDate.format(DATE_FMT)

        val (lvl, inLvl, toNext) = computeLevelFromTotalXp(state.totalXp)
        var s = state.copy(
            level = lvl,
            xpInCurrentLevel = inLvl,
            xpToNextLevel = toNext,
            rankExamReady = isRankExamReady(state.rank, state.rankXp)
        )

        if (s.todayDateKey == normalizedKey && s.todayQuests.isNotEmpty()) {
            val syncedDays = buildSevenDayWindow(s.recentDays, normalizedKey, s.todaySteps, s.todayStepGoalRewarded, isTodayWorkoutDone(s))
            return s.copy(recentDays = syncedDays)
        }

        // Day rollover (or first-ever launch)
        val mergedHistory = LinkedHashMap<String, DayActivityEntry>()
        for (entry in s.recentDays) {
            mergedHistory[entry.dateKey] = entry
        }
        if (s.todayDateKey.isNotBlank() && s.todayDateKey != normalizedKey) {
            val prev = mergedHistory[s.todayDateKey]
            mergedHistory[s.todayDateKey] = DayActivityEntry(
                dateKey = s.todayDateKey,
                steps = maxOf(prev?.steps ?: 0, s.todaySteps),
                goalReached = (prev?.goalReached == true) || s.todaySteps >= s.stepGoal,
                workoutDone = (prev?.workoutDone == true) || (s.lastActiveDateKey == s.todayDateKey)
            )
        }

        // Update streak gracefully (zero penalty!)
        val updatedStreak = computeStreakOnDayStart(
            lastActiveDateKey = s.lastActiveDateKey,
            currentStreak = s.streakDays,
            currentDate = safeDate
        )

        val freshQuests = ProceduralQuestEngine.generateDailyQuests(
            dateKey = normalizedKey,
            rank = s.rank,
            equipmentFr = equipmentFr
        )
        val sevenDays = buildSevenDayWindow(
            existing = mergedHistory.values.toList(),
            todayKey = normalizedKey,
            todaySteps = 0,
            todayGoalReached = false,
            todayWorkoutDone = (s.lastActiveDateKey == normalizedKey)
        )

        s = s.copy(
            todayDateKey = normalizedKey,
            todaySteps = 0,
            todayStepGoalRewarded = false,
            todayStepBucketsAwarded = 0,
            streakDays = updatedStreak,
            todayQuests = freshQuests,
            recentDays = sevenDays
        )
        return evaluateBadgesAndTitles(s).first
    }

    /**
     * Processes a completed camera workout session (single or multi-exercise) and returns
     * the updated [AthleteProgressState] alongside a rich [ProgressionDelta] for UI feedback.
     */
    fun applyWorkoutSession(
        currentState: AthleteProgressState,
        session: WorkoutSession,
        perExerciseWork: List<Triple<ExerciseType, Int, Int>> = emptyList(), // (exercise, reps, holdSec)
        currentDateKey: String = todayDateKey(),
        equipmentFr: String = "Salle complète"
    ): Pair<AthleteProgressState, ProgressionDelta> {
        val base = ensureUpToDateForDay(currentState, currentDateKey, equipmentFr)
        val workItems = if (perExerciseWork.isNotEmpty()) {
            perExerciseWork
        } else {
            val reps = if (session.exerciseType.isIsometric) 0 else session.reps
            val hold = if (session.exerciseType.isIsometric) session.durationSec else 0
            listOf(Triple(session.exerciseType, reps, hold))
        }

        val effectiveWork = workItems.filter { (_, reps, hold) -> reps > 0 || hold >= 3 }
        if (effectiveWork.isEmpty()) {
            return base to ProgressionDelta()
        }

        // 1. Update lifetime exercise totals (Palmarès)
        val updatedTotals = base.exerciseTotals.toMutableMap()
        for ((ex, reps, hold) in effectiveWork) {
            val delta = if (ex.isIsometric) hold else reps
            if (delta > 0) {
                updatedTotals[ex.id] = (updatedTotals[ex.id] ?: 0) + delta
            }
        }

        // 2. Compute quality-weighted workout XP & attribute gains
        val formMult = when {
            session.avgFormScore >= 90 -> 1.20f
            session.avgFormScore >= 80 -> 1.05f
            session.avgFormScore < 65 -> 0.90f
            else -> 1.00f
        }
        var rawWorkoutXp = 0f
        var addForce = 0
        var addEndurance = 0
        var addAgility = 0
        var addVitality = 0
        var addMastery = if (session.avgFormScore >= 85) 2 else 1

        for ((ex, reps, hold) in effectiveWork) {
            val amount = if (ex.isIsometric) hold else reps
            val unitXp = when (ex) {
                ExerciseType.PULL_UP, ExerciseType.BURPEE -> 4.0f
                ExerciseType.TRICEP_DIP, ExerciseType.BULGARIAN_SQUAT, ExerciseType.DEADLIFT -> 2.8f
                ExerciseType.PUSHUP, ExerciseType.SQUAT, ExerciseType.LUNGE,
                ExerciseType.OVERHEAD_PRESS, ExerciseType.BENT_ROW -> 2.2f
                ExerciseType.PLANK -> 1.1f // per second
                else -> 1.6f
            }
            rawWorkoutXp += amount * unitXp

            val statGain = when {
                ex.isIsometric -> (amount / 25).coerceIn(1, 5)
                ex == ExerciseType.PULL_UP || ex == ExerciseType.BURPEE -> (amount / 5).coerceIn(1, 6)
                else -> (amount / 12).coerceIn(1, 5)
            }
            when (ex) {
                ExerciseType.PUSHUP, ExerciseType.PULL_UP, ExerciseType.TRICEP_DIP,
                ExerciseType.DEADLIFT, ExerciseType.OVERHEAD_PRESS, ExerciseType.BENT_ROW,
                ExerciseType.BICEP_CURL, ExerciseType.LATERAL_RAISE -> addForce += statGain
                ExerciseType.BURPEE, ExerciseType.JUMPING_JACK, ExerciseType.PLANK -> addEndurance += statGain
                ExerciseType.SQUAT, ExerciseType.LUNGE, ExerciseType.BULGARIAN_SQUAT,
                ExerciseType.GLUTE_BRIDGE, ExerciseType.LEG_RAISE -> addAgility += statGain
            }
        }
        val hasVbt = !session.exerciseType.isIsometric &&
            (session.peakVelocityMps > 0.05f || session.estimatedMaxReps > 0)
        val vbtBonusXp = if (hasVbt) 10 else 0
        if (hasVbt) addForce += 1
        if (session.avgRomPct >= 85) addAgility += 1
        if (session.caloriesKcal >= 15.0) addVitality += 1

        var totalXpGain = ((rawWorkoutXp * formMult).roundToInt().coerceIn(15, 320)) + vbtBonusXp
        var totalRankXpGain = totalXpGain

        // 3. Auto-advance matching daily quests!
        val completedQuestTitles = mutableListOf<String>()
        var newlyCompletedQuestsCount = 0
        val updatedQuests = base.todayQuests.map { quest ->
            if (quest.completed || quest.exerciseType == null) {
                quest
            } else {
                val matchingAmount = effectiveWork
                    .filter { it.first == quest.exerciseType }
                    .sumOf { (ex, reps, hold) -> if (ex.isIsometric) hold else reps }
                if (matchingAmount <= 0) {
                    quest
                } else {
                    val newProg = (quest.currentProgress + matchingAmount).coerceAtMost(quest.targetAmount)
                    val justCompleted = newProg >= quest.targetAmount
                    if (justCompleted) {
                        completedQuestTitles.add(quest.titleFr)
                        newlyCompletedQuestsCount++
                        totalXpGain += quest.xpReward
                        totalRankXpGain += quest.xpReward
                        when (quest.attributeKey) {
                            "force" -> addForce += 2
                            "endurance" -> addEndurance += 2
                            "agility" -> addAgility += 2
                            "vitality" -> addVitality += 2
                            else -> addMastery += 2
                        }
                    }
                    quest.copy(currentProgress = newProg, completed = justCompleted)
                }
            }
        }

        // 4. Mark active day & update Streak (with +150 XP bonus every 7 consecutive days!)
        val (newStreak, newLongest, weeklyBonus) = markActiveDayForStreak(base, currentDateKey)
        if (weeklyBonus) {
            totalXpGain += WEEKLY_STREAK_BONUS_XP
            totalRankXpGain += WEEKLY_STREAK_BONUS_XP
            addVitality += 2
            addMastery += 2
        }

        val updatedRecentDays = markTodayInRecentDays(
            days = base.recentDays,
            todayKey = base.todayDateKey,
            steps = base.todaySteps,
            goalReached = base.todayStepGoalRewarded,
            workoutDone = true
        )

        val newTotalXp = base.totalXp + totalXpGain
        val (newLvl, inLvl, toNext) = computeLevelFromTotalXp(newTotalXp)
        val newRankXp = computeClampedRankXp(base.rank, base.rankXp + totalRankXpGain)

        val preAwards = base.copy(
            level = newLvl,
            totalXp = newTotalXp,
            xpInCurrentLevel = inLvl,
            xpToNextLevel = toNext,
            rankXp = newRankXp,
            rankExamReady = isRankExamReady(base.rank, newRankXp),
            attributes = AthleteAttributes(
                force = base.attributes.force + addForce,
                endurance = base.attributes.endurance + addEndurance,
                agility = base.attributes.agility + addAgility,
                vitality = base.attributes.vitality + addVitality,
                mastery = base.attributes.mastery + addMastery
            ),
            streakDays = newStreak,
            longestStreak = newLongest,
            lastActiveDateKey = base.todayDateKey,
            totalQuestsCompleted = base.totalQuestsCompleted + newlyCompletedQuestsCount,
            totalWorkoutsCompleted = base.totalWorkoutsCompleted + 1,
            highFormSessionsCount = base.highFormSessionsCount + if (session.avgFormScore >= 90) 1 else 0,
            vbtSessionsCount = base.vbtSessionsCount + if (hasVbt) 1 else 0,
            exerciseTotals = updatedTotals,
            todayQuests = updatedQuests,
            recentDays = updatedRecentDays
        )

        val (finalState, newBadges, newTitles) = evaluateBadgesAndTitles(
            preAwards,
            sessionFormScore = session.avgFormScore
        )

        val delta = ProgressionDelta(
            xpGained = totalXpGain,
            rankXpGained = totalRankXpGain,
            completedQuestTitles = completedQuestTitles,
            leveledUpFrom = if (newLvl > base.level) base.level else null,
            leveledUpTo = if (newLvl > base.level) newLvl else null,
            newlyUnlockedBadges = newBadges,
            newlyUnlockedTitles = newTitles,
            weeklyStreakBonusAwarded = weeklyBonus
        )
        return finalState to delta
    }

    /**
     * Idempotently applies today's total step count (from hardware sensor or manual entry).
     * Awards +10 XP + 1 Vitality per newly crossed 1,000-step bucket, +100 XP at 10,000 steps,
     * and auto-advances any daily walking quest.
     */
    fun applyDailySteps(
        currentState: AthleteProgressState,
        newTodaySteps: Int,
        currentDateKey: String = todayDateKey(),
        equipmentFr: String = "Salle complète"
    ): Pair<AthleteProgressState, ProgressionDelta> {
        val base = ensureUpToDateForDay(currentState, currentDateKey, equipmentFr)
        val clampedSteps = newTodaySteps.coerceIn(0, 150_000)
        if (clampedSteps <= base.todaySteps) {
            return base to ProgressionDelta()
        }

        val addedSteps = clampedSteps - base.todaySteps
        val totalBucketsNow = clampedSteps / STEP_BUCKET_SIZE
        val newBuckets = (totalBucketsNow - base.todayStepBucketsAwarded).coerceAtLeast(0)

        var xpGain = newBuckets * STEP_BUCKET_XP
        var addVitality = newBuckets
        var addEndurance = 0

        val hit10kNow = !base.todayStepGoalRewarded && clampedSteps >= base.stepGoal
        if (hit10kNow) {
            xpGain += STEP_GOAL_10K_XP
            addVitality += 3
            addEndurance += 2
        }

        // Advance any walking quest (exerciseType == null)
        val completedQuestTitles = mutableListOf<String>()
        var newlyCompletedQuests = 0
        val updatedQuests = base.todayQuests.map { quest ->
            if (quest.completed || quest.exerciseType != null) {
                quest
            } else {
                val prog = clampedSteps.coerceAtMost(quest.targetAmount)
                val justDone = prog >= quest.targetAmount
                if (justDone) {
                    completedQuestTitles.add(quest.titleFr)
                    newlyCompletedQuests++
                    xpGain += quest.xpReward
                    addVitality += 2
                }
                quest.copy(currentProgress = prog, completed = justDone)
            }
        }

        // Reaching 10k steps or completing a walking quest also counts as an active streak day!
        var newStreak = base.streakDays
        var newLongest = base.longestStreak
        var lastActive = base.lastActiveDateKey
        var weeklyBonus = false
        if (hit10kNow || newlyCompletedQuests > 0) {
            val streakRes = markActiveDayForStreak(base, currentDateKey)
            newStreak = streakRes.first
            newLongest = streakRes.second
            weeklyBonus = streakRes.third
            lastActive = base.todayDateKey
            if (weeklyBonus) {
                xpGain += WEEKLY_STREAK_BONUS_XP
                addVitality += 2
            }
        }

        val newTotalXp = base.totalXp + xpGain
        val (newLvl, inLvl, toNext) = computeLevelFromTotalXp(newTotalXp)
        val newRankXp = computeClampedRankXp(base.rank, base.rankXp + xpGain)

        val updatedRecentDays = markTodayInRecentDays(
            days = base.recentDays,
            todayKey = base.todayDateKey,
            steps = clampedSteps,
            goalReached = base.todayStepGoalRewarded || hit10kNow,
            workoutDone = isTodayWorkoutDone(base)
        )

        val preAwards = base.copy(
            level = newLvl,
            totalXp = newTotalXp,
            xpInCurrentLevel = inLvl,
            xpToNextLevel = toNext,
            rankXp = newRankXp,
            rankExamReady = isRankExamReady(base.rank, newRankXp),
            attributes = base.attributes.copy(
                endurance = base.attributes.endurance + addEndurance,
                vitality = base.attributes.vitality + addVitality
            ),
            streakDays = newStreak,
            longestStreak = newLongest,
            lastActiveDateKey = lastActive,
            todaySteps = clampedSteps,
            todayStepGoalRewarded = base.todayStepGoalRewarded || hit10kNow,
            todayStepBucketsAwarded = base.todayStepBucketsAwarded + newBuckets,
            totalStepsLifetime = base.totalStepsLifetime + addedSteps.toLong(),
            totalQuestsCompleted = base.totalQuestsCompleted + newlyCompletedQuests,
            todayQuests = updatedQuests,
            recentDays = updatedRecentDays
        )

        val (finalState, newBadges, newTitles) = evaluateBadgesAndTitles(preAwards)
        val delta = ProgressionDelta(
            xpGained = xpGain,
            rankXpGained = xpGain,
            completedQuestTitles = completedQuestTitles,
            leveledUpFrom = if (newLvl > base.level) base.level else null,
            leveledUpTo = if (newLvl > base.level) newLvl else null,
            newlyUnlockedBadges = newBadges,
            newlyUnlockedTitles = newTitles,
            weeklyStreakBonusAwarded = weeklyBonus,
            stepGoal10kAwarded = hit10kNow
        )
        return finalState to delta
    }

    /**
     * Manual off-camera fallback to complete a daily quest (e.g., outdoor run or gym machine).
     */
    fun completeQuestManually(
        currentState: AthleteProgressState,
        questId: String,
        currentDateKey: String = todayDateKey(),
        equipmentFr: String = "Salle complète"
    ): Pair<AthleteProgressState, ProgressionDelta> {
        val base = ensureUpToDateForDay(currentState, currentDateKey, equipmentFr)
        val target = base.todayQuests.firstOrNull { it.id == questId && !it.completed }
            ?: return base to ProgressionDelta()

        val remainingAmount = (target.targetAmount - target.currentProgress).coerceAtLeast(0)
        val updatedTotals = base.exerciseTotals.toMutableMap()
        if (target.exerciseType != null && remainingAmount > 0) {
            updatedTotals[target.exerciseType.id] = (updatedTotals[target.exerciseType.id] ?: 0) + remainingAmount
        }

        var xpGain = target.xpReward
        var addForce = 0
        var addEndurance = 0
        var addAgility = 0
        var addVitality = 0
        var addMastery = 1
        when (target.attributeKey) {
            "force" -> addForce += 2
            "endurance" -> addEndurance += 2
            "agility" -> addAgility += 2
            "vitality" -> addVitality += 2
            else -> addMastery += 2
        }

        val (newStreak, newLongest, weeklyBonus) = markActiveDayForStreak(base, currentDateKey)
        if (weeklyBonus) {
            xpGain += WEEKLY_STREAK_BONUS_XP
            addVitality += 2
        }

        val updatedQuests = base.todayQuests.map { q ->
            if (q.id == questId) q.copy(currentProgress = q.targetAmount, completed = true) else q
        }
        val newTotalXp = base.totalXp + xpGain
        val (newLvl, inLvl, toNext) = computeLevelFromTotalXp(newTotalXp)
        val newRankXp = computeClampedRankXp(base.rank, base.rankXp + xpGain)

        val preAwards = base.copy(
            level = newLvl,
            totalXp = newTotalXp,
            xpInCurrentLevel = inLvl,
            xpToNextLevel = toNext,
            rankXp = newRankXp,
            rankExamReady = isRankExamReady(base.rank, newRankXp),
            attributes = AthleteAttributes(
                force = base.attributes.force + addForce,
                endurance = base.attributes.endurance + addEndurance,
                agility = base.attributes.agility + addAgility,
                vitality = base.attributes.vitality + addVitality,
                mastery = base.attributes.mastery + addMastery
            ),
            streakDays = newStreak,
            longestStreak = newLongest,
            lastActiveDateKey = base.todayDateKey,
            totalQuestsCompleted = base.totalQuestsCompleted + 1,
            exerciseTotals = updatedTotals,
            todayQuests = updatedQuests,
            recentDays = markTodayInRecentDays(
                days = base.recentDays,
                todayKey = base.todayDateKey,
                steps = base.todaySteps,
                goalReached = base.todayStepGoalRewarded,
                workoutDone = true
            )
        )

        val (finalState, newBadges, newTitles) = evaluateBadgesAndTitles(preAwards)
        val delta = ProgressionDelta(
            xpGained = xpGain,
            rankXpGained = xpGain,
            completedQuestTitles = listOf(target.titleFr),
            leveledUpFrom = if (newLvl > base.level) base.level else null,
            leveledUpTo = if (newLvl > base.level) newLvl else null,
            newlyUnlockedBadges = newBadges,
            newlyUnlockedTitles = newTitles,
            weeklyStreakBonusAwarded = weeklyBonus
        )
        return finalState to delta
    }

    /**
     * Promotes the athlete's Rank (E->D->C->B->A->S) ONLY when a camera-verified Rank Exam is passed.
     */
    fun applyRankExamPassed(
        currentState: AthleteProgressState,
        exam: RankExamSpec,
        currentDateKey: String = todayDateKey(),
        equipmentFr: String = "Salle complète"
    ): Pair<AthleteProgressState, ProgressionDelta> {
        val base = ensureUpToDateForDay(currentState, currentDateKey, equipmentFr)
        if (base.rank != exam.fromRank || !base.rankExamReady) {
            return base to ProgressionDelta()
        }

        val nextRank = exam.toRank
        val xpGain = exam.xpBonus
        val newTotalXp = base.totalXp + xpGain
        val (newLvl, inLvl, toNext) = computeLevelFromTotalXp(newTotalXp)
        val promotedRankXp = maxOf(base.rankXp, nextRank.minRankXp)

        val preAwards = base.copy(
            level = newLvl,
            totalXp = newTotalXp,
            xpInCurrentLevel = inLvl,
            xpToNextLevel = toNext,
            rank = nextRank,
            rankXp = promotedRankXp,
            rankExamReady = isRankExamReady(nextRank, promotedRankXp),
            attributes = AthleteAttributes(
                force = base.attributes.force + 3,
                endurance = base.attributes.endurance + 3,
                agility = base.attributes.agility + 2,
                vitality = base.attributes.vitality + 2,
                mastery = base.attributes.mastery + 3
            )
        )

        val (finalState, newBadges, newTitles) = evaluateBadgesAndTitles(preAwards)
        val delta = ProgressionDelta(
            xpGained = xpGain,
            rankXpGained = 0,
            leveledUpFrom = if (newLvl > base.level) base.level else null,
            leveledUpTo = if (newLvl > base.level) newLvl else null,
            rankedUpFrom = base.rank,
            rankedUpTo = nextRank,
            newlyUnlockedBadges = newBadges,
            newlyUnlockedTitles = newTitles
        )
        return finalState to delta
    }

    fun equipTitle(state: AthleteProgressState, titleId: String): AthleteProgressState {
        if (titleId !in state.unlockedTitleIds) return state
        return state.copy(equippedTitleId = titleId)
    }

    private fun isRankExamReady(rank: AthleteRank, rankXp: Int): Boolean =
        rank != AthleteRank.S && rankXp >= rank.targetRankXp

    private fun computeClampedRankXp(rank: AthleteRank, rawRankXp: Int): Int {
        // Hold rank XP cleanly at targetRankXp until the athlete passes the Rank Exam
        return if (rank == AthleteRank.S) {
            rawRankXp.coerceAtLeast(rank.minRankXp)
        } else {
            rawRankXp.coerceIn(0, rank.targetRankXp)
        }
    }

    private fun computeStreakOnDayStart(
        lastActiveDateKey: String,
        currentStreak: Int,
        currentDate: LocalDate
    ): Int {
        if (lastActiveDateKey.isBlank()) return 0
        val lastDate = runCatching { LocalDate.parse(lastActiveDateKey, DATE_FMT) }.getOrNull() ?: return 0
        val daysBetween = ChronoUnit.DAYS.between(lastDate, currentDate)
        return when {
            daysBetween <= 1L -> currentStreak.coerceAtLeast(0)
            else -> 0 // Graceful reset after 2+ rest days — zero XP penalty!
        }
    }

    private fun markActiveDayForStreak(
        base: AthleteProgressState,
        currentDateKey: String
    ): Triple<Int, Int, Boolean> {
        if (base.lastActiveDateKey == currentDateKey) {
            return Triple(base.streakDays.coerceAtLeast(1), maxOf(base.longestStreak, base.streakDays.coerceAtLeast(1)), false)
        }
        val today = runCatching { LocalDate.parse(currentDateKey, DATE_FMT) }.getOrElse { LocalDate.now() }
        val last = runCatching { LocalDate.parse(base.lastActiveDateKey, DATE_FMT) }.getOrNull()
        val consecutive = last != null && ChronoUnit.DAYS.between(last, today) == 1L
        val nextStreak = if (consecutive) base.streakDays + 1 else 1
        val nextLongest = maxOf(base.longestStreak, nextStreak)
        val weeklyBonus = nextStreak > 0 && (nextStreak % 7 == 0)
        return Triple(nextStreak, nextLongest, weeklyBonus)
    }

    private fun isTodayWorkoutDone(state: AthleteProgressState): Boolean =
        state.recentDays.any { it.dateKey == state.todayDateKey && it.workoutDone } ||
            (state.lastActiveDateKey.isNotBlank() && state.lastActiveDateKey == state.todayDateKey)

    private fun buildSevenDayWindow(
        existing: List<DayActivityEntry>,
        todayKey: String,
        todaySteps: Int,
        todayGoalReached: Boolean,
        todayWorkoutDone: Boolean
    ): List<DayActivityEntry> {
        val byDate = existing.associateBy { it.dateKey }.toMutableMap()
        val todayDate = runCatching { LocalDate.parse(todayKey, DATE_FMT) }.getOrElse { LocalDate.now() }
        byDate[todayKey] = DayActivityEntry(
            dateKey = todayKey,
            steps = todaySteps,
            goalReached = todayGoalReached,
            workoutDone = todayWorkoutDone
        )
        return (6 downTo 0).map { offset ->
            val key = todayDate.minusDays(offset.toLong()).format(DATE_FMT)
            byDate[key] ?: DayActivityEntry(dateKey = key)
        }
    }

    private fun markTodayInRecentDays(
        days: List<DayActivityEntry>,
        todayKey: String,
        steps: Int,
        goalReached: Boolean,
        workoutDone: Boolean
    ): List<DayActivityEntry> = buildSevenDayWindow(days, todayKey, steps, goalReached, workoutDone)

    private fun evaluateBadgesAndTitles(
        state: AthleteProgressState,
        sessionFormScore: Int = 0
    ): Triple<AthleteProgressState, List<MeritBadge>, List<MeritTitle>> {
        val badges = state.unlockedBadgeIds.toMutableSet()
        val titles = state.unlockedTitleIds.toMutableSet()
        titles.add(MeritTitle.ATHLETE_KINETIX.id)

        val pushups = state.exerciseTotals[ExerciseType.PUSHUP.id] ?: 0
        val pullups = state.exerciseTotals[ExerciseType.PULL_UP.id] ?: 0
        val squats = state.exerciseTotals[ExerciseType.SQUAT.id] ?: 0

        val newBadges = mutableListOf<MeritBadge>()
        val newTitles = mutableListOf<MeritTitle>()

        fun unlockBadge(b: MeritBadge, condition: Boolean) {
            if (condition && badges.add(b.id)) newBadges.add(b)
        }

        fun unlockTitle(t: MeritTitle, condition: Boolean) {
            if (condition && titles.add(t.id)) newTitles.add(t)
        }

        unlockBadge(MeritBadge.FIRST_WORKOUT, state.totalWorkoutsCompleted >= 1)
        unlockBadge(MeritBadge.STREAK_7, state.longestStreak >= 7 || state.streakDays >= 7)
        unlockBadge(MeritBadge.PUSHUPS_100, pushups >= 100)
        unlockBadge(MeritBadge.PUSHUPS_500, pushups >= 500)
        unlockBadge(MeritBadge.PULLUPS_100, pullups >= 100)
        unlockBadge(MeritBadge.SQUATS_500, squats >= 500)
        unlockBadge(MeritBadge.STEPS_10K, state.todaySteps >= 10_000 || state.todayStepGoalRewarded)
        unlockBadge(MeritBadge.STEPS_100K, state.totalStepsLifetime >= 100_000L)
        unlockBadge(MeritBadge.GOLDEN_FORM, sessionFormScore >= 92)
        unlockBadge(MeritBadge.QUESTS_10, state.totalQuestsCompleted >= 10)
        unlockBadge(MeritBadge.RANK_D, state.rank.ordinal >= AthleteRank.D.ordinal)
        unlockBadge(MeritBadge.RANK_B, state.rank.ordinal >= AthleteRank.B.ordinal)

        unlockTitle(MeritTitle.REGULIER, state.longestStreak >= 3 || state.streakDays >= 3)
        unlockTitle(MeritTitle.INARRETABLE, state.longestStreak >= 7 || state.streakDays >= 7)
        unlockTitle(MeritTitle.FORCE_PURE, pushups >= 250 || pullups >= 100)
        unlockTitle(MeritTitle.ENDURANT_10K, state.todaySteps >= 10_000 || state.todayStepGoalRewarded)
        unlockTitle(MeritTitle.EXECUTION_PRO, state.highFormSessionsCount >= 5)
        unlockTitle(MeritTitle.EXPLOSIF_VBT, state.vbtSessionsCount >= 3)
        unlockTitle(MeritTitle.RANG_C, state.rank.ordinal >= AthleteRank.C.ordinal)
        unlockTitle(MeritTitle.RANG_A, state.rank.ordinal >= AthleteRank.A.ordinal)
        unlockTitle(MeritTitle.RANG_S, state.rank == AthleteRank.S)

        val updated = state.copy(
            unlockedBadgeIds = badges,
            unlockedTitleIds = titles,
            equippedTitleId = if (state.equippedTitleId in titles) state.equippedTitleId else MeritTitle.ATHLETE_KINETIX.id
        )
        return Triple(updated, newBadges, newTitles)
    }
}
