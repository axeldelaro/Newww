package com.kinetix.coach.ui.vision

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kinetix.coach.KinetixApp
import com.kinetix.coach.data.ChallengeRecord
import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.MovementPhase
import com.kinetix.coach.data.WorkoutSession
import com.kinetix.coach.vision.AutoExerciseDetector
import com.kinetix.coach.vision.BiomechanicsEngine
import com.kinetix.coach.vision.BiomechanicsFrame
import com.kinetix.coach.vision.BufferedPoseFrame
import com.kinetix.coach.vision.CalorieEngine
import com.kinetix.coach.vision.ChallengeConfig
import com.kinetix.coach.vision.ChallengeController
import com.kinetix.coach.vision.ChallengeOutput
import com.kinetix.coach.vision.ChallengeResult
import com.kinetix.coach.vision.ChallengeSnapshot
import com.kinetix.coach.vision.ChallengeType
import com.kinetix.coach.vision.DetectionStrictness
import com.kinetix.coach.vision.FormQuality
import com.kinetix.coach.vision.PoseOverlayState
import com.kinetix.coach.vision.PosePoint
import com.kinetix.coach.vision.VelocityTelemetry
import com.kinetix.coach.vision.ViewpointInfo
import com.kinetix.coach.vision.VoiceCoach
import com.kinetix.coach.vision.WorkoutAudioEngine
import com.kinetix.coach.vision.WorkoutMusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

data class ExerciseBreakdownItem(
    val exercise: ExerciseType,
    val reps: Int,
    val holdSec: Int,
    val caloriesKcal: Double
)

data class VisionUiState(
    val selectedExercise: ExerciseType = ExerciseType.SQUAT,
    val detectionStrictness: DetectionStrictness = DetectionStrictness.ADAPTIVE,
    val isAutoDetectEnabled: Boolean = true,
    val autoDetectConfidence: Float = 0f,
    val autoDetectStatusFr: String = "En attente de mouvement…",
    val isFrontCamera: Boolean = true,
    val isVoiceEnabled: Boolean = true,
    val showDetailedAngles: Boolean = false,
    val isSessionActive: Boolean = true,
    val sessionStartMs: Long = System.currentTimeMillis(),
    val delegateLabel: String = "GPU",
    val imageWidth: Int = 480,
    val imageHeight: Int = 640,
    val frame: BiomechanicsFrame = BiomechanicsEngine(ExerciseType.SQUAT).emptyFrame(),
    val viewpointInfo: ViewpointInfo = ViewpointInfo(),
    val currentSetNumber: Int = 1,
    val completedSetsCount: Int = 0,
    val sessionTotalReps: Int = 0,
    val sessionTotalHoldSec: Int = 0,
    val currentSetCaloriesKcal: Double = 0.0,
    val sessionTotalCaloriesKcal: Double = 0.0,
    val multiExerciseBreakdown: List<ExerciseBreakdownItem> = emptyList(),
    val restDurationSec: Int = 60,
    val restRemainingSec: Int = 0,
    val isResting: Boolean = false,
    val restFinishedEvent: Int = 0,
    // Hands-free setup countdown ("Chrono d'installation") preventing false reps while placing the phone
    val setupDelaySec: Int = 5,
    val setupRemainingSec: Int = 0,
    val completedSummary: WorkoutSession? = null,
    val completedBreakdown: List<ExerciseBreakdownItem> = emptyList(),
    val completedVelocityTelemetry: VelocityTelemetry? = null,
    // Challenges & Music state
    val activeChallenge: ChallengeSnapshot? = null,
    val completedChallengeResult: ChallengeResult? = null,
    val selectedMusicTrack: WorkoutMusicTrack = WorkoutMusicTrack.TECHNO_PULSE,
    val customMusicUri: String? = null,
    val customMusicName: String? = null,
    val isMusicMuted: Boolean = false,
    val musicVolume: Float = 0.72f,
    val errorMessage: String? = null
) {
    val isSettingUp: Boolean
        get() = setupRemainingSec > 0
}

class VisionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREF_STRICTNESS = "detection_strictness"
        private const val PREF_SETUP_DELAY_SEC = "setup_delay_sec"
        private const val PREF_DETAILED_ANGLES = "show_detailed_angles"
        private const val PREF_CUSTOM_MUSIC_URI = "custom_music_uri"
        private const val PREF_CUSTOM_MUSIC_NAME = "custom_music_name"
        private const val PREF_MUSIC_TRACK = "preferred_music_track"
        private const val HUD_MINOR_UPDATE_INTERVAL_MS = 120L
        private const val REPLAY_HISTORY_MAX_FRAMES = 75
        private const val REPLAY_HISTORY_WINDOW_MS = 2400L
        private val SETUP_DELAY_PRESETS = listOf(0, 5, 8, 10)
    }

    private val app = application as KinetixApp
    private val pipelineLock = Any()

    private val initialStrictness: DetectionStrictness = runCatching {
        DetectionStrictness.valueOf(app.userDb.getPref(PREF_STRICTNESS, DetectionStrictness.ADAPTIVE.name))
    }.getOrDefault(DetectionStrictness.ADAPTIVE)

    private val initialSetupDelaySec: Int = app.userDb.getPref(PREF_SETUP_DELAY_SEC, "5")
        .toIntOrNull()
        ?.takeIf { it in SETUP_DELAY_PRESETS }
        ?: 5

    private val initialDetailedAngles: Boolean = app.userDb.getPref(PREF_DETAILED_ANGLES, "false")
        .equals("true", ignoreCase = true)

    private val engine = BiomechanicsEngine(
        initialExercise = ExerciseType.SQUAT,
        initialStrictness = initialStrictness
    )
    private val autoDetector = AutoExerciseDetector()
    private val voiceCoach = VoiceCoach(application)
    private val audioEngine = WorkoutAudioEngine(application)
    private val challengeController = ChallengeController()

    val challengeRecords: StateFlow<List<ChallengeRecord>> = app.userDb.challengeRecords

    private val _uiState = MutableStateFlow(
        VisionUiState(
            detectionStrictness = initialStrictness,
            showDetailedAngles = initialDetailedAngles,
            setupDelaySec = initialSetupDelaySec,
            setupRemainingSec = initialSetupDelaySec,
            frame = engine.emptyFrame(),
            selectedMusicTrack = WorkoutMusicTrack.fromId(
                app.userDb.getPref(PREF_MUSIC_TRACK, WorkoutMusicTrack.TECHNO_PULSE.id)
            ),
            customMusicUri = app.userDb.getPref(PREF_CUSTOM_MUSIC_URI, "").ifBlank { null },
            customMusicName = app.userDb.getPref(PREF_CUSTOM_MUSIC_NAME, "").ifBlank { null }
        )
    )
    val uiState: StateFlow<VisionUiState> = _uiState.asStateFlow()

    // Dedicated full-FPS overlay state isolated from HUD telemetry recompositions
    private val _poseOverlayState = MutableStateFlow(PoseOverlayState(showDetailedAngles = initialDetailedAngles))
    val poseOverlayState: StateFlow<PoseOverlayState> = _poseOverlayState.asStateFlow()

    private var lastRepSpoken = 0
    private var lastHudEmitTsMs = 0L
    private var restTimerJob: Job? = null
    private var setupTimerJob: Job? = null
    private var challengeTickerJob: Job? = null
    private var musicStartedForChallenge = false
    private var lastActiveChallengeConfig: ChallengeConfig? = null

    // Rolling ~2.4s landmark history for zero-lost-first-rep retroactive replay when AUTO switches exercises
    private val recentPoseHistory = ArrayDeque<BufferedPoseFrame>(REPLAY_HISTORY_MAX_FRAMES + 4)

    // Multi-set & multi-exercise session accumulators (always guarded by pipelineLock)
    private var activeSetCaloriesKcal = 0.0
    private var formScoreWeightedSum = 0L
    private var romWeightedSum = 0L
    private var setSamplesWeight = 0L
    private val sessionFaults = linkedSetOf<String>()
    private val exerciseBreakdownMap = linkedMapOf<ExerciseType, ExerciseBreakdownItem>()

    init {
        if (initialSetupDelaySec > 0) {
            startSetupCountdown(initialSetupDelaySec, announce = false)
        }
    }

    fun toggleDetailedAngles() {
        val next = !_uiState.value.showDetailedAngles
        viewModelScope.launch(Dispatchers.IO) {
            app.userDb.setPref(PREF_DETAILED_ANGLES, next.toString())
        }
        _poseOverlayState.update { it.copy(showDetailedAngles = next) }
        _uiState.update { it.copy(showDetailedAngles = next) }
    }

    fun setSetupDelay(seconds: Int) {
        val clamped = if (seconds in SETUP_DELAY_PRESETS) seconds else 5
        viewModelScope.launch(Dispatchers.IO) {
            app.userDb.setPref(PREF_SETUP_DELAY_SEC, clamped.toString())
        }
        _uiState.update { it.copy(setupDelaySec = clamped) }
        if (clamped == 0) {
            skipSetupCountdown()
        }
    }

    fun setDetectionStrictness(mode: DetectionStrictness) {
        synchronized(pipelineLock) {
            engine.setStrictness(mode)
        }
        viewModelScope.launch(Dispatchers.IO) {
            app.userDb.setPref(PREF_STRICTNESS, mode.name)
        }
        _uiState.update { it.copy(detectionStrictness = mode) }
    }

    fun toggleDetectionStrictness() {
        val next = if (_uiState.value.detectionStrictness == DetectionStrictness.ADAPTIVE) {
            DetectionStrictness.STRICT
        } else {
            DetectionStrictness.ADAPTIVE
        }
        setDetectionStrictness(next)
    }

    fun toggleAutoDetect() {
        val next: Boolean
        val selected: ExerciseType
        synchronized(pipelineLock) {
            if (challengeController.isActive) return
            next = !_uiState.value.isAutoDetectEnabled
            selected = engine.currentExercise
            if (next) {
                autoDetector.reset(initialLocked = null)
            } else {
                autoDetector.reset(initialLocked = selected)
            }
        }
        _uiState.update {
            it.copy(
                selectedExercise = selected,
                isAutoDetectEnabled = next,
                autoDetectConfidence = if (next) 0f else 1f,
                autoDetectStatusFr = if (next) "Analyse du mouvement…" else "Mode fixe : ${selected.labelFr}"
            )
        }
    }

    /**
     * 1-tap quick Lock/Unlock on the Vision HUD: pins the currently auto-detected exercise
     * into FIXE mode immediately WITHOUT resetting reps or calories, or unlocks back to AUTO.
     */
    fun lockCurrentAutoExercise() {
        val wasAuto: Boolean
        val current: ExerciseType
        synchronized(pipelineLock) {
            if (challengeController.isActive) return
            wasAuto = _uiState.value.isAutoDetectEnabled
            current = engine.currentExercise
            if (wasAuto) {
                autoDetector.reset(initialLocked = current)
            } else {
                autoDetector.reset(initialLocked = null)
            }
        }
        if (wasAuto) {
            _uiState.update {
                it.copy(
                    selectedExercise = current,
                    isAutoDetectEnabled = false,
                    autoDetectConfidence = 1f,
                    autoDetectStatusFr = "Verrouillé : ${current.labelFr}"
                )
            }
            voiceCoach.speak("${current.labelFr} verrouillé", force = false)
        } else {
            _uiState.update {
                it.copy(
                    isAutoDetectEnabled = true,
                    autoDetectConfidence = 0f,
                    autoDetectStatusFr = "Analyse du mouvement…"
                )
            }
        }
    }

    /** Manual exercise selection from picker locks into FIXE mode and resets the session cleanly. */
    fun selectExercise(exercise: ExerciseType) {
        cancelRestTimer()
        cancelSetupTimer()
        val setupSec = _uiState.value.setupDelaySec
        val emptyFrame: BiomechanicsFrame
        synchronized(pipelineLock) {
            if (challengeController.isActive) {
                stopChallengeSilentlyLocked()
            }
            engine.setExercise(exercise)
            autoDetector.reset(initialLocked = exercise)
            lastRepSpoken = 0
            lastHudEmitTsMs = 0L
            resetMultiSetAccumulatorsLocked()
            emptyFrame = engine.emptyFrame()
        }
        _poseOverlayState.value = PoseOverlayState(showDetailedAngles = _uiState.value.showDetailedAngles)
        _uiState.update {
            it.copy(
                selectedExercise = exercise,
                isAutoDetectEnabled = false,
                autoDetectConfidence = 1f,
                autoDetectStatusFr = "Mode fixe : ${exercise.labelFr}",
                sessionStartMs = System.currentTimeMillis(),
                isSessionActive = true,
                frame = emptyFrame,
                currentSetNumber = 1,
                completedSetsCount = 0,
                sessionTotalReps = 0,
                sessionTotalHoldSec = 0,
                currentSetCaloriesKcal = 0.0,
                sessionTotalCaloriesKcal = 0.0,
                multiExerciseBreakdown = emptyList(),
                restRemainingSec = 0,
                isResting = false,
                setupRemainingSec = setupSec,
                completedSummary = null,
                completedBreakdown = emptyList(),
                activeChallenge = null,
                completedChallengeResult = null,
                errorMessage = null
            )
        }
        if (setupSec > 0) {
            startSetupCountdown(setupSec, announce = true)
        }
    }

    fun toggleCamera() {
        synchronized(pipelineLock) {
            engine.resetTrackingFilters()
            recentPoseHistory.clear()
            val lockTarget = if (_uiState.value.isAutoDetectEnabled) {
                autoDetector.currentLockedExercise
            } else {
                engine.currentExercise
            }
            autoDetector.reset(initialLocked = lockTarget)
        }
        _poseOverlayState.value = PoseOverlayState(showDetailedAngles = _uiState.value.showDetailedAngles)
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

    // -------------------------------------------------------------------------
    // Offline Music & Custom Local Audio Controls
    // -------------------------------------------------------------------------

    fun selectMusicTrack(track: WorkoutMusicTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            app.userDb.setPref(PREF_MUSIC_TRACK, track.id)
        }
        _uiState.update { it.copy(selectedMusicTrack = track, isMusicMuted = false) }
        audioEngine.setMuted(false)
        val challengeActive = synchronized(pipelineLock) {
            val active = challengeController.activeConfig()
            if (active != null) {
                lastActiveChallengeConfig = active.copy(musicTrack = track)
            }
            challengeController.isActive
        }
        if (challengeActive) {
            if (track == WorkoutMusicTrack.NONE) {
                audioEngine.stopMusic()
                musicStartedForChallenge = false
            } else {
                startTrackPlayback(track)
                musicStartedForChallenge = true
            }
        }
    }

    fun setCustomMusicUri(uriString: String, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.userDb.setPref(PREF_CUSTOM_MUSIC_URI, uriString)
            app.userDb.setPref(PREF_CUSTOM_MUSIC_NAME, displayName)
            app.userDb.setPref(PREF_MUSIC_TRACK, WorkoutMusicTrack.CUSTOM_LOCAL.id)
        }
        _uiState.update {
            it.copy(
                customMusicUri = uriString,
                customMusicName = displayName,
                selectedMusicTrack = WorkoutMusicTrack.CUSTOM_LOCAL,
                isMusicMuted = false
            )
        }
        audioEngine.setMuted(false)
        val challengeActive = synchronized(pipelineLock) { challengeController.isActive }
        if (challengeActive) {
            startTrackPlayback(WorkoutMusicTrack.CUSTOM_LOCAL)
            musicStartedForChallenge = true
        }
    }

    fun toggleMusicMute() {
        val next = !_uiState.value.isMusicMuted
        audioEngine.setMuted(next)
        _uiState.update { it.copy(isMusicMuted = next) }
    }

    fun cycleQuickMusicTrack() {
        val state = _uiState.value
        val playable = buildList {
            add(WorkoutMusicTrack.NONE)
            add(WorkoutMusicTrack.TECHNO_PULSE)
            add(WorkoutMusicTrack.FOCUS_SYNTH)
            add(WorkoutMusicTrack.HIIT_DRIVE)
            add(WorkoutMusicTrack.ZEN_BREATH)
            if (!state.customMusicUri.isNullOrBlank()) {
                add(WorkoutMusicTrack.CUSTOM_LOCAL)
            }
        }
        val idx = playable.indexOf(state.selectedMusicTrack)
        val next = playable[(idx + 1) % playable.size]
        selectMusicTrack(next)
    }

    fun setMusicVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        audioEngine.musicVolume = clamped
        _uiState.update { it.copy(musicVolume = clamped) }
    }

    private fun startTrackPlayback(track: WorkoutMusicTrack) {
        val uri = _uiState.value.customMusicUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        audioEngine.startMusic(track, customUri = uri) { err ->
            reportError(err)
        }
    }

    fun pauseBackgroundAudio() {
        audioEngine.stopMusic()
        musicStartedForChallenge = false
    }

    // -------------------------------------------------------------------------
    // Camera-Verified Challenges Lifecycle
    // -------------------------------------------------------------------------

    fun startChallenge(config: ChallengeConfig) {
        cancelRestTimer()
        cancelSetupTimer()
        cancelChallengeTicker()
        audioEngine.stopMusic()
        musicStartedForChallenge = false

        val targetExercise = config.effectiveExercise()
        val resolvedTrack = if (config.musicTrack == WorkoutMusicTrack.CUSTOM_LOCAL && _uiState.value.customMusicUri.isNullOrBlank()) {
            WorkoutMusicTrack.TECHNO_PULSE
        } else {
            config.musicTrack
        }
        val finalConfig = config.copy(exercise = targetExercise, musicTrack = resolvedTrack)
        val now = System.currentTimeMillis()

        val startOutput: ChallengeOutput
        val emptyFrame: BiomechanicsFrame
        synchronized(pipelineLock) {
            lastActiveChallengeConfig = finalConfig
            engine.setExercise(targetExercise)
            autoDetector.reset(initialLocked = targetExercise)
            lastRepSpoken = 0
            lastHudEmitTsMs = 0L
            resetMultiSetAccumulatorsLocked()
            startOutput = challengeController.start(finalConfig, now)
            emptyFrame = engine.emptyFrame()
        }

        viewModelScope.launch(Dispatchers.IO) {
            app.userDb.setPref(PREF_MUSIC_TRACK, resolvedTrack.id)
        }

        _poseOverlayState.value = PoseOverlayState(showDetailedAngles = _uiState.value.showDetailedAngles)
        _uiState.update {
            it.copy(
                selectedExercise = targetExercise,
                isAutoDetectEnabled = false,
                autoDetectConfidence = 1f,
                autoDetectStatusFr = "Défi · ${finalConfig.type.shortTitleFr}",
                sessionStartMs = now,
                isSessionActive = true,
                frame = emptyFrame,
                currentSetNumber = 1,
                completedSetsCount = 0,
                sessionTotalReps = 0,
                sessionTotalHoldSec = 0,
                currentSetCaloriesKcal = 0.0,
                sessionTotalCaloriesKcal = 0.0,
                multiExerciseBreakdown = emptyList(),
                isResting = false,
                restRemainingSec = 0,
                setupRemainingSec = 0,
                completedSummary = null,
                completedBreakdown = emptyList(),
                selectedMusicTrack = resolvedTrack,
                isMusicMuted = false,
                activeChallenge = startOutput.snapshot,
                completedChallengeResult = null,
                errorMessage = null
            )
        }

        audioEngine.setMuted(false)
        applyChallengeOutputEffects(startOutput)
        launchChallengeTicker()
    }

    fun replayLastChallenge() {
        val cfg = synchronized(pipelineLock) { lastActiveChallengeConfig } ?: return
        startChallenge(cfg)
    }

    fun stopActiveChallenge() {
        val now = System.currentTimeMillis()
        val out = synchronized(pipelineLock) {
            val kcal = _uiState.value.sessionTotalCaloriesKcal + activeSetCaloriesKcal
            challengeController.finishEarly(now, kcal)
        }
        cancelChallengeTicker()
        audioEngine.stopMusic()
        musicStartedForChallenge = false
        if (out != null) {
            applyChallengeOutputEffects(out)
        } else {
            _uiState.update { it.copy(activeChallenge = null) }
        }
    }

    fun dismissChallengeResult() {
        audioEngine.stopMusic()
        musicStartedForChallenge = false
        _uiState.update {
            it.copy(
                activeChallenge = null,
                completedChallengeResult = null,
                isSessionActive = true
            )
        }
        resetSession()
    }

    fun bestRecordFor(type: ChallengeType, exercise: ExerciseType): ChallengeRecord? =
        app.userDb.bestRecordFor(type.id, exercise.id, lowerIsBetter = type.lowerIsBetter)

    private fun stopChallengeSilentlyLocked() {
        cancelChallengeTicker()
        challengeController.stopAndReset()
        audioEngine.stopMusic()
        musicStartedForChallenge = false
    }

    private fun launchChallengeTicker() {
        cancelChallengeTicker()
        challengeTickerJob = viewModelScope.launch {
            while (synchronized(pipelineLock) { challengeController.isActive }) {
                delay(150L)
                val out = synchronized(pipelineLock) {
                    if (!challengeController.isActive) return@synchronized null
                    val state = _uiState.value
                    val kcal = state.sessionTotalCaloriesKcal + activeSetCaloriesKcal
                    val frameArg = if (state.frame.poseDetected) state.frame.copy(repCompletedNow = false) else null
                    challengeController.onFrame(frameArg, System.currentTimeMillis(), kcal)
                }
                if (out != null) {
                    applyChallengeOutputEffects(out)
                }
            }
        }
    }

    private fun cancelChallengeTicker() {
        challengeTickerJob?.cancel()
        challengeTickerJob = null
    }

    private fun applyChallengeOutputEffects(output: ChallengeOutput) {
        output.beepsToPlay.forEach { beep -> audioEngine.playBeep(beep) }
        output.voiceAnnouncementFr?.let { cue ->
            voiceCoach.speak(cue, isRepCounter = true, force = true)
        }

        val track = _uiState.value.selectedMusicTrack
        if (output.completedResult != null) {
            cancelChallengeTicker()
            audioEngine.stopMusic()
            musicStartedForChallenge = false
            handleChallengeCompleted(output.completedResult)
            return
        }

        if (track != WorkoutMusicTrack.NONE) {
            if (output.musicShouldPlay) {
                if (!musicStartedForChallenge) {
                    startTrackPlayback(track)
                    musicStartedForChallenge = true
                }
                audioEngine.setMusicPaused(false)
                audioEngine.setDucked(output.musicDucked)
                audioEngine.setFinalSprint(output.musicFinalSprint)
            } else if (musicStartedForChallenge) {
                audioEngine.setMusicPaused(true)
            }
        }

        _uiState.update { it.copy(activeChallenge = output.snapshot) }
    }

    private fun handleChallengeCompleted(rawResult: ChallengeResult) {
        val cfg = rawResult.config
        val ex = cfg.effectiveExercise()
        val prevBest = app.userDb.bestRecordFor(cfg.type.id, ex.id, lowerIsBetter = cfg.type.lowerIsBetter)

        val eligibleForPr = when (cfg.type) {
            ChallengeType.TARGET_REPS_RACE -> rawResult.completedGoal && rawResult.scoreValue > 0.0
            else -> rawResult.scoreValue > 0.0
        }
        val beatsPrevious = eligibleForPr && (
            prevBest == null ||
                if (cfg.type.lowerIsBetter) rawResult.scoreValue < prevBest.scoreValue
                else rawResult.scoreValue > prevBest.scoreValue
            )

        val enriched = rawResult.copy(
            isNewRecord = beatsPrevious,
            previousRecordLabel = prevBest?.scoreLabel
        )

        viewModelScope.launch(Dispatchers.IO) {
            if (eligibleForPr) {
                app.userDb.insertChallengeRecord(
                    ChallengeRecord(
                        timestampMs = System.currentTimeMillis(),
                        challengeId = cfg.type.id,
                        exerciseId = ex.id,
                        scoreValue = enriched.scoreValue,
                        scoreLabel = enriched.scoreLabel,
                        durationSec = enriched.durationSec,
                        caloriesKcal = enriched.caloriesKcal
                    )
                )
            }
            if (enriched.totalReps > 0 || (ex.isIsometric && enriched.durationSec >= 3)) {
                app.userDb.insertSession(
                    WorkoutSession(
                        timestampMs = System.currentTimeMillis(),
                        exerciseType = ex,
                        reps = enriched.totalReps,
                        durationSec = enriched.durationSec,
                        avgFormScore = enriched.avgFormScore,
                        avgRomPct = _uiState.value.frame.avgRomPct.coerceAtLeast(if (ex.isIsometric) 100 else 75),
                        topFaults = _uiState.value.frame.topFaults,
                        notes = "Défi · ${cfg.type.shortTitleFr} (${enriched.scoreLabel})",
                        caloriesKcal = enriched.caloriesKcal
                    )
                )
            }
        }

        _uiState.update {
            it.copy(
                activeChallenge = null,
                isSessionActive = false,
                completedChallengeResult = enriched
            )
        }
    }

    // -------------------------------------------------------------------------
    // Rest Timer & Standard Multi-Set Flow
    // -------------------------------------------------------------------------

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
        val restSec = state.restDurationSec
        val setReps: Int
        val setHold: Int
        val setKcal: Double
        val breakdownSnapshot: List<ExerciseBreakdownItem>
        val emptyFrame: BiomechanicsFrame

        synchronized(pipelineLock) {
            val frame = state.frame
            val activeEx = engine.currentExercise
            setKcal = activeSetCaloriesKcal
            bankActiveExerciseWorkLocked(activeEx, frame, setKcal)

            setReps = if (activeEx.isIsometric) 0 else frame.repCount
            setHold = if (activeEx.isIsometric) frame.isometricHoldSec.toInt() else 0

            // Archive set VBT telemetry & keep cross-set fresh baseline for Set 2+ readiness & fatigue estimation
            engine.completeSet()
            voiceCoach.resetCooldowns()
            lastRepSpoken = 0
            lastHudEmitTsMs = 0L
            activeSetCaloriesKcal = 0.0
            breakdownSnapshot = exerciseBreakdownMap.values.toList()
            emptyFrame = engine.emptyFrame()
        }

        _poseOverlayState.value = PoseOverlayState(showDetailedAngles = _uiState.value.showDetailedAngles)
        _uiState.update {
            it.copy(
                currentSetNumber = it.currentSetNumber + 1,
                completedSetsCount = it.completedSetsCount + 1,
                sessionTotalReps = it.sessionTotalReps + setReps,
                sessionTotalHoldSec = it.sessionTotalHoldSec + setHold,
                sessionTotalCaloriesKcal = it.sessionTotalCaloriesKcal + setKcal,
                currentSetCaloriesKcal = 0.0,
                multiExerciseBreakdown = breakdownSnapshot,
                frame = emptyFrame,
                isResting = true,
                restRemainingSec = restSec,
                setupRemainingSec = 0
            )
        }
        cancelSetupTimer()
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
        synchronized(pipelineLock) {
            engine.armAfterSetup()
            recentPoseHistory.clear()
        }
        _uiState.update {
            it.copy(isResting = false, restRemainingSec = 0)
        }
    }

    // -------------------------------------------------------------------------
    // Hands-Free Setup Countdown ("Chrono d'installation")
    // -------------------------------------------------------------------------

    fun cycleSetupDelay() {
        val current = _uiState.value.setupDelaySec
        val idx = SETUP_DELAY_PRESETS.indexOf(current).coerceAtLeast(0)
        val next = SETUP_DELAY_PRESETS[(idx + 1) % SETUP_DELAY_PRESETS.size]
        viewModelScope.launch(Dispatchers.IO) {
            app.userDb.setPref(PREF_SETUP_DELAY_SEC, next.toString())
        }
        _uiState.update { it.copy(setupDelaySec = next) }
        if (next == 0) {
            skipSetupCountdown()
        } else if (!_uiState.value.isResting &&
            _uiState.value.activeChallenge == null &&
            _uiState.value.frame.repCount == 0 &&
            _uiState.value.frame.isometricHoldSec < 1f
        ) {
            startSetupCountdown(next, announce = true)
        }
    }

    fun startSetupCountdown(seconds: Int = _uiState.value.setupDelaySec, announce: Boolean = true) {
        cancelSetupTimer()
        val safeSec = seconds.coerceIn(0, 30)
        synchronized(pipelineLock) {
            engine.armAfterSetup()
            recentPoseHistory.clear()
            if (_uiState.value.isAutoDetectEnabled) {
                autoDetector.reset(initialLocked = null)
            }
        }
        if (safeSec <= 0) {
            _uiState.update { it.copy(setupRemainingSec = 0) }
            return
        }
        _uiState.update { it.copy(setupRemainingSec = safeSec) }
        if (announce && safeSec >= 4) {
            voiceCoach.speak("Installez-vous, $safeSec secondes.", force = true)
        }
        setupTimerJob = viewModelScope.launch {
            while (true) {
                val rem = _uiState.value.setupRemainingSec
                if (rem <= 0) break
                delay(1000L)
                val updated = (_uiState.value.setupRemainingSec - 1).coerceAtLeast(0)
                if (updated == 0) {
                    synchronized(pipelineLock) {
                        engine.armAfterSetup()
                        recentPoseHistory.clear()
                    }
                    _uiState.update {
                        it.copy(
                            setupRemainingSec = 0,
                            sessionStartMs = System.currentTimeMillis()
                        )
                    }
                    voiceCoach.speak("C'est parti !", force = true)
                    break
                } else {
                    _uiState.update { it.copy(setupRemainingSec = updated) }
                    if (updated in 1..3) {
                        voiceCoach.speak("$updated", isRepCounter = true)
                    }
                }
            }
        }
    }

    fun addSetupSeconds(deltaSec: Int) {
        if (!_uiState.value.isSettingUp) return
        _uiState.update {
            it.copy(setupRemainingSec = (it.setupRemainingSec + deltaSec).coerceIn(1, 30))
        }
    }

    fun skipSetupCountdown() {
        cancelSetupTimer()
        synchronized(pipelineLock) {
            engine.armAfterSetup()
            recentPoseHistory.clear()
        }
        _uiState.update {
            it.copy(
                setupRemainingSec = 0,
                sessionStartMs = System.currentTimeMillis()
            )
        }
    }

    private fun cancelSetupTimer() {
        setupTimerJob?.cancel()
        setupTimerJob = null
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
                    synchronized(pipelineLock) {
                        engine.armAfterSetup()
                        recentPoseHistory.clear()
                    }
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

    fun onPoseLandmarks(
        screenPoints: List<PosePoint>,
        worldPoints: List<PosePoint>,
        width: Int,
        height: Int,
        timestampMs: Long
    ) {
        val state = _uiState.value
        if (!state.isSessionActive) return

        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val profile = app.userDb.profile.value

        var overlaySnapshot: PoseOverlayState? = null
        var hudFrame: BiomechanicsFrame? = null
        var activeExercise = state.selectedExercise
        var autoConf = state.autoDetectConfidence
        var autoStatus = state.autoDetectStatusFr
        var bankedRepsDelta = 0
        var bankedHoldDelta = 0
        var bankedKcalDelta = 0.0
        var currentSetKcalSnapshot = 0.0
        var breakdownSnapshot: List<ExerciseBreakdownItem>? = null
        var challengeOutput: ChallengeOutput? = null
        var voiceToSpeak: Pair<String, Boolean>? = null // text to isRepCounter
        var shouldUpdateHud = false

        synchronized(pipelineLock) {
            if (!_uiState.value.isSessionActive) return

            val inChallenge = challengeController.isActive
            engine.athleteHeightMeters = (profile.heightCm / 100f).coerceIn(1.30f, 2.25f)

            // During Setup Countdown or Rest Timer: keep smooth 60 FPS skeleton framing preview
            // while completely disarming rep counting, VBT, auto-switching, calories, and posture faults!
            if (!inChallenge && (state.isSettingUp || state.isResting)) {
                recentPoseHistory.clear()
                val previewMsg = if (state.isSettingUp) {
                    "Installation (${state.setupRemainingSec}s) — posez le téléphone et mettez-vous en place"
                } else {
                    "Repos (${state.restRemainingSec}s) — comptage en pause"
                }
                val previewFrame = engine.previewPoseOnly(screenPoints, worldPoints, timestampMs, previewMsg)
                overlaySnapshot = PoseOverlayState(
                    landmarks = previewFrame.landmarks,
                    angleBadges = emptyList(),
                    corrections = emptyList(),
                    formQuality = FormQuality.GOOD,
                    imageWidth = safeW,
                    imageHeight = safeH,
                    viewpointInfo = previewFrame.viewpointInfo,
                    showDetailedAngles = state.showDetailedAngles
                )
                val prevFrame = state.frame
                if (previewFrame.poseDetected != prevFrame.poseDetected ||
                    previewFrame.feedbackFr != prevFrame.feedbackFr ||
                    previewFrame.viewpointInfo.viewpoint != state.viewpointInfo.viewpoint ||
                    (timestampMs - lastHudEmitTsMs) >= HUD_MINOR_UPDATE_INTERVAL_MS
                ) {
                    lastHudEmitTsMs = timestampMs
                    hudFrame = previewFrame.copy(landmarks = emptyList(), worldLandmarks = emptyList())
                    shouldUpdateHud = true
                }
            } else {
                // Record rolling ~2.4s pose buffer for zero-lost-first-rep retroactive AUTO replay
                if (!inChallenge && state.isAutoDetectEnabled && screenPoints.size >= 29) {
                    recentPoseHistory.addLast(BufferedPoseFrame(screenPoints, worldPoints, timestampMs))
                    while (recentPoseHistory.size > REPLAY_HISTORY_MAX_FRAMES ||
                        (recentPoseHistory.size > 2 && (timestampMs - recentPoseHistory.first().timestampMs) > REPLAY_HISTORY_WINDOW_MS)
                    ) {
                        recentPoseHistory.removeFirst()
                    }
                }

                // 1. Run 3D multi-angle biomechanics + VBT velocity analysis + physics sanity gate first
                val rawFrame = engine.processFrame(screenPoints, worldPoints, timestampMs)
                var effectiveFrame = rawFrame
                activeExercise = engine.currentExercise

                // 2. Profile-calibrated real-time calorie expenditure accumulation
                if (rawFrame.poseDetected && !rawFrame.isStabilizing) {
                    if (rawFrame.repCompletedNow) {
                        activeSetCaloriesKcal += CalorieEngine.estimateIncrementalRepCalories(
                            exercise = rawFrame.exercise,
                            romPct = rawFrame.completedRepRomPct.coerceAtLeast(35),
                            repDurationSec = rawFrame.completedRepDurationSec.toDouble(),
                            profile = profile
                        )
                    }
                    if (rawFrame.exercise.isIsometric && rawFrame.isometricDeltaSec > 0f) {
                        activeSetCaloriesKcal += CalorieEngine.estimateIsometricCalories(
                            exercise = rawFrame.exercise,
                            holdSec = rawFrame.isometricDeltaSec.toDouble(),
                            profile = profile
                        )
                    }
                }

                // 3. Real-time anti-glitch automatic exercise detection (paused during active challenge)
                var switchedExerciseNow = false
                if (!inChallenge && state.isAutoDetectEnabled && screenPoints.size >= 29) {
                    val detect = autoDetector.processFrame(
                        rawScreenPoints = screenPoints,
                        rawWorldPoints = worldPoints,
                        timestampMs = timestampMs,
                        isMidRep = engine.isMidRep,
                        hasActiveSetReps = engine.hasActiveSetReps,
                        isTransitioning = rawFrame.isStabilizing
                    )
                    autoConf = detect.confidence
                    autoStatus = detect.statusFr
                    val detected = detect.detectedExercise
                    if (detected != null && detected != engine.currentExercise) {
                        val prevEx = engine.currentExercise
                        bankActiveExerciseWorkLocked(prevEx, rawFrame, activeSetCaloriesKcal)
                        bankedRepsDelta = if (prevEx.isIsometric) 0 else rawFrame.repCount
                        bankedHoldDelta = if (prevEx.isIsometric) rawFrame.isometricHoldSec.toInt() else 0
                        bankedKcalDelta = activeSetCaloriesKcal
                        activeSetCaloriesKcal = 0.0
                        breakdownSnapshot = exerciseBreakdownMap.values.toList()

                        // Retroactive replay when switching from idle/0-rep or Plank into a dynamic exercise:
                        // guarantees Rep #1 is synced live (or immediately credited) instead of ever being eaten!
                        val shouldReplayFirstRep = !detected.isIsometric &&
                            (rawFrame.repCount == 0 || prevEx == ExerciseType.PLANK) &&
                            recentPoseHistory.size >= 6
                        val replayedFrame = if (shouldReplayFirstRep) {
                            engine.switchExerciseWithReplay(detected, recentPoseHistory.toList())
                        } else {
                            engine.switchExerciseSmoothly(detected)
                            null
                        }
                        recentPoseHistory.clear()
                        activeExercise = detected
                        switchedExerciseNow = true

                        if (replayedFrame != null) {
                            effectiveFrame = replayedFrame
                            if (replayedFrame.repCount > 0) {
                                lastRepSpoken = replayedFrame.repCount
                                activeSetCaloriesKcal += CalorieEngine.estimateIncrementalRepCalories(
                                    exercise = detected,
                                    romPct = replayedFrame.avgRomPct.coerceAtLeast(60),
                                    repDurationSec = 1.6,
                                    profile = profile
                                ) * replayedFrame.repCount
                                voiceToSpeak = "${replayedFrame.repCount}" to true
                            } else {
                                lastRepSpoken = 0
                                voiceToSpeak = detected.labelFr to false
                            }
                        } else {
                            lastRepSpoken = 0
                            voiceToSpeak = detected.labelFr to false
                            effectiveFrame = rawFrame.copy(
                                exercise = detected,
                                repCount = 0,
                                isometricHoldSec = 0f,
                                phase = MovementPhase.READY,
                                romPct = 0,
                                repCompletedNow = false,
                                completedRepRomPct = 0,
                                completedRepDurationSec = 0f,
                                isometricDeltaSec = 0f,
                                detectedVariantFr = null,
                                velocityTelemetry = engine.currentVelocityTelemetry()
                            )
                        }
                    }
                }

                currentSetKcalSnapshot = activeSetCaloriesKcal

                // 4. Advance active camera-verified Challenge OR prepare anti-spam Voice Coach cue
                if (inChallenge) {
                    val totalKcal = state.sessionTotalCaloriesKcal + bankedKcalDelta + currentSetKcalSnapshot
                    challengeOutput = challengeController.onFrame(effectiveFrame, System.currentTimeMillis(), totalKcal)
                } else if (!switchedExerciseNow && effectiveFrame.poseDetected && !effectiveFrame.isStabilizing && !state.isResting) {
                    if (!effectiveFrame.exercise.isIsometric && effectiveFrame.repCount > lastRepSpoken) {
                        lastRepSpoken = effectiveFrame.repCount
                        voiceToSpeak = (effectiveFrame.voiceCueFr ?: "${effectiveFrame.repCount}") to true
                    } else {
                        // Strictly honor PostureFeedbackCoach's rate-limited voiceCueFr (never bypass to raw corrections!)
                        effectiveFrame.voiceCueFr?.let { spokenCue ->
                            voiceToSpeak = spokenCue to false
                        }
                    }
                }

                // 5. Full-FPS isolated overlay state
                overlaySnapshot = PoseOverlayState(
                    landmarks = effectiveFrame.landmarks,
                    angleBadges = effectiveFrame.angleBadges,
                    corrections = effectiveFrame.corrections,
                    formQuality = effectiveFrame.formQuality,
                    imageWidth = safeW,
                    imageHeight = safeH,
                    viewpointInfo = effectiveFrame.viewpointInfo,
                    showDetailedAngles = state.showDetailedAngles
                )

            // 6. Deduplicate & throttle HUD updates so Compose UI never churns on sub-pixel noise
            val prevFrame = state.frame
            val vbt = effectiveFrame.velocityTelemetry
            val prevVbt = prevFrame.velocityTelemetry
            val discreteChanged = switchedExerciseNow ||
                activeExercise != state.selectedExercise ||
                effectiveFrame.repCount != prevFrame.repCount ||
                effectiveFrame.phase != prevFrame.phase ||
                effectiveFrame.poseDetected != prevFrame.poseDetected ||
                effectiveFrame.isStabilizing != prevFrame.isStabilizing ||
                effectiveFrame.formQuality != prevFrame.formQuality ||
                effectiveFrame.feedbackFr != prevFrame.feedbackFr ||
                effectiveFrame.detectedVariantFr != prevFrame.detectedVariantFr ||
                effectiveFrame.viewpointInfo.viewpoint != state.viewpointInfo.viewpoint ||
                effectiveFrame.viewpointInfo.is3DActive != state.viewpointInfo.is3DActive ||
                effectiveFrame.viewpointInfo.framingWarningFr != state.viewpointInfo.framingWarningFr ||
                effectiveFrame.corrections.firstOrNull()?.shortBadgeFr != prevFrame.corrections.firstOrNull()?.shortBadgeFr ||
                effectiveFrame.corrections.firstOrNull()?.instructionFr != prevFrame.corrections.firstOrNull()?.instructionFr ||
                vbt.estimatedMaxReps != prevVbt.estimatedMaxReps ||
                vbt.estimatedRir != prevVbt.estimatedRir ||
                vbt.fatigueZone != prevVbt.fatigueZone ||
                vbt.velocityLossPct != prevVbt.velocityLossPct ||
                autoStatus != state.autoDetectStatusFr ||
                bankedRepsDelta > 0 || bankedHoldDelta > 0 || bankedKcalDelta > 0.0

            val continuousChanged = abs(effectiveFrame.formScore - prevFrame.formScore) >= 2 ||
                abs(effectiveFrame.romPct - prevFrame.romPct) >= 3 ||
                abs(effectiveFrame.isometricHoldSec - prevFrame.isometricHoldSec) >= 0.1f ||
                abs(currentSetKcalSnapshot - state.currentSetCaloriesKcal) >= 0.05 ||
                abs(autoConf - state.autoDetectConfidence) >= 0.03f ||
                abs(effectiveFrame.viewpointInfo.azimuthDeg - state.viewpointInfo.azimuthDeg) >= 2 ||
                abs(vbt.currentLiveVelocityMps - prevVbt.currentLiveVelocityMps) >= 0.04f

            shouldUpdateHud = discreteChanged ||
                (continuousChanged && (timestampMs - lastHudEmitTsMs) >= HUD_MINOR_UPDATE_INTERVAL_MS)

                if (shouldUpdateHud) {
                    lastHudEmitTsMs = timestampMs
                    // Strip heavy landmark coordinates from HUD state (Canvas consumes poseOverlayState directly)
                    hudFrame = effectiveFrame.copy(landmarks = emptyList(), worldLandmarks = emptyList())
                }
            }
        }

        // Emit state flows and side effects cleanly outside pipelineLock
        overlaySnapshot?.let { _poseOverlayState.value = it }
        challengeOutput?.let { applyChallengeOutputEffects(it) }
        voiceToSpeak?.let { (cue, isRep) ->
            voiceCoach.speak(cue, isRepCounter = isRep)
        }

        if (shouldUpdateHud && hudFrame != null) {
            val frameToEmit = hudFrame!!
            _uiState.update {
                it.copy(
                    selectedExercise = activeExercise,
                    autoDetectConfidence = autoConf,
                    autoDetectStatusFr = autoStatus,
                    imageWidth = safeW,
                    imageHeight = safeH,
                    frame = frameToEmit,
                    viewpointInfo = frameToEmit.viewpointInfo,
                    sessionTotalReps = it.sessionTotalReps + bankedRepsDelta,
                    sessionTotalHoldSec = it.sessionTotalHoldSec + bankedHoldDelta,
                    sessionTotalCaloriesKcal = it.sessionTotalCaloriesKcal + bankedKcalDelta,
                    currentSetCaloriesKcal = currentSetKcalSnapshot,
                    multiExerciseBreakdown = breakdownSnapshot ?: it.multiExerciseBreakdown
                )
            }
        }
    }

    fun resetSession() {
        cancelRestTimer()
        cancelSetupTimer()
        val setupSec = _uiState.value.setupDelaySec
        val emptyFrame: BiomechanicsFrame
        synchronized(pipelineLock) {
            if (challengeController.isActive) {
                stopChallengeSilentlyLocked()
            }
            engine.resetSession()
            voiceCoach.resetCooldowns()
            if (_uiState.value.isAutoDetectEnabled) {
                autoDetector.reset(initialLocked = null)
            } else {
                autoDetector.reset(initialLocked = engine.currentExercise)
            }
            lastRepSpoken = 0
            lastHudEmitTsMs = 0L
            resetMultiSetAccumulatorsLocked()
            emptyFrame = engine.emptyFrame()
        }
        _poseOverlayState.value = PoseOverlayState(showDetailedAngles = _uiState.value.showDetailedAngles)
        _uiState.update {
            it.copy(
                sessionStartMs = System.currentTimeMillis(),
                isSessionActive = true,
                frame = emptyFrame,
                currentSetNumber = 1,
                completedSetsCount = 0,
                sessionTotalReps = 0,
                sessionTotalHoldSec = 0,
                currentSetCaloriesKcal = 0.0,
                sessionTotalCaloriesKcal = 0.0,
                multiExerciseBreakdown = emptyList(),
                isResting = false,
                restRemainingSec = 0,
                setupRemainingSec = setupSec,
                completedSummary = null,
                completedBreakdown = emptyList(),
                completedVelocityTelemetry = null,
                activeChallenge = null,
                completedChallengeResult = null
            )
        }
        if (setupSec > 0) {
            startSetupCountdown(setupSec, announce = true)
        }
    }

    fun finishSession() {
        if (synchronized(pipelineLock) { challengeController.isActive }) {
            stopActiveChallenge()
            return
        }
        cancelRestTimer()
        cancelSetupTimer()
        audioEngine.stopMusic()

        val session: WorkoutSession
        val completedBreakdownList: List<ExerciseBreakdownItem>
        val finalVelocityTelemetry: VelocityTelemetry

        synchronized(pipelineLock) {
            val state = _uiState.value
            val frame = state.frame
            val activeEx = engine.currentExercise
            val liveSetKcal = activeSetCaloriesKcal
            finalVelocityTelemetry = engine.currentVelocityTelemetry()

            val breakdownSnapshot = LinkedHashMap(exerciseBreakdownMap)
            val curReps = if (activeEx.isIsometric) 0 else frame.repCount
            val curHold = if (activeEx.isIsometric) frame.isometricHoldSec.toInt() else 0
            if (curReps > 0 || curHold > 0 || liveSetKcal > 0.0) {
                val existing = breakdownSnapshot[activeEx]
                breakdownSnapshot[activeEx] = ExerciseBreakdownItem(
                    exercise = activeEx,
                    reps = (existing?.reps ?: 0) + curReps,
                    holdSec = (existing?.holdSec ?: 0) + curHold,
                    caloriesKcal = (existing?.caloriesKcal ?: 0.0) + liveSetKcal
                )
            }

            val totalReps = state.sessionTotalReps + frame.repCount
            val totalHoldSec = state.sessionTotalHoldSec + frame.isometricHoldSec.toInt()
            val elapsedSec = if (activeEx.isIsometric && breakdownSnapshot.size <= 1) {
                totalHoldSec.coerceAtLeast(1)
            } else {
                ((System.currentTimeMillis() - state.sessionStartMs) / 1000L).toInt().coerceAtLeast(1)
            }

            val currentWeight = maxOf(0, if (activeEx.isIsometric) curHold else curReps).toLong()
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

            val primaryExercise = breakdownSnapshot.values
                .maxByOrNull { it.caloriesKcal + it.reps * 0.5 + it.holdSec * 0.2 }
                ?.exercise ?: activeEx

            var totalCalories = state.sessionTotalCaloriesKcal + liveSetKcal
            if (totalCalories <= 0.0 && (totalReps > 0 || totalHoldSec >= 3)) {
                totalCalories = CalorieEngine.estimateSetCalories(
                    exercise = primaryExercise,
                    reps = totalReps,
                    activeDurationSec = elapsedSec,
                    avgRomPct = finalAvgRom.coerceAtLeast(50),
                    profile = app.userDb.profile.value
                )
            }

            val notesText = buildString {
                if (breakdownSnapshot.size > 1) {
                    append(breakdownSnapshot.values.joinToString(" + ") { item ->
                        if (item.exercise.isIsometric) "${item.exercise.labelFr} ${item.holdSec}s"
                        else "${item.exercise.labelFr} ×${item.reps}"
                    })
                } else if (totalSetsDone > 1) {
                    append("$totalSetsDone séries")
                }
            }

            val dominantVariant = engine.dominantVariantLabel() ?: frame.detectedVariantFr.orEmpty()
            val bestEstMax = finalVelocityTelemetry.estimatedFreshMaxReps
                ?: finalVelocityTelemetry.estimatedMaxReps
                ?: finalVelocityTelemetry.completedSets.mapNotNull { it.estimatedMaxReps }.maxOrNull()
                ?: 0
            val bestPeakVel = maxOf(
                finalVelocityTelemetry.sessionFreshBaselineMps,
                finalVelocityTelemetry.baselineVelocityMps,
                finalVelocityTelemetry.completedSets.maxOfOrNull { it.baselineVelocityMps } ?: 0f
            )
            val finalVlPct = if (finalVelocityTelemetry.repSamples.isNotEmpty()) {
                finalVelocityTelemetry.velocityLossPct
            } else {
                finalVelocityTelemetry.completedSets.lastOrNull()?.velocityLossPct ?: 0
            }

            session = WorkoutSession(
                timestampMs = System.currentTimeMillis(),
                exerciseType = primaryExercise,
                reps = totalReps,
                durationSec = elapsedSec,
                avgFormScore = finalAvgForm,
                avgRomPct = finalAvgRom,
                topFaults = combinedFaults,
                notes = notesText,
                caloriesKcal = totalCalories,
                detectedVariant = dominantVariant,
                estimatedMaxReps = bestEstMax,
                peakVelocityMps = bestPeakVel,
                velocityLossPct = finalVlPct
            )
            completedBreakdownList = breakdownSnapshot.values.toList()
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (session.reps > 0 || (session.exerciseType.isIsometric && session.durationSec >= 3) || session.caloriesKcal >= 0.5) {
                app.userDb.insertSession(session)
            }
        }

        _uiState.update {
            it.copy(
                isSessionActive = false,
                isResting = false,
                restRemainingSec = 0,
                setupRemainingSec = 0,
                completedSummary = session,
                completedBreakdown = completedBreakdownList,
                completedVelocityTelemetry = finalVelocityTelemetry
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
        val variantTag = if (session.detectedVariant.isNotBlank()) " (variante ${session.detectedVariant})" else ""
        val metricStr = if (session.exerciseType.isIsometric) {
            "Maintien aligné : ${session.durationSec} secondes"
        } else {
            val setsSuffix = if (session.notes.isNotBlank()) " (${session.notes})" else ""
            "${session.reps} répétitions$setsSuffix en ${session.durationSec}s"
        }
        val kcalStr = String.format(Locale.US, "%.1f kcal", session.caloriesKcal)
        val vbtStr = if (session.estimatedMaxReps > 0 || session.peakVelocityMps > 0.05f) {
            val peakStr = String.format(Locale.US, "%.2f m/s", session.peakVelocityMps)
            val maxStr = if (session.estimatedMaxReps > 0) ", Rep Max estimé ~${session.estimatedMaxReps} reps" else ""
            " Télémétrie vitesse (VBT) : pic concentrique $peakStr, perte de vitesse -${session.velocityLossPct}%$maxStr."
        } else {
            ""
        }
        val vp = _uiState.value.viewpointInfo
        val angleStr = if (vp.is3DActive) {
            "Caméra 3D (${vp.viewpoint.shortLabelFr}, azimut ${vp.azimuthDeg}°)"
        } else {
            "Caméra ${vp.viewpoint.shortLabelFr}"
        }
        val activeCorrections = _uiState.value.frame.corrections
            .take(2)
            .joinToString("; ") { "${it.shortBadgeFr} : ${it.instructionFr}" }
        val corrSuffix = if (activeCorrections.isNotBlank()) " Corrections angulaires relevées : $activeCorrections." else ""

        return "Analyse ma séance Vision KINETIX 3D de ${session.exerciseType.labelFr}$variantTag : " +
            "$metricStr, dépense estimée $kcalStr (calibrée sur mon profil), " +
            "score postural moyen ${session.avgFormScore}/100, " +
            "amplitude moyenne (ROM) ${session.avgRomPct}%, $angleStr.$vbtStr " +
            "Défauts relevés : $faultsText.$corrSuffix " +
            "Donne-moi un débriefing biomécanique et VBT précis en 3 axes : points forts, corrections techniques concrètes et recommandation de charge/volume pour la prochaine séance."
    }

    private fun bankActiveExerciseWorkLocked(
        exercise: ExerciseType,
        frame: BiomechanicsFrame,
        setKcal: Double
    ) {
        val setReps = if (exercise.isIsometric) 0 else frame.repCount
        val setHold = if (exercise.isIsometric) frame.isometricHoldSec.toInt() else 0
        if (setReps <= 0 && setHold <= 0 && setKcal <= 0.0) return

        val weight = maxOf(1, setReps, setHold).toLong()
        formScoreWeightedSum += frame.avgFormScore.toLong() * weight
        romWeightedSum += frame.avgRomPct.toLong() * weight
        setSamplesWeight += weight
        sessionFaults.addAll(frame.topFaults)

        val prev = exerciseBreakdownMap[exercise]
        exerciseBreakdownMap[exercise] = ExerciseBreakdownItem(
            exercise = exercise,
            reps = (prev?.reps ?: 0) + setReps,
            holdSec = (prev?.holdSec ?: 0) + setHold,
            caloriesKcal = (prev?.caloriesKcal ?: 0.0) + setKcal
        )
    }

    private fun resetMultiSetAccumulatorsLocked() {
        recentPoseHistory.clear()
        activeSetCaloriesKcal = 0.0
        formScoreWeightedSum = 0L
        romWeightedSum = 0L
        setSamplesWeight = 0L
        sessionFaults.clear()
        exerciseBreakdownMap.clear()
    }

    override fun onCleared() {
        super.onCleared()
        cancelRestTimer()
        cancelSetupTimer()
        cancelChallengeTicker()
        audioEngine.release()
        voiceCoach.shutdown()
    }
}
