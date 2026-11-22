package com.kinetix.coach.ui.vision

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kinetix.coach.data.ChallengeRecord
import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.progression.ProgressionDelta
import com.kinetix.coach.ui.components.FilterChipPill
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
import com.kinetix.coach.ui.theme.FormGood
import com.kinetix.coach.ui.theme.ObsidianBg
import com.kinetix.coach.ui.theme.SignalOrange
import com.kinetix.coach.ui.theme.SignalOrangeDim
import com.kinetix.coach.ui.theme.SteelGray
import com.kinetix.coach.vision.BipGoTempo
import com.kinetix.coach.vision.ChallengeConfig
import com.kinetix.coach.vision.ChallengePhase
import com.kinetix.coach.vision.ChallengeResult
import com.kinetix.coach.vision.ChallengeSnapshot
import com.kinetix.coach.vision.ChallengeType
import com.kinetix.coach.vision.WorkoutMusicTrack
import com.kinetix.coach.vision.formatChallengeClock
import java.util.Locale

private val BIP_GO_CYCLE_PRESETS = listOf(15, 25, 40, 0)
private val PLANK_TARGET_PRESETS = listOf(60, 90, 120, 180, 300, 0)
private val TARGET_REPS_PRESETS = listOf(25, 50, 100)
private val AMRAP_DURATION_PRESETS = listOf(60, 120, 180)
private val EMOM_MINUTE_PRESETS = listOf(5, 10)
private val EMOM_REPS_PRESETS = listOf(8, 10, 12, 15)

@Composable
fun ActiveChallengeBanner(
    snapshot: ChallengeSnapshot,
    selectedTrack: WorkoutMusicTrack,
    customMusicName: String?,
    isMusicMuted: Boolean,
    onToggleMute: () -> Unit,
    onCycleTrack: () -> Unit,
    onPickCustomAudio: () -> Unit,
    onStopChallenge: () -> Unit
) {
    val accent = when {
        snapshot.phase == ChallengePhase.PAUSED_POSTURE -> FormCaution
        snapshot.phase == ChallengePhase.REST -> FormGood
        snapshot.phase == ChallengePhase.HOLDING -> FormGood
        snapshot.phase == ChallengePhase.BIP_GO -> SignalOrange
        snapshot.isUrgent -> SignalOrange
        else -> SignalOrange
    }

    val trackShortLabel = when {
        isMusicMuted -> "Muet"
        selectedTrack == WorkoutMusicTrack.CUSTOM_LOCAL -> customMusicName?.take(14) ?: "MP3 Local"
        selectedTrack == WorkoutMusicTrack.NONE -> "Bips seuls"
        else -> selectedTrack.labelFr.replace(" BPM", "")
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = ObsidianBg.copy(alpha = 0.94f),
        border = BorderStroke(1.5.dp, accent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            // Header row: Challenge title + sync badge + primary tabular metric
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.EmojiEvents,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = snapshot.config.type.shortTitleFr.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelGray,
                            fontWeight = FontWeight.Bold
                        )
                        snapshot.syncStateFr?.let { syncLabel ->
                            val syncColor = when {
                                syncLabel.contains("✓") -> FormGood
                                syncLabel.contains("MANQUÉ") || syncLabel.contains("RETARD") || syncLabel.contains("PAUSE") -> FormCaution
                                else -> accent
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = syncColor.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, syncColor.copy(alpha = 0.7f))
                            ) {
                                Text(
                                    text = syncLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = syncColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = snapshot.phaseLabelFr,
                        style = MaterialTheme.typography.titleMedium,
                        color = CrispWhite,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = snapshot.subtitleFr,
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = snapshot.primaryMetricLabelFr,
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelGray
                    )
                    Text(
                        text = snapshot.primaryMetricValue,
                        style = MaterialTheme.typography.titleLarge,
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { snapshot.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accent,
                trackColor = ElevatedSurface
            )

            Spacer(Modifier.height(7.dp))

            // Compact music quick-switcher + 1-tap Stop button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ElevatedSurface,
                        border = BorderStroke(1.dp, BorderHairline),
                        modifier = Modifier.clickable(onClick = onToggleMute)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isMusicMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.Filled.MusicNote,
                                contentDescription = "Couper ou activer la musique",
                                tint = if (isMusicMuted) SteelGray else SignalOrange,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = trackShortLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = CrispWhite,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ElevatedSurface,
                        border = BorderStroke(1.dp, BorderHairline),
                        modifier = Modifier.clickable(onClick = onCycleTrack)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Piste suivante",
                            tint = CrispWhite,
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .size(15.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ElevatedSurface,
                        border = BorderStroke(1.dp, BorderHairline),
                        modifier = Modifier.clickable(onClick = onPickCustomAudio)
                    ) {
                        Text(
                            text = "MP3",
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelGray,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SignalOrangeDim,
                    border = BorderStroke(1.dp, SignalOrange),
                    modifier = Modifier.clickable(onClick = onStopChallenge)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Arrêter le défi",
                            tint = SignalOrange,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "Terminer",
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChallengesAndMusicSheet(
    currentExercise: ExerciseType,
    selectedTrack: WorkoutMusicTrack,
    customMusicUri: String?,
    customMusicName: String?,
    records: List<ChallengeRecord>,
    onSelectTrack: (WorkoutMusicTrack) -> Unit,
    onPickCustomAudio: () -> Unit,
    onLaunchChallenge: (ChallengeConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedType by remember { mutableStateOf(ChallengeType.BIP_AND_GO_PUSHUPS) }
    var chosenExercise by remember {
        mutableStateOf(if (currentExercise.isIsometric) ExerciseType.PUSHUP else currentExercise)
    }
    var bipGoTempo by remember { mutableStateOf(BipGoTempo.MILITARY) }
    var bipGoCycles by remember { mutableIntStateOf(25) }
    var plankTargetSec by remember { mutableIntStateOf(90) }
    var targetReps by remember { mutableIntStateOf(50) }
    var amrapSec by remember { mutableIntStateOf(120) }
    var emomMins by remember { mutableIntStateOf(5) }
    var emomReps by remember { mutableIntStateOf(10) }

    val effectiveExercise = selectedType.fixedExercise ?: when (selectedType) {
        ChallengeType.LUC_LEGER_CADENCE ->
            if (chosenExercise in ChallengeType.LUC_LEGER_EXERCISES) chosenExercise else ExerciseType.PUSHUP
        else -> chosenExercise
    }

    fun bestRecord(type: ChallengeType, ex: ExerciseType): ChallengeRecord? {
        val matching = records.filter { it.challengeId == type.id && it.exerciseId == ex.id }
        return if (type.lowerIsBetter) matching.minByOrNull { it.scoreValue }
        else matching.maxByOrNull { it.scoreValue }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = CharcoalSurface,
        contentColor = CrispWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp)
        ) {
            SectionLabel(
                text = "DÉFIS CAMÉRA & MUSIQUE HORS-LIGNE",
                trailing = "7 MODES"
            )
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Challenge selector cards
                ChallengeType.entries.forEach { type ->
                    val isSelected = selectedType == type
                    val exForPr = type.fixedExercise ?: effectiveExercise
                    val pr = bestRecord(type, exForPr)

                    IndustrialCard(
                        containerColor = if (isSelected) ElevatedSurface else ObsidianBg,
                        borderColor = if (isSelected) SignalOrange else BorderHairline,
                        onClick = {
                            selectedType = type
                            if (type == ChallengeType.PLANK_MUSIC_HOLD && selectedTrack == WorkoutMusicTrack.NONE) {
                                onSelectTrack(WorkoutMusicTrack.FOCUS_SYNTH)
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = type.titleFr,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CrispWhite,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = type.descriptionFr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SteelGray
                                )
                            }
                            if (pr != null) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = SignalOrangeDim,
                                    border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.7f))
                                ) {
                                    Text(
                                        text = "PR · ${pr.scoreLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SignalOrange,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HairlineDivider()

                // 2. Parameters for selected challenge
                IndustrialCard(containerColor = ObsidianBg) {
                    SectionLabel(
                        text = "Réglages · ${selectedType.shortTitleFr}",
                        trailing = effectiveExercise.labelFr.uppercase()
                    )
                    Spacer(Modifier.height(10.dp))

                    if (selectedType.fixedExercise == null) {
                        val exOptions = if (selectedType == ChallengeType.LUC_LEGER_CADENCE) {
                            ChallengeType.LUC_LEGER_EXERCISES
                        } else {
                            ChallengeType.DYNAMIC_EXERCISES
                        }
                        Text(
                            text = "EXERCICE",
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelGray
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            exOptions.forEach { ex ->
                                FilterChipPill(
                                    label = ex.labelFr,
                                    selected = effectiveExercise == ex,
                                    onClick = { chosenExercise = ex }
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    when (selectedType) {
                        ChallengeType.BIP_AND_GO_PUSHUPS -> {
                            Text("TEMPO DE CADENCE", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BipGoTempo.entries.forEach { t ->
                                    FilterChipPill(
                                        label = t.labelFr,
                                        selected = bipGoTempo == t,
                                        onClick = { bipGoTempo = t }
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("OBJECTIF CYCLES", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BIP_GO_CYCLE_PRESETS.forEach { c ->
                                    FilterChipPill(
                                        label = if (c == 0) "Infini" else "$c cycles",
                                        selected = bipGoCycles == c,
                                        onClick = { bipGoCycles = c }
                                    )
                                }
                            }
                        }

                        ChallengeType.LUC_LEGER_CADENCE -> {
                            Text(
                                text = "Palier 1 (3,6s/rep) → accélère de -0,25s toutes les 5 reps jusqu'au Palier 12 (1,1s/rep).",
                                style = MaterialTheme.typography.bodySmall,
                                color = SteelGray
                            )
                        }

                        ChallengeType.PLANK_MUSIC_HOLD -> {
                            Text("DURÉE CIBLE GAINAGE", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PLANK_TARGET_PRESETS.forEach { sec ->
                                    FilterChipPill(
                                        label = if (sec == 0) "Infini" else "${sec}s",
                                        selected = plankTargetSec == sec,
                                        onClick = { plankTargetSec = sec }
                                    )
                                }
                            }
                        }

                        ChallengeType.TARGET_REPS_RACE -> {
                            Text("OBJECTIF RÉPÉTITIONS", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TARGET_REPS_PRESETS.forEach { goal ->
                                    FilterChipPill(
                                        label = "$goal reps",
                                        selected = targetReps == goal,
                                        onClick = { targetReps = goal }
                                    )
                                }
                            }
                        }

                        ChallengeType.AMRAP_CHRONO -> {
                            Text("DURÉE AMRAP", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AMRAP_DURATION_PRESETS.forEach { sec ->
                                    FilterChipPill(
                                        label = "${sec / 60} min",
                                        selected = amrapSec == sec,
                                        onClick = { amrapSec = sec }
                                    )
                                }
                            }
                        }

                        ChallengeType.TABATA_HIIT -> {
                            Text(
                                text = "Protocole officiel : 20s d'effort maximal / 10s de repos × 8 tours (4 minutes total).",
                                style = MaterialTheme.typography.bodySmall,
                                color = SteelGray
                            )
                        }

                        ChallengeType.EMOM_CHALLENGE -> {
                            Text("DURÉE EMOM", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                EMOM_MINUTE_PRESETS.forEach { m ->
                                    FilterChipPill(
                                        label = "$m min",
                                        selected = emomMins == m,
                                        onClick = { emomMins = m }
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text("REPS PAR MINUTE", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                EMOM_REPS_PRESETS.forEach { r ->
                                    FilterChipPill(
                                        label = "$r reps/min",
                                        selected = emomReps == r,
                                        onClick = { emomReps = r }
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Offline Music & Custom Phone MP3/M4A Selector
                IndustrialCard(containerColor = ObsidianBg) {
                    SectionLabel(
                        text = "Musique d'entraînement",
                        trailing = "100% HORS-LIGNE"
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        WorkoutMusicTrack.entries.forEach { track ->
                            val isSel = selectedTrack == track
                            FilterChipPill(
                                label = track.labelFr,
                                selected = isSel,
                                onClick = {
                                    if (track == WorkoutMusicTrack.CUSTOM_LOCAL && customMusicUri.isNullOrBlank()) {
                                        onPickCustomAudio()
                                    } else {
                                        onSelectTrack(track)
                                    }
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AudioFile,
                                contentDescription = null,
                                tint = if (customMusicUri != null) SignalOrange else SteelGray,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = customMusicName ?: selectedTrack.subtitleFr,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (customMusicUri != null) CrispWhite else SteelGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onPickCustomAudio,
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.75f))
                        ) {
                            Text(
                                text = if (customMusicUri != null) "Changer MP3" else "Importer MP3",
                                style = MaterialTheme.typography.labelSmall,
                                color = CrispWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val cfg = ChallengeConfig(
                        type = selectedType,
                        exercise = effectiveExercise,
                        bipGoTempo = bipGoTempo,
                        bipGoTargetCycles = bipGoCycles,
                        plankTargetSec = plankTargetSec,
                        targetRepsGoal = targetReps,
                        amrapDurationSec = amrapSec,
                        emomMinutes = emomMins,
                        emomRepsPerMin = emomReps,
                        musicTrack = selectedTrack
                    )
                    onLaunchChallenge(cfg)
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SignalOrange,
                    contentColor = CrispWhite
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "LANCER LE DÉFI",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeResultSheet(
    result: ChallengeResult,
    progressionDelta: ProgressionDelta? = null,
    onReplay: () -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = CharcoalSurface,
        contentColor = CrispWhite
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (progressionDelta?.rankedUpTo != null) {
                        "ÉPREUVE DE RANG VALIDÉE"
                    } else if (result.completedGoal) {
                        "DÉFI ACCOMPLI"
                    } else {
                        "BILAN DU DÉFI"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (progressionDelta?.rankedUpTo != null) FormGood else SteelGray,
                    fontWeight = FontWeight.Bold
                )
                if (result.isNewRecord) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = FormGood.copy(alpha = 0.22f),
                        border = BorderStroke(1.dp, FormGood)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.EmojiEvents,
                                contentDescription = null,
                                tint = FormGood,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "NOUVEAU RECORD",
                                style = MaterialTheme.typography.labelSmall,
                                color = FormGood,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = result.config.type.titleFr,
                style = MaterialTheme.typography.headlineMedium,
                color = CrispWhite
            )
            Text(
                text = result.config.effectiveExercise().labelFr,
                style = MaterialTheme.typography.labelMedium,
                color = SignalOrange
            )

            if (progressionDelta != null && (progressionDelta.rankedUpTo != null || progressionDelta.xpGained > 0)) {
                Spacer(Modifier.height(10.dp))
                val rankColor = progressionDelta.rankedUpTo?.let { Color(it.accentHex) } ?: SignalOrange
                IndustrialCard(containerColor = ObsidianBg, borderColor = rankColor) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = progressionDelta.rankedUpTo?.let {
                                    "Promotion Rang ${it.code} · ${it.labelFr}"
                                } ?: "Progression athlète",
                                style = MaterialTheme.typography.titleSmall,
                                color = rankColor,
                                fontWeight = FontWeight.Bold
                            )
                            val detail = buildString {
                                if (progressionDelta.leveledUpTo != null) append("Niveau ${progressionDelta.leveledUpTo} atteint")
                                if (progressionDelta.completedQuestTitles.isNotEmpty()) {
                                    if (isNotEmpty()) append(" · ")
                                    append("Quête validée")
                                }
                                if (isEmpty()) append("Séance vérifiée caméra")
                            }
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = SteelGray
                            )
                        }
                        if (progressionDelta.xpGained > 0) {
                            Text(
                                text = "+${progressionDelta.xpGained} XP",
                                style = MaterialTheme.typography.titleMedium,
                                color = FormGood,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Primary Hero Score Card
            IndustrialCard(
                containerColor = ObsidianBg,
                borderColor = if (result.isNewRecord) FormGood else SignalOrange
            ) {
                Text(
                    text = "SCORE FINAL",
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelGray
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = result.scoreLabel,
                    style = MaterialTheme.typography.displayMedium,
                    color = if (result.isNewRecord) FormGood else SignalOrange,
                    fontWeight = FontWeight.Bold
                )
                if (!result.previousRecordLabel.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (result.isNewRecord) {
                            "Ancien record battu : ${result.previousRecordLabel}"
                        } else {
                            "Record personnel : ${result.previousRecordLabel}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelGray
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            IndustrialCard(containerColor = ObsidianBg) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricCell(
                        label = "Durée",
                        value = formatChallengeClock(result.durationSec),
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "Calories",
                        value = String.format(Locale.US, "%.1f", result.caloriesKcal),
                        unit = "kcal",
                        valueColor = SignalOrange,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCell(
                        label = "Posture",
                        value = "${result.avgFormScore}",
                        unit = "/100",
                        valueColor = formScoreColor(result.avgFormScore),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onReplay,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = CrispWhite),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Rejouer le défi", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClose,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                Text("Fermer", color = CrispWhite, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
