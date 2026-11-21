package com.kinetix.coach.ui.coach

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kinetix.coach.KinetixApp
import com.kinetix.coach.data.AthleteProfile
import com.kinetix.coach.data.ChatMessage
import com.kinetix.coach.data.KnowledgeEntry
import com.kinetix.coach.data.ModelPrepareState
import com.kinetix.coach.data.RagHit
import com.kinetix.coach.data.RagSourceRef
import com.kinetix.coach.llm.EngineStatus
import com.kinetix.coach.llm.LlamaEngine
import com.kinetix.coach.rag.RagQueryProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CoachUiState(
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val streamingSources: List<RagSourceRef> = emptyList(),
    val liveTokensPerSec: Float = 0f,
    val threadCount: Int = LlamaEngine.recommendedThreads(),
    val ragEnabled: Boolean = true,
    val ragTopK: Int = 4,
    val noticeMessage: String? = null,
    val selectedSourceEntry: KnowledgeEntry? = null
)

class CoachViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KinetixApp

    val modelState: StateFlow<ModelPrepareState> = app.modelAssetManager.state
    val engineStatus: StateFlow<EngineStatus> = app.llamaEngine.status
    val messages: StateFlow<List<ChatMessage>> = app.userDb.messages
    val profile: StateFlow<AthleteProfile> = app.userDb.profile
        .stateIn(viewModelScope, SharingStarted.Eagerly, AthleteProfile())

    private val _uiState = MutableStateFlow(
        CoachUiState(
            threadCount = app.userDb.getPref("llm_threads", LlamaEngine.recommendedThreads().toString())
                .toIntOrNull()?.coerceIn(2, 6) ?: LlamaEngine.recommendedThreads(),
            ragEnabled = app.userDb.getPref("rag_enabled", "true") != "false",
            ragTopK = app.userDb.getPref("rag_top_k", "4").toIntOrNull()?.coerceIn(2, 5) ?: 4
        )
    )
    val uiState: StateFlow<CoachUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    fun sendMessage(rawQuestion: String) {
        val question = rawQuestion.trim()
        if (question.isBlank()) return

        if (_uiState.value.isGenerating) {
            stopGeneration()
        }

        val previousHistory = messages.value
            .takeLast(6)
            .map { it.isUser to it.text }

        app.userDb.insertMessage(
            ChatMessage(
                isUser = true,
                text = question
            )
        )

        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    streamingText = "",
                    streamingSources = emptyList(),
                    liveTokensPerSec = 0f,
                    noticeMessage = null
                )
            }

            // 1. Instant local SQLite FTS5 + BM25 + Synonym RAG retrieval (< 5 ms)
            val ragHits: List<RagHit> = if (_uiState.value.ragEnabled) {
                runCatching {
                    app.ragEngine.retrieve(query = question, topK = _uiState.value.ragTopK)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val sourceRefs = ragHits.map { hit ->
                RagSourceRef(
                    id = hit.entry.id,
                    title = hit.entry.title,
                    category = hit.entry.category,
                    subcategory = hit.entry.subcategory
                )
            }
            _uiState.update { it.copy(streamingSources = sourceRefs) }

            // 2. Ensure embedded or custom GGUF model is extracted and loaded in llama.cpp
            val readyEngine = ensureEngineLoaded()
            if (!readyEngine) {
                val fallback = buildOfflineRagFallback(question, ragHits)
                app.userDb.insertMessage(
                    ChatMessage(
                        isUser = false,
                        text = fallback,
                        sources = sourceRefs,
                        tokensPerSec = 0f
                    )
                )
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        streamingSources = emptyList(),
                        liveTokensPerSec = 0f
                    )
                }
                return@launch
            }

            // 3. Build grounded French coaching prompt with athlete profile + local RAG sources
            val promptPayload = RagQueryProcessor.buildCoachSystemAndUserMessage(
                userQuestion = question,
                profile = profile.value,
                ragHits = ragHits,
                recentHistory = previousHistory
            )

            // 4. Stream tokens directly from native llama.cpp engine
            val result = app.llamaEngine.generate(
                userTurnPayload = promptPayload,
                maxTokens = 512,
                temperature = 0.42f,
                topP = 0.90f,
                topK = 40,
                repeatPenalty = 1.12f
            ) { accumulated, tokPerSec ->
                _uiState.update {
                    it.copy(
                        streamingText = accumulated,
                        liveTokensPerSec = tokPerSec
                    )
                }
            }

            val finalText = when {
                result.fullText.isNotBlank() && result.cancelled ->
                    "${result.fullText}\n\n[Génération interrompue]"
                result.fullText.isNotBlank() ->
                    result.fullText
                result.cancelled ->
                    "Génération annulée."
                else ->
                    buildOfflineRagFallback(question, ragHits)
            }

            app.userDb.insertMessage(
                ChatMessage(
                    isUser = false,
                    text = finalText,
                    sources = sourceRefs,
                    tokensPerSec = result.tokensPerSec
                )
            )

            _uiState.update {
                it.copy(
                    isGenerating = false,
                    streamingText = "",
                    streamingSources = emptyList(),
                    liveTokensPerSec = result.tokensPerSec
                )
            }
        }
    }

    private suspend fun ensureEngineLoaded(): Boolean {
        if (app.llamaEngine.status.value is EngineStatus.Ready) return true
        val prep = when (val current = app.modelAssetManager.state.value) {
            is ModelPrepareState.Ready -> current
            else -> app.modelAssetManager.ensureActiveModelReady()
        }
        return if (prep is ModelPrepareState.Ready) {
            app.llamaEngine.loadModel(
                modelPath = prep.modelPath,
                displayName = prep.displayName,
                threads = _uiState.value.threadCount
            )
        } else {
            false
        }
    }

    private fun buildOfflineRagFallback(question: String, hits: List<RagHit>): String {
        if (hits.isEmpty()) {
            return "Le moteur LLM local n'est pas encore prêt et aucune fiche directe n'a été trouvée pour « $question ». Vérifiez l'état du modèle dans les paramètres (engrenage en haut à droite)."
        }
        return buildString {
            append("Moteur LLM en cours d'initialisation — synthèse directe depuis votre base locale KINETIX :\n\n")
            hits.take(3).forEachIndexed { idx, hit ->
                append("${idx + 1}. **${hit.entry.title}** (${hit.entry.subcategory})\n")
                append(hit.entry.summary.trim()).append("\n\n")
            }
            append("Touchez une source ci-dessous pour ouvrir la fiche complète.")
        }.trim()
    }

    fun stopGeneration() {
        app.llamaEngine.cancelGeneration()
    }

    fun clearHistory() {
        if (_uiState.value.isGenerating) {
            stopGeneration()
        }
        app.userDb.clearChat()
    }

    fun updateThreads(threads: Int) {
        val clamped = threads.coerceIn(2, 6)
        app.userDb.setPref("llm_threads", clamped.toString())
        app.llamaEngine.updateThreads(clamped)
        _uiState.update { it.copy(threadCount = clamped) }
    }

    fun updateRagConfig(enabled: Boolean, topK: Int) {
        val clampedK = topK.coerceIn(2, 5)
        app.userDb.setPref("rag_enabled", enabled.toString())
        app.userDb.setPref("rag_top_k", clampedK.toString())
        _uiState.update { it.copy(ragEnabled = enabled, ragTopK = clampedK) }
    }

    fun importCustomModel(uri: Uri) {
        if (_uiState.value.isGenerating) stopGeneration()
        viewModelScope.launch {
            _uiState.update { it.copy(noticeMessage = "Import du fichier GGUF en cours…") }
            val result = app.modelAssetManager.importCustomModel(uri)
            if (result is ModelPrepareState.Ready) {
                val ok = app.llamaEngine.loadModel(
                    modelPath = result.modelPath,
                    displayName = result.displayName,
                    threads = _uiState.value.threadCount
                )
                _uiState.update {
                    it.copy(
                        noticeMessage = if (ok) {
                            "Modèle actif : ${result.displayName} (${result.sizeMb} Mo)"
                        } else {
                            "Modèle copié mais impossible de le charger dans llama.cpp."
                        }
                    )
                }
            } else if (result is ModelPrepareState.Error) {
                _uiState.update { it.copy(noticeMessage = result.message) }
            }
        }
    }

    fun resetToEmbeddedModel() {
        if (_uiState.value.isGenerating) stopGeneration()
        viewModelScope.launch {
            _uiState.update { it.copy(noticeMessage = "Bascule vers Gemma-3 1B intégré…") }
            val result = app.modelAssetManager.switchToEmbeddedModel()
            if (result is ModelPrepareState.Ready) {
                val ok = app.llamaEngine.loadModel(
                    modelPath = result.modelPath,
                    displayName = result.displayName,
                    threads = _uiState.value.threadCount
                )
                _uiState.update {
                    it.copy(
                        noticeMessage = if (ok) {
                            "Modèle intégré restauré : ${result.displayName}"
                        } else {
                            "Échec du chargement du modèle intégré."
                        }
                    )
                }
            } else if (result is ModelPrepareState.Error) {
                _uiState.update { it.copy(noticeMessage = result.message) }
            }
        }
    }

    fun openSourceEntry(id: Long) {
        viewModelScope.launch {
            val entry = app.ragEngine.getEntryById(id)
            _uiState.update { it.copy(selectedSourceEntry = entry) }
        }
    }

    fun dismissSourceEntry() {
        _uiState.update { it.copy(selectedSourceEntry = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
    }
}
