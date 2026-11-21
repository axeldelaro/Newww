package com.newww.luminaterrarium

import com.newww.luminaterrarium.engine.TerrariumSimulationEngine
import com.newww.luminaterrarium.model.*
import org.junit.Assert.*
import org.junit.Test

class TerrariumSimulationEngineTest {

    @Test
    fun lowBatteryTriggersSleepyLowBatteryMood() {
        val spirit = SpiritProfile(SpiritElement.MOSS)
        val mood = TerrariumSimulationEngine.computeMood(
            spirit,
            BatterySnapshot(levelPercent = 15, isCharging = false),
            DayPhase.AFTERNOON
        )
        assertEquals(SpiritMood.LOW_BATTERY, mood)
    }

    @Test
    fun chargingRestoresEnergyOverTime() {
        val base = TerrariumAppState(
            spirits = mapOf(
                SpiritElement.MOSS to SpiritProfile(SpiritElement.MOSS, energy = 40f)
            ),
            lastTickTimestampMs = 1_000_000L
        )
        val later = TerrariumSimulationEngine.applyTimeTick(
            state = base,
            battery = BatterySnapshot(levelPercent = 55, isCharging = true),
            nowMs = 1_000_000L + 3_600_000L
        )
        assertTrue(later.activeSpirit.energy > 45f)
    }

    @Test
    fun stepsConvertToDewAndXp() {
        val base = TerrariumAppState(dewCurrency = 10, totalStepsConverted = 0)
        val after = TerrariumSimulationEngine.recordStepsDelta(base, stepsDelta = 250, nowMs = 10_000L)
        assertEquals(20, after.dewCurrency)
        assertEquals(250, after.totalStepsConverted)
        assertTrue(after.activeSpirit.xp > 0)
    }

    @Test
    fun feedingFavoriteGivesBonusXpAndEvolution() {
        val nearEvo = SpiritProfile(SpiritElement.MOSS, xp = 95, hunger = 50f)
        val base = TerrariumAppState(
            activeElement = SpiritElement.MOSS,
            spirits = mapOf(SpiritElement.MOSS to nearEvo)
        )
        val after = TerrariumSimulationEngine.feedSpirit(base, FoodType.LUMINA_BERRY, nowMs = 50_000L)
        assertEquals(EvolutionStage.SPROUT, after.activeSpirit.stage)
        assertTrue(after.activeSpirit.hunger > 85f)
    }

    @Test
    fun decorUnlockConsumesDewAndPlacesItem() {
        val base = TerrariumAppState(dewCurrency = 50)
        val after = TerrariumSimulationEngine.unlockOrToggleDecor(base, DecorItem.GLOW_MUSHROOMS, nowMs = 5_000L)
        assertEquals(30, after.dewCurrency)
        assertTrue(DecorItem.GLOW_MUSHROOMS.id in after.unlockedDecorIds)
        assertTrue(DecorItem.GLOW_MUSHROOMS.id in after.activeSpirit.activeDecorIds)
    }
}
