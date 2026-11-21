package com.kinetix.coach.ui.vision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.WorkoutSession
import com.kinetix.coach.ui.components.ExerciseDemoSheetContent
import com.kinetix.coach.ui.components.HairlineDivider
import com.kinetix.coach.ui.components.IndustrialCard
import com.kinetix.coach.ui.components.MetricCell
import com.kinetix.coach.ui.components.SectionLabel
import com.kinetix.coach.ui.components.formScoreColor
import com.kinetix.coach.ui.theme.BorderHairline
import com.kinetix.coach.ui.theme.CharcoalSurface
import com.kinetix.coach.ui.theme.CrispWhite
import com.kinetix.coach.ui.theme.ElevatedSurface
import com.kinetix.coach.ui.theme.FormCaution
import com.kinetix.coach.ui.theme.FormFault
import com.kinetix.coach.ui.theme.FormGood
import com.kinetix.coach.ui.theme.ObsidianBg
import com.kinetix.coach.ui.theme.SignalOrange
import com.kinetix.coach.ui.theme.SignalOrangeDim
import com.kinetix.coach.ui.theme.SteelGray
import com.kinetix.coach.vision.AngleCorrection
import com.kinetix.coach.vision.DetectionStrictness
import com.kinetix.coach.vision.ExerciseVideoMapper
import com.kinetix.coach.vision.FormQuality
import com.kinetix.coach.vision.PoseLandmarkerHelper
import com.kinetix.coach.vision.SkeletonOverlay
import com.kinetix.coach.vision.VelocityFatigueZone
import com.kinetix.coach.vision.VelocityTelemetry
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private val REST_PRESETS_SEC = listOf(45, 60, 90, 120)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionScreen(
    viewModel: VisionViewModel,
    onAnalyzeWithCoach: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val challengeRecords by viewModel.challengeRecords.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Android Storage Access Framework launcher for local MP3 / M4A / WAV / OGG music
    val customAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            var displayName = "Ma musique locale"
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0)
                        if (!name.isNullOrBlank()) displayName = name
                    }
                }
            }
            viewModel.setCustomMusicUri(uri.toString(), displayName)
        }
    }

    var showExercisePicker by remember { mutableStateOf(false) }
    var showChallengesSheet by remember { mutableStateOf(false) }
    var demoPreviewExercise by remember { mutableStateOf<ExerciseType?>(null) }

    // Ensure offline music stops cleanly on ON_PAUSE (lock screen / phone call) or tab switch
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.pauseBackgroundAudio()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pauseBackgroundAudio()
        }
    }

    // Haptic pulse when rest timer completes
    LaunchedEffect(uiState.restFinishedEvent) {
        if (uiState.restFinishedEvent > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        if (!hasCameraPermission) {
            CameraPermissionPrompt(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            )
        } else {
            CameraPoseViewport(
                isFrontCamera = uiState.isFrontCamera,
                viewModel = viewModel
            )

            // Isolated full-FPS Skeleton + Angle Badges Overlay (never recomposes HUD)
            LiveSkeletonOverlayLayer(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )

            // Top Precision Control Bar
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1-Tap AUTO / FIXE Mode Pill Toggle
                    Surface(
                        modifier = Modifier
                            .height(44.dp)
                            .clickable { viewModel.toggleAutoDetect() },
                        shape = RoundedCornerShape(10.dp),
                        color = if (uiState.isAutoDetectEnabled) SignalOrange else ObsidianBg.copy(alpha = 0.90f),
                        border = BorderStroke(
                            1.dp,
                            if (uiState.isAutoDetectEnabled) SignalOrange else BorderHairline
                        )
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (uiState.isAutoDetectEnabled) "AUTO" else "FIXE",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.isAutoDetectEnabled) CrispWhite else SteelGray,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    // Exercise Selector Trigger + Auto-Detected Variant Pill
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showExercisePicker = true },
                        shape = RoundedCornerShape(10.dp),
                        color = ObsidianBg.copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, BorderHairline)
                    ) {
                        val confPct = (uiState.autoDetectConfidence * 100f).roundToInt()
                        val titleLabel = when {
                            uiState.isAutoDetectEnabled && confPct >= 55 ->
                                "AUTO · ${uiState.selectedExercise.labelFr} $confPct%"
                            uiState.isAutoDetectEnabled ->
                                "AUTO · ${uiState.selectedExercise.labelFr}"
                            else -> uiState.selectedExercise.labelFr
                        }
                        val subLabel = if (uiState.isAutoDetectEnabled && confPct < 55) {
                            "${uiState.autoDetectStatusFr} · ${uiState.delegateLabel}"
                        } else {
                            "${uiState.selectedExercise.musclesFr} · ${uiState.detectionStrictness.labelFr}"
                        }
                        val detectedVariant = if (uiState.frame.poseDetected) uiState.frame.detectedVariantFr else null

                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = titleLabel,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = CrispWhite,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (!detectedVariant.isNullOrBlank()) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = SignalOrangeDim,
                                            border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.75f))
                                        ) {
                                            Text(
                                                text = detectedVariant,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = SignalOrange,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = subLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SteelGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Changer d'exercice",
                                tint = SignalOrange,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    // Crisp "Démo" Video Pill Button
                    Surface(
                        modifier = Modifier
                            .height(44.dp)
                            .clickable { demoPreviewExercise = uiState.selectedExercise },
                        shape = RoundedCornerShape(10.dp),
                        color = ObsidianBg.copy(alpha = 0.90f),
                        border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.85f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Voir la démo vidéo",
                                tint = SignalOrange,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "Démo",
                                style = MaterialTheme.typography.labelSmall,
                                color = CrispWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.width(5.dp))

                    // Voice Toggle
                    HudIconButton(
                        onClick = { viewModel.toggleVoice() }
                    ) {
                        Icon(
                            imageVector = if (uiState.isVoiceEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Coach vocal",
                            tint = if (uiState.isVoiceEnabled) SignalOrange else SteelGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(5.dp))

                    // Flip Camera
                    HudIconButton(
                        onClick = { viewModel.toggleCamera() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Changer de caméra",
                            tint = CrispWhite,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(7.dp))

                // Compact Telemetry Sub-Bar: 3D Viewpoint + Live Calories + Défis Pill + Framing Alert
                val vp = uiState.viewpointInfo
                val liveTotalKcal = uiState.sessionTotalCaloriesKcal + uiState.currentSetCaloriesKcal
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Camera Angle & 3D Precision Pill
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = ObsidianBg.copy(alpha = 0.86f),
                        border = BorderStroke(1.dp, if (vp.is3DActive) FormGood.copy(alpha = 0.65f) else BorderHairline)
                    ) {
                        val modeTag = if (vp.is3DActive) "3D" else "2D"
                        val vpLabel = if (uiState.frame.poseDetected) {
                            "$modeTag · ${vp.viewpoint.shortLabelFr} ${vp.azimuthDeg}°"
                        } else {
                            "$modeTag · MULTI-ANGLES"
                        }
                        Text(
                            text = vpLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (vp.is3DActive && uiState.frame.poseDetected) FormGood else SteelGray,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Live Profile-Calibrated Calories Pill
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = ObsidianBg.copy(alpha = 0.86f),
                        border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.65f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocalFireDepartment,
                                contentDescription = null,
                                tint = SignalOrange,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = String.format(Locale.US, "%.1f kcal", liveTotalKcal),
                                style = MaterialTheme.typography.labelSmall,
                                color = CrispWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Crisp "Défis" Challenges & Music Pill Button
                    val challengeActive = uiState.activeChallenge != null
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = if (challengeActive) SignalOrange else ObsidianBg.copy(alpha = 0.90f),
                        border = BorderStroke(1.dp, SignalOrange),
                        modifier = Modifier.clickable { showChallengesSheet = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.EmojiEvents,
                                contentDescription = "Ouvrir les défis",
                                tint = if (challengeActive) CrispWhite else SignalOrange,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Défis",
                                style = MaterialTheme.typography.labelSmall,
                                color = CrispWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 1-Tap Quick Lock / Unlock Exercise Padlock (pins AUTO exercise without losing reps)
                    if (!challengeActive) {
                        val isLockedFixed = !uiState.isAutoDetectEnabled
                        val showCompactLock = uiState.frame.poseDetected && !vp.framingWarningFr.isNullOrBlank()
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = if (isLockedFixed) SignalOrangeDim else ObsidianBg.copy(alpha = 0.90f),
                            border = BorderStroke(
                                1.dp,
                                if (isLockedFixed) SignalOrange else BorderHairline
                            ),
                            modifier = Modifier.clickable { viewModel.lockCurrentAutoExercise() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isLockedFixed) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    contentDescription = if (isLockedFixed) "Déverrouiller AUTO" else "Verrouiller l'exercice",
                                    tint = if (isLockedFixed) SignalOrange else CrispWhite,
                                    modifier = Modifier.size(13.dp)
                                )
                                if (!showCompactLock) {
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        text = if (isLockedFixed) "Figé" else "Figer",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isLockedFixed) SignalOrange else CrispWhite,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // 1-Tap Setup Countdown Delay Pill (0s / 5s / 8s / 10s — prevents false reps while placing phone!)
                        val setupEnabled = uiState.setupDelaySec > 0
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = if (uiState.isSettingUp) FormCaution.copy(alpha = 0.22f) else ObsidianBg.copy(alpha = 0.90f),
                            border = BorderStroke(
                                1.dp,
                                when {
                                    uiState.isSettingUp -> FormCaution
                                    setupEnabled -> FormGood.copy(alpha = 0.65f)
                                    else -> BorderHairline
                                }
                            ),
                            modifier = Modifier.clickable { viewModel.cycleSetupDelay() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = "Délai d'installation",
                                    tint = when {
                                        uiState.isSettingUp -> FormCaution
                                        setupEnabled -> FormGood
                                        else -> SteelGray
                                    },
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = if (setupEnabled) "${uiState.setupDelaySec}s" else "0s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        uiState.isSettingUp -> FormCaution
                                        setupEnabled -> CrispWhite
                                        else -> SteelGray
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Framing Alert Chip (only appears if body parts are clipped out of frame)
                    val framingWarn = vp.framingWarningFr
                    if (uiState.frame.poseDetected && !framingWarn.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(7.dp),
                            color = ObsidianBg.copy(alpha = 0.90f),
                            border = BorderStroke(1.dp, FormCaution)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WarningAmber,
                                    contentDescription = null,
                                    tint = FormCaution,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = framingWarn,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = FormCaution,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Active Camera-Verified Challenge Banner
                uiState.activeChallenge?.let { activeSnap ->
                    Spacer(Modifier.height(8.dp))
                    ActiveChallengeBanner(
                        snapshot = activeSnap,
                        selectedTrack = uiState.selectedMusicTrack,
                        customMusicName = uiState.customMusicName,
                        isMusicMuted = uiState.isMusicMuted,
                        onToggleMute = { viewModel.toggleMusicMute() },
                        onCycleTrack = { viewModel.cycleQuickMusicTrack() },
                        onPickCustomAudio = { customAudioLauncher.launch(arrayOf("audio/*")) },
                        onStopChallenge = { viewModel.stopActiveChallenge() }
                    )
                }

                // Active Hands-Free Installation Countdown Pill (Zero false reps while setting phone down!)
                if (uiState.isSettingUp && !uiState.isResting && uiState.activeChallenge == null) {
                    Spacer(Modifier.height(8.dp))
                    SetupCountdownOverlayPill(
                        remainingSec = uiState.setupRemainingSec,
                        totalSec = maxOf(uiState.setupDelaySec, uiState.setupRemainingSec),
                        onAdd5s = { viewModel.addSetupSeconds(5) },
                        onStartNow = { viewModel.skipSetupCountdown() }
                    )
                }

                // Active Smart Rest Countdown Overlay Pill
                if (uiState.isResting && uiState.activeChallenge == null) {
                    Spacer(Modifier.height(8.dp))
                    RestCountdownOverlayPill(
                        remainingSec = uiState.restRemainingSec,
                        totalSec = uiState.restDurationSec,
                        nextSetNumber = uiState.currentSetNumber,
                        onAdd15s = { viewModel.addRestSeconds(15) },
                        onSkip = { viewModel.skipRest() }
                    )
                }
            }

            // Bottom Stack: Live Precision Angle Correction Banner + Bottom Telemetry HUD
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrecisionCorrectionBanner(
                    poseDetected = uiState.frame.poseDetected,
                    isStabilizing = uiState.frame.isStabilizing,
                    formScore = uiState.frame.formScore,
                    formQuality = uiState.frame.formQuality,
                    topCorrection = uiState.frame.corrections.firstOrNull(),
                    fallbackMessage = uiState.errorMessage ?: uiState.frame.feedbackFr
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = ObsidianBg.copy(alpha = 0.93f),
                    border = BorderStroke(1.dp, BorderHairline)
                ) {
                    Column(modifier = Modifier.padding(13.dp)) {
                    // Multi-Set Header + Rest Preset Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = SignalOrangeDim,
                                border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = "SÉRIE ${uiState.currentSetNumber}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SignalOrange,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                            val totalSessionMetric = if (uiState.selectedExercise.isIsometric) {
                                val totSec = uiState.sessionTotalHoldSec + uiState.frame.isometricHoldSec.toInt()
                                "Total ${totSec}s"
                            } else {
                                val totReps = uiState.sessionTotalReps + uiState.frame.repCount
                                "Total $totReps reps"
                            }
                            Text(
                                text = totalSessionMetric,
                                style = MaterialTheme.typography.labelSmall,
                                color = SteelGray
                            )
                        }

                        // Configurable Rest Duration Presets (45s / 60s / 90s / 120s)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            REST_PRESETS_SEC.forEach { sec ->
                                val selected = uiState.restDurationSec == sec
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = if (selected) ElevatedSurface else ObsidianBg,
                                    border = BorderStroke(1.dp, if (selected) SignalOrange else BorderHairline),
                                    modifier = Modifier.clickable { viewModel.setRestDuration(sec) }
                                ) {
                                    Text(
                                        text = "${sec}s",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) SignalOrange else SteelGray,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Giant Tabular Counter (Reps or Isometric Hold Time)
                        Column {
                            Text(
                                text = if (uiState.selectedExercise.isIsometric) "MAINTIEN ALIGNÉ" else "RÉPÉTITIONS",
                                style = MaterialTheme.typography.labelSmall,
                                color = SteelGray
                            )
                            val primaryValue = if (uiState.selectedExercise.isIsometric) {
                                String.format("%.1fs", uiState.frame.isometricHoldSec)
                            } else {
                                uiState.frame.repCount.toString()
                            }
                            Text(
                                text = primaryValue,
                                style = MaterialTheme.typography.displayLarge,
                                color = CrispWhite
                            )
                        }

                        // Phase + ROM + Action Controls
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = ElevatedSurface,
                                    border = BorderStroke(1.dp, BorderHairline)
                                ) {
                                    Text(
                                        text = uiState.frame.phase.labelFr,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SignalOrange,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Text(
                                    text = "ROM ${uiState.frame.romPct}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = CrispWhite
                                )
                            }

                            Spacer(Modifier.height(7.dp))

                            LinearProgressIndicator(
                                progress = { uiState.frame.romPct / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = SignalOrange,
                                trackColor = ElevatedSurface
                            )

                            Spacer(Modifier.height(9.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.resetSession() },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, BorderHairline),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Réinitialiser",
                                        tint = SteelGray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                OutlinedButton(
                                    onClick = { viewModel.completeSetAndStartRest() },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.7f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "Fin de série / Repos",
                                        tint = SignalOrange,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Repos", style = MaterialTheme.typography.labelMedium, color = CrispWhite)
                                }
                                Button(
                                    onClick = { viewModel.finishSession() },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SignalOrange,
                                        contentColor = CrispWhite
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Flag,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Fin", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    // Live VBT Concentric Speed & Estimated Max Reps Telemetry Strip (dynamic exercises)
                    if (!uiState.selectedExercise.isIsometric && uiState.frame.velocityTelemetry.isSupported) {
                        Spacer(Modifier.height(9.dp))
                        HairlineDivider()
                        Spacer(Modifier.height(8.dp))
                        VelocityTelemetryStrip(
                            vbt = uiState.frame.velocityTelemetry,
                            currentReps = uiState.frame.repCount
                        )
                    }
                    }
                }
            }
        }
    }

    // 16-Exercise Selector Sheet (with Adaptatif/Strict mode toggle + inline "Voir la vidéo" trigger)
    if (showExercisePicker) {
        ModalBottomSheet(
            onDismissRequest = { showExercisePicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CharcoalSurface,
            contentColor = CrispWhite
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                SectionLabel(
                    text = "Sélectionner un exercice",
                    trailing = "${ExerciseType.entries.size} MOUVEMENTS · VIDÉO"
                )
                Spacer(Modifier.height(10.dp))

                // 1-Tap Adaptatif / Strict Detection Strictness Toggle
                IndustrialCard(containerColor = ObsidianBg) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "MODE DE DÉTECTION",
                                style = MaterialTheme.typography.labelSmall,
                                color = SteelGray,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = uiState.detectionStrictness.descFr,
                                style = MaterialTheme.typography.labelSmall,
                                color = CrispWhite,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            DetectionStrictness.entries.forEach { mode ->
                                val isSel = uiState.detectionStrictness == mode
                                Surface(
                                    shape = RoundedCornerShape(7.dp),
                                    color = if (isSel) SignalOrange else ElevatedSurface,
                                    border = BorderStroke(1.dp, if (isSel) SignalOrange else BorderHairline),
                                    modifier = Modifier.clickable { viewModel.setDetectionStrictness(mode) }
                                ) {
                                    Text(
                                        text = mode.labelFr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSel) CrispWhite else SteelGray,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(420.dp)
                ) {
                    items(ExerciseType.entries) { ex ->
                        val selected = ex == uiState.selectedExercise
                        IndustrialCard(
                            containerColor = if (selected) ElevatedSurface else ObsidianBg,
                            borderColor = if (selected) SignalOrange else BorderHairline,
                            onClick = {
                                viewModel.selectExercise(ex)
                                showExercisePicker = false
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = ex.labelFr,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = CrispWhite
                                        )
                                        if (selected) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = SignalOrange,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "${ex.musclesFr} · Caméra : ${ex.cameraAngleHint}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SignalOrange
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = ex.targetCueFr,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = SteelGray
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                // Direct "Voir la vidéo" Preview Button
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = SignalOrangeDim,
                                    border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.65f)),
                                    modifier = Modifier.clickable {
                                        showExercisePicker = false
                                        demoPreviewExercise = ex
                                    }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayCircleOutline,
                                            contentDescription = "Voir la vidéo",
                                            tint = SignalOrange,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "VIDÉO",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CrispWhite,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Challenges & Offline Music Modal Bottom Sheet
    if (showChallengesSheet) {
        ChallengesAndMusicSheet(
            currentExercise = uiState.selectedExercise,
            selectedTrack = uiState.selectedMusicTrack,
            customMusicUri = uiState.customMusicUri,
            customMusicName = uiState.customMusicName,
            records = challengeRecords,
            onSelectTrack = { viewModel.selectMusicTrack(it) },
            onPickCustomAudio = { customAudioLauncher.launch(arrayOf("audio/*")) },
            onLaunchChallenge = { cfg ->
                showChallengesSheet = false
                viewModel.startChallenge(cfg)
            },
            onDismiss = { showChallengesSheet = false }
        )
    }

    // Challenge Victory / Summary Modal Bottom Sheet
    uiState.completedChallengeResult?.let { result ->
        ChallengeResultSheet(
            result = result,
            onReplay = { viewModel.replayLastChallenge() },
            onClose = { viewModel.dismissChallengeResult() }
        )
    }

    // Exercise Studio Demo Modal Bottom Sheet (MP4 + 0.5× slow-mo + 4-phase guide + checklist)
    demoPreviewExercise?.let { exercise ->
        val guide = remember(exercise) { ExerciseVideoMapper.forVisionExercise(exercise) }
        ModalBottomSheet(
            onDismissRequest = { demoPreviewExercise = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CharcoalSurface,
            contentColor = CrispWhite
        ) {
            ExerciseDemoSheetContent(
                title = exercise.labelFr,
                subtitle = "${exercise.musclesFr} · Caméra : ${exercise.cameraAngleHint}",
                guide = guide,
                onClose = { demoPreviewExercise = null }
            )
        }
    }

    // Session Summary Bottom Sheet with 1-tap Coach AI Biomechanical & VBT Debrief
    uiState.completedSummary?.let { summary ->
        SessionSummarySheet(
            session = summary,
            breakdown = uiState.completedBreakdown,
            velocityTelemetry = uiState.completedVelocityTelemetry,
            onDismiss = { viewModel.dismissSummaryAndRestart() },
            onAnalyzeWithCoach = {
                val prompt = viewModel.buildCoachDebriefPrompt(summary)
                viewModel.dismissSummaryAndRestart()
                onAnalyzeWithCoach(prompt)
            }
        )
    }
}

@Composable
private fun LiveSkeletonOverlayLayer(
    viewModel: VisionViewModel,
    modifier: Modifier = Modifier
) {
    val overlayState by viewModel.poseOverlayState.collectAsStateWithLifecycle()
    SkeletonOverlay(
        overlayState = overlayState,
        modifier = modifier
    )
}

@Composable
private fun PrecisionCorrectionBanner(
    poseDetected: Boolean,
    isStabilizing: Boolean,
    formScore: Int,
    formQuality: FormQuality,
    topCorrection: AngleCorrection?,
    fallbackMessage: String
) {
    val accentColor = when {
        !poseDetected -> SteelGray
        isStabilizing -> FormCaution
        topCorrection != null -> when (topCorrection.severity) {
            FormQuality.FAULT -> FormFault
            FormQuality.CAUTION -> FormCaution
            FormQuality.GOOD -> FormGood
        }
        else -> when (formQuality) {
            FormQuality.GOOD -> FormGood
            FormQuality.CAUTION -> FormCaution
            FormQuality.FAULT -> FormFault
        }
    }

    Surface(
        shape = RoundedCornerShape(9.dp),
        color = ObsidianBg.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, if (poseDetected) accentColor.copy(alpha = 0.85f) else BorderHairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Swiss Precision vertical status bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor)
            )
            Spacer(Modifier.width(9.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (poseDetected && isStabilizing) {
                    Text(
                        text = fallbackMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = FormCaution,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (poseDetected && topCorrection != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = accentColor.copy(alpha = 0.22f),
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.7f))
                        ) {
                            Text(
                                text = topCorrection.shortBadgeFr,
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                        Text(
                            text = topCorrection.jointLabelFr.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = topCorrection.instructionFr,
                        style = MaterialTheme.typography.bodySmall,
                        color = CrispWhite,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = if (poseDetected && formQuality == FormQuality.GOOD && fallbackMessage.startsWith("Posture")) {
                            "Alignement optimal · Angles validés"
                        } else {
                            fallbackMessage
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (poseDetected && formQuality == FormQuality.GOOD) FormGood else CrispWhite,
                        fontWeight = if (poseDetected && formQuality == FormQuality.GOOD) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = accentColor.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, accentColor)
            ) {
                Text(
                    text = if (isStabilizing) "STAB" else "$formScore%",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SetupCountdownOverlayPill(
    remainingSec: Int,
    totalSec: Int,
    onAdd5s: () -> Unit,
    onStartNow: () -> Unit
) {
    val progress = if (totalSec > 0) (remainingSec.toFloat() / totalSec.toFloat()).coerceIn(0f, 1f) else 0f

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianBg.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, FormCaution),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = FormCaution,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "INSTALLATION · COMPTAGE EN PAUSE",
                            style = MaterialTheme.typography.labelSmall,
                            color = FormCaution,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "${remainingSec}s",
                                style = MaterialTheme.typography.titleLarge,
                                color = CrispWhite,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Posez le téléphone et placez-vous",
                                style = MaterialTheme.typography.labelSmall,
                                color = SteelGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = ElevatedSurface,
                        border = BorderStroke(1.dp, BorderHairline),
                        modifier = Modifier.clickable(onClick = onAdd5s)
                    ) {
                        Text(
                            text = "+5s",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrispWhite,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = FormGood,
                        modifier = Modifier.clickable(onClick = onStartNow)
                    ) {
                        Text(
                            text = "Prêt !",
                            style = MaterialTheme.typography.labelMedium,
                            color = ObsidianBg,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = FormCaution,
                trackColor = ElevatedSurface
            )
        }
    }
}

@Composable
private fun RestCountdownOverlayPill(
    remainingSec: Int,
    totalSec: Int,
    nextSetNumber: Int,
    onAdd15s: () -> Unit,
    onSkip: () -> Unit
) {
    val mins = remainingSec / 60
    val secs = remainingSec % 60
    val formattedTime = String.format(Locale.US, "%02d:%02d", mins, secs)
    val progress = if (totalSec > 0) (remainingSec.toFloat() / totalSec.toFloat()).coerceIn(0f, 1f) else 0f

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianBg.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, SignalOrange),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = SignalOrange,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "REPOS AVANT SÉRIE $nextSetNumber",
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelGray,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.titleLarge,
                            color = SignalOrange,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = ElevatedSurface,
                        border = BorderStroke(1.dp, BorderHairline),
                        modifier = Modifier.clickable(onClick = onAdd15s)
                    ) {
                        Text(
                            text = "+15s",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrispWhite,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = SignalOrange,
                        modifier = Modifier.clickable(onClick = onSkip)
                    ) {
                        Text(
                            text = "Passer",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrispWhite,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = SignalOrange,
                trackColor = ElevatedSurface
            )
        }
    }
}

@Composable
private fun HudIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianBg.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, BorderHairline),
        modifier = Modifier.size(44.dp)
    ) {
        IconButton(onClick = onClick) {
            content()
        }
    }
}

@Composable
private fun CameraPermissionPrompt(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        IndustrialCard(modifier = Modifier.fillMaxWidth()) {
            SectionLabel(text = "VISION BIOMÉCANIQUE 3D HORS-LIGNE")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Accès caméra requis",
                style = MaterialTheme.typography.headlineMedium,
                color = CrispWhite
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "KINETIX Vision analyse vos 33 repères articulaires 3D 100% en local sur votre appareil via MediaPipe. Aucune image n'est enregistrée ni transmise.",
                style = MaterialTheme.typography.bodyMedium,
                color = SteelGray
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRequest,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = CrispWhite),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Autoriser la caméra", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CameraPoseViewport(
    isFrontCamera: Boolean,
    viewModel: VisionViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val landmarkerHelper = remember {
        PoseLandmarkerHelper(
            context = context,
            onResult = { screenPts, worldPts, w, h, ts ->
                viewModel.onPoseLandmarks(screenPts, worldPts, w, h, ts)
            },
            onError = { msg -> viewModel.reportError(msg) }
        )
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        landmarkerHelper.initialize()
        viewModel.setDelegateLabel(landmarkerHelper.activeDelegateLabel)
        onDispose {
            runCatching { boundProvider?.unbindAll() }
            landmarkerHelper.close()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(isFrontCamera, lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            boundProvider = cameraProvider
            val previewResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(previewResolutionSelector)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            val analysisResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val analyzer = ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolutionSelector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { proxy ->
                        landmarkerHelper.detectLiveStream(proxy, isFrontCamera)
                    }
                }

            val selector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analyzer)
            } catch (e: Exception) {
                viewModel.reportError("Caméra indisponible : ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

private fun fatigueZoneColor(zone: VelocityFatigueZone): Color = when (zone) {
    VelocityFatigueZone.CALIBRATING -> SteelGray
    VelocityFatigueZone.EXPLOSIVE -> FormGood
    VelocityFatigueZone.OPTIMAL -> SignalOrange
    VelocityFatigueZone.FATIGUE -> FormCaution
    VelocityFatigueZone.NEAR_FAILURE -> FormFault
}

@Composable
private fun VelocityTelemetryStrip(
    vbt: VelocityTelemetry,
    currentReps: Int
) {
    val zoneColor = fatigueZoneColor(vbt.fatigueZone)
    val displaySpeed = if (vbt.lastRepVelocityMps > 0.01f) vbt.lastRepVelocityMps else vbt.currentLiveVelocityMps
    val speedLabel = if (displaySpeed > 0.01f) String.format(Locale.US, "%.2f m/s", displaySpeed) else "-- m/s"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Left: Live concentric velocity + Velocity Loss % + Readiness
        Column(modifier = Modifier.weight(1.05f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Speed,
                    contentDescription = "Vitesse concentrique",
                    tint = zoneColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = speedLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = CrispWhite,
                    fontWeight = FontWeight.Bold
                )
                if (vbt.repVelocitiesMps.size >= 2) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = zoneColor.copy(alpha = 0.20f),
                        border = BorderStroke(1.dp, zoneColor.copy(alpha = 0.65f))
                    ) {
                        Text(
                            text = if (vbt.velocityLossPct > 0) "-${vbt.velocityLossPct}%" else "0%",
                            style = MaterialTheme.typography.labelSmall,
                            color = zoneColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            val subText = if (vbt.completedSets.isNotEmpty() && vbt.readinessPct in 50..96) {
                "Fraîcheur ${vbt.readinessPct}% · ${vbt.fatigueZone.shortLabelFr}"
            } else {
                vbt.statusFr
            }
            Text(
                text = subText,
                style = MaterialTheme.typography.labelSmall,
                color = SteelGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Center: Live Rep-by-Rep Concentric Velocity Decay Sparkline
        VelocitySparklineChart(
            velocities = vbt.repVelocitiesMps,
            baselineVelocity = maxOf(vbt.sessionFreshBaselineMps, vbt.baselineVelocityMps),
            modifier = Modifier
                .width(74.dp)
                .height(30.dp)
        )

        // Right: Estimated Max Reps & RIR Precision Pill
        Surface(
            shape = RoundedCornerShape(7.dp),
            color = if (vbt.estimatedMaxReps != null) zoneColor.copy(alpha = 0.16f) else ElevatedSurface,
            border = BorderStroke(
                1.dp,
                if (vbt.estimatedMaxReps != null) zoneColor.copy(alpha = 0.80f) else BorderHairline
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.End
            ) {
                val estMax = vbt.estimatedMaxReps
                val rir = vbt.estimatedRir
                Text(
                    text = if (estMax != null) "MAX ~$estMax" else "CALIB. $currentReps/3",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (estMax != null) CrispWhite else SteelGray,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        rir != null && rir == 0 -> "ÉCHEC · RPE 10"
                        rir != null -> "RIR $rir · ${vbt.confidencePct}%"
                        vbt.estimatedFreshMaxReps != null -> "RÉF. ~${vbt.estimatedFreshMaxReps}"
                        else -> "VBT ACTIF"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (estMax != null) zoneColor else SteelGray,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun VelocitySparklineChart(
    velocities: List<Float>,
    baselineVelocity: Float,
    modifier: Modifier = Modifier
) {
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f) }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padV = 3f
        val usableH = (h - padV * 2f).coerceAtLeast(4f)

        // Subtle failure threshold reference line (~50% baseline)
        val failY = h - padV - usableH * 0.48f
        drawLine(
            color = FormFault.copy(alpha = 0.42f),
            start = Offset(0f, failY),
            end = Offset(w, failY),
            strokeWidth = 1.4f,
            pathEffect = dashEffect
        )

        if (velocities.isEmpty()) {
            // Subtle baseline slots before first rep
            val slots = 6
            val gap = 3f
            val barW = ((w - gap * (slots - 1)) / slots).coerceAtLeast(3f)
            for (idx in 0 until slots) {
                val x = idx * (barW + gap)
                drawRoundRect(
                    color = ElevatedSurface,
                    topLeft = Offset(x, h - padV - 6f),
                    size = ComposeSize(barW, 6f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }
            return@Canvas
        }

        val visible = velocities.takeLast(12)
        val refMax = maxOf(baselineVelocity, visible.maxOrNull() ?: 0.5f, 0.25f)
        val count = visible.size.coerceAtLeast(4)
        val gap = 3f
        val barW = ((w - gap * (count - 1)) / count).coerceAtLeast(3f)

        visible.forEachIndexed { idx, v ->
            val ratio = (v / refMax).coerceIn(0.14f, 1.0f)
            val barH = usableH * ratio
            val x = idx * (barW + gap)
            val y = h - padV - barH
            val vl = ((1f - ratio) * 100f)
            val barColor = when {
                vl >= 40f -> FormFault
                vl >= 25f -> FormCaution
                vl >= 10f -> SignalOrange
                else -> FormGood
            }
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, y),
                size = ComposeSize(barW, barH),
                cornerRadius = CornerRadius(2.5f, 2.5f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSummarySheet(
    session: WorkoutSession,
    breakdown: List<ExerciseBreakdownItem>,
    velocityTelemetry: VelocityTelemetry?,
    onDismiss: () -> Unit,
    onAnalyzeWithCoach: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CharcoalSurface,
        contentColor = CrispWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            SectionLabel(
                text = "BILAN BIOMÉCANIQUE 3D",
                trailing = if (session.notes.isNotBlank()) session.notes.uppercase() else "ENREGISTRÉ"
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (breakdown.size > 1) "Séance Multi-Exercices" else session.exerciseType.labelFr,
                    style = MaterialTheme.typography.headlineMedium,
                    color = CrispWhite
                )
                if (session.detectedVariant.isNotBlank() && breakdown.size <= 1) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SignalOrangeDim,
                        border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.75f))
                    ) {
                        Text(
                            text = session.detectedVariant,
                            style = MaterialTheme.typography.labelMedium,
                            color = SignalOrange,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            IndustrialCard(containerColor = ObsidianBg) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricCell(
                        label = if (session.exerciseType.isIsometric && breakdown.size <= 1) "Maintien" else "Reps",
                        value = if (session.exerciseType.isIsometric && breakdown.size <= 1) "${session.durationSec}" else "${session.reps}",
                        unit = if (session.exerciseType.isIsometric && breakdown.size <= 1) "s" else "",
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "Calories",
                        value = String.format(Locale.US, "%.1f", session.caloriesKcal),
                        unit = "kcal",
                        valueColor = SignalOrange,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "Posture",
                        value = "${session.avgFormScore}",
                        unit = "/100",
                        valueColor = formScoreColor(session.avgFormScore),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "ROM",
                        value = "${session.avgRomPct}",
                        unit = "%",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // VBT Progressive Velocity & Estimated Max Reps Card
            val hasVbtData = !session.exerciseType.isIsometric &&
                (session.peakVelocityMps > 0.05f || session.estimatedMaxReps > 0 ||
                    (velocityTelemetry != null && (velocityTelemetry.repVelocitiesMps.isNotEmpty() || velocityTelemetry.completedSets.isNotEmpty())))
            if (hasVbtData) {
                Spacer(Modifier.height(10.dp))
                IndustrialCard(containerColor = ObsidianBg) {
                    SectionLabel(
                        text = "Vitesse & Estimation Rep Max (VBT)",
                        trailing = velocityTelemetry?.fatigueZone?.shortLabelFr ?: "VBT"
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricCell(
                            label = "Pic vitesse",
                            value = String.format(Locale.US, "%.2f", session.peakVelocityMps),
                            unit = "m/s",
                            modifier = Modifier.weight(1f)
                        )
                        MetricCell(
                            label = "Perte vit.",
                            value = "-${session.velocityLossPct}",
                            unit = "%",
                            valueColor = when {
                                session.velocityLossPct >= 40 -> FormFault
                                session.velocityLossPct >= 25 -> FormCaution
                                else -> FormGood
                            },
                            modifier = Modifier.weight(1f)
                        )
                        MetricCell(
                            label = "Max estimé",
                            value = if (session.estimatedMaxReps > 0) "~${session.estimatedMaxReps}" else "--",
                            unit = if (session.estimatedMaxReps > 0) "reps" else "",
                            valueColor = SignalOrange,
                            modifier = Modifier.weight(1f)
                        )
                        val rirVal = velocityTelemetry?.estimatedRir
                        MetricCell(
                            label = "RIR fin",
                            value = rirVal?.toString() ?: "--",
                            unit = "",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (velocityTelemetry != null && velocityTelemetry.repVelocitiesMps.size >= 2) {
                        Spacer(Modifier.height(10.dp))
                        VelocitySparklineChart(
                            velocities = velocityTelemetry.repVelocitiesMps,
                            baselineVelocity = maxOf(velocityTelemetry.sessionFreshBaselineMps, velocityTelemetry.baselineVelocityMps),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Rep 1", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                            Text("Ligne rouge : seuil échec estimé", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                            Text("Rep ${velocityTelemetry.repVelocitiesMps.size}", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                        }
                    }

                    if (velocityTelemetry != null && velocityTelemetry.completedSets.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HairlineDivider()
                        Spacer(Modifier.height(6.dp))
                        velocityTelemetry.completedSets.forEach { setSum ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Série ${setSum.setNumber} · ${setSum.repCount} reps",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CrispWhite
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "%.2f→%.2f m/s (-%d%%)%s",
                                        setSum.baselineVelocityMps,
                                        setSum.lastVelocityMps,
                                        setSum.velocityLossPct,
                                        if (setSum.estimatedMaxReps != null) " · Max ~${setSum.estimatedMaxReps}" else ""
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SignalOrange,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            if (breakdown.size > 1) {
                Spacer(Modifier.height(10.dp))
                IndustrialCard(containerColor = ObsidianBg) {
                    SectionLabel(text = "Exercices auto-détectés", trailing = "${breakdown.size} MOUVEMENTS")
                    Spacer(Modifier.height(6.dp))
                    breakdown.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.exercise.labelFr,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CrispWhite,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (item.exercise.isIsometric) "${item.holdSec}s" else "${item.reps} reps",
                                style = MaterialTheme.typography.labelMedium,
                                color = SteelGray
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = String.format(Locale.US, "%.1f kcal", item.caloriesKcal),
                                style = MaterialTheme.typography.labelMedium,
                                color = SignalOrange,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel(text = "Points techniques détectés")
            Spacer(Modifier.height(6.dp))
            if (session.topFaults.isEmpty()) {
                Text(
                    text = "Exécution propre et régulière, aucun défaut récurrent détecté.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FormGood
                )
            } else {
                session.topFaults.forEach { fault ->
                    Text(
                        text = "• $fault",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FormCaution,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            HairlineDivider()
            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onAnalyzeWithCoach,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = CrispWhite),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Analyser avec le Coach IA", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text("Nouvelle série", color = CrispWhite, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
