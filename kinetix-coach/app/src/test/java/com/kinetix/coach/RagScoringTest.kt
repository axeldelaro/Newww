package com.kinetix.coach

import com.kinetix.coach.data.AthleteProfile
import com.kinetix.coach.data.KnowledgeEntry
import com.kinetix.coach.data.RagHit
import com.kinetix.coach.rag.RagQueryProcessor
import com.kinetix.coach.ui.tracker.AthleticCalculators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagScoringTest {

    private val sampleSynonyms = mapOf(
        "mal" to listOf("douleur", "blessure", "tendinopathie"),
        "epaule" to listOf("coiffe", "deltoide", "acromial"),
        "genou" to listOf("rotulien", "valgus", "squat"),
        "creatine" to listOf("monohydrate", "atp", "force")
    )

    @Test
    fun normalize_stripsFrenchAccentsAndPunctuation() {
        assertEquals(
            "developpe incline halteres epaule",
            RagQueryProcessor.normalize("Développé Incliné — Haltères & Épaule !")
        )
    }

    @Test
    fun extractTokens_filtersFrenchStopwords() {
        val tokens = RagQueryProcessor.extractTokens("Comment faire le squat avec mal aux genoux ?")
        assertTrue(tokens.contains("squat"))
        assertTrue(tokens.contains("mal"))
        assertTrue(tokens.contains("genoux"))
        assertFalse(tokens.contains("comment"))
        assertFalse(tokens.contains("le"))
        assertFalse(tokens.contains("avec"))
    }

    @Test
    fun buildFtsMatchExpression_expandsSynonymsAndHandlesPlurals() {
        val expr = RagQueryProcessor.buildFtsMatchExpression(
            query = "mal epaules",
            synonyms = sampleSynonyms,
            strictAnd = true
        )
        assertTrue("Expected AND join in strict expr: $expr", expr.contains(" AND "))
        assertTrue("Expected synonym expansion for mal: $expr", expr.contains("douleur*"))
        assertTrue("Expected singularized synonym expansion for epaules: $expr", expr.contains("coiffe*"))
    }

    @Test
    fun detectPreferredCategory_classifiesIntentAccurately() {
        assertEquals(
            "blessures",
            RagQueryProcessor.detectPreferredCategory(listOf("mal", "epaule", "tendinite"))
        )
        assertEquals(
            "nutrition",
            RagQueryProcessor.detectPreferredCategory(listOf("creatine", "dosage", "proteines"))
        )
        assertEquals(
            "programme",
            RagQueryProcessor.detectPreferredCategory(listOf("programme", "upper", "lower", "4", "jours"))
        )
    }

    @Test
    fun rerankHits_prioritizesDirectTitleAndCategoryIntentMatch() {
        val genericEntry = KnowledgeEntry(
            id = 1L,
            slug = "developpe-couche",
            category = "exercice",
            subcategory = "Pectoraux",
            title = "Développé couché barre",
            summary = "Exercice pectoraux polyarticulaire.",
            body = "Attention à la position de l'épaule.",
            tags = "pectoraux epaule barre",
            metadataJson = "{}"
        )
        val injuryEntry = KnowledgeEntry(
            id = 2L,
            slug = "conflit-sous-acromial-epaule",
            category = "blessures",
            subcategory = "Épaule",
            title = "Conflit sous-acromial et douleur d'épaule",
            summary = "Protocole de rééducation de la coiffe des rotateurs.",
            body = "Réduisez l'amplitude douloureuse et renforcez les rotateurs externes.",
            tags = "epaule douleur coiffe conflit",
            metadataJson = "{}"
        )

        val ranked = RagQueryProcessor.rerankHits(
            query = "mal epaule douleur",
            candidates = listOf(
                RagHit(genericEntry, score = 8.0),
                RagHit(injuryEntry, score = 7.0)
            ),
            synonyms = sampleSynonyms,
            topK = 2
        )

        assertEquals(2L, ranked.first().entry.id)
    }

    @Test
    fun buildCoachPrompt_injectsProfileAndRagSources() {
        val profile = AthleteProfile(
            name = "Alex",
            age = 30,
            sex = "Homme",
            weightKg = 80f,
            heightCm = 180,
            goal = "Hypertrophie",
            level = "Intermédiaire",
            equipment = "Salle complète",
            injuries = "Tendinite épaule droite"
        )
        val hit = RagHit(
            entry = KnowledgeEntry(
                id = 10L,
                slug = "creatine-guide",
                category = "nutrition",
                subcategory = "Compléments",
                title = "Créatine monohydrate",
                summary = "3 à 5 g par jour.",
                body = "Prendre 3 à 5 g/jour de créatine monohydrate Creapure, phase de charge inutile.",
                tags = "creatine",
                metadataJson = "{\"type\":\"guide_science\"}"
            ),
            score = 12.0
        )

        val prompt = RagQueryProcessor.buildCoachSystemAndUserMessage(
            userQuestion = "Quel dosage de créatine ?",
            profile = profile,
            ragHits = listOf(hit),
            recentHistory = emptyList()
        )

        assertTrue(prompt.contains("Alex"))
        assertTrue(prompt.contains("Tendinite épaule droite"))
        assertTrue(prompt.contains("[S1 · NUTRITION] Créatine monohydrate"))
        assertTrue(prompt.contains("Quel dosage de créatine ?"))
    }

    @Test
    fun calculators_computeAccurateOneRmAndMifflinStJeorTdee() {
        val oneRm = AthleticCalculators.calculateOneRm(weightKg = 100f, reps = 5)
        // Epley: 100 * (1 + 5/30) = 116.7, Brzycki: 100 * 36/32 = 112.5, Avg = 114.6
        assertEquals(116.7f, oneRm.epleyKg, 0.2f)
        assertEquals(112.5f, oneRm.brzyckiKg, 0.2f)
        assertEquals(114.6f, oneRm.estimated1RmKg, 0.2f)

        val profile = AthleteProfile(
            age = 30,
            sex = "Homme",
            weightKg = 80f,
            heightCm = 180,
            activityFactor = 1.55f
        )
        // BMR = 10*80 + 6.25*180 - 5*30 + 5 = 800 + 1125 - 150 + 5 = 1780
        // TDEE = 1780 * 1.55 = 2759
        val tdee = AthleticCalculators.calculateTdee(profile)
        assertEquals(1780, tdee.bmrKcal)
        assertEquals(2759, tdee.tdeeKcal)
        assertTrue(tdee.cutPlan.kcal < tdee.maintenancePlan.kcal)
        assertTrue(tdee.bulkPlan.kcal > tdee.maintenancePlan.kcal)
    }
}
