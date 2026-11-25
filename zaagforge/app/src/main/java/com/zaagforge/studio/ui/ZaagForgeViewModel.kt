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
import com.zaagforge.studio.model.CustomPresetStore
import com.zaagforge.studio.model.DeezerSearchTrack
import com.zaagforge.studio.model.ExportFormat
import com.zaagforge.studio.model.ExportedRemix
import com.zaagforge.studio.model.KickType
import com.zaagforge.studio.model.MeterReading
import com.zaagforge.studio.model.RemixGenreTheme
import com.zaagforge.studio.model.RemixParams
import com.zaagforge.studio.model.RemixPreset
import com.zaagforge.studio.model.RemixRenderResult
import com.zaagforge.studio.model.ResolvedStreamingTrack
import com.zaagforge.studio.model.SectionMarker
import com.zaagforge.studio.model.StudioPresets
import com.zaagforge.studio.model.TrackAnalysis
import com.zaagforge.studio.model.TrackSourceType
import com.zaagforge.studio.music.LibraryAndStreamingRepository
import kotlinx.coroutines.CancellationException
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
    val selectedGenreFilter: RemixGenreTheme? = null, // null = TOUS
    val selectedPreset: RemixPreset = StudioPresets.byId("uok_piep_madness"),
    val customPresets: List<RemixPreset> = emptyList(),
    val params: RemixParams = StudioPresets.byId("uok_piep_madness").params,
    val remixResult: RemixRenderResult? = null,
    val waveformPeaks: FloatArray = FloatArray(0),
    val sectionMarkers: List<SectionMarker> = emptyList(),
    val hasPendingChanges: Boolean = false,
    val isBusy: Boolean = false,
    val busyMessage: String = "",
    val busyProgress: Float = 0f,
    val canCancelBusy: Boolean = false,
    val statusBanner: String? = null,
    val errorBanner: String? = null,
    val auditioningKick: KickType? = null,
    val showCutControls: Boolean = false,
    val showMixControls: Boolean = true,
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

    // Single-flight job serialization prevents concurrent renders from piling up on gSession.mutex!
    private var activeWorkJob: Job? = null
    private var workGeneration: Long = 0L

    private val _uiState = MutableStateFlow(ZaagForgeUiState())
    val uiState: StateFlow<ZaagForgeUiState> = _uiState.asStateFlow()

    init {
        loadCustomPresets()
        refreshExports()
        refreshLocalLibrary()
        loadTrack(LibraryAndStreamingRepository.builtInDemos.first(), autoRemixImmediately = true)
    }

    private fun loadCustomPresets() {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = CustomPresetStore.loadAll(appContext)
            _uiState.update { it.copy(customPresets = saved) }
        }
    }

    fun selectTab(tab: StudioTab) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == StudioTab.EXPORTS) refreshExports()
    }

    fun selectGenreFilter(theme: RemixGenreTheme?) {
        _uiState.update { it.copy(selectedGenreFilter = theme) }
    }

    fun cancelActiveWork() {
        workGeneration++
        runCatching { NativeDspBridge.nativeSessionRequestCancel() }
        activeWorkJob?.cancel()
        activeWorkJob = null
        _uiState.update {
            it.copy(
                isBusy = false,
                busyMessage = "",
                busyProgress = 0f,
                canCancelBusy = false,
                statusBanner = "Rendu annulé."
            )
        }
    }

    fun saveCustomPreset(name: String) {
        val currentParams = _uiState.value.params.sanitized()
        viewModelScope.launch(Dispatchers.IO) {
            val updated = CustomPresetStore.savePreset(appContext, name, currentParams)
            val created = updated.firstOrNull()
            _uiState.update {
                it.copy(
                    customPresets = updated,
                    selectedPreset = created ?: it.selectedPreset,
                    statusBanner = "Preset perso « ${created?.name ?: name} » sauvegardé !"
                )
            }
        }
    }

    fun deleteCustomPreset(presetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = CustomPresetStore.deletePreset(appContext, presetId)
            _uiState.update {
                it.copy(
                    customPresets = updated,
                    statusBanner = "Preset perso supprimé."
                )
            }
        }
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
        if (_uiState.value.isBusy) return
        _uiState.update { state ->
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

    fun placeDrop1AtPlayhead() {
        val frac = player.progress.value.coerceIn(0.04f, 0.88f)
        updateParams {
            it.copy(customDrop1Frac = frac, drop1BarNudge = 0)
        }
        val dur = _uiState.value.analysis?.durationSec ?: 0f
        _uiState.update {
            it.copy(statusBanner = "DROP 1 calé sur ${formatTimeSec(frac * dur)} · clique RE-CALCULER pour appliquer")
        }
    }

    fun placeDrop2AtPlayhead() {
        val frac = player.progress.value.coerceIn(0.12f, 0.95f)
        updateParams {
            it.copy(customDrop2Frac = frac, drop2BarNudge = 0)
        }
        val dur = _uiState.value.analysis?.durationSec ?: 0f
        _uiState.update {
            it.copy(statusBanner = "DROP 2 calé sur ${formatTimeSec(frac * dur)} · clique RE-CALCULER pour appliquer")
        }
    }

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

    fun scaleDetectedSourceBpm(factor: Float) {
        val baseBpm = _uiState.value.params.sourceBpmOverride.takeIf { it > 55f }
            ?: _uiState.value.analysis?.bpm ?: 128f
        val nextBpm = (baseBpm * factor).coerceIn(60f, 240f)
        updateParams { it.copy(sourceBpmOverride = nextBpm) }
        _uiState.update {
            it.copy(statusBanner = "BPM source corrigé à ${nextBpm.roundToInt()} BPM · clique RE-CALCULER")
        }
    }

    private fun refreshLiveSectionPreview(params: RemixParams) {
        if (!hasOriginalLoaded || _uiState.value.isBusy) return
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
        // Cancel any in-flight decode/render immediately so work never piles up!
        val prevJob = activeWorkJob
        val gen = ++workGeneration
        runCatching { NativeDspBridge.nativeSessionRequestCancel() }
        prevJob?.cancel()

        activeWorkJob = viewModelScope.launch {
            prevJob?.join()
            if (gen != workGeneration) return@launch

            player.pause()
            _uiState.update {
                it.copy(
                    activeTab = StudioTab.STUDIO,
                    isBusy = true,
                    busyMessage = "Décodage rapide & détection refrain / BPM…",
                    busyProgress = 0.02f,
                    canCancelBusy = true,
                    errorBanner = null,
                    statusBanner = null
                )
            }
            val decodeStartNs = System.nanoTime()
            try {
                val analysis = withContext(Dispatchers.IO) {
                    val onProg: (Int) -> Unit = { pct ->
                        if (gen == workGeneration) {
                            val stage = if (pct < 78) "Décodage MP3 haute vitesse ($pct %)…" else "Analyse BPM, Clé & Refrains ($pct %)…"
                            _uiState.update { s ->
                                s.copy(busyProgress = (pct / 100f).coerceIn(0.02f, 0.99f), busyMessage = stage)
                            }
                        }
                    }
                    val cancelCheck: () -> Boolean = { gen != workGeneration || !isActive }

                    when (item.sourceType) {
                        TrackSourceType.BUILTIN_DEMO ->
                            NativeDspBridge.loadDemoAndAnalyze(item.demoStyleIndex.coerceAtLeast(0), 30f)
                        TrackSourceType.DEEZER_PREVIEW -> {
                            val filePath = item.localFilePath
                                ?: throw IllegalStateException("Chemin aperçu Deezer manquant.")
                            AudioDecoder.decodeFileToNativeSession(File(filePath), onProg, cancelCheck)
                        }
                        TrackSourceType.LOCAL_MEDIASTORE, TrackSourceType.SAF_IMPORT -> {
                            val uriStr = item.uriString
                                ?: throw IllegalStateException("URI audio manquant.")
                            AudioDecoder.decodeUriToNativeSession(appContext, Uri.parse(uriStr), onProg, cancelCheck)
                        }
                    }
                }

                if (gen != workGeneration) return@launch

                hasOriginalLoaded = analysis.totalFrames > 0
                currentRemixFrames = 0
                player.setSessionFrameCounts(originalFrames = analysis.totalFrames, remixFrames = 0)

                val cleanParams = _uiState.value.params.copy(
                    sourceBpmOverride = 0f,
                    gridPhaseNudgeMs = 0f,
                    gridBeatShift = 0,
                    drop1BarNudge = 0,
                    drop2BarNudge = 0,
                    customDrop1Frac = -1f,
                    customDrop2Frac = -1f
                )

                val decodeSec = (System.nanoTime() - decodeStartNs) / 1_000_000_000.0
                _uiState.update {
                    it.copy(
                        loadedTrack = item.copy(durationMs = (analysis.durationSec * 1000f).toLong()),
                        analysis = analysis,
                        params = cleanParams,
                        remixResult = null,
                        waveformPeaks = analysis.waveformPeaks,
                        sectionMarkers = analysis.sections,
                        hasPendingChanges = false,
                        isBusy = autoRemixImmediately,
                        busyProgress = if (autoRemixImmediately) 0.05f else 0f,
                        busyMessage = if (autoRemixImmediately) "Lancement du moteur Multi-Cœurs C++20…" else "",
                        canCancelBusy = autoRemixImmediately,
                        statusBanner = "Analysé en ${String.format(Locale.US, "%.2fs", decodeSec)} · ${analysis.bpm.roundToInt()} BPM · Clé ${analysis.keyName} · Refrain 1 @ ${formatTimeSec(analysis.chorus1Sec)}"
                    )
                }

                if (autoRemixImmediately) {
                    runRemixInline(gen, analysis, cleanParams, _uiState.value.selectedPreset.name)
                }
            } catch (_: CancellationException) {
                if (gen == workGeneration) {
                    _uiState.update {
                        it.copy(isBusy = false, busyMessage = "", busyProgress = 0f, canCancelBusy = false)
                    }
                }
            } catch (t: Throwable) {
                if (gen == workGeneration) {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            busyMessage = "",
                            busyProgress = 0f,
                            canCancelBusy = false,
                            errorBanner = "Erreur chargement audio : ${t.message ?: "format non supporté"}"
                        )
                    }
                }
            }
        }
    }

    fun renderCurrentRemix() {
        val analysis = _uiState.value.analysis ?: return
        if (!hasOriginalLoaded) return
        val params = _uiState.value.params
        val presetName = _uiState.value.selectedPreset.name

        val prevJob = activeWorkJob
        val gen = ++workGeneration
        runCatching { NativeDspBridge.nativeSessionRequestCancel() }
        prevJob?.cancel()

        activeWorkJob = viewModelScope.launch {
            prevJob?.join()
            if (gen != workGeneration) return@launch
            runRemixInline(gen, analysis, params, presetName)
        }
    }

    private suspend fun runRemixInline(
        gen: Long,
        analysis: TrackAnalysis,
        params: RemixParams,
        presetName: String
    ) {
        player.pause()
        _uiState.update {
            it.copy(
                isBusy = true,
                busyMessage = "Mixage Multi-Cœurs ($presetName · ${params.targetBpm.roundToInt()} BPM)…",
                busyProgress = 0.04f,
                canCancelBusy = true,
                errorBanner = null
            )
        }

        // Poll lock-free native progress atomics every 60 ms for silky smooth live progress bar!
        val progressPoller = viewModelScope.launch {
            while (isActive && gen == workGeneration) {
                delay(60L)
                val pct = runCatching { NativeDspBridge.nativeSessionGetProgress() }.getOrDefault(0)
                if (pct > 0 && gen == workGeneration) {
                    val stage = when {
                        pct < 38 -> "Recalage tempo multi-cœurs ($pct %)…"
                        pct < 96 -> "Mixage voix, kicks & mastering ($pct %)…"
                        else -> "Finalisation forme d'onde ($pct %)…"
                    }
                    _uiState.update { s ->
                        if (s.isBusy) s.copy(busyProgress = (pct / 100f).coerceIn(0.04f, 0.99f), busyMessage = stage) else s
                    }
                }
            }
        }

        try {
            val rendered = withContext(Dispatchers.Default) {
                NativeDspBridge.renderSessionRemix(params, analysis)
            }
            if (gen != workGeneration || rendered == null) return

            currentRemixFrames = rendered.totalFrames
            player.setRemixFrameCount(rendered.totalFrames, autoSwitchToRemix = true)

            val secStr = String.format(Locale.US, "%.2fs", rendered.renderTimeMs / 1000f)
            val spdStr = String.format(Locale.US, "×%.2f", rendered.tempoStretchRatio)
            val rmsStr = String.format(Locale.US, "%.1f", rendered.rmsDb)

            _uiState.update {
                it.copy(
                    remixResult = rendered,
                    waveformPeaks = rendered.waveformPeaks,
                    sectionMarkers = rendered.sections,
                    hasPendingChanges = false,
                    isBusy = false,
                    busyMessage = "",
                    busyProgress = 1f,
                    canCancelBusy = false,
                    statusBanner = "Rendu en $secStr · ${rendered.effectiveTargetBpm.roundToInt()} BPM (Vitesse $spdStr) · Clé ${rendered.keyName} · RMS $rmsStr dB"
                )
            }
        } catch (_: CancellationException) {
            // Cancelled cleanly
        } catch (t: Throwable) {
            if (gen == workGeneration) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        busyProgress = 0f,
                        canCancelBusy = false,
                        errorBanner = "Erreur rendu C++ : ${t.message}"
                    )
                }
            }
        } finally {
            progressPoller.cancel()
            // Guaranteed safety net: NEVER leave the loading overlay stuck!
            if (gen == workGeneration && _uiState.value.isBusy) {
                _uiState.update {
                    it.copy(isBusy = false, busyMessage = "", busyProgress = 0f, canCancelBusy = false)
                }
            }
        }
    }

    fun auditionKick(kick: KickType) {
        if (_uiState.value.isBusy) return
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
        if (_uiState.value.isBusy) return
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
                    busyProgress = 0.5f,
                    canCancelBusy = false,
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
                        busyProgress = 0f,
                        lastExportNotice = "Exporté : ${exported.displayName} (Music/ZaagForge/ · prêt dans StrikeCam !)"
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        busyProgress = 0f,
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
        val prevJob = activeWorkJob
        val gen = ++workGeneration
        runCatching { NativeDspBridge.nativeSessionRequestCancel() }
        prevJob?.cancel()

        activeWorkJob = viewModelScope.launch {
            prevJob?.join()
            if (gen != workGeneration) return@launch
            _uiState.update {
                it.copy(
                    activeTab = StudioTab.STUDIO,
                    isBusy = true,
                    busyMessage = "Téléchargement aperçu 30 s Deezer (${track.title})…",
                    busyProgress = 0.15f,
                    canCancelBusy = true,
                    errorBanner = null,
                    resolvedLink = null
                )
            }
            try {
                val cachedFile = withContext(Dispatchers.IO) {
                    LibraryAndStreamingRepository.downloadPreviewToCache(appContext, track.previewUrl, track.id)
                }
                if (gen != workGeneration) return@launch
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
            } catch (_: CancellationException) {
                if (gen == workGeneration) {
                    _uiState.update { it.copy(isBusy = false, busyMessage = "", busyProgress = 0f, canCancelBusy = false) }
                }
            } catch (t: Throwable) {
                if (gen == workGeneration) {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            busyMessage = "",
                            busyProgress = 0f,
                            canCancelBusy = false,
                            errorBanner = "Impossible de récupérer l'aperçu Deezer : ${t.message}"
                        )
                    }
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
                    busyProgress = 0.35f,
                    canCancelBusy = false,
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
                        busyProgress = 0f,
                        errorBanner = "Lien streaming non reconnu ou hors-ligne. Utilise un lien Spotify, SoundCloud ou Deezer."
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        busyMessage = "",
                        busyProgress = 0f,
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
        workGeneration++
        runCatching { NativeDspBridge.nativeSessionRequestCancel() }
        activeWorkJob?.cancel()
        player.release()
        runCatching { NativeDspBridge.nativeSessionClear() }
    }

    private fun formatTimeSec(sec: Float): String {
        val total = sec.coerceAtLeast(0f).roundToInt()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }
}
