package com.newww.auracompanion

import com.newww.auracompanion.engine.AutoProfileLearner
import com.newww.auracompanion.engine.CompanionBrainEngine
import com.newww.auracompanion.model.*
import org.junit.Assert.*
import org.junit.Test

class CompanionBrainEngineTest {

    @Test
    fun parsesEmotionTagAndMemoryTagCleanly() {
        val parsed = CompanionBrainEngine.parseReply("[LOVE] Super contente de savoir que tu vis à Lyon ! [MEM: Habite à Lyon]")
        assertEquals(AvatarEmotion.LOVE, parsed.emotion)
        assertEquals("Super contente de savoir que tu vis à Lyon !", parsed.cleanText)
        assertEquals("Habite à Lyon", parsed.memoryNote)
    }

    @Test
    fun autoProfileLearnerUpdatesSettingsDirectlyFromChat() {
        val base = AuraAppState.defaultCharacters().first().copy(
            userName = "Toi",
            userNickname = "mon cœur",
            traits = PersonalityTraits(humor = 0.50f),
            responseLength = ResponseLength.BALANCED
        )
        val res = AutoProfileLearner.learnFromUserMessage(
            base,
            "Je m'appelle Axel, appelle-moi chéri, sois plus drôle et fais des réponses plus courtes. J'habite à Lyon et j'adore la science-fiction.",
            enabled = true
        )
        assertEquals("Axel", res.updatedCharacter.userName)
        assertEquals("chéri", res.updatedCharacter.userNickname)
        assertTrue(res.updatedCharacter.traits.humor > 0.60f)
        assertEquals(ResponseLength.SHORT, res.updatedCharacter.responseLength)
        assertTrue(res.updatedCharacter.memories.any { it.contains("Lyon") })
        assertTrue(res.learnedBadges.size >= 4)
    }

    @Test
    fun llama32PromptUsesNativeHeaderTokensAndScenario() {
        val ch = AuraAppState.defaultCharacters().first().copy(
            userName = "Axel",
            scenario = RoleplayScenario.COZY_MOVIE,
            memories = listOf("[Lieu] J'habite à Lyon")
        )
        val prompt = CompanionBrainEngine.buildPrompt(ch, "Quel film on regarde ?")
        assertTrue(prompt.contains("<|begin_of_text|><|start_header_id|>system<|end_header_id|>"))
        assertTrue(prompt.contains("film"))
        assertTrue(prompt.contains("J'habite à Lyon"))
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>\n\nQuel film on regarde ?<|eot_id|>"))
    }

    @Test
    fun defaultCharactersIncludeEightRichPersonalities() {
        val chars = AuraAppState.defaultCharacters()
        assertEquals(8, chars.size)
        assertEquals(8, chars.map { it.id }.toSet().size)
    }
}
