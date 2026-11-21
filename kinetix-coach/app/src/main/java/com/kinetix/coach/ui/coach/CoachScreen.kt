package com.kinetix.coach.ui.coach

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kinetix.coach.data.ChatMessage
import com.kinetix.coach.data.KnowledgeEntry
import com.kinetix.coach.data.ModelPrepareState
import com.kinetix.coach.data.RagSourceRef
import com.kinetix.coach.llm.EngineStatus
import com.kinetix.coach.ui.components.HairlineDivider
import com.kinetix.coach.ui.components.IndustrialCard
import com.kinetix.coach.ui.components.SectionLabel
import com.kinetix.coach.ui.components.StatusPill
import com.kinetix.coach.ui.components.categoryColor
import com.kinetix.coach.ui.components.categoryLabelFr
import com.kinetix.coach.ui.theme.BorderHairline
import com.kinetix.coach.ui.theme.CharcoalSurface
import com.kinetix.coach.ui.theme.CrispWhite
import com.kinetix.coach.ui.theme.ElevatedSurface
import com.kinetix.coach.ui.theme.FormCaution
import com.kinetix.coach.ui.theme.FormFault
import com.kinetix.coach.ui.theme.FormGood
import com.kinetix.coach.ui.theme.ObsidianBg
import com.kinetix.coach.ui.theme.SignalOrange
import com.kinetix.coach.ui.theme.SteelGray
import kotlin.math.roundToInt

private val QUICK_COACH_PROMPTS = listOf(
    "Programme hypertrophie 4 jours Upper/Lower",
    "Comment corriger le valgus des genoux au squat ?",
    "Combien de protéines par jour en sèche ?",
    "Protocole créatine monohydrate : dosage et timing",
    "Douleur à l'épaule au développé couché : que faire ?",
    "Échauffement RAMP complet avant séance jambes"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachScreen(viewModel: CoachViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    var inputText by rememberSaveable { mutableStateOf("") }
    var showSettingsSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    val ggufPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importCustomModel(uri)
        }
    }

    LaunchedEffect(messages.size, uiState.streamingText.length) {
        val total = messages.size + if (uiState.isGenerating) 1 else 0
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
            .imePadding()
    ) {
        // Top Swiss Industrial Bar
        CoachTopBar(
            modelState = modelState,
            engineStatus = engineStatus,
            liveTokensPerSec = uiState.liveTokensPerSec,
            hasMessages = messages.isNotEmpty(),
            onClearChat = { viewModel.clearHistory() },
            onOpenSettings = { showSettingsSheet = true }
        )

        HairlineDivider()

        // Model Extraction / Loading Banner
        ModelReadinessBanner(
            modelState = modelState,
            engineStatus = engineStatus,
            noticeMessage = uiState.noticeMessage,
            onDismissNotice = { viewModel.dismissNotice() }
        )

        // Messages List or Empty Quick-Prompt State
        if (messages.isEmpty() && !uiState.isGenerating) {
            CoachEmptyState(
                ragEnabled = uiState.ragEnabled,
                onSelectPrompt = { prompt ->
                    viewModel.sendMessage(prompt)
                },
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatBubbleItem(
                        message = msg,
                        onOpenSource = { sourceId -> viewModel.openSourceEntry(sourceId) }
                    )
                }
                if (uiState.isGenerating) {
                    item(key = "streaming_assistant_bubble") {
                        ChatBubbleItem(
                            message = ChatMessage(
                                id = -1L,
                                isUser = false,
                                text = uiState.streamingText.ifBlank { "Analyse locale et génération…" },
                                sources = uiState.streamingSources,
                                tokensPerSec = uiState.liveTokensPerSec,
                                isStreaming = true
                            ),
                            onOpenSource = { sourceId -> viewModel.openSourceEntry(sourceId) }
                        )
                    }
                }
            }
        }

        HairlineDivider()

        // Bottom Input Bar
        CoachInputBar(
            value = inputText,
            onValueChange = { inputText = it },
            isGenerating = uiState.isGenerating,
            onSend = {
                val trimmed = inputText.trim()
                if (trimmed.isNotEmpty()) {
                    inputText = ""
                    viewModel.sendMessage(trimmed)
                }
            },
            onStop = { viewModel.stopGeneration() }
        )
    }

    // Settings Bottom Sheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CharcoalSurface,
            contentColor = CrispWhite
        ) {
            CoachSettingsSheetContent(
                modelState = modelState,
                engineStatus = engineStatus,
                uiState = uiState,
                onThreadsChanged = { viewModel.updateThreads(it) },
                onRagChanged = { enabled, topK -> viewModel.updateRagConfig(enabled, topK) },
                onImportGguf = {
                    ggufPickerLauncher.launch(arrayOf("*/*"))
                },
                onResetEmbedded = { viewModel.resetToEmbeddedModel() }
            )
        }
    }

    // Source Entry Inspector Modal
    uiState.selectedSourceEntry?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissSourceEntry() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = CharcoalSurface,
            contentColor = CrispWhite
        ) {
            SourceArticleSheet(
                entry = entry,
                onClose = { viewModel.dismissSourceEntry() }
            )
        }
    }
}

@Composable
private fun CoachTopBar(
    modelState: ModelPrepareState,
    engineStatus: EngineStatus,
    liveTokensPerSec: Float,
    hasMessages: Boolean,
    onClearChat: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val (statusLabel, dotColor) = when {
        engineStatus is EngineStatus.Ready -> "LOCAL PRÊT" to FormGood
        modelState is ModelPrepareState.Extracting -> "EXTRACTION ${modelState.progressPct}%" to FormCaution
        engineStatus is EngineStatus.Loading -> "CHARGEMENT" to FormCaution
        engineStatus is EngineStatus.Error || modelState is ModelPrepareState.Error -> "HORS LIGNE (RAG)" to FormFault
        else -> "INITIALISATION" to SteelGray
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CharcoalSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "COACH IA KINETIX",
                style = MaterialTheme.typography.titleMedium,
                color = CrispWhite,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(text = statusLabel, dotColor = dotColor, onClick = onOpenSettings)
                if (liveTokensPerSec > 0.5f) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = ElevatedSurface,
                        border = BorderStroke(1.dp, BorderHairline)
                    ) {
                        Text(
                            text = String.format("%.1f tok/s", liveTokensPerSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = SignalOrange,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasMessages) {
                IconButton(onClick = onClearChat) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Effacer l'historique",
                        tint = SteelGray
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Paramètres moteur IA",
                    tint = CrispWhite
                )
            }
        }
    }
}

@Composable
private fun ModelReadinessBanner(
    modelState: ModelPrepareState,
    engineStatus: EngineStatus,
    noticeMessage: String?,
    onDismissNotice: () -> Unit
) {
    when {
        modelState is ModelPrepareState.Extracting -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElevatedSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = modelState.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = CrispWhite
                    )
                    Text(
                        text = "${modelState.progressPct}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = SignalOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { modelState.progressPct / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = SignalOrange,
                    trackColor = BorderHairline
                )
            }
            HairlineDivider()
        }

        engineStatus is EngineStatus.Loading -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElevatedSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = engineStatus.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = CrispWhite
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = SignalOrange,
                    trackColor = BorderHairline
                )
            }
            HairlineDivider()
        }

        !noticeMessage.isNullOrBlank() -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElevatedSurface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = noticeMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = CrispWhite,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismissNotice, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Fermer",
                        tint = SteelGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            HairlineDivider()
        }
    }
}

@Composable
private fun CoachEmptyState(
    ragEnabled: Boolean,
    onSelectPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        IndustrialCard(containerColor = CharcoalSurface) {
            Text(
                text = "COACHING BIOMÉCANIQUE & NUTRITION 100% HORS-LIGNE",
                style = MaterialTheme.typography.labelMedium,
                color = SignalOrange,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Gemma-3 1B tourne directement sur votre appareil, relié en temps réel aux 2 683 fiches locales KINETIX (exercices, CIQUAL/USDA, programmes, biomécanique, blessures).",
                style = MaterialTheme.typography.bodyMedium,
                color = SteelGray
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (ragEnabled) "RAG local actif · Recherche FTS5/BM25 < 5 ms" else "RAG local désactivé (mode LLM pur)",
                style = MaterialTheme.typography.labelSmall,
                color = if (ragEnabled) FormGood else FormCaution
            )
        }

        SectionLabel(text = "Questions rapides")

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK_COACH_PROMPTS.forEach { prompt ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectPrompt(prompt) },
                    shape = RoundedCornerShape(10.dp),
                    color = CharcoalSurface,
                    border = BorderStroke(1.dp, BorderHairline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CrispWhite,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            tint = SignalOrange,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatBubbleItem(
    message: ChatMessage,
    onOpenSource: (Long) -> Unit
) {
    var sourcesExpanded by remember(message.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 330.dp),
            shape = RoundedCornerShape(
                topStart = 10.dp,
                topEnd = 10.dp,
                bottomStart = if (message.isUser) 10.dp else 3.dp,
                bottomEnd = if (message.isUser) 3.dp else 10.dp
            ),
            color = if (message.isUser) ElevatedSurface else CharcoalSurface,
            border = BorderStroke(
                width = 1.dp,
                color = if (message.isUser) SignalOrange.copy(alpha = 0.55f) else BorderHairline
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (message.isUser) "ATHLÈTE" else "KINETIX COACH",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isUser) SignalOrange else SteelGray,
                        fontWeight = FontWeight.Bold
                    )
                    if (!message.isUser && message.tokensPerSec > 0.5f) {
                        Text(
                            text = String.format("%.1f tok/s", message.tokensPerSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = SteelGray
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CrispWhite
                )

                if (message.isStreaming) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = SignalOrange,
                        trackColor = BorderHairline
                    )
                }

                if (!message.isUser && message.sources.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    HairlineDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { sourcesExpanded = !sourcesExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = SignalOrange,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Sources locales (${message.sources.size})",
                                style = MaterialTheme.typography.labelMedium,
                                color = CrispWhite,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Icon(
                            imageVector = if (sourcesExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = SteelGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    if (sourcesExpanded) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            message.sources.forEach { src ->
                                SourceBadgeChip(
                                    source = src,
                                    onClick = { onOpenSource(src.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceBadgeChip(
    source: RagSourceRef,
    onClick: () -> Unit
) {
    val accent = categoryColor(source.category)
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = ElevatedSurface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 12.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = source.title,
                style = MaterialTheme.typography.labelSmall,
                color = CrispWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CoachInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CharcoalSurface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = "Posez une question sport, posture, nutrition…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SteelGray
                )
            },
            maxLines = 4,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ObsidianBg,
                unfocusedContainerColor = ObsidianBg,
                focusedBorderColor = SignalOrange,
                unfocusedBorderColor = BorderHairline,
                focusedTextColor = CrispWhite,
                unfocusedTextColor = CrispWhite,
                cursorColor = SignalOrange
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (!isGenerating) onSend() })
        )

        if (isGenerating) {
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onStop),
                shape = RoundedCornerShape(10.dp),
                color = FormFault
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Arrêter la génération",
                        tint = CrispWhite
                    )
                }
            }
        } else {
            val enabled = value.isNotBlank()
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(enabled = enabled, onClick = onSend),
                shape = RoundedCornerShape(10.dp),
                color = if (enabled) SignalOrange else ElevatedSurface,
                border = BorderStroke(1.dp, if (enabled) SignalOrange else BorderHairline)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Envoyer",
                        tint = if (enabled) CrispWhite else SteelGray
                    )
                }
            }
        }
    }
}

@Composable
private fun CoachSettingsSheetContent(
    modelState: ModelPrepareState,
    engineStatus: EngineStatus,
    uiState: CoachUiState,
    onThreadsChanged: (Int) -> Unit,
    onRagChanged: (Boolean, Int) -> Unit,
    onImportGguf: () -> Unit,
    onResetEmbedded: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "PARAMÈTRES MOTEUR LOCAL",
            style = MaterialTheme.typography.titleLarge,
            color = CrispWhite,
            fontWeight = FontWeight.Bold
        )

        // Active Model Card
        IndustrialCard(containerColor = ObsidianBg) {
            SectionLabel(text = "Modèle GGUF actif")
            Spacer(Modifier.height(8.dp))
            val activeName = when (engineStatus) {
                is EngineStatus.Ready -> engineStatus.modelName
                else -> when (modelState) {
                    is ModelPrepareState.Ready -> modelState.displayName
                    is ModelPrepareState.Extracting -> modelState.label
                    is ModelPrepareState.Error -> modelState.message
                    ModelPrepareState.Idle -> "Gemma-3 1B Q4_K_M (Intégré)"
                }
            }
            Text(
                text = activeName,
                style = MaterialTheme.typography.titleMedium,
                color = CrispWhite
            )
            if (modelState is ModelPrepareState.Ready) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Taille : ${modelState.sizeMb} Mo · Contexte : 2048 tokens · 100% hors-ligne",
                    style = MaterialTheme.typography.labelMedium,
                    color = SteelGray
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onImportGguf,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SignalOrange,
                    contentColor = CrispWhite
                )
            ) {
                Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Importer un modèle .gguf externe")
            }
            if (modelState is ModelPrepareState.Ready && modelState.isCustom) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onResetEmbedded,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderHairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CrispWhite)
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Revenir à Gemma-3 1B intégré")
                }
            }
        }

        // CPU Threads
        IndustrialCard(containerColor = ObsidianBg) {
            SectionLabel(
                text = "Threads CPU (llama.cpp)",
                trailing = "${uiState.threadCount} threads"
            )
            Spacer(Modifier.height(6.dp))
            Slider(
                value = uiState.threadCount.toFloat(),
                onValueChange = { onThreadsChanged(it.roundToInt()) },
                valueRange = 2f..6f,
                steps = 3,
                colors = SliderDefaults.colors(
                    thumbColor = SignalOrange,
                    activeTrackColor = SignalOrange,
                    inactiveTrackColor = BorderHairline
                )
            )
            Text(
                text = "Recommandé : 4 threads sur processeurs ARM64 octo-cœurs pour un débit optimal sans chauffe.",
                style = MaterialTheme.typography.labelMedium,
                color = SteelGray
            )
        }

        // Local RAG Config
        IndustrialCard(containerColor = ObsidianBg) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BASE DE CONNAISSANCES RAG",
                        style = MaterialTheme.typography.labelMedium,
                        color = CrispWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Injecte les meilleures fiches locales (FTS5/BM25) dans chaque réponse.",
                        style = MaterialTheme.typography.labelMedium,
                        color = SteelGray
                    )
                }
                Switch(
                    checked = uiState.ragEnabled,
                    onCheckedChange = { onRagChanged(it, uiState.ragTopK) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CrispWhite,
                        checkedTrackColor = SignalOrange,
                        uncheckedThumbColor = SteelGray,
                        uncheckedTrackColor = ElevatedSurface
                    )
                )
            }
            if (uiState.ragEnabled) {
                Spacer(Modifier.height(12.dp))
                HairlineDivider()
                Spacer(Modifier.height(10.dp))
                SectionLabel(
                    text = "Nombre de sources injectées (Top-K)",
                    trailing = "${uiState.ragTopK} fiches"
                )
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = uiState.ragTopK.toFloat(),
                    onValueChange = { onRagChanged(true, it.roundToInt()) },
                    valueRange = 2f..5f,
                    steps = 2,
                    colors = SliderDefaults.colors(
                        thumbColor = SignalOrange,
                        activeTrackColor = SignalOrange,
                        inactiveTrackColor = BorderHairline
                    )
                )
            }
        }
    }
}

@Composable
private fun SourceArticleSheet(
    entry: KnowledgeEntry,
    onClose: () -> Unit
) {
    val accent = categoryColor(entry.category)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 18.dp)
                        .background(accent, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${categoryLabelFr(entry.category)} · ${entry.subcategory.uppercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Fermer", tint = SteelGray)
            }
        }
        Text(
            text = entry.title,
            style = MaterialTheme.typography.headlineMedium,
            color = CrispWhite
        )
        if (entry.summary.isNotBlank()) {
            IndustrialCard(containerColor = ObsidianBg, borderColor = accent.copy(alpha = 0.4f)) {
                Text(
                    text = entry.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CrispWhite
                )
            }
        }
        Text(
            text = entry.body,
            style = MaterialTheme.typography.bodyMedium,
            color = CrispWhite
        )
    }
}
