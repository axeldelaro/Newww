package com.kinetix.coach.ui.vision

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
import com.kinetix.coach.vision.ExerciseVideoMapper
import com.kinetix.coach.vision.FormQuality
import com.kinetix.coach.vision.PoseLandmarkerHelper
import com.kinetix.coach.vision.SkeletonOverlay
import java.util.concurrent.Executors

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

    var showExercisePicker by remember { mutableStateOf(false) }
    var demoPreviewExercise by remember { mutableStateOf<ExerciseType?>(null) }

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

            // Live Skeleton + Angle Badges Overlay
            SkeletonOverlay(
                frame = uiState.frame,
                imageWidth = uiState.imageWidth,
                imageHeight = uiState.imageHeight,
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
                    // Exercise Selector Trigger
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showExercisePicker = true },
                        shape = RoundedCornerShape(10.dp),
                        color = ObsidianBg.copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, BorderHairline)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = uiState.selectedExercise.labelFr,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CrispWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${uiState.selectedExercise.cameraAngleHint} · ${uiState.delegateLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SteelGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Changer d'exercice",
                                tint = SignalOrange
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
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Voir la démo vidéo",
                                tint = SignalOrange,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "Démo",
                                style = MaterialTheme.typography.labelMedium,
                                color = CrispWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    // Voice Toggle
                    HudIconButton(
                        onClick = { viewModel.toggleVoice() }
                    ) {
                        Icon(
                            imageVector = if (uiState.isVoiceEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Coach vocal",
                            tint = if (uiState.isVoiceEnabled) SignalOrange else SteelGray,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    // Flip Camera
                    HudIconButton(
                        onClick = { viewModel.toggleCamera() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Changer de caméra",
                            tint = CrispWhite,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Live Posture Feedback Strip
                val statusColor = when (uiState.frame.formQuality) {
                    FormQuality.GOOD -> FormGood
                    FormQuality.CAUTION -> FormCaution
                    FormQuality.FAULT -> FormFault
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ObsidianBg.copy(alpha = 0.86f),
                    border = BorderStroke(1.dp, if (uiState.frame.poseDetected) statusColor else BorderHairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = uiState.errorMessage ?: uiState.frame.feedbackFr,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CrispWhite,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(10.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = statusColor.copy(alpha = 0.20f),
                            border = BorderStroke(1.dp, statusColor)
                        ) {
                            Text(
                                text = "${uiState.frame.formScore}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Active Smart Rest Countdown Overlay Pill
                if (uiState.isResting) {
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

            // Bottom Telemetry HUD (with Multi-Set & Rest Timer Controls)
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
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
                }
            }
        }
    }

    // 16-Exercise Selector Sheet (with inline "Voir la vidéo" trigger on every exercise)
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
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(450.dp)
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

    // Session Summary Bottom Sheet with 1-tap Coach AI Biomechanical Debrief
    uiState.completedSummary?.let { summary ->
        SessionSummarySheet(
            session = summary,
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
private fun RestCountdownOverlayPill(
    remainingSec: Int,
    totalSec: Int,
    nextSetNumber: Int,
    onAdd15s: () -> Unit,
    onSkip: () -> Unit
) {
    val mins = remainingSec / 60
    val secs = remainingSec % 60
    val formattedTime = String.format("%02d:%02d", mins, secs)
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
            SectionLabel(text = "VISION BIOMÉCANIQUE HORS-LIGNE")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Accès caméra requis",
                style = MaterialTheme.typography.headlineMedium,
                color = CrispWhite
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "KINETIX Vision analyse vos 33 repères articulaires 100% en local sur votre appareil via MediaPipe. Aucune image n'est enregistrée ni transmise.",
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
            onResult = { pts, w, h, ts -> viewModel.onPoseLandmarks(pts, w, h, ts) },
            onError = { msg -> viewModel.reportError(msg) }
        )
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analyzer = ImageAnalysis.Builder()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSummarySheet(
    session: WorkoutSession,
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            SectionLabel(
                text = "BILAN BIOMÉCANIQUE",
                trailing = if (session.notes.isNotBlank()) session.notes.uppercase() else "ENREGISTRÉ"
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = session.exerciseType.labelFr,
                style = MaterialTheme.typography.headlineMedium,
                color = CrispWhite
            )
            Spacer(Modifier.height(14.dp))

            IndustrialCard(containerColor = ObsidianBg) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricCell(
                        label = if (session.exerciseType.isIsometric) "Maintien" else "Répétitions",
                        value = if (session.exerciseType.isIsometric) "${session.durationSec}" else "${session.reps}",
                        unit = if (session.exerciseType.isIsometric) "sec" else "reps",
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
                        label = "ROM Moy.",
                        value = "${session.avgRomPct}",
                        unit = "%",
                        modifier = Modifier.weight(1f)
                    )
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
