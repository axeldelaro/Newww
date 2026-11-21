package com.newww.luminaterrarium.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newww.luminaterrarium.audio.AmbientSoundEngine
import com.newww.luminaterrarium.data.TerrariumRepository
import com.newww.luminaterrarium.engine.TerrariumSimulationEngine
import com.newww.luminaterrarium.model.*
import com.newww.luminaterrarium.notifications.CareReminderWorker
import com.newww.luminaterrarium.sensors.DeviceSensorsManager
import com.newww.luminaterrarium.widget.TerrariumWidgetProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class VisualPulseEvent(
    val id: Long = System.nanoTime(),
    val kind: PulseKind,
    val food: FoodType? = null,
    val xNorm: Float = 0.5f,
    val yNorm: Float = 0.5f
)

enum class PulseKind { PET, TAP, FEED, MIST, EVOLVE, LULLABY, ORB_CAUGHT }

class TerrariumViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TerrariumRepository(application)
    private val audioEngine = AmbientSoundEngine()
    private val sensors = DeviceSensorsManager(application) { delta ->
        onStepsRecorded(delta)
    }

    private val _state = MutableStateFlow(repo.load())
    val state: StateFlow<TerrariumAppState> = _state.asStateFlow()

    val battery: StateFlow<BatterySnapshot> = sensors.battery
    val tilt: StateFlow<TiltSnapshot> = sensors.tilt

    private val _dayPhase = MutableStateFlow(DayPhase.fromHour(TerrariumSimulationEngine.currentHour()))
    val dayPhase: StateFlow<DayPhase> = _dayPhase.asStateFlow()

    private val _visualPulse = MutableStateFlow<VisualPulseEvent?>(null)
    val visualPulse: StateFlow<VisualPulseEvent?> = _visualPulse.asStateFlow()

    private val _miniGameActive = MutableStateFlow(false)
    val miniGameActive: StateFlow<Boolean> = _miniGameActive.asStateFlow()

    private val _miniGameScore = MutableStateFlow(0)
    val miniGameScore: StateFlow<Int> = _miniGameScore.asStateFlow()

    private val _miniGameSecondsLeft = MutableStateFlow(20)
    val miniGameSecondsLeft: StateFlow<Int> = _miniGameSecondsLeft.asStateFlow()

    init {
        CareReminderWorker.schedule(application)
        sensors.start()
        applyTickAndPersist()
        audioEngine.setSoundscape(_state.value.soundscape)

        viewModelScope.launch {
            while (true) {
                delay(30_000L)
                _dayPhase.value = DayPhase.fromHour(TerrariumSimulationEngine.currentHour())
                applyTickAndPersist()
            }
        }
    }

    fun onResume() {
        sensors.start()
        _dayPhase.value = DayPhase.fromHour(TerrariumSimulationEngine.currentHour())
        applyTickAndPersist()
        audioEngine.setSoundscape(_state.value.soundscape)
    }

    fun onPause() {
        audioEngine.stopAmbient()
        repo.save(_state.value)
        TerrariumWidgetProvider.refreshWidgets(getApplication())
    }

    override fun onCleared() {
        sensors.stop()
        audioEngine.release()
        super.onCleared()
    }

    fun petSpirit(xNorm: Float = 0.5f, yNorm: Float = 0.55f) {
        val prevStage = _state.value.activeSpirit.stage
        val next = TerrariumSimulationEngine.petSpirit(_state.value, battery.value)
        commit(next)
        val evolved = next.activeSpirit.stage.level > prevStage.level
        audioEngine.playSfx(
            if (evolved) AmbientSoundEngine.SfxKind.EVOLVE else AmbientSoundEngine.SfxKind.PET,
            next.sfxEnabled
        )
        _visualPulse.value = VisualPulseEvent(
            kind = if (evolved) PulseKind.EVOLVE else PulseKind.PET,
            xNorm = xNorm,
            yNorm = yNorm
        )
    }

    fun tapGlass(xNorm: Float, yNorm: Float) {
        val next = TerrariumSimulationEngine.tapGlass(_state.value, battery.value)
        commit(next)
        audioEngine.playSfx(AmbientSoundEngine.SfxKind.GLASS_TAP, next.sfxEnabled)
        _visualPulse.value = VisualPulseEvent(kind = PulseKind.TAP, xNorm = xNorm, yNorm = yNorm)
    }

    fun feedSpirit(food: FoodType) {
        val prevStage = _state.value.activeSpirit.stage
        val next = TerrariumSimulationEngine.feedSpirit(_state.value, food)
        commit(next)
        val evolved = next.activeSpirit.stage.level > prevStage.level
        audioEngine.playSfx(
            if (evolved) AmbientSoundEngine.SfxKind.EVOLVE else AmbientSoundEngine.SfxKind.FEED,
            next.sfxEnabled
        )
        _visualPulse.value = VisualPulseEvent(
            kind = if (evolved) PulseKind.EVOLVE else PulseKind.FEED,
            food = food
        )
    }

    fun mistTerrarium() {
        val prevStage = _state.value.activeSpirit.stage
        val next = TerrariumSimulationEngine.mistTerrarium(_state.value)
        commit(next)
        val evolved = next.activeSpirit.stage.level > prevStage.level
        audioEngine.playSfx(
            if (evolved) AmbientSoundEngine.SfxKind.EVOLVE else AmbientSoundEngine.SfxKind.MIST,
            next.sfxEnabled
        )
        _visualPulse.value = VisualPulseEvent(kind = if (evolved) PulseKind.EVOLVE else PulseKind.MIST)
    }

    fun setLightLevel(level: Float) {
        commit(TerrariumSimulationEngine.setLightLevel(_state.value, level))
    }

    fun toggleLullaby() {
        val next = TerrariumSimulationEngine.toggleLullabySleep(_state.value)
        commit(next)
        audioEngine.playSfx(AmbientSoundEngine.SfxKind.LULLABY, next.sfxEnabled)
        _visualPulse.value = VisualPulseEvent(kind = PulseKind.LULLABY)
    }

    fun startMiniGame() {
        if (_miniGameActive.value) return
        _miniGameScore.value = 0
        _miniGameSecondsLeft.value = 20
        _miniGameActive.value = true
        viewModelScope.launch {
            for (sec in 20 downTo 1) {
                if (!_miniGameActive.value) return@launch
                _miniGameSecondsLeft.value = sec
                delay(1000L)
            }
            finishMiniGame()
        }
    }

    fun onOrbCaught(xNorm: Float, yNorm: Float) {
        if (!_miniGameActive.value) return
        _miniGameScore.value += 1
        audioEngine.playSfx(AmbientSoundEngine.SfxKind.CATCH_ORB, _state.value.sfxEnabled)
        _visualPulse.value = VisualPulseEvent(kind = PulseKind.ORB_CAUGHT, xNorm = xNorm, yNorm = yNorm)
    }

    fun finishMiniGame() {
        if (!_miniGameActive.value) return
        _miniGameActive.value = false
        val score = _miniGameScore.value
        val next = TerrariumSimulationEngine.completeMiniGame(_state.value, score)
        commit(next)
    }

    fun onStepsRecorded(delta: Int) {
        if (delta <= 0) return
        commit(TerrariumSimulationEngine.recordStepsDelta(_state.value, delta))
    }

    fun unlockOrToggleDecor(decor: DecorItem) {
        commit(TerrariumSimulationEngine.unlockOrToggleDecor(_state.value, decor))
    }

    fun unlockOrSelectJar(jarStyle: JarStyle) {
        commit(TerrariumSimulationEngine.unlockOrSelectJar(_state.value, jarStyle))
    }

    fun switchElement(element: SpiritElement) {
        commit(TerrariumSimulationEngine.switchElement(_state.value, element))
    }

    fun customizeSpirit(name: String, auraHex: Long) {
        commit(TerrariumSimulationEngine.customizeSpirit(_state.value, name, auraHex))
    }

    fun setSoundscape(soundscape: AmbientSoundscape) {
        commit(_state.value.copy(soundscape = soundscape))
        audioEngine.setSoundscape(soundscape)
    }

    fun setSfxEnabled(enabled: Boolean) {
        commit(_state.value.copy(sfxEnabled = enabled))
    }

    fun setRemindersEnabled(enabled: Boolean) {
        commit(_state.value.copy(remindersEnabled = enabled))
    }

    fun claimDailyQuest(questId: String, rewardDew: Int) {
        commit(TerrariumSimulationEngine.claimDailyQuest(_state.value, questId, rewardDew))
    }

    private fun applyTickAndPersist() {
        val ticked = TerrariumSimulationEngine.applyTimeTick(_state.value, battery.value)
        commit(ticked)
    }

    private fun commit(next: TerrariumAppState) {
        _state.value = next
        repo.save(next)
        TerrariumWidgetProvider.refreshWidgets(getApplication())
    }
}
