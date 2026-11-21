package com.newww.stepquest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newww.stepquest.ai.LlmStatus
import com.newww.stepquest.engine.ExpeditionEngine
import com.newww.stepquest.model.*
import com.newww.stepquest.ui.canvas.ExpeditionMapCanvas
import com.newww.stepquest.viewmodel.StepQuestViewModel
import java.text.SimpleDateFormat
import java.util.*

private val BgDeep = Color(0xFF0E0C17)
private val CardBg = Color(0xFF1A1629)
private val AccentGold = Color(0xFFF6C768)
private val AccentMint = Color(0xFF6CF0B5)
private val TextLight = Color(0xFFF6F2FF)
private val TextSoft = Color(0xFFA9A2BF)

private enum class StepTab(val label: String, val icon: ImageVector) {
    JOURNEY("Voyage", Icons.Rounded.Explore),
    CAMP("Feu IA", Icons.Rounded.LocalFireDepartment),
    CRAFT("Atelier", Icons.Rounded.AutoFixHigh),
    HERO("Héros", Icons.Rounded.Person)
}

@Composable
fun StepQuestApp(
    viewModel: StepQuestViewModel,
    onRequestStepPermission: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val pedometer by viewModel.pedometerStatus.collectAsState()
    val llmStatus by viewModel.llmStatus.collectAsState()
    val llmProgress by viewModel.llmPrepProgress.collectAsState()
    val llmLabel by viewModel.llmStatusLabel.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val streamingDraft by viewModel.streamingDraft.collectAsState()
    val mapToast by viewModel.mapToast.collectAsState()

    var currentTab by remember { mutableStateOf(StepTab.JOURNEY) }

    Scaffold(
        containerColor = BgDeep,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF151222)) {
                StepTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BgDeep,
                            selectedTextColor = AccentGold,
                            indicatorColor = AccentGold,
                            unselectedIconColor = TextSoft,
                            unselectedTextColor = TextSoft
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!pedometer.permissionGranted) {
                Surface(
                    color = Color(0xFF2D2212),
                    modifier = Modifier.fillMaxWidth().clickable { onRequestStepPermission() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "👣 Autorise « Activité physique » pour synchroniser automatiquement tes pas de la journée",
                            color = AccentGold,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text("Autoriser", color = AccentMint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            if (llmStatus == LlmStatus.PREPARING_ASSETS || llmStatus == LlmStatus.LOADING_MODEL) {
                Surface(color = Color(0xFF261E3B), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text("🧠 $llmLabel", color = AccentGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { llmProgress.coerceIn(0.05f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = AccentGold,
                            trackColor = Color(0xFF3E3559)
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    StepTab.JOURNEY -> JourneyTab(
                        state = state,
                        mapToast = mapToast,
                        onCollectTreasure = viewModel::collectMapTreasure,
                        onCheerHero = viewModel::cheerAdventurer,
                        onExploreEncounter = viewModel::exploreNearbyEncounter,
                        onAddSteps = viewModel::recordSteps,
                        onClaimMilestone = viewModel::claimStepMilestone,
                        onToggleGear = viewModel::toggleGear,
                        onConfirmPack = viewModel::confirmMorningPack,
                        onFeed = viewModel::feedProvision,
                        onRest = viewModel::restAtCamp,
                        onGoToLetter = {
                            currentTab = StepTab.CAMP
                            viewModel.generateEveningLetter()
                        }
                    )
                    StepTab.CAMP -> CampfireTab(
                        state = state,
                        llmStatus = llmStatus,
                        llmLabel = llmLabel,
                        isGenerating = isGenerating,
                        streamingDraft = streamingDraft,
                        onGenerateLetter = viewModel::generateEveningLetter,
                        onSendChat = viewModel::sendCampMessage,
                        onStop = viewModel::stopGeneration
                    )
                    StepTab.CRAFT -> CraftAndRelicsTab(
                        state = state,
                        onCraft = viewModel::craftRecipe
                    )
                    StepTab.HERO -> HeroTab(
                        state = state,
                        pedometer = pedometer,
                        llmLabel = llmLabel,
                        onRequestStepPermission = onRequestStepPermission,
                        onCustomize = viewModel::customizeAdventurer,
                        onToggleReminders = viewModel::setRemindersEnabled
                    )
                }
            }
        }
    }

    // Fenêtre modale de Rencontre Interactive RPG à 3 choix !
    state.activeEncounter?.let { enc ->
        AlertDialog(
            onDismissRequest = {},
            containerColor = CardBg,
            title = { Text("${enc.emoji} ${enc.titleFr}", color = AccentGold, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(enc.storyFr, color = TextLight, fontSize = 13.sp, lineHeight = 19.sp)
                    enc.choices.forEachIndexed { idx, ch ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF28223D),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AccentGold.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.resolveEncounterChoice(idx) }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("▸ ${ch.labelFr}", color = AccentMint, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                val bonus = buildList {
                                    add("+${ch.xpDelta} XP")
                                    if (ch.coinsDelta > 0) add("+${ch.coinsDelta} 🪙")
                                    ch.rewardIngredient?.let { add("+1 ${it.emoji}") }
                                }.joinToString(" • ")
                                Text(bonus, color = TextSoft, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun JourneyTab(
    state: StepQuestState,
    mapToast: String,
    onCollectTreasure: () -> Unit,
    onCheerHero: () -> Unit,
    onExploreEncounter: () -> Unit,
    onAddSteps: (Int) -> Unit,
    onClaimMilestone: (Int, Int) -> Unit,
    onToggleGear: (GearItem) -> Unit,
    onConfirmPack: () -> Unit,
    onFeed: (ProvisionItem) -> Unit,
    onRest: () -> Unit,
    onGoToLetter: () -> Unit
) {
    val biome = ExpeditionEngine.biomeForSteps(state.totalSteps)
    val landmark = ExpeditionEngine.currentLandmark(state.totalSteps)
    val progress = ExpeditionEngine.biomeProgress(state.totalSteps)
    val kmToday = String.format(Locale.FRANCE, "%.2f", state.todaySteps * 0.00075f)
    val today = ExpeditionEngine.dayKey()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "${state.adventurerClass.emoji} ${state.adventurerName} & ${state.familiar.emoji} • Niv.${state.level}",
                        color = TextLight,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(ExpeditionEngine.currentDayPeriodFr(), color = AccentGold, fontSize = 12.sp)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = CardBg) {
                    Text(
                        "🪙 ${state.coins} • 🔥 ${state.streakDays}j",
                        color = AccentGold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // Carte RPG Interactive 60 FPS
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.5.dp, AccentGold.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
            ) {
                ExpeditionMapCanvas(
                    state = state,
                    onCollectTreasure = onCollectTreasure,
                    onCheerHero = onCheerHero
                )
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xCC120F1C)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(biome.nameFr, color = AccentGold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Étape : $landmark (${(progress * 100).toInt()} %)", color = TextLight, fontSize = 11.sp)
                    }
                }
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xCC120F1C)
                ) {
                    Text(
                        mapToast.ifBlank { "✨ Touche les coffres brillants sur la carte pour récolter des ingrédients !" },
                        color = AccentMint,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // Compteur de pas réels + bouton Rencontre Interactive + Objectifs de pas
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("👣 ${state.todaySteps} pas aujourd'hui ($kmToday km)", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Total : ${state.totalSteps} pas • ${state.discoveredRelics.size}/14 reliques", color = TextSoft, fontSize = 12.sp)
                        }
                        FilledTonalButton(
                            onClick = onExploreEncounter,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("🧭 Rencontre", fontSize = 12.sp)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StatGauge("Santé", state.health, Color(0xFFFF758F), Modifier.weight(1f))
                        StatGauge("Énergie", state.energy, AccentMint, Modifier.weight(1f))
                        StatGauge("Moral", state.morale, AccentGold, Modifier.weight(1f))
                    }

                    // Paliers de pas quotidiens à réclamer
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(1000 to 15, 3000 to 30, 6000 to 55, 10000 to 100)) { (goal, reward) ->
                            val claimed = goal in state.claimedStepMilestonesToday
                            val ready = state.todaySteps >= goal && !claimed
                            FilterChip(
                                selected = claimed || ready,
                                onClick = { if (ready) onClaimMilestone(goal, reward) },
                                label = {
                                    Text(
                                        when {
                                            claimed -> "$goal ✓"
                                            ready -> "🎁 $goal (+${reward}🪙)"
                                            else -> "👣 $goal"
                                        },
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onAddSteps(500) }, modifier = Modifier.weight(1f)) {
                            Text("+500 pas manuel", fontSize = 12.sp)
                        }
                        Button(
                            onClick = onGoToLetter,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = BgDeep)
                        ) {
                            Text("📜 Lettre du soir", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Rituel Matin : Sac à dos
        item {
            val packedToday = state.morningPackDoneDayKey == today
            Surface(shape = RoundedCornerShape(18.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("🌅 Matin : Sac d'Expédition (${state.packedGearIds.size}/3)", color = AccentGold, fontWeight = FontWeight.Bold)
                        if (packedToday) Text("Prêt ✓", color = AccentMint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(GearItem.entries) { gear ->
                            val selected = gear.id in state.packedGearIds
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) Color(0xFF332B4D) else BgDeep,
                                border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, AccentGold) else null,
                                modifier = Modifier.width(142.dp).clickable { onToggleGear(gear) }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("${gear.emoji} ${gear.labelFr}", color = TextLight, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text(gear.bonusDescFr, color = TextSoft, fontSize = 10.sp, lineHeight = 13.sp)
                                }
                            }
                        }
                    }
                    Button(
                        onClick = onConfirmPack,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = BgDeep)
                    ) {
                        Text(if (packedToday) "Mettre à jour le sac" else "Valider le sac du matin (+25 XP)")
                    }
                }
            }
        }

        // Rituel Midi : Provisions & Campement
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("☀️ Midi : Provisions & Soins", color = AccentMint, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = onRest, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("🔥 Pause camp", fontSize = 12.sp)
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ProvisionItem.entries) { item ->
                            Surface(shape = RoundedCornerShape(14.dp), color = BgDeep, modifier = Modifier.width(134.dp).clickable { onFeed(item) }) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("${item.emoji} ${item.labelFr}", color = TextLight, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text("+${item.energyGain.toInt()} Én • +${item.moraleGain.toInt()} Mor", color = TextSoft, fontSize = 10.sp)
                                    Text(if (item.costCoins == 0) "Gratuit" else "🪙 ${item.costCoins}", color = AccentGold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CampfireTab(
    state: StepQuestState,
    llmStatus: LlmStatus,
    llmLabel: String,
    isGenerating: Boolean,
    streamingDraft: String,
    onGenerateLetter: () -> Unit,
    onSendChat: (String) -> Unit,
    onStop: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var showLetters by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.campChat.size, streamingDraft) {
        if (!showLetters && state.campChat.isNotEmpty()) listState.animateScrollToItem(state.campChat.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("🔥 Feu de Camp • ${state.adventurerName}", color = TextLight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(llmLabel, color = if (llmStatus == LlmStatus.READY) AccentMint else AccentGold, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !showLetters, onClick = { showLetters = false }, label = { Text("Chat") })
                FilterChip(selected = showLetters, onClick = { showLetters = true }, label = { Text("Lettres (${state.letters.size})") })
            }
        }
        Spacer(Modifier.height(10.dp))

        if (showLetters) {
            Button(
                onClick = onGenerateLetter,
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = BgDeep)
            ) {
                Text(if (isGenerating) "Rédaction en cours par Gemma-3 1B…" else "📜 Rédiger la lettre de voyage du jour (IA locale)")
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isGenerating && streamingDraft.isNotBlank()) {
                    item { LetterCard("Rédaction en direct…", "${state.todaySteps} pas", streamingDraft, true) }
                }
                items(state.letters) { letter ->
                    LetterCard(
                        letter.title,
                        "${letter.dayKey} • ${letter.stepsCount} pas • ${if (letter.generatedByLlm) "IA Locale Gemma-3 1B" else "Carnet"}",
                        letter.body,
                        false
                    )
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.campChat) { msg ->
                    ChatBubble(msg.fromUser, if (msg.fromUser) "Toi" else state.adventurerName, msg.text)
                }
                if (isGenerating) {
                    item { ChatBubble(false, state.adventurerName, streamingDraft.ifBlank { "Réfléchit près du feu…" }) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Parle à ${state.adventurerName} hors-ligne…", fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 3
                )
                if (isGenerating) {
                    IconButton(onClick = onStop) { Icon(Icons.Rounded.StopCircle, contentDescription = "Arrêter", tint = Color(0xFFFF758F)) }
                } else {
                    FilledIconButton(
                        onClick = { val t = inputText; inputText = ""; onSendChat(t) },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = AccentGold, contentColor = BgDeep)
                    ) { Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Envoyer") }
                }
            }
        }
    }
}

@Composable
private fun CraftAndRelicsTab(state: StepQuestState, onCraft: (CraftRecipe) -> Unit) {
    val foundIds = state.discoveredRelics.map { it.id }.toSet()
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("⚗️ Atelier de Campement", color = TextLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Récolte des ingrédients en touchant les coffres de la carte ou en marchant", color = TextSoft, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IngredientType.entries.forEach { ing ->
                    Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(ing.emoji, fontSize = 18.sp)
                            Text("${state.ingredients[ing] ?: 0}", color = AccentGold, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        items(CraftRecipe.entries) { recipe ->
            val canCraft = (state.ingredients[IngredientType.WOOD] ?: 0) >= recipe.costWood &&
                (state.ingredients[IngredientType.HERB] ?: 0) >= recipe.costHerb &&
                (state.ingredients[IngredientType.CRYSTAL] ?: 0) >= recipe.costCrystal &&
                (state.ingredients[IngredientType.HONEY] ?: 0) >= recipe.costHoney
            val costTxt = buildList {
                if (recipe.costWood > 0) add("${recipe.costWood}🪵")
                if (recipe.costHerb > 0) add("${recipe.costHerb}🌿")
                if (recipe.costCrystal > 0) add("${recipe.costCrystal}💎")
                if (recipe.costHoney > 0) add("${recipe.costHoney}🍯")
            }.joinToString(" ")

            Surface(shape = RoundedCornerShape(14.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${recipe.emoji} ${recipe.labelFr}", color = TextLight, fontWeight = FontWeight.Bold)
                        Text(recipe.descFr, color = TextSoft, fontSize = 11.sp)
                        Text("Coût : $costTxt", color = AccentGold, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { onCraft(recipe) },
                        enabled = canCraft,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentMint, contentColor = BgDeep)
                    ) { Text("Créer") }
                }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Text("Reliques (${state.discoveredRelics.size}/${ExpeditionEngine.RELIC_CATALOG.size})", color = AccentGold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        items(ExpeditionEngine.RELIC_CATALOG) { relic ->
            val found = relic.id in foundIds
            Surface(shape = RoundedCornerShape(14.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (found) relic.emoji else "❔", fontSize = 22.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(if (found) "${relic.nameFr} (${relic.rarityFr})" else "Inconnue — ${relic.biomeName}", color = if (found) AccentGold else TextSoft, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(if (found) relic.loreFr else "Explore ${relic.biomeName} pour la dénicher.", color = TextSoft, fontSize = 11.sp)
                    }
                }
            }
        }
        item { Text("Chronique de la Route", color = AccentMint, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        items(state.events.take(10)) { ev ->
            Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ev.emoji, fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(ev.title, color = TextLight, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(ev.description, color = TextSoft, fontSize = 11.sp)
                        Text(fmt.format(Date(ev.timestampMs)), color = TextSoft.copy(alpha = 0.65f), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroTab(
    state: StepQuestState,
    pedometer: PedometerStatus,
    llmLabel: String,
    onRequestStepPermission: () -> Unit,
    onCustomize: (AdventurerClass, String, Long, FamiliarCompanion) -> Unit,
    onToggleReminders: (Boolean) -> Unit
) {
    var name by remember(state.adventurerName) { mutableStateOf(state.adventurerName) }
    var selectedClass by remember(state.adventurerClass) { mutableStateOf(state.adventurerClass) }
    var cloakHex by remember(state.customCloakHex) { mutableLongStateOf(state.customCloakHex) }
    var familiar by remember(state.familiar) { mutableStateOf(state.familiar) }
    val cloakPalette = listOf(0xFFE8844AL, 0xFF39B982L, 0xFFD95B7AL, 0xFF7B61FFL, 0xFF3DA5D9L, 0xFFE6B84CL)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Ton Aventurier & Familier", color = TextLight, fontSize = 20.sp, fontWeight = FontWeight.Bold) }

        // Graphique 7 jours des pas auto
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("👣 Synchro Podomètre Journée", color = AccentMint, fontWeight = FontWeight.Bold)
                    Text(pedometer.label, color = TextLight, fontSize = 12.sp)
                    if (!pedometer.permissionGranted) {
                        Button(onClick = onRequestStepPermission) { Text("Autoriser le suivi auto") }
                    }
                    val entries = state.weeklySteps.entries.sortedBy { it.key }.takeLast(7)
                    if (entries.isNotEmpty()) {
                        val maxVal = entries.maxOf { it.value }.coerceAtLeast(1000)
                        Row(modifier = Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                            entries.forEach { (d, st) ->
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(0.7f)
                                            .height((48f * (st.toFloat() / maxVal)).dp.coerceAtLeast(6.dp))
                                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                            .background(AccentGold)
                                    )
                                    Text(d.takeLast(2), color = TextSoft, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("Choisis ton Familier de Route", color = AccentGold, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FamiliarCompanion.entries) { fam ->
                    val sel = fam == familiar
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (sel) Color(0xFF332B4D) else CardBg,
                        border = if (sel) androidx.compose.foundation.BorderStroke(1.5.dp, Color(fam.colorHex)) else null,
                        modifier = Modifier.width(150.dp).clickable {
                            familiar = fam
                            onCustomize(selectedClass, name, cloakHex, fam)
                        }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("${fam.emoji} ${fam.labelFr}", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(fam.bonusFr, color = TextSoft, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        items(AdventurerClass.entries) { cls ->
            val selected = cls == selectedClass
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardBg,
                border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, Color(cls.accentHex)) else null,
                modifier = Modifier.fillMaxWidth().clickable {
                    selectedClass = cls; name = cls.defaultName; cloakHex = cls.cloakHex
                    onCustomize(cls, cls.defaultName, cls.cloakHex, familiar)
                }
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cls.emoji, fontSize = 26.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${cls.defaultName} — ${cls.titleFr}", color = TextLight, fontWeight = FontWeight.Bold)
                        Text(cls.personalityFr, color = TextSoft, fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom de ton aventurier") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        cloakPalette.forEach { hex ->
                            Box(
                                modifier = Modifier.size(30.dp).clip(CircleShape).background(Color(hex))
                                    .border(if (cloakHex == hex) 3.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { cloakHex = hex }
                            )
                        }
                    }
                    Button(
                        onClick = { onCustomize(selectedClass, name, cloakHex, familiar) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = BgDeep)
                    ) { Text("Enregistrer") }
                    Text("• IA embarquée : $llmLabel", color = TextSoft, fontSize = 11.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Rappels Matin / Midi / Soir", color = TextLight, fontSize = 13.sp)
                        Switch(checked = state.remindersEnabled, onCheckedChange = onToggleReminders)
                    }
                }
            }
        }
    }
}

@Composable
private fun LetterCard(title: String, meta: String, body: String, isLive: Boolean) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF241E33),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isLive) AccentMint else AccentGold.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = AccentGold, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(meta, color = TextSoft, fontSize = 11.sp)
            HorizontalDivider(color = Color(0x33FFFFFF))
            Text(body, color = TextLight, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun ChatBubble(fromUser: Boolean, sender: String, text: String) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (fromUser) Color(0xFF392E59) else CardBg, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(sender, color = if (fromUser) AccentGold else AccentMint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(text, color = TextLight, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun StatGauge(label: String, value: Float, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextSoft, fontSize = 11.sp)
            Text("${value.toInt()}%", color = TextLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { (value / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = Color(0xFF332D47)
        )
    }
}
