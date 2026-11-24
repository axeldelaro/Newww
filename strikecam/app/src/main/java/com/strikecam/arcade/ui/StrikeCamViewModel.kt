package com.strikecam.arcade.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.strikecam.arcade.data.ArcadeDatabase
import com.strikecam.arcade.data.ArcadeScoreRecord
import com.strikecam.arcade.data.ArcadeTrack
import com.strikecam.arcade.data.BeatTarget
import com.strikecam.arcade.data.BuiltInTracksCatalog
import com.strikecam.arcade.data.CameraBackdropMode
import com.strikecam.arcade.data.CareerStats
import com.strikecam.arcade.data.Difficulty
import com.strikecam.arcade.data.DuelMatchState
import com.strikecam.arcade.data.DuelPhase
import com.strikecam.arcade.data.GameMode
import com.strikecam.arcade.data.GeneratedBeatmap
import com.strikecam.arcade.data.PlayerRoundSummary
import com.strikecam.arcade.data.StreamingPlatform
import com.strikecam.arcade.data.TrackSource
import com.strikecam.arcade.game.GameFrameSnapshot
import com.strikecam.arcade.game.StrikeGameEngine
import com.strikecam.arcade.music.StrikeAudioEngine
import com.strikecam.arcade.music.TrackDiscoveryRepository
import com.strikecam.arcade.nativeengine.StrikeCamNative
import com.strikecam.arcade.vision.PosePoint
import com.strikecam.arcade.vision.StrikeKinematicsEngine
import com.strikecam.arcade.vision.StrikePoseSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ArcadeScreen {
    LOBBY,
    LOADING,
    COUNTDOWN,
    PLAYING,
    DUEL_HANDOFF,
    RESULTS
}

enum class LobbyTab(val labelFr: String) {
    ARCADE("ARCADE"),
    LOCAL_MP3("MES MP3 & REMIX"),
    STREAMING("STREAMING")
}

data class StrikeCamUiState(
    val screen: ArcadeScreen = ArcadeScreen.LOBBY,
    val selectedTab: LobbyTab = LobbyTab.ARCADE,
    val selectedMode: GameMode = GameMode.SOLO_ARCADE,
    val selectedDifficulty: Difficulty = Difficulty.PRO,
    val backdropMode: CameraBackdropMode = CameraBackdropMode.DIM_50,
    val isFrontCamera: Boolean = true,
    val sfxMuted: Boolean = false,
    val builtInTracks: List<ArcadeTrack> = BuiltInTracksCatalog.tracks,
    val zaagForgeTracks: List<ArcadeTrack> = emptyList(),
    val localMp3Tracks: List<ArcadeTrack> = emptyList(),
    val selectedTrack: ArcadeTrack = BuiltInTracksCatalog.tracks[3], // Zaag Overdrive 200 BPM
    val deezerQuery: String = "",
    val deezerResults: List<ArcadeTrack> = emptyList(),
    val isSearchingDeezer: Boolean = false,
    val streamingLinkInput: String = "",
    val resolvedStreamTrack: ArcadeTrack? = null,
    val isResolvingLink: Boolean = false,
    val loadingMessage: String = "",
    val countdownValue: Int = 3,
    val calibrationProgress: Float = 0f,
    val activeBeatmapBpm: Float = 140f,
    val activeBeatmapTargets: Int = 0,
    val activeBeatmapDrops: Int = 0,
    val isLiveSyncSession: Boolean = false,
    val liveSyncBpm: Float = 140f,
    val livePhaseOffsetMs: Int = 0,
    val liveMicEnabled: Boolean = false,
    val duelState: DuelMatchState = DuelMatchState(),
    val lastSummary: PlayerRoundSummary? = null,
    val careerStats: CareerStats = CareerStats(),
    val recentScores: List<ArcadeScoreRecord> = emptyList(),
    val delegateLabel: String = "GPU",
    val bannerMessage: String? = null
)

class StrikeCamViewModel(application: Application) : AndroidViewModel(application) {

    private val database = ArcadeDatabase(application)
    private val audioEngine = StrikeAudioEngine(application)
    private val kinematicsEngine = StrikeKinematicsEngine()

    private val _uiState = MutableStateFlow(StrikeCamUiState())
    val uiState: StateFlow<StrikeCamUiState> = _uiState.asStateFlow()

    // High-frequency 60 FPS state isolated so lobby & chrome never needlessly recompose
    private val _gameFrame = MutableStateFlow(GameFrameSnapshot())
    val gameFrame: StateFlow<GameFrameSnapshot> = _gameFrame.asStateFlow()

    @Volatile
    private var latestPose: StrikePoseSnapshot = StrikePoseSnapshot()

    @Volatile
    private var isCalibratingNow: Boolean = false

    private var activeEngine: StrikeGameEngine? = null
    private var activeBeatmap: GeneratedBeatmap? = null
    private var activeMusicShorts: ShortArray = ShortArray(0)
    private var survivalLoopIndex: Int = 0
    private var liveTrackerHandle: Long = 0L
    private var liveSessionStartMs: Long = 0L
    private var gameLoopJob: Job? = null

    init {
        viewModelScope.launch {
            database.careerStats.collect { stats ->
                _uiState.update { it.copy(careerStats = stats) }
            }
        }
        viewModelScope.launch {
            database.recentScores.collect { scores ->
                _uiState.update { it.copy(recentScores = scores) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            audioEngine.warmUpSfxBank()
            refreshLocalLibraryInternal()
        }
    }

    fun selectTab(tab: LobbyTab) {
        _uiState.update { it.copy(selectedTab = tab, bannerMessage = null) }
    }

    fun selectMode(mode: GameMode) {
        _uiState.update { it.copy(selectedMode = mode) }
    }

    fun selectDifficulty(difficulty: Difficulty) {
        _uiState.update { it.copy(selectedDifficulty = difficulty) }
    }

    fun selectTrack(track: ArcadeTrack) {
        _uiState.update { it.copy(selectedTrack = track, bannerMessage = null) }
    }

    fun cycleBackdropMode() {
        val modes = CameraBackdropMode.entries
        val next = modes[(_uiState.value.backdropMode.ordinal + 1) % modes.size]
        _uiState.update { it.copy(backdropMode = next) }
    }

    fun toggleCameraFacing() {
        _uiState.update { it.copy(isFrontCamera = !it.isFrontCamera) }
    }

    fun toggleSfxMute() {
        val next = !_uiState.value.sfxMuted
        audioEngine.setSfxMuted(next)
        _uiState.update { it.copy(sfxMuted = next) }
    }

    fun updateDelegateLabel(label: String) {
        _uiState.update { it.copy(delegateLabel = label) }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(bannerMessage = null) }
    }

    fun refreshLocalLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshLocalLibraryInternal()
        }
    }

    private fun refreshLocalLibraryInternal() {
        val (zaag, locals) = TrackDiscoveryRepository.scanLocalTracks(getApplication())
        _uiState.update { state ->
            state.copy(
                zaagForgeTracks = zaag,
                localMp3Tracks = locals
            )
        }
    }

    fun importLocalAudioUri(uri: Uri, displayName: String?) {
        val title = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Piste importée"
        val isZaag = title.contains("ZaagForge", ignoreCase = true) ||
            title.contains("UOK", ignoreCase = true) ||
            title.contains("KRACH", ignoreCase = true) ||
            title.contains("ZAAG", ignoreCase = true)
        val bpm = TrackDiscoveryRepository.extractBpmFromFileName(title) ?: if (isZaag) 200f else 142f
        val imported = ArcadeTrack(
            id = "import_${System.currentTimeMillis()}",
            title = title,
            artist = if (isZaag) "REMIX ZAAGFORGE" else "Fichier local",
            bpm = bpm,
            durationMs = 180_000L,
            source = if (isZaag) TrackSource.LOCAL_ZAAGFORGE else TrackSource.LOCAL_FILE,
            genreTag = if (isZaag) "REMIX ZAAGFORGE" else "MP3 LOCAL",
            contentUri = uri.toString(),
            isZaagForgeRemix = isZaag
        )
        _uiState.update { state ->
            state.copy(
                selectedTrack = imported,
                zaagForgeTracks = if (isZaag) listOf(imported) + state.zaagForgeTracks else state.zaagForgeTracks,
                localMp3Tracks = if (!isZaag) listOf(imported) + state.localMp3Tracks else state.localMp3Tracks,
                bannerMessage = "Piste prête : $title"
            )
        }
        launchSelectedTrack()
    }

    fun updateDeezerQuery(q: String) {
        _uiState.update { it.copy(deezerQuery = q) }
    }

    fun searchDeezer() {
        val q = _uiState.value.deezerQuery.trim()
        if (q.isEmpty()) return
        _uiState.update { it.copy(isSearchingDeezer = true, bannerMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val res = TrackDiscoveryRepository.searchDeezerTracks(q, limit = 15)
            _uiState.update {
                it.copy(
                    isSearchingDeezer = false,
                    deezerResults = res,
                    bannerMessage = if (res.isEmpty()) "Aucun aperçu Deezer trouvé pour « $q »." else null
                )
            }
        }
    }

    fun updateStreamingLinkInput(text: String) {
        _uiState.update { it.copy(streamingLinkInput = text) }
    }

    fun handleIncomingShareText(sharedText: String) {
        if (sharedText.isBlank()) return
        _uiState.update {
            it.copy(
                screen = ArcadeScreen.LOBBY,
                selectedTab = LobbyTab.STREAMING,
                streamingLinkInput = sharedText.trim()
            )
        }
        resolveStreamingLink()
    }

    fun resolveStreamingLink() {
        val raw = _uiState.value.streamingLinkInput.trim()
        if (raw.isBlank()) return
        _uiState.update { it.copy(isResolvingLink = true, bannerMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val resolved = TrackDiscoveryRepository.resolveStreamingLiveTrack(raw)
            _uiState.update {
                if (resolved != null) {
                    it.copy(
                        isResolvingLink = false,
                        resolvedStreamTrack = resolved,
                        selectedTrack = resolved,
                        bannerMessage = "Lien ${resolved.streamingPlatform.label} détecté : ${resolved.title} (${resolved.bpm.toInt()} BPM)"
                    )
                } else {
                    it.copy(
                        isResolvingLink = false,
                        bannerMessage = "Collez un lien valide Spotify, Deezer ou SoundCloud."
                    )
                }
            }
        }
    }

    fun openExternalStreamingApp(platform: StreamingPlatform, url: String? = null) {
        TrackDiscoveryRepository.launchExternalStreamingApp(getApplication(), platform, url)
    }

    /** Starts a track directly using its 30s Deezer FFT preview if available. */
    fun launchPreviewFromResolved(track: ArcadeTrack) {
        if (track.previewUrl.isNullOrBlank()) return
        val previewTrack = track.copy(
            source = TrackSource.DEEZER_PREVIEW,
            durationMs = 30_000L,
            genreTag = "APERÇU 30S FFT"
        )
        _uiState.update { it.copy(selectedTrack = previewTrack) }
        launchSelectedTrack()
    }

    fun launchTrack(track: ArcadeTrack) {
        _uiState.update { it.copy(selectedTrack = track) }
        launchSelectedTrack()
    }

    fun launchSelectedTrack() {
        val state = _uiState.value
        val track = state.selectedTrack
        val difficulty = state.selectedDifficulty
        val mode = state.selectedMode

        stopActiveSession()

        val initialDuel = if (mode == GameMode.DUEL_2P) {
            DuelMatchState(phase = DuelPhase.PLAYER_1_PLAYING)
        } else {
            DuelMatchState(phase = DuelPhase.NONE)
        }

        _uiState.update {
            it.copy(
                screen = ArcadeScreen.LOADING,
                loadingMessage = when (track.source) {
                    TrackSource.BUILTIN_SYNTH -> "Synthèse C++20 & Beatmap FFT…"
                    TrackSource.LOCAL_ZAAGFORGE -> "Analyse FFT du remix ZAAGFORGE…"
                    TrackSource.LOCAL_FILE -> "Décodage MP3 & Beatmap FFT…"
                    TrackSource.DEEZER_PREVIEW -> "Téléchargement aperçu 30s & Analyse FFT…"
                    TrackSource.STREAMING_LIVE_SYNC -> "Initialisation Live Beat Tracker…"
                },
                duelState = initialDuel,
                lastSummary = null,
                bannerMessage = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (track.source) {
                    TrackSource.BUILTIN_SYNTH -> {
                        val pcm = StrikeCamNative.synthesizeTrackPcm16(track.builtInTrackId)
                        val beatmap = StrikeCamNative.generateBuiltInBeatmap(track.builtInTrackId, difficulty)
                        activeMusicShorts = pcm
                        activeBeatmap = beatmap
                    }
                    TrackSource.LOCAL_ZAAGFORGE, TrackSource.LOCAL_FILE -> {
                        val uri = Uri.parse(track.contentUri ?: error("URI audio manquant"))
                        val decoded = audioEngine.decodeAudioUri(uri)
                        val beatmap = StrikeCamNative.generateBeatmap(
                            decoded.stereoFloats,
                            decoded.sampleRate,
                            2,
                            difficulty
                        )
                        activeMusicShorts = decoded.stereoShorts
                        activeBeatmap = beatmap
                    }
                    TrackSource.DEEZER_PREVIEW -> {
                        val cachedFile = TrackDiscoveryRepository.downloadPreviewFile(getApplication(), track)
                        val decoded = audioEngine.decodeAudioFile(cachedFile)
                        val beatmap = StrikeCamNative.generateBeatmap(
                            decoded.stereoFloats,
                            decoded.sampleRate,
                            2,
                            difficulty
                        )
                        activeMusicShorts = decoded.stereoShorts
                        activeBeatmap = beatmap
                    }
                    TrackSource.STREAMING_LIVE_SYNC -> {
                        activeMusicShorts = ShortArray(0)
                        activeBeatmap = GeneratedBeatmap(
                            bpm = track.bpm.coerceIn(80f, 230f),
                            firstBeatMs = 0f,
                            durationMs = 600_000f,
                            dropCount = 0,
                            targets = emptyList()
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    beginCountdownAndPlay()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            screen = ArcadeScreen.LOBBY,
                            bannerMessage = "Erreur chargement audio : ${t.message ?: "format non supporté"}"
                        )
                    }
                }
            }
        }
    }

    /** Launches Player 2 on the exact same pre-generated beatmap & audio in Duel 2P mode. */
    fun startDuelPlayer2() {
        if (activeBeatmap == null) {
            returnToLobby()
            return
        }
        _uiState.update {
            it.copy(
                duelState = it.duelState.copy(phase = DuelPhase.PLAYER_2_PLAYING)
            )
        }
        beginCountdownAndPlay()
    }

    private fun beginCountdownAndPlay() {
        val state = _uiState.value
        val beatmap = activeBeatmap ?: return
        val isLive = state.selectedTrack.source == TrackSource.STREAMING_LIVE_SYNC

        kinematicsEngine.reset(resetCalibration = true)
        isCalibratingNow = true
        survivalLoopIndex = 0

        val engine = StrikeGameEngine(
            difficulty = state.selectedDifficulty,
            mode = state.selectedMode,
            liveSyncMode = isLive,
            kinematicsHelper = kinematicsEngine
        )
        engine.loadBeatmap(beatmap.targets, beatmap.durationMs)
        activeEngine = engine

        if (!isLive && activeMusicShorts.isNotEmpty()) {
            audioEngine.loadMusicShortPcm(
                pcm16Stereo = activeMusicShorts,
                loop = state.selectedMode == GameMode.SURVIVAL
            )
        }

        if (isLive) {
            destroyLiveTracker()
            val bpm = state.selectedTrack.bpm.coerceIn(80f, 230f)
            liveTrackerHandle = StrikeCamNative.createLiveTracker(StrikeCamNative.SAMPLE_RATE, bpm)
        }

        _uiState.update {
            it.copy(
                screen = ArcadeScreen.COUNTDOWN,
                countdownValue = 3,
                calibrationProgress = 0f,
                activeBeatmapBpm = beatmap.bpm,
                activeBeatmapTargets = beatmap.targets.size,
                activeBeatmapDrops = beatmap.dropCount,
                isLiveSyncSession = isLive,
                liveSyncBpm = beatmap.bpm,
                livePhaseOffsetMs = 0,
                liveMicEnabled = false
            )
        }

        gameLoopJob?.cancel()
        gameLoopJob = viewModelScope.launch(Dispatchers.Default) {
            // 3-2-1 Auto-Calibration Countdown (samples athlete standing head/shoulders height)
            for (step in 3 downTo 1) {
                _uiState.update { it.copy(countdownValue = step) }
                audioEngine.playSfx(StrikeCamNative.SFX_COUNTDOWN_TICK, 0.85f)
                val endMs = SystemClock.elapsedRealtime() + 850L
                while (isActive && SystemClock.elapsedRealtime() < endMs) {
                    val pose = latestPose
                    val prog = (pose.baseline.sampleCount / 12f).coerceIn(0f, 1f)
                    _uiState.update { it.copy(calibrationProgress = prog) }
                    _gameFrame.value = GameFrameSnapshot(pose = pose)
                    delay(16L)
                }
            }

            isCalibratingNow = false
            audioEngine.playSfx(StrikeCamNative.SFX_COUNTDOWN_GO, 0.95f)
            _uiState.update { it.copy(screen = ArcadeScreen.PLAYING, countdownValue = 0) }

            if (isLive) {
                liveSessionStartMs = SystemClock.elapsedRealtime()
                StrikeCamNative.resetLiveTracker(liveTrackerHandle, 0.0)
                // Launch external streaming app gently if user tapped Play? No — user controls play/pause right inside Live Sync bar so camera stays visible!
            } else {
                audioEngine.startPlayback(resetCursor = true)
            }

            // High-precision 60 FPS rhythm-boxing game loop
            var prevSurvivalTimeMs = 0f
            while (isActive) {
                val nowPosMs = if (isLive) {
                    (SystemClock.elapsedRealtime() - liveSessionStartMs).toFloat()
                } else {
                    val rawMs = audioEngine.currentMusicTimeMs()
                    if (state.selectedMode == GameMode.SURVIVAL && beatmap.durationMs > 5000f) {
                        if (rawMs + 2500f < prevSurvivalTimeMs) {
                            survivalLoopIndex++
                            enqueueSurvivalLoopTargets(engine, beatmap, survivalLoopIndex)
                        }
                        prevSurvivalTimeMs = rawMs
                        rawMs + survivalLoopIndex * beatmap.durationMs
                    } else {
                        rawMs
                    }
                }

                // In Live Sync Mode, poll native beat grid ahead by approachTimeMs so orbs fly cleanly onto every beat!
                if (isLive && liveTrackerHandle != 0L) {
                    val leadMs = state.selectedDifficulty.approachTimeMs.toDouble()
                    val sig = StrikeCamNative.pollTrackerGrid(liveTrackerHandle, nowPosMs.toDouble(), leadMs)
                    if (sig.triggered) {
                        engine.enqueueLiveSyncBeat(sig.beatTimeMs, sig.isDrop)
                    }
                }

                val snap = engine.step(nowPosMs, latestPose)
                _gameFrame.value = snap

                if (state.selectedMode == GameMode.SURVIVAL && !isLive) {
                    audioEngine.setPlaybackSpeed(snap.speedMultiplier)
                }

                for (sfxId in snap.sfxEvents) {
                    audioEngine.playSfx(sfxId, if (sfxId == StrikeCamNative.SFX_MISS) 0.72f else 0.90f)
                }

                if (snap.isRoundFinished || (!isLive && state.selectedMode != GameMode.SURVIVAL && audioEngine.isTrackFinished() && nowPosMs > beatmap.durationMs + 400f)) {
                    withContext(Dispatchers.Main) {
                        finishCurrentRound(engine)
                    }
                    break
                }

                delay(16L)
            }
        }
    }

    private fun enqueueSurvivalLoopTargets(engine: StrikeGameEngine, beatmap: GeneratedBeatmap, loopIndex: Int) {
        val offset = loopIndex * beatmap.durationMs
        // Re-enqueue beatmap targets shifted forward in time for endless Survival ramp
        beatmap.targets.forEach { t ->
            engine.enqueueLiveSyncBeat(t.timeMs + offset, t.intensity >= 0.85f)
        }
    }

    private fun finishCurrentRound(engine: StrikeGameEngine) {
        audioEngine.stopPlayback()
        audioEngine.stopLiveMicBeatCapture()
        destroyLiveTracker()

        val state = _uiState.value
        val duel = state.duelState

        if (state.selectedMode == GameMode.DUEL_2P && duel.phase == DuelPhase.PLAYER_1_PLAYING) {
            val p1Summary = engine.buildSummary(duel.player1Name)
            saveScoreRecord(state.selectedTrack, state.selectedMode, state.selectedDifficulty, p1Summary)
            _uiState.update {
                it.copy(
                    screen = ArcadeScreen.DUEL_HANDOFF,
                    lastSummary = p1Summary,
                    duelState = duel.copy(
                        phase = DuelPhase.HANDOFF_TO_PLAYER_2,
                        player1Summary = p1Summary
                    )
                )
            }
            return
        }

        val playerName = if (state.selectedMode == GameMode.DUEL_2P) duel.player2Name else "JOUEUR 1"
        val summary = engine.buildSummary(playerName)
        saveScoreRecord(state.selectedTrack, state.selectedMode, state.selectedDifficulty, summary)

        val finalDuel = if (state.selectedMode == GameMode.DUEL_2P) {
            duel.copy(phase = DuelPhase.DUEL_COMPLETE, player2Summary = summary)
        } else {
            duel
        }

        _uiState.update {
            it.copy(
                screen = ArcadeScreen.RESULTS,
                lastSummary = summary,
                duelState = finalDuel,
                liveMicEnabled = false
            )
        }
    }

    fun finishLiveSyncSession() {
        val engine = activeEngine ?: run {
            returnToLobby()
            return
        }
        gameLoopJob?.cancel()
        finishCurrentRound(engine)
    }

    private fun saveScoreRecord(
        track: ArcadeTrack,
        mode: GameMode,
        difficulty: Difficulty,
        summary: PlayerRoundSummary
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            database.insertScore(
                ArcadeScoreRecord(
                    trackId = track.id,
                    trackTitle = track.title,
                    sourceType = track.source.name,
                    mode = mode,
                    difficulty = difficulty,
                    score = summary.score,
                    rank = summary.rank,
                    maxCombo = summary.maxCombo,
                    accuracyPct = summary.accuracyPct,
                    perfectCount = summary.perfectCount,
                    greatCount = summary.greatCount,
                    goodCount = summary.goodCount,
                    dodgeCount = summary.dodgeCount,
                    missCount = summary.missCount,
                    avgReactionMs = summary.avgReactionMs,
                    totalPunches = summary.totalPunches,
                    caloriesKcal = summary.caloriesKcal,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
    }

    /** Called on MediaPipe background callback thread for every live camera pose frame. */
    fun onPoseLandmarks(
        screenPoints: List<PosePoint>,
        worldPoints: List<PosePoint>,
        imageWidth: Int,
        imageHeight: Int,
        timestampMs: Long
    ) {
        latestPose = kinematicsEngine.processFrame(
            rawScreen = screenPoints,
            rawWorld = worldPoints,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            timestampMs = timestampMs,
            calibrating = isCalibratingNow
        )
    }

    // --- Live Streaming Sync Controls (Spotify / Deezer / SoundCloud) ---

    fun registerLiveTapSync() {
        if (liveTrackerHandle == 0L) return
        val nowMs = (SystemClock.elapsedRealtime() - liveSessionStartMs).toDouble()
        val estBpm = StrikeCamNative.registerTrackerTap(liveTrackerHandle, nowMs)
        _uiState.update { it.copy(liveSyncBpm = estBpm) }
        audioEngine.playSfx(StrikeCamNative.SFX_COUNTDOWN_TICK, 0.5f)
    }

    fun adjustLiveBpm(deltaBpm: Float) {
        val next = (_uiState.value.liveSyncBpm + deltaBpm).coerceIn(75f, 235f)
        if (liveTrackerHandle != 0L) {
            StrikeCamNative.setTrackerBpm(liveTrackerHandle, next)
        }
        _uiState.update { it.copy(liveSyncBpm = next) }
    }

    fun nudgeLivePhase(deltaMs: Int) {
        if (liveTrackerHandle != 0L) {
            StrikeCamNative.nudgeTrackerPhase(liveTrackerHandle, deltaMs.toDouble())
        }
        _uiState.update { it.copy(livePhaseOffsetMs = it.livePhaseOffsetMs + deltaMs) }
    }

    fun toggleLiveMicCapture(hasAudioPermission: Boolean) {
        val enabling = !_uiState.value.liveMicEnabled
        if (!enabling) {
            audioEngine.stopLiveMicBeatCapture()
            _uiState.update { it.copy(liveMicEnabled = false) }
            return
        }
        if (!hasAudioPermission || liveTrackerHandle == 0L) return
        _uiState.update { it.copy(liveMicEnabled = true) }
        audioEngine.startLiveMicBeatCapture { monoChunk, _ ->
            val handle = liveTrackerHandle
            if (handle != 0L) {
                val nowRelMs = (SystemClock.elapsedRealtime() - liveSessionStartMs).toDouble()
                val sig = StrikeCamNative.processTrackerAudio(handle, monoChunk, nowRelMs)
                if (sig.triggered && sig.estimatedBpm in 75f..235f) {
                    _uiState.update { it.copy(liveSyncBpm = sig.estimatedBpm) }
                }
            }
        }
    }

    fun sendMediaPlayPause() = audioEngine.dispatchExternalMediaControl(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun sendMediaNext() = audioEngine.dispatchExternalMediaControl(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun sendMediaPrev() = audioEngine.dispatchExternalMediaControl(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    fun returnToLobby() {
        stopActiveSession()
        _uiState.update {
            it.copy(
                screen = ArcadeScreen.LOBBY,
                liveMicEnabled = false,
                duelState = DuelMatchState()
            )
        }
    }

    fun pauseOnLifecycleStop() {
        if (_uiState.value.screen == ArcadeScreen.PLAYING || _uiState.value.screen == ArcadeScreen.COUNTDOWN) {
            returnToLobby()
        }
    }

    private fun stopActiveSession() {
        gameLoopJob?.cancel()
        gameLoopJob = null
        isCalibratingNow = false
        audioEngine.stopPlayback()
        audioEngine.stopLiveMicBeatCapture()
        destroyLiveTracker()
    }

    private fun destroyLiveTracker() {
        if (liveTrackerHandle != 0L) {
            StrikeCamNative.destroyLiveTracker(liveTrackerHandle)
            liveTrackerHandle = 0L
        }
    }

    override fun onCleared() {
        stopActiveSession()
        audioEngine.release()
        database.close()
        super.onCleared()
    }
}
