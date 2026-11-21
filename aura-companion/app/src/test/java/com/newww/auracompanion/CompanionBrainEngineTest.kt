package com.newww.auracompanion

import com.newww.auracompanion.engine.CompanionBrainEngine
import com.newww.auracompanion.model.*
import org.junit.Assert.*
import org.junit.Test

class CompanionBrainEngineTest {

    @Test
    fun parsesExplicitEmotionTagAndStripsAsterisks() {
        val parsed = CompanionBrainEngine.parseReply("[LOVE] *sourit* Je suis tellement heureuse de te parler !")
        assertEquals(AvatarEmotion.LOVE, parsed.emotion)
        assertEquals("sourit Je suis tellement heureuse de te parler !", parsed.cleanText)
    }

    @Test
    fun infersShyEmotionWithoutTag() {
        val parsed = CompanionBrainEngine.parseReply("Oh… tu me fais rougir, merci beaucoup !")
        assertEquals(AvatarEmotion.SHY, parsed.emotion)
    }

    @Test
    fun extractsRelevantPersonalMemoryFacts() {
        assertNotNull(CompanionBrainEngine.extractMemoryFactIfRelevant("J'adore regarder les étoiles le soir."))
        assertNull(CompanionBrainEngine.extractMemoryFactIfRelevant("Ok salut"))
    }

    @Test
    fun promptIncludesCustomTraitsAndMemories() {
        val ch = AuraAppState.defaultCharacters().first().copy(
            name = "Luna",
            userNickname = "mon trésor",
            memories = listOf("Aime le chocolat chaud"),
            customRules = "Parle toujours avec une touche poétique."
        )
        val prompt = CompanionBrainEngine.buildPrompt(ch, "Comment vas-tu ?")
        assertTrue(prompt.contains("Luna"))
        assertTrue(prompt.contains("mon trésor"))
        assertTrue(prompt.contains("Aime le chocolat chaud"))
        assertTrue(prompt.contains("Parle toujours avec une touche poétique."))
    }

    @Test
    fun defaultCharactersIncludeFiveDistinctPersonalities() {
        val chars = AuraAppState.defaultCharacters()
        assertEquals(5, chars.size)
        assertEquals(5, chars.map { it.id }.toSet().size)
    }
}
