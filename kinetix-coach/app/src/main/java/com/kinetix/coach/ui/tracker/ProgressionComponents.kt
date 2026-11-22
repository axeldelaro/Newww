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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetix.coach.data.AthleteProfile
import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.progression.AthleteAttributes
import com.kinetix.coach.progression.AthleteProgressState
import com.kinetix.coach.progression.DailyQuest
import com.kinetix.coach.progression.DayActivityEntry
import com.kinetix.coach.progression.MeritBadge
import com.kinetix.coach.progression.MeritTitle
import com.kinetix.coach.progression.ProgressionDelta
import com.kinetix.coach.progression.RankExamSpec
import com.kinetix.coach.ui.components.IndustrialCard
import com.kinetix.coach.ui.components.MetricCell
import com.kinetix.coach.ui.components.SectionLabel
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val ISO_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

private fun formatFrenchInt(value: Long): String {
    val s = value.coerceAtLeast(0L).toString()
    if (s.length <= 3) return s
    return s.reversed().chunked(3).joinToString(" ").reversed()
}

private fun frenchDayInitial(dateKey: String): String {
    val dow = runCatching { LocalDate.parse(dateKey, ISO_DATE_FMT).dayOfWeek }.getOrNull()
    return when (dow) {
        DayOfWeek.MONDAY -> "L"
        DayOfWeek.TUESDAY -> "M"
        DayOfWeek.WEDNESDAY -> "M"
        DayOfWeek.THURSDAY -> "J"
        DayOfWeek.FRIDAY -> "V"
        DayOfWeek.SATURDAY -> "S"
        DayOfWeek.SUNDAY -> "D"
        null -> "·"
    }
}

/**
 * Compact studio-pro athlete header showing Rank Emblem (E..S), Name, Equipped Merit Title,
 * Level, Streak Flame, and crisp XP progress bar.
 */
@Composable
fun AthleteCompactHeader(
    profile: AthleteProfile,
    state: AthleteProgressState
) {
    val rankColor = Color(state.rank.accentHex)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Clean Rank Emblem Badge (E -> S)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = rankColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.5.dp, rankColor),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = state.rank.code,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = rankColor
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = CrispWhite,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = ElevatedSurface,
                            border = BorderStroke(1.dp, BorderHairline)
                        ) {
                            Text(
                                text = "Niv. ${state.level}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CrispWhite,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = "${state.equippedTitle.labelFr} · Rang ${state.rank.code} (${state.rank.labelFr})",
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Compact Streak Pill
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (state.streakDays > 0) SignalOrangeDim else ObsidianBg,
                border = BorderStroke(
                    1.dp,
                    if (state.streakDays > 0) SignalOrange.copy(alpha = 0.75f) else BorderHairline
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocalFireDepartment,
                        contentDescription = "Streak",
                        tint = if (state.streakDays > 0) SignalOrange else SteelGray,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${state.streakDays}j",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.streakDays > 0) CrispWhite else SteelGray,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Sleek XP & Rank Progress Bar Row
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "XP Niveau : ${state.xpInCurrentLevel} / ${state.xpToNextLevel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelGray
                )
                val rankRightLabel = when {
                    state.rank.nextRank() == null -> "Rang S Max"
                    state.rankExamReady -> "Épreuve Rang ${state.rank.nextRank()!!.code} prête"
                    else -> "Rang ${state.rank.nextRank()!!.code} : -${state.rankXpRemaining} XP"
                }
                Text(
                    text = rankRightLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.rankExamReady) rankColor else SteelGray,
                    fontWeight = if (state.rankExamReady) FontWeight.Bold else FontWeight.Medium
                )
            }
            LinearProgressIndicator(
                progress = { state.levelProgressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = SignalOrange,
                trackColor = ObsidianBg
            )
        }
    }
}

/**
 * Primary Cockpit Tab ("Quêtes"): 7-Day Visual Streak, Camera Rank Exam (when unlocked),
 * 3 Procedural Daily Quests (with 1-tap Camera launch), and Offline Hardware Pedometer.
 */
@Composable
fun QuestsCockpitTab(
    state: AthleteProgressState,
    pedometerActive: Boolean,
    hasHardwarePedometer: Boolean,
    onRequestPedometerPermission: () -> Unit,
    onAddManualSteps: (Int) -> Unit,
    onLaunchQuestInCamera: (ExerciseType) -> Unit,
    onCompleteQuestManually: (String) -> Unit,
    onLaunchRankExam: (RankExamSpec) -> Unit,
    modifier: Modifier = Modifier
) {
    val rankExam = remember(state.rank, state.rankExamReady) {
        if (state.rankExamReady) state.rank.rankExamSpec() else null
    }
    val completedTodayCount = state.todayQuests.count { it.completed }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Camera-Verified Rank Exam Card (surfaces cleanly ONLY when Rank XP reaches 100%!)
        if (rankExam != null) {
            item {
                val targetColor = Color(rankExam.toRank.accentHex)
                IndustrialCard(borderColor = targetColor) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionLabel(
                            text = "ÉPREUVE DE PASSAGE · RANG ${rankExam.toRank.code}",
                            trailing = "+${rankExam.xpBonus} XP"
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = rankExam.titleFr,
                        style = MaterialTheme.typography.titleMedium,
                        color = CrispWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = rankExam.descriptionFr,
                        style = MaterialTheme.typography.bodySmall,
                        color = SteelGray
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onLaunchRankExam(rankExam) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = targetColor, contentColor = ObsidianBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Passer l'épreuve à la caméra",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. Visual 7-Day Streak Row (zero penalties, pure consistency reward!)
        item {
            StreakSevenDayCard(
                streakDays = state.streakDays,
                longestStreak = state.longestStreak,
                recentDays = state.recentDays,
                todayDateKey = state.todayDateKey
            )
        }

        // 3. Today's 3 Procedural Adaptive Quests
        item {
            SectionLabel(
                text = "Quêtes du jour · Rang ${state.rank.code}",
                trailing = "$completedTodayCount/${state.todayQuests.size} VALIDÉES"
            )
        }

        state.todayQuests.forEach { quest ->
            item(key = quest.id) {
                DailyQuestCard(
                    quest = quest,
                    onLaunchCamera = { quest.exerciseType?.let(onLaunchQuestInCamera) },
                    onCompleteManually = { onCompleteQuestManually(quest.id) }
                )
            }
        }

        // 4. Offline Hardware Pedometer & 10k Daily Goal Card
        item {
            PedometerCard(
                todaySteps = state.todaySteps,
                stepGoal = state.stepGoal,
                progressFraction = state.stepsProgressFraction,
                goalRewarded = state.todayStepGoalRewarded,
                pedometerActive = pedometerActive,
                hasHardwarePedometer = hasHardwarePedometer,
                onEnablePedometer = onRequestPedometerPermission,
                onAddManualSteps = onAddManualSteps
            )
        }
    }
}

@Composable
private fun StreakSevenDayCard(
    streakDays: Int,
    longestStreak: Int,
    recentDays: List<DayActivityEntry>,
    todayDateKey: String
) {
    val activeInWeek = recentDays.count { it.isActiveDay }
    IndustrialCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Filled.LocalFireDepartment,
                    contentDescription = null,
                    tint = if (streakDays > 0) SignalOrange else SteelGray,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = if (streakDays > 0) "Série de $streakDays jour${if (streakDays > 1) "s" else ""}" else "Démarrez votre série",
                        style = MaterialTheme.typography.titleSmall,
                        color = CrispWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Record : ${longestStreak}j · Bonus 7/7 (+150 XP)",
                        style = MaterialTheme.typography.labelSmall,
                        color = SteelGray
                    )
                }
            }
            Text(
                text = "$activeInWeek/7",
                style = MaterialTheme.typography.labelLarge,
                color = if (activeInWeek >= 7) FormGood else SignalOrange,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            recentDays.takeLast(7).forEach { day ->
                val isToday = day.dateKey == todayDateKey
                val active = day.isActiveDay
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = when {
                        active -> SignalOrange.copy(alpha = 0.20f)
                        isToday -> ElevatedSurface
                        else -> ObsidianBg
                    },
                    border = BorderStroke(
                        width = if (isToday) 1.5.dp else 1.dp,
                        color = when {
                            active -> SignalOrange
                            isToday -> CrispWhite.copy(alpha = 0.35f)
                            else -> BorderHairline
                        }
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = frenchDayInitial(day.dateKey),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active || isToday) CrispWhite else SteelGray,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (active) FormGood else BorderHairline)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyQuestCard(
    quest: DailyQuest,
    onLaunchCamera: () -> Unit,
    onCompleteManually: () -> Unit
) {
    val borderColor = if (quest.completed) FormGood.copy(alpha = 0.65f) else BorderHairline
    IndustrialCard(borderColor = borderColor) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = quest.titleFr,
                        style = MaterialTheme.typography.titleSmall,
                        color = CrispWhite,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (quest.completed) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Validée",
                            tint = FormGood,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = quest.subtitleFr,
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelGray
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = if (quest.completed) FormGood.copy(alpha = 0.16f) else SignalOrangeDim,
                border = BorderStroke(
                    1.dp,
                    if (quest.completed) FormGood.copy(alpha = 0.65f) else SignalOrange.copy(alpha = 0.55f)
                )
            ) {
                Text(
                    text = if (quest.completed) "Validée" else "+${quest.xpReward} XP",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (quest.completed) FormGood else SignalOrange,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(9.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { quest.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (quest.completed) FormGood else SignalOrange,
                    trackColor = ObsidianBg
                )
                Text(
                    text = "${formatFrenchInt(quest.currentProgress.toLong())} / ${formatFrenchInt(quest.targetAmount.toLong())} ${quest.unitFr}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelGray
                )
            }

            if (!quest.completed) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (quest.exerciseType != null) {
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = SignalOrange,
                            modifier = Modifier.clickable(onClick = onLaunchCamera)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Videocam,
                                    contentDescription = "Lancer caméra",
                                    tint = CrispWhite,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Caméra",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CrispWhite,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    // Discreet off-camera manual validation fallback
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = ObsidianBg,
                        border = BorderStroke(1.dp, BorderHairline),
                        modifier = Modifier.clickable(onClick = onCompleteManually)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Valider hors-caméra",
                                tint = SteelGray,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PedometerCard(
    todaySteps: Int,
    stepGoal: Int,
    progressFraction: Float,
    goalRewarded: Boolean,
    pedometerActive: Boolean,
    hasHardwarePedometer: Boolean,
    onEnablePedometer: () -> Unit,
    onAddManualSteps: (Int) -> Unit
) {
    IndustrialCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Sleek Daily Steps Progress Ring
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier.size(72.dp),
                    color = if (goalRewarded) FormGood else SignalOrange,
                    trackColor = ObsidianBg,
                    strokeWidth = 6.dp,
                    strokeCap = StrokeCap.Round
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                    contentDescription = null,
                    tint = if (goalRewarded) FormGood else CrispWhite,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SectionLabel(
                    text = "Podomètre du jour",
                    trailing = if (goalRewarded) "10K VALIDÉ ✓" else "+10 XP / 1 000"
                )
                Text(
                    text = "${formatFrenchInt(todaySteps.toLong())} / ${formatFrenchInt(stepGoal.toLong())} pas",
                    style = MaterialTheme.typography.titleMedium,
                    color = CrispWhite,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        pedometerActive -> "Capteur matériel actif · 100 % hors-ligne"
                        hasHardwarePedometer -> "Activez le capteur pour le comptage auto"
                        else -> "Ajout manuel rapide disponible"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (pedometerActive) FormGood else SteelGray
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasHardwarePedometer && !pedometerActive) {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = SignalOrange,
                    modifier = Modifier
                        .weight(1.4f)
                        .clickable(onClick = onEnablePedometer)
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Activer podomètre",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrispWhite,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = ObsidianBg,
                border = BorderStroke(1.dp, BorderHairline),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onAddManualSteps(500) }
            ) {
                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text("+500 pas", style = MaterialTheme.typography.labelMedium, color = CrispWhite, fontWeight = FontWeight.SemiBold)
                }
            }
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = ObsidianBg,
                border = BorderStroke(1.dp, BorderHairline),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onAddManualSteps(1000) }
            ) {
                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text("+1 000 pas", style = MaterialTheme.typography.labelMedium, color = CrispWhite, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Full Athletic Progression View: 5-Axis Radar Chart, Next Objective, 7-Day Steps Bar Chart,
 * Lifetime Exercise Palmarès, Equipable Merit Titles, and 12 Merit Badges.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressionOverviewSection(
    state: AthleteProgressState,
    onEquipTitle: (String) -> Unit,
    onLaunchRankExam: (RankExamSpec) -> Unit
) {
    val rankColor = Color(state.rank.accentHex)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Next Objective Card (Level + Rank)
        IndustrialCard {
            SectionLabel(
                text = "Prochain objectif",
                trailing = "RANG ${state.rank.code}"
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricCell(
                    label = "Niveau ${state.level + 1}",
                    value = "-${(state.xpToNextLevel - state.xpInCurrentLevel).coerceAtLeast(0)}",
                    unit = "XP",
                    modifier = Modifier.weight(1f)
                )
                val nextRank = state.rank.nextRank()
                MetricCell(
                    label = if (nextRank != null) "Rang ${nextRank.code}" else "Rang Max",
                    value = when {
                        nextRank == null -> "S"
                        state.rankExamReady -> "PRÊT"
                        else -> "-${state.rankXpRemaining}"
                    },
                    unit = if (nextRank != null && !state.rankExamReady) "XP" else "",
                    valueColor = rankColor,
                    modifier = Modifier.weight(1f)
                )
                MetricCell(
                    label = "Total XP",
                    value = formatFrenchInt(state.totalXp.toLong()),
                    unit = "",
                    valueColor = SignalOrange,
                    modifier = Modifier.weight(1f)
                )
            }
            if (state.rank.nextRank() != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { state.rankProgressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = rankColor,
                    trackColor = ObsidianBg
                )
                val exam = state.rank.rankExamSpec()
                if (state.rankExamReady && exam != null) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onLaunchRankExam(exam) },
                        shape = RoundedCornerShape(9.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = rankColor, contentColor = ObsidianBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Lancer l'épreuve Rang ${exam.toRank.code}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. 5-Axis Athletic Radar Chart
        IndustrialCard {
            SectionLabel(
                text = "Profil biomécanique · 5 axes",
                trailing = "AXE À RENFORCER : ${state.attributes.weakestLabelFr().uppercase()}"
            )
            Spacer(Modifier.height(10.dp))
            AthleticRadarChart(
                attributes = state.attributes,
                accentColor = rankColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(216.dp)
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                state.attributes.asLabeledList().forEach { (label, score) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$score",
                            style = MaterialTheme.typography.titleSmall,
                            color = CrispWhite,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = label.take(5),
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelGray,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // 3. 7-Day Steps Bar Chart
        IndustrialCard {
            val sum7d = state.recentDays.takeLast(7).sumOf { it.steps }
            SectionLabel(
                text = "Activité marche · 7 jours",
                trailing = "${formatFrenchInt(sum7d.toLong())} PAS"
            )
            Spacer(Modifier.height(10.dp))
            StepsSevenDayBarChart(
                days = state.recentDays.takeLast(7),
                goal = state.stepGoal,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
            )
        }

        // 4. Lifetime Exercise Palmarès
        IndustrialCard {
            SectionLabel(
                text = "Palmarès cumulé à vie",
                trailing = "${state.totalWorkoutsCompleted} SÉANCES"
            )
            Spacer(Modifier.height(10.dp))
            val pushups = state.exerciseTotals[ExerciseType.PUSHUP.id] ?: 0
            val pullups = state.exerciseTotals[ExerciseType.PULL_UP.id] ?: 0
            val squats = state.exerciseTotals[ExerciseType.SQUAT.id] ?: 0
            val plankSec = state.exerciseTotals[ExerciseType.PLANK.id] ?: 0

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCell(label = "Pompes", value = formatFrenchInt(pushups.toLong()), modifier = Modifier.weight(1f))
                MetricCell(label = "Tractions", value = formatFrenchInt(pullups.toLong()), modifier = Modifier.weight(1f))
                MetricCell(label = "Squats", value = formatFrenchInt(squats.toLong()), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCell(label = "Gainage", value = "${plankSec}", unit = "s", modifier = Modifier.weight(1f))
                MetricCell(label = "Quêtes", value = "${state.totalQuestsCompleted}", valueColor = FormGood, modifier = Modifier.weight(1f))
                MetricCell(
                    label = "Pas totaux",
                    value = if (state.totalStepsLifetime >= 10_000L) "${state.totalStepsLifetime / 1000L}k" else formatFrenchInt(state.totalStepsLifetime),
                    valueColor = SignalOrange,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 5. Equipable Merit Titles (NO shop, earned strictly by performance!)
        IndustrialCard {
            var lockedTitleHint by remember { mutableStateOf<MeritTitle?>(null) }
            SectionLabel(
                text = "Titres au mérite",
                trailing = "${state.unlockedTitleIds.size}/${MeritTitle.entries.size}"
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Touchez un titre débloqué pour l'équiper, ou un titre verrouillé pour voir son objectif.",
                style = MaterialTheme.typography.labelSmall,
                color = SteelGray
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MeritTitle.entries.forEach { title ->
                    val unlocked = title.id in state.unlockedTitleIds
                    val equipped = title.id == state.equippedTitleId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            equipped -> SignalOrange
                            unlocked -> ObsidianBg
                            else -> ObsidianBg.copy(alpha = 0.55f)
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                equipped -> SignalOrange
                                unlocked -> CrispWhite.copy(alpha = 0.25f)
                                lockedTitleHint == title -> SignalOrange.copy(alpha = 0.6f)
                                else -> BorderHairline
                            }
                        ),
                        modifier = Modifier.clickable {
                            if (unlocked) {
                                lockedTitleHint = null
                                onEquipTitle(title.id)
                            } else {
                                lockedTitleHint = if (lockedTitleHint == title) null else title
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = if (unlocked) Icons.Filled.MilitaryTech else Icons.Filled.Lock,
                                contentDescription = null,
                                tint = when {
                                    equipped -> CrispWhite
                                    unlocked -> SignalOrange
                                    else -> SteelGray.copy(alpha = 0.65f)
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = title.labelFr,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (unlocked) CrispWhite else SteelGray.copy(alpha = 0.7f),
                                fontWeight = if (equipped) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
            lockedTitleHint?.let { hint ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Objectif « ${hint.labelFr} » : ${hint.requirementFr}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SignalOrange,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 6. Merit Badges Trophy Grid
        IndustrialCard {
            SectionLabel(
                text = "Badges au mérite",
                trailing = "${state.unlockedBadgeIds.size}/${MeritBadge.entries.size}"
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MeritBadge.entries.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { badge ->
                            val unlocked = badge.id in state.unlockedBadgeIds
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (unlocked) ElevatedSurface else ObsidianBg,
                                border = BorderStroke(
                                    1.dp,
                                    if (unlocked) FormGood.copy(alpha = 0.55f) else BorderHairline
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (unlocked) Icons.Filled.EmojiEvents else Icons.Filled.Lock,
                                            contentDescription = null,
                                            tint = if (unlocked) FormGood else SteelGray.copy(alpha = 0.6f),
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = badge.titleFr,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (unlocked) CrispWhite else SteelGray,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = badge.detailFr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SteelGray,
                                        maxLines = 2,
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
        }
    }
}

@Composable
private fun AthleticRadarChart(
    attributes: AthleteAttributes,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val entries = attributes.asLabeledList()
    val maxScale = maxOf(25, entries.maxOf { it.second } + 5).toFloat()

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.52f
            val radius = (minOf(size.width, size.height) * 0.37f).coerceAtLeast(36f)
            val sides = entries.size
            val angleStep = (2.0 * PI / sides).toFloat()
            val startAngle = (-PI / 2.0).toFloat()

            fun pointAt(idx: Int, frac: Float): Offset {
                val a = startAngle + idx * angleStep
                return Offset(
                    x = cx + radius * frac * cos(a.toDouble()).toFloat(),
                    y = cy + radius * frac * sin(a.toDouble()).toFloat()
                )
            }

            // Concentric studio pentagon grid rings (33%, 66%, 100%)
            for (ring in listOf(0.33f, 0.66f, 1.0f)) {
                val gridPath = Path()
                for (i in 0 until sides) {
                    val p = pointAt(i, ring)
                    if (i == 0) gridPath.moveTo(p.x, p.y) else gridPath.lineTo(p.x, p.y)
                }
                gridPath.close()
                drawPath(
                    path = gridPath,
                    color = BorderHairline.copy(alpha = if (ring == 1f) 0.9f else 0.55f),
                    style = Stroke(width = 1.4f)
                )
            }

            // Radial spokes
            for (i in 0 until sides) {
                drawLine(
                    color = BorderHairline.copy(alpha = 0.55f),
                    start = Offset(cx, cy),
                    end = pointAt(i, 1f),
                    strokeWidth = 1.2f
                )
            }

            // Filled athlete attribute polygon
            val dataPath = Path()
            val vertexPoints = mutableListOf<Offset>()
            entries.forEachIndexed { i, (_, value) ->
                val frac = (value / maxScale).coerceIn(0.14f, 1.0f)
                val p = pointAt(i, frac)
                vertexPoints.add(p)
                if (i == 0) dataPath.moveTo(p.x, p.y) else dataPath.lineTo(p.x, p.y)
            }
            dataPath.close()
            drawPath(path = dataPath, color = accentColor.copy(alpha = 0.24f), style = Fill)
            drawPath(path = dataPath, color = accentColor, style = Stroke(width = 3.0f))

            // Vertex dots
            for (pt in vertexPoints) {
                drawCircle(color = ObsidianBg, radius = 6.5f, center = pt)
                drawCircle(color = accentColor, radius = 4.2f, center = pt)
            }
        }

        // Crisp readable axis labels positioned around the pentagon
        Text(
            text = "Force",
            style = MaterialTheme.typography.labelSmall,
            color = CrispWhite,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
        )
        Text(
            text = "Endurance",
            style = MaterialTheme.typography.labelSmall,
            color = CrispWhite,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp, bottom = 36.dp)
        )
        Text(
            text = "Agilité",
            style = MaterialTheme.typography.labelSmall,
            color = CrispWhite,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 26.dp, bottom = 4.dp)
        )
        Text(
            text = "Vitalité",
            style = MaterialTheme.typography.labelSmall,
            color = CrispWhite,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 26.dp, bottom = 4.dp)
        )
        Text(
            text = "Maîtrise",
            style = MaterialTheme.typography.labelSmall,
            color = CrispWhite,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp, bottom = 36.dp)
        )
    }
}

@Composable
private fun StepsSevenDayBarChart(
    days: List<DayActivityEntry>,
    goal: Int,
    modifier: Modifier = Modifier
) {
    val safeDays = if (days.isEmpty()) List(7) { DayActivityEntry("") } else days
    val maxVal = maxOf(goal, safeDays.maxOfOrNull { it.steps } ?: goal, 1000).toFloat()
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f) }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val padTop = 8f
            val padBottom = 6f
            val chartH = (size.height - padTop - padBottom).coerceAtLeast(20f)

            // Dashed 10 000 steps goal line
            val goalY = size.height - padBottom - chartH * (goal / maxVal).coerceIn(0f, 1f)
            drawLine(
                color = FormGood.copy(alpha = 0.45f),
                start = Offset(0f, goalY),
                end = Offset(size.width, goalY),
                strokeWidth = 1.6f,
                pathEffect = dash
            )

            val count = safeDays.size.coerceAtLeast(1)
            val gap = 14f
            val barW = ((size.width - gap * (count - 1)) / count).coerceAtLeast(8f)

            safeDays.forEachIndexed { idx, day ->
                val x = idx * (barW + gap)
                // Background track
                drawRoundRect(
                    color = ObsidianBg,
                    topLeft = Offset(x, padTop),
                    size = Size(barW, chartH),
                    cornerRadius = CornerRadius(6f, 6f)
                )
                val frac = (day.steps / maxVal).coerceIn(0f, 1f)
                val h = if (day.steps > 0) maxOf(chartH * frac, 6f) else 0f
                if (h > 0f) {
                    val barColor = when {
                        day.steps >= goal -> FormGood
                        day.steps >= goal / 2 -> SignalOrange
                        else -> FormCaution
                    }
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, size.height - padBottom - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            safeDays.forEach { day ->
                Text(
                    text = frenchDayInitial(day.dateKey),
                    style = MaterialTheme.typography.labelSmall,
                    color = SteelGray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Refined studio-pro modal shown when crossing major milestones (Rank Up, Level Up, 10k Steps, 7/7 Streak). */
@Composable
fun ProgressionCelebrationDialog(
    delta: ProgressionDelta,
    onDismiss: () -> Unit
) {
    val accent = delta.rankedUpTo?.let { Color(it.accentHex) } ?: FormGood
    val title = when {
        delta.rankedUpTo != null -> "Promotion Rang ${delta.rankedUpTo.code}"
        delta.leveledUpTo != null -> "Niveau ${delta.leveledUpTo} atteint"
        delta.stepGoal10kAwarded -> "Objectif 10 000 pas validé"
        delta.weeklyStreakBonusAwarded -> "Semaine parfaite 7/7"
        else -> "Objectif validé"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CharcoalSurface,
        titleContentColor = CrispWhite,
        textContentColor = SteelGray,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = CrispWhite,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (delta.xpGained > 0) {
                    Text(
                        text = "+${delta.xpGained} XP ajoutés à votre progression.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (delta.completedQuestTitles.isNotEmpty()) {
                    Text(
                        text = "Quête validée : ${delta.completedQuestTitles.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CrispWhite
                    )
                }
                if (delta.newlyUnlockedBadges.isNotEmpty()) {
                    Text(
                        text = "Badge débloqué : ${delta.newlyUnlockedBadges.joinToString(", ") { it.titleFr }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FormGood
                    )
                }
                if (delta.newlyUnlockedTitles.isNotEmpty()) {
                    Text(
                        text = "Nouveau titre disponible : ${delta.newlyUnlockedTitles.joinToString(", ") { it.labelFr }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SignalOrange
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = CrispWhite)
            ) {
                Text("Continuer", fontWeight = FontWeight.Bold)
            }
        }
    )
}
