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

    fun selectExercise(exercise: ExerciseType) {
        engine.setExercise(exercise)
        lastRepSpoken = 0
        _uiState.update {
            it.copy(
                selectedExercise = exercise,
                sessionStartMs = System.currentTimeMillis(),
                isSessionActive = true,
                frame = engine.emptyFrame(),
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

    fun onPoseLandmarks(points: List<PosePoint>, width: Int, height: Int, timestampMs: Long) {
        if (!_uiState.value.isSessionActive) return
        val frame = engine.processFrame(points, timestampMs)

        // Trigger French TTS voice coach on new rep or fault correction
        if (frame.poseDetected) {
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
        engine.resetSession()
        lastRepSpoken = 0
        _uiState.update {
            it.copy(
                sessionStartMs = System.currentTimeMillis(),
                isSessionActive = true,
                frame = engine.emptyFrame(),
                completedSummary = null
            )
        }
    }

    fun finishSession() {
        val state = _uiState.value
        val frame = state.frame
        val elapsedSec = if (state.selectedExercise.isIsometric) {
            frame.isometricHoldSec.toInt().coerceAtLeast(1)
        } else {
            ((System.currentTimeMillis() - state.sessionStartMs) / 1000L).toInt().coerceAtLeast(1)
        }

        val session = WorkoutSession(
            timestampMs = System.currentTimeMillis(),
            exerciseType = state.selectedExercise,
            reps = frame.repCount,
            durationSec = elapsedSec,
            avgFormScore = frame.avgFormScore,
            avgRomPct = frame.avgRomPct,
            topFaults = frame.topFaults
        )

        viewModelScope.launch(Dispatchers.IO) {
            if (session.reps > 0 || (session.exerciseType.isIsometric && session.durationSec >= 3)) {
                app.userDb.insertSession(session)
            }
        }

        _uiState.update {
            it.copy(
                isSessionActive = false,
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
            "${session.reps} répétitions en ${session.durationSec}s"
        }
        return "Analyse ma séance Vision KINETIX de ${session.exerciseType.labelFr} : " +
            "$metricStr, score postural moyen ${session.avgFormScore}/100, " +
            "amplitude moyenne (ROM) ${session.avgRomPct}%. " +
            "Défauts relevés : $faultsText. " +
            "Donne-moi un débriefing biomécanique précis en 3 axes : points forts, corrections techniques concrètes et recommandation pour la prochaine série."
    }

    override fun onCleared() {
        super.onCleared()
        voiceCoach.shutdown()
    }
}
