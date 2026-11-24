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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zaagforge.studio.model.AudioTrackItem
import com.zaagforge.studio.model.DeezerSearchTrack
import com.zaagforge.studio.model.ExportFormat
import com.zaagforge.studio.model.ExportedRemix
import com.zaagforge.studio.model.KickType
import com.zaagforge.studio.model.MeterReading
import com.zaagforge.studio.model.RemixParams
import com.zaagforge.studio.model.RemixPreset
import com.zaagforge.studio.model.ResolvedStreamingTrack
import com.zaagforge.studio.model.SectionKind
import com.zaagforge.studio.model.SectionMarker
import com.zaagforge.studio.model.StudioPresets
import com.zaagforge.studio.music.LibraryAndStreamingRepository
import com.zaagforge.studio.ui.theme.CrispWhite
import com.zaagforge.studio.ui.theme.HazardRed
import com.zaagforge.studio.ui.theme.HazardRedDim
import com.zaagforge.studio.ui.theme.MeterGreen
import com.zaagforge.studio.ui.theme.MeterGreenDim
import com.zaagforge.studio.ui.theme.PeakAmber
import com.zaagforge.studio.ui.theme.RackBorder
import com.zaagforge.studio.ui.theme.RackCharcoal
import com.zaagforge.studio.ui.theme.RackMetal
import com.zaagforge.studio.ui.theme.RackObsidian
import com.zaagforge.studio.ui.theme.SignalOrange
import com.zaagforge.studio.ui.theme.SignalOrangeDim
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
                            selectedIconColor = HazardRed,
                            selectedTextColor = HazardRed,
                            unselectedIconColor = SteelGray,
                            unselectedTextColor = SteelGray,
                            indicatorColor = HazardRedDim
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
                    isPlaying = isPlaying,
                    progress = progress,
                    isRemixActive = isRemixActive,
                    meter = meter,
                    onImportMp3 = { audioPicker.launch(arrayOf("audio/*")) },
                    onOpenLinkDialog = { showLinkDialog = true },
                    onSelectPreset = { viewModel.selectPreset(it, renderNow = true) },
                    onRenderRemix = { viewModel.renderCurrentRemix() },
                    onTogglePlay = { viewModel.togglePlayPause() },
                    onToggleAb = { viewModel.toggleAbSource() },
                    onSeek = { viewModel.seekToFraction(it) },
                    onJumpSection = { viewModel.jumpToSection(it) },
                    onAuditionKick = { viewModel.auditionKick(it) },
                    onToggleProTweaks = { viewModel.toggleProTweaks() },
                    onUpdateParams = { viewModel.updateParams(it) },
                    onExport = { viewModel.exportCurrentRemix(it) }
                )

                StudioTab.LIBRARY -> LibraryScreen(
                    state = state,
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

            // Blocking DSP render HUD overlay
            if (state.isBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(RackObsidian.copy(alpha = 0.84f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = RackCharcoal,
                        border = BorderStroke(1.dp, HazardRed)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = HazardRed, strokeWidth = 3.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "MOTEUR C++20 ZAAGFORGE",
                                style = MaterialTheme.typography.labelMedium,
                                color = HazardRed
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = state.busyMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CrispWhite
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLinkDialog) {
        PasteStreamingLinkDialog(
            onDismiss = { showLinkDialog = false },
            onResolve = { url ->
                showLinkDialog = false
                viewModel.handleIncomingShareLink(url)
            }
        )
    }
}

@Composable
private fun StudioScreen(
    state: ZaagForgeUiState,
    isPlaying: Boolean,
    progress: Float,
    isRemixActive: Boolean,
    meter: MeterReading,
    onImportMp3: () -> Unit,
    onOpenLinkDialog: () -> Unit,
    onSelectPreset: (RemixPreset) -> Unit,
    onRenderRemix: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleAb: () -> Unit,
    onSeek: (Float) -> Unit,
    onJumpSection: (SectionMarker) -> Unit,
    onAuditionKick: (KickType) -> Unit,
    onToggleProTweaks: () -> Unit,
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
        // Hardware Header Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ZAAGFORGE // DSP",
                    style = MaterialTheme.typography.headlineMedium,
                    color = CrispWhite
                )
                Text(
                    text = "UPTEMPO • PIEP • KRACH • ZAAG STUDIO",
                    style = MaterialTheme.typography.labelSmall,
                    color = SignalOrange
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RackMiniButton(icon = Icons.Filled.FolderOpen, label = "MP3", onClick = onImportMp3)
                RackMiniButton(icon = Icons.Filled.Link, label = "LIEN", onClick = onOpenLinkDialog)
            }
        }

        // Alerts / Notices
        state.errorBanner?.let { err ->
            BannerCard(text = err, accent = HazardRed)
        }
        state.lastExportNotice?.let { notice ->
            BannerCard(text = notice, accent = MeterGreen)
        }

        // Deck Telemetry + Interactive Waveform + A/B Transport
        RackCard {
            val track = state.loadedTrack
            val analysis = state.analysis
            val remix = state.remixResult

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
                        text = track?.artist ?: "Importe un MP3 ou cherche sur Deezer",
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                // Instant A/B Original vs Remix Toggle
                val hasRemix = remix != null
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isRemixActive && hasRemix) HazardRed else RackMetal,
                    border = BorderStroke(1.dp, if (isRemixActive && hasRemix) HazardRed else RackBorder),
                    modifier = Modifier.clickable(enabled = hasRemix) { onToggleAb() }
                ) {
                    Text(
                        text = if (isRemixActive && hasRemix) "REMIX ●" else "ORIGINAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = CrispWhite,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 4-Cell Tabular Readout (SRC BPM / TARGET BPM / KEY / LOUDNESS)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TelemetryCell(
                    label = "SRC BPM",
                    value = analysis?.bpm?.roundToInt()?.toString() ?: "--",
                    modifier = Modifier.weight(1f)
                )
                TelemetryCell(
                    label = "REMIX",
                    value = "${(remix?.effectiveTargetBpm ?: state.params.targetBpm).roundToInt()}",
                    valueColor = HazardRed,
                    modifier = Modifier.weight(1f)
                )
                TelemetryCell(
                    label = "CLÉ",
                    value = remix?.keyName ?: analysis?.keyName ?: "--",
                    valueColor = PeakAmber,
                    modifier = Modifier.weight(1f)
                )
                TelemetryCell(
                    label = "RMS",
                    value = remix?.let { String.format(Locale.US, "%.1fdB", it.rmsDb) } ?: "--",
                    valueColor = MeterGreen,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Color-Coded Section Interactive Waveform + Scrubber
            SectionWaveformView(
                peaks = state.waveformPeaks,
                sections = state.sectionMarkers,
                progress = progress,
                isRemixActive = isRemixActive && remix != null,
                onSeek = onSeek,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(82.dp)
            )

            Spacer(Modifier.height(8.dp))

            // Live Stereo Peak VU Meter
            StereoVuMeter(meter = meter)

            Spacer(Modifier.height(8.dp))

            // Section Jump Chips (Intro / Build / Fake-Drop / Drop 1 / Drop 2)
            if (state.sectionMarkers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    state.sectionMarkers.forEach { marker ->
                        val chipColor = sectionColor(marker.kind)
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

            // Main Transport Row: PLAY/PAUSE + 1-TAP AUTO REMIX
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

                Button(
                    onClick = onRenderRemix,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HazardRed, contentColor = CrispWhite),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier.weight(0.58f)
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("AUTO REMIX C++", style = MaterialTheme.typography.labelLarge)
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

        // 8 Iconic Hardcore Presets Grid (1-Tap Instant Switch & Render)
        RackCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("PRESETS PRO (8 STYLES)", style = MaterialTheme.typography.labelLarge, color = SignalOrange)
                Text("1-TAP REMIX", style = MaterialTheme.typography.labelSmall, color = SteelGray)
            }
            Spacer(Modifier.height(8.dp))

            StudioPresets.all.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { preset ->
                        val selected = preset.id == state.selectedPreset.id
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) HazardRedDim else RackMetal,
                            border = BorderStroke(1.dp, if (selected) HazardRed else RackBorder),
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
                                        color = if (selected) HazardRed else PeakAmber
                                    )
                                    Text(
                                        text = "${preset.params.targetBpm.roundToInt()}",
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
                }
            }
        }

        // 8-Pad Live Kick Audition Rack (Tap any kick to hear 1-bar C++ synthesis instantly!)
        RackCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("RACK D'ÉCOUTE KICKS (DIRECT)", style = MaterialTheme.typography.labelLarge, color = PeakAmber)
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
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
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

        // Expandable Pro Studio Tweak Rack
        RackCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleProTweaks() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, contentDescription = null, tint = SignalOrange, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("RÉGLAGES STUDIO AVANCÉS", style = MaterialTheme.typography.labelLarge, color = CrispWhite)
                }
                Text(
                    text = if (state.showProTweaks) "MASQUER ▲" else "OUVRIR ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = SignalOrange
                )
            }

            if (state.showProTweaks) {
                Spacer(Modifier.height(12.dp))
                val p = state.params

                StudioSlider(
                    label = "TEMPO CIBLE",
                    valueText = "${p.targetBpm.roundToInt()} BPM",
                    value = p.targetBpm,
                    range = 150f..240f,
                    onChange = { v -> onUpdateParams { it.copy(targetBpm = v.roundToInt().toFloat()) } }
                )

                KickSelectorRow(
                    title = "DROP 1 KICK",
                    selected = p.drop1Kick,
                    onSelect = { k -> onUpdateParams { it.copy(drop1Kick = k) } }
                )
                Spacer(Modifier.height(6.dp))
                KickSelectorRow(
                    title = "DROP 2 KICK-SWITCH",
                    selected = p.drop2Kick,
                    onSelect = { k -> onUpdateParams { it.copy(drop2Kick = k) } }
                )
                Spacer(Modifier.height(8.dp))

                StudioSlider(
                    label = "DRIVE / DISTORSION",
                    valueText = "${(p.drive * 100).roundToInt()}%",
                    value = p.drive,
                    range = 0.2f..1f,
                    onChange = { v -> onUpdateParams { it.copy(drive = v) } }
                )
                StudioSlider(
                    label = "AGRESSIVITÉ ZAAG / PIEP",
                    valueText = "${(p.zaagAggression * 100).roundToInt()}%",
                    value = p.zaagAggression,
                    range = 0.1f..1f,
                    onChange = { v -> onUpdateParams { it.copy(zaagAggression = v) } }
                )
                StudioSlider(
                    label = "BALANCE PISTE ORIGINALE",
                    valueText = "${(p.originalMix * 100).roundToInt()}%",
                    value = p.originalMix,
                    range = 0.2f..1f,
                    onChange = { v -> onUpdateParams { it.copy(originalMix = v) } }
                )
                StudioSlider(
                    label = "PITCH VOCAL (CHIPMUNK UOK)",
                    valueText = if (p.vocalPitchSemitones >= 0) "+${p.vocalPitchSemitones} st" else "${p.vocalPitchSemitones} st",
                    value = p.vocalPitchSemitones.toFloat(),
                    range = -5f..7f,
                    onChange = { v -> onUpdateParams { it.copy(vocalPitchSemitones = v.roundToInt()) } }
                )
                StudioSlider(
                    label = "STUTTER / VOCAL CHOPS",
                    valueText = "${(p.stutterAmount * 100).roundToInt()}%",
                    value = p.stutterAmount,
                    range = 0f..1f,
                    onChange = { v -> onUpdateParams { it.copy(stutterAmount = v) } }
                )

                ToggleRow("FAKE-DROP (2 temps tension)", p.enableFakeDrop) { on ->
                    onUpdateParams { it.copy(enableFakeDrop = on) }
                }
                ToggleRow("SCREECHES & LASERS INDUSTRIELS", p.enableScreeches) { on ->
                    onUpdateParams { it.copy(enableScreeches = on) }
                }
                ToggleRow("MÉLODIE KICK EN CLÉ", p.enablePitchKickMelody) { on ->
                    onUpdateParams { it.copy(enablePitchKickMelody = on) }
                }
                ToggleRow("TRIPLET KICK ROLLS", p.enableTripletRolls) { on ->
                    onUpdateParams { it.copy(enableTripletRolls = on) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("INTERVALLE KICK-SWITCH", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4, 8).forEach { bars ->
                            val sel = p.kickSwitchBars == bars
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (sel) HazardRed else RackMetal,
                                border = BorderStroke(1.dp, if (sel) HazardRed else RackBorder),
                                modifier = Modifier.clickable { onUpdateParams { it.copy(kickSwitchBars = bars) } }
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
                Button(
                    onClick = onRenderRemix,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = CrispWhite),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("APPLIQUER & RE-CALCULER LE REMIX", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Export Bar (M4A 256k + WAV 16-bit -> Music/ZaagForge/ -> Auto-visible in StrikeCam!)
        RackCard {
            Text("EXPORT HORS-LIGNE → Music/ZaagForge/", style = MaterialTheme.typography.labelLarge, color = MeterGreen)
            Spacer(Modifier.height(3.dp))
            Text(
                text = "Visible immédiatement dans ton lecteur musique et dans StrikeCam Arcade.",
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
private fun LibraryScreen(
    state: ZaagForgeUiState,
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
                text = "Importe tes MP3/WAV/FLAC complets ou teste n'importe quel titre via aperçu 30 s Deezer & liens Spotify / SoundCloud.",
                style = MaterialTheme.typography.bodySmall,
                color = SteelGray
            )
        }

        // Quick Actions Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportSaf,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HazardRed, contentColor = CrispWhite),
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

        // Resolved Share Link Card (if user pasted or shared from Spotify/SoundCloud/Deezer)
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

        // Deezer Instant 30s Preview Search Box
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

        // Built-in Starter Demos
        item {
            Text("PISTES DÉMO INTÉGRÉES (TEST INSTANTANÉ)", style = MaterialTheme.typography.labelLarge, color = SignalOrange)
        }
        items(LibraryAndStreamingRepository.builtInDemos, key = { it.id }) { demo ->
            TrackRowCard(track = demo, badge = "DÉMO C++", badgeColor = SignalOrange) {
                onSelectTrack(demo)
            }
        }

        // Local Device MP3 Library
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

            // Background section bands
            for (sec in sections) {
                val x0 = sec.startFrac.coerceIn(0f, 1f) * w
                val x1 = sec.endFrac.coerceIn(0f, 1f) * w
                val c = sectionColor(sec.kind)
                drawRect(
                    color = c.copy(alpha = 0.10f),
                    topLeft = Offset(x0, 0f),
                    size = Size((x1 - x0).coerceAtLeast(1f), h)
                )
                drawLine(
                    color = c.copy(alpha = 0.55f),
                    start = Offset(x0, 0f),
                    end = Offset(x0, h),
                    strokeWidth = 1.5f
                )
            }

            // Center zero line
            drawLine(color = RackBorder, start = Offset(0f, midY), end = Offset(w, midY), strokeWidth = 1f)

            // Waveform bars
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
                    val activeColor = if (isRemixActive) HazardRed else MeterGreen
                    val barColor = if (frac <= progress) activeColor else activeColor.copy(alpha = 0.36f)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, midY - barH * 0.5f),
                        size = Size(barW.coerceAtLeast(1.5f), barH),
                        cornerRadius = CornerRadius(1.5f, 1.5f)
                    )
                }
            }

            // Playhead needle
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
                thumbColor = HazardRed,
                activeTrackColor = HazardRed,
                inactiveTrackColor = RackMetal
            )
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = CrispWhite)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CrispWhite,
                checkedTrackColor = HazardRed,
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
private fun PasteStreamingLinkDialog(onDismiss: () -> Unit, onResolve: (String) -> Unit) {
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
                        focusedBorderColor = SignalOrange,
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
                colors = ButtonDefaults.buttonColors(containerColor = HazardRed, contentColor = CrispWhite)
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
private fun RackCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = RackCharcoal,
        border = BorderStroke(1.dp, RackBorder),
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
            Icon(icon, contentDescription = label, tint = SignalOrange, modifier = Modifier.size(15.dp))
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = SteelGray)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, color = valueColor, fontWeight = FontWeight.Black)
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

private fun sectionColor(kind: SectionKind): Color = when (kind) {
    SectionKind.INTRO -> SteelGray
    SectionKind.VOCAL_BREAK, SectionKind.MID_BREAK -> MeterGreen
    SectionKind.BUILDUP -> PeakAmber
    SectionKind.FAKE_DROP -> SignalOrange
    SectionKind.DROP_1, SectionKind.DROP_2 -> HazardRed
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "AUDIO"
    val totalSec = (ms / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%d:%02d", m, s)
}
