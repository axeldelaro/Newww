package com.zaagforge.studio.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaagforge.studio.audio.AudioDecoder
import com.zaagforge.studio.audio.AudioExporter
import com.zaagforge.studio.audio.RemixPlayer
import com.zaagforge.studio.dsp.NativeDspBridge
import com.zaagforge.studio.model.AudioTrackItem
import com.zaagforge.studio.model.DeezerSearchTrack
import com.zaagforge.studio.model.ExportFormat
import com.zaagforge.studio.model.ExportedRemix
import com.zaagforge.studio.model.KickType
import com.zaagforge.studio.model.MeterReading
import com.zaagforge.studio.model.RemixParams
import com.zaagforge.studio.model.RemixPreset
import com.zaagforge.studio.model.RemixRenderResult
import com.zaagforge.studio.model.ResolvedStreamingTrack
import com.zaagforge.studio.model.SectionMarker
import com.zaagforge.studio.model.StudioPresets
import com.zaagforge.studio.model.TrackAnalysis
import com.zaagforge.studio.model.TrackSourceType
import com.zaagforge.studio.music.LibraryAndStreamingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

enum class StudioTab(val label: String) {
    STUDIO("STUDIO"),
    LIBRARY("BIBLIOTHÈQUE"),
    EXPORTS("EXPORTS")
}

data class ZaagForgeUiState(
    val activeTab: StudioTab = StudioTab.STUDIO,
    val loadedTrack: AudioTrackItem? = null,
    val analysis: TrackAnalysis? = null,
    val selectedPreset: RemixPreset = StudioPresets.all.first(),
    val params: RemixParams = StudioPresets.all.first().params,
    val remixResult: RemixRenderResult? = null,
    val waveformPeaks: FloatArray = FloatArray(0),
    val sectionMarkers: List<SectionMarker> = emptyList(),
    val hasPendingChanges: Boolean = false,
    val isBusy: Boolean = false,
    val busyMessage: String = "",
    val statusBanner: String? = null,
    val errorBanner: String? = null,
    val auditioningKick: KickType? = null,
    val showCutControls: Boolean = true,
    val showMixControls: Boolean = false,
    val showProTweaks: Boolean = false,
    // Library & Streaming
    val localTracks: List<AudioTrackItem> = emptyList(),
    val deezerQuery: String = "",
    val deezerResults: List<DeezerSearchTrack> = emptyList(),
    val isSearchingDeezer: Boolean = false,
    val resolvedLink: ResolvedStreamingTrack? = null,
    // Exports
    val exportedRemixes: List<ExportedRemix> = emptyList(),
    val lastExportNotice: String? = null
)

class ZaagForgeViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val player = RemixPlayer()

    val isPlaying: StateFlow<Boolean> = player.isPlaying
    val playbackProgress: StateFlow<Float> = player.progress
    val isRemixActive: StateFlow<Boolean> = player.isRemixActive
    val meterReading: StateFlow<MeterReading> = player.meter

    private var hasOriginalLoaded: Boolean = false
    private var currentRemixFrames: Int = 0

    private val _uiState = MutableStateFlow(ZaagForgeUiState())
    val uiState: StateFlow<ZaagForgeUiState> = _uiState.asStateFlow()

    init {
        refreshExports()
        refreshLocalLibrary()
        loadTrack(LibraryAndStreamingRepository.builtInDemos.first(), autoRemixImmediately = true)
    }

    fun selectTab(tab: StudioTab) {
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == StudioTab.EXPORTS) refreshExports()
    }

    fun refreshLocalLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val locals = LibraryAndStreamingRepository.scanLocalAudioTracks(appContext)
            _uiState.update { it.copy(localTracks = locals) }
        }
    }

    fun refreshExports() {
        viewModelScope.launch(Dispatchers.IO) {
            val exports = AudioExporter.listExportedRemixes(appContext)
            _uiState.update { it.copy(exportedRemixes = exports) }
        }
    }

    fun clearBanners() {
        _uiState.update { it.copy(errorBanner = null, statusBanner = null, lastExportNotice = null) }
    }

    fun dismissResolvedLink() {
        _uiState.update { it.copy(resolvedLink = null) }
    }

    fun toggleCutControls() {
        _uiState.update { it.copy(showCutControls = !it.showCutControls) }
    }

    fun toggleMixControls() {
        _uiState.update { it.copy(showMixControls = !it.showMixControls) }
    }

    fun toggleProTweaks() {
        _uiState.update { it.copy(showProTweaks = !it.showProTweaks) }
    }

    fun selectPreset(preset: RemixPreset, renderNow: Boolean = false) {
        _uiState.update { state ->
            // Preserve user's track-specific drop/grid timing when switching presets!
            state.copy(
                selectedPreset = preset,
                params = state.params.withPresetStyle(preset.params),
                errorBanner = null,
                hasPendingChanges = true
            )
        }
        if (renderNow && hasOriginalLoaded && _uiState.value.analysis != null) {
            renderCurrentRemix()
        }
    }

    fun updateParams(transform: (RemixParams) -> RemixParams) {
        val next = transform(_uiState.value.params).sanitized()
        _uiState.update { state ->
            state.copy(params = next, hasPendingChanges = true)
        }
        refreshLiveSectionPreview(next)
    }

    /** Instant 1-tap: places DROP 1 exactly on the nearest musical bar at current playhead! */
    fun placeDrop1AtPlayhead() {
        val frac = player.progress.value.coerceIn(0.04f, 0.88f)
        updateParams {
            it.copy(customDrop1Frac = frac, drop1BarNudge = 0)
        }
        val dur = _uiState.value.analysis?.durationSec ?: 0f
        _uiState.update {
            it.copy(statusBanner = "DROP 1 calé sur ${formatTimeSec(frac * dur)} · clique AUTO REMIX C++ pour appliquer")
        }
    }

    /** Instant 1-tap: places DROP 2 exactly on the nearest musical bar at current playhead! */
    fun placeDrop2AtPlayhead() {
        val frac = player.progress.value.coerceIn(0.12f, 0.95f)
        updateParams {
            it.copy(customDrop2Frac = frac, drop2BarNudge = 0)
        }
        val dur = _uiState.value.analysis?.durationSec ?: 0f
        _uiState.update {
            it.copy(statusBanner = "DROP 2 calé sur ${formatTimeSec(frac * dur)} · clique AUTO REMIX C++ pour appliquer")
        }
    }

    /** Resets all custom cut/drop/grid overrides back to automatic chorus detection. */
    fun resetTimingToAuto() {
        updateParams {
            it.copy(
                sourceBpmOverride = 0f,
                gridPhaseNudgeMs = 0f,
                gridBeatShift = 0,
                drop1BarNudge = 0,
                drop2BarNudge = 0,
                customDrop1Frac = -1f,
                customDrop2Frac = -1f
            )
        }
        _uiState.update { it.copy(statusBanner = "Calage réinitialisé sur la détection auto du refrain.") }
    }

    /** Half-time / Double-time BPM correction in 1 tap when a track has ambiguous pulse. */
    fun scaleDetectedSourceBpm(factor: Float) {
        val baseBpm = _uiState.value.params.sourceBpmOverride.takeIf { it > 55f }
            ?: _uiState.value.analysis?.bpm ?: 128f
        val nextBpm = (baseBpm * factor).coerceIn(60f, 240f)
        updateParams { it.copy(sourceBpmOverride = nextBpm) }
        _uiState.update {
            it.copy(statusBanner = "BPM source corrigé à ${nextBpm.roundToInt()} BPM · clique AUTO REMIX C++")
        }
    }

    private fun refreshLiveSectionPreview(params: RemixParams) {
        if (!hasOriginalLoaded) return
        viewModelScope.launch(Dispatchers.Default) {
            val previewSecs = runCatching { NativeDspBridge.previewSections(params) }.getOrNull()
            if (!previewSecs.isNullOrEmpty()) {
                _uiState.update { it.copy(sectionMarkers = previewSecs) }
            }
        }
    }

    fun importSafAudio(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            var title = "Piste importée"
            runCatching {
                appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0)
                        if (!name.isNullOrBlank()) title = name.substringBeforeLast('.')
                    }
                }
            }
            val item = AudioTrackItem(
                id = "saf_${System.currentTimeMillis()}",
                title = title,
                artist = "Import MP3 / WAV / FLAC",
                durationMs = 0L,
                sourceType = TrackSourceType.SAF_IMPORT,
                uriString = uri.toString()
            )
            loadTrack(item, autoRemixImmediately = true)
        }
    }

    fun loadTrack(item: AudioTrackItem, autoRemixImmediately: Boolean = true) {
        viewModelScope.launch {
            player.pause()
            _uiState.update {
                it.copy(
                    activeTab = StudioTab.STUDIO,
                    isBusy = true,
                    busyMessage = "Décodage streaming C++ & détection refrain / BPM…",
                    errorBanner = null,
                    statusBanner = null
                )
            }
            try {
                // Streams directly into C++ native heap (ZERO 77 MB Java FloatArray allocation!)
                val analysis = withContext(Dispatchers.IO) {
                    when (item.sourceType) {
                        TrackSourceType.BUILTIN_DEMO ->
                            NativeDspBridge.loadDemoAndAnalyze(item.demoStyleIndex.coerceAtLeast(0), 30f)
                        TrackSourceType.DEEZER_PREVIEW -> {
                            val filePath = item.localFilePath
                                ?: throw IllegalStateException("Chemin aperçu Deezer manquant.")
                            AudioDecoder.decodeFileToNativeSession(File(filePath))
                        }
                        TrackSourceType.LOCAL_MEDIASTORE, TrackSourceType.SAF_IMPORT -> {
                            val uriStr = item.uriString
                                ?: throw IllegalStateException("URI audio manquant.")
                            AudioDecoder.decodeUriToNativeSession(appContext, Uri.parse(uriStr))
                        }
                    }
                }

                hasOriginalLoaded = analysis.totalFrames > 0
                currentRemixFrames = 0
                player.setSessionFrameCounts(originalFrames = analysis.totalFrames, remixFrames = 0)

                // Reset track-specific manual drop/BPM overrides cleanly when loading a new song
                val cleanParams = _uiState.value.params.copy(
                    sourceBpmOverride = 0f,
                    gridPhaseNudgeMs = 0f,
                    gridBeatShift = 0,
                    drop1BarNudge = 0,
                    drop2BarNudge = 0,
                    customDrop1Frac = -1f,
                    customDrop2Frac = -1f
                )

                _uiState.update {
                    it.copy(
                        loadedTrack = item.copy(durationMs = (analysis.durationSec * 1000f).toLong()),
                        analysis = analysis,
                        params = cleanParams,
                        remixResult = null,
                        waveformPeaks = analysis.waveformPeaks,
                        sectionMarkers = analysis.sections,
                        hasPendingChanges = false,
                        isBusy = false,
                        busyMessage = "",
                        statusBanner = "Analysé : ${analysis.bpm.roundToInt()} BPM · Clé ${analysis.keyName} · Refrain 1 @ ${formatTimeSec(analysis.chorus1Sec)} (Mes. ${analysis.chorus1Bar})"
                    )
                }

                if (autoRemixImmediately) {
                    renderCurrentRemix()
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        errorBanner = "Erreur chargement audio : ${t.message ?: "format non supporté"}"
                    )
                }
            }
        }
    }

    fun renderCurrentRemix() {
        val analysis = _uiState.value.analysis ?: return
        if (!hasOriginalLoaded) return
        val params = _uiState.value.params
        val presetName = _uiState.value.selectedPreset.name

        viewModelScope.launch {
            player.pause()
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = "Mixage C++20 ($presetName · ${params.targetBpm.roundToInt()} BPM · ${params.masterMode.label})…",
                    errorBanner = null,
                    statusBanner = null
                )
            }
            try {
                // Zero-OOM native render: PCM stays in C++ native heap, Kotlin receives only ~2 KB metadata!
                val rendered = withContext(Dispatchers.Default) {
                    NativeDspBridge.renderSessionRemix(params, analysis)
                }

                currentRemixFrames = rendered.totalFrames
                player.setRemixFrameCount(rendered.totalFrames, autoSwitchToRemix = true)

                _uiState.update {
                    it.copy(
                        remixResult = rendered,
                        waveformPeaks = rendered.waveformPeaks,
                        sectionMarkers = rendered.sections,
                        hasPendingChanges = false,
                        isBusy = false,
                        busyMessage = "",
                        statusBanner = "REMIX PRÊT · ${rendered.effectiveTargetBpm.roundToInt()} BPM · Clé ${rendered.keyName} · RMS ${String.format(Locale.US, "%.1f", rendered.rmsDb)} dB · ${params.masterMode.label}"
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        errorBanner = "Erreur rendu C++ : ${t.message}"
                    )
                }
            }
        }
    }

    fun auditionKick(kick: KickType) {
        val analysis = _uiState.value.analysis
        val params = _uiState.value.params
        val rootHz = analysis?.rootHz ?: 48.99f
        _uiState.update { it.copy(auditioningKick = kick) }
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val loop = NativeDspBridge.synthesizeKickPreview(
                    kickType = kick,
                    rootHz = rootHz,
                    bpm = params.targetBpm,
                    drive = params.drive,
                    aggression = params.zaagAggression,
                    bassSaturation = params.bassSaturation
                )
                player.playAuditionLoop(loop)
            }
        }
    }

    fun togglePlayPause() = player.togglePlayPause()

    fun toggleAbSource() {
        player.toggleAbSource()
        val playingRemixNow = player.isRemixActive.value
        val state = _uiState.value
        val peaks = if (playingRemixNow) {
            state.remixResult?.waveformPeaks ?: state.analysis?.waveformPeaks ?: FloatArray(0)
        } else {
            state.analysis?.waveformPeaks ?: FloatArray(0)
        }
        _uiState.update { it.copy(waveformPeaks = peaks) }
    }

    fun seekToFraction(frac: Float) = player.seekToFraction(frac)

    fun jumpToSection(marker: SectionMarker) {
        player.seekToFraction(marker.startFrac)
        if (!player.isPlaying.value) {
            player.play()
        }
    }

    fun exportCurrentRemix(format: ExportFormat) {
        val track = _uiState.value.loadedTrack ?: return
        val remix = _uiState.value.remixResult
        if (remix == null || currentRemixFrames <= 0) {
            _uiState.update { it.copy(errorBanner = "Lance d'abord AUTO REMIX avant d'exporter.") }
            return
        }
        val preset = _uiState.value.selectedPreset
        val bpmInt = remix.effectiveTargetBpm.roundToInt()
        val baseName = "${track.title}_${preset.shortCode}_${bpmInt}BPM"

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = "Export streaming ${format.badge} vers Music/ZaagForge/…",
                    errorBanner = null
                )
            }
            try {
                val exported = withContext(Dispatchers.IO) {
                    AudioExporter.exportSessionRemix(
                        context = appContext,
                        totalRemixFrames = currentRemixFrames,
                        sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                        baseName = baseName,
                        format = format
                    )
                }
                refreshExports()
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        lastExportNotice = "Exporté : ${exported.displayName} (Music/ZaagForge/ · prêt dans StrikeCam !)"
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        errorBanner = "Échec export : ${t.message}"
                    )
                }
            }
        }
    }

    fun playExportedFile(exported: ExportedRemix) {
        val item = AudioTrackItem(
            id = "exp_${exported.id}",
            title = exported.displayName.substringBeforeLast('.'),
            artist = "Export ZAAGFORGE",
            durationMs = 0L,
            sourceType = TrackSourceType.LOCAL_MEDIASTORE,
            uriString = exported.uriString
        )
        loadTrack(item, autoRemixImmediately = false)
    }

    fun onDeezerQueryChange(q: String) {
        _uiState.update { it.copy(deezerQuery = q) }
    }

    fun searchDeezerNow() {
        val q = _uiState.value.deezerQuery.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSearchingDeezer = true, errorBanner = null) }
            val results = withContext(Dispatchers.IO) {
                LibraryAndStreamingRepository.searchDeezer(q, limit = 15)
            }
            _uiState.update {
                it.copy(
                    isSearchingDeezer = false,
                    deezerResults = results,
                    errorBanner = if (results.isEmpty()) "Aucun aperçu Deezer trouvé pour « $q »." else null
                )
            }
        }
    }

    fun loadDeezerPreviewTrack(track: DeezerSearchTrack) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activeTab = StudioTab.STUDIO,
                    isBusy = true,
                    busyMessage = "Téléchargement aperçu 30 s Deezer (${track.title})…",
                    errorBanner = null,
                    resolvedLink = null
                )
            }
            try {
                val cachedFile = withContext(Dispatchers.IO) {
                    LibraryAndStreamingRepository.downloadPreviewToCache(appContext, track.previewUrl, track.id)
                }
                val item = AudioTrackItem(
                    id = "dz_${track.id}",
                    title = "${track.title} (Aperçu 30s)",
                    artist = track.artist,
                    durationMs = 30_000L,
                    sourceType = TrackSourceType.DEEZER_PREVIEW,
                    localFilePath = cachedFile.absolutePath,
                    previewUrl = track.previewUrl,
                    coverUrl = track.coverUrl
                )
                loadTrack(item, autoRemixImmediately = true)
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        errorBanner = "Impossible de récupérer l'aperçu Deezer : ${t.message}"
                    )
                }
            }
        }
    }

    fun handleIncomingShareLink(rawText: String) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = "Résolution du lien Spotify / SoundCloud / Deezer…",
                    errorBanner = null
                )
            }
            val locals = _uiState.value.localTracks.ifEmpty {
                withContext(Dispatchers.IO) { LibraryAndStreamingRepository.scanLocalAudioTracks(appContext) }
            }
            val resolved = withContext(Dispatchers.IO) {
                LibraryAndStreamingRepository.resolveStreamingLink(rawText, locals)
            }
            if (resolved == null) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        errorBanner = "Lien streaming non reconnu ou hors-ligne. Utilise un lien Spotify, SoundCloud ou Deezer."
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        localTracks = locals,
                        resolvedLink = resolved,
                        activeTab = StudioTab.LIBRARY
                    )
                }
            }
        }
    }

    fun pauseAudio() {
        player.pause()
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
        runCatching { NativeDspBridge.nativeSessionClear() }
    }

    private fun formatTimeSec(sec: Float): String {
        val total = sec.coerceAtLeast(0f).roundToInt()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }
}
