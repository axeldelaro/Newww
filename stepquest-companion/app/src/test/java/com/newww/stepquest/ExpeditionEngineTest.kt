package com.newww.stepquest

import com.newww.stepquest.engine.ExpeditionEngine
import com.newww.stepquest.model.*
import org.junit.Assert.*
import org.junit.Test

class ExpeditionEngineTest {

    @Test
    fun stepsAdvanceBiomeRecordWeeklyAndTriggerEncounter() {
        val now = 50_000L
        val start = StepQuestState(
            totalSteps = 2_800,
            todaySteps = 200,
            todayDayKey = ExpeditionEngine.dayKey(now),
            coins = 10,
            lastEncounterStepMark = 2_600
        )
        val after = ExpeditionEngine.advanceSteps(start, 600, nowMs = now)
        assertEquals(3_400, after.totalSteps)
        assertEquals("Forêt des Lanternes Suspendues", ExpeditionEngine.biomeForSteps(after.totalSteps).nameFr)
        assertTrue(after.weeklySteps.values.any { it == 800 })
        assertNotNull(after.activeEncounter)
    }

    @Test
    fun craftingConsumesIngredientsAndRewardsHero() {
        val start = StepQuestState(
            health = 50f,
            energy = 40f,
            ingredients = mapOf(IngredientType.HERB to 3, IngredientType.HONEY to 2)
        )
        val after = ExpeditionEngine.craftRecipe(start, CraftRecipe.VITAL_ELIXIR, nowMs = 90_000L)
        assertEquals(1, after.ingredients[IngredientType.HERB])
        assertEquals(1, after.ingredients[IngredientType.HONEY])
        assertTrue(after.health >= 95f)
        assertTrue(after.energy >= 85f)
    }

    @Test
    fun chatPromptUsesMultiTurnAndDirectUserQuestion() {
        val state = StepQuestState(adventurerName = "Élio", todaySteps = 3200)
        val prompt = ExpeditionEngine.buildChatPrompt(state, "Combien de pas avons-nous fait ?")
        assertTrue(prompt.contains("<start_of_turn>user"))
        assertTrue(prompt.contains("3200"))
        assertTrue(prompt.contains("<start_of_turn>user\nCombien de pas avons-nous fait ?<end_of_turn>"))
    }
}
