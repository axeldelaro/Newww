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
            spirits = mapOf(SpiritElement.MOSS to SpiritProfile(SpiritElement.MOSS, energy = 40f)),
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
    fun stepsConvertToDewAndRecordWeeklyStats() {
        val base = TerrariumAppState(dewCurrency = 10, totalStepsConverted = 0)
        val after = TerrariumSimulationEngine.recordStepsDelta(base, stepsDelta = 250, nowMs = 10_000L)
        assertEquals(20, after.dewCurrency)
        assertEquals(250, after.totalStepsConverted)
        assertEquals(250, after.dailyQuest.stepsToday)
        assertTrue(after.weeklySteps.values.any { it == 250 })
    }

    @Test
    fun wipingGlassFogClearsCondensationAndAwardsXp() {
        val foggy = SpiritProfile(SpiritElement.MOSS, glassFog = 0.6f, xp = 10)
        val base = TerrariumAppState(spirits = mapOf(SpiritElement.MOSS to foggy))
        val after = TerrariumSimulationEngine.wipeGlassFog(base, nowMs = 5_000L)
        assertTrue(after.activeSpirit.glassFog < 0.3f)
        assertTrue(after.activeSpirit.xp > 10)
    }

    @Test
    fun movingDecorClampsAndPersistsNormalizedPosition() {
        val base = TerrariumAppState()
        val after = TerrariumSimulationEngine.setDecorPosition(base, DecorItem.GLOW_MUSHROOMS.id, 0.99f, 0.01f)
        val pos = after.activeSpirit.decorPositions[DecorItem.GLOW_MUSHROOMS.id]
        assertNotNull(pos)
        assertEquals(0.82f, pos!!.x, 0.001f)
        assertEquals(0.24f, pos.y, 0.001f)
    }
}
