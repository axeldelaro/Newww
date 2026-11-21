package com.newww.luminaterrarium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newww.luminaterrarium.engine.TerrariumSimulationEngine
import com.newww.luminaterrarium.model.*
import com.newww.luminaterrarium.ui.canvas.TerrariumCanvas
import com.newww.luminaterrarium.viewmodel.TerrariumViewModel
import java.text.SimpleDateFormat
import java.util.*

private val BgDark = Color(0xFF0A141A)
private val CardDark = Color(0xFF13242D)
private val AccentMint = Color(0xFF7CF7B4)
private val TextMain = Color(0xFFF1FFF8)
private val TextMuted = Color(0xFF9AC2B7)

private enum class TerrariumTab(val label: String, val icon: ImageVector) {
    HOME("Terrarium", Icons.Rounded.Spa),
    SPIRITS("Esprits", Icons.Rounded.AutoAwesome),
    WORKSHOP("Atelier", Icons.Rounded.Palette),
    JOURNAL("Journal", Icons.Rounded.Book)
}

@Composable
fun LuminaTerrariumApp(viewModel: TerrariumViewModel) {
    val state by viewModel.state.collectAsState()
    val battery by viewModel.battery.collectAsState()
    val tilt by viewModel.tilt.collectAsState()
    val dayPhase by viewModel.dayPhase.collectAsState()
    val pulse by viewModel.visualPulse.collectAsState()
    val miniGameActive by viewModel.miniGameActive.collectAsState()
    val miniGameScore by viewModel.miniGameScore.collectAsState()
    val miniGameSec by viewModel.miniGameSecondsLeft.collectAsState()

    var currentTab by remember { mutableStateOf(TerrariumTab.HOME) }
    var showFoodDialog by remember { mutableStateOf(false) }
    var showCustomizeDialog by remember { mutableStateOf(false) }

    val spirit = state.activeSpirit
    val mood = TerrariumSimulationEngine.computeMood(spirit, battery, dayPhase)

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0E1C24),
                tonalElevation = 4.dp
            ) {
                TerrariumTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BgDark,
                            selectedTextColor = AccentMint,
                            indicatorColor = AccentMint,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (currentTab) {
                TerrariumTab.HOME -> TerrariumMainTab(
                    state = state,
                    spirit = spirit,
                    mood = mood,
                    dayPhase = dayPhase,
                    battery = battery,
                    tilt = tilt,
                    pulse = pulse,
                    miniGameActive = miniGameActive,
                    miniGameScore = miniGameScore,
                    miniGameSec = miniGameSec,
                    onPet = viewModel::petSpirit,
                    onTapGlass = viewModel::tapGlass,
                    onCatchOrb = viewModel::onOrbCaught,
                    onOpenFood = { showFoodDialog = true },
                    onMist = viewModel::mistTerrarium,
                    onToggleLullaby = viewModel::toggleLullaby,
                    onStartMiniGame = viewModel::startMiniGame,
                    onLightChange = viewModel::setLightLevel,
                    onAddManualWalk = { viewModel.onStepsRecorded(250) }
                )
                TerrariumTab.SPIRITS -> SpiritsTab(
                    state = state,
                    onSelectElement = viewModel::switchElement,
                    onOpenCustomize = { showCustomizeDialog = true }
                )
                TerrariumTab.WORKSHOP -> WorkshopTab(
                    state = state,
                    onToggleDecor = viewModel::unlockOrToggleDecor,
                    onSelectJar = viewModel::unlockOrSelectJar,
                    onSelectSoundscape = viewModel::setSoundscape,
                    onToggleSfx = viewModel::setSfxEnabled
                )
                TerrariumTab.JOURNAL -> JournalTab(
                    state = state,
                    onClaimQuest = viewModel::claimDailyQuest,
                    onToggleReminders = viewModel::setRemindersEnabled,
                    onAddWalkSteps = { viewModel.onStepsRecorded(500) }
                )
            }
        }
    }

    if (showFoodDialog) {
        FoodPickerDialog(
            favorite = spirit.element.favoriteFood,
            onDismiss = { showFoodDialog = false },
            onSelect = { food ->
                showFoodDialog = false
                viewModel.feedSpirit(food)
            }
        )
    }

    if (showCustomizeDialog) {
        CustomizeSpiritDialog(
            spirit = spirit,
            onDismiss = { showCustomizeDialog = false },
            onSave = { name, colorHex ->
                showCustomizeDialog = false
                viewModel.customizeSpirit(name, colorHex)
            }
        )
    }
}

@Composable
private fun TerrariumMainTab(
    state: TerrariumAppState,
    spirit: SpiritProfile,
    mood: SpiritMood,
    dayPhase: DayPhase,
    battery: BatterySnapshot,
    tilt: TiltSnapshot,
    pulse: com.newww.luminaterrarium.viewmodel.VisualPulseEvent?,
    miniGameActive: Boolean,
    miniGameScore: Int,
    miniGameSec: Int,
    onPet: (Float, Float) -> Unit,
    onTapGlass: (Float, Float) -> Unit,
    onCatchOrb: (Float, Float) -> Unit,
    onOpenFood: () -> Unit,
    onMist: () -> Unit,
    onToggleLullaby: () -> Unit,
    onStartMiniGame: () -> Unit,
    onLightChange: (Float) -> Unit,
    onAddManualWalk: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Barre d'état capteurs réels + monnaie Rosée
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "${spirit.name} • ${spirit.stage.labelFr}",
                    color = TextMain,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${mood.emoji} ${mood.labelFr} • ${dayPhase.labelFr} • Niv.${spirit.stage.level} (${spirit.xp} XP)",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardDark
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("💧 ${state.dewCurrency} Rosée", color = AccentMint, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        // Pilules capteurs temps réel : Batterie, Pas, Ambiance
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(
                text = if (battery.isCharging) "⚡ ${battery.levelPercent}%" else "🔋 ${battery.levelPercent}%",
                accent = if (battery.levelPercent <= 20 && !battery.isCharging) Color(0xFFFF8A80) else AccentMint
            )
            StatusChip(
                text = "👣 ${state.dailyQuest.stepsToday} pas",
                accent = Color(0xFF7CE8FF),
                onClick = onAddManualWalk
            )
            StatusChip(
                text = "❤️ ${spirit.affection.toInt()}%",
                accent = Color(0xFFFF8FB8)
            )
        }

        // Zone interactive du Terrarium 60 FPS
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 6.dp)
        ) {
            TerrariumCanvas(
                spirit = spirit,
                mood = mood,
                dayPhase = dayPhase,
                battery = battery,
                tilt = tilt,
                pulseEvent = pulse,
                miniGameActive = miniGameActive,
                onPet = onPet,
                onTapGlass = onTapGlass,
                onCatchOrb = onCatchOrb
            )

            // Bulle de dialogue vivante
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xCC0D1E27),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentMint.copy(alpha = 0.35f))
            ) {
                Text(
                    text = if (miniGameActive) {
                        "✨ Chasse aux Lucioles : $miniGameScore attrapées • ${miniGameSec}s"
                    } else {
                        spirit.lastSpeech
                    },
                    color = TextMain,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
        }

        // Jauges de besoins + curseur de lumière + boutons de soins
        Surface(
            color = Color(0xFF0E1D25),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VitalBar("Faim", spirit.hunger, Color(0xFFFFB74D), Modifier.weight(1f))
                    VitalBar("Brume", spirit.hydration, Color(0xFF4FC3F7), Modifier.weight(1f))
                    VitalBar("Énergie", spirit.energy, Color(0xFFAED581), Modifier.weight(1f))
                    VitalBar("Joie", spirit.happiness, Color(0xFFF48FB1), Modifier.weight(1f))
                }

                Spacer(Modifier.height(6.dp))

                // Contrôle de la lampe lumineuse du bocal
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("☀️ Lumière", color = TextMuted, fontSize = 12.sp)
                    Slider(
                        value = spirit.lightLevel,
                        onValueChange = onLightChange,
                        valueRange = 5f..100f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFFE082),
                            activeTrackColor = Color(0xFFFFE082)
                        )
                    )
                    Text("${spirit.lightLevel.toInt()}%", color = TextMain, fontSize = 12.sp)
                }

                // Boutons de soins quotidiens
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CareActionButton("🍓", "Nourrir", onOpenFood, Modifier.weight(1f))
                    CareActionButton("💧", "Brume", onMist, Modifier.weight(1f))
                    CareActionButton(
                        if (spirit.isSleeping) "☀️" else "🌙",
                        if (spirit.isSleeping) "Éveiller" else "Bercer",
                        onToggleLullaby,
                        Modifier.weight(1f)
                    )
                    CareActionButton("✨", "Lucioles", onStartMiniGame, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, accent: Color, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CardDark,
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun VitalBar(label: String, value: Float, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = TextMuted, fontSize = 11.sp)
            Text("${value.toInt()}%", color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { (value / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = Color(0xFF1F3845)
        )
    }
}

@Composable
private fun CareActionButton(emoji: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CardDark, contentColor = TextMain)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 16.sp)
            Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SpiritsTab(
    state: TerrariumAppState,
    onSelectElement: (SpiritElement) -> Unit,
    onOpenCustomize: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Tes 4 Esprits Élémentaires", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Change de bocal à tout moment, chacun évolue à son rythme", color = TextMuted, fontSize = 12.sp)
                }
                FilledTonalButton(onClick = onOpenCustomize) {
                    Text("Personnaliser")
                }
            }
        }

        items(SpiritElement.entries) { element ->
            val sp = state.spirits[element] ?: SpiritProfile(element)
            val isSelected = state.activeElement == element
            val accent = Color(sp.customAuraHex)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardDark,
                border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, accent) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectElement(element) }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(accent, Color(element.secondaryColorHex)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (element) {
                                SpiritElement.MOSS -> "🌿"
                                SpiritElement.WATER -> "💧"
                                SpiritElement.EMBER -> "🔥"
                                SpiritElement.CLOUD -> "☁️"
                            },
                            fontSize = 26.sp
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sp.name, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            if (isSelected) {
                                Text("ACTIF", color = BgDark, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(accent)
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Text("${element.titleFr} • ${sp.stage.labelFr} (${sp.xp} XP)", color = accent, fontSize = 12.sp)
                        Text(element.subtitleFr, color = TextMuted, fontSize = 12.sp)
                        Text(
                            "Favori : ${element.favoriteFood.labelFr} • Affection ${sp.affection.toInt()}%",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkshopTab(
    state: TerrariumAppState,
    onToggleDecor: (DecorItem) -> Unit,
    onSelectJar: (JarStyle) -> Unit,
    onSelectSoundscape: (AmbientSoundscape) -> Unit,
    onToggleSfx: (Boolean) -> Unit
) {
    val active = state.activeSpirit
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Atelier du Terrarium", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("💧 ${state.dewCurrency} Rosée", color = AccentMint, fontWeight = FontWeight.Bold)
            }
        }

        // Ambiances sonores PCM hors-ligne
        item {
            Text("Ambiance sonore 100 % hors-ligne", color = AccentMint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AmbientSoundscape.entries) { sc ->
                    FilterChip(
                        selected = state.soundscape == sc,
                        onClick = { onSelectSoundscape(sc) },
                        label = { Text(sc.labelFr) }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Effets sonores tactiles", color = TextMain, fontSize = 13.sp)
                Switch(checked = state.sfxEnabled, onCheckedChange = onToggleSfx)
            }
        }

        // Styles de bocaux
        item {
            Text("Styles de Bocaux", color = AccentMint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        items(JarStyle.entries) { jar ->
            val unlocked = jar in state.unlockedJarStyles
            val selected = active.jarStyle == jar
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CardDark,
                border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, AccentMint) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectJar(jar) }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(jar.labelFr, color = TextMain, fontWeight = FontWeight.Bold)
                        Text(jar.descriptionFr, color = TextMuted, fontSize = 12.sp)
                    }
                    Text(
                        text = when {
                            selected -> "Équipé"
                            unlocked -> "Choisir"
                            else -> "💧 ${jar.costDew}"
                        },
                        color = if (selected || unlocked) AccentMint else Color(0xFFFFD54F),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Décorations
        item {
            Text("Décorations du bocal (${active.activeDecorIds.size} placées)", color = AccentMint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        items(DecorItem.entries) { item ->
            val unlocked = item.id in state.unlockedDecorIds
            val placed = item.id in active.activeDecorIds
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CardDark,
                border = if (placed) androidx.compose.foundation.BorderStroke(1.dp, AccentMint) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleDecor(item) }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(item.labelFr, color = TextMain, fontWeight = FontWeight.SemiBold)
                        Text(item.categoryFr, color = TextMuted, fontSize = 12.sp)
                    }
                    Text(
                        text = when {
                            placed -> "Placé ✓"
                            unlocked -> "Placer"
                            else -> "💧 ${item.costDew}"
                        },
                        color = when {
                            placed -> AccentMint
                            unlocked -> TextMain
                            state.dewCurrency >= item.costDew -> Color(0xFFFFD54F)
                            else -> TextMuted
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun JournalTab(
    state: TerrariumAppState,
    onClaimQuest: (String, Int) -> Unit,
    onToggleReminders: (Boolean) -> Unit,
    onAddWalkSteps: () -> Unit
) {
    val q = state.dailyQuest
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Rituels du Jour & Journal", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        item {
            QuestCard("Caresser son esprit 3 fois", q.petsCount, 3, 20, "q_pet", q.claimedIds, onClaimQuest)
            Spacer(Modifier.height(8.dp))
            QuestCard("Nourrir & vaporiser (2 soins)", q.feedsCount + q.mistsCount, 2, 25, "q_care", q.claimedIds, onClaimQuest)
            Spacer(Modifier.height(8.dp))
            QuestCard("Marcher 500 pas aujourd'hui", q.stepsToday, 500, 35, "q_steps", q.claimedIds, onClaimQuest)
            Spacer(Modifier.height(8.dp))
            QuestCard("Jouer aux Lucioles", q.miniGamesWon, 1, 30, "q_game", q.claimedIds, onClaimQuest)
        }

        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardDark, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Rappels locaux doux", color = TextMain, fontWeight = FontWeight.SemiBold)
                            Text("Notifications hors-ligne si ton esprit a faim ou soif", color = TextMuted, fontSize = 12.sp)
                        }
                        Switch(checked = state.remindersEnabled, onCheckedChange = onToggleReminders)
                    }
                    Text(
                        "Widget écran d'accueil : appui long sur ton écran d'accueil Android → Widgets → Lumina Terrarium.",
                        color = AccentMint,
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = onAddWalkSteps) {
                        Text("Enregistrer une petite marche (+500 pas)")
                    }
                }
            }
        }

        item {
            Text("Souvenirs du Terrarium", color = AccentMint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        items(state.journal) { entry ->
            Surface(shape = RoundedCornerShape(14.dp), color = CardDark, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.icon, fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(entry.detail, color = TextMuted, fontSize = 12.sp)
                        Text(fmt.format(Date(entry.timestampMs)), color = TextMuted.copy(alpha = 0.7f), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestCard(
    title: String,
    current: Int,
    target: Int,
    rewardDew: Int,
    id: String,
    claimed: List<String>,
    onClaim: (String, Int) -> Unit
) {
    val isClaimed = id in claimed
    val isDone = current >= target
    Surface(shape = RoundedCornerShape(14.dp), color = CardDark, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("${current.coerceAtMost(target)} / $target • Récompense : +$rewardDew Rosée", color = TextMuted, fontSize = 12.sp)
            }
            when {
                isClaimed -> Text("Reçu ✓", color = AccentMint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                isDone -> Button(onClick = { onClaim(id, rewardDew) }) { Text("Réclamer") }
                else -> Text("${((current.toFloat() / target) * 100).toInt().coerceIn(0, 99)}%", color = TextMuted)
            }
        }
    }
}

@Composable
private fun FoodPickerDialog(
    favorite: FoodType,
    onDismiss: () -> Unit,
    onSelect: (FoodType) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text("Choisir une friandise", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FoodType.entries.forEach { food ->
                    val isFav = food == favorite
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = BgDark,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(food) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (isFav) "${food.labelFr} ★ Favori" else food.labelFr,
                                    color = Color(food.colorHex),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "+${food.hungerGain.toInt()} Faim • +${food.xpGain} XP",
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                            Text("Donner", color = AccentMint, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fermer", color = TextMuted) }
        }
    )
}

@Composable
private fun CustomizeSpiritDialog(
    spirit: SpiritProfile,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit
) {
    var name by remember { mutableStateOf(spirit.name) }
    var selectedHex by remember { mutableLongStateOf(spirit.customAuraHex) }
    val palette = listOf(
        0xFF6EF5A8L, 0xFF66DCFFL, 0xFFFFA552L, 0xFFE3ECFFL,
        0xFFFF8FB8L, 0xFFC59BFFL, 0xFFFFE57FL, 0xFF80CBC4L
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text("Personnaliser ${spirit.element.titleFr}", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom de ton esprit") },
                    singleLine = true
                )
                Text("Couleur de l'aura", color = TextMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(hex))
                                .border(
                                    width = if (selectedHex == hex) 3.dp else 0.dp,
                                    color = Color.White,
                                    shape = CircleShape
                                )
                                .clickable { selectedHex = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, selectedHex) }) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", color = TextMuted) }
        }
    )
}
