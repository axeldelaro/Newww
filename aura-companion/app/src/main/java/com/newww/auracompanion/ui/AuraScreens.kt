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
import androidx.compose.ui.graphics.Brush
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
    MIND("IA & Voix", Icons.Rounded.Tune)
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
    val lipSyncOpen by viewModel.lipSyncLevel.collectAsState()
    val lipSyncWide by viewModel.lipSyncWide.collectAsState()
    val micLevel by viewModel.micLevel.collectAsState()
    val analyzedPhoto by viewModel.analyzedPhoto.collectAsState()
    val photoProcessing by viewModel.photoProcessing.collectAsState()
    val callActive by viewModel.callActive.collectAsState()
    val autoLearnNotice by viewModel.autoLearnNotice.collectAsState()

    val theme = state.themePreset
    val bgColor = Color(theme.bgHex)
    val surfaceColor = Color(theme.surfaceHex)
    val primaryColor = Color(theme.primaryHex)
    val secondaryColor = Color(theme.secondaryHex)
    val textMain = Color(0xFFFAF5FF)
    val textMuted = Color(0xFFB7ABC9)

    var currentTab by remember { mutableStateOf(AuraTab.LIVE) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showScenarioSheet by remember { mutableStateOf(false) }
    val activeChar = state.activeCharacter

    Scaffold(
        containerColor = bgColor,
        bottomBar = {
            NavigationBar(containerColor = surfaceColor.copy(alpha = 0.96f)) {
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (llmState == EngineInitState.EXTRACTING || llmState == EngineInitState.LOADING) {
                Surface(color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("🧠 $llmStatus", color = primaryColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = { llmProgress.coerceIn(0.05f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(3.dp)),
                            color = primaryColor,
                            trackColor = bgColor
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    AuraTab.LIVE -> MimicoLiveRoomTab(
                        state = state,
                        character = activeChar,
                        analyzedPhoto = analyzedPhoto,
                        photoProcessing = photoProcessing,
                        lipSyncOpen = lipSyncOpen,
                        lipSyncWide = lipSyncWide,
                        micLevel = micLevel,
                        isListening = isListening,
                        isSpeaking = isSpeaking,
                        isThinking = isThinking,
                        callActive = callActive,
                        streamingReply = streamingReply,
                        autoLearnNotice = autoLearnNotice,
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
                        onOpenScenarios = { showScenarioSheet = true },
                        onPreviewVoice = viewModel::previewVoice,
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
                        onSelect = { viewModel.selectCharacter(it); currentTab = AuraTab.LIVE },
                        onCreateOpen = { showCreateDialog = true },
                        onDelete = viewModel::deleteCharacter,
                        onPickPhoto = onPickPhoto
                    )
                    AuraTab.STUDIO -> StudioAndPhotoTab(
                        state = state,
                        character = activeChar,
                        analyzedPhoto = analyzedPhoto,
                        photoProcessing = photoProcessing,
                        lipSyncOpen = lipSyncOpen,
                        lipSyncWide = lipSyncWide,
                        bgColor = bgColor,
                        surfaceColor = surfaceColor,
                        primaryColor = primaryColor,
                        secondaryColor = secondaryColor,
                        textMain = textMain,
                        textMuted = textMuted,
                        onPickPhoto = onPickPhoto,
                        onSetMode = viewModel::setAvatarMode,
                        onUpdateChar = viewModel::updateActiveCharacter,
                        onSelectTheme = viewModel::setThemePreset,
                        onSelectBubbleStyle = viewModel::setBubbleStyle,
                        onSelectAmbient = viewModel::setAmbientSound
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
                        onToggleAutoLearn = viewModel::setAutoLearnSettings,
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

    if (showScenarioSheet) {
        ScenarioPickerDialog(
            current = activeChar.scenario,
            surfaceColor = surfaceColor,
            primaryColor = primaryColor,
            textMain = textMain,
            textMuted = textMuted,
            onDismiss = { showScenarioSheet = false },
            onPick = { sc ->
                showScenarioSheet = false
                viewModel.setScenario(sc)
            }
        )
    }
}

/**
 * Écran Live plein écran façon Mimico :
 * Portrait/Photo animée 60 FPS en fond intégral, barre d'actions flottantes en verre sur le côté,
 * bulles de conversation translucides en bas, mode Focus Portrait 1-tap et badge d'auto-apprentissage.
 */
@Composable
private fun MimicoLiveRoomTab(
    state: AuraAppState,
    character: CompanionCharacter,
    analyzedPhoto: AnalyzedPhotoAvatar?,
    photoProcessing: Boolean,
    lipSyncOpen: Float,
    lipSyncWide: Float,
    micLevel: Float,
    isListening: Boolean,
    isSpeaking: Boolean,
    isThinking: Boolean,
    callActive: Boolean,
    streamingReply: String,
    autoLearnNotice: String?,
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
    onOpenScenarios: () -> Unit,
    onPreviewVoice: () -> Unit,
    onToggleContinuousCall: (Boolean) -> Unit,
    onToggleAutoSpeak: (Boolean) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var focusPortraitOnly by remember { mutableStateOf(false) }
    val chatListState = rememberLazyListState()

    LaunchedEffect(character.chatHistory.size, streamingReply) {
        if (!focusPortraitOnly && character.chatHistory.isNotEmpty()) {
            chatListState.animateScrollToItem(character.chatHistory.lastIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Portrait / Photo IA plein écran 60 FPS !
        if (character.avatarMode == AvatarSourceMode.PHOTO_AI && analyzedPhoto != null) {
            PhotoFaceAnimatorCanvas(
                analyzedPhoto = analyzedPhoto,
                character = character,
                emotion = character.currentEmotion,
                lipSyncOpen = lipSyncOpen,
                lipSyncWide = lipSyncWide,
                isListening = isListening,
                isThinking = isThinking,
                onPetAvatar = onPet,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            StudioAvatarCanvas(
                character = character,
                emotion = character.currentEmotion,
                lipSyncLevel = lipSyncOpen,
                lipSyncWide = lipSyncWide,
                isListening = isListening,
                onPetAvatar = onPet,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (photoProcessing) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xD8120A18)
            ) {
                Text("✨ Analyse IA du visage (478 points)…", color = primaryColor, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
            }
        }

        // 2. Barre supérieure Glassmorphism (Nom, Émotion, Affinité, Scénario, Statut)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xB8140C1C),
                    border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.45f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("${character.name} ${character.currentEmotion.emoji}", color = textMain, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Niv.${character.affinityLevel} • ${character.affinityTitleFr} • ${character.scenario.emoji}", color = primaryColor, fontSize = 11.sp)
                        }
                    }
                }

                // Pilule d'état live (Écoute / Parole / Réflexion) + Focus Portrait
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xB8140C1C),
                        modifier = Modifier.clickable { focusPortraitOnly = !focusPortraitOnly }
                    ) {
                        Text(
                            if (focusPortraitOnly) "💬 Chat" else "👁️ Plein écran",
                            color = textMain,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            // Badge Mimico d'auto-apprentissage en temps réel !
            if (autoLearnNotice != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = primaryColor.copy(alpha = 0.88f)
                ) {
                    Text(
                        autoLearnNotice,
                        color = bgColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            } else if (isListening || isThinking || isSpeaking) {
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xC8140C1C)) {
                    val txt = when {
                        isListening -> "🎙️ Je t'écoute… (${(micLevel * 100).toInt()} %)"
                        isThinking -> "💭 ${character.name} prépare sa réponse…"
                        else -> "🗣️ ${character.name} te parle…"
                    }
                    Text(txt, color = secondaryColor, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }

        // 3. Colonne flottante d'actions rapides à droite (style Mimico Live)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FloatingMimicoPill("📷", "Photo", primaryColor, onPickPhoto)
            FloatingMimicoPill(if (state.autoSpeakReplies) "🔊" else "🔇", "Voix", secondaryColor) { onToggleAutoSpeak(!state.autoSpeakReplies) }
            FloatingMimicoPill("🎭", "Scène", primaryColor, onOpenScenarios)
            FloatingMimicoPill("🌸", "Offrir", Color(0xFFFF9BD2)) { onGift("une rose parfumée") }
            FloatingMimicoPill("🗣️", "Test", secondaryColor, onPreviewVoice)
        }

        // 4. Zone de chat flottante translucide + micro appel vocal en bas
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, bgColor.copy(alpha = 0.82f), bgColor.copy(alpha = 0.96f))))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!focusPortraitOnly) {
                LazyColumn(
                    state = chatListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 225.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(character.chatHistory.takeLast(12)) { msg ->
                        MimicoChatBubble(
                            msg = msg,
                            charName = character.name,
                            userName = character.userName,
                            bubbleStyle = state.bubbleStyle,
                            surfaceColor = surfaceColor,
                            primaryColor = primaryColor,
                            textMain = textMain
                        )
                    }
                    if (isThinking && streamingReply.isNotBlank()) {
                        item {
                            MimicoChatBubble(
                                msg = AuraChatMessage("live", false, streamingReply, character.currentEmotion),
                                charName = character.name,
                                userName = character.userName,
                                bubbleStyle = state.bubbleStyle,
                                surfaceColor = surfaceColor,
                                primaryColor = primaryColor,
                                textMain = textMain
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // Barre de saisie + Gros bouton Appel Vocal Live
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledIconButton(
                    onClick = if (callActive || isListening || isSpeaking) onStopAll else onToggleMic,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (callActive || isListening) Color(0xFFFF4D7D) else primaryColor,
                        contentColor = bgColor
                    )
                ) {
                    Icon(
                        imageVector = if (callActive || isListening || isSpeaking) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = "Parler"
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Écris ou parle à ${character.name}…", fontSize = 13.sp, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xAA160E20),
                        unfocusedContainerColor = Color(0x99160E20),
                        focusedBorderColor = primaryColor,
                        unfocusedBorderColor = primaryColor.copy(alpha = 0.35f)
                    ),
                    maxLines = 2
                )

                FilledIconButton(
                    onClick = { val txt = inputText; inputText = ""; onSendText(txt) },
                    modifier = Modifier.size(46.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = secondaryColor, contentColor = bgColor)
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
                    if (state.continuousCallMode) "📞 Appel continu mains-libres actif" else "🎙️ Micro Whisper FR + Voix Piper FR",
                    color = textMuted,
                    fontSize = 11.sp
                )
                Text(
                    if (state.continuousCallMode) "Mode manuel" else "Mode Appel Continu",
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
private fun FloatingMimicoPill(emoji: String, label: String, accent: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Surface(
            shape = CircleShape,
            color = Color(0xB8140C1C),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
            modifier = Modifier.size(42.dp)
        ) {
            Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 18.sp) }
        }
        Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MimicoChatBubble(
    msg: AuraChatMessage,
    charName: String,
    userName: String,
    bubbleStyle: ChatBubbleStyle,
    surfaceColor: Color,
    primaryColor: Color,
    textMain: Color
) {
    val bg = when (bubbleStyle) {
        ChatBubbleStyle.FROSTED_GLASS -> if (msg.fromUser) primaryColor.copy(alpha = 0.34f) else Color(0xBB160E22)
        ChatBubbleStyle.FLOATING_MINIMAL -> if (msg.fromUser) primaryColor.copy(alpha = 0.22f) else Color(0x88100A18)
        ChatBubbleStyle.NEON_GLOW -> if (msg.fromUser) Color(0xCC241538) else Color(0xCC121428)
        ChatBubbleStyle.SOFT_CLASSIC -> if (msg.fromUser) primaryColor.copy(alpha = 0.38f) else surfaceColor.copy(alpha = 0.92f)
    }
    val border = if (bubbleStyle == ChatBubbleStyle.NEON_GLOW || bubbleStyle == ChatBubbleStyle.FROSTED_GLASS) {
        androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = if (msg.fromUser) 0.55f else 0.28f))
    } else null

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.fromUser) Alignment.End else Alignment.Start
    ) {
        Surface(shape = RoundedCornerShape(18.dp), color = bg, border = border, modifier = Modifier.widthIn(max = 295.dp)) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
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
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("8 Profils & Création Illimitée", color = textMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Chaque personnage a sa photo IA, sa voix, son caractère et sa mémoire", color = textMuted, fontSize = 12.sp)
                }
                Button(onClick = onCreateOpen, colors = ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = Color(state.themePreset.bgHex))) {
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
                modifier = Modifier.fillMaxWidth().clickable { onSelect(ch.id) }
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(54.dp).clip(CircleShape).background(Color(ch.studio.outfitColorHex)), contentAlignment = Alignment.Center) {
                        Text(if (ch.avatarMode == AvatarSourceMode.PHOTO_AI && ch.photoPath != null) "📷" else ch.currentEmotion.emoji, fontSize = 24.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${ch.name}${if (selected) " • ACTIVE" else ""}", color = textMain, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("${ch.relationRole} • ${ch.voicePack.labelFr}", color = secondaryColor, fontSize = 11.sp)
                        Text(ch.tagline, color = textMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Niv.${ch.affinityLevel} (${ch.affinityTitleFr}) • ${ch.memories.size} souvenirs", color = primaryColor, fontSize = 11.sp)
                    }
                    if (state.characters.size > 1) {
                        IconButton(onClick = { onDelete(ch.id) }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Supprimer", tint = textMuted) }
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
    lipSyncOpen: Float,
    lipSyncWide: Float,
    bgColor: Color,
    surfaceColor: Color,
    primaryColor: Color,
    secondaryColor: Color,
    textMain: Color,
    textMuted: Color,
    onPickPhoto: () -> Unit,
    onSetMode: (AvatarSourceMode) -> Unit,
    onUpdateChar: ((CompanionCharacter) -> CompanionCharacter) -> Unit,
    onSelectTheme: (AppThemePreset) -> Unit,
    onSelectBubbleStyle: (ChatBubbleStyle) -> Unit,
    onSelectAmbient: (AuraAmbientSound) -> Unit
) {
    val st = character.studio
    val anim = character.photoAnim
    val skinPalette = listOf(0xFFFFECE0L, 0xFFFFDFC8L, 0xFFF4CEB4L, 0xFFE3B18EL, 0xFFC58C64L, 0xFF935F42L, 0xFF6B422CL)
    val hairPalette = listOf(0xFF1F1D26L, 0xFF5A342BL, 0xFFB87B4BL, 0xFFF0CF8AL, 0xFFE84A6FL, 0xFF2B2D6EL, 0xFFD69AC4L, 0xFF39A98AL, 0xFFEDE8F5L, 0xFFC45C36L)
    val eyePalette = listOf(0xFF4F8A6BL, 0xFF4A78B8L, 0xFF7B61FFL, 0xFFFF5CA8L, 0xFF00D4B4L, 0xFFB56E3BL, 0xFFE85D5DL, 0xFFFFC857L)
    val outfitPalette = listOf(0xFFE87A9AL, 0xFF3E3BFFL, 0xFF2D465EL, 0xFFA28BFFL, 0xFF231F30L, 0xFF2FA87CL, 0xFFE68A4AL, 0xFF4A2559L, 0xFFE8E2F5L)

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.5.dp, primaryColor.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
            ) {
                if (character.avatarMode == AvatarSourceMode.PHOTO_AI && analyzedPhoto != null) {
                    PhotoFaceAnimatorCanvas(analyzedPhoto, character, character.currentEmotion, lipSyncOpen, lipSyncWide, false, false, {})
                } else {
                    StudioAvatarCanvas(character, character.currentEmotion, lipSyncOpen, lipSyncWide, false, {})
                }
                Surface(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp), shape = RoundedCornerShape(10.dp), color = Color(0xCC120C18)) {
                    val info = when {
                        photoProcessing -> "Analyse IA en cours…"
                        character.avatarMode == AvatarSourceMode.PHOTO_AI && analyzedPhoto != null ->
                            if (analyzedPhoto.rig.detectedByAi) "✓ Photo LivePortrait IA (478 points + paupières peau)" else "✓ Photo animée (mode portrait)"
                        else -> "Studio : ${st.artStyle.labelFr}"
                    }
                    Text(info, color = primaryColor, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AvatarSourceMode.entries.forEach { mode ->
                    FilterChip(selected = character.avatarMode == mode, onClick = { onSetMode(mode) }, label = { Text(mode.labelFr, fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                }
            }
        }

        // Photo IA LivePortrait + Réglages vivants
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📷 Photo Animée par IA (LivePortrait v2)", color = primaryColor, fontWeight = FontWeight.Bold)
                    Button(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = bgColor)) {
                        Text(if (character.photoPath == null) "Importer une photo de la galerie" else "Changer la photo animée")
                    }
                    if (character.photoPath != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Remplacer le fond de la photo par IA", color = textMain, fontSize = 13.sp)
                            Switch(checked = character.replacePhotoBackground, onCheckedChange = { c -> onUpdateChar { it.copy(replacePhotoBackground = c) } })
                        }
                        TraitSlider("Intensité Lip-Sync", (anim.lipSyncIntensity - 0.5f) / 1.2f, primaryColor) { n ->
                            onUpdateChar { it.copy(photoAnim = it.photoAnim.copy(lipSyncIntensity = 0.5f + n * 1.2f)) }
                        }
                        TraitSlider("Expressivité & Mouvements de tête", (anim.headMotionIntensity - 0.4f) / 1.2f, secondaryColor) { n ->
                            onUpdateChar { it.copy(photoAnim = it.photoAnim.copy(headMotionIntensity = 0.4f + n * 1.2f)) }
                        }
                        TraitSlider("Zoom / Cadrage Photo", (anim.photoZoom - 0.95f) / 0.40f, primaryColor) { n ->
                            onUpdateChar { it.copy(photoAnim = it.photoAnim.copy(photoZoom = 0.95f + n * 0.40f)) }
                        }
                        Text("10 Filtres Photo Cinéma", color = textMuted, fontSize = 12.sp)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(PhotoFilterStyle.entries) { pf ->
                                FilterChip(selected = character.photoFilter == pf, onClick = { onUpdateChar { it.copy(photoFilter = pf) } }, label = { Text(pf.labelFr, fontSize = 11.sp) })
                            }
                        }
                    }
                }
            }
        }

        // 10 Décors & Ambiance sonore
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🌌 10 Décors Animés & Ambiance Sonore", color = secondaryColor, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SceneBackdrop.entries) { sc ->
                            FilterChip(selected = character.backdrop == sc, onClick = { onUpdateChar { it.copy(backdrop = sc) } }, label = { Text(sc.labelFr, fontSize = 11.sp) })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AuraAmbientSound.entries) { amb ->
                            FilterChip(selected = state.ambientSound == amb, onClick = { onSelectAmbient(amb) }, label = { Text("${amb.emoji} ${amb.labelFr}", fontSize = 11.sp) })
                        }
                    }
                }
            }
        }

        // 10 Thèmes d'interface & 4 Styles de bulles Mimico
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✨ 10 Thèmes d'Interface & Styles de Chat", color = primaryColor, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AppThemePreset.entries) { preset ->
                            FilterChip(selected = state.themePreset == preset, onClick = { onSelectTheme(preset) }, label = { Text(preset.labelFr, fontSize = 11.sp) })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ChatBubbleStyle.entries) { bs ->
                            FilterChip(selected = state.bubbleStyle == bs, onClick = { onSelectBubbleStyle(bs) }, label = { Text(bs.labelFr, fontSize = 11.sp) })
                        }
                    }
                }
            }
        }

        // Studio Avatar Complet
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🎨 Studio Modèle Avatar (6 styles, 10 coiffures, 8 tenues)", color = secondaryColor, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(StudioArtStyle.entries) { style ->
                            FilterChip(selected = st.artStyle == style, onClick = { onUpdateChar { it.copy(avatarMode = AvatarSourceMode.STUDIO_MODEL, studio = it.studio.copy(artStyle = style)) } }, label = { Text(style.labelFr, fontSize = 11.sp) })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(HairStyle.entries) { hs ->
                            FilterChip(selected = st.hairStyle == hs, onClick = { onUpdateChar { it.copy(studio = it.studio.copy(hairStyle = hs)) } }, label = { Text(hs.labelFr, fontSize = 11.sp) })
                        }
                    }
                    ColorRow("Couleur cheveux", hairPalette, st.hairColorHex) { hex -> onUpdateChar { it.copy(studio = it.studio.copy(hairColorHex = hex)) } }
                    ColorRow("Reflets / mèches", hairPalette, st.hairHighlightHex) { hex -> onUpdateChar { it.copy(studio = it.studio.copy(hairHighlightHex = hex)) } }
                    ColorRow("Teint de peau", skinPalette, st.skinToneHex) { hex -> onUpdateChar { it.copy(studio = it.studio.copy(skinToneHex = hex)) } }
                    ColorRow("Couleur des yeux", eyePalette, st.eyeColorHex) { hex -> onUpdateChar { it.copy(studio = it.studio.copy(eyeColorHex = hex)) } }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(OutfitStyle.entries) { o -> FilterChip(selected = st.outfitStyle == o, onClick = { onUpdateChar { it.copy(studio = it.studio.copy(outfitStyle = o)) } }, label = { Text(o.labelFr, fontSize = 11.sp) }) }
                    }
                    ColorRow("Couleur tenue", outfitPalette, st.outfitColorHex) { hex -> onUpdateChar { it.copy(studio = it.studio.copy(outfitColorHex = hex)) } }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AccessoryStyle.entries) { acc -> FilterChip(selected = st.accessory == acc, onClick = { onUpdateChar { it.copy(studio = it.studio.copy(accessory = acc)) } }, label = { Text(acc.labelFr, fontSize = 11.sp) }) }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Taches de rousseur", color = textMain, fontSize = 13.sp)
                        Switch(checked = st.freckles, onCheckedChange = { f -> onUpdateChar { it.copy(studio = it.studio.copy(freckles = f)) } })
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
                    modifier = Modifier.size(30.dp).clip(CircleShape).background(Color(hex))
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
    onToggleAutoLearn: (Boolean) -> Unit,
    onToggleReminders: (Boolean) -> Unit
) {
    var newMemoryText by remember { mutableStateOf("") }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("IA Adaptative, Mémoire & Voix", color = textMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Tout ce que tu dis dans le chat peut mettre à jour ces réglages tout seul !", color = textMuted, fontSize = 12.sp)
        }

        // Switch Auto-Apprentissage Mimico
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🧠 Auto-Adaptation dans la conversation", color = primaryColor, fontWeight = FontWeight.Bold)
                        Text("Met automatiquement à jour ton prénom, surnom, tutoiement, curseurs et souvenirs quand tu en parles.", color = textMuted, fontSize = 11.sp)
                    }
                    Switch(checked = state.autoLearnSettings, onCheckedChange = onToggleAutoLearn)
                }
            }
        }

        // Identité & Relation
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Identité & Relation", color = primaryColor, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = character.name, onValueChange = { v -> onUpdateChar { it.copy(name = v.take(24)) } }, label = { Text("Nom du personnage") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = character.relationRole, onValueChange = { v -> onUpdateChar { it.copy(relationRole = v) } }, label = { Text("Rôle / Relation") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = character.userName, onValueChange = { v -> onUpdateChar { it.copy(userName = v.take(24)) } }, label = { Text("Ton prénom") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = character.userNickname, onValueChange = { v -> onUpdateChar { it.copy(userNickname = v.take(24)) } }, label = { Text("Surnom affectueux") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = character.backstory, onValueChange = { v -> onUpdateChar { it.copy(backstory = v) } }, label = { Text("Histoire personnelle") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = character.interests, onValueChange = { v -> onUpdateChar { it.copy(interests = v) } }, label = { Text("Centres d'intérêt") }, maxLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = character.customRules, onValueChange = { v -> onUpdateChar { it.copy(customRules = v) } }, label = { Text("Consignes spéciales") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Tutoiement naturel", color = textMain, fontSize = 13.sp)
                        Switch(checked = character.useTuForm, onCheckedChange = { u -> onUpdateChar { it.copy(useTuForm = u) } })
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ResponseLength.entries) { rl ->
                            FilterChip(selected = character.responseLength == rl, onClick = { onUpdateChar { it.copy(responseLength = rl) } }, label = { Text(rl.labelFr, fontSize = 11.sp) })
                        }
                    }
                }
            }
        }

        // 8 Curseurs
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

        // Voix Neurale FR
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
                            modifier = Modifier.fillMaxWidth().clickable { onUpdateChar { it.copy(voicePack = vp) } }
                        ) {
                            Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(vp.labelFr, color = textMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(vp.subtitleFr, color = textMuted, fontSize = 11.sp)
                                }
                                if (selected) Text("Actif ✓", color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                    TraitSlider("Vitesse (${String.format("%.2fx", character.voiceSpeed)})", (character.voiceSpeed - 0.75f) / 0.60f, secondaryColor) { n ->
                        onUpdateChar { it.copy(voiceSpeed = (0.75f + n * 0.60f).coerceIn(0.75f, 1.35f)) }
                    }
                    TraitSlider("Timbre (${String.format("%.2f", character.voicePitch)})", (character.voicePitch - 0.85f) / 0.35f, secondaryColor) { n ->
                        onUpdateChar { it.copy(voicePitch = (0.85f + n * 0.35f).coerceIn(0.85f, 1.20f)) }
                    }
                    Button(onClick = onPreviewVoice, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = secondaryColor, contentColor = Color(state.themePreset.bgHex))) {
                        Text("🔊 Tester cette voix maintenant")
                    }
                }
            }
        }

        // Mémoire Long-Terme Éditable
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🧠 Mémoire Long-Terme (${character.memories.size} faits)", color = primaryColor, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = newMemoryText, onValueChange = { newMemoryText = it }, placeholder = { Text("Ajouter un fait…", fontSize = 12.sp) }, modifier = Modifier.weight(1f), singleLine = true)
                        FilledTonalButton(onClick = { onAddMemory(newMemoryText); newMemoryText = "" }) { Text("Ajouter") }
                    }
                    character.memories.forEach { fact ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("• $fact", color = textMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemoveMemory(fact) }) { Icon(Icons.Rounded.Close, contentDescription = "Oublier", tint = textMuted, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        }

        // Moteurs intégrés
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = surfaceColor, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚡ Moteurs IA Intégrés (Zéro Internet)", color = secondaryColor, fontWeight = FontWeight.Bold)
                    Text("• Cerveau : $llmStatus", color = textMain, fontSize = 12.sp)
                    Text("• Audio : $voiceStatus", color = textMain, fontSize = 12.sp)
                    Text("• Vision : MediaPipe Face Landmarker 478 pts + Selfie Segmenter", color = textMain, fontSize = 12.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Messages locaux quotidiens", color = textMain, fontSize = 13.sp)
                        Switch(checked = state.remindersEnabled, onCheckedChange = onToggleReminders)
                    }
                    OutlinedButton(onClick = onClearChat, modifier = Modifier.fillMaxWidth()) { Text("Effacer la conversation de ${character.name}") }
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
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onChange, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
    }
}

@Composable
private fun ScenarioPickerDialog(
    current: RoleplayScenario,
    surfaceColor: Color,
    primaryColor: Color,
    textMain: Color,
    textMuted: Color,
    onDismiss: () -> Unit,
    onPick: (RoleplayScenario) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = surfaceColor,
        title = { Text("🎭 Choisir une ambiance / scénario", color = textMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoleplayScenario.entries.forEach { sc ->
                    val sel = sc == current
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (sel) primaryColor.copy(alpha = 0.25f) else Color(0xFF140D1C),
                        border = if (sel) androidx.compose.foundation.BorderStroke(1.dp, primaryColor) else null,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(sc) }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("${sc.emoji} ${sc.labelFr}", color = textMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            if (sc.promptHintFr.isNotBlank()) Text(sc.promptHintFr, color = textMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer", color = textMuted) } }
    )
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
    var style by remember { mutableStateOf(StudioArtStyle.MIMICO_GLAM) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = surfaceColor,
        title = { Text("Créer un nouveau personnage", color = textMain) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Prénom (ex. Luna, Chloé, Alex…)") }, singleLine = true)
                OutlinedTextField(value = role, onValueChange = { role = it }, label = { Text("Type de relation") }, singleLine = true)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(StudioArtStyle.entries) { st ->
                        FilterChip(selected = style == st, onClick = { style = st }, label = { Text(st.labelFr, fontSize = 11.sp) })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onCreate(name, role, style) }, colors = ButtonDefaults.buttonColors(containerColor = primaryColor)) { Text("Créer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler", color = textMuted) } }
    )
}
