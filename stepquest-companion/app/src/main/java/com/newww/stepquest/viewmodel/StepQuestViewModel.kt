package com.newww.stepquest.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newww.stepquest.ai.LlmStatus
import com.newww.stepquest.ai.NativeLlamaEngine
import com.newww.stepquest.data.StepQuestRepository
import com.newww.stepquest.engine.ExpeditionEngine
import com.newww.stepquest.model.*
import com.newww.stepquest.notifications.ExpeditionReminderWorker
import com.newww.stepquest.sensors.StepTracker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class StepQuestViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = StepQuestRepository(application)
    private val llm = NativeLlamaEngine(application)
    private val stepTracker = StepTracker(application) { delta ->
        recordSteps(delta)
    }

    private val _state = MutableStateFlow(ExpeditionEngine.ensureDayRollover(repo.load()))
    val state: StateFlow<StepQuestState> = _state.asStateFlow()

    val llmStatus: StateFlow<LlmStatus> = llm.status
    val llmPrepProgress: StateFlow<Float> = llm.prepProgress
    val llmStatusLabel: StateFlow<String> = llm.statusLabel

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _streamingDraft = MutableStateFlow("")
    val streamingDraft: StateFlow<String> = _streamingDraft.asStateFlow()

    val hasHardwarePedometer: Boolean get() = stepTracker.hasHardwareStepSensor

    init {
        ExpeditionReminderWorker.schedule(application)
        stepTracker.start()
        viewModelScope.launch {
            llm.ensureLoaded()
        }
    }

    fun onResume() {
        stepTracker.start()
        commit(ExpeditionEngine.ensureDayRollover(_state.value))
    }

    fun onPause() {
        repo.save(_state.value)
    }

    override fun onCleared() {
        stepTracker.stop()
        llm.release()
        super.onCleared()
    }

    fun recordSteps(delta: Int) {
        if (delta <= 0) return
        commit(ExpeditionEngine.advanceSteps(_state.value, delta))
    }

    fun toggleGear(gear: GearItem) {
        commit(ExpeditionEngine.togglePackGear(_state.value, gear))
    }

    fun confirmMorningPack() {
        commit(ExpeditionEngine.confirmMorningPack(_state.value))
    }

    fun feedProvision(item: ProvisionItem) {
        commit(ExpeditionEngine.feedProvision(_state.value, item))
    }

    fun restAtCamp() {
        commit(ExpeditionEngine.restAtCamp(_state.value))
    }

    fun customizeAdventurer(cls: AdventurerClass, name: String, cloakHex: Long) {
        commit(ExpeditionEngine.selectAdventurer(_state.value, cls, name, cloakHex))
    }

    fun setRemindersEnabled(enabled: Boolean) {
        commit(_state.value.copy(remindersEnabled = enabled))
    }

    fun generateEveningLetter() {
        if (_isGenerating.value) return
        val snapshot = _state.value
        _isGenerating.value = true
        _streamingDraft.value = ""

        viewModelScope.launch {
            val prompt = ExpeditionEngine.buildLetterPrompt(snapshot)
            val buffer = StringBuilder()
            val generated = llm.generate(prompt, maxTokens = 260, temperature = 0.78f) { piece ->
                buffer.append(piece)
                _streamingDraft.value = buffer.toString()
            }

            val finalBody = generated.ifBlank { ExpeditionEngine.buildFallbackLetter(snapshot) }
            val biome = ExpeditionEngine.biomeForSteps(snapshot.totalSteps)
            val now = System.currentTimeMillis()
            val today = ExpeditionEngine.dayKey(now)
            val letter = ExpeditionLetter(
                id = UUID.randomUUID().toString(),
                dayKey = today,
                timestampMs = now,
                biomeName = biome.nameFr,
                stepsCount = snapshot.todaySteps,
                title = "Lettre depuis ${biome.nameFr}",
                body = finalBody,
                generatedByLlm = generated.isNotBlank()
            )
            commit(
                _state.value.copy(
                    eveningLetterDayKey = today,
                    letters = (listOf(letter) + _state.value.letters).take(30),
                    xp = _state.value.xp + 30
                )
            )
            _streamingDraft.value = ""
            _isGenerating.value = false
        }
    }

    fun sendCampMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || _isGenerating.value) return

        val userMsg = CampChatMessage(UUID.randomUUID().toString(), fromUser = true, text = text)
        val updated = _state.value.copy(campChat = (_state.value.campChat + userMsg).takeLast(40))
        commit(updated)

        _isGenerating.value = true
        _streamingDraft.value = ""

        viewModelScope.launch {
            val prompt = ExpeditionEngine.buildChatPrompt(updated, text)
            val buffer = StringBuilder()
            val generated = llm.generate(prompt, maxTokens = 160, temperature = 0.72f) { piece ->
                buffer.append(piece)
                _streamingDraft.value = buffer.toString()
            }

            val biome = ExpeditionEngine.biomeForSteps(updated.totalSteps)
            val replyText = generated.ifBlank {
                "Merci pour ton message ! Ici près de ${ExpeditionEngine.currentLandmark(updated.totalSteps)} (${biome.nameFr}), tes ${updated.todaySteps} pas m'ont déjà fait parcourir un beau chemin. On continue ensemble !"
            }
            val botMsg = CampChatMessage(UUID.randomUUID().toString(), fromUser = false, text = replyText)
            commit(_state.value.copy(campChat = (_state.value.campChat + botMsg).takeLast(40)))
            _streamingDraft.value = ""
            _isGenerating.value = false
        }
    }

    fun stopGeneration() {
        llm.stopGeneration()
    }

    private fun commit(next: StepQuestState) {
        _state.value = next
        repo.save(next)
    }
}
