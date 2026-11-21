package com.newww.stepquest

import com.newww.stepquest.engine.ExpeditionEngine
import com.newww.stepquest.model.*
import org.junit.Assert.*
import org.junit.Test

class ExpeditionEngineTest {

    @Test
    fun stepsAdvanceBiomeAndAwardXpAndCoins() {
        val start = StepQuestState(totalSteps = 2_800, todaySteps = 200, coins = 10)
        val after = ExpeditionEngine.advanceSteps(start, 600, nowMs = 50_000L)
        assertEquals(3_400, after.totalSteps)
        assertEquals("Forêt des Lanternes Suspendues", ExpeditionEngine.biomeForSteps(after.totalSteps).nameFr)
        assertTrue(after.coins > 10)
        assertTrue(after.xp > 0)
    }

    @Test
    fun morningPackCapsAtThreeGearItems() {
        var s = StepQuestState(packedGearIds = emptyList())
        s = ExpeditionEngine.togglePackGear(s, GearItem.RUNIC_LANTERN)
        s = ExpeditionEngine.togglePackGear(s, GearItem.COPPER_COMPASS)
        s = ExpeditionEngine.togglePackGear(s, GearItem.RAIN_CLOAK)
        s = ExpeditionEngine.togglePackGear(s, GearItem.MAGIC_FLASK)
        assertEquals(3, s.packedGearIds.size)
        assertTrue(GearItem.MAGIC_FLASK.id in s.packedGearIds)
        assertFalse(GearItem.RUNIC_LANTERN.id in s.packedGearIds)
    }

    @Test
    fun feedingProvisionRestoresEnergyAndDeductsCoins() {
        val start = StepQuestState(coins = 30, energy = 40f, morale = 50f)
        val after = ExpeditionEngine.feedProvision(start, ProvisionItem.FOREST_SOUP, nowMs = 80_000L)
        assertEquals(18, after.coins)
        assertTrue(after.energy > 60f)
        assertTrue(after.morale > 60f)
        assertTrue(after.mealsFedToday.contains(ProvisionItem.FOREST_SOUP.labelFr))
    }

    @Test
    fun letterPromptContainsRealActivityContext() {
        val state = StepQuestState(
            adventurerName = "Lyra",
            adventurerClass = AdventurerClass.RANGER,
            todaySteps = 4_200,
            totalSteps = 4_200,
            packedGearIds = listOf(GearItem.RUNIC_LANTERN.id)
        )
        val prompt = ExpeditionEngine.buildLetterPrompt(state)
        assertTrue(prompt.contains("Lyra"))
        assertTrue(prompt.contains("4200"))
        assertTrue(prompt.contains("Forêt des Lanternes Suspendues"))
        assertTrue(prompt.contains("Lanterne Runique"))
    }
}
