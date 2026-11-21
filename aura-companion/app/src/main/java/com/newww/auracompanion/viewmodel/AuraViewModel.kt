package com.newww.auracompanion.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newww.auracompanion.ai.AuraLlamaEngine
import com.newww.auracompanion.ai.EngineInitState
import com.newww.auracompanion.ai.OfflineVoicePipeline
import com.newww.auracompanion.data.AuraRepository
import com.newww.auracompanion.engine.AutoProfileLearner
import com.newww.auracompanion.engine.CompanionBrainEngine
import com.newww.auracompanion.model.*
import com.newww.auracompanion.notifications.AuraReminderWorker
import com.newww.auracompanion.vision.AnalyzedPhotoAvatar
import com.newww.auracompanion.vision.PhotoFaceRigAnalyzer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class AuraViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AuraRepository(application)
    private val llm = AuraLlamaEngine(application)
    private val voice = OfflineVoicePipeline(application)
    private val photoAnalyzer = PhotoFaceRigAnalyzer(application)

    private val _state = MutableStateFlow(repo.load())
    val state: StateFlow<AuraAppState> = _state.asStateFlow()

    val llmState: StateFlow<EngineInitState> = llm.state
    val llmProgress: StateFlow<Float> = llm.progress
    val llmStatus: StateFlow<String> = llm.statusMessage

    val voiceReady: StateFlow<Boolean> = voice.voiceReady
    val voiceStatus: StateFlow<String> = voice.voiceStatus
    val isListening: StateFlow<Boolean> = voice.isListening
    val isSpeaking: StateFlow<Boolean> = voice.isSpeaking
    val lipSyncLevel: StateFlow<Float> = voice.lipSyncLevel
    val lipSyncWide: StateFlow<Float> = voice.lipSyncWide
    val micLevel: StateFlow<Float> = voice.micLevel

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _streamingReply = MutableStateFlow("")
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

    private val _analyzedPhoto = MutableStateFlow<AnalyzedPhotoAvatar?>(null)
    val analyzedPhoto: StateFlow<AnalyzedPhotoAvatar?> = _analyzedPhoto.asStateFlow()

    private val _photoProcessing = MutableStateFlow(false)
    val photoProcessing: StateFlow<Boolean> = _photoProcessing.asStateFlow()

    private val _callActive = MutableStateFlow(false)
    val callActive: StateFlow<Boolean> = _callActive.asStateFlow()

    private val _autoLearnNotice = MutableStateFlow<String?>(null)
    val autoLearnNotice: StateFlow<String?> = _autoLearnNotice.asStateFlow()

    private var callJob: Job? = null
    private var noticeJob: Job? = null

    init {
        AuraReminderWorker.schedule(application)
        // Voix initialisée en premier (< 0,5 s grâce à espeak-ng-data.zip !), puis LLM 3B sans contention I/O !
        viewModelScope.launch {
            voice.initialize()
            voice.setAmbientSound(_state.value.ambientSound)
            llm.ensureLoaded()
        }
        viewModelScope.launch {
            loadPhotoForActiveCharacter()
        }
    }

    override fun onCleared() {
        stopCall()
        voice.release()
        llm.release()
        photoAnalyzer.release()
        super.onCleared()
    }

    fun selectCharacter(id: String) {
        stopCall()
        voice.stopSpeaking()
        commit(_state.value.copy(activeCharacterId = id))
        viewModelScope.launch { loadPhotoForActiveCharacter() }
    }

    fun createNewCharacter(name: String, relation: String, artStyle: StudioArtStyle) {
        val cleanName = name.trim().ifEmpty { "Nouvelle Compagne" }.take(24)
        val id = "custom_${System.currentTimeMillis()}"
        val newChar = CompanionCharacter(
            id = id,
            name = cleanName,
            tagline = "Personnage personnalisé créé par toi",
            relationRole = relation.trim().ifEmpty { "Petite amie personnalisée" },
            backstory = "$cleanName a été créée sur mesure pour t'accompagner chaque jour hors-ligne.",
            interests = "Discussions complices, musique, cinéma, voyages",
            studio = StudioAppearance(artStyle = artStyle),
            chatHistory = listOf(
                AuraChatMessage(
                    id = "${id}_hi",
                    fromUser = false,
                    text = "Bonjour ! Je suis $cleanName. Parle-moi de toi ou importe une photo pour m'animer en direct !",
                    emotion = AvatarEmotion.HAPPY
                )
            )
        )
        commit(_state.value.copy(activeCharacterId = id, characters = _state.value.characters + newChar))
        _analyzedPhoto.value = null
    }

    fun deleteCharacter(id: String) {
        if (_state.value.characters.size <= 1) return
        val remaining = _state.value.characters.filterNot { it.id == id }
        val nextActive = if (_state.value.activeCharacterId == id) remaining.first().id else _state.value.activeCharacterId
        commit(_state.value.copy(activeCharacterId = nextActive, characters = remaining))
        viewModelScope.launch { loadPhotoForActiveCharacter() }
    }

    fun updateActiveCharacter(transform: (CompanionCharacter) -> CompanionCharacter) {
        val activeId = _state.value.activeCharacter.id
        val updatedList = _state.value.characters.map { ch -> if (ch.id == activeId) transform(ch) else ch }
        commit(_state.value.copy(characters = updatedList))
    }

    fun importPhotoForActiveCharacter(uri: Uri) {
        val charId = _state.value.activeCharacter.id
        _photoProcessing.value = true
        viewModelScope.launch {
            val savedPath = photoAnalyzer.importAndSavePhoto(uri, charId)
            if (savedPath != null) {
                updateActiveCharacter { it.copy(avatarMode = AvatarSourceMode.PHOTO_AI, photoPath = savedPath) }
                _analyzedPhoto.value = photoAnalyzer.analyzePhotoFile(savedPath)
            }
            _photoProcessing.value = false
        }
    }

    fun setAvatarMode(mode: AvatarSourceMode) {
        updateActiveCharacter { it.copy(avatarMode = mode) }
        if (mode == AvatarSourceMode.PHOTO_AI) viewModelScope.launch { loadPhotoForActiveCharacter() }
    }

    fun setThemePreset(preset: AppThemePreset) = commit(_state.value.copy(themePreset = preset))
    fun setBubbleStyle(style: ChatBubbleStyle) = commit(_state.value.copy(bubbleStyle = style))
    fun setAutoSpeak(enabled: Boolean) = commit(_state.value.copy(autoSpeakReplies = enabled))
    fun setContinuousCall(enabled: Boolean) = commit(_state.value.copy(continuousCallMode = enabled))
    fun setAutoLearnSettings(enabled: Boolean) = commit(_state.value.copy(autoLearnSettings = enabled))
    fun setRemindersEnabled(enabled: Boolean) = commit(_state.value.copy(remindersEnabled = enabled))

    fun setAmbientSound(sound: AuraAmbientSound) {
        commit(_state.value.copy(ambientSound = sound))
        voice.setAmbientSound(sound)
    }

    fun setScenario(scenario: RoleplayScenario) {
        updateActiveCharacter { it.copy(scenario = scenario) }
        showNotice("Scénario actif : ${scenario.emoji} ${scenario.labelFr}")
    }

    fun addMemory(fact: String) {
        val clean = fact.trim()
        if (clean.isEmpty()) return
        updateActiveCharacter { ch -> ch.copy(memories = (ch.memories + clean).distinct().takeLast(30)) }
    }

    fun removeMemory(fact: String) {
        updateActiveCharacter { ch -> ch.copy(memories = ch.memories - fact) }
    }

    fun clearChatHistory() {
        updateActiveCharacter { ch -> ch.copy(chatHistory = emptyList()) }
    }

    fun onPetAvatar() {
        updateActiveCharacter { ch ->
            val emo = if (ch.traits.shyness > 0.65f) AvatarEmotion.SHY else AvatarEmotion.LOVE
            ch.copy(affinityXp = ch.affinityXp + 3, currentEmotion = emo)
        }
    }

    fun giveSmallGift(giftLabel: String) {
        sendUserMessage("Je t'offre $giftLabel !")
    }

    fun previewVoice() {
        val ch = _state.value.activeCharacter
        viewModelScope.launch {
            voice.speak(
                text = "Coucou ${ch.userNickname}, c'est ${ch.name} ! Ma voix française fonctionne à cent pour cent hors ligne et mes lèvres suivent chaque mot.",
                voicePack = ch.voicePack,
                speed = ch.voiceSpeed,
                pitch = ch.voicePitch
            )
        }
    }

    fun sendUserMessage(rawText: String, speakReply: Boolean = _state.value.autoSpeakReplies) {
        val text = rawText.trim()
        if (text.isEmpty() || _isThinking.value) return
        viewModelScope.launch { processUserTurn(text, speakReply) }
    }

    fun toggleVoiceTurnOrCall() {
        if (_callActive.value || isListening.value) {
            stopCall()
            return
        }
        val continuous = _state.value.continuousCallMode
        _callActive.value = true
        callJob = viewModelScope.launch {
            do {
                val spoken = voice.recordAndTranscribeFrench(autoStopOnSilence = true, maxSeconds = 12)
                if (!_callActive.value) break
                if (spoken.isNotBlank()) {
                    processUserTurn(spoken, speakReply = true)
                } else {
                    delay(350L)
                }
            } while (continuous && _callActive.value)
            _callActive.value = false
        }
    }

    fun stopCall() {
        _callActive.value = false
        callJob?.cancel()
        callJob = null
        voice.stopListening()
        voice.stopSpeaking()
        llm.stop()
        _isThinking.value = false
        _streamingReply.value = ""
    }

    private suspend fun processUserTurn(userText: String, speakReply: Boolean) {
        voice.stopSpeaking()

        // 1. Auto-apprentissage immédiat des réglages et souvenirs depuis le message utilisateur !
        val learnedFromUser = AutoProfileLearner.learnFromUserMessage(
            character = _state.value.activeCharacter,
            rawMessage = userText,
            enabled = _state.value.autoLearnSettings
        )
        if (learnedFromUser.learnedBadges.isNotEmpty()) {
            showNotice("✨ Réglages adaptés : ${learnedFromUser.learnedBadges.joinToString(" • ")}")
        }

        // Garde l'historique AVANT d'ajouter le message courant pour construire un vrai prompt multi-tours sans doublon
        val historySnapshotChar = learnedFromUser.updatedCharacter
        val userMsg = AuraChatMessage(UUID.randomUUID().toString(), fromUser = true, text = userText)

        updateActiveCharacter {
            historySnapshotChar.copy(
                chatHistory = (historySnapshotChar.chatHistory + userMsg).takeLast(60),
                affinityXp = historySnapshotChar.affinityXp + 5
            )
        }

        _isThinking.value = true
        _streamingReply.value = ""

        // 2. Attend proprement que le modèle Llama-3.2 3B soit prêt si premier chargement en cours
        if (llm.state.value != EngineInitState.READY) {
            _streamingReply.value = "Préparation du cerveau IA 3B…"
            llm.ensureLoaded()
            _streamingReply.value = ""
        }

        val prompt = CompanionBrainEngine.buildPrompt(historySnapshotChar, userText)
        val buf = StringBuilder()
        val rawReply = llm.generate(
            prompt = prompt,
            maxTokens = historySnapshotChar.responseLength.maxTokens,
            temperature = 0.65f
        ) { piece ->
            buf.append(piece)
            _streamingReply.value = CompanionBrainEngine.parseReply(buf.toString()).cleanText
        }

        val parsed = if (rawReply.isNotBlank()) {
            CompanionBrainEngine.parseReply(rawReply)
        } else {
            CompanionBrainEngine.buildFallbackReply(historySnapshotChar, userText)
        }

        // 3. Enregistre aussi les éventuels souvenirs [MEM: ...] détectés par l'IA
        if (_state.value.autoLearnSettings && !parsed.memoryNote.isNullOrBlank()) {
            val merged = AutoProfileLearner.mergeLlmMemoryNote(_state.value.activeCharacter, parsed.memoryNote)
            if (merged.learnedBadges.isNotEmpty()) {
                updateActiveCharacter { merged.updatedCharacter }
                showNotice("🧠 ${merged.learnedBadges.first()}")
            }
        }

        val botMsg = AuraChatMessage(
            id = UUID.randomUUID().toString(),
            fromUser = false,
            text = parsed.cleanText.ifBlank { "Je suis là avec toi !" },
            emotion = parsed.emotion
        )

        updateActiveCharacter { ch ->
            ch.copy(
                currentEmotion = parsed.emotion,
                chatHistory = (ch.chatHistory + botMsg).takeLast(60),
                affinityXp = ch.affinityXp + 4
            )
        }

        _streamingReply.value = ""
        _isThinking.value = false

        if (speakReply) {
            val active = _state.value.activeCharacter
            voice.speak(
                text = botMsg.text,
                voicePack = active.voicePack,
                speed = active.voiceSpeed,
                pitch = active.voicePitch
            )
        }
    }

    private fun showNotice(msg: String) {
        noticeJob?.cancel()
        _autoLearnNotice.value = msg
        noticeJob = viewModelScope.launch {
            delay(4500L)
            _autoLearnNotice.value = null
        }
    }

    private suspend fun loadPhotoForActiveCharacter() {
        val path = _state.value.activeCharacter.photoPath
        if (path.isNullOrBlank()) {
            _analyzedPhoto.value = null
            return
        }
        _photoProcessing.value = true
        _analyzedPhoto.value = photoAnalyzer.analyzePhotoFile(path)
        _photoProcessing.value = false
    }

    private fun commit(next: AuraAppState) {
        _state.value = next
        repo.save(next)
    }
}
