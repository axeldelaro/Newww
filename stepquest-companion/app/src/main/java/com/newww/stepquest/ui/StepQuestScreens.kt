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

private val BgDeep = Color(0xFF12101A)
private val CardBg = Color(0xFF1E1B2B)
private val AccentGold = Color(0xFFF6C768)
private val AccentMint = Color(0xFF6CF0B5)
private val TextLight = Color(0xFFF6F2FF)
private val TextSoft = Color(0xFFA9A2BF)

private enum class StepTab(val label: String, val icon: ImageVector) {
    JOURNEY("Voyage", Icons.Rounded.Explore),
    CAMP("Feu de Camp", Icons.Rounded.LocalFireDepartment),
    RELICS("Reliques", Icons.Rounded.AutoAwesome),
    HERO("Héros", Icons.Rounded.Person)
}

@Composable
fun StepQuestApp(viewModel: StepQuestViewModel) {
    val state by viewModel.state.collectAsState()
    val llmStatus by viewModel.llmStatus.collectAsState()
    val llmProgress by viewModel.llmPrepProgress.collectAsState()
    val llmLabel by viewModel.llmStatusLabel.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val streamingDraft by viewModel.streamingDraft.collectAsState()

    var currentTab by remember { mutableStateOf(StepTab.JOURNEY) }

    Scaffold(
        containerColor = BgDeep,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF181424)) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Bandeau discret pendant la préparation unique du modèle IA intégré (0 téléchargement)
            if (llmStatus == LlmStatus.PREPARING_ASSETS || llmStatus == LlmStatus.LOADING_MODEL) {
                Surface(color = Color(0xFF28213B), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
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
                        onAddSteps = viewModel::recordSteps,
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
                    StepTab.RELICS -> RelicsTab(state = state)
                    StepTab.HERO -> HeroTab(
                        state = state,
                        llmLabel = llmLabel,
                        hasHardwarePedometer = viewModel.hasHardwarePedometer,
                        onCustomize = viewModel::customizeAdventurer,
                        onToggleReminders = viewModel::setRemindersEnabled
                    )
                }
            }
        }
    }
}

@Composable
private fun JourneyTab(
    state: StepQuestState,
    onAddSteps: (Int) -> Unit,
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
        // En-tête + Carte animée 60 FPS
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "${state.adventurerClass.emoji} ${state.adventurerName} • Niv.${state.level}",
                        color = TextLight,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        ExpeditionEngine.currentDayPeriodFr(),
                        color = AccentGold,
                        fontSize = 12.sp
                    )
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, AccentGold.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            ) {
                ExpeditionMapCanvas(state = state)
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xBB14111E)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(biome.nameFr, color = AccentGold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Étape : $landmark (${(progress * 100).toInt()} %)", color = TextLight, fontSize = 11.sp)
                    }
                }
            }
        }

        // Compteur de pas réels + jauge vitale
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
                            Text("Total expédition : ${state.totalSteps} pas • ${state.discoveredRelics.size}/14 reliques", color = TextSoft, fontSize = 12.sp)
                        }
                        FilledTonalButton(
                            onClick = { onAddSteps(500) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("+500 pas", fontSize = 12.sp)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        StatGauge("Santé", state.health, Color(0xFFFF758F), Modifier.weight(1f))
                        StatGauge("Énergie", state.energy, AccentMint, Modifier.weight(1f))
                        StatGauge("Moral", state.morale, AccentGold, Modifier.weight(1f))
                    }
                }
            }
        }

        // Rituel 1 : Matin — Préparer son sac (3 objets)
        item {
            val packedToday = state.morningPackDoneDayKey == today
            Surface(shape = RoundedCornerShape(18.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("🌅 Matin : Préparer le sac (${state.packedGearIds.size}/3)", color = AccentGold, fontWeight = FontWeight.Bold)
                            Text("Choisis 3 objets qui influencent ses découvertes et lettres", color = TextSoft, fontSize = 11.sp)
                        }
                        if (packedToday) Text("Prêt ✓", color = AccentMint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(GearItem.entries) { gear ->
                            val selected = gear.id in state.packedGearIds
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (selected) Color(0xFF332B4D) else BgDeep,
                                border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, AccentGold) else null,
                                modifier = Modifier
                                    .width(142.dp)
                                    .clickable { onToggleGear(gear) }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("${gear.emoji} ${gear.labelFr}", color = TextLight, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Spacer(Modifier.height(3.dp))
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

        // Rituel 2 : Midi — Ravitaillement & Repos
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("☀️ Midi : Provisions & Soins", color = AccentMint, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = onRest, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Text("🔥 Pause camp", fontSize = 12.sp)
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ProvisionItem.entries) { item ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = BgDeep,
                                modifier = Modifier
                                    .width(136.dp)
                                    .clickable { onFeed(item) }
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("${item.emoji} ${item.labelFr}", color = TextLight, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    Text("+${item.energyGain.toInt()} Én • +${item.moraleGain.toInt()} Mor", color = TextSoft, fontSize = 10.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (item.costCoins == 0) "Gratuit" else "🪙 ${item.costCoins}",
                                        color = AccentGold,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Rituel 3 : Soir — Lettre IA locale
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF28203D), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🌙 Soir : Lettre d'Expédition IA", color = AccentGold, fontWeight = FontWeight.Bold)
                        Text(
                            "${state.adventurerName} rédige hors-ligne le récit unique de tes ${state.todaySteps} pas.",
                            color = TextSoft,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = onGoToLetter,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = BgDeep)
                    ) {
                        Text("Écrire 📜")
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
        if (!showLetters && state.campChat.isNotEmpty()) {
            listState.animateScrollToItem(state.campChat.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("🔥 Feu de Camp avec ${state.adventurerName}", color = TextLight, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                Text(if (isGenerating) "Rédaction en cours par l'IA locale…" else "📜 Écrire la lettre de voyage du jour (IA locale)")
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isGenerating && streamingDraft.isNotBlank()) {
                    item {
                        LetterCard("Rédaction en direct…", "${state.todaySteps} pas", streamingDraft, true)
                    }
                }
                items(state.letters) { letter ->
                    LetterCard(
                        title = letter.title,
                        meta = "${letter.dayKey} • ${letter.stepsCount} pas • ${if (letter.generatedByLlm) "IA Locale Gemma-3" else "Carnet"}",
                        body = letter.body,
                        isLive = false
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.campChat) { msg ->
                    ChatBubble(fromUser = msg.fromUser, sender = if (msg.fromUser) "Toi" else state.adventurerName, text = msg.text)
                }
                if (isGenerating) {
                    item {
                        ChatBubble(
                            fromUser = false,
                            sender = state.adventurerName,
                            text = streamingDraft.ifBlank { "Réfléchit près du feu de camp…" }
                        )
                    }
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
                    IconButton(onClick = onStop) {
                        Icon(Icons.Rounded.StopCircle, contentDescription = "Arrêter", tint = Color(0xFFFF758F))
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            val t = inputText
                            inputText = ""
                            onSendChat(t)
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = AccentGold, contentColor = BgDeep)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Envoyer")
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (fromUser) Color(0xFF392E59) else CardBg,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(sender, color = if (fromUser) AccentGold else AccentMint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(text, color = TextLight, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun RelicsTab(state: StepQuestState) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }
    val foundIds = state.discoveredRelics.map { it.id }.toSet()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Collection de Reliques (${state.discoveredRelics.size}/${ExpeditionEngine.RELIC_CATALOG.size})", color = TextLight, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Marche chaque jour avec le bon équipement pour tout découvrir", color = TextSoft, fontSize = 12.sp)
        }
        items(ExpeditionEngine.RELIC_CATALOG) { relic ->
            val found = relic.id in foundIds
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CardBg,
                border = if (found) androidx.compose.foundation.BorderStroke(1.dp, AccentGold.copy(alpha = 0.5f)) else null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (found) relic.emoji else "❔", fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (found) "${relic.nameFr} (${relic.rarityFr})" else "Relique inconnue — ${relic.biomeName}",
                            color = if (found) AccentGold else TextSoft,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            if (found) relic.loreFr else "Continue d'explorer ${relic.biomeName} pour la dénicher.",
                            color = TextSoft,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text("Chronique de la Route", color = AccentMint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        items(state.events.take(15)) { ev ->
            Surface(shape = RoundedCornerShape(12.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ev.emoji, fontSize = 20.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(ev.title, color = TextLight, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(ev.description, color = TextSoft, fontSize = 12.sp)
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
    llmLabel: String,
    hasHardwarePedometer: Boolean,
    onCustomize: (AdventurerClass, String, Long) -> Unit,
    onToggleReminders: (Boolean) -> Unit
) {
    var name by remember(state.adventurerName) { mutableStateOf(state.adventurerName) }
    var selectedClass by remember(state.adventurerClass) { mutableStateOf(state.adventurerClass) }
    var cloakHex by remember(state.customCloakHex) { mutableLongStateOf(state.customCloakHex) }
    val cloakPalette = listOf(0xFFE8844AL, 0xFF39B982L, 0xFFD95B7AL, 0xFF7B61FFL, 0xFF3DA5D9L, 0xFFE6B84CL)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Ton Compagnon d'Expédition", color = TextLight, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Change de classe, de nom ou de cape à tout moment", color = TextSoft, fontSize = 12.sp)
        }

        items(AdventurerClass.entries) { cls ->
            val selected = cls == selectedClass
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = CardBg,
                border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, Color(cls.accentHex)) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedClass = cls
                        name = cls.defaultName
                        cloakHex = cls.cloakHex
                        onCustomize(cls, cls.defaultName, cls.cloakHex)
                    }
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cls.emoji, fontSize = 28.sp)
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
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nom de ton aventurier") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Couleur de cape", color = TextSoft, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        cloakPalette.forEach { hex ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(hex))
                                    .border(if (cloakHex == hex) 3.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { cloakHex = hex }
                            )
                        }
                    }
                    Button(
                        onClick = { onCustomize(selectedClass, name, cloakHex) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = BgDeep)
                    ) {
                        Text("Enregistrer le compagnon")
                    }
                }
            }
        }

        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardBg, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Système 100 % Hors-Ligne", color = AccentMint, fontWeight = FontWeight.Bold)
                    Text("• Cerveau IA intégré : $llmLabel", color = TextLight, fontSize = 12.sp)
                    Text(
                        "• Podomètre : ${if (hasHardwarePedometer) "Capteur matériel actif" else "Mode accéléromètre + bouton marche"}",
                        color = TextLight,
                        fontSize = 12.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Rappels Matin / Midi / Soir", color = TextLight, fontSize = 13.sp)
                        Switch(checked = state.remindersEnabled, onCheckedChange = onToggleReminders)
                    }
                }
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
