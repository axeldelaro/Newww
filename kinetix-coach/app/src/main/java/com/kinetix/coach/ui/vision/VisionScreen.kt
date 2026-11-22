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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.WorkoutSession
import com.kinetix.coach.progression.ProgressionDelta
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
import com.kinetix.coach.vision.DetectionStrictness
import com.kinetix.coach.vision.ExerciseVideoMapper
import com.kinetix.coach.vision.FormQuality
import com.kinetix.coach.vision.PoseLandmarkerHelper
import com.kinetix.coach.vision.SkeletonOverlay
import com.kinetix.coach.vision.VelocityFatigueZone
import com.kinetix.coach.vision.VelocityTelemetry
import java.util.Locale
import java.util.concurrent.Executors

private val REST_PRESETS_SEC = listOf(45, 60, 90, 120)
private val SETUP_PRESETS_SEC = listOf(0, 5, 8, 10)

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
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showChallengesSheet by remember { mutableStateOf(false) }
    var showExpandedVbt by remember { mutableStateOf(false) }
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

    // Subtle crisp pop + haptic feedback on every counted rep
    var repPulseActive by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.frame.repCount) {
        if (uiState.frame.repCount > 0 && !uiState.selectedExercise.isIsometric) {
            repPulseActive = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            kotlinx.coroutines.delay(140L)
            repPulseActive = false
        }
    }
    val repScale by animateFloatAsState(
        targetValue = if (repPulseActive) 1.13f else 1.0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 550f),
        label = "repPop"
    )
    val smoothRom by animateFloatAsState(
        targetValue = (uiState.frame.romPct / 100f).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
        label = "romFill"
    )

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
            // 1. Full-screen unobstructed live camera feed
            CameraPoseViewport(
                isFrontCamera = uiState.isFrontCamera,
                viewModel = viewModel
            )

            // 2. Sleek full-FPS Skeleton Overlay (minimalist by default; detailed angles optional in Settings)
            LiveSkeletonOverlayLayer(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )

            // Subtle top & bottom studio gradient scrims for effortless text legibility without heavy boxes
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(ObsidianBg.copy(alpha = 0.62f), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(172.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, ObsidianBg.copy(alpha = 0.72f))
                        )
                    )
            )

            // 3. Minimalist Single-Row Top Bar (ONLY 1 Exercise Pill + 2 Clean Round Buttons)
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Single Sleek Exercise + Variant Glass Pill
                    val detectedVariant = if (uiState.isTrackingStarted &&
                        uiState.frame.poseDetected &&
                        !uiState.isSettingUp &&
                        !uiState.isResting
                    ) {
                        uiState.frame.detectedVariantFr
                    } else null
                    val statusDotColor = when {
                        uiState.isSettingUp || uiState.frame.isStabilizing -> FormCaution
                        uiState.isResting -> SignalOrange
                        uiState.isIdlePreview -> if (uiState.frame.poseDetected) FormGood.copy(alpha = 0.85f) else SteelGray
                        uiState.frame.poseDetected -> FormGood
                        else -> SteelGray
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickable { showExercisePicker = true },
                        shape = RoundedCornerShape(24.dp),
                        color = ObsidianBg.copy(alpha = 0.68f),
                        border = BorderStroke(1.dp, CrispWhite.copy(alpha = 0.14f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                            )
                            Text(
                                text = uiState.selectedExercise.labelFr,
                                style = MaterialTheme.typography.titleSmall,
                                color = CrispWhite,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val badgeSuffix = when {
                                !detectedVariant.isNullOrBlank() -> detectedVariant
                                uiState.isAutoDetectEnabled -> "Auto"
                                else -> null
                            }
                            if (badgeSuffix != null) {
                                Text(
                                    text = "· $badgeSuffix",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (!detectedVariant.isNullOrBlank()) SignalOrange else SteelGray,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Changer d'exercice",
                                tint = CrispWhite.copy(alpha = 0.72f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    // Just 2 Minimal Translucent Circular Buttons: Flip Camera & Settings/Tools
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircleGlassButton(
                            onClick = { viewModel.toggleCamera() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cameraswitch,
                                contentDescription = "Changer de caméra",
                                tint = CrispWhite,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        CircleGlassButton(
                            onClick = { showSettingsSheet = true },
                            accentBorder = uiState.activeChallenge != null
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Réglages & outils",
                                tint = if (uiState.activeChallenge != null) SignalOrange else CrispWhite,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }

                // Active Camera-Verified Challenge Banner (only shown when a challenge is actively running)
                uiState.activeChallenge?.let { activeSnap ->
                    Spacer(Modifier.height(10.dp))
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

                // Sleek Minimal Rest Countdown Pill (only shown during inter-set rest)
                if (uiState.isResting && uiState.activeChallenge == null) {
                    Spacer(Modifier.height(10.dp))
                    RestCountdownOverlayPill(
                        remainingSec = uiState.restRemainingSec,
                        totalSec = uiState.restDurationSec,
                        nextSetNumber = uiState.currentSetNumber,
                        onAdd15s = { viewModel.addRestSeconds(15) },
                        onSkip = { viewModel.skipRest() }
                    )
                }
            }

            // 4. Giant Centered Minimalist Setup Countdown (ONLY after user taps Démarrer!)
            if (uiState.isSettingUp && !uiState.isResting && uiState.activeChallenge == null) {
                SetupCenterCountdown(
                    remainingSec = uiState.setupRemainingSec,
                    totalSec = maxOf(uiState.setupDelaySec, uiState.setupRemainingSec),
                    onAdd5s = { viewModel.addSetupSeconds(5) },
                    onStartNow = { viewModel.skipSetupCountdown() },
                    onCancel = { viewModel.cancelSetupCountdown() },
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // 5. Minimalist Floating Bottom Dock
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Conditional Minimal Coach Toast: COMPLETELY HIDDEN in IDLE preview AND during clean reps!
                val topCorrection = uiState.frame.corrections.firstOrNull()
                val framingWarn = if (uiState.frame.poseDetected) uiState.viewpointInfo.framingWarningFr else null
                val postureGateHint = if (uiState.isTrackingStarted &&
                    uiState.frame.poseDetected &&
                    !uiState.isSettingUp &&
                    !uiState.isResting &&
                    (uiState.frame.feedbackFr.startsWith("Mettez-vous") ||
                        uiState.frame.feedbackFr.startsWith("Allongez-vous") ||
                        uiState.frame.feedbackFr.startsWith("Redressez-vous") ||
                        uiState.frame.feedbackFr.startsWith("Saisissez") ||
                        uiState.frame.feedbackFr.startsWith("Suspendez-vous") ||
                        uiState.frame.feedbackFr.startsWith("Placez les mains") ||
                        uiState.frame.feedbackFr.startsWith("Penchez le buste") ||
                        uiState.frame.feedbackFr.startsWith("Prenez appui") ||
                        uiState.frame.feedbackFr.startsWith("Position détectée"))
                ) {
                    uiState.frame.feedbackFr
                } else null

                val toastData: Pair<String, Color>? = when {
                    uiState.errorMessage != null -> uiState.errorMessage!! to FormFault
                    uiState.isIdlePreview || uiState.isSettingUp || uiState.isResting -> null
                    uiState.frame.isStabilizing -> "Stabilisation…" to FormCaution
                    topCorrection != null -> {
                        val c = when (topCorrection.severity) {
                            FormQuality.FAULT -> FormFault
                            FormQuality.CAUTION -> FormCaution
                            FormQuality.GOOD -> FormGood
                        }
                        topCorrection.instructionFr to c
                    }
                    !framingWarn.isNullOrBlank() -> framingWarn to FormCaution
                    postureGateHint != null -> postureGateHint to SteelGray
                    !uiState.frame.poseDetected -> "Placez-vous face à la caméra" to SteelGray
                    else -> null
                }

                AnimatedVisibility(
                    visible = toastData != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    toastData?.let { (message, accent) ->
                        MinimalCoachToast(message = message, accentColor = accent)
                    }
                }

                if (uiState.isIdlePreview) {
                    // IDLE PREVIEW DOCK: Calm framing preview + 1-tap delay chip + unmistakable "Démarrer" CTA
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = ObsidianBg.copy(alpha = 0.78f),
                        border = BorderStroke(1.dp, CrispWhite.copy(alpha = 0.12f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 1-tap Installation Delay Chip (Off / 5s / 8s / 10s)
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = CrispWhite.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, CrispWhite.copy(alpha = 0.14f)),
                                modifier = Modifier.clickable { viewModel.cycleSetupDelay() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = "Délai d'installation",
                                        tint = if (uiState.setupDelaySec > 0) FormCaution else SteelGray,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Text(
                                        text = if (uiState.setupDelaySec == 0) "0s" else "${uiState.setupDelaySec}s",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = CrispWhite,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // Primary Explicit Start Button ("Démarrer") — NEVER starts on its own!
                            Surface(
                                shape = RoundedCornerShape(15.dp),
                                color = SignalOrange,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { viewModel.startWorkoutSet() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 13.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = CrispWhite,
                                        modifier = Modifier.size(21.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Démarrer",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = CrispWhite,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                } else if (!uiState.isSettingUp) {
                    // Optional Tap-to-Expand Live VBT Sparkline Strip (hidden by default to keep HUD ultra-clean!)
                    val vbt = uiState.frame.velocityTelemetry
                    AnimatedVisibility(
                        visible = showExpandedVbt && !uiState.selectedExercise.isIsometric && vbt.isSupported,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = ObsidianBg.copy(alpha = 0.80f),
                            border = BorderStroke(1.dp, CrispWhite.copy(alpha = 0.10f))
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                VelocityTelemetryStrip(
                                    vbt = vbt,
                                    currentReps = uiState.frame.repCount
                                )
                            }
                        }
                    }

                    // Main Compact Floating Glass Active Dock
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = ObsidianBg.copy(alpha = 0.78f),
                        border = BorderStroke(1.dp, CrispWhite.copy(alpha = 0.12f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Ultra-thin 3dp ROM progress line seamlessly integrated at the top edge of the dock
                            LinearProgressIndicator(
                                progress = { if (uiState.isResting) 0f else smoothRom },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp),
                                color = if (uiState.frame.romPct >= 80) FormGood else SignalOrange,
                                trackColor = CrispWhite.copy(alpha = 0.08f)
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // LEFT: Giant Crisp Tabular Counter (readable from 2.5m!)
                                Column {
                                    val primaryValue = if (uiState.selectedExercise.isIsometric) {
                                        String.format(Locale.US, "%.1fs", uiState.frame.isometricHoldSec)
                                    } else {
                                        uiState.frame.repCount.toString()
                                    }
                                    Text(
                                        text = primaryValue,
                                        fontSize = 44.sp,
                                        lineHeight = 46.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CrispWhite,
                                        modifier = Modifier.scale(repScale)
                                    )
                                    val subMetric = if (uiState.currentSetNumber > 1 && !uiState.selectedExercise.isIsometric) {
                                        "Série ${uiState.currentSetNumber} · ${uiState.sessionTotalReps + uiState.frame.repCount} tot."
                                    } else {
                                        "Série ${uiState.currentSetNumber}"
                                    }
                                    Text(
                                        text = subMetric,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SteelGray
                                    )
                                }

                                // CENTER: Minimalist 2-Line VBT Pill (Speed + Estimated Max Reps; tap to expand graph)
                                if (!uiState.selectedExercise.isIsometric && vbt.isSupported) {
                                    val zoneColor = fatigueZoneColor(vbt.fatigueZone)
                                    val displaySpeed = if (vbt.lastRepVelocityMps > 0.01f) vbt.lastRepVelocityMps else vbt.currentLiveVelocityMps
                                    val speedStr = if (displaySpeed > 0.01f) {
                                        String.format(Locale.US, "%.2f m/s", displaySpeed)
                                    } else {
                                        "— m/s"
                                    }
                                    val maxStr = when {
                                        vbt.estimatedMaxReps != null -> "MAX ~${vbt.estimatedMaxReps}"
                                        uiState.frame.repCount > 0 -> "Calib. ${uiState.frame.repCount}/3"
                                        vbt.estimatedFreshMaxReps != null -> "Réf. ~${vbt.estimatedFreshMaxReps}"
                                        else -> "Vitesse"
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = CrispWhite.copy(alpha = 0.06f),
                                        border = BorderStroke(
                                            1.dp,
                                            if (vbt.estimatedMaxReps != null) zoneColor.copy(alpha = 0.55f) else CrispWhite.copy(alpha = 0.08f)
                                        ),
                                        modifier = Modifier.clickable { showExpandedVbt = !showExpandedVbt }
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .clip(CircleShape)
                                                        .background(zoneColor)
                                                )
                                                Text(
                                                    text = speedStr,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = CrispWhite,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Spacer(Modifier.height(1.dp))
                                            Text(
                                                text = maxStr,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (vbt.estimatedMaxReps != null) zoneColor else SteelGray,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }

                                // RIGHT: Just 2 Crisp Action Pills ("Repos" & "Fin")
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(13.dp),
                                        color = CrispWhite.copy(alpha = 0.09f),
                                        border = BorderStroke(1.dp, CrispWhite.copy(alpha = 0.16f)),
                                        modifier = Modifier.clickable { viewModel.completeSetAndStartRest() }
                                    ) {
                                        Text(
                                            text = "Repos",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = CrispWhite,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(13.dp),
                                        color = SignalOrange,
                                        modifier = Modifier.clickable { viewModel.finishSession() }
                                    ) {
                                        Text(
                                            text = "Fin",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = CrispWhite,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
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

    // Clean 16-Exercise Selector Sheet (with integrated Auto / Fixe toggle right at top)
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
                    text = "Choisir un exercice",
                    trailing = "${ExerciseType.entries.size} EXERCICES"
                )
                Spacer(Modifier.height(10.dp))

                // Clean Auto vs Fixe Toggle Card
                IndustrialCard(containerColor = ObsidianBg) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Détection automatique",
                                style = MaterialTheme.typography.titleSmall,
                                color = CrispWhite,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (uiState.isAutoDetectEnabled) {
                                    "Reconnaît l'exercice tout seul (sans perdre la 1re rep)"
                                } else {
                                    "Verrouillé sur ${uiState.selectedExercise.labelFr}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = SteelGray
                            )
                        }
                        Switch(
                            checked = uiState.isAutoDetectEnabled,
                            onCheckedChange = { viewModel.toggleAutoDetect() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CrispWhite,
                                checkedTrackColor = SignalOrange,
                                uncheckedThumbColor = SteelGray,
                                uncheckedTrackColor = ElevatedSurface
                            )
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(430.dp)
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
                                        text = ex.musclesFr,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SignalOrange
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = SignalOrangeDim,
                                    border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.65f)),
                                    modifier = Modifier.clickable {
                                        showExercisePicker = false
                                        demoPreviewExercise = ex
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PlayCircleOutline,
                                            contentDescription = "Voir la démo",
                                            tint = SignalOrange,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "Démo",
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

    // Sleek Quick Settings & Pro Tools Sheet (keeps the camera 100% clean while preserving all pro features!)
    if (showSettingsSheet) {
        VisionSettingsSheet(
            uiState = uiState,
            onDismiss = { showSettingsSheet = false },
            onOpenChallenges = {
                showSettingsSheet = false
                showChallengesSheet = true
            },
            onOpenDemo = {
                showSettingsSheet = false
                demoPreviewExercise = uiState.selectedExercise
            },
            onResetSession = {
                showSettingsSheet = false
                viewModel.resetSession()
            },
            onToggleAuto = { viewModel.toggleAutoDetect() },
            onToggleVoice = { viewModel.toggleVoice() },
            onToggleDetailedAngles = { viewModel.toggleDetailedAngles() },
            onSelectSetupDelay = { viewModel.setSetupDelay(it) },
            onTriggerSetupNow = {
                showSettingsSheet = false
                viewModel.startWorkoutSet()
            },
            onSelectRestDuration = { viewModel.setRestDuration(it) },
            onSelectStrictness = { viewModel.setDetectionStrictness(it) }
        )
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
            progressionDelta = uiState.completedProgressionDelta,
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
            progressionDelta = uiState.completedProgressionDelta,
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
private fun CircleGlassButton(
    onClick: () -> Unit,
    accentBorder: Boolean = false,
    content: @Composable () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = ObsidianBg.copy(alpha = 0.68f),
        border = BorderStroke(
            width = 1.dp,
            color = if (accentBorder) SignalOrange.copy(alpha = 0.80f) else CrispWhite.copy(alpha = 0.14f)
        ),
        modifier = Modifier.size(42.dp)
    ) {
        IconButton(onClick = onClick) {
            content()
        }
    }
}

@Composable
private fun SetupCenterCountdown(
    remainingSec: Int,
    totalSec: Int,
    onAdd5s: () -> Unit,
    onStartNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val targetProgress = if (totalSec > 0) {
        (remainingSec.toFloat() / totalSec.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val smoothProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 220f),
        label = "setupRing"
    )

    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Giant readable floating circular ring (visible from 3m away without hiding the body)
        Surface(
            shape = CircleShape,
            color = ObsidianBg.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, CrispWhite.copy(alpha = 0.10f)),
            modifier = Modifier.size(144.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { smoothProgress },
                    modifier = Modifier.size(132.dp),
                    color = FormCaution,
                    trackColor = CrispWhite.copy(alpha = 0.14f),
                    strokeWidth = 5.dp,
                    strokeCap = StrokeCap.Round
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$remainingSec",
                        fontSize = 58.sp,
                        lineHeight = 60.sp,
                        fontWeight = FontWeight.Bold,
                        color = CrispWhite
                    )
                    Text(
                        text = "PRÊT",
                        style = MaterialTheme.typography.labelSmall,
                        color = FormCaution,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Concise 1-line placement hint pill
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = ObsidianBg.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, CrispWhite.copy(alpha = 0.12f))
        ) {
            Text(
                text = "Placez-vous · comptage en pause",
                style = MaterialTheme.typography.labelMedium,
                color = CrispWhite,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }

        // Clean action pills: Annuler, +5s & Commencer
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ObsidianBg.copy(alpha = 0.76f),
                border = BorderStroke(1.dp, CrispWhite.copy(alpha = 0.16f)),
                modifier = Modifier.clickable(onClick = onCancel)
            ) {
                Text(
                    text = "Annuler",
                    style = MaterialTheme.typography.labelLarge,
                    color = SteelGray,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ObsidianBg.copy(alpha = 0.76f),
                border = BorderStroke(1.dp, CrispWhite.copy(alpha = 0.18f)),
                modifier = Modifier.clickable(onClick = onAdd5s)
            ) {
                Text(
                    text = "+5s",
                    style = MaterialTheme.typography.labelLarge,
                    color = CrispWhite,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = FormGood,
                modifier = Modifier.clickable(onClick = onStartNow)
            ) {
                Text(
                    text = "Commencer",
                    style = MaterialTheme.typography.labelLarge,
                    color = ObsidianBg,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun MinimalCoachToast(
    message: String,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = ObsidianBg.copy(alpha = 0.76f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = CrispWhite,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
        shape = RoundedCornerShape(18.dp),
        color = ObsidianBg.copy(alpha = 0.80f),
        border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
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
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text(
                            text = "Repos · Série $nextSetNumber",
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelGray,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.titleLarge,
                            color = CrispWhite,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CrispWhite.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, CrispWhite.copy(alpha = 0.16f)),
                        modifier = Modifier.clickable(onClick = onAdd15s)
                    ) {
                        Text(
                            text = "+15s",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrispWhite,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SignalOrange,
                        modifier = Modifier.clickable(onClick = onSkip)
                    ) {
                        Text(
                            text = "Passer",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrispWhite,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = SignalOrange,
                trackColor = CrispWhite.copy(alpha = 0.08f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisionSettingsSheet(
    uiState: VisionUiState,
    onDismiss: () -> Unit,
    onOpenChallenges: () -> Unit,
    onOpenDemo: () -> Unit,
    onResetSession: () -> Unit,
    onToggleAuto: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleDetailedAngles: () -> Unit,
    onSelectSetupDelay: (Int) -> Unit,
    onTriggerSetupNow: () -> Unit,
    onSelectRestDuration: (Int) -> Unit,
    onSelectStrictness: (DetectionStrictness) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CharcoalSurface,
        contentColor = CrispWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionLabel(
                text = "Réglages caméra",
                trailing = uiState.selectedExercise.labelFr.uppercase()
            )

            // 1. Clean 3-Tile Quick Action Strip (Challenges, Demo, Reset)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsQuickActionTile(
                    icon = Icons.Default.EmojiEvents,
                    label = if (uiState.activeChallenge != null) "Défi actif" else "Défis",
                    highlighted = uiState.activeChallenge != null,
                    onClick = onOpenChallenges,
                    modifier = Modifier.weight(1f)
                )
                SettingsQuickActionTile(
                    icon = Icons.Default.PlayCircleOutline,
                    label = "Démo",
                    highlighted = false,
                    onClick = onOpenDemo,
                    modifier = Modifier.weight(1f)
                )
                SettingsQuickActionTile(
                    icon = Icons.Default.Refresh,
                    label = "Réinitialiser",
                    highlighted = false,
                    onClick = onResetSession,
                    modifier = Modifier.weight(1f)
                )
            }

            // 2. Core Toggles (Auto Detect, Voice Coach, Expert Skeleton Angles)
            IndustrialCard(containerColor = ObsidianBg) {
                SettingsToggleRow(
                    title = "Détection automatique",
                    subtitle = "Reconnaît l'exercice et récupère la 1re rep",
                    checked = uiState.isAutoDetectEnabled,
                    onCheckedChange = { onToggleAuto() }
                )
                HairlineDivider()
                SettingsToggleRow(
                    title = "Coach vocal",
                    subtitle = "Compte les répétitions et alerte sur les défauts",
                    checked = uiState.isVoiceEnabled,
                    onCheckedChange = { onToggleVoice() }
                )
                HairlineDivider()
                SettingsToggleRow(
                    title = "Angles sur le squelette",
                    subtitle = "Mode expert : affiche les degrés et arcs en direct",
                    checked = uiState.showDetailedAngles,
                    onCheckedChange = { onToggleDetailedAngles() }
                )
            }

            // 3. Hands-Free Setup Countdown Preset + Instant Trigger
            IndustrialCard(containerColor = ObsidianBg) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Délai d'installation",
                            style = MaterialTheme.typography.titleSmall,
                            color = CrispWhite,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Pause le comptage le temps de vous placer",
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelGray
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SignalOrangeDim,
                        border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.7f)),
                        modifier = Modifier.clickable(onClick = onTriggerSetupNow)
                    ) {
                        Text(
                            text = "Lancer",
                            style = MaterialTheme.typography.labelMedium,
                            color = SignalOrange,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SETUP_PRESETS_SEC.forEach { sec ->
                        val active = uiState.setupDelaySec == sec
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) SignalOrange else ElevatedSurface,
                            border = BorderStroke(1.dp, if (active) SignalOrange else BorderHairline),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelectSetupDelay(sec) }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (sec == 0) "Off" else "${sec}s",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) CrispWhite else SteelGray,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 4. Rest Timer Preset
            IndustrialCard(containerColor = ObsidianBg) {
                Text(
                    text = "Temps de repos entre séries",
                    style = MaterialTheme.typography.titleSmall,
                    color = CrispWhite,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    REST_PRESETS_SEC.forEach { sec ->
                        val active = uiState.restDurationSec == sec
                        val label = if (sec >= 60) {
                            val m = sec / 60
                            val s = sec % 60
                            if (s == 0) "${m}m" else "${m}m${s}"
                        } else "${sec}s"
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (active) SignalOrange else ElevatedSurface,
                            border = BorderStroke(1.dp, if (active) SignalOrange else BorderHairline),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelectRestDuration(sec) }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) CrispWhite else SteelGray,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // 5. Counting Strictness (Adaptive vs Strict)
            IndustrialCard(containerColor = ObsidianBg) {
                Text(
                    text = "Exigence d'amplitude",
                    style = MaterialTheme.typography.titleSmall,
                    color = CrispWhite,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isAdaptive = uiState.detectionStrictness == DetectionStrictness.ADAPTIVE
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isAdaptive) SignalOrange else ElevatedSurface,
                        border = BorderStroke(1.dp, if (isAdaptive) SignalOrange else BorderHairline),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectStrictness(DetectionStrictness.ADAPTIVE) }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = "Adaptatif",
                                style = MaterialTheme.typography.labelMedium,
                                color = CrispWhite,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Fluide & naturel",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isAdaptive) CrispWhite.copy(alpha = 0.85f) else SteelGray
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (!isAdaptive) SignalOrange else ElevatedSurface,
                        border = BorderStroke(1.dp, if (!isAdaptive) SignalOrange else BorderHairline),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectStrictness(DetectionStrictness.STRICT) }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = "Strict",
                                style = MaterialTheme.typography.labelMedium,
                                color = CrispWhite,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Amplitude complète",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!isAdaptive) CrispWhite.copy(alpha = 0.85f) else SteelGray
                            )
                        }
                    }
                }
            }

            // 6. Discreet Telemetry Footer (keeps technical metrics accessible without cluttering the camera!)
            val totalKcal = uiState.sessionTotalCaloriesKcal + uiState.currentSetCaloriesKcal
            val primaryDeg = uiState.frame.angleBadges.firstOrNull()?.degrees
            val angleStr = if (uiState.frame.poseDetected && primaryDeg != null) "${primaryDeg}°" else "—"
            val mode3d = if (uiState.viewpointInfo.is3DActive) "3D" else "2D"
            Text(
                text = "Télémétrie : $angleStr ($mode3d) · Posture ${uiState.frame.formScore}% · ${String.format(Locale.US, "%.1f", totalKcal)} kcal · ${uiState.delegateLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = SteelGray,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingsQuickActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (highlighted) SignalOrangeDim else ObsidianBg,
        border = BorderStroke(1.dp, if (highlighted) SignalOrange else BorderHairline),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlighted) SignalOrange else CrispWhite,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (highlighted) SignalOrange else CrispWhite,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = CrispWhite,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = SteelGray
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CrispWhite,
                checkedTrackColor = SignalOrange,
                uncheckedThumbColor = SteelGray,
                uncheckedTrackColor = ElevatedSurface
            )
        )
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
    progressionDelta: ProgressionDelta? = null,
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

            if (progressionDelta != null && (progressionDelta.xpGained > 0 || progressionDelta.hasCelebration)) {
                Spacer(Modifier.height(10.dp))
                val borderAccent = if (progressionDelta.leveledUpTo != null) FormGood else SignalOrange.copy(alpha = 0.75f)
                IndustrialCard(containerColor = ObsidianBg, borderColor = borderAccent) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    progressionDelta.leveledUpTo != null -> "Niveau ${progressionDelta.leveledUpTo} atteint !"
                                    progressionDelta.completedQuestTitles.isNotEmpty() -> "Quête du jour validée"
                                    else -> "Progression enregistrée"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = if (progressionDelta.leveledUpTo != null) FormGood else CrispWhite,
                                fontWeight = FontWeight.Bold
                            )
                            val sub = buildString {
                                if (progressionDelta.completedQuestTitles.isNotEmpty()) {
                                    append(progressionDelta.completedQuestTitles.joinToString(" · "))
                                }
                                if (progressionDelta.newlyUnlockedBadges.isNotEmpty()) {
                                    if (isNotEmpty()) append(" · ")
                                    append("Badge : ${progressionDelta.newlyUnlockedBadges.first().titleFr}")
                                }
                                if (progressionDelta.weeklyStreakBonusAwarded) {
                                    if (isNotEmpty()) append(" · ")
                                    append("Bonus 7/7 (+150 XP)")
                                }
                                if (isEmpty()) {
                                    append("Attributs & palmarès mis à jour")
                                }
                            }
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = SteelGray,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = FormGood.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, FormGood.copy(alpha = 0.7f))
                        ) {
                            Text(
                                text = "+${progressionDelta.xpGained} XP",
                                style = MaterialTheme.typography.labelLarge,
                                color = FormGood,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
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
                Text("Retour caméra", color = CrispWhite, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
