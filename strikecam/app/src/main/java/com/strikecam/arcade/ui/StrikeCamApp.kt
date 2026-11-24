package com.strikecam.arcade.ui

import android.Manifest
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SportsMma
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.strikecam.arcade.data.ArcadeRank
import com.strikecam.arcade.data.ArcadeTrack
import com.strikecam.arcade.data.Difficulty
import com.strikecam.arcade.data.DuelMatchState
import com.strikecam.arcade.data.GameMode
import com.strikecam.arcade.data.PlayerRoundSummary
import com.strikecam.arcade.data.StreamingPlatform
import com.strikecam.arcade.data.TrackSource
import com.strikecam.arcade.game.GameFrameSnapshot
import com.strikecam.arcade.ui.game.ArcadeCanvas
import com.strikecam.arcade.ui.theme.ArcadeBorder
import com.strikecam.arcade.ui.theme.ArcadeBorderBright
import com.strikecam.arcade.ui.theme.ArcadeSurface
import com.strikecam.arcade.ui.theme.ArcadeSurfaceElevated
import com.strikecam.arcade.ui.theme.CrispWhite
import com.strikecam.arcade.ui.theme.DodgeAmber
import com.strikecam.arcade.ui.theme.LeftCrimson
import com.strikecam.arcade.ui.theme.OledBlack
import com.strikecam.arcade.ui.theme.PerfectGreen
import com.strikecam.arcade.ui.theme.PowerGold
import com.strikecam.arcade.ui.theme.RightCyan
import com.strikecam.arcade.ui.theme.SteelDim
import com.strikecam.arcade.ui.theme.SteelMuted
import com.strikecam.arcade.vision.PoseLandmarkerHelper
import java.util.Locale
import java.util.concurrent.Executors

@Composable
fun StrikeCamApp(viewModel: StrikeCamViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) viewModel.launchSelectedTrack()
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) viewModel.toggleLiveMicCapture(true)
    }

    val audioReadPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.refreshLocalLibrary()
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            var name: String? = null
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
            }
            if (hasCameraPermission) {
                viewModel.importLocalAudioUri(uri, name)
            } else {
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    val requestPlay: () -> Unit = {
        if (hasCameraPermission) {
            viewModel.launchSelectedTrack()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = OledBlack) {
        when (state.screen) {
            ArcadeScreen.LOBBY -> LobbyScreen(
                state = state,
                onSelectTab = viewModel::selectTab,
                onSelectMode = viewModel::selectMode,
                onSelectDifficulty = viewModel::selectDifficulty,
                onSelectTrack = viewModel::selectTrack,
                onLaunchTrack = { t ->
                    viewModel.selectTrack(t)
                    requestPlay()
                },
                onLaunchSelected = requestPlay,
                onRefreshLocals = {
                    val readPerm = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        Manifest.permission.READ_MEDIA_AUDIO
                    } else {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                    if (ContextCompat.checkSelfPermission(context, readPerm) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.refreshLocalLibrary()
                    } else {
                        audioReadPermLauncher.launch(readPerm)
                    }
                },
                onImportAudioFile = {
                    audioPickerLauncher.launch(arrayOf("audio/*"))
                },
                onDeezerQueryChange = viewModel::updateDeezerQuery,
                onSearchDeezer = viewModel::searchDeezer,
                onStreamingLinkChange = viewModel::updateStreamingLinkInput,
                onResolveStreamingLink = viewModel::resolveStreamingLink,
                onOpenStreamingApp = viewModel::openExternalStreamingApp,
                onPlayPreviewFromResolved = { t ->
                    if (hasCameraPermission) viewModel.launchPreviewFromResolved(t)
                    else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                },
                onDismissBanner = viewModel::dismissBanner
            )

            ArcadeScreen.LOADING -> LoadingOverlayScreen(
                track = state.selectedTrack,
                message = state.loadingMessage,
                onCancel = viewModel::returnToLobby
            )

            ArcadeScreen.COUNTDOWN, ArcadeScreen.PLAYING -> {
                val frame by viewModel.gameFrame.collectAsState()
                GameplayScreen(
                    state = state,
                    frame = frame,
                    hasCameraPermission = hasCameraPermission,
                    onPoseFrame = viewModel::onPoseLandmarks,
                    onDelegateReady = viewModel::updateDelegateLabel,
                    onCycleBackdrop = viewModel::cycleBackdropMode,
                    onFlipCamera = viewModel::toggleCameraFacing,
                    onToggleMute = viewModel::toggleSfxMute,
                    onQuit = viewModel::returnToLobby,
                    onTapSync = viewModel::registerLiveTapSync,
                    onAdjustBpm = viewModel::adjustLiveBpm,
                    onNudgePhase = viewModel::nudgeLivePhase,
                    onToggleMic = {
                        if (hasMicPermission) viewModel.toggleLiveMicCapture(true)
                        else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onMediaPrev = viewModel::sendMediaPrev,
                    onMediaPlayPause = viewModel::sendMediaPlayPause,
                    onMediaNext = viewModel::sendMediaNext,
                    onOpenExternalApp = {
                        viewModel.openExternalStreamingApp(
                            state.selectedTrack.streamingPlatform,
                            state.selectedTrack.externalUrl
                        )
                    },
                    onFinishLiveSession = viewModel::finishLiveSyncSession
                )
            }

            ArcadeScreen.DUEL_HANDOFF -> DuelHandoffScreen(
                duelState = state.duelState,
                trackTitle = state.selectedTrack.title,
                onStartPlayer2 = viewModel::startDuelPlayer2,
                onQuit = viewModel::returnToLobby
            )

            ArcadeScreen.RESULTS -> ResultsScreen(
                track = state.selectedTrack,
                mode = state.selectedMode,
                difficulty = state.selectedDifficulty,
                summary = state.lastSummary,
                duelState = state.duelState,
                onReplay = requestPlay,
                onBackToLobby = viewModel::returnToLobby
            )
        }
    }
}

@Composable
private fun LobbyScreen(
    state: StrikeCamUiState,
    onSelectTab: (LobbyTab) -> Unit,
    onSelectMode: (GameMode) -> Unit,
    onSelectDifficulty: (Difficulty) -> Unit,
    onSelectTrack: (ArcadeTrack) -> Unit,
    onLaunchTrack: (ArcadeTrack) -> Unit,
    onLaunchSelected: () -> Unit,
    onRefreshLocals: () -> Unit,
    onImportAudioFile: () -> Unit,
    onDeezerQueryChange: (String) -> Unit,
    onSearchDeezer: () -> Unit,
    onStreamingLinkChange: (String) -> Unit,
    onResolveStreamingLink: () -> Unit,
    onOpenStreamingApp: (StreamingPlatform, String?) -> Unit,
    onPlayPreviewFromResolved: (ArcadeTrack) -> Unit,
    onDismissBanner: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Header + Compact Career Telemetry
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(LeftCrimson),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SportsMma,
                        contentDescription = null,
                        tint = CrispWhite,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "STRIKECAM ARCADE",
                        style = MaterialTheme.typography.titleLarge,
                        color = CrispWhite
                    )
                    Text(
                        text = "${state.careerStats.totalPunches} coups • ${state.careerStats.totalSessions} combats • Record ${state.careerStats.bestScore}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelMuted
                    )
                }
            }
            state.careerStats.bestRank?.let { bestRank ->
                RankBadgeChip(rank = bestRank)
            }
        }

        // Mode Selector Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GameMode.entries.forEach { mode ->
                val selected = mode == state.selectedMode
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectMode(mode) },
                    color = if (selected) LeftCrimson.copy(alpha = 0.22f) else ArcadeSurface,
                    border = BorderStroke(1.dp, if (selected) LeftCrimson else ArcadeBorder),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = mode.labelFr,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) CrispWhite else SteelMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Difficulty Selector Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Difficulty.entries.forEach { diff ->
                val selected = diff == state.selectedDifficulty
                val accent = when (diff) {
                    Difficulty.CHILL -> PerfectGreen
                    Difficulty.PRO -> RightCyan
                    Difficulty.EXPERT -> DodgeAmber
                    Difficulty.UPTEMPO_INSANE -> LeftCrimson
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectDifficulty(diff) },
                    color = if (selected) accent.copy(alpha = 0.22f) else ArcadeSurface,
                    border = BorderStroke(1.dp, if (selected) accent else ArcadeBorder),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = diff.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) accent else SteelMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 7.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 3 Music Source Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ArcadeSurface)
                .border(1.dp, ArcadeBorder, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LobbyTab.entries.forEach { tab ->
                val selected = tab == state.selectedTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) ArcadeSurfaceElevated else Color.Transparent)
                        .border(
                            width = if (selected) 1.dp else 0.dp,
                            color = if (selected) RightCyan.copy(alpha = 0.7f) else Color.Transparent,
                            shape = RoundedCornerShape(9.dp)
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.labelFr,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) CrispWhite else SteelMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        state.bannerMessage?.let { msg ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = ArcadeSurfaceElevated,
                border = BorderStroke(1.dp, RightCyan.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CrispWhite,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissBanner, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Fermer", tint = SteelMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Scrollable Tab Content
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (state.selectedTab) {
                LobbyTab.ARCADE -> {
                    item {
                        Text(
                            text = "5 PISTES SYNTHÉ C++20 • ZÉRO LATENCE • 100% HORS-LIGNE",
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelMuted
                        )
                    }
                    items(state.builtInTracks, key = { it.id }) { track ->
                        TrackCard(
                            track = track,
                            isSelected = track.id == state.selectedTrack.id,
                            accentColor = if (track.bpm >= 190f) LeftCrimson else RightCyan,
                            onSelect = { onSelectTrack(track) },
                            onQuickPlay = { onLaunchTrack(track) }
                        )
                    }
                }

                LobbyTab.LOCAL_MP3 -> {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onImportAudioFile,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = RightCyan, contentColor = OledBlack),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("IMPORTER MP3 / WAV", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = onRefreshLocals,
                                border = BorderStroke(1.dp, ArcadeBorderBright),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Scanner", tint = CrispWhite, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    if (state.zaagForgeTracks.isNotEmpty()) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 6.dp)
                            ) {
                                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = PowerGold, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "REMIX ZAAGFORGE DÉTECTÉS (${state.zaagForgeTracks.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PowerGold
                                )
                            }
                        }
                        items(state.zaagForgeTracks, key = { it.id }) { track ->
                            TrackCard(
                                track = track,
                                isSelected = track.id == state.selectedTrack.id,
                                accentColor = PowerGold,
                                onSelect = { onSelectTrack(track) },
                                onQuickPlay = { onLaunchTrack(track) }
                            )
                        }
                    } else {
                        item {
                            Surface(
                                color = ArcadeSurface,
                                border = BorderStroke(1.dp, PowerGold.copy(alpha = 0.35f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Astuce : tous vos exports créés dans ZAAGFORGE (Music/ZaagForge/) apparaissent automatiquement ici avec leur BPM Uptempo/Zaag !",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SteelMuted,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }

                    if (state.localMp3Tracks.isNotEmpty()) {
                        item {
                            Text(
                                text = "BIBLIOTHÈQUE AUDIO LOCALE (${state.localMp3Tracks.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = SteelMuted,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        items(state.localMp3Tracks, key = { it.id }) { track ->
                            TrackCard(
                                track = track,
                                isSelected = track.id == state.selectedTrack.id,
                                accentColor = RightCyan,
                                onSelect = { onSelectTrack(track) },
                                onQuickPlay = { onLaunchTrack(track) }
                            )
                        }
                    }
                }

                LobbyTab.STREAMING -> {
                    item {
                        // Deezer Instant 30s Official Preview FFT Search
                        Surface(
                            color = ArcadeSurface,
                            border = BorderStroke(1.dp, ArcadeBorder),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "RECHERCHE DEEZER (BEATMAP FFT 30s INSTANTANÉE)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RightCyan
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = state.deezerQuery,
                                        onValueChange = onDeezerQueryChange,
                                        placeholder = { Text("Ex: Unicorn On Ketamine, Noiseflow, Angerfist…", fontSize = 13.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = RightCyan,
                                            unfocusedBorderColor = ArcadeBorder,
                                            focusedTextColor = CrispWhite,
                                            unfocusedTextColor = CrispWhite
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = onSearchDeezer,
                                        enabled = !state.isSearchingDeezer,
                                        colors = ButtonDefaults.buttonColors(containerColor = RightCyan, contentColor = OledBlack),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        if (state.isSearchingDeezer) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = OledBlack, strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Rounded.Search, contentDescription = "Chercher")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.deezerResults.isNotEmpty()) {
                        items(state.deezerResults, key = { it.id }) { track ->
                            TrackCard(
                                track = track,
                                isSelected = track.id == state.selectedTrack.id,
                                accentColor = RightCyan,
                                onSelect = { onSelectTrack(track) },
                                onQuickPlay = { onLaunchTrack(track) }
                            )
                        }
                    }

                    item {
                        // Spotify / Deezer / SoundCloud Live Sync Card
                        Surface(
                            color = ArcadeSurface,
                            border = BorderStroke(1.dp, LeftCrimson.copy(alpha = 0.45f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "LIVE SYNC SPOTIFY • DEEZER • SOUNDCLOUD",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LeftCrimson
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Collez ou partagez un lien : lancez la lecture sur votre appli puis combattez en rythme (Tap-Sync + Micro Beat + Télécommande Média). Les flux DRM ne sont pas extraits.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SteelMuted
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = state.streamingLinkInput,
                                        onValueChange = onStreamingLinkChange,
                                        placeholder = { Text("https://open.spotify.com/track/…", fontSize = 13.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = LeftCrimson,
                                            unfocusedBorderColor = ArcadeBorder,
                                            focusedTextColor = CrispWhite,
                                            unfocusedTextColor = CrispWhite
                                        )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = onResolveStreamingLink,
                                        enabled = !state.isResolvingLink,
                                        colors = ButtonDefaults.buttonColors(containerColor = LeftCrimson, contentColor = CrispWhite),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        if (state.isResolvingLink) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = CrispWhite, strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Rounded.Link, contentDescription = "Analyser")
                                        }
                                    }
                                }

                                // Quick App Launch Pills
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        StreamingPlatform.SPOTIFY to PerfectGreen,
                                        StreamingPlatform.DEEZER to RightCyan,
                                        StreamingPlatform.SOUNDCLOUD to DodgeAmber
                                    ).forEach { (plat, col) ->
                                        OutlinedButton(
                                            onClick = { onOpenStreamingApp(plat, null) },
                                            modifier = Modifier.weight(1f),
                                            border = BorderStroke(1.dp, col.copy(alpha = 0.6f)),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(plat.label, style = MaterialTheme.typography.labelSmall, color = col, maxLines = 1)
                                        }
                                    }
                                }

                                state.resolvedStreamTrack?.let { resolved ->
                                    Spacer(Modifier.height(10.dp))
                                    Surface(
                                        color = ArcadeSurfaceElevated,
                                        border = BorderStroke(1.dp, PowerGold.copy(alpha = 0.7f)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = "${resolved.title} — ${resolved.artist}",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = CrispWhite
                                            )
                                            Text(
                                                text = "${resolved.streamingPlatform.label} • ${resolved.bpm.toInt()} BPM",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = PowerGold
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = { onLaunchTrack(resolved) },
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.buttonColors(containerColor = LeftCrimson),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text("LIVE SYNC", style = MaterialTheme.typography.labelSmall)
                                                }
                                                if (!resolved.previewUrl.isNullOrBlank()) {
                                                    Button(
                                                        onClick = { onPlayPreviewFromResolved(resolved) },
                                                        modifier = Modifier.weight(1f),
                                                        colors = ButtonDefaults.buttonColors(containerColor = RightCyan, contentColor = OledBlack),
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Text("FFT 30s", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sticky Bottom Primary Fight CTA
        Surface(
            color = ArcadeSurface,
            border = BorderStroke(1.dp, ArcadeBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.selectedTrack.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = CrispWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${state.selectedTrack.bpm.toInt()} BPM • ${state.selectedDifficulty.labelFr} • ${state.selectedMode.labelFr}",
                        style = MaterialTheme.typography.labelSmall,
                        color = RightCyan
                    )
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onLaunchSelected,
                    colors = ButtonDefaults.buttonColors(containerColor = LeftCrimson, contentColor = CrispWhite),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("COMBATTRE", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun TrackCard(
    track: ArcadeTrack,
    isSelected: Boolean,
    accentColor: Color,
    onSelect: () -> Unit,
    onQuickPlay: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect),
        color = if (isSelected) ArcadeSurfaceElevated else ArcadeSurface,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) accentColor else ArcadeBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.16f))
                    .border(1.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (track.isZaagForgeRemix) Icons.Rounded.Bolt else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = CrispWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = track.genreTag.ifBlank { track.artist },
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "• ${track.bpm.toInt()} BPM",
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelMuted
                    )
                }
            }
            IconButton(
                onClick = onQuickPlay,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Jouer",
                    tint = if (accentColor == LeftCrimson) CrispWhite else OledBlack
                )
            }
        }
    }
}

@Composable
private fun LoadingOverlayScreen(
    track: ArcadeTrack,
    message: String,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = LeftCrimson,
                trackColor = ArcadeSurfaceElevated,
                strokeWidth = 4.dp,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text(text = track.title, style = MaterialTheme.typography.headlineMedium, color = CrispWhite, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = RightCyan, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onCancel, border = BorderStroke(1.dp, ArcadeBorderBright)) {
                Text("ANNULER", color = CrispWhite)
            }
        }
    }
}

@Composable
private fun GameplayScreen(
    state: StrikeCamUiState,
    frame: GameFrameSnapshot,
    hasCameraPermission: Boolean,
    onPoseFrame: (List<com.strikecam.arcade.vision.PosePoint>, List<com.strikecam.arcade.vision.PosePoint>, Int, Int, Long) -> Unit,
    onDelegateReady: (String) -> Unit,
    onCycleBackdrop: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleMute: () -> Unit,
    onQuit: () -> Unit,
    onTapSync: () -> Unit,
    onAdjustBpm: (Float) -> Unit,
    onNudgePhase: (Int) -> Unit,
    onToggleMic: () -> Unit,
    onMediaPrev: () -> Unit,
    onMediaPlayPause: () -> Unit,
    onMediaNext: () -> Unit,
    onOpenExternalApp: () -> Unit,
    onFinishLiveSession: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Live CameraX 4:3 Pose Viewport (Exact AspectRatioStrategy parity between Preview & ImageAnalysis)
        if (hasCameraPermission) {
            CameraPoseViewport(
                isFrontCamera = state.isFrontCamera,
                onPoseFrame = onPoseFrame,
                onDelegateReady = onDelegateReady,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. 60-120 FPS Tactical 3D Perspective Arcade Canvas
        ArcadeCanvas(
            frame = frame,
            backdropMode = state.backdropMode,
            modifier = Modifier.fillMaxSize()
        )

        // 3. Minimal Ultra-Legible Top Arcade HUD
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Score + Combo Pill
                Surface(
                    color = OledBlack.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, if (frame.isFeverMode) PowerGold else ArcadeBorderBright),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column {
                            Text(
                                text = String.format(Locale.US, "%,d", frame.score),
                                style = MaterialTheme.typography.headlineMedium,
                                color = CrispWhite
                            )
                            Text(
                                text = String.format(Locale.US, "%.0f%% • %d BPM", frame.accuracyPct, state.activeBeatmapBpm.toInt()),
                                style = MaterialTheme.typography.labelSmall,
                                color = SteelMuted
                            )
                        }
                        if (frame.combo > 0) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "${frame.combo}×",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (frame.isFeverMode) PowerGold else RightCyan
                                )
                                Text(
                                    text = if (frame.isFeverMode) "FEVER ×${frame.multiplier}" else "MULT ×${frame.multiplier}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (frame.isFeverMode) PowerGold else CrispWhite
                                )
                            }
                        }
                    }
                }

                // Quick Tactical Controls
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HudIconButton(Icons.Rounded.Visibility, "Fond", onCycleBackdrop)
                    HudIconButton(Icons.Rounded.Cameraswitch, "Caméra", onFlipCamera)
                    HudIconButton(if (state.sfxMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "Son", onToggleMute)
                    HudIconButton(Icons.Rounded.Close, "Quitter", onQuit, tint = LeftCrimson)
                }
            }

            Spacer(Modifier.height(6.dp))

            // Survival Lives / Speed Banner OR Track Progress Bar
            if (state.selectedMode == GameMode.SURVIVAL) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 1..frame.maxLives) {
                            Icon(
                                imageVector = if (i <= frame.healthLives) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = null,
                                tint = if (i <= frame.healthLives) LeftCrimson else SteelDim,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Surface(
                        color = OledBlack.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, DodgeAmber.copy(alpha = 0.7f))
                    ) {
                        Text(
                            text = String.format(Locale.US, "VITESSE %.2f×", frame.speedMultiplier),
                            style = MaterialTheme.typography.labelSmall,
                            color = DodgeAmber,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            } else if (!state.isLiveSyncSession) {
                val progress = (frame.currentTimeMs / frame.durationMs.coerceAtLeast(1f)).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (frame.isFeverMode) PowerGold else RightCyan,
                    trackColor = ArcadeSurfaceElevated.copy(alpha = 0.65f)
                )
            }
        }

        // 4. 3-2-1 Calibration Countdown Overlay
        if (state.screen == ArcadeScreen.COUNTDOWN) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OledBlack.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.selectedMode == GameMode.DUEL_2P) {
                        val activePlayer = if (state.duelState.phase == com.strikecam.arcade.data.DuelPhase.PLAYER_2_PLAYING) {
                            state.duelState.player2Name
                        } else {
                            state.duelState.player1Name
                        }
                        Text(activePlayer, style = MaterialTheme.typography.titleLarge, color = PowerGold)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        text = state.countdownValue.toString(),
                        fontSize = 112.sp,
                        fontWeight = FontWeight.Black,
                        color = CrispWhite
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = OledBlack.copy(alpha = 0.80f),
                        border = BorderStroke(1.dp, RightCyan),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (frame.pose.baseline.isCalibrated) "POSTURE CALIBRÉE ✓" else "PLACEZ-VOUS FACE CAMÉRA…",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (frame.pose.baseline.isCalibrated) PerfectGreen else PowerGold
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("● ROUGE = POING GAUCHE", style = MaterialTheme.typography.labelSmall, color = LeftCrimson)
                                Text("● CYAN = POING DROIT", style = MaterialTheme.typography.labelSmall, color = RightCyan)
                            }
                        }
                    }
                }
            }
        }

        // 5. Live Streaming Sync Control Dock (Spotify / Deezer / SoundCloud)
        if (state.screen == ArcadeScreen.PLAYING && state.isLiveSyncSession) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp),
                color = OledBlack.copy(alpha = 0.84f),
                border = BorderStroke(1.dp, LeftCrimson.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "LIVE ${state.liveSyncBpm.toInt()} BPM (${if (state.livePhaseOffsetMs >= 0) "+" else ""}${state.livePhaseOffsetMs}ms)",
                            style = MaterialTheme.typography.labelMedium,
                            color = PowerGold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = onMediaPrev, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Précédent", tint = CrispWhite)
                            }
                            IconButton(onClick = onMediaPlayPause, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.Pause, contentDescription = "Lecture/Pause", tint = RightCyan)
                            }
                            IconButton(onClick = onMediaNext, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.SkipNext, contentDescription = "Suivant", tint = CrispWhite)
                            }
                            IconButton(onClick = onOpenExternalApp, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.OpenInNew, contentDescription = "Ouvrir app", tint = PerfectGreen)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onTapSync,
                            colors = ButtonDefaults.buttonColors(containerColor = LeftCrimson),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Rounded.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("TAP SYNC", style = MaterialTheme.typography.labelSmall)
                        }
                        SmallChipButton("-50ms") { onNudgePhase(-50) }
                        SmallChipButton("+50ms") { onNudgePhase(50) }
                        SmallChipButton("-2 BPM") { onAdjustBpm(-2f) }
                        SmallChipButton("+2 BPM") { onAdjustBpm(2f) }
                        OutlinedButton(
                            onClick = onToggleMic,
                            border = BorderStroke(1.dp, if (state.liveMicEnabled) PerfectGreen else ArcadeBorderBright),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = if (state.liveMicEnabled) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                                contentDescription = "Micro Beat",
                                tint = if (state.liveMicEnabled) PerfectGreen else SteelMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (state.liveMicEnabled) "MIC ON" else "MIC",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.liveMicEnabled) PerfectGreen else SteelMuted
                            )
                        }
                        Button(
                            onClick = onFinishLiveSession,
                            colors = ButtonDefaults.buttonColors(containerColor = RightCyan, contentColor = OledBlack),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("FINIR", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPoseViewport(
    isFrontCamera: Boolean,
    onPoseFrame: (List<com.strikecam.arcade.vision.PosePoint>, List<com.strikecam.arcade.vision.PosePoint>, Int, Int, Long) -> Unit,
    onDelegateReady: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }

    val poseHelper = remember(context) {
        PoseLandmarkerHelper(
            context = context,
            onResult = onPoseFrame,
            onError = {}
        )
    }

    DisposableEffect(poseHelper) {
        poseHelper.initialize()
        onDelegateReady(poseHelper.activeDelegateLabel)
        onDispose {
            poseHelper.close()
            analyzerExecutor.shutdown()
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(isFrontCamera, lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                runCatching {
                    val cameraProvider = cameraProviderFuture.get()
                    // Lock BOTH Preview and ImageAnalysis to identical 4:3 aspect ratio for 0.0px hand-to-target alignment!
                    val selector43 = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(selector43)
                        .build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(selector43)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { ia ->
                            ia.setAnalyzer(analyzerExecutor) { proxy ->
                                poseHelper.detectLiveStream(proxy, isFrontCamera)
                            }
                        }

                    val lensSelector = if (isFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, lensSelector, preview, analysis)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@Composable
private fun DuelHandoffScreen(
    duelState: DuelMatchState,
    trackTitle: String,
    onStartPlayer2: () -> Unit,
    onQuit: () -> Unit
) {
    val p1 = duelState.player1Summary
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DUEL 2 JOUEURS — MANCHE 1 TERMINÉE", style = MaterialTheme.typography.labelLarge, color = PowerGold)
            Spacer(Modifier.height(10.dp))
            Text(trackTitle, style = MaterialTheme.typography.headlineMedium, color = CrispWhite, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            if (p1 != null) {
                Surface(
                    color = ArcadeSurface,
                    border = BorderStroke(1.5.dp, LeftCrimson),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(p1.playerName, style = MaterialTheme.typography.labelLarge, color = LeftCrimson)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = String.format(Locale.US, "%,d PTS", p1.score),
                            style = MaterialTheme.typography.displayMedium,
                            color = CrispWhite
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Rang ${p1.rank.label} • Précision ${p1.accuracyPct.toInt()}% • Max Combo ${p1.maxCombo}×",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SteelMuted
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Passez le téléphone au ${duelState.player2Name} — même beatmap, même rythme !",
                style = MaterialTheme.typography.bodyLarge,
                color = RightCyan,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onStartPlayer2,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RightCyan, contentColor = OledBlack),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("PRÊT — LANCER ${duelState.player2Name}", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onQuit, border = BorderStroke(1.dp, ArcadeBorderBright)) {
                Text("QUITTER LE DUEL", color = SteelMuted)
            }
        }
    }
}

@Composable
private fun ResultsScreen(
    track: ArcadeTrack,
    mode: GameMode,
    difficulty: Difficulty,
    summary: PlayerRoundSummary?,
    duelState: DuelMatchState,
    onReplay: () -> Unit,
    onBackToLobby: () -> Unit
) {
    val s = summary ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (mode == GameMode.DUEL_2P) duelState.winnerLabel else if (s.failedOut) "K.O. EN MODE SURVIE" else "COMBAT TERMINÉ",
            style = MaterialTheme.typography.headlineMedium,
            color = if (mode == GameMode.DUEL_2P) PowerGold else if (s.failedOut) LeftCrimson else PerfectGreen,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${track.title} • ${difficulty.labelFr}",
            style = MaterialTheme.typography.bodyMedium,
            color = SteelMuted
        )

        Spacer(Modifier.height(16.dp))

        if (mode == GameMode.DUEL_2P && duelState.player1Summary != null && duelState.player2Summary != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DuelResultColumn(duelState.player1Summary, LeftCrimson, Modifier.weight(1f))
                DuelResultColumn(duelState.player2Summary, RightCyan, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
        } else {
            // Big Rank & Score Hero
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(86.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(rankColor(s.rank).copy(alpha = 0.18f))
                        .border(2.dp, rankColor(s.rank), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = s.rank.label, style = MaterialTheme.typography.displayLarge, color = rankColor(s.rank))
                }
                Column {
                    Text(
                        text = String.format(Locale.US, "%,d", s.score),
                        style = MaterialTheme.typography.displayMedium,
                        color = CrispWhite
                    )
                    Text(
                        text = String.format(Locale.US, "PRÉCISION %.1f%% • COMBO MAX %d×", s.accuracyPct, s.maxCombo),
                        style = MaterialTheme.typography.labelMedium,
                        color = RightCyan
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        // Breakdown Telemetry Grid
        Surface(
            color = ArcadeSurface,
            border = BorderStroke(1.dp, ArcadeBorder),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatCell("PERFECT", "${s.perfectCount}", PerfectGreen)
                    StatCell("GREAT", "${s.greatCount}", RightCyan)
                    StatCell("GOOD", "${s.goodCount}", PowerGold)
                    StatCell("ESQUIVES", "${s.dodgeCount}", PerfectGreen)
                    StatCell("MISS", "${s.missCount}", LeftCrimson)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatCell("RÉACTION MOY.", "${s.avgReactionMs.toInt()} ms", CrispWhite)
                    StatCell("COUPS PORTÉS", "${s.totalPunches}", CrispWhite)
                    StatCell("CALORIES", String.format(Locale.US, "%.1f kcal", s.caloriesKcal), DodgeAmber)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onBackToLobby,
                modifier = Modifier.weight(1f).height(52.dp),
                border = BorderStroke(1.dp, ArcadeBorderBright),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("LOBBY", style = MaterialTheme.typography.labelLarge, color = CrispWhite)
            }
            Button(
                onClick = onReplay,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LeftCrimson),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("REJOUER", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DuelResultColumn(summary: PlayerRoundSummary, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = ArcadeSurface,
        border = BorderStroke(1.5.dp, accent),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(summary.playerName, style = MaterialTheme.typography.labelLarge, color = accent)
            Spacer(Modifier.height(4.dp))
            Text(summary.rank.label, style = MaterialTheme.typography.displayMedium, color = rankColor(summary.rank))
            Text(String.format(Locale.US, "%,d", summary.score), style = MaterialTheme.typography.titleLarge, color = CrispWhite)
            Text(
                text = String.format(Locale.US, "%.1f%% • %d×", summary.accuracyPct, summary.maxCombo),
                style = MaterialTheme.typography.labelSmall,
                color = SteelMuted
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = SteelMuted)
    }
}

@Composable
private fun HudIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = CrispWhite
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(OledBlack.copy(alpha = 0.72f))
            .border(1.dp, ArcadeBorderBright, RoundedCornerShape(10.dp))
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SmallChipButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, ArcadeBorderBright),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = CrispWhite)
    }
}

@Composable
private fun RankBadgeChip(rank: ArcadeRank) {
    val c = rankColor(rank)
    Surface(
        color = c.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, c),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = "RANG ${rank.label}",
            style = MaterialTheme.typography.labelSmall,
            color = c,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun rankColor(rank: ArcadeRank): Color = when (rank) {
    ArcadeRank.S_PLUS -> PowerGold
    ArcadeRank.S -> PerfectGreen
    ArcadeRank.A -> RightCyan
    ArcadeRank.B -> DodgeAmber
    ArcadeRank.C -> SteelMuted
    ArcadeRank.D -> LeftCrimson
}
