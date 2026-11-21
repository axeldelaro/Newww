package com.newww.auracompanion.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newww.auracompanion.ai.EngineInitState
import com.newww.auracompanion.model.*
import com.newww.auracompanion.ui.avatar.PhotoFaceAnimatorCanvas
import com.newww.auracompanion.ui.avatar.StudioAvatarCanvas
import com.newww.auracompanion.viewmodel.AuraViewModel
import com.newww.auracompanion.vision.AnalyzedPhotoAvatar

private enum class AuraTab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Rounded.GraphicEq),
    CHARACTERS("Profils", Icons.Rounded.People),
    STUDIO("Photo & Style", Icons.Rounded.AutoFixHigh),
    MIND("Caractère", Icons.Rounded.Tune)
}

@Composable
fun AuraCompanionApp(
    viewModel: AuraViewModel,
    onPickPhoto: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val llmState by viewModel.llmState.collectAsState()
    val llmProgress by viewModel.llmProgress.collectAsState()
    val llmStatus by viewModel.llmStatus.collectAsState()
    val voiceStatus by viewModel.voiceStatus.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val streamingReply by viewModel.streamingReply.collectAsState()
    val lipSync by viewModel.lipSyncLevel.collectAsState()
    val micLevel by viewModel.micLevel.collectAsState()
    val analyzedPhoto by viewModel.analyzedPhoto.collectAsState()
    val photoProcessing by viewModel.photoProcessing.collectAsState()
    val callActive by viewModel.callActive.collectAsState()

    val theme = state.themePreset
    val bgColor = Color(theme.bgHex)
    val surfaceColor = Color(theme.surfaceHex)
    val primaryColor = Color(theme.primaryHex)
    val secondaryColor = Color(theme.secondaryHex)
    val textMain = Color(0xFFFAF5FF)
    val textMuted = Color(0xFFB7ABC9)

    var currentTab by remember { mutableStateOf(AuraTab.LIVE) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val activeChar = state.activeCharacter

    Scaffold(
        containerColor = bgColor,
        bottomBar = {
            NavigationBar(containerColor = surfaceColor) {
                AuraTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = bgColor,
                            selectedTextColor = primaryColor,
                            indicatorColor = primaryColor,
                            unselectedIconColor = textMuted,
                            unselectedTextColor = textMuted
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
            if (llmState == EngineInitState.EXTRACTING || llmState == EngineInitState.LOADING) {
                Surface(color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text("🧠 $llmStatus", color = primaryColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { llmProgress.coerceIn(0.05f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = primaryColor,
                            trackColor = bgColor
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    AuraTab.LIVE -> LiveCallAndChatTab(
                        state = state,
                        character = activeChar,
                        analyzedPhoto = analyzedPhoto,
                        photoProcessing = photoProcessing,
                        lipSync = lipSync,
                        micLevel = micLevel,
                        isListening = isListening,
                        isSpeaking = isSpeaking,
                        isThinking = isThinking,
                        callActive = callActive,
                        streamingReply = streamingReply,
                        bgColor = bgColor,
                        surfaceColor = surfaceColor,
                        primaryColor = primaryColor,
                        secondaryColor = secondaryColor,
                        textMain = textMain,
                        textMuted = textMuted,
                        onPet = viewModel::onPetAvatar,
                        onToggleMic = viewModel::toggleVoiceTurnOrCall,
                        onStopAll = viewModel::stopCall,
                        onSendText = { viewModel.sendUserMessage(it) },
                        onGift = viewModel::giveSmallGift,
                        onPickPhoto = onPickPhoto,
                        onToggleContinuousCall = viewModel::setContinuousCall,
                        onToggleAutoSpeak = viewModel::setAutoSpeak
                    )
                    AuraTab.CHARACTERS -> CharactersTab(
                        state = state,
                        surfaceColor = surfaceColor,
                        primaryColor = primaryColor,
                        secondaryColor = secondaryColor,
                        textMain = textMain,
                        textMuted = textMuted,
                        onSelect = {
                            viewModel.selectCharacter(it)
                            currentTab = AuraTab.LIVE
                        },
                        onCreateOpen = { showCreateDialog = true },
                        onDelete = viewModel::deleteCharacter,
                        onPickPhoto = onPickPhoto
                    )
                    AuraTab.STUDIO -> StudioAndPhotoTab(
                        state = state,
                        character = activeChar,
                        analyzedPhoto = analyzedPhoto,
                        photoProcessing = photoProcessing,
                        lipSync = lipSync,
                        bgColor = bgColor,
                        surfaceColor = surfaceColor,
                        primaryColor = primaryColor,
                        secondaryColor = secondaryColor,
                        textMain = textMain,
                        textMuted = textMuted,
                        onPickPhoto = onPickPhoto,
                        onSetMode = viewModel::setAvatarMode,
                        onUpdateChar = viewModel::updateActiveCharacter,
                        onSelectTheme = viewModel::setThemePreset
                    )
                    AuraTab.MIND -> MindAndVoiceTab(
                        state = state,
                        character = activeChar,
                        llmStatus = llmStatus,
                        voiceStatus = voiceStatus,
                        surfaceColor = surfaceColor,
                        primaryColor = primaryColor,
                        secondaryColor = secondaryColor,
                        textMain = textMain,
                        textMuted = textMuted,
                        onUpdateChar = viewModel::updateActiveCharacter,
                        onPreviewVoice = viewModel::previewVoice,
                        onAddMemory = viewModel::addMemory,
                        onRemoveMemory = viewModel::removeMemory,
                        onClearChat = viewModel::clearChatHistory,
                        onToggleReminders = viewModel::setRemindersEnabled
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateCharacterDialog(
            surfaceColor = surfaceColor,
            primaryColor = primaryColor,
            textMain = textMain,
            textMuted = textMuted,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, role, style ->
                showCreateDialog = false
                viewModel.createNewCharacter(name, role, style)
                currentTab = AuraTab.STUDIO
            }
        )
    }
}

@Composable
private fun LiveCallAndChatTab(
    state: AuraAppState,
    character: CompanionCharacter,
    analyzedPhoto: AnalyzedPhotoAvatar?,
    photoProcessing: Boolean,
    lipSync: Float,
    micLevel: Float,
    isListening: Boolean,
    isSpeaking: Boolean,
    isThinking: Boolean,
    callActive: Boolean,
    streamingReply: String,
    bgColor: Color,
    surfaceColor: Color,
    primaryColor: Color,
    secondaryColor: Color,
    textMain: Color,
    textMuted: Color,
    onPet: () -> Unit,
    onToggleMic: () -> Unit,
    onStopAll: () -> Unit,
    onSendText: (String) -> Unit,
    onGift: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onToggleContinuousCall: (Boolean) -> Unit,
    onToggleAutoSpeak: (Boolean) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val chatListState = rememberLazyListState()

    LaunchedEffect(character.chatHistory.size, streamingReply) {
        if (character.chatHistory.isNotEmpty()) {
            chatListState.animateScrollToItem(character.chatHistory.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Scène Avatar Live 60 FPS (Photo Animée IA OU Modèle Studio)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.54f)
        ) {
            if (character.avatarMode == AvatarSourceMode.PHOTO_AI && analyzedPhoto != null) {
                PhotoFaceAnimatorCanvas(
                    analyzedPhoto = analyzedPhoto,
                    character = character,
                    emotion = character.currentEmotion,
                    lipSyncLevel = lipSync,
                    isListening = isListening,
                    onPetAvatar = onPet
                )
            } else {
                StudioAvatarCanvas(
                    character = character,
                    emotion = character.currentEmotion,
                    lipSyncLevel = lipSync,
                    isListening = isListening,
                    onPetAvatar = onPet
                )
            }

            if (photoProcessing) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xCC120C18)
                ) {
                    Text(
                        "✨ Analyse IA du visage (478 points)…",
                        color = primaryColor,
                        modifier = Modifier.padding(14.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // En-tête flottant (Nom, Émotion, Affinité, Bouton Photo rapide)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xB8140C1C)) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(
                            "${character.name} ${character.currentEmotion.emoji}",
                            color = textMain,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            "Niv.${character.affinityLevel} • ${character.affinityTitleFr}",
                            color = primaryColor,
                            fontSize = 11.sp
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xB8140C1C),
                        modifier = Modifier.clickable { onPickPhoto() }
                    ) {
                        Text(
                            "📷 Photo IA",
                            color = secondaryColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (state.autoSpeakReplies) primaryColor.copy(alpha = 0.25f) else Color(0xB8140C1C),
                        modifier = Modifier.clickable { onToggleAutoSpeak(!state.autoSpeakReplies) }
                    ) {
                        Text(
                            if (state.autoSpeakReplies) "🔊 Voix" else "🔇 Muet",
                            color = textMain,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            // Indicateur d'état vocal en bas de la scène (Écoute / Parole / Réflexion)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xC8140C1C)) {
                    val statusTxt = when {
                        isListening -> "🎙️ Je t'écoute… (${(micLevel * 100).toInt()} %)"
                        isThinking -> "💭 ${character.name} réfléchit…"
                        isSpeaking -> "🗣️ ${character.name} te parle…"
                        else -> "✨ Touche pour caresser • ${if (character.avatarMode == AvatarSourceMode.PHOTO_AI && analyzedPhoto != null) "Photo IA" else "Studio Live"}"
                    }
                    Text(
                        statusTxt,
                        color = if (isListening) secondaryColor else textMain,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                // Petites attentions rapides
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickGiftPill("☕", { onGift("un café chaud") })
                    QuickGiftPill("🌸", { onGift("un bouquet de fleurs") })
                    QuickGiftPill("💖", onPet)
                }
            }
        }

        // 2. Zone de conversation + Appel Vocal Hors-Ligne
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.46f)
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            LazyColumn(
                state = chatListState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(character.chatHistory) { msg ->
                    AuraChatBubble(
                        msg = msg,
                        charName = character.name,
                        userName = character.userName,
                        surfaceColor = surfaceColor,
                        primaryColor = primaryColor,
                        textMain = textMain
                    )
                }
                if (isThinking && streamingReply.isNotBlank()) {
                    item {
                        AuraChatBubble(
                            msg = AuraChatMessage("live", false, streamingReply, character.currentEmotion),
                            charName = character.name,
                            userName = character.userName,
                            surfaceColor = surfaceColor,
                            primaryColor = primaryColor,
                            textMain = textMain
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Barre Appel Continu + Composer texte + Bouton Micro Live
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Bouton Appel Vocal Live (Micro Whisper FR + Réponse Voix Neurale)
                FilledIconButton(
                    onClick = if (callActive || isListening || isSpeaking) onStopAll else onToggleMic,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (callActive || isListening) Color(0xFFFF5A7E) else primaryColor,
                        contentColor = bgColor
                    )
                ) {
                    Icon(
                        imageVector = if (callActive || isListening || isSpeaking) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = "Appel vocal hors-ligne"
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Écris ou parle à ${character.name}…", fontSize = 13.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    maxLines = 2
                )

                FilledIconButton(
                    onClick = {
                        val txt = inputText
                        inputText = ""
                        onSendText(txt)
                    },
                    modifier = Modifier.size(46.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = secondaryColor,
                        contentColor = bgColor
                    )
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Envoyer")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (state.continuousCallMode) "📞 Mode Appel Continu actif (mains-libres)" else "🎙️ Appuie sur le micro pour parler hors-ligne",
                    color = textMuted,
                    fontSize = 11.sp
                )
                Text(
                    if (state.continuousCallMode) "Passer en manuel" else "Activer Appel Continu",
                    color = primaryColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onToggleContinuousCall(!state.continuousCallMode) }
                )
            }
        }
    }
}

@Composable
private fun QuickGiftPill(emoji: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color(0xCC1B1226),
        modifier = Modifier
            .size(34.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 16.sp)
        }
    }
}

@Composable
private fun AuraChatBubble(
    msg: AuraChatMessage,
    charName: String,
    userName: String,
    surfaceColor: Color,
    primaryColor: Color,
    textMain: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.fromUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (msg.fromUser) primaryColor.copy(alpha = 0.24f) else surfaceColor,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    if (msg.fromUser) userName else "$charName ${msg.emotion.emoji}",
                    color = primaryColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(msg.text, color = textMain, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun CharactersTab(
    state: AuraAppState,
    surfaceColor: Color,
    primaryColor: Color,
    secondaryColor: Color,
    textMain: Color,
    textMuted: Color,
    onSelect: (String) -> Unit,
    onCreateOpen: () -> Unit,
    onDelete: (String) -> Unit,
    onPickPhoto: () -> Unit
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tes Compagnes & Personnages", color = textMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Crée autant de caractères différents que tu veux", color = textMuted, fontSize = 12.sp)
                }
                Button(
                    onClick = onCreateOpen,
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = Color(state.themePreset.bgHex))
                ) {
                    Text("+ Créer")
                }
            }
        }

        items(state.characters, key = { it.id }) { ch ->
            val selected = ch.id == state.activeCharacter.id
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = surfaceColor,
                border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, primaryColor) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(ch.id) }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color(ch.studio.outfitColorHex)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (ch.avatarMode == AvatarSourceMode.PHOTO_AI && ch.photoPath != null) "📷" else ch.currentEmotion.emoji,
                            fontSize = 24.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ch.name, color = textMain, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Spacer(Modifier.width(8.dp))
                            if (selected) {
                                Text(
                                    "ACTIVE",
                                    color = Color(state.themePreset.bgHex),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(primaryColor)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text("${ch.relationRole} • ${ch.voicePack.labelFr}", color = secondaryColor, fontSize = 11.sp)
                        Text(ch.tagline, color = textMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Niv.${ch.affinityLevel} (${ch.affinityTitleFr}) • ${if (ch.photoPath != null) "Photo IA prête" else ch.studio.artStyle.labelFr}",
                            color = primaryColor,
                            fontSize = 11.sp
                        )
                    }
                    if (state.characters.size > 1) {
                        IconButton(onClick = { onDelete(ch.id) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Supprimer", tint = textMuted)
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth()) {
                Text("📷 Importer une photo pour ${state.activeCharacter.name}")
            }
        }
    }
}

@Composable
private fun StudioAndPhotoTab(
    state: AuraAppState,
    character: CompanionCharacter,
    analyzedPhoto: AnalyzedPhotoAvatar?,
    photoProcessing: Boolean,
    lipSync: Float,
    bgColor: Color,
    surfaceColor: Color,
    primaryColor: Color,
    secondaryColor: Color,
    textMain: Color,
    textMuted: Color,
    onPickPhoto: () -> Unit,
    onSetMode: (AvatarSourceMode) -> Unit,
    onUpdateChar: ((CompanionCharacter) -> CompanionCharacter) -> Unit,
    onSelectTheme: (AppThemePreset) -> Unit
) {
    val st = character.studio
    val skinPalette = listOf(0xFFFFECE0L, 0xFFFFDFC8L, 0xFFF4CEB4L, 0xFFE3B18EL, 0xFFC58C64L, 0xFF935F42L, 0xFF6B422CL)
    val hairPalette = listOf(0xFF1F1D26L, 0xFF5A342BL, 0xFFB87B4BL, 0xFFF0CF8AL, 0xFFE84A6FL, 0xFF2B2D6EL, 0xFFD69AC4L, 0xFF39A98AL, 0xFFEDE8F5L)
    val eyePalette = listOf(0xFF4F8A6BL, 0xFF4A78B8L, 0xFF7B61FFL, 0xFFFF5CA8L, 0xFF00D4B4L, 0xFFB56E3BL, 0xFFE85D5DL, 0xFFFFC857L)
    val outfitPalette = listOf(0xFFE87A9AL, 0xFF3E3BFFL, 0xFF2D465EL, 0xFFA28BFFL, 0xFF231F30L, 0xFF2FA87CL, 0xFFE68A4AL, 0xFFE8E2F5L)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Aperçu en direct du modèle ou de la photo animée IA
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.5.dp, primaryColor.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
            ) {
                if (character.avatarMode == AvatarSourceMode.PHOTO_AI && analyzedPhoto != null) {
                    PhotoFaceAnimatorCanvas(
                        analyzedPhoto = analyzedPhoto,
                        character = character,
                        emotion = character.currentEmotion,
                        lipSyncLevel = lipSync,
                        isListening = false,
                        onPetAvatar = {}
                    )
                } else {
                    StudioAvatarCanvas(
                        character = character,
                        emotion = character.currentEmotion,
                        lipSyncLevel = lipSync,
                        isListening = false,
                        onPetAvatar = {}
                    )
                }
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xCC120C18)
                ) {
                    val info = when {
                        photoProcessing -> "Analyse IA en cours…"
                        character.avatarMode == AvatarSourceMode.PHOTO_AI && analyzedPhoto != null ->
                            if (analyzedPhoto.rig.detectedByAi) "✓ Photo animée par IA (478 points faciaux)" else "✓ Photo animée (mode portrait)"
                        else -> "Modèle Studio : ${st.artStyle.labelFr}"
                    }
                    Text(info, color = primaryColor, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }

        // Sélecteur Photo Animée IA vs Création Modèle Studio
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AvatarSourceMode.entries.forEach { mode ->
                    FilterChip(
                        selected = character.avatarMode == mode,
                        onClick = { onSetMode(mode) },
                        label = { Text(mode.labelFr, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Bloc Animation de PHOTO par IA
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("📷 Animation d'une Photo par IA Locale", color = primaryColor, fontWeight = FontWeight.Bold)
                    Text(
                        "Choisis n'importe quelle photo : l'IA MediaPipe locale détecte automatiquement les yeux, sourcils, joues et lèvres pour l'animer en temps réel avec la voix !",
                        color = textMuted,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = onPickPhoto,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = bgColor)
                    ) {
                        Text(if (character.photoPath == null) "Choisir une photo dans la galerie" else "Changer la photo animée")
                    }

                    if (character.photoPath != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Détourer & remplacer le fond par IA", color = textMain, fontSize = 13.sp)
                                Text("Utilise Selfie Segmenter local pour changer le décor", color = textMuted, fontSize = 11.sp)
                            }
                            Switch(
                                checked = character.replacePhotoBackground,
                                onCheckedChange = { checked ->
                                    onUpdateChar { it.copy(replacePhotoBackground = checked) }
                                }
                            )
                        }

                        Text("Filtre cinématique sur la photo", color = textMuted, fontSize = 12.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(PhotoFilterStyle.entries) { pf ->
                                FilterChip(
                                    selected = character.photoFilter == pf,
                                    onClick = { onUpdateChar { it.copy(photoFilter = pf) } },
                                    label = { Text(pf.labelFr, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Décor de scène (partagé Photo détourée & Studio)
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🌌 Décor de Scène Animé", color = secondaryColor, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SceneBackdrop.entries) { scene ->
                            FilterChip(
                                selected = character.backdrop == scene,
                                onClick = { onUpdateChar { it.copy(backdrop = scene) } },
                                label = { Text(scene.labelFr, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        // Studio de Création de Modèle Avatar 100 % Personnalisable
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🎨 Studio Modèle Avatar (100 % Configurable)", color = primaryColor, fontWeight = FontWeight.Bold)

                    Text("Style artistique", color = textMuted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(StudioArtStyle.entries) { style ->
                            FilterChip(
                                selected = st.artStyle == style,
                                onClick = { onUpdateChar { it.copy(avatarMode = AvatarSourceMode.STUDIO_MODEL, studio = it.studio.copy(artStyle = style)) } },
                                label = { Text(style.labelFr, fontSize = 11.sp) }
                            )
                        }
                    }

                    Text("Coiffure", color = textMuted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(HairStyle.entries) { hs ->
                            FilterChip(
                                selected = st.hairStyle == hs,
                                onClick = { onUpdateChar { it.copy(studio = it.studio.copy(hairStyle = hs)) } },
                                label = { Text(hs.labelFr, fontSize = 11.sp) }
                            )
                        }
                    }

                    ColorRow("Couleur des cheveux", hairPalette, st.hairColorHex) { hex ->
                        onUpdateChar { it.copy(studio = it.studio.copy(hairColorHex = hex)) }
                    }
                    ColorRow("Reflets / mèches", hairPalette, st.hairHighlightHex) { hex ->
                        onUpdateChar { it.copy(studio = it.studio.copy(hairHighlightHex = hex)) }
                    }
                    ColorRow("Teint de peau", skinPalette, st.skinToneHex) { hex ->
                        onUpdateChar { it.copy(studio = it.studio.copy(skinToneHex = hex)) }
                    }
                    ColorRow("Couleur des yeux", eyePalette, st.eyeColorHex) { hex ->
                        onUpdateChar { it.copy(studio = it.studio.copy(eyeColorHex = hex)) }
                    }

                    Text("Tenue", color = textMuted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(OutfitStyle.entries) { outfit ->
                            FilterChip(
                                selected = st.outfitStyle == outfit,
                                onClick = { onUpdateChar { it.copy(studio = it.studio.copy(outfitStyle = outfit)) } },
                                label = { Text(outfit.labelFr, fontSize = 11.sp) }
                            )
                        }
                    }
                    ColorRow("Couleur de tenue", outfitPalette, st.outfitColorHex) { hex ->
                        onUpdateChar { it.copy(studio = it.studio.copy(outfitColorHex = hex)) }
                    }

                    Text("Accessoire", color = textMuted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AccessoryStyle.entries) { acc ->
                            FilterChip(
                                selected = st.accessory == acc,
                                onClick = { onUpdateChar { it.copy(studio = it.studio.copy(accessory = acc)) } },
                                label = { Text(acc.labelFr, fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Taches de rousseur", color = textMain, fontSize = 13.sp)
                        Switch(
                            checked = st.freckles,
                            onCheckedChange = { f -> onUpdateChar { it.copy(studio = it.studio.copy(freckles = f)) } }
                        )
                    }
                }
            }
        }

        // Thème visuel global de l'application
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✨ Thème & Style de l'Interface", color = secondaryColor, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AppThemePreset.entries) { preset ->
                            FilterChip(
                                selected = state.themePreset == preset,
                                onClick = { onSelectTheme(preset) },
                                label = { Text(preset.labelFr, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorRow(title: String, palette: List<Long>, selectedHex: Long, onPick: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Color(0xFFB7ABC9), fontSize = 12.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(palette) { hex ->
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(hex))
                        .border(if (selectedHex == hex) 3.dp else 1.dp, if (selectedHex == hex) Color.White else Color(0x55FFFFFF), CircleShape)
                        .clickable { onPick(hex) }
                )
            }
        }
    }
}

@Composable
private fun MindAndVoiceTab(
    state: AuraAppState,
    character: CompanionCharacter,
    llmStatus: String,
    voiceStatus: String,
    surfaceColor: Color,
    primaryColor: Color,
    secondaryColor: Color,
    textMain: Color,
    textMuted: Color,
    onUpdateChar: ((CompanionCharacter) -> CompanionCharacter) -> Unit,
    onPreviewVoice: () -> Unit,
    onAddMemory: (String) -> Unit,
    onRemoveMemory: (String) -> Unit,
    onClearChat: () -> Unit,
    onToggleReminders: (Boolean) -> Unit
) {
    var newMemoryText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Caractère & Voix de ${character.name}", color = textMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Personnalise à 100 % sa personnalité, sa voix neurale et ses souvenirs", color = textMuted, fontSize = 12.sp)
        }

        // Identité & relation
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Identité & Relation", color = primaryColor, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = character.name,
                        onValueChange = { v -> onUpdateChar { it.copy(name = v.take(24)) } },
                        label = { Text("Nom du personnage") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = character.relationRole,
                        onValueChange = { v -> onUpdateChar { it.copy(relationRole = v) } },
                        label = { Text("Rôle / Relation (ex. Petite amie douce, Confidente…)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = character.userName,
                            onValueChange = { v -> onUpdateChar { it.copy(userName = v.take(24)) } },
                            label = { Text("Ton prénom") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = character.userNickname,
                            onValueChange = { v -> onUpdateChar { it.copy(userNickname = v.take(24)) } },
                            label = { Text("Surnom affectueux") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = character.backstory,
                        onValueChange = { v -> onUpdateChar { it.copy(backstory = v) } },
                        label = { Text("Histoire personnelle (Backstory)") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = character.interests,
                        onValueChange = { v -> onUpdateChar { it.copy(interests = v) } },
                        label = { Text("Centres d'intérêt") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = character.customRules,
                        onValueChange = { v -> onUpdateChar { it.copy(customRules = v) } },
                        label = { Text("Style / Consignes personnalisées (optionnel)") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Tutoiement naturel", color = textMain, fontSize = 13.sp)
                        Switch(
                            checked = character.useTuForm,
                            onCheckedChange = { u -> onUpdateChar { it.copy(useTuForm = u) } }
                        )
                    }
                    Text("Longueur des réponses", color = textMuted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ResponseLength.entries) { rl ->
                            FilterChip(
                                selected = character.responseLength == rl,
                                onClick = { onUpdateChar { it.copy(responseLength = rl) } },
                                label = { Text(rl.labelFr, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        // 8 Curseurs de caractère fins
        item {
            val t = character.traits
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("8 Curseurs de Caractère", color = secondaryColor, fontWeight = FontWeight.Bold)
                    TraitSlider("Douceur & Tendresse", t.warmth, primaryColor) { v -> onUpdateChar { it.copy(traits = it.traits.copy(warmth = v)) } }
                    TraitSlider("Humour & Taquinerie", t.humor, primaryColor) { v -> onUpdateChar { it.copy(traits = it.traits.copy(humor = v)) } }
                    TraitSlider("Romantisme", t.romance, primaryColor) { v -> onUpdateChar { it.copy(traits = it.traits.copy(romance = v)) } }
                    TraitSlider("Timidité", t.shyness, primaryColor) { v -> onUpdateChar { it.copy(traits = it.traits.copy(shyness = v)) } }
                    TraitSlider("Énergie & Enthousiasme", t.energy, primaryColor) { v -> onUpdateChar { it.copy(traits = it.traits.copy(energy = v)) } }
                    TraitSlider("Empathie & Écoute", t.empathy, primaryColor) { v -> onUpdateChar { it.copy(traits = it.traits.copy(empathy = v)) } }
                    TraitSlider("Franc-parler", t.directness, primaryColor) { v -> onUpdateChar { it.copy(traits = it.traits.copy(directness = v)) } }
                    TraitSlider("Imagination & Poésie", t.imagination, primaryColor) { v -> onUpdateChar { it.copy(traits = it.traits.copy(imagination = v)) } }
                }
            }
        }

        // Voix Neurale Française Intégrée
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🗣️ Voix Neurale Française (100 % Hors-Ligne)", color = primaryColor, fontWeight = FontWeight.Bold)
                    VoicePack.entries.forEach { vp ->
                        val selected = character.voicePack == vp
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(state.themePreset.bgHex),
                            border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, primaryColor) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUpdateChar { it.copy(voicePack = vp) } }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(vp.labelFr, color = textMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(vp.subtitleFr, color = textMuted, fontSize = 11.sp)
                                }
                                if (selected) Text("Actif ✓", color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                    TraitSlider("Vitesse (${String.format("%.2fx", character.voiceSpeed)})", (character.voiceSpeed - 0.75f) / 0.60f, secondaryColor) { norm ->
                        onUpdateChar { it.copy(voiceSpeed = (0.75f + norm * 0.60f).coerceIn(0.75f, 1.35f)) }
                    }
                    TraitSlider("Timbre / Hauteur (${String.format("%.2f", character.voicePitch)})", (character.voicePitch - 0.85f) / 0.35f, secondaryColor) { norm ->
                        onUpdateChar { it.copy(voicePitch = (0.85f + norm * 0.35f).coerceIn(0.85f, 1.20f)) }
                    }
                    Button(
                        onClick = onPreviewVoice,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = secondaryColor, contentColor = Color(state.themePreset.bgHex))
                    ) {
                        Text("🔊 Tester cette voix hors-ligne")
                    }
                }
            }
        }

        // Mémoire Long-Terme Éditable
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🧠 Mémoire Long-Terme de ${character.name}", color = primaryColor, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newMemoryText,
                            onValueChange = { newMemoryText = it },
                            placeholder = { Text("Ajouter un souvenir (ex. J'adore le café)…", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        FilledTonalButton(onClick = {
                            onAddMemory(newMemoryText)
                            newMemoryText = ""
                        }) {
                            Text("Ajouter")
                        }
                    }
                    character.memories.forEach { fact ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("• $fact", color = textMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemoveMemory(fact) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Oublier", tint = textMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        // Statut IA Intégrée & Paramètres
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚡ Moteurs IA Intégrés (Zéro Internet)", color = secondaryColor, fontWeight = FontWeight.Bold)
                    Text("• LLM : $llmStatus", color = textMain, fontSize = 12.sp)
                    Text("• Audio : $voiceStatus", color = textMain, fontSize = 12.sp)
                    Text("• Vision : MediaPipe Face Landmarker 478 pts + Selfie Segmenter", color = textMain, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Messages locaux quotidiens", color = textMain, fontSize = 13.sp)
                        Switch(checked = state.remindersEnabled, onCheckedChange = onToggleReminders)
                    }
                    OutlinedButton(onClick = onClearChat, modifier = Modifier.fillMaxWidth()) {
                        Text("Effacer la conversation de ${character.name}")
                    }
                }
            }
        }
    }
}

@Composable
private fun TraitSlider(label: String, value: Float, color: Color, onChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFFAF5FF), fontSize = 12.sp)
            Text("${(value.coerceIn(0f, 1f) * 100).toInt()} %", color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onChange,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color)
        )
    }
}

@Composable
private fun CreateCharacterDialog(
    surfaceColor: Color,
    primaryColor: Color,
    textMain: Color,
    textMuted: Color,
    onDismiss: () -> Unit,
    onCreate: (String, String, StudioArtStyle) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("Petite amie personnalisée") }
    var style by remember { mutableStateOf(StudioArtStyle.ANIME_LIVE2D) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = surfaceColor,
        title = { Text("Créer un nouveau personnage", color = textMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Prénom (ex. Luna, Chloé, Alex…)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    label = { Text("Type de relation") },
                    singleLine = true
                )
                Text("Style de départ (modifiable + photo IA possible)", color = textMuted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(StudioArtStyle.entries) { st ->
                        FilterChip(selected = style == st, onClick = { style = st }, label = { Text(st.labelFr, fontSize = 11.sp) })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, role, style) },
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
            ) {
                Text("Créer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", color = textMuted) }
        }
    )
}
