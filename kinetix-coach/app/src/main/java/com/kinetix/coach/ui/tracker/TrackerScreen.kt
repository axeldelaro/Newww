package com.kinetix.coach.ui.tracker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kinetix.coach.data.AthleteProfile
import com.kinetix.coach.data.ChallengeRecord
import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.data.WorkoutSession
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
import com.kinetix.coach.ui.theme.FormFault
import com.kinetix.coach.ui.theme.FormGood
import com.kinetix.coach.ui.theme.ObsidianBg
import com.kinetix.coach.ui.theme.SignalOrange
import com.kinetix.coach.ui.theme.SignalOrangeDim
import com.kinetix.coach.ui.theme.SteelGray
import com.kinetix.coach.vision.ChallengeType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class TrackerTab(val labelFr: String) {
    HISTORY("Historique"),
    PROFILE("Profil"),
    CALCULATORS("Calculateurs")
}

private val GOAL_OPTIONS = listOf(
    "Hypertrophie & Performance",
    "Sèche & Définition",
    "Force Maximale",
    "Endurance & Cardio",
    "Recomposition"
)

private val LEVEL_OPTIONS = listOf("Débutant", "Intermédiaire", "Avancé", "Compétiteur")

private val EQUIPMENT_OPTIONS = listOf(
    "Salle complète",
    "Haltères / Maison",
    "Poids du corps",
    "Kettlebells / Élastiques"
)

private val ACTIVITY_LEVELS = listOf(
    1.20f to "Sédentaire (1.20)",
    1.375f to "Léger · 1–3 j/sem (1.38)",
    1.55f to "Modéré · 3–5 j/sem (1.55)",
    1.725f to "Intense · 6–7 j/sem (1.73)",
    1.90f to "Athlète biquotidien (1.90)"
)

@Composable
fun TrackerScreen(viewModel: TrackerViewModel) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val challengeRecords by viewModel.challengeRecords.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val savedFeedback by viewModel.profileSavedFeedback.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(TrackerTab.HISTORY) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        // Header + Sub-tabs
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CharcoalSurface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "SUIVI & TÉLÉMÉTRIE",
                        style = MaterialTheme.typography.titleMedium,
                        color = CrispWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${profile.name} · ${profile.level} · ${profile.weightKg.toInt()} kg",
                        style = MaterialTheme.typography.labelMedium,
                        color = SteelGray
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TrackerTab.entries.forEach { tab ->
                    val isSelected = selectedTab == tab
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = tab },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) SignalOrange else ObsidianBg,
                        border = BorderStroke(1.dp, if (isSelected) SignalOrange else BorderHairline)
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tab.labelFr,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) CrispWhite else SteelGray,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        HairlineDivider()

        when (selectedTab) {
            TrackerTab.HISTORY -> HistoryTabContent(
                sessions = sessions,
                challengeRecords = challengeRecords,
                onDeleteSession = { viewModel.deleteSession(it) },
                modifier = Modifier.weight(1f)
            )
            TrackerTab.PROFILE -> ProfileTabContent(
                profile = profile,
                savedFeedback = savedFeedback,
                onSave = { viewModel.saveProfile(it) },
                onClearFeedback = { viewModel.clearProfileSavedFeedback() },
                modifier = Modifier.weight(1f)
            )
            TrackerTab.CALCULATORS -> CalculatorsTabContent(
                profile = profile,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HistoryTabContent(
    sessions: List<WorkoutSession>,
    challengeRecords: List<ChallengeRecord>,
    onDeleteSession: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalSessions = sessions.size
    val totalReps = sessions.sumOf { it.reps }
    val totalKcal = sessions.sumOf { it.caloriesKcal }
    val avgScore = if (sessions.isNotEmpty()) {
        sessions.map { it.avgFormScore }.average().roundToInt()
    } else 0

    // Compute best personal record per (challengeId, exerciseId) pair
    val personalBests = remember(challengeRecords) {
        challengeRecords
            .groupBy { it.challengeId to it.exerciseId }
            .mapNotNull { (key, list) ->
                val type = ChallengeType.fromId(key.first)
                if (type.lowerIsBetter) list.minByOrNull { it.scoreValue }
                else list.maxByOrNull { it.scoreValue }
            }
            .sortedByDescending { it.timestampMs }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // KPI Summary Row
        item {
            IndustrialCard {
                SectionLabel(text = "Synthèse athlétique")
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricCell(label = "Séances", value = "$totalSessions")
                    MetricCell(
                        label = "Score",
                        value = if (totalSessions > 0) "$avgScore" else "—",
                        unit = if (totalSessions > 0) "/100" else "",
                        valueColor = if (totalSessions > 0) formScoreColor(avgScore) else SteelGray
                    )
                    MetricCell(label = "Reps", value = "$totalReps")
                    MetricCell(
                        label = "Calories",
                        value = if (totalKcal >= 100.0) "${totalKcal.roundToInt()}" else String.format(Locale.US, "%.1f", totalKcal),
                        unit = "kcal",
                        valueColor = SignalOrange
                    )
                }
            }
        }

        // Personal Records & Camera-Verified Challenges Section
        item {
            ChallengeRecordsCard(records = personalBests)
        }

        // Form Score Progression Chart
        if (sessions.size >= 2) {
            item {
                FormProgressionChartCard(sessions = sessions.take(12).reversed())
            }
        }

        item {
            SectionLabel(
                text = "Séances enregistrées",
                trailing = if (sessions.isNotEmpty()) "${sessions.size} entrées" else null
            )
        }

        if (sessions.isEmpty()) {
            item {
                IndustrialCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.FitnessCenter,
                            contentDescription = null,
                            tint = SignalOrange,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Aucune séance enregistrée",
                            style = MaterialTheme.typography.titleMedium,
                            color = CrispWhite
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Lancez une analyse biomécanique dans l'onglet Vision puis touchez « Terminer » pour enregistrer vos répétitions, scores posturaux et axes de correction.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SteelGray
                    )
                }
            }
        } else {
            items(sessions, key = { it.id }) { session ->
                SessionHistoryCard(
                    session = session,
                    onDelete = { onDeleteSession(session.id) }
                )
            }
        }
    }
}

@Composable
private fun ChallengeRecordsCard(records: List<ChallengeRecord>) {
    IndustrialCard {
        SectionLabel(
            text = "Records & Défis",
            trailing = if (records.isNotEmpty()) "${records.size} RECORDS" else "7 DÉFIS"
        )
        Spacer(Modifier.height(10.dp))
        if (records.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = SignalOrange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = "Aucun record établi — ouvrez « Défis » dans Vision (Bip & Go, Luc Léger, Gainage Musical, Tabata, EMOM…).",
                    style = MaterialTheme.typography.bodySmall,
                    color = SteelGray
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                records.take(8).forEachIndexed { idx, rec ->
                    val chType = ChallengeType.fromId(rec.challengeId)
                    val exType = ExerciseType.fromId(rec.exerciseId)
                    val dateStr = remember(rec.timestampMs) {
                        SimpleDateFormat("dd MMM", Locale.FRENCH).format(Date(rec.timestampMs))
                    }
                    if (idx > 0) HairlineDivider()
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
                                imageVector = Icons.Filled.EmojiEvents,
                                contentDescription = null,
                                tint = SignalOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = chType.titleFr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CrispWhite,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${exType.labelFr} · $dateStr",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SteelGray
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SignalOrangeDim,
                            border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.7f))
                        ) {
                            Text(
                                text = rec.scoreLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = SignalOrange,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormProgressionChartCard(sessions: List<WorkoutSession>) {
    IndustrialCard {
        SectionLabel(
            text = "Évolution du score posture",
            trailing = "${sessions.size} dernières"
        )
        Spacer(Modifier.height(12.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val w = size.width
            val h = size.height
            val padLeft = 10f
            val padRight = 10f
            val padTop = 12f
            val padBottom = 16f
            val chartW = (w - padLeft - padRight).coerceAtLeast(1f)
            val chartH = (h - padTop - padBottom).coerceAtLeast(1f)

            // Reference threshold lines (100%, 82% good, 65% caution)
            val thresholds = listOf(100f to BorderHairline, 82f to FormGood.copy(alpha = 0.35f), 65f to FormCaution.copy(alpha = 0.35f))
            thresholds.forEach { (valPct, lineColor) ->
                val y = padTop + chartH * (1f - valPct / 100f)
                drawLine(
                    color = lineColor,
                    start = Offset(padLeft, y),
                    end = Offset(w - padRight, y),
                    strokeWidth = 1.5f
                )
            }

            val stepX = if (sessions.size > 1) chartW / (sessions.size - 1) else 0f
            val points = sessions.mapIndexed { idx, s ->
                val x = padLeft + idx * stepX
                val clamped = s.avgFormScore.coerceIn(0, 100)
                val y = padTop + chartH * (1f - clamped / 100f)
                Offset(x, y) to clamped
            }

            // Volume bars subtly behind line
            val maxReps = sessions.maxOf { it.reps }.coerceAtLeast(1)
            val barWidth = (stepX * 0.36f).coerceIn(6f, 22f)
            sessions.forEachIndexed { idx, s ->
                val cx = padLeft + idx * stepX
                val barH = chartH * (s.reps.toFloat() / maxReps) * 0.45f
                drawRect(
                    color = ElevatedSurface,
                    topLeft = Offset(cx - barWidth / 2f, padTop + chartH - barH),
                    size = androidx.compose.ui.geometry.Size(barWidth, barH)
                )
            }

            // Polyline for Form Score
            val path = Path()
            points.forEachIndexed { i, (pt, _) ->
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            drawPath(
                path = path,
                color = SignalOrange,
                style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // Nodes
            points.forEach { (pt, score) ->
                drawCircle(
                    color = ObsidianBg,
                    radius = 6.5f,
                    center = pt
                )
                drawCircle(
                    color = formScoreColor(score),
                    radius = 4.2f,
                    center = pt
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Seuil optimal ≥ 82", style = MaterialTheme.typography.labelSmall, color = FormGood)
            Text("Seuil vigilance ≥ 65", style = MaterialTheme.typography.labelSmall, color = FormCaution)
            Text("Barres : volume reps", style = MaterialTheme.typography.labelSmall, color = SteelGray)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionHistoryCard(
    session: WorkoutSession,
    onDelete: () -> Unit
) {
    val dateStr = remember(session.timestampMs) {
        SimpleDateFormat("dd MMM · HH:mm", Locale.FRENCH).format(Date(session.timestampMs))
    }
    val scoreColor = formScoreColor(session.avgFormScore)

    IndustrialCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.exerciseType.labelFr.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = CrispWhite,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (session.notes.isNotBlank()) "$dateStr · ${session.notes}" else dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelGray
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = ElevatedSurface,
                border = BorderStroke(1.dp, SignalOrange.copy(alpha = 0.55f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
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
                        text = String.format(Locale.US, "%.1f kcal", session.caloriesKcal),
                        style = MaterialTheme.typography.labelSmall,
                        color = CrispWhite,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "Supprimer la séance",
                    tint = SteelGray,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricCell(
                label = if (session.exerciseType.isIsometric) "Maintien" else "Répétitions",
                value = if (session.exerciseType.isIsometric) "${session.durationSec}" else "${session.reps}",
                unit = if (session.exerciseType.isIsometric) "s" else "reps",
                valueColor = SignalOrange
            )
            MetricCell(
                label = "Score posture",
                value = "${session.avgFormScore}",
                unit = "/100",
                valueColor = scoreColor
            )
            MetricCell(
                label = "Amplitude",
                value = "${session.avgRomPct}",
                unit = "%"
            )
            MetricCell(
                label = "Durée",
                value = "${session.durationSec}",
                unit = "s"
            )
        }
        if (session.topFaults.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HairlineDivider()
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                session.topFaults.forEach { fault ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ElevatedSurface,
                        border = BorderStroke(1.dp, FormCaution.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = fault,
                            style = MaterialTheme.typography.labelSmall,
                            color = CrispWhite,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileTabContent(
    profile: AthleteProfile,
    savedFeedback: Boolean,
    onSave: (AthleteProfile) -> Unit,
    onClearFeedback: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember(profile) { mutableStateOf(profile.name) }
    var sex by remember(profile) { mutableStateOf(profile.sex) }
    var ageText by remember(profile) { mutableStateOf(profile.age.toString()) }
    var weightText by remember(profile) { mutableStateOf(profile.weightKg.toInt().toString()) }
    var heightText by remember(profile) { mutableStateOf(profile.heightCm.toString()) }
    var goal by remember(profile) { mutableStateOf(profile.goal) }
    var level by remember(profile) { mutableStateOf(profile.level) }
    var equipment by remember(profile) { mutableStateOf(profile.equipment) }
    var injuries by remember(profile) { mutableStateOf(profile.injuries) }
    var activityFactor by remember(profile) { mutableFloatStateOf(profile.activityFactor) }

    LaunchedEffect(savedFeedback) {
        if (savedFeedback) {
            kotlinx.coroutines.delay(2500)
            onClearFeedback()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IndustrialCard {
            SectionLabel(text = "Identité & Biométrie")
            Spacer(Modifier.height(10.dp))

            ProfileTextField(label = "Prénom / Pseudo", value = name, onValueChange = { name = it })
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Homme", "Femme").forEach { option ->
                    FilterChipPill(
                        label = option,
                        selected = sex == option,
                        onClick = { sex = option },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileTextField(
                    label = "Âge",
                    value = ageText,
                    onValueChange = { ageText = it.filter(Char::isDigit) },
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
                ProfileTextField(
                    label = "Poids (kg)",
                    value = weightText,
                    onValueChange = { weightText = it.filter { c -> c.isDigit() || c == '.' } },
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
                ProfileTextField(
                    label = "Taille (cm)",
                    value = heightText,
                    onValueChange = { heightText = it.filter(Char::isDigit) },
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        IndustrialCard {
            SectionLabel(text = "Objectif principal")
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GOAL_OPTIONS.forEach { opt ->
                    FilterChipPill(label = opt, selected = goal == opt, onClick = { goal = opt })
                }
            }
        }

        IndustrialCard {
            SectionLabel(text = "Niveau d'entraînement")
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LEVEL_OPTIONS.forEach { opt ->
                    FilterChipPill(label = opt, selected = level == opt, onClick = { level = opt })
                }
            }
            Spacer(Modifier.height(14.dp))
            SectionLabel(text = "Matériel disponible")
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EQUIPMENT_OPTIONS.forEach { opt ->
                    FilterChipPill(label = opt, selected = equipment == opt, onClick = { equipment = opt })
                }
            }
        }

        IndustrialCard {
            SectionLabel(text = "Facteur d'activité (TDEE)")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ACTIVITY_LEVELS.forEach { (factor, label) ->
                    val selected = kotlin.math.abs(activityFactor - factor) < 0.03f
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { activityFactor = factor },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) ElevatedSurface else ObsidianBg,
                        border = BorderStroke(1.dp, if (selected) SignalOrange else BorderHairline)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) CrispWhite else SteelGray,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            ProfileTextField(
                label = "Antécédents / Blessures (injecté dans le Coach IA)",
                value = injuries,
                onValueChange = { injuries = it }
            )
        }

        if (savedFeedback) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = ElevatedSurface,
                border = BorderStroke(1.dp, FormGood)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = FormGood)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Profil sauvegardé · Synchronisé avec le Coach IA local et le TDEE.",
                        style = MaterialTheme.typography.labelMedium,
                        color = CrispWhite
                    )
                }
            }
        }

        Button(
            onClick = {
                onSave(
                    AthleteProfile(
                        name = name.ifBlank { "Athlète" },
                        age = ageText.toIntOrNull()?.coerceIn(13, 95) ?: profile.age,
                        sex = sex,
                        weightKg = weightText.toFloatOrNull()?.coerceIn(35f, 220f) ?: profile.weightKg,
                        heightCm = heightText.toIntOrNull()?.coerceIn(120, 230) ?: profile.heightCm,
                        goal = goal,
                        level = level,
                        equipment = equipment,
                        injuries = injuries.ifBlank { "Aucune" },
                        activityFactor = activityFactor
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SignalOrange,
                contentColor = CrispWhite
            )
        ) {
            Text(
                text = "Enregistrer le profil athlète",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun CalculatorsTabContent(
    profile: AthleteProfile,
    modifier: Modifier = Modifier
) {
    var weightKgText by rememberSaveable { mutableStateOf("80") }
    var reps by rememberSaveable { mutableIntStateOf(6) }

    val parsedWeight = weightKgText.toFloatOrNull()?.coerceIn(5f, 450f) ?: 80f
    val oneRm = remember(parsedWeight, reps) {
        AthleticCalculators.calculateOneRm(parsedWeight, reps)
    }
    val tdee = remember(profile) {
        AthleticCalculators.calculateTdee(profile)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1RM Calculator Card
        IndustrialCard {
            SectionLabel(text = "Calculateur 1RM (Epley + Brzycki)", trailing = "FORCE")
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ProfileTextField(
                    label = "Charge (kg)",
                    value = weightKgText,
                    onValueChange = { weightKgText = it.filter { c -> c.isDigit() || c == '.' } },
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f)
                )
                Column(modifier = Modifier.weight(1.3f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Répétitions", style = MaterialTheme.typography.labelSmall, color = SteelGray)
                        Text("$reps reps", style = MaterialTheme.typography.labelLarge, color = SignalOrange)
                    }
                    Slider(
                        value = reps.toFloat(),
                        onValueChange = { reps = it.roundToInt().coerceIn(1, 15) },
                        valueRange = 1f..15f,
                        steps = 13,
                        colors = SliderDefaults.colors(
                            thumbColor = SignalOrange,
                            activeTrackColor = SignalOrange,
                            inactiveTrackColor = BorderHairline
                        )
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            HairlineDivider()
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricCell("1RM Estimé", String.format("%.1f", oneRm.estimated1RmKg), "kg", valueColor = SignalOrange)
                MetricCell("Epley", String.format("%.1f", oneRm.epleyKg), "kg")
                MetricCell("Brzycki", String.format("%.1f", oneRm.brzyckiKg), "kg")
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel(text = "Grille des charges cibles")
            Spacer(Modifier.height(6.dp))

            oneRm.loadTable.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${row.pct}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = SignalOrange,
                        modifier = Modifier.width(44.dp)
                    )
                    Text(
                        text = row.zoneFr,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CrispWhite,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = row.targetReps,
                        style = MaterialTheme.typography.labelMedium,
                        color = SteelGray,
                        modifier = Modifier.width(72.dp)
                    )
                    Text(
                        text = String.format("%.1f kg", row.weightKg),
                        style = MaterialTheme.typography.labelLarge,
                        color = CrispWhite,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // TDEE & Macros Card
        IndustrialCard {
            SectionLabel(text = "TDEE & Macros (Mifflin-St Jeor)", trailing = "PROFIL ACTIF")
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Basé sur votre profil (${profile.sex}, ${profile.age} ans, ${profile.weightKg.toInt()} kg, ${profile.heightCm} cm, activité ×${String.format("%.2f", profile.activityFactor)}).",
                style = MaterialTheme.typography.labelMedium,
                color = SteelGray
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricCell("Métabolisme basal", "${tdee.bmrKcal}", "kcal")
                MetricCell("Maintenance TDEE", "${tdee.tdeeKcal}", "kcal", valueColor = SignalOrange)
            }
            Spacer(Modifier.height(12.dp))
            HairlineDivider()
            Spacer(Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MacroPlanCard(plan = tdee.cutPlan, accent = FormFault)
                MacroPlanCard(plan = tdee.maintenancePlan, accent = FormGood)
                MacroPlanCard(plan = tdee.bulkPlan, accent = SignalOrange)
            }
        }
    }
}

@Composable
private fun MacroPlanCard(
    plan: MacroPlan,
    accent: androidx.compose.ui.graphics.Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = ObsidianBg,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = plan.labelFr.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${plan.kcal} kcal / jour",
                    style = MaterialTheme.typography.titleMedium,
                    color = CrispWhite,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Protéines : ${plan.proteinG} g", style = MaterialTheme.typography.labelMedium, color = FormGood)
                Text("Glucides : ${plan.carbsG} g", style = MaterialTheme.typography.labelMedium, color = CrispWhite)
                Text("Lipides : ${plan.fatG} g", style = MaterialTheme.typography.labelMedium, color = SteelGray)
            }
        }
    }
}

@Composable
private fun ProfileTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = ObsidianBg,
            unfocusedContainerColor = ObsidianBg,
            focusedBorderColor = SignalOrange,
            unfocusedBorderColor = BorderHairline,
            focusedLabelColor = SignalOrange,
            unfocusedLabelColor = SteelGray,
            focusedTextColor = CrispWhite,
            unfocusedTextColor = CrispWhite,
            cursorColor = SignalOrange
        )
    )
}
