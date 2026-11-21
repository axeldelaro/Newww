package com.newww.auracompanion.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newww.auracompanion.ai.AuraLlamaEngine
import com.newww.auracompanion.ai.EngineInitState
import com.newww.auracompanion.ai.OfflineVoicePipeline
import com.newww.auracompanion.data.AuraRepository
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

    private var callJob: Job? = null

    init {
        AuraReminderWorker.schedule(application)
        viewModelScope.launch {
            launch { llm.ensureLoaded() }
            launch { voice.initialize() }
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
            backstory = "$cleanName a été imaginée sur mesure pour t'accompagner chaque jour hors-ligne.",
            interests = "Discussions complices, musique, cinéma, moments cosy",
            studio = StudioAppearance(artStyle = artStyle),
            chatHistory = listOf(
                AuraChatMessage(
                    id = "${id}_hi",
                    fromUser = false,
                    text = "Bonjour ! Je suis $cleanName. Tu peux personnaliser mon caractère, ma voix, mon style ou même me donner une photo à animer !",
                    emotion = AvatarEmotion.HAPPY
                )
            )
        )
        commit(
            _state.value.copy(
                activeCharacterId = id,
                characters = _state.value.characters + newChar
            )
        )
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
        val updatedList = _state.value.characters.map { ch ->
            if (ch.id == activeId) transform(ch) else ch
        }
        commit(_state.value.copy(characters = updatedList))
    }

    fun importPhotoForActiveCharacter(uri: Uri) {
        val charId = _state.value.activeCharacter.id
        _photoProcessing.value = true
        viewModelScope.launch {
            val savedPath = photoAnalyzer.importAndSavePhoto(uri, charId)
            if (savedPath != null) {
                updateActiveCharacter {
                    it.copy(
                        avatarMode = AvatarSourceMode.PHOTO_AI,
                        photoPath = savedPath
                    )
                }
                val analyzed = photoAnalyzer.analyzePhotoFile(savedPath)
                _analyzedPhoto.value = analyzed
            }
            _photoProcessing.value = false
        }
    }

    fun setAvatarMode(mode: AvatarSourceMode) {
        updateActiveCharacter { it.copy(avatarMode = mode) }
        if (mode == AvatarSourceMode.PHOTO_AI) {
            viewModelScope.launch { loadPhotoForActiveCharacter() }
        }
    }

    fun setThemePreset(preset: AppThemePreset) {
        commit(_state.value.copy(themePreset = preset))
    }

    fun setAutoSpeak(enabled: Boolean) {
        commit(_state.value.copy(autoSpeakReplies = enabled))
    }

    fun setContinuousCall(enabled: Boolean) {
        commit(_state.value.copy(continuousCallMode = enabled))
    }

    fun setRemindersEnabled(enabled: Boolean) {
        commit(_state.value.copy(remindersEnabled = enabled))
    }

    fun addMemory(fact: String) {
        val clean = fact.trim()
        if (clean.isEmpty()) return
        updateActiveCharacter { ch ->
            ch.copy(memories = (ch.memories + clean).distinct().takeLast(20))
        }
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
                text = "Coucou ${ch.userNickname}, c'est ${ch.name} ! Ma voix tourne à cent pour cent hors ligne sur ton téléphone.",
                voicePack = ch.voicePack,
                speed = ch.voiceSpeed,
                pitch = ch.voicePitch
            )
        }
    }

    fun sendUserMessage(rawText: String, speakReply: Boolean = _state.value.autoSpeakReplies) {
        val text = rawText.trim()
        if (text.isEmpty() || _isThinking.value) return
        viewModelScope.launch {
            processUserTurn(text, speakReply)
        }
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
        val chBefore = _state.value.activeCharacter
        val newMem = CompanionBrainEngine.extractMemoryFactIfRelevant(userText)
        val userMsg = AuraChatMessage(UUID.randomUUID().toString(), fromUser = true, text = userText)

        updateActiveCharacter { ch ->
            ch.copy(
                chatHistory = (ch.chatHistory + userMsg).takeLast(50),
                memories = if (newMem != null) (ch.memories + newMem).distinct().takeLast(20) else ch.memories,
                affinityXp = ch.affinityXp + 5
            )
        }

        _isThinking.value = true
        _streamingReply.value = ""

        val chSnapshot = _state.value.activeCharacter
        val prompt = CompanionBrainEngine.buildPrompt(chSnapshot, userText)
        val buf = StringBuilder()
        val rawReply = llm.generate(
            prompt = prompt,
            maxTokens = chSnapshot.responseLength.maxTokens,
            temperature = 0.78f
        ) { piece ->
            buf.append(piece)
            _streamingReply.value = CompanionBrainEngine.parseReply(buf.toString()).cleanText
        }

        val parsed = if (rawReply.isNotBlank()) {
            CompanionBrainEngine.parseReply(rawReply)
        } else {
            CompanionBrainEngine.buildFallbackReply(chBefore, userText)
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
                chatHistory = (ch.chatHistory + botMsg).takeLast(50),
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
