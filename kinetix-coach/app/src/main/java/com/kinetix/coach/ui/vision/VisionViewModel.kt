package com.kinetix.coach.ui.vision

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kinetix.coach.KinetixApp
import com.kinetix.coach.data.ChallengeRecord
import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.WorkoutSession
import com.kinetix.coach.vision.AutoExerciseDetector
import com.kinetix.coach.vision.BiomechanicsEngine
import com.kinetix.coach.vision.BiomechanicsFrame
import com.kinetix.coach.vision.CalorieEngine
import com.kinetix.coach.vision.ChallengeConfig
import com.kinetix.coach.vision.ChallengeController
import com.kinetix.coach.vision.ChallengeOutput
import com.kinetix.coach.vision.ChallengeResult
import com.kinetix.coach.vision.ChallengeSnapshot
import com.kinetix.coach.vision.ChallengeType
import com.kinetix.coach.vision.DetectionStrictness
import com.kinetix.coach.vision.FormQuality
import com.kinetix.coach.vision.PosePoint
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
    val completedSummary: WorkoutSession? = null,
    val completedBreakdown: List<ExerciseBreakdownItem> = emptyList(),
    // Challenges & Music state
    val activeChallenge: ChallengeSnapshot? = null,
    val completedChallengeResult: ChallengeResult? = null,
    val selectedMusicTrack: WorkoutMusicTrack = WorkoutMusicTrack.TECHNO_PULSE,
    val customMusicUri: String? = null,
    val customMusicName: String? = null,
    val isMusicMuted: Boolean = false,
    val musicVolume: Float = 0.72f,
    val errorMessage: String? = null
)

class VisionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREF_STRICTNESS = "detection_strictness"
        private const val PREF_CUSTOM_MUSIC_URI = "custom_music_uri"
        private const val PREF_CUSTOM_MUSIC_NAME = "custom_music_name"
        private const val PREF_MUSIC_TRACK = "preferred_music_track"
    }

    private val app = application as KinetixApp
    private val initialStrictness: DetectionStrictness = runCatching {
        DetectionStrictness.valueOf(app.userDb.getPref(PREF_STRICTNESS, DetectionStrictness.ADAPTIVE.name))
    }.getOrDefault(DetectionStrictness.ADAPTIVE)

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
            frame = engine.emptyFrame(),
            selectedMusicTrack = WorkoutMusicTrack.fromId(
                app.userDb.getPref(PREF_MUSIC_TRACK, WorkoutMusicTrack.TECHNO_PULSE.id)
            ),
            customMusicUri = app.userDb.getPref(PREF_CUSTOM_MUSIC_URI, "").ifBlank { null },
            customMusicName = app.userDb.getPref(PREF_CUSTOM_MUSIC_NAME, "").ifBlank { null }
        )
    )
    val uiState: StateFlow<VisionUiState> = _uiState.asStateFlow()

    private var lastRepSpoken = 0
    private var restTimerJob: Job? = null
    private var challengeTickerJob: Job? = null
    private var musicStartedForChallenge = false
    private var lastActiveChallengeConfig: ChallengeConfig? = null

    // Multi-set & multi-exercise session accumulators for accurate summary averaging
    private var formScoreWeightedSum = 0L
    private var romWeightedSum = 0L
    private var setSamplesWeight = 0L
    private val sessionFaults = linkedSetOf<String>()
    private val exerciseBreakdownMap = linkedMapOf<ExerciseType, ExerciseBreakdownItem>()

    fun setDetectionStrictness(mode: DetectionStrictness) {
        engine.setStrictness(mode)
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
        if (challengeController.isActive) return
        val next = !_uiState.value.isAutoDetectEnabled
        if (next) {
            autoDetector.reset(initialLocked = null)
        }
        _uiState.update {
            it.copy(
                isAutoDetectEnabled = next,
                autoDetectConfidence = if (next) 0f else 1f,
                autoDetectStatusFr = if (next) "Analyse du mouvement…" else "Mode fixe : ${it.selectedExercise.labelFr}"
            )
        }
    }

    /** Manual exercise selection from picker locks into FIXE mode and resets the session cleanly. */
    fun selectExercise(exercise: ExerciseType) {
        cancelRestTimer()
        if (challengeController.isActive) {
            stopChallengeSilently()
        }
        engine.setExercise(exercise)
        autoDetector.reset(initialLocked = exercise)
        lastRepSpoken = 0
        resetMultiSetAccumulators()
        _uiState.update {
            it.copy(
                selectedExercise = exercise,
                isAutoDetectEnabled = false,
                autoDetectConfidence = 1f,
                autoDetectStatusFr = "Mode fixe : ${exercise.labelFr}",
                sessionStartMs = System.currentTimeMillis(),
                isSessionActive = true,
                frame = engine.emptyFrame(),
                currentSetNumber = 1,
                completedSetsCount = 0,
                sessionTotalReps = 0,
                sessionTotalHoldSec = 0,
                currentSetCaloriesKcal = 0.0,
                sessionTotalCaloriesKcal = 0.0,
                multiExerciseBreakdown = emptyList(),
                restRemainingSec = 0,
                isResting = false,
                completedSummary = null,
                completedBreakdown = emptyList(),
                activeChallenge = null,
                completedChallengeResult = null,
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

    // -------------------------------------------------------------------------
    // Offline Music & Custom Local Audio Controls
    // -------------------------------------------------------------------------

    fun selectMusicTrack(track: WorkoutMusicTrack) {
        viewModelScope.launch(Dispatchers.IO) {
            app.userDb.setPref(PREF_MUSIC_TRACK, track.id)
        }
        _uiState.update { it.copy(selectedMusicTrack = track, isMusicMuted = false) }
        audioEngine.setMuted(false)
        if (challengeController.isActive) {
            val active = challengeController.activeConfig()
            if (active != null) {
                lastActiveChallengeConfig = active.copy(musicTrack = track)
            }
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
        if (challengeController.isActive) {
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
        lastActiveChallengeConfig = finalConfig

        engine.setExercise(targetExercise)
        autoDetector.reset(initialLocked = targetExercise)
        lastRepSpoken = 0
        resetMultiSetAccumulators()

        val now = System.currentTimeMillis()
        val startOutput = challengeController.start(finalConfig, now)

        viewModelScope.launch(Dispatchers.IO) {
            app.userDb.setPref(PREF_MUSIC_TRACK, resolvedTrack.id)
        }

        _uiState.update {
            it.copy(
                selectedExercise = targetExercise,
                isAutoDetectEnabled = false,
                autoDetectConfidence = 1f,
                autoDetectStatusFr = "Défi · ${finalConfig.type.shortTitleFr}",
                sessionStartMs = now,
                isSessionActive = true,
                frame = engine.emptyFrame(),
                currentSetNumber = 1,
                completedSetsCount = 0,
                sessionTotalReps = 0,
                sessionTotalHoldSec = 0,
                currentSetCaloriesKcal = 0.0,
                sessionTotalCaloriesKcal = 0.0,
                multiExerciseBreakdown = emptyList(),
                isResting = false,
                restRemainingSec = 0,
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
        val cfg = lastActiveChallengeConfig ?: return
        startChallenge(cfg)
    }

    fun stopActiveChallenge() {
        val now = System.currentTimeMillis()
        val kcal = _uiState.value.sessionTotalCaloriesKcal + _uiState.value.currentSetCaloriesKcal
        val out = challengeController.finishEarly(now, kcal)
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

    private fun stopChallengeSilently() {
        cancelChallengeTicker()
        challengeController.stopAndReset()
        audioEngine.stopMusic()
        musicStartedForChallenge = false
    }

    private fun launchChallengeTicker() {
        cancelChallengeTicker()
        challengeTickerJob = viewModelScope.launch {
            while (challengeController.isActive) {
                delay(150L)
                if (!challengeController.isActive) break
                val state = _uiState.value
                val kcal = state.sessionTotalCaloriesKcal + state.currentSetCaloriesKcal
                val frameArg = if (state.frame.poseDetected) state.frame.copy(repCompletedNow = false) else null
                val out = synchronized(challengeController) {
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
        val frame = state.frame
        bankActiveExerciseWork(state.selectedExercise, frame, state.currentSetCaloriesKcal)

        val setReps = if (state.selectedExercise.isIsometric) 0 else frame.repCount
        val setHold = if (state.selectedExercise.isIsometric) frame.isometricHoldSec.toInt() else 0
        val setKcal = state.currentSetCaloriesKcal

        engine.resetSession()
        lastRepSpoken = 0

        val restSec = state.restDurationSec
        _uiState.update {
            it.copy(
                currentSetNumber = it.currentSetNumber + 1,
                completedSetsCount = it.completedSetsCount + 1,
                sessionTotalReps = it.sessionTotalReps + setReps,
                sessionTotalHoldSec = it.sessionTotalHoldSec + setHold,
                sessionTotalCaloriesKcal = it.sessionTotalCaloriesKcal + setKcal,
                currentSetCaloriesKcal = 0.0,
                multiExerciseBreakdown = exerciseBreakdownMap.values.toList(),
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

    fun onPoseLandmarks(
        screenPoints: List<PosePoint>,
        worldPoints: List<PosePoint>,
        width: Int,
        height: Int,
        timestampMs: Long
    ) {
        val state = _uiState.value
        if (!state.isSessionActive) return

        var activeExercise = state.selectedExercise
        var autoConf = state.autoDetectConfidence
        var autoStatus = state.autoDetectStatusFr
        var bankedRepsDelta = 0
        var bankedHoldDelta = 0
        var bankedKcalDelta = 0.0
        var currentSetKcal = state.currentSetCaloriesKcal

        val inChallenge = challengeController.isActive

        // 1. Real-time automatic exercise detection from 3D movement (paused during rest or active challenge)
        if (!inChallenge && state.isAutoDetectEnabled && !state.isResting && screenPoints.size >= 29) {
            val detect = autoDetector.processFrame(screenPoints, worldPoints, timestampMs)
            autoConf = detect.confidence
            autoStatus = detect.statusFr
            val detected = detect.detectedExercise
            if (detected != null && detected != engine.currentExercise) {
                val prevFrame = state.frame
                val prevEx = engine.currentExercise
                bankActiveExerciseWork(prevEx, prevFrame, currentSetKcal)
                bankedRepsDelta = if (prevEx.isIsometric) 0 else prevFrame.repCount
                bankedHoldDelta = if (prevEx.isIsometric) prevFrame.isometricHoldSec.toInt() else 0
                bankedKcalDelta = currentSetKcal
                currentSetKcal = 0.0

                engine.setExercise(detected)
                activeExercise = detected
                lastRepSpoken = 0
                voiceCoach.speak("Exercice détecté : ${detected.labelFr}", force = false)
            }
        }

        // 2. Run 3D multi-angle biomechanics analysis
        val frame = engine.processFrame(screenPoints, worldPoints, timestampMs)

        // 3. Profile-calibrated real-time calorie expenditure accumulation
        val profile = app.userDb.profile.value
        if (!state.isResting && frame.poseDetected) {
            if (frame.repCompletedNow) {
                currentSetKcal += CalorieEngine.estimateIncrementalRepCalories(
                    exercise = frame.exercise,
                    romPct = frame.completedRepRomPct.coerceAtLeast(35),
                    repDurationSec = frame.completedRepDurationSec.toDouble(),
                    profile = profile
                )
            }
            if (frame.exercise.isIsometric && frame.isometricDeltaSec > 0f) {
                currentSetKcal += CalorieEngine.estimateIsometricCalories(
                    exercise = frame.exercise,
                    holdSec = frame.isometricDeltaSec.toDouble(),
                    profile = profile
                )
            }
        }

        // 4. Advance active camera-verified Challenge OR trigger normal Voice Coach
        if (inChallenge) {
            val totalKcal = state.sessionTotalCaloriesKcal + bankedKcalDelta + currentSetKcal
            val out = synchronized(challengeController) {
                challengeController.onFrame(frame, System.currentTimeMillis(), totalKcal)
            }
            if (out != null) {
                applyChallengeOutputEffects(out)
            }
        } else if (frame.poseDetected && !state.isResting) {
            if (!frame.exercise.isIsometric && frame.repCount > lastRepSpoken) {
                lastRepSpoken = frame.repCount
                voiceCoach.speak(frame.voiceCueFr ?: "${frame.repCount}", isRepCounter = true)
            } else {
                val topFaultCorr = frame.corrections.firstOrNull { it.severity == FormQuality.FAULT }
                val spokenCue = topFaultCorr?.voiceCueFr ?: frame.voiceCueFr
                if (spokenCue != null) {
                    voiceCoach.speak(spokenCue, isRepCounter = false)
                }
            }
        }

        _uiState.update {
            it.copy(
                selectedExercise = activeExercise,
                autoDetectConfidence = autoConf,
                autoDetectStatusFr = autoStatus,
                imageWidth = width.coerceAtLeast(1),
                imageHeight = height.coerceAtLeast(1),
                frame = frame,
                viewpointInfo = frame.viewpointInfo,
                sessionTotalReps = it.sessionTotalReps + bankedRepsDelta,
                sessionTotalHoldSec = it.sessionTotalHoldSec + bankedHoldDelta,
                sessionTotalCaloriesKcal = it.sessionTotalCaloriesKcal + bankedKcalDelta,
                currentSetCaloriesKcal = currentSetKcal,
                multiExerciseBreakdown = if (bankedRepsDelta > 0 || bankedHoldDelta > 0 || bankedKcalDelta > 0.0) {
                    exerciseBreakdownMap.values.toList()
                } else {
                    it.multiExerciseBreakdown
                }
            )
        }
    }

    fun resetSession() {
        cancelRestTimer()
        if (challengeController.isActive) {
            stopChallengeSilently()
        }
        engine.resetSession()
        if (_uiState.value.isAutoDetectEnabled) {
            autoDetector.reset(initialLocked = null)
        }
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
                currentSetCaloriesKcal = 0.0,
                sessionTotalCaloriesKcal = 0.0,
                multiExerciseBreakdown = emptyList(),
                isResting = false,
                restRemainingSec = 0,
                completedSummary = null,
                completedBreakdown = emptyList(),
                activeChallenge = null,
                completedChallengeResult = null
            )
        }
    }

    fun finishSession() {
        if (challengeController.isActive) {
            stopActiveChallenge()
            return
        }
        cancelRestTimer()
        audioEngine.stopMusic()
        val state = _uiState.value
        val frame = state.frame

        val breakdownSnapshot = LinkedHashMap(exerciseBreakdownMap)
        val curReps = if (state.selectedExercise.isIsometric) 0 else frame.repCount
        val curHold = if (state.selectedExercise.isIsometric) frame.isometricHoldSec.toInt() else 0
        if (curReps > 0 || curHold > 0 || state.currentSetCaloriesKcal > 0.0) {
            val existing = breakdownSnapshot[state.selectedExercise]
            breakdownSnapshot[state.selectedExercise] = ExerciseBreakdownItem(
                exercise = state.selectedExercise,
                reps = (existing?.reps ?: 0) + curReps,
                holdSec = (existing?.holdSec ?: 0) + curHold,
                caloriesKcal = (existing?.caloriesKcal ?: 0.0) + state.currentSetCaloriesKcal
            )
        }

        val totalReps = state.sessionTotalReps + frame.repCount
        val totalHoldSec = state.sessionTotalHoldSec + frame.isometricHoldSec.toInt()
        val elapsedSec = if (state.selectedExercise.isIsometric && breakdownSnapshot.size <= 1) {
            totalHoldSec.coerceAtLeast(1)
        } else {
            ((System.currentTimeMillis() - state.sessionStartMs) / 1000L).toInt().coerceAtLeast(1)
        }

        val currentWeight = maxOf(0, if (state.selectedExercise.isIsometric) curHold else curReps).toLong()
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
            ?.exercise ?: state.selectedExercise

        var totalCalories = state.sessionTotalCaloriesKcal + state.currentSetCaloriesKcal
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

        val session = WorkoutSession(
            timestampMs = System.currentTimeMillis(),
            exerciseType = primaryExercise,
            reps = totalReps,
            durationSec = elapsedSec,
            avgFormScore = finalAvgForm,
            avgRomPct = finalAvgRom,
            topFaults = combinedFaults,
            notes = notesText,
            caloriesKcal = totalCalories
        )

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
                completedSummary = session,
                completedBreakdown = breakdownSnapshot.values.toList()
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
        val kcalStr = String.format(Locale.US, "%.1f kcal", session.caloriesKcal)
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

        return "Analyse ma séance Vision KINETIX 3D de ${session.exerciseType.labelFr} : " +
            "$metricStr, dépense estimée $kcalStr (calibrée sur mon profil), " +
            "score postural moyen ${session.avgFormScore}/100, " +
            "amplitude moyenne (ROM) ${session.avgRomPct}%, $angleStr. " +
            "Défauts relevés : $faultsText.$corrSuffix " +
            "Donne-moi un débriefing biomécanique précis en 3 axes : points forts, corrections angulaires concrètes et recommandation pour la prochaine série."
    }

    private fun bankActiveExerciseWork(
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

    private fun resetMultiSetAccumulators() {
        formScoreWeightedSum = 0L
        romWeightedSum = 0L
        setSamplesWeight = 0L
        sessionFaults.clear()
        exerciseBreakdownMap.clear()
    }

    override fun onCleared() {
        super.onCleared()
        cancelRestTimer()
        cancelChallengeTicker()
        audioEngine.release()
        voiceCoach.shutdown()
    }
}
