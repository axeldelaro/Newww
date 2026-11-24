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
    val isBusy: Boolean = false,
    val busyMessage: String = "",
    val statusBanner: String? = null,
    val errorBanner: String? = null,
    val auditioningKick: KickType? = null,
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

    private var currentOriginalPcm: FloatArray = FloatArray(0)
    private var currentRemixPcm: FloatArray = FloatArray(0)

    private val _uiState = MutableStateFlow(ZaagForgeUiState())
    val uiState: StateFlow<ZaagForgeUiState> = _uiState.asStateFlow()

    init {
        refreshExports()
        refreshLocalLibrary()
        // Auto-load Built-In Demo 0 on first launch so the user can audition & remix immediately
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

    fun toggleProTweaks() {
        _uiState.update { it.copy(showProTweaks = !it.showProTweaks) }
    }

    fun selectPreset(preset: RemixPreset, renderNow: Boolean = false) {
        _uiState.update {
            it.copy(
                selectedPreset = preset,
                params = preset.params,
                errorBanner = null
            )
        }
        if (renderNow && currentOriginalPcm.isNotEmpty() && _uiState.value.analysis != null) {
            renderCurrentRemix()
        }
    }

    fun updateParams(transform: (RemixParams) -> RemixParams) {
        _uiState.update { state ->
            state.copy(params = transform(state.params).sanitized())
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
                    busyMessage = "Décodage & analyse BPM / tonalité…",
                    errorBanner = null,
                    statusBanner = null
                )
            }
            try {
                val decodedPcm = withContext(Dispatchers.IO) {
                    when (item.sourceType) {
                        TrackSourceType.BUILTIN_DEMO ->
                            NativeDspBridge.generateDemoTrack(item.demoStyleIndex.coerceAtLeast(0), 30f)
                        TrackSourceType.DEEZER_PREVIEW -> {
                            val filePath = item.localFilePath
                                ?: throw IllegalStateException("Chemin aperçu Deezer manquant.")
                            AudioDecoder.decodeFile(File(filePath)).stereoPcm
                        }
                        TrackSourceType.LOCAL_MEDIASTORE, TrackSourceType.SAF_IMPORT -> {
                            val uriStr = item.uriString
                                ?: throw IllegalStateException("URI audio manquant.")
                            AudioDecoder.decodeUri(appContext, Uri.parse(uriStr)).stereoPcm
                        }
                    }
                }

                val analysis = withContext(Dispatchers.Default) {
                    NativeDspBridge.analyzeTrack(decodedPcm, AudioDecoder.TARGET_SAMPLE_RATE)
                }

                currentOriginalPcm = decodedPcm
                currentRemixPcm = FloatArray(0)
                player.loadBuffers(original = decodedPcm, remix = null)

                _uiState.update {
                    it.copy(
                        loadedTrack = item.copy(durationMs = (analysis.durationSec * 1000f).toLong()),
                        analysis = analysis,
                        remixResult = null,
                        waveformPeaks = analysis.waveformPeaks,
                        sectionMarkers = analysis.sections,
                        isBusy = false,
                        busyMessage = "",
                        statusBanner = "Analysé : ${analysis.bpm.roundToInt()} BPM · Clé ${analysis.keyName} (${analysis.rootHz.roundToInt()} Hz)"
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
        if (currentOriginalPcm.isEmpty()) return
        val params = _uiState.value.params
        val presetName = _uiState.value.selectedPreset.name

        viewModelScope.launch {
            player.pause()
            _uiState.update {
                it.copy(
                    isBusy = true,
                    busyMessage = "Synthèse C++20 ($presetName · ${params.targetBpm.roundToInt()} BPM)…",
                    errorBanner = null,
                    statusBanner = null
                )
            }
            try {
                val rendered = withContext(Dispatchers.Default) {
                    NativeDspBridge.renderRemix(
                        stereoPcm = currentOriginalPcm,
                        analysis = analysis,
                        params = params,
                        sampleRate = AudioDecoder.TARGET_SAMPLE_RATE
                    )
                }

                currentRemixPcm = rendered.stereoPcm
                player.setRemixBuffer(rendered.stereoPcm, autoSwitchToRemix = true)

                _uiState.update {
                    it.copy(
                        remixResult = rendered,
                        waveformPeaks = rendered.waveformPeaks,
                        sectionMarkers = rendered.sections,
                        isBusy = false,
                        busyMessage = "",
                        statusBanner = "REMIX PRÊT · ${rendered.effectiveTargetBpm.roundToInt()} BPM · Clé ${rendered.keyName} · RMS ${String.format("%.1f", rendered.rmsDb)} dB"
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
                    aggression = params.zaagAggression
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
        val sections = if (playingRemixNow) {
            state.remixResult?.sections ?: state.analysis?.sections.orEmpty()
        } else {
            state.analysis?.sections.orEmpty()
        }
        _uiState.update { it.copy(waveformPeaks = peaks, sectionMarkers = sections) }
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
        if (remix == null || currentRemixPcm.isEmpty()) {
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
                    busyMessage = "Export ${format.badge} vers Music/ZaagForge/…",
                    errorBanner = null
                )
            }
            try {
                val exported = withContext(Dispatchers.IO) {
                    AudioExporter.exportRemix(
                        context = appContext,
                        stereoPcm = currentRemixPcm,
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

    // -------------------------------------------------------------------------
    // Deezer Public Search + Spotify / SoundCloud / Deezer Share-Link Bridge
    // -------------------------------------------------------------------------

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
    }
}
