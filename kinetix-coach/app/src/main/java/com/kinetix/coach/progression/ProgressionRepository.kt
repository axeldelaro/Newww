package com.kinetix.coach.progression

import android.content.Context
import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.UserDatabaseHelper
import com.kinetix.coach.data.WorkoutSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thread-safe application-level repository coordinating Athlete Progression, Daily Quests,
 * Merit Badges/Titles, Camera-Verified Rank Exams, and the Offline Hardware Pedometer.
 * Immediately renders safe local state with non-blocking persistence and SQLite auto-repair.
 */
class ProgressionRepository(
    context: Context,
    private val userDb: UserDatabaseHelper,
    private val scope: CoroutineScope
) {
    private val lock = Any()

    private val _state = MutableStateFlow(AthleteProgressState())
    val state: StateFlow<AthleteProgressState> = _state.asStateFlow()

    private val _lastCelebration = MutableStateFlow<ProgressionDelta?>(null)
    val lastCelebration: StateFlow<ProgressionDelta?> = _lastCelebration.asStateFlow()

    private val _pedometerActive = MutableStateFlow(false)
    val pedometerActive: StateFlow<Boolean> = _pedometerActive.asStateFlow()

    @Volatile
    var activeRankExam: RankExamSpec? = null
        private set

    val stepTracker: NativeStepTracker = NativeStepTracker(
        context = context.applicationContext,
        userDb = userDb,
        onDailyStepsChanged = { dailyTotal ->
            recordDailySteps(dailyTotal)
        }
    )

    init {
        initializeAndRefreshToday()
        if (stepTracker.hasActivityPermission()) {
            _pedometerActive.value = stepTracker.startIfPermitted()
        }
    }

    fun initializeAndRefreshToday() {
        val updated: AthleteProgressState
        synchronized(lock) {
            val loaded = userDb.loadProgressionState() ?: seedFromExistingSessionsLocked()
            val equipment = userDb.profile.value.equipment
            updated = RpgProgressionEngine.ensureUpToDateForDay(
                state = loaded,
                currentDateKey = RpgProgressionEngine.todayDateKey(),
                equipmentFr = equipment
            )
            _state.value = updated
        }
        persistAsync(updated)
    }

    /** Seeds lifetime palmarès gracefully if an existing user upgrades from SQLite v4 -> v5. */
    private fun seedFromExistingSessionsLocked(): AthleteProgressState {
        val pastSessions = userDb.sessions.value
        if (pastSessions.isEmpty()) return AthleteProgressState()
        val totals = linkedMapOf<String, Int>()
        var highForm = 0
        var vbtCount = 0
        for (s in pastSessions) {
            val amount = if (s.exerciseType.isIsometric) s.durationSec else s.reps
            if (amount > 0) {
                totals[s.exerciseType.id] = (totals[s.exerciseType.id] ?: 0) + amount
            }
            if (s.avgFormScore >= 90) highForm++
            if (!s.exerciseType.isIsometric && (s.peakVelocityMps > 0.05f || s.estimatedMaxReps > 0)) vbtCount++
        }
        return AthleteProgressState(
            totalWorkoutsCompleted = pastSessions.size,
            highFormSessionsCount = highForm,
            vbtSessionsCount = vbtCount,
            exerciseTotals = totals
        )
    }

    fun onPedometerPermissionGranted(): Boolean {
        val started = stepTracker.startIfPermitted()
        _pedometerActive.value = started
        return started
    }

    fun beginRankExam(exam: RankExamSpec) {
        activeRankExam = exam
    }

    fun clearActiveRankExam() {
        activeRankExam = null
    }

    fun recordWorkoutSession(
        session: WorkoutSession,
        perExerciseWork: List<Triple<ExerciseType, Int, Int>> = emptyList(),
        emitGlobalCelebration: Boolean = true
    ): ProgressionDelta {
        val updated: AthleteProgressState
        val delta: ProgressionDelta
        synchronized(lock) {
            val equipment = userDb.profile.value.equipment
            val res = RpgProgressionEngine.applyWorkoutSession(
                currentState = _state.value,
                session = session,
                perExerciseWork = perExerciseWork,
                currentDateKey = RpgProgressionEngine.todayDateKey(),
                equipmentFr = equipment
            )
            updated = res.first
            delta = res.second
            _state.value = updated
        }
        if (emitGlobalCelebration && delta.hasCelebration) {
            _lastCelebration.value = delta
        }
        persistAsync(updated)
        return delta
    }

    fun recordRankExamResult(
        achievedScore: Int,
        passed: Boolean,
        emitGlobalCelebration: Boolean = true
    ): ProgressionDelta {
        val exam = activeRankExam ?: return ProgressionDelta()
        activeRankExam = null
        if (!passed || achievedScore < exam.minScoreToPass) {
            return ProgressionDelta()
        }
        val updated: AthleteProgressState
        val delta: ProgressionDelta
        synchronized(lock) {
            val equipment = userDb.profile.value.equipment
            val res = RpgProgressionEngine.applyRankExamPassed(
                currentState = _state.value,
                exam = exam,
                currentDateKey = RpgProgressionEngine.todayDateKey(),
                equipmentFr = equipment
            )
            updated = res.first
            delta = res.second
            _state.value = updated
        }
        if (emitGlobalCelebration && delta.hasCelebration) {
            _lastCelebration.value = delta
        }
        persistAsync(updated)
        return delta
    }

    fun recordDailySteps(newDailySteps: Int): ProgressionDelta {
        val updated: AthleteProgressState
        val delta: ProgressionDelta
        var changed = false
        synchronized(lock) {
            val prevSteps = _state.value.todaySteps
            val equipment = userDb.profile.value.equipment
            val res = RpgProgressionEngine.applyDailySteps(
                currentState = _state.value,
                newTodaySteps = newDailySteps,
                currentDateKey = RpgProgressionEngine.todayDateKey(),
                equipmentFr = equipment
            )
            updated = res.first
            delta = res.second
            changed = updated.todaySteps != prevSteps || delta.xpGained > 0
            _state.value = updated
        }
        if (delta.hasCelebration || delta.stepGoal10kAwarded) {
            _lastCelebration.value = delta
        }
        if (changed) {
            persistAsync(updated)
        }
        return delta
    }

    fun addManualSteps(deltaSteps: Int): ProgressionDelta {
        if (deltaSteps <= 0) return ProgressionDelta()
        val todayKey = RpgProgressionEngine.todayDateKey()
        val currentSteps = synchronized(lock) {
            val refreshed = RpgProgressionEngine.ensureUpToDateForDay(
                _state.value,
                todayKey,
                userDb.profile.value.equipment
            )
            _state.value = refreshed
            refreshed.todaySteps
        }
        val nextTotal = stepTracker.recordManualDelta(deltaSteps, currentSteps, todayKey)
        return recordDailySteps(nextTotal)
    }

    fun completeQuestManually(questId: String): ProgressionDelta {
        val updated: AthleteProgressState
        val delta: ProgressionDelta
        synchronized(lock) {
            val equipment = userDb.profile.value.equipment
            val res = RpgProgressionEngine.completeQuestManually(
                currentState = _state.value,
                questId = questId,
                currentDateKey = RpgProgressionEngine.todayDateKey(),
                equipmentFr = equipment
            )
            updated = res.first
            delta = res.second
            _state.value = updated
        }
        if (delta.hasCelebration) {
            _lastCelebration.value = delta
        }
        persistAsync(updated)
        return delta
    }

    fun equipTitle(titleId: String) {
        val updated: AthleteProgressState
        synchronized(lock) {
            updated = RpgProgressionEngine.equipTitle(_state.value, titleId)
            _state.value = updated
        }
        persistAsync(updated)
    }

    fun dismissCelebration() {
        _lastCelebration.value = null
    }

    private fun persistAsync(snapshot: AthleteProgressState) {
        scope.launch(Dispatchers.IO) {
            runCatching { userDb.saveProgressionState(snapshot) }
        }
    }
}
