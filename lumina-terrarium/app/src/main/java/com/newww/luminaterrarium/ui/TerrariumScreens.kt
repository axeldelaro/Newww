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

private val BgDark = Color(0xFF071117)
private val CardDark = Color(0xFF11222C)
private val GlassCard = Color(0xCC0D1E28)
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
fun LuminaTerrariumApp(
    viewModel: TerrariumViewModel,
    onRequestStepPermission: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val battery by viewModel.battery.collectAsState()
    val tilt by viewModel.tilt.collectAsState()
    val pedometerHealth by viewModel.pedometerHealth.collectAsState()
    val dayPhase by viewModel.dayPhase.collectAsState()
    val pulse by viewModel.visualPulse.collectAsState()
    val miniGameMode by viewModel.miniGameMode.collectAsState()
    val miniGameScore by viewModel.miniGameScore.collectAsState()
    val miniGameSec by viewModel.miniGameSecondsLeft.collectAsState()
    val melodyHighlight by viewModel.melodyHighlightPad.collectAsState()
    val melodyDemo by viewModel.melodyShowingDemo.collectAsState()
    val decorEditMode by viewModel.decorEditMode.collectAsState()

    var currentTab by remember { mutableStateOf(TerrariumTab.HOME) }
    var showFoodDialog by remember { mutableStateOf(false) }
    var showGamesDialog by remember { mutableStateOf(false) }
    var showCustomizeDialog by remember { mutableStateOf(false) }

    val spirit = state.activeSpirit
    val mood = TerrariumSimulationEngine.computeMood(spirit, battery, dayPhase)

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0B1820), tonalElevation = 4.dp) {
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (currentTab) {
                TerrariumTab.HOME -> TerrariumMainTab(
                    state = state,
                    spirit = spirit,
                    mood = mood,
                    dayPhase = dayPhase,
                    battery = battery,
                    tilt = tilt,
                    pedometerHealth = pedometerHealth,
                    pulse = pulse,
                    miniGameMode = miniGameMode,
                    miniGameScore = miniGameScore,
                    miniGameSec = miniGameSec,
                    melodyHighlight = melodyHighlight,
                    melodyDemo = melodyDemo,
                    decorEditMode = decorEditMode,
                    onRequestStepPermission = onRequestStepPermission,
                    onPet = viewModel::petSpirit,
                    onWipeFog = viewModel::wipeGlassFog,
                    onTapGlass = viewModel::tapGlass,
                    onMoveDecor = viewModel::moveDecor,
                    onToggleDecorEdit = viewModel::toggleDecorEditMode,
                    onMiniGamePoint = viewModel::onMiniGameScorePoint,
                    onMelodyPadTap = viewModel::onMelodyPadTap,
                    onStopMiniGame = viewModel::finishMiniGame,
                    onOpenFood = { showFoodDialog = true },
                    onMist = viewModel::mistTerrarium,
                    onToggleLullaby = viewModel::toggleLullaby,
                    onOpenGames = { showGamesDialog = true },
                    onLightChange = viewModel::setLightLevel,
                    onSelectLampTint = viewModel::setLampTint
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
                    pedometerHealth = pedometerHealth,
                    onRequestStepPermission = onRequestStepPermission,
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

    if (showGamesDialog) {
        MiniGamesPickerDialog(
            onDismiss = { showGamesDialog = false },
            onPick = { mode ->
                showGamesDialog = false
                viewModel.startMiniGame(mode)
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
    pedometerHealth: PedometerHealth,
    pulse: com.newww.luminaterrarium.viewmodel.VisualPulseEvent?,
    miniGameMode: MiniGameMode,
    miniGameScore: Int,
    miniGameSec: Int,
    melodyHighlight: Int,
    melodyDemo: Boolean,
    decorEditMode: Boolean,
    onRequestStepPermission: () -> Unit,
    onPet: (Float, Float) -> Unit,
    onWipeFog: (Float, Float) -> Unit,
    onTapGlass: (Float, Float) -> Unit,
    onMoveDecor: (String, Float, Float) -> Unit,
    onToggleDecorEdit: () -> Unit,
    onMiniGamePoint: (Int, Float, Float) -> Unit,
    onMelodyPadTap: (Int) -> Unit,
    onStopMiniGame: () -> Unit,
    onOpenFood: () -> Unit,
    onMist: () -> Unit,
    onToggleLullaby: () -> Unit,
    onOpenGames: () -> Unit,
    onLightChange: (Float) -> Unit,
    onSelectLampTint: (LampTint) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Bandeau permission podomètre si pas encore accordée
        if (!pedometerHealth.permissionGranted) {
            Surface(
                color = Color(0xFF2B2214),
                modifier = Modifier.fillMaxWidth().clickable { onRequestStepPermission() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "👣 Autorise « Activité physique » pour récupérer tes pas auto toute la journée",
                        color = Color(0xFFFFD56B),
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text("Autoriser", color = AccentMint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        // Scène plein-écran interactive 60 FPS avec HUD glassmorphism superposé
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            TerrariumCanvas(
                spirit = spirit,
                mood = mood,
                dayPhase = dayPhase,
                battery = battery,
                tilt = tilt,
                pulseEvent = pulse,
                miniGameMode = miniGameMode,
                decorEditMode = decorEditMode,
                melodyHighlightPad = melodyHighlight,
                onPet = onPet,
                onWipeFog = onWipeFog,
                onTapGlass = onTapGlass,
                onMoveDecor = onMoveDecor,
                onMiniGamePoint = onMiniGamePoint,
                onMelodyPadTap = onMelodyPadTap
            )

            // En-tête Glassmorphism flottant
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = RoundedCornerShape(16.dp), color = GlassCard) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                "${spirit.name} • ${spirit.stage.labelFr}",
                                color = TextMain,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${mood.emoji} ${mood.labelFr} • ${dayPhase.labelFr} • ${spirit.xp} XP",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (decorEditMode) AccentMint else GlassCard,
                            modifier = Modifier.clickable { onToggleDecorEdit() }
                        ) {
                            Text(
                                if (decorEditMode) "✓ Fini" else "✋ Décors",
                                color = if (decorEditMode) BgDark else TextMain,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                            )
                        }
                        Surface(shape = RoundedCornerShape(14.dp), color = GlassCard) {
                            Text(
                                "💧 ${state.dewCurrency}",
                                color = AccentMint,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Pilules temps réel : Batterie, Pas auto du jour, Buée
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(
                        if (battery.isCharging) "⚡ ${battery.levelPercent}%" else "🔋 ${battery.levelPercent}%",
                        if (battery.levelPercent <= 20 && !battery.isCharging) Color(0xFFFF8A80) else AccentMint
                    )
                    StatusChip("👣 ${state.dailyQuest.stepsToday} pas auto", Color(0xFF7CE8FF))
                    StatusChip("❤️ ${spirit.affection.toInt()}%", Color(0xFFFF8FB8))
                    if (spirit.glassFog > 0.25f) {
                        StatusChip("🫧 Essuie la vitre !", Color(0xFFD6FCFF))
                    }
                }

                // Bulle de dialogue vivante ou HUD de mini-jeu
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = GlassCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentMint.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                decorEditMode -> "✋ Glisse tes décorations où tu veux dans le bocal !"
                                miniGameMode == MiniGameMode.MELODY ->
                                    if (melodyDemo) "🎵 Écoute la mélodie lumineuse…" else "🎵 À toi ! Score : $miniGameScore"
                                miniGameMode != MiniGameMode.NONE ->
                                    "${miniGameMode.emoji} ${miniGameMode.labelFr} : $miniGameScore pts • ${miniGameSec}s"
                                else -> spirit.lastSpeech
                            },
                            color = TextMain,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (miniGameMode != MiniGameMode.NONE) {
                            Text(
                                "Stop",
                                color = Color(0xFFFF8A80),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp).clickable { onStopMiniGame() }
                            )
                        }
                    }
                }
            }
        }

        // Panneau inférieur de soins + lampe couleur
        Surface(
            color = Color(0xFF0C1A22),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VitalBar("Faim", spirit.hunger, Color(0xFFFFB74D), Modifier.weight(1f))
                    VitalBar("Brume", spirit.hydration, Color(0xFF4FC3F7), Modifier.weight(1f))
                    VitalBar("Énergie", spirit.energy, Color(0xFFAED581), Modifier.weight(1f))
                    VitalBar("Joie", spirit.happiness, Color(0xFFF48FB1), Modifier.weight(1f))
                }

                // Lampe + 4 teintes lumineuses
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("☀️", fontSize = 14.sp)
                    Slider(
                        value = spirit.lightLevel,
                        onValueChange = onLightChange,
                        valueRange = 5f..100f,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(spirit.lampTint.colorHex),
                            activeTrackColor = Color(spirit.lampTint.colorHex)
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LampTint.entries.forEach { tint ->
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(tint.colorHex))
                                    .border(if (spirit.lampTint == tint) 2.5.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { onSelectLampTint(tint) }
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CareActionButton("🍓", "Nourrir", onOpenFood, Modifier.weight(1f))
                    CareActionButton("💧", "Brume", onMist, Modifier.weight(1f))
                    CareActionButton(
                        if (spirit.isSleeping) "☀️" else "🌙",
                        if (spirit.isSleeping) "Éveiller" else "Bercer",
                        onToggleLullaby,
                        Modifier.weight(1f)
                    )
                    CareActionButton("🎮", "3 Jeux", onOpenGames, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, accent: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = GlassCard) {
        Text(
            text = text,
            color = accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun VitalBar(label: String, value: Float, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextMuted, fontSize = 10.sp)
            Text("${value.toInt()}%", color = TextMain, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { (value / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = Color(0xFF1D3440)
        )
    }
}

@Composable
private fun CareActionButton(emoji: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CardDark, contentColor = TextMain)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 15.sp)
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
                    Text("Chacun possède son propre bocal et son évolution", color = TextMuted, fontSize = 12.sp)
                }
                FilledTonalButton(onClick = onOpenCustomize) { Text("Personnaliser") }
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
                modifier = Modifier.fillMaxWidth().clickable { onSelectElement(element) }
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(54.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(accent, Color(element.secondaryColorHex)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when (element) {
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
                        Text("${sp.name} ${if (isSelected) "• ACTIF" else ""}", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("${element.titleFr} • ${sp.stage.labelFr} (${sp.xp} XP)", color = accent, fontSize = 12.sp)
                        Text(element.subtitleFr, color = TextMuted, fontSize = 12.sp)
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Atelier du Terrarium", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("💧 ${state.dewCurrency} Rosée", color = AccentMint, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Text("Ambiance sonore PCM hors-ligne", color = AccentMint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AmbientSoundscape.entries) { sc ->
                    FilterChip(selected = state.soundscape == sc, onClick = { onSelectSoundscape(sc) }, label = { Text(sc.labelFr) })
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Effets sonores tactiles", color = TextMain, fontSize = 13.sp)
                Switch(checked = state.sfxEnabled, onCheckedChange = onToggleSfx)
            }
        }
        item { Text("6 Styles de Bocaux", color = AccentMint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        items(JarStyle.entries) { jar ->
            val unlocked = jar in state.unlockedJarStyles
            val selected = active.jarStyle == jar
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CardDark,
                border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, AccentMint) else null,
                modifier = Modifier.fillMaxWidth().clickable { onSelectJar(jar) }
            ) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(jar.labelFr, color = TextMain, fontWeight = FontWeight.Bold)
                        Text(jar.descriptionFr, color = TextMuted, fontSize = 12.sp)
                    }
                    Text(
                        when { selected -> "Équipé"; unlocked -> "Choisir"; else -> "💧 ${jar.costDew}" },
                        color = if (selected || unlocked) AccentMint else Color(0xFFFFD54F),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        item {
            Text("16 Décorations déplaçables (${active.activeDecorIds.size} placées)", color = AccentMint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Astuce : appuie sur « ✋ Décors » dans le Terrarium pour déplacer tes objets au doigt.", color = TextMuted, fontSize = 11.sp)
        }
        items(DecorItem.entries) { item ->
            val unlocked = item.id in state.unlockedDecorIds
            val placed = item.id in active.activeDecorIds
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CardDark,
                border = if (placed) androidx.compose.foundation.BorderStroke(1.dp, AccentMint) else null,
                modifier = Modifier.fillMaxWidth().clickable { onToggleDecor(item) }
            ) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(item.labelFr, color = TextMain, fontWeight = FontWeight.SemiBold)
                        Text(item.categoryFr, color = TextMuted, fontSize = 11.sp)
                    }
                    Text(
                        when { placed -> "Placé ✓"; unlocked -> "Placer"; else -> "💧 ${item.costDew}" },
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
    pedometerHealth: PedometerHealth,
    onRequestStepPermission: () -> Unit,
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
        item { Text("Podomètre Auto, Rituels & Journal", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold) }

        // Graphique 7 jours des pas récupérés automatiquement
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardDark, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("👣 Activité & Synchro Automatique", color = AccentMint, fontWeight = FontWeight.Bold)
                    Text(pedometerHealth.statusText, color = TextMain, fontSize = 12.sp)
                    Text("Aujourd'hui : ${q.stepsToday} pas • Total converti : ${state.totalStepsConverted} pas", color = TextMuted, fontSize = 12.sp)
                    if (!pedometerHealth.permissionGranted) {
                        Button(onClick = onRequestStepPermission) { Text("Autoriser le podomètre automatique") }
                    }
                    val entries = state.weeklySteps.entries.sortedBy { it.key }.takeLast(7)
                    if (entries.isNotEmpty()) {
                        val maxVal = entries.maxOf { it.value }.coerceAtLeast(1000)
                        Row(
                            modifier = Modifier.fillMaxWidth().height(74.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            entries.forEach { (day, steps) ->
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.7f)
                                            .height((52f * (steps.toFloat() / maxVal)).dp.coerceAtLeast(6.dp))
                                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                            .background(AccentMint)
                                    )
                                    Text(day.takeLast(2), color = TextMuted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = onAddWalkSteps) { Text("Ajouter manuellement +500 pas") }
                }
            }
        }

        item {
            QuestCard("Caresser son esprit 3 fois", q.petsCount, 3, 20, "q_pet", q.claimedIds, onClaimQuest)
            Spacer(Modifier.height(8.dp))
            QuestCard("Nourrir & vaporiser (2 soins)", q.feedsCount + q.mistsCount, 2, 25, "q_care", q.claimedIds, onClaimQuest)
            Spacer(Modifier.height(8.dp))
            QuestCard("Essuyer la buée du bocal", q.wipesCount, 1, 20, "q_wipe", q.claimedIds, onClaimQuest)
            Spacer(Modifier.height(8.dp))
            QuestCard("Marcher 1 000 pas dans la journée", q.stepsToday, 1000, 45, "q_steps", q.claimedIds, onClaimQuest)
            Spacer(Modifier.height(8.dp))
            QuestCard("Gagner 1 mini-jeu", q.miniGamesWon, 1, 35, "q_game", q.claimedIds, onClaimQuest)
        }

        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardDark, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Rappels locaux doux", color = TextMain, fontWeight = FontWeight.SemiBold)
                        Switch(checked = state.remindersEnabled, onCheckedChange = onToggleReminders)
                    }
                    Text("Widget écran d'accueil : appui long sur l'écran Android → Widgets → Lumina Terrarium.", color = AccentMint, fontSize = 11.sp)
                }
            }
        }

        item { Text("Souvenirs du Terrarium", color = AccentMint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
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
private fun QuestCard(title: String, current: Int, target: Int, rewardDew: Int, id: String, claimed: List<String>, onClaim: (String, Int) -> Unit) {
    val isClaimed = id in claimed
    val isDone = current >= target
    Surface(shape = RoundedCornerShape(14.dp), color = CardDark, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("${current.coerceAtMost(target)} / $target • +$rewardDew Rosée", color = TextMuted, fontSize = 12.sp)
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
private fun MiniGamesPickerDialog(onDismiss: () -> Unit, onPick: (MiniGameMode) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text("Choisir un mini-jeu interactif", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(MiniGameMode.FIREFLIES, MiniGameMode.STAR_RAIN, MiniGameMode.MELODY).forEach { mode ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = BgDark,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(mode) }
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(mode.emoji, fontSize = 24.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mode.labelFr, color = AccentMint, fontWeight = FontWeight.Bold)
                                Text(mode.descFr, color = TextMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer", color = TextMuted) } }
    )
}

@Composable
private fun FoodPickerDialog(favorite: FoodType, onDismiss: () -> Unit, onSelect: (FoodType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text("Lancer une friandise", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FoodType.entries.forEach { food ->
                    val isFav = food == favorite
                    Surface(shape = RoundedCornerShape(12.dp), color = BgDark, modifier = Modifier.fillMaxWidth().clickable { onSelect(food) }) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("${food.emoji} ${food.labelFr}${if (isFav) " ★" else ""}", color = Color(food.colorHex), fontWeight = FontWeight.Bold)
                                Text("+${food.hungerGain.toInt()} Faim • +${food.xpGain} XP", color = TextMuted, fontSize = 12.sp)
                            }
                            Text("Lancer", color = AccentMint, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer", color = TextMuted) } }
    )
}

@Composable
private fun CustomizeSpiritDialog(spirit: SpiritProfile, onDismiss: () -> Unit, onSave: (String, Long) -> Unit) {
    var name by remember { mutableStateOf(spirit.name) }
    var selectedHex by remember { mutableLongStateOf(spirit.customAuraHex) }
    val palette = listOf(0xFF6EF5A8L, 0xFF66DCFFL, 0xFFFFA552L, 0xFFE3ECFFL, 0xFFFF8FB8L, 0xFFC59BFFL, 0xFFFFE57FL, 0xFF80CBC4L)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text("Personnaliser ${spirit.element.titleFr}", color = TextMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom de ton esprit") }, singleLine = true)
                Text("Couleur de l'aura", color = TextMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEach { hex ->
                        Box(
                            modifier = Modifier.size(30.dp).clip(CircleShape).background(Color(hex))
                                .border(if (selectedHex == hex) 3.dp else 0.dp, Color.White, CircleShape)
                                .clickable { selectedHex = hex }
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(name, selectedHex) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = TextMuted) } }
    )
}
