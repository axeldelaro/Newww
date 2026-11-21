package com.kinetix.coach.rag

import com.kinetix.coach.data.AthleteProfile
import com.kinetix.coach.data.KnowledgeEntry
import com.kinetix.coach.data.RagHit
import java.text.Normalizer

object RagQueryProcessor {

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9\\s]+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val FRENCH_STOPWORDS = setOf(
        "le", "la", "les", "de", "du", "des", "un", "une", "et", "ou", "a", "au", "aux",
        "en", "pour", "par", "sur", "avec", "sans", "dans", "je", "tu", "il", "elle", "on",
        "nous", "vous", "ils", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses",
        "que", "qui", "quoi", "comment", "quel", "quelle", "quels", "quelles", "est", "sont",
        "suis", "fait", "faire", "avoir", "etre", "plus", "moins", "tres", "bien", "peux",
        "peut", "faut", "dois", "moi", "toi", "lui", "leur", "ce", "cet", "cette", "ces",
        "mais", "donc", "car", "ni", "ne", "pas", "si", "tout", "tous", "toute", "toutes",
        "jai", "cest", "quest", "quil", "dune", "dun"
    )

    private val INJURY_INTENT_HINTS = setOf(
        "mal", "douleur", "douleurs", "blessure", "tendinite", "tendinopathie", "entorse",
        "claquage", "dechirure", "hernie", "sciatique", "lumbago", "lombalgie", "periostite",
        "fasciite", "conflit", "pincement", "pubalgie", "bursite", "reeducation", "prehab"
    )

    private val NUTRITION_INTENT_HINTS = setOf(
        "proteine", "proteines", "calorie", "calories", "kcal", "macro", "macros", "glucide",
        "glucides", "lipide", "lipides", "creatine", "whey", "caseine", "cafeine", "omega",
        "vitamine", "magnesium", "fer", "collagene", "poulet", "saumon", "oeuf", "oeufs",
        "riz", "avoine", "skyr", "manger", "repas", "aliment", "aliments", "nutrition",
        "seche", "maigrir", "regime", "hydratation", "electrolytes", "dosage", "complement"
    )

    private val PROGRAM_INTENT_HINTS = setOf(
        "programme", "plan", "split", "ppl", "upper", "lower", "full", "semaine", "jours",
        "seances", "cycle", "periodisation", "deload", "marathon", "semi", "10km", "5km",
        "hyrox", "5x5", "wendler", "gzcl", "calisthenie"
    )

    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val noAccents = DIACRITICS_REGEX.replace(decomposed, "")
        val alnum = NON_ALNUM_REGEX.replace(noAccents.lowercase(), " ")
        return WHITESPACE_REGEX.replace(alnum, " ").trim()
    }

    fun extractTokens(query: String): List<String> {
        val norm = normalize(query)
        if (norm.isBlank()) return emptyList()
        return norm.split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in FRENCH_STOPWORDS }
            .distinct()
    }

    fun expandTokens(tokens: List<String>, synonyms: Map<String, List<String>>): Map<String, List<String>> {
        val expanded = linkedMapOf<String, List<String>>()
        for (tok in tokens) {
            val alts = linkedSetOf(tok)
            // Exact term lookup
            synonyms[tok]?.forEach { exp ->
                val clean = normalize(exp)
                if (clean.length >= 2 && clean !in FRENCH_STOPWORDS) alts.add(clean)
            }
            // Simple French plural singularization (e.g. "genoux" -> "genou", "epaules" -> "epaule")
            if (tok.endsWith("s") && tok.length > 3) {
                val singular = tok.dropLast(1)
                synonyms[singular]?.forEach { exp ->
                    val clean = normalize(exp)
                    if (clean.length >= 2 && clean !in FRENCH_STOPWORDS) alts.add(clean)
                }
            }
            expanded[tok] = alts.take(5)
        }
        return expanded
    }

    fun buildFtsMatchExpression(
        query: String,
        synonyms: Map<String, List<String>>,
        strictAnd: Boolean = false
    ): String {
        val tokens = extractTokens(query)
        if (tokens.isEmpty()) return ""
        val expanded = expandTokens(tokens, synonyms)
        val clauses = expanded.values.mapNotNull { group ->
            val safeTerms = group
                .map { it.replace(Regex("[^a-z0-9]"), "") }
                .filter { it.length >= 2 }
                .distinct()
            if (safeTerms.isEmpty()) null
            else "(" + safeTerms.joinToString(" OR ") { "$it*" } + ")"
        }
        if (clauses.isEmpty()) return ""
        val useAnd = strictAnd || clauses.size <= 2
        return clauses.joinToString(if (useAnd) " AND " else " OR ")
    }

    fun detectPreferredCategory(tokens: List<String>): String? {
        val tokSet = tokens.toSet()
        val injuryHits = tokSet.count { it in INJURY_INTENT_HINTS }
        val nutritionHits = tokSet.count { it in NUTRITION_INTENT_HINTS }
        val programHits = tokSet.count { it in PROGRAM_INTENT_HINTS }
        return when {
            injuryHits > 0 && injuryHits >= nutritionHits && injuryHits >= programHits -> "blessures"
            nutritionHits > 0 && nutritionHits >= programHits -> "nutrition"
            programHits > 0 -> "programme"
            else -> null
        }
    }

    fun rerankHits(
        query: String,
        candidates: List<RagHit>,
        synonyms: Map<String, List<String>>,
        topK: Int
    ): List<RagHit> {
        if (candidates.isEmpty()) return emptyList()
        val tokens = extractTokens(query)
        val expanded = expandTokens(tokens, synonyms)
        val allExpanded = expanded.values.flatten().toSet()
        val preferredCategory = detectPreferredCategory(tokens)

        return candidates
            .distinctBy { it.entry.id }
            .map { hit ->
                val titleNorm = normalize(hit.entry.title)
                val tagsNorm = normalize(hit.entry.tags)
                val subNorm = normalize(hit.entry.subcategory)

                var boost = 0.0
                var directMatches = 0
                for (tok in tokens) {
                    if (titleNorm.contains(tok)) {
                        boost += 6.0
                        directMatches++
                    } else if (subNorm.contains(tok) || tagsNorm.contains(tok)) {
                        boost += 2.5
                        directMatches++
                    }
                }
                for (exp in allExpanded) {
                    if (exp !in tokens && titleNorm.contains(exp)) {
                        boost += 1.5
                    }
                }
                // Multi-term coverage reward
                if (tokens.size >= 2 && directMatches == tokens.size) {
                    boost += 5.0
                }
                // Category intent alignment boost
                if (preferredCategory != null && hit.entry.category == preferredCategory) {
                    boost += 3.5
                }
                // Science guides slightly preferred over raw single foods when query is conceptual
                if (hit.entry.category == "nutrition" &&
                    hit.entry.metadataJson.contains("\"guide_science\"") &&
                    tokens.any { it in setOf("dosage", "combien", "pourquoi", "quand", "comment", "protocole", "besoin") }
                ) {
                    boost += 4.5
                }

                RagHit(entry = hit.entry, score = hit.score + boost)
            }
            .sortedByDescending { it.score }
            .take(topK)
    }

    fun buildCoachSystemAndUserMessage(
        userQuestion: String,
        profile: AthleteProfile,
        ragHits: List<RagHit>,
        recentHistory: List<Pair<Boolean, String>>
    ): String {
        val sb = StringBuilder(2200)
        sb.append("Tu es KINETIX Coach, préparateur physique, biomécanicien et expert en nutrition sportive 100% hors-ligne. ")
        sb.append("Réponds UNIQUEMENT en français clair, structuré, concret et direct (listes courtes, chiffres exacts, zéro blabla). ")
        sb.append("Appuie-toi prioritairement sur les sources locales fournies ci-dessous. ")
        sb.append("En cas de douleur aiguë ou symptôme grave, rappelle de consulter un médecin du sport ou kinésithérapeute.\n\n")
        sb.append("PROFIL ATHLÈTE : ").append(profile.toPromptSummary()).append("\n")

        if (ragHits.isNotEmpty()) {
            sb.append("\nSOURCES LOCALES KINETIX :\n")
            ragHits.forEachIndexed { idx, hit ->
                val e: KnowledgeEntry = hit.entry
                val excerpt = e.body.replace("\n", " ").take(420)
                sb.append("[S").append(idx + 1).append(" · ").append(e.category.uppercase()).append("] ")
                sb.append(e.title).append(" — ").append(excerpt).append("\n")
            }
        }

        if (recentHistory.isNotEmpty()) {
            sb.append("\nCONTEXTE RÉCENT :\n")
            recentHistory.takeLast(4).forEach { (isUser, content) ->
                val prefix = if (isUser) "Athlète" else "Coach"
                sb.append(prefix).append(" : ").append(content.replace("\n", " ").take(180)).append("\n")
            }
        }

        sb.append("\nQUESTION DE L'ATHLÈTE :\n").append(userQuestion.trim())
        return sb.toString()
    }
}
