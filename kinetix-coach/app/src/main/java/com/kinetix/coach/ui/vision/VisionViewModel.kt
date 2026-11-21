package com.kinetix.coach.ui.vision

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kinetix.coach.KinetixApp
import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.WorkoutSession
import com.kinetix.coach.vision.BiomechanicsEngine
import com.kinetix.coach.vision.BiomechanicsFrame
import com.kinetix.coach.vision.PosePoint
import com.kinetix.coach.vision.VoiceCoach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VisionUiState(
    val selectedExercise: ExerciseType = ExerciseType.SQUAT,
    val isFrontCamera: Boolean = true,
    val isVoiceEnabled: Boolean = true,
    val isSessionActive: Boolean = true,
    val sessionStartMs: Long = System.currentTimeMillis(),
    val delegateLabel: String = "GPU",
    val imageWidth: Int = 480,
    val imageHeight: Int = 640,
    val frame: BiomechanicsFrame = BiomechanicsEngine(ExerciseType.SQUAT).emptyFrame(),
    val currentSetNumber: Int = 1,
    val completedSetsCount: Int = 0,
    val sessionTotalReps: Int = 0,
    val sessionTotalHoldSec: Int = 0,
    val restDurationSec: Int = 60,
    val restRemainingSec: Int = 0,
    val isResting: Boolean = false,
    val restFinishedEvent: Int = 0,
    val completedSummary: WorkoutSession? = null,
    val errorMessage: String? = null
)

class VisionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KinetixApp
    private val engine = BiomechanicsEngine(ExerciseType.SQUAT)
    private val voiceCoach = VoiceCoach(application)

    private val _uiState = MutableStateFlow(
        VisionUiState(frame = engine.emptyFrame())
    )
    val uiState: StateFlow<VisionUiState> = _uiState.asStateFlow()

    private var lastRepSpoken = 0
    private var restTimerJob: Job? = null

    // Multi-set session accumulators for accurate summary averaging
    private var formScoreWeightedSum = 0L
    private var romWeightedSum = 0L
    private var setSamplesWeight = 0L
    private val sessionFaults = linkedSetOf<String>()

    fun selectExercise(exercise: ExerciseType) {
        cancelRestTimer()
        engine.setExercise(exercise)
        lastRepSpoken = 0
        resetMultiSetAccumulators()
        _uiState.update {
            it.copy(
                selectedExercise = exercise,
                sessionStartMs = System.currentTimeMillis(),
                isSessionActive = true,
                frame = engine.emptyFrame(),
                currentSetNumber = 1,
                completedSetsCount = 0,
                sessionTotalReps = 0,
                sessionTotalHoldSec = 0,
                restRemainingSec = 0,
                isResting = false,
                completedSummary = null,
                errorMessage = null
            )
        }
    }

    fun toggleCamera() {
        _uiState.update { it.copy(isFrontCamera = !it.isFrontCamera) }
    }

    fun toggleVoice() {
        val next = !_uiState.value.isVoiceEnabled
        voiceCoach.enabled = next
        _uiState.update { it.copy(isVoiceEnabled = next) }
    }

    fun setDelegateLabel(label: String) {
        _uiState.update { it.copy(delegateLabel = label) }
    }

    fun reportError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun setRestDuration(seconds: Int) {
        val clamped = seconds.coerceIn(15, 300)
        _uiState.update { state ->
            state.copy(
                restDurationSec = clamped,
                restRemainingSec = if (state.isResting) clamped else state.restRemainingSec
            )
        }
    }

    fun completeSetAndStartRest() {
        val state = _uiState.value
        val frame = state.frame
        val setReps = if (state.selectedExercise.isIsometric) 0 else frame.repCount
        val setHold = if (state.selectedExercise.isIsometric) frame.isometricHoldSec.toInt() else 0

        val weight = maxOf(1, setReps, setHold).toLong()
        if (setReps > 0 || setHold > 0) {
            formScoreWeightedSum += frame.avgFormScore.toLong() * weight
            romWeightedSum += frame.avgRomPct.toLong() * weight
            setSamplesWeight += weight
            sessionFaults.addAll(frame.topFaults)
        }

        engine.resetSession()
        lastRepSpoken = 0

        val restSec = state.restDurationSec
        _uiState.update {
            it.copy(
                currentSetNumber = it.currentSetNumber + 1,
                completedSetsCount = it.completedSetsCount + 1,
                sessionTotalReps = it.sessionTotalReps + setReps,
                sessionTotalHoldSec = it.sessionTotalHoldSec + setHold,
                frame = engine.emptyFrame(),
                isResting = true,
                restRemainingSec = restSec
            )
        }

        voiceCoach.speak("Série validée. Repos $restSec secondes.", force = true)
        launchRestCountdown()
    }

    fun addRestSeconds(deltaSec: Int) {
        if (!_uiState.value.isResting) return
        _uiState.update {
            it.copy(restRemainingSec = (it.restRemainingSec + deltaSec).coerceIn(5, 300))
        }
    }

    fun skipRest() {
        cancelRestTimer()
        _uiState.update {
            it.copy(isResting = false, restRemainingSec = 0)
        }
    }

    private fun launchRestCountdown() {
        cancelRestTimer()
        restTimerJob = viewModelScope.launch {
            while (true) {
                val rem = _uiState.value.restRemainingSec
                if (rem <= 0) break
                delay(1000L)
                val updated = (_uiState.value.restRemainingSec - 1).coerceAtLeast(0)
                if (updated == 0) {
                    _uiState.update {
                        it.copy(
                            isResting = false,
                            restRemainingSec = 0,
                            restFinishedEvent = it.restFinishedEvent + 1
                        )
                    }
                    voiceCoach.speak("Repos terminé, série suivante !", force = true)
                    break
                } else {
                    _uiState.update { it.copy(restRemainingSec = updated) }
                }
            }
        }
    }

    private fun cancelRestTimer() {
        restTimerJob?.cancel()
        restTimerJob = null
    }

    fun onPoseLandmarks(points: List<PosePoint>, width: Int, height: Int, timestampMs: Long) {
        val state = _uiState.value
        if (!state.isSessionActive) return
        val frame = engine.processFrame(points, timestampMs)

        // Trigger French TTS voice coach on new rep or fault correction (muted during active rest)
        if (frame.poseDetected && !state.isResting) {
            if (!frame.exercise.isIsometric && frame.repCount > lastRepSpoken) {
                lastRepSpoken = frame.repCount
                voiceCoach.speak(frame.voiceCueFr ?: "${frame.repCount}", isRepCounter = true)
            } else if (frame.voiceCueFr != null) {
                voiceCoach.speak(frame.voiceCueFr, isRepCounter = false)
            }
        }

        _uiState.update {
            it.copy(
                imageWidth = width.coerceAtLeast(1),
                imageHeight = height.coerceAtLeast(1),
                frame = frame
            )
        }
    }

    fun resetSession() {
        cancelRestTimer()
        engine.resetSession()
        lastRepSpoken = 0
        resetMultiSetAccumulators()
        _uiState.update {
            it.copy(
                sessionStartMs = System.currentTimeMillis(),
                isSessionActive = true,
                frame = engine.emptyFrame(),
                currentSetNumber = 1,
                completedSetsCount = 0,
                sessionTotalReps = 0,
                sessionTotalHoldSec = 0,
                isResting = false,
                restRemainingSec = 0,
                completedSummary = null
            )
        }
    }

    fun finishSession() {
        cancelRestTimer()
        val state = _uiState.value
        val frame = state.frame

        val totalReps = state.sessionTotalReps + frame.repCount
        val totalHoldSec = state.sessionTotalHoldSec + frame.isometricHoldSec.toInt()
        val elapsedSec = if (state.selectedExercise.isIsometric) {
            totalHoldSec.coerceAtLeast(1)
        } else {
            ((System.currentTimeMillis() - state.sessionStartMs) / 1000L).toInt().coerceAtLeast(1)
        }

        val currentWeight = maxOf(0, if (state.selectedExercise.isIsometric) frame.isometricHoldSec.toInt() else frame.repCount).toLong()
        val combinedWeight = setSamplesWeight + currentWeight
        val finalAvgForm = if (combinedWeight > 0L) {
            ((formScoreWeightedSum + frame.avgFormScore.toLong() * currentWeight) / combinedWeight).toInt().coerceIn(0, 100)
        } else {
            frame.avgFormScore
        }
        val finalAvgRom = if (combinedWeight > 0L) {
            ((romWeightedSum + frame.avgRomPct.toLong() * currentWeight) / combinedWeight).toInt().coerceIn(0, 100)
        } else {
            frame.avgRomPct
        }
        val combinedFaults = (sessionFaults + frame.topFaults).distinct().take(3)
        val totalSetsDone = state.completedSetsCount + if (frame.repCount > 0 || frame.isometricHoldSec >= 3f) 1 else 0

        val session = WorkoutSession(
            timestampMs = System.currentTimeMillis(),
            exerciseType = state.selectedExercise,
            reps = totalReps,
            durationSec = elapsedSec,
            avgFormScore = finalAvgForm,
            avgRomPct = finalAvgRom,
            topFaults = combinedFaults,
            notes = if (totalSetsDone > 1) "$totalSetsDone séries" else ""
        )

        viewModelScope.launch(Dispatchers.IO) {
            if (session.reps > 0 || (session.exerciseType.isIsometric && session.durationSec >= 3)) {
                app.userDb.insertSession(session)
            }
        }

        _uiState.update {
            it.copy(
                isSessionActive = false,
                isResting = false,
                restRemainingSec = 0,
                completedSummary = session
            )
        }
    }

    fun dismissSummaryAndRestart() {
        resetSession()
    }

    fun buildCoachDebriefPrompt(session: WorkoutSession): String {
        val faultsText = if (session.topFaults.isEmpty()) {
            "Aucun défaut majeur détecté"
        } else {
            session.topFaults.joinToString("; ")
        }
        val metricStr = if (session.exerciseType.isIsometric) {
            "Maintien aligné : ${session.durationSec} secondes"
        } else {
            val setsSuffix = if (session.notes.isNotBlank()) " (${session.notes})" else ""
            "${session.reps} répétitions$setsSuffix en ${session.durationSec}s"
        }
        return "Analyse ma séance Vision KINETIX de ${session.exerciseType.labelFr} : " +
            "$metricStr, score postural moyen ${session.avgFormScore}/100, " +
            "amplitude moyenne (ROM) ${session.avgRomPct}%. " +
            "Défauts relevés : $faultsText. " +
            "Donne-moi un débriefing biomécanique précis en 3 axes : points forts, corrections techniques concrètes et recommandation pour la prochaine série."
    }

    private fun resetMultiSetAccumulators() {
        formScoreWeightedSum = 0L
        romWeightedSum = 0L
        setSamplesWeight = 0L
        sessionFaults.clear()
    }

    override fun onCleared() {
        super.onCleared()
        cancelRestTimer()
        voiceCoach.shutdown()
    }
}
