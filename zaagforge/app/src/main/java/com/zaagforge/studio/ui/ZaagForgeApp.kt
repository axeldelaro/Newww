package com.zaagforge.studio.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaagforge.studio.model.AudioTrackItem
import com.zaagforge.studio.model.DeezerSearchTrack
import com.zaagforge.studio.model.DropAlignmentMode
import com.zaagforge.studio.model.ExportFormat
import com.zaagforge.studio.model.ExportedRemix
import com.zaagforge.studio.model.KickType
import com.zaagforge.studio.model.MasterMode
import com.zaagforge.studio.model.MeterReading
import com.zaagforge.studio.model.RemixGenreTheme
import com.zaagforge.studio.model.RemixParams
import com.zaagforge.studio.model.RemixPreset
import com.zaagforge.studio.model.ResolvedStreamingTrack
import com.zaagforge.studio.model.SectionKind
import com.zaagforge.studio.model.SectionMarker
import com.zaagforge.studio.model.StudioPresets
import com.zaagforge.studio.model.TempoSyncMode
import com.zaagforge.studio.music.LibraryAndStreamingRepository
import com.zaagforge.studio.ui.theme.CrispWhite
import com.zaagforge.studio.ui.theme.HazardRed
import com.zaagforge.studio.ui.theme.MeterGreen
import com.zaagforge.studio.ui.theme.PeakAmber
import com.zaagforge.studio.ui.theme.RackBorder
import com.zaagforge.studio.ui.theme.RackCharcoal
import com.zaagforge.studio.ui.theme.RackMetal
import com.zaagforge.studio.ui.theme.RackObsidian
import com.zaagforge.studio.ui.theme.SignalOrange
import com.zaagforge.studio.ui.theme.SteelDim
import com.zaagforge.studio.ui.theme.SteelGray
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ZaagForgeApp(viewModel: ZaagForgeViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val progress by viewModel.playbackProgress.collectAsStateWithLifecycle()
    val isRemixActive by viewModel.isRemixActive.collectAsStateWithLifecycle()
    val meter by viewModel.meterReading.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Dynamic Studio LED Accent matches active RemixGenreTheme (Techno Cyan / Hard Techno Orange / Frenchcore Green / Gabber Crimson / Uptempo Red)
    val themeAccent = Color(state.params.genreTheme.accentHex)
    val themeDimBg = Color(state.params.genreTheme.dimBgHex)

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.importSafAudio(uri)
        }
    }

    val readPerm = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasAudioPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, readPerm) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAudioPermission = granted
        if (granted) viewModel.refreshLocalLibrary()
    }

    var showLinkDialog by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = RackObsidian,
            bottomBar = {
                NavigationBar(
                    containerColor = RackCharcoal,
                    tonalElevation = 0.dp
                ) {
                    val items = listOf(
                        Triple(StudioTab.STUDIO, "STUDIO", Icons.Filled.GraphicEq),
                        Triple(StudioTab.LIBRARY, "BIBLIO", Icons.Filled.LibraryMusic),
                        Triple(StudioTab.EXPORTS, "EXPORTS", Icons.Filled.Download)
                    )
                    items.forEach { (tab, title, icon) ->
                        val selected = state.activeTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { viewModel.selectTab(tab) },
                            icon = { Icon(icon, contentDescription = title) },
                            label = { Text(title, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = themeAccent,
                                selectedTextColor = themeAccent,
                                unselectedIconColor = SteelGray,
                                unselectedTextColor = SteelGray,
                                indicatorColor = themeDimBg
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(RackObsidian)
            ) {
                when (state.activeTab) {
                    StudioTab.STUDIO -> StudioScreen(
                        state = state,
                        themeAccent = themeAccent,
                        themeDimBg = themeDimBg,
                        isPlaying = isPlaying,
                        progress = progress,
                        isRemixActive = isRemixActive,
                        meter = meter,
                        onImportMp3 = { audioPicker.launch(arrayOf("audio/*")) },
                        onOpenLinkDialog = { showLinkDialog = true },
                        onOpenSavePresetDialog = { showSavePresetDialog = true },
                        onSelectGenreFilter = { viewModel.selectGenreFilter(it) },
                        onSelectPreset = { viewModel.selectPreset(it, renderNow = true) },
                        onDeleteCustomPreset = { viewModel.deleteCustomPreset(it) },
                        onRenderRemix = { viewModel.renderCurrentRemix() },
                        onTogglePlay = { viewModel.togglePlayPause() },
                        onToggleAb = { viewModel.toggleAbSource() },
                        onSeek = { viewModel.seekToFraction(it) },
                        onJumpSection = { viewModel.jumpToSection(it) },
                        onAuditionKick = { viewModel.auditionKick(it) },
                        onToggleCutControls = { viewModel.toggleCutControls() },
                        onToggleMixControls = { viewModel.toggleMixControls() },
                        onToggleProTweaks = { viewModel.toggleProTweaks() },
                        onPlaceDrop1Here = { viewModel.placeDrop1AtPlayhead() },
                        onPlaceDrop2Here = { viewModel.placeDrop2AtPlayhead() },
                        onResetTimingAuto = { viewModel.resetTimingToAuto() },
                        onScaleSourceBpm = { viewModel.scaleDetectedSourceBpm(it) },
                        onUpdateParams = { viewModel.updateParams(it) },
                        onExport = { viewModel.exportCurrentRemix(it) }
                    )

                    StudioTab.LIBRARY -> LibraryScreen(
                        state = state,
                        themeAccent = themeAccent,
                        hasAudioPermission = hasAudioPermission,
                        onRequestPermission = { permLauncher.launch(readPerm) },
                        onImportSaf = { audioPicker.launch(arrayOf("audio/*")) },
                        onOpenLinkDialog = { showLinkDialog = true },
                        onSelectTrack = { viewModel.loadTrack(it, autoRemixImmediately = true) },
                        onDeezerQueryChange = { viewModel.onDeezerQueryChange(it) },
                        onSearchDeezer = { viewModel.searchDeezerNow() },
                        onSelectDeezerTrack = { viewModel.loadDeezerPreviewTrack(it) },
                        onDismissResolved = { viewModel.dismissResolvedLink() }
                    )

                    StudioTab.EXPORTS -> ExportsScreen(
                        exports = state.exportedRemixes,
                        onRefresh = { viewModel.refreshExports() },
                        onPlayExport = { viewModel.playExportedFile(it) },
                        onShareExport = { exp ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = exp.format.mimeType
                                putExtra(Intent.EXTRA_STREAM, Uri.parse(exp.uriString))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Partager ${exp.displayName}"))
                        }
                    )
                }
            }
        }

        if (state.isBusy) {
            // Full-screen touch-blocking overlay above Scaffold + bottomBar: ZERO taps leak through!
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(RackObsidian.copy(alpha = 0.88f))
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = RackCharcoal,
                    border = BorderStroke(1.5.dp, themeAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "MOTEUR ULTRA-TURBO C++20",
                                style = MaterialTheme.typography.labelMedium,
                                color = themeAccent
                            )
                            Text(
                                text = "${(state.busyProgress * 100f).roundToInt()} %",
                                style = MaterialTheme.typography.labelLarge,
                                color = CrispWhite
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { state.busyProgress.coerceIn(0.02f, 1f) },
                            color = themeAccent,
                            trackColor = RackMetal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = state.busyMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CrispWhite,
                            textAlign = TextAlign.Center
                        )
                        if (state.canCancelBusy) {
                            Spacer(Modifier.height(14.dp))
                            OutlinedButton(
                                onClick = { viewModel.cancelActiveWork() },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, RackBorder)
                            ) {
                                Text("ANNULER", style = MaterialTheme.typography.labelMedium, color = SteelGray)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLinkDialog) {
        PasteStreamingLinkDialog(
            themeAccent = themeAccent,
            onDismiss = { showLinkDialog = false },
            onResolve = { url ->
                showLinkDialog = false
                viewModel.handleIncomingShareLink(url)
            }
        )
    }

    if (showSavePresetDialog) {
        SaveCustomPresetDialog(
            themeAccent = themeAccent,
            defaultName = "${state.params.genreTheme.shortLabel} ${state.params.targetBpm.roundToInt()}",
            onDismiss = { showSavePresetDialog = false },
            onSave = { name ->
                showSavePresetDialog = false
                viewModel.saveCustomPreset(name)
            }
        )
    }
}

@Composable
private fun StudioScreen(
    state: ZaagForgeUiState,
    themeAccent: Color,
    themeDimBg: Color,
    isPlaying: Boolean,
    progress: Float,
    isRemixActive: Boolean,
    meter: MeterReading,
    onImportMp3: () -> Unit,
    onOpenLinkDialog: () -> Unit,
    onOpenSavePresetDialog: () -> Unit,
    onSelectGenreFilter: (RemixGenreTheme?) -> Unit,
    onSelectPreset: (RemixPreset) -> Unit,
    onDeleteCustomPreset: (String) -> Unit,
    onRenderRemix: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleAb: () -> Unit,
    onSeek: (Float) -> Unit,
    onJumpSection: (SectionMarker) -> Unit,
    onAuditionKick: (KickType) -> Unit,
    onToggleCutControls: () -> Unit,
    onToggleMixControls: () -> Unit,
    onToggleProTweaks: () -> Unit,
    onPlaceDrop1Here: () -> Unit,
    onPlaceDrop2Here: () -> Unit,
    onResetTimingAuto: () -> Unit,
    onScaleSourceBpm: (Float) -> Unit,
    onUpdateParams: ((RemixParams) -> RemixParams) -> Unit,
    onExport: (ExportFormat) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Dynamic Hardware Header Bar reflecting active genre theme
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ZAAGFORGE // v3.5 TURBO",
                    style = MaterialTheme.typography.headlineMedium,
                    color = CrispWhite
                )
                Text(
                    text = "${state.params.genreTheme.shortLabel} · ${state.params.genreTheme.artistInspo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = themeAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RackMiniButton(icon = Icons.Filled.FolderOpen, label = "MP3", accent = themeAccent, onClick = onImportMp3)
                RackMiniButton(icon = Icons.Filled.Link, label = "LIEN", accent = themeAccent, onClick = onOpenLinkDialog)
            }
        }

        state.errorBanner?.let { err ->
            BannerCard(text = err, accent = HazardRed)
        }
        state.lastExportNotice?.let { notice ->
            BannerCard(text = notice, accent = MeterGreen)
        }

        // Deck Telemetry + Interactive Waveform + A/B Transport
        RackCard(borderColor = themeAccent.copy(alpha = 0.45f)) {
            val track = state.loadedTrack
            val analysis = state.analysis
            val remix = state.remixResult
            val activeDuration = if (isRemixActive && remix != null) remix.durationSec else (analysis?.durationSec ?: 0f)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track?.title ?: "Aucune piste chargée",
                        style = MaterialTheme.typography.titleMedium,
                        color = CrispWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${track?.artist ?: "Importe un MP3"} · ${formatSec(progress * activeDuration)} / ${formatSec(activeDuration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                val hasRemix = remix != null
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isRemixActive && hasRemix) themeAccent else RackMetal,
                    border = BorderStroke(1.dp, if (isRemixActive && hasRemix) themeAccent else RackBorder),
                    modifier = Modifier.clickable(enabled = hasRemix) { onToggleAb() }
                ) {
                    Text(
                        text = if (isRemixActive && hasRemix) "REMIX ●" else "ORIGINAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isRemixActive && hasRemix && state.params.genreTheme == RemixGenreTheme.TECHNO_CLUB) RackObsidian else CrispWhite,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            val displayedSrcBpm = state.params.sourceBpmOverride.takeIf { it > 55f } ?: analysis?.bpm
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TelemetryCell(
                    label = "SRC BPM",
                    value = displayedSrcBpm?.roundToInt()?.toString() ?: "--",
                    modifier = Modifier.weight(1f)
                )
                TelemetryCell(
                    label = "REMIX",
                    value = "${(remix?.effectiveTargetBpm ?: state.params.targetBpm).roundToInt()}",
                    valueColor = themeAccent,
                    modifier = Modifier.weight(1f)
                )
                TelemetryCell(
                    label = "VITESSE",
                    value = remix?.let { String.format(Locale.US, "×%.2f", it.tempoStretchRatio) } ?: "≥1.00",
                    valueColor = MeterGreen,
                    modifier = Modifier.weight(1f)
                )
                TelemetryCell(
                    label = "CLÉ",
                    value = remix?.keyName ?: analysis?.keyName ?: "--",
                    valueColor = PeakAmber,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))

            SectionWaveformView(
                peaks = state.waveformPeaks,
                sections = state.sectionMarkers,
                progress = progress,
                isRemixActive = isRemixActive && remix != null,
                remixAccent = themeAccent,
                onSeek = onSeek,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
            )

            Spacer(Modifier.height(8.dp))

            StereoVuMeter(meter = meter)

            Spacer(Modifier.height(8.dp))

            if (state.sectionMarkers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    state.sectionMarkers.forEach { marker ->
                        val chipColor = sectionColor(marker.kind, themeAccent)
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = chipColor.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, chipColor.copy(alpha = 0.65f)),
                            modifier = Modifier.clickable { onJumpSection(marker) }
                        ) {
                            Text(
                                text = marker.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = chipColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onTogglePlay,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (isPlaying) MeterGreen else RackBorder),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier.weight(0.42f)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = if (isPlaying) MeterGreen else CrispWhite,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (isPlaying) "PAUSE" else "ÉCOUTER",
                        style = MaterialTheme.typography.labelLarge,
                        color = CrispWhite
                    )
                }

                val btnContentColor = if (!state.hasPendingChanges && state.params.genreTheme == RemixGenreTheme.TECHNO_CLUB) RackObsidian else CrispWhite
                Button(
                    onClick = onRenderRemix,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.hasPendingChanges) SignalOrange else themeAccent,
                        contentColor = btnContentColor
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier.weight(0.58f)
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (state.hasPendingChanges) "RE-CALCULER ●" else "AUTO REMIX TURBO",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            state.statusBanner?.let { status ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelGray
                )
            }
        }

        // RACK 1: THÈMES & 12 PRESETS PRO ARTISTES + MES PRESETS PERSO
        RackCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("THÈMES & PRESETS PRO (12)", style = MaterialTheme.typography.labelLarge, color = themeAccent)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = themeDimBg,
                    border = BorderStroke(1.dp, themeAccent),
                    modifier = Modifier.clickable { onOpenSavePresetDialog() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = themeAccent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("SAUVER PERSO", style = MaterialTheme.typography.labelSmall, color = themeAccent)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // Genre Filter Bar (TOUS / TECHNO / HARD TECHNO / FRENCHCORE / GABBER / RAWSTYLE / UPTEMPO)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val allSel = state.selectedGenreFilter == null
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (allSel) themeAccent.copy(alpha = 0.22f) else RackMetal,
                    border = BorderStroke(1.dp, if (allSel) themeAccent else RackBorder),
                    modifier = Modifier.clickable { onSelectGenreFilter(null) }
                ) {
                    Text(
                        text = "TOUS (${StudioPresets.all.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (allSel) themeAccent else CrispWhite,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                    )
                }
                RemixGenreTheme.entries.forEach { genre ->
                    val sel = state.selectedGenreFilter == genre
                    val gAccent = Color(genre.accentHex)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (sel) gAccent.copy(alpha = 0.22f) else RackMetal,
                        border = BorderStroke(1.dp, if (sel) gAccent else RackBorder),
                        modifier = Modifier.clickable { onSelectGenreFilter(genre) }
                    ) {
                        Text(
                            text = genre.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sel) gAccent else CrispWhite,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // User-Saved Custom Presets Row (when present)
            if (state.customPresets.isNotEmpty()) {
                Text("MES PRESETS PERSO (${state.customPresets.size})", style = MaterialTheme.typography.labelSmall, color = PeakAmber)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.customPresets.forEach { cp ->
                        val selected = cp.id == state.selectedPreset.id
                        val cAccent = Color(cp.params.genreTheme.accentHex)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) Color(cp.params.genreTheme.dimBgHex) else RackMetal,
                            border = BorderStroke(1.dp, if (selected) cAccent else PeakAmber.copy(alpha = 0.55f)),
                            modifier = Modifier.clickable { onSelectPreset(cp) }
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(cp.name, style = MaterialTheme.typography.labelMedium, color = CrispWhite, maxLines = 1)
                                    Text("${cp.params.genreTheme.shortLabel} · ${cp.params.targetBpm.roundToInt()} BPM", style = MaterialTheme.typography.labelSmall, color = cAccent)
                                }
                                IconButton(
                                    onClick = { onDeleteCustomPreset(cp.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Supprimer", tint = SteelGray, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Filtered Studio Artist Presets Grid
            val displayedPresets = state.selectedGenreFilter?.let { filter ->
                StudioPresets.all.filter { it.params.genreTheme == filter }
            } ?: StudioPresets.all

            displayedPresets.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { preset ->
                        val selected = preset.id == state.selectedPreset.id
                        val pAccent = Color(preset.params.genreTheme.accentHex)
                        val pDim = Color(preset.params.genreTheme.dimBgHex)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) pDim else RackMetal,
                            border = BorderStroke(1.dp, if (selected) pAccent else RackBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelectPreset(preset) }
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = preset.shortCode,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = pAccent
                                    )
                                    Text(
                                        text = "${preset.params.targetBpm.roundToInt()} BPM",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SteelGray
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = CrispWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = preset.genreTag,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SteelGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // RACK 2: VITESSE, PAROLES ÉPIQUES, COUCHES SYNTHÉ & QUALITÉ MASTERING
        RackCard {
            val p = state.params
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleMixControls() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Equalizer, contentDescription = null, tint = MeterGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("VITESSE, PAROLES ÉPIQUES & MIX", style = MaterialTheme.typography.labelLarge, color = CrispWhite)
                        Text(
                            text = "${p.tempoSyncMode.label} · Voix Épiques ${(p.epicVocalBoost * 100).roundToInt()}% · Sub ${(p.bassSaturation * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeterGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = if (state.showMixControls) "MASQUER ▲" else "OUVRIR ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeterGreen
                )
            }

            if (state.showMixControls) {
                Spacer(Modifier.height(10.dp))

                // Speed Policy (NEVER SLOW DOWN!)
                Text("CONTRÔLE VITESSE (NE RALENTIT JAMAIS LA MUSIQUE)", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TempoSyncMode.entries.forEach { mode ->
                        val sel = p.tempoSyncMode == mode
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = if (sel) themeAccent.copy(alpha = 0.22f) else RackMetal,
                            border = BorderStroke(1.dp, if (sel) themeAccent else RackBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onUpdateParams { it.copy(tempoSyncMode = mode) } }
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sel) themeAccent else CrispWhite,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(p.tempoSyncMode.subtitle, style = MaterialTheme.typography.bodySmall, color = SteelGray)

                Spacer(Modifier.height(10.dp))

                Text("QUALITÉ MASTERING", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MasterMode.entries.forEach { mode ->
                        val sel = p.masterMode == mode
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = if (sel) MeterGreen.copy(alpha = 0.20f) else RackMetal,
                            border = BorderStroke(1.dp, if (sel) MeterGreen else RackBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onUpdateParams { it.copy(masterMode = mode) } }
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sel) MeterGreen else CrispWhite,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(p.masterMode.subtitle, style = MaterialTheme.typography.bodySmall, color = SteelGray)

                Spacer(Modifier.height(10.dp))

                StudioSlider(
                    label = "BOOST PAROLES ÉPIQUES (DROP / BASSE)",
                    valueText = "${(p.epicVocalBoost * 100).roundToInt()}%",
                    value = p.epicVocalBoost,
                    range = 0f..1f,
                    accent = MeterGreen,
                    onChange = { v -> onUpdateParams { it.copy(epicVocalBoost = v) } }
                )
                StudioSlider(
                    label = "CLARTÉ PAROLES (FOCUS VOIX MID/SIDE)",
                    valueText = "${(p.vocalClarity * 100).roundToInt()}%",
                    value = p.vocalClarity,
                    range = 0f..1f,
                    accent = MeterGreen,
                    onChange = { v -> onUpdateParams { it.copy(vocalClarity = v) } }
                )
                StudioSlider(
                    label = "NIVEAU PAROLES / MUSIQUE ORIGINALE",
                    valueText = "${(p.originalMix * 100).roundToInt()}%",
                    value = p.originalMix,
                    range = 0.15f..1f,
                    accent = themeAccent,
                    onChange = { v -> onUpdateParams { it.copy(originalMix = v) } }
                )
                StudioSlider(
                    label = "SATURATION BASSES / SUB (0% = SUB PUR)",
                    valueText = "${(p.bassSaturation * 100).roundToInt()}%",
                    value = p.bassSaturation,
                    range = 0f..1f,
                    accent = themeAccent,
                    onChange = { v -> onUpdateParams { it.copy(bassSaturation = v) } }
                )
                StudioSlider(
                    label = "NIVEAU KICKS",
                    valueText = "${(p.kickLevel * 100).roundToInt()}%",
                    value = p.kickLevel,
                    range = 0.35f..1.20f,
                    accent = themeAccent,
                    onChange = { v -> onUpdateParams { it.copy(kickLevel = v) } }
                )
                StudioSlider(
                    label = "COUCHES SYNTHÉ (303 / EUPHORIC / HOOVER)",
                    valueText = "${(p.synthLayerAmount * 100).roundToInt()}%",
                    value = p.synthLayerAmount,
                    range = 0f..1f,
                    accent = PeakAmber,
                    onChange = { v -> onUpdateParams { it.copy(synthLayerAmount = v) } }
                )
                StudioSlider(
                    label = "PERCUSSIONS 909 (CLAP / 16TH HATS / RIDE)",
                    valueText = "${(p.percGrooveAmount * 100).roundToInt()}%",
                    value = p.percGrooveAmount,
                    range = 0f..1f,
                    accent = PeakAmber,
                    onChange = { v -> onUpdateParams { it.copy(percGrooveAmount = v) } }
                )
                StudioSlider(
                    label = "SIDECHAIN SUR LES VOIX (DOUX ← → FORT)",
                    valueText = "${(p.vocalSidechain * 100).roundToInt()}%",
                    value = p.vocalSidechain,
                    range = 0f..0.80f,
                    accent = themeAccent,
                    onChange = { v -> onUpdateParams { it.copy(vocalSidechain = v) } }
                )
                StudioSlider(
                    label = "COUPE-BAS PISTE ORIGINALE (HPF)",
                    valueText = "${p.bassCutHz.roundToInt()} Hz",
                    value = p.bassCutHz,
                    range = 110f..340f,
                    accent = themeAccent,
                    onChange = { v -> onUpdateParams { it.copy(bassCutHz = (v / 5f).roundToInt() * 5f) } }
                )

                Button(
                    onClick = onRenderRemix,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MeterGreen, contentColor = RackObsidian),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("APPLIQUER LE MIX TURBO C++", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // RACK 3: Surgical Drop & Beat-Grid Precision Cutting ("CALAGE & DÉCOUPE PRÉCISE")
        RackCard {
            val p = state.params
            val analysis = state.analysis
            val drop1Sec = state.sectionMarkers.firstOrNull { it.kind == SectionKind.DROP_1 }
                ?.let { it.startFrac * (analysis?.durationSec ?: 0f) }
            val drop2Sec = state.sectionMarkers.firstOrNull { it.kind == SectionKind.DROP_2 }
                ?.let { it.startFrac * (analysis?.durationSec ?: 0f) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleCutControls() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ContentCut, contentDescription = null, tint = PeakAmber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("CALAGE & DÉCOUPE PRÉCISE", style = MaterialTheme.typography.labelLarge, color = CrispWhite)
                        Text(
                            text = "Drop 1 @ ${drop1Sec?.let(::formatSec) ?: "--"} · Drop 2 @ ${drop2Sec?.let(::formatSec) ?: "--"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = PeakAmber
                        )
                    }
                }
                Text(
                    text = if (state.showCutControls) "MASQUER ▲" else "OUVRIR ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = PeakAmber
                )
            }

            if (state.showCutControls) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Écoute l'ORIGINAL, place le curseur pile sur le début du refrain puis clique « POSER DROP 1 ICI » :",
                    style = MaterialTheme.typography.bodySmall,
                    color = SteelGray
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onPlaceDrop1Here,
                        shape = RoundedCornerShape(7.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = HazardRed, contentColor = CrispWhite),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("POSER DROP 1 ICI", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    Button(
                        onClick = onPlaceDrop2Here,
                        shape = RoundedCornerShape(7.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = CrispWhite),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("POSER DROP 2 ICI", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onResetTimingAuto,
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, RackBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        Text("AUTO", style = MaterialTheme.typography.labelSmall, color = CrispWhite)
                    }
                }

                Spacer(Modifier.height(10.dp))

                BarNudgeRow(
                    label = "DÉCALAGE DROP 1",
                    nudgeBars = p.drop1BarNudge,
                    customActive = p.customDrop1Frac >= 0f,
                    onNudge = { d -> onUpdateParams { it.copy(drop1BarNudge = it.drop1BarNudge + d) } }
                )
                Spacer(Modifier.height(6.dp))
                BarNudgeRow(
                    label = "DÉCALAGE DROP 2",
                    nudgeBars = p.drop2BarNudge,
                    customActive = p.customDrop2Frac >= 0f,
                    onNudge = { d -> onUpdateParams { it.copy(drop2BarNudge = it.drop2BarNudge + d) } }
                )

                Spacer(Modifier.height(10.dp))

                Text("STRUCTURE DES DROPS", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DropAlignmentMode.entries.forEach { mode ->
                        val sel = p.dropMode == mode
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (sel) PeakAmber.copy(alpha = 0.22f) else RackMetal,
                            border = BorderStroke(1.dp, if (sel) PeakAmber else RackBorder),
                            modifier = Modifier.clickable { onUpdateParams { it.copy(dropMode = mode) } }
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sel) PeakAmber else CrispWhite,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("DURÉE BUILD-UP", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4, 8, 12).forEach { bars ->
                            val sel = p.buildupBars == bars
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (sel) SignalOrange else RackMetal,
                                border = BorderStroke(1.dp, if (sel) SignalOrange else RackBorder),
                                modifier = Modifier.clickable { onUpdateParams { it.copy(buildupBars = bars) } }
                            ) {
                                Text(
                                    text = "$bars MES.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CrispWhite,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text("GRILLE RYTHMIQUE & CALAGE TEMPS 1", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SmallActionChip("BPM ÷2", Modifier.weight(1f)) { onScaleSourceBpm(0.5f) }
                    SmallActionChip("BPM ×2", Modifier.weight(1f)) { onScaleSourceBpm(2.0f) }
                    SmallActionChip(
                        label = "TEMPS +1 (${p.gridBeatShift + 1}/4)",
                        modifier = Modifier.weight(1.4f),
                        accent = if (p.gridBeatShift > 0) PeakAmber else CrispWhite
                    ) {
                        onUpdateParams { it.copy(gridBeatShift = (it.gridBeatShift + 1) % 4) }
                    }
                }

                Spacer(Modifier.height(6.dp))
                StudioSlider(
                    label = "MICRO-CALAGE GRILLE (PHASE)",
                    valueText = "${if (p.gridPhaseNudgeMs >= 0) "+" else ""}${p.gridPhaseNudgeMs.roundToInt()} ms",
                    value = p.gridPhaseNudgeMs,
                    range = -250f..250f,
                    accent = PeakAmber,
                    onChange = { v -> onUpdateParams { it.copy(gridPhaseNudgeMs = (v / 5f).roundToInt() * 5f) } }
                )
            }
        }

        // RACK 4: 12-Pad Live Kick Audition Rack (3 rows x 4 pads)
        RackCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("RACK D'ÉCOUTE 12 KICKS (DIRECT)", style = MaterialTheme.typography.labelLarge, color = PeakAmber)
                Text("TAP POUR TESTER", style = MaterialTheme.typography.labelSmall, color = SteelGray)
            }
            Spacer(Modifier.height(8.dp))

            KickType.entries.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { kick ->
                        val active = state.auditioningKick == kick
                        val accent = Color(kick.accentHex)
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = if (active) accent.copy(alpha = 0.25f) else RackMetal,
                            border = BorderStroke(1.dp, if (active) accent else RackBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onAuditionKick(kick) }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = kick.code,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = accent
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = kick.displayName.substringBefore(' '),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CrispWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // RACK 5: Expandable Custom Kick Design, Arrangement Theme & FX Rack
        RackCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleProTweaks() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, contentDescription = null, tint = themeAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("CUSTOMISATION KICKS, THÈME & FX", style = MaterialTheme.typography.labelLarge, color = CrispWhite)
                }
                Text(
                    text = if (state.showProTweaks) "MASQUER ▲" else "OUVRIR ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = themeAccent
                )
            }

            if (state.showProTweaks) {
                Spacer(Modifier.height(12.dp))
                val p = state.params

                Text("THÈME D'ARRANGEMENT (PERCUSSIONS & SYNTHÉS)", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                Spacer(Modifier.height(5.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RemixGenreTheme.entries.forEach { gt ->
                        val sel = p.genreTheme == gt
                        val gAccent = Color(gt.accentHex)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (sel) gAccent.copy(alpha = 0.24f) else RackMetal,
                            border = BorderStroke(1.dp, if (sel) gAccent else RackBorder),
                            modifier = Modifier.clickable { onUpdateParams { it.copy(genreTheme = gt) } }
                        ) {
                            Text(
                                text = gt.shortLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sel) gAccent else CrispWhite,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                StudioSlider(
                    label = "TEMPO CIBLE (TECHNO 124 → PIEP 240 BPM)",
                    valueText = "${p.targetBpm.roundToInt()} BPM",
                    value = p.targetBpm,
                    range = 124f..240f,
                    accent = themeAccent,
                    onChange = { v -> onUpdateParams { it.copy(targetBpm = v.roundToInt().toFloat()) } }
                )

                KickSelectorRow(
                    title = "DROP 1 KICK (12 SIGNATURES)",
                    selected = p.drop1Kick,
                    onSelect = { k -> onUpdateParams { it.copy(drop1Kick = k) } }
                )
                Spacer(Modifier.height(6.dp))
                KickSelectorRow(
                    title = "DROP 2 KICK-SWITCH (12 SIGNATURES)",
                    selected = p.drop2Kick,
                    onSelect = { k -> onUpdateParams { it.copy(drop2Kick = k) } }
                )
                Spacer(Modifier.height(8.dp))

                StudioSlider(
                    label = "DRIVE / CHARACTER KICK (HAUT)",
                    valueText = "${(p.drive * 100).roundToInt()}%",
                    value = p.drive,
                    range = 0.2f..1f,
                    accent = themeAccent,
                    onChange = { v -> onUpdateParams { it.copy(drive = v) } }
                )
                StudioSlider(
                    label = "AGRESSIVITÉ ZAAG / PIEP / 303",
                    valueText = "${(p.zaagAggression * 100).roundToInt()}%",
                    value = p.zaagAggression,
                    range = 0.1f..1f,
                    accent = themeAccent,
                    onChange = { v -> onUpdateParams { it.copy(zaagAggression = v) } }
                )
                StudioSlider(
                    label = "PITCH VOCAL (0 = VOIX NATURELLE)",
                    valueText = if (p.vocalPitchSemitones >= 0) "+${p.vocalPitchSemitones} st" else "${p.vocalPitchSemitones} st",
                    value = p.vocalPitchSemitones.toFloat(),
                    range = -5f..7f,
                    accent = themeAccent,
                    onChange = { v -> onUpdateParams { it.copy(vocalPitchSemitones = v.roundToInt()) } }
                )
                StudioSlider(
                    label = "STUTTER / VOCAL CHOPS",
                    valueText = "${(p.stutterAmount * 100).roundToInt()}%",
                    value = p.stutterAmount,
                    range = 0f..1f,
                    accent = themeAccent,
                    onChange = { v -> onUpdateParams { it.copy(stutterAmount = v) } }
                )

                ToggleRow("FAKE-DROP (1 mesure avant Drop 1)", p.enableFakeDrop, themeAccent) { on ->
                    onUpdateParams { it.copy(enableFakeDrop = on) }
                }
                ToggleRow("SCREECHES & LASERS INDUSTRIELS", p.enableScreeches, themeAccent) { on ->
                    onUpdateParams { it.copy(enableScreeches = on) }
                }
                ToggleRow("MÉLODIE KICK EN CLÉ (FRENCHCORE / RAW)", p.enablePitchKickMelody, themeAccent) { on ->
                    onUpdateParams { it.copy(enablePitchKickMelody = on) }
                }
                ToggleRow("TRIPLET KICK ROLLS", p.enableTripletRolls, themeAccent) { on ->
                    onUpdateParams { it.copy(enableTripletRolls = on) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("INTERVALLE KICK-SWITCH", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4, 8, 16).forEach { bars ->
                            val sel = p.kickSwitchBars == bars
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (sel) themeAccent else RackMetal,
                                border = BorderStroke(1.dp, if (sel) themeAccent else RackBorder),
                                modifier = Modifier.clickable { onUpdateParams { it.copy(kickSwitchBars = bars) } }
                            ) {
                                Text(
                                    text = "$bars MES.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sel && p.genreTheme == RemixGenreTheme.TECHNO_CLUB) RackObsidian else CrispWhite,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenSavePresetDialog,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, PeakAmber),
                        modifier = Modifier.weight(0.45f)
                    ) {
                        Text("SAUVER PERSO", style = MaterialTheme.typography.labelSmall, color = PeakAmber)
                    }
                    Button(
                        onClick = onRenderRemix,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = CrispWhite),
                        modifier = Modifier.weight(0.55f)
                    ) {
                        Text("APPLIQUER & REMIXER", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Export Bar
        RackCard {
            Text("EXPORT HORS-LIGNE → Music/ZaagForge/", style = MaterialTheme.typography.labelLarge, color = MeterGreen)
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Export streaming zéro OOM · Visible dans ton lecteur musique et StrikeCam Arcade.",
                style = MaterialTheme.typography.bodySmall,
                color = SteelGray
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onExport(ExportFormat.M4A) },
                    enabled = state.remixResult != null,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MeterGreen, contentColor = RackObsidian),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("EXPORT M4A 256K", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = { onExport(ExportFormat.WAV) },
                    enabled = state.remixResult != null,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, PeakAmber),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("EXPORT WAV 16B", style = MaterialTheme.typography.labelLarge, color = PeakAmber)
                }
            }
        }
    }
}

@Composable
private fun SaveCustomPresetDialog(
    themeAccent: Color,
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var presetName by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RackCharcoal,
        titleContentColor = CrispWhite,
        textContentColor = SteelGray,
        title = { Text("Sauvegarder mon Preset Perso", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enregistre tes réglages actuels (thème, BPM, kicks, voix épiques, synthés 303/Euphoric, percussions 909) pour les réutiliser en 1 clic.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it.take(28) },
                    placeholder = { Text("Ex : Ma Hard Techno Perso") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = themeAccent,
                        unfocusedBorderColor = RackBorder,
                        focusedTextColor = CrispWhite,
                        unfocusedTextColor = CrispWhite
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(presetName) },
                colors = ButtonDefaults.buttonColors(containerColor = MeterGreen, contentColor = RackObsidian)
            ) {
                Text("SAUVEGARDER", style = MaterialTheme.typography.labelMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = SteelGray)
            }
        }
    )
}

@Composable
private fun BarNudgeRow(
    label: String,
    nudgeBars: Int,
    customActive: Boolean,
    onNudge: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = SteelGray)
            Text(
                text = (if (customActive) "MANUEL " else "AUTO ") +
                    if (nudgeBars == 0) "(0 mes.)" else "(${if (nudgeBars > 0) "+" else ""}$nudgeBars mes.)",
                style = MaterialTheme.typography.labelSmall,
                color = if (customActive || nudgeBars != 0) PeakAmber else CrispWhite
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallNudgePill("-4") { onNudge(-4) }
            SmallNudgePill("-1") { onNudge(-1) }
            SmallNudgePill("+1") { onNudge(1) }
            SmallNudgePill("+4") { onNudge(4) }
        }
    }
}

@Composable
private fun SmallNudgePill(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = RackMetal,
        border = BorderStroke(1.dp, RackBorder),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = CrispWhite,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SmallActionChip(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = CrispWhite,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = RackMetal,
        border = BorderStroke(1.dp, RackBorder),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun LibraryScreen(
    state: ZaagForgeUiState,
    themeAccent: Color,
    hasAudioPermission: Boolean,
    onRequestPermission: () -> Unit,
    onImportSaf: () -> Unit,
    onOpenLinkDialog: () -> Unit,
    onSelectTrack: (AudioTrackItem) -> Unit,
    onDeezerQueryChange: (String) -> Unit,
    onSearchDeezer: () -> Unit,
    onSelectDeezerTrack: (DeezerSearchTrack) -> Unit,
    onDismissResolved: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("BIBLIOTHÈQUE & STREAMING", style = MaterialTheme.typography.headlineMedium, color = CrispWhite)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Importe tes MP3/WAV/FLAC complets (jusqu'à 8 min sans OOM) ou teste via aperçu 30 s Deezer & liens Spotify / SoundCloud.",
                style = MaterialTheme.typography.bodySmall,
                color = SteelGray
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportSaf,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = themeAccent,
                        contentColor = if (state.params.genreTheme == RemixGenreTheme.TECHNO_CLUB) RackObsidian else CrispWhite
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("IMPORTER MP3", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onOpenLinkDialog,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, SignalOrange),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null, tint = SignalOrange, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("LIEN SPOTIFY/SC", style = MaterialTheme.typography.labelMedium, color = SignalOrange)
                }
            }
        }

        state.resolvedLink?.let { res ->
            item {
                ResolvedLinkCard(
                    resolved = res,
                    onUseLocal = { local ->
                        onDismissResolved()
                        onSelectTrack(local)
                    },
                    onUseDeezer = { dz ->
                        onDismissResolved()
                        onSelectDeezerTrack(dz)
                    },
                    onDismiss = onDismissResolved
                )
            }
        }

        item {
            RackCard {
                Text("DEEZER — RECHERCHE & REMIX APERÇU 30 S", style = MaterialTheme.typography.labelLarge, color = PeakAmber)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.deezerQuery,
                        onValueChange = onDeezerQueryChange,
                        placeholder = { Text("Artiste ou titre (ex. David Guetta, Ninho...)", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PeakAmber,
                            unfocusedBorderColor = RackBorder,
                            focusedTextColor = CrispWhite,
                            unfocusedTextColor = CrispWhite
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onSearchDeezer,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PeakAmber, contentColor = RackObsidian),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        if (state.isSearchingDeezer) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = RackObsidian, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = "Rechercher")
                        }
                    }
                }
            }
        }

        if (state.deezerResults.isNotEmpty()) {
            items(state.deezerResults, key = { "dz_${it.id}" }) { dz ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = RackCharcoal,
                    border = BorderStroke(1.dp, RackBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectDeezerTrack(dz) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(dz.title, style = MaterialTheme.typography.titleSmall, color = CrispWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${dz.artist} · Aperçu officiel 30 s", style = MaterialTheme.typography.bodySmall, color = SteelGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Surface(shape = RoundedCornerShape(5.dp), color = PeakAmber.copy(alpha = 0.2f), border = BorderStroke(1.dp, PeakAmber)) {
                            Text("REMIX 30S", style = MaterialTheme.typography.labelSmall, color = PeakAmber, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }

        item {
            Text("PISTES DÉMO INTÉGRÉES (TEST INSTANTANÉ)", style = MaterialTheme.typography.labelLarge, color = SignalOrange)
        }
        items(LibraryAndStreamingRepository.builtInDemos, key = { it.id }) { demo ->
            TrackRowCard(track = demo, badge = "DÉMO C++", badgeColor = SignalOrange) {
                onSelectTrack(demo)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MES MP3 / WAV / FLAC LOCAUX (${state.localTracks.size})", style = MaterialTheme.typography.labelLarge, color = CrispWhite)
                if (!hasAudioPermission) {
                    TextButton(onClick = onRequestPermission) {
                        Text("AUTORISER", style = MaterialTheme.typography.labelSmall, color = HazardRed)
                    }
                }
            }
        }

        if (state.localTracks.isEmpty()) {
            item {
                RackCard {
                    Text(
                        text = if (hasAudioPermission) {
                            "Aucun fichier audio local détecté dans Music/ ou Download/. Utilise « IMPORTER MP3 » ci-dessus."
                        } else {
                            "Autorise l'accès audio local pour lister automatiquement tous tes MP3, ou clique sur « IMPORTER MP3 »."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelGray
                    )
                }
            }
        } else {
            items(state.localTracks, key = { it.id }) { track ->
                TrackRowCard(track = track, badge = formatDuration(track.durationMs), badgeColor = MeterGreen) {
                    onSelectTrack(track)
                }
            }
        }
    }
}

@Composable
private fun ExportsScreen(
    exports: List<ExportedRemix>,
    onRefresh: () -> Unit,
    onPlayExport: (ExportedRemix) -> Unit,
    onShareExport: (ExportedRemix) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("EXPORTS ZAAGFORGE", style = MaterialTheme.typography.headlineMedium, color = CrispWhite)
                    Text("Dossier Music/ZaagForge/ · Synchro StrikeCam Arcade", style = MaterialTheme.typography.labelSmall, color = MeterGreen)
                }
                TextButton(onClick = onRefresh) {
                    Text("ACTUALISER", style = MaterialTheme.typography.labelSmall, color = SignalOrange)
                }
            }
        }

        if (exports.isEmpty()) {
            item {
                RackCard {
                    Text(
                        text = "Aucun remix exporté pour l'instant. Lance AUTO REMIX dans l'onglet STUDIO puis clique sur EXPORT M4A ou WAV.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelGray
                    )
                }
            }
        } else {
            items(exports, key = { it.id }) { exp ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = RackCharcoal,
                    border = BorderStroke(1.dp, RackBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onPlayExport(exp) }
                        ) {
                            Text(
                                text = exp.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = CrispWhite,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(3.dp))
                            val mb = exp.sizeBytes / (1024f * 1024f)
                            Text(
                                text = "${exp.format.badge} · ${String.format(Locale.US, "%.1f Mo", mb)} · Prêt dans StrikeCam",
                                style = MaterialTheme.typography.labelSmall,
                                color = MeterGreen
                            )
                        }
                        IconButton(onClick = { onPlayExport(exp) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Lire", tint = CrispWhite)
                        }
                        IconButton(onClick = { onShareExport(exp) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Partager", tint = SignalOrange)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionWaveformView(
    peaks: FloatArray,
    sections: List<SectionMarker>,
    progress: Float,
    isRemixActive: Boolean,
    remixAccent: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = RackObsidian,
        border = BorderStroke(1.dp, RackBorder),
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                if (size.width > 0) {
                    onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 0.999f))
                }
            }
        }
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(vertical = 6.dp, horizontal = 4.dp)) {
            val w = size.width
            val h = size.height
            val midY = h * 0.5f

            for (sec in sections) {
                val x0 = sec.startFrac.coerceIn(0f, 1f) * w
                val x1 = sec.endFrac.coerceIn(0f, 1f) * w
                val c = sectionColor(sec.kind, remixAccent)
                drawRect(
                    color = c.copy(alpha = 0.12f),
                    topLeft = Offset(x0, 0f),
                    size = Size((x1 - x0).coerceAtLeast(1f), h)
                )
                drawLine(
                    color = c.copy(alpha = 0.72f),
                    start = Offset(x0, 0f),
                    end = Offset(x0, h),
                    strokeWidth = if (sec.kind == SectionKind.DROP_1 || sec.kind == SectionKind.DROP_2) 2.5f else 1.5f
                )
            }

            drawLine(color = RackBorder, start = Offset(0f, midY), end = Offset(w, midY), strokeWidth = 1f)

            if (peaks.isNotEmpty()) {
                val barCount = 120
                val barW = (w / barCount) * 0.72f
                val step = peaks.size.toFloat() / barCount
                for (i in 0 until barCount) {
                    val idx = (i * step).toInt().coerceIn(0, peaks.lastIndex)
                    val amp = peaks[idx].coerceIn(0.03f, 1f)
                    val barH = amp * h * 0.88f
                    val x = (i.toFloat() / barCount) * w
                    val frac = i.toFloat() / barCount
                    val activeColor = if (isRemixActive) remixAccent else MeterGreen
                    val barColor = if (frac <= progress) activeColor else activeColor.copy(alpha = 0.36f)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, midY - barH * 0.5f),
                        size = Size(barW.coerceAtLeast(1.5f), barH),
                        cornerRadius = CornerRadius(1.5f, 1.5f)
                    )
                }
            }

            val px = progress.coerceIn(0f, 1f) * w
            drawLine(color = CrispWhite, start = Offset(px, 0f), end = Offset(px, h), strokeWidth = 2.5f)
        }
    }
}

@Composable
private fun StereoVuMeter(meter: MeterReading) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        VuBarRow("L", meter.leftPeak, meter.leftPeakHold)
        VuBarRow("R", meter.rightPeak, meter.rightPeakHold)
    }
}

@Composable
private fun VuBarRow(channel: String, level: Float, peakHold: Float) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(channel, style = MaterialTheme.typography.labelSmall, color = SteelDim, modifier = Modifier.width(12.dp))
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        ) {
            val segments = 28
            val segW = size.width / segments
            val gap = 2f
            val litSegs = (level.coerceIn(0f, 1f) * segments).roundToInt()
            val holdIdx = (peakHold.coerceIn(0f, 1f) * (segments - 1)).roundToInt()
            for (i in 0 until segments) {
                val frac = i.toFloat() / segments
                val segColor = when {
                    frac >= 0.85f -> HazardRed
                    frac >= 0.65f -> PeakAmber
                    else -> MeterGreen
                }
                val isLit = i < litSegs || (peakHold > 0.05f && i == holdIdx)
                drawRect(
                    color = if (isLit) segColor else RackMetal,
                    topLeft = Offset(i * segW, 0f),
                    size = Size((segW - gap).coerceAtLeast(1f), size.height)
                )
            }
        }
    }
}

@Composable
private fun KickSelectorRow(title: String, selected: KickType, onSelect: (KickType) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall, color = SteelGray)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KickType.entries.forEach { k ->
                val sel = k == selected
                val accent = Color(k.accentHex)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (sel) accent.copy(alpha = 0.25f) else RackMetal,
                    border = BorderStroke(1.dp, if (sel) accent else RackBorder),
                    modifier = Modifier.clickable { onSelect(k) }
                ) {
                    Text(
                        text = k.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sel) accent else CrispWhite,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color = HazardRed,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = SteelGray)
            Text(valueText, style = MaterialTheme.typography.labelSmall, color = CrispWhite)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = RackMetal
            )
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    accent: Color = HazardRed,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = CrispWhite, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CrispWhite,
                checkedTrackColor = accent,
                uncheckedThumbColor = SteelGray,
                uncheckedTrackColor = RackMetal
            )
        )
    }
}

@Composable
private fun ResolvedLinkCard(
    resolved: ResolvedStreamingTrack,
    onUseLocal: (AudioTrackItem) -> Unit,
    onUseDeezer: (DeezerSearchTrack) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = RackCharcoal,
        border = BorderStroke(1.dp, SignalOrange),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LIEN ${resolved.provider.name} RÉSOLU", style = MaterialTheme.typography.labelLarge, color = SignalOrange)
                Text("FERMER", style = MaterialTheme.typography.labelSmall, color = SteelGray, modifier = Modifier.clickable { onDismiss() })
            }
            Text("${resolved.title} — ${resolved.artist}", style = MaterialTheme.typography.titleSmall, color = CrispWhite)
            resolved.matchedLocalTrack?.let { local ->
                Button(
                    onClick = { onUseLocal(local) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MeterGreen, contentColor = RackObsidian),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("MP3 LOCAL TROUVÉ : REMIXER EN ENTIER", style = MaterialTheme.typography.labelMedium)
                }
            }
            resolved.deezerFallback?.let { dz ->
                OutlinedButton(
                    onClick = { onUseDeezer(dz) },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, PeakAmber),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("TESTER EN REMIX SUR APERÇU DEEZER 30 S", style = MaterialTheme.typography.labelMedium, color = PeakAmber)
                }
            }
        }
    }
}

@Composable
private fun PasteStreamingLinkDialog(
    themeAccent: Color,
    onDismiss: () -> Unit,
    onResolve: (String) -> Unit
) {
    var linkText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RackCharcoal,
        titleContentColor = CrispWhite,
        textContentColor = SteelGray,
        title = { Text("Lien Spotify / SoundCloud / Deezer", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Colle un lien partagé : ZAAGFORGE identifie le morceau, cherche ton MP3 local correspondant ou charge l'aperçu officiel 30 s Deezer pour le remixer.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    placeholder = { Text("https://open.spotify.com/track/...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = themeAccent,
                        unfocusedBorderColor = RackBorder,
                        focusedTextColor = CrispWhite,
                        unfocusedTextColor = CrispWhite
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onResolve(linkText) },
                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = CrispWhite)
            ) {
                Text("RÉSOUDRE", style = MaterialTheme.typography.labelMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler", color = SteelGray)
            }
        }
    )
}

@Composable
private fun TrackRowCard(
    track: AudioTrackItem,
    badge: String,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = RackCharcoal,
        border = BorderStroke(1.dp, RackBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AudioFile, contentDescription = null, tint = badgeColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, color = CrispWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = SteelGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(5.dp), color = badgeColor.copy(alpha = 0.16f), border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.7f))) {
                Text(badge, style = MaterialTheme.typography.labelSmall, color = badgeColor, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun RackCard(
    borderColor: Color = RackBorder,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = RackCharcoal,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@Composable
private fun RackMiniButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color = SignalOrange,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = RackMetal,
        border = BorderStroke(1.dp, RackBorder),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = label, tint = accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = CrispWhite)
        }
    }
}

@Composable
private fun TelemetryCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = CrispWhite
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = RackMetal,
        border = BorderStroke(1.dp, RackBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = SteelGray, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

@Composable
private fun BannerCard(text: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = CrispWhite,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

private fun sectionColor(kind: SectionKind, dropAccent: Color = HazardRed): Color = when (kind) {
    SectionKind.INTRO -> SteelGray
    SectionKind.VOCAL_BREAK, SectionKind.MID_BREAK -> MeterGreen
    SectionKind.BUILDUP -> PeakAmber
    SectionKind.FAKE_DROP -> SignalOrange
    SectionKind.DROP_1, SectionKind.DROP_2 -> dropAccent
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "AUDIO"
    val totalSec = (ms / 1000L).toInt()
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}

private fun formatSec(sec: Float): String {
    val total = sec.coerceAtLeast(0f).roundToInt()
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
