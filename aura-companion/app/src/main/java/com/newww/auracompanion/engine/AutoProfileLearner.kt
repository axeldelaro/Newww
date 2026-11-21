package com.newww.auracompanion.engine

import com.newww.auracompanion.model.*

data class AutoLearnResult(
    val updatedCharacter: CompanionCharacter,
    val learnedBadges: List<String>
)

/**
 * Moteur d'auto-apprentissage & d'adaptation automatique façon Mimico :
 * Extrait automatiquement les infos personnelles et préférences données dans la conversation
 * et met directement à jour les réglages (prénom, surnom, tutoiement, curseurs de personnalité,
 * longueur des réponses, nom de la compagne et mémoire long-terme).
 */
object AutoProfileLearner {

    private val NAME_REGEX = Regex(
        """(?:je m['’]appelle|mon pr[ée]nom (?:est|c['’]est)|moi c['’]est)\s+([A-ZÀ-Ÿa-zà-ÿ-]{2,20})""",
        RegexOption.IGNORE_CASE
    )
    private val NICKNAME_REGEX = Regex(
        """(?:appelle[- ]moi|surnomme[- ]moi)\s+([A-ZÀ-Ÿa-zà-ÿ '’-]{2,24}?)(?:[.!?,]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val RENAME_HER_REGEX = Regex(
        """(?:je vais t['’]appeler|ton (?:nouveau )?nom (?:est|c['’]est)|tu t['’]appelles d[ée]sormais)\s+([A-ZÀ-Ÿa-zà-ÿ-]{2,20})""",
        RegexOption.IGNORE_CASE
    )

    fun learnFromUserMessage(
        character: CompanionCharacter,
        rawMessage: String,
        enabled: Boolean
    ): AutoLearnResult {
        if (!enabled) return AutoLearnResult(character, emptyList())
        val msg = rawMessage.trim()
        if (msg.length < 4) return AutoLearnResult(character, emptyList())
        val lower = msg.lowercase()

        var ch = character
        val badges = mutableListOf<String>()

        // 1. Prénom de l'utilisateur
        NAME_REGEX.find(msg)?.let { m ->
            val extracted = m.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            if (extracted.length in 2..20 && !extracted.equals(ch.userName, ignoreCase = true)) {
                ch = ch.copy(userName = extracted)
                badges += "Prénom → $extracted"
            }
        }

        // 2. Surnom affectueux souhaité
        NICKNAME_REGEX.find(msg)?.let { m ->
            val nick = m.groupValues[1].trim()
            if (nick.length in 2..24 && !nick.equals(ch.userNickname, ignoreCase = true)) {
                val resolved = if (nick.lowercase().contains("mon prénom")) ch.userName else nick
                ch = ch.copy(userNickname = resolved)
                badges += "Surnom → $resolved"
            }
        }

        // 3. Changement du nom du personnage
        RENAME_HER_REGEX.find(msg)?.let { m ->
            val newName = m.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            if (newName.length in 2..20 && !newName.equals(ch.name, ignoreCase = true)) {
                ch = ch.copy(name = newName)
                badges += "Nom → $newName"
            }
        }

        // 4. Tutoiement / Vouvoiement automatique
        if (("tutoie" in lower || "tutoyer" in lower) && !ch.useTuForm) {
            ch = ch.copy(useTuForm = true)
            badges += "Style → Tutoiement"
        } else if (("vouvoie" in lower || "vouvoyer" in lower) && ch.useTuForm) {
            ch = ch.copy(useTuForm = false)
            badges += "Style → Vouvoiement"
        }

        // 5. Adaptation de la longueur des réponses
        if (listOf("réponses plus courtes", "sois plus courte", "fais plus court", "parle moins long").any { it in lower }) {
            if (ch.responseLength != ResponseLength.SHORT) {
                ch = ch.copy(responseLength = ResponseLength.SHORT)
                badges += "Réponses → Courtes"
            }
        } else if (listOf("réponses plus longues", "sois plus détaillée", "développe plus", "fais plus long").any { it in lower }) {
            if (ch.responseLength != ResponseLength.DETAILED) {
                ch = ch.copy(responseLength = ResponseLength.DETAILED)
                badges += "Réponses → Détaillées"
            }
        }

        // 6. Adaptation dynamique des 8 curseurs de personnalité
        var traits = ch.traits
        var traitChanged = false
        fun bump(label: String, apply: (PersonalityTraits) -> PersonalityTraits) {
            traits = apply(traits)
            traitChanged = true
            badges += label
        }

        if (listOf("plus douce", "plus tendre", "plus gentille", "plus affectueuse").any { it in lower }) {
            bump("Douceur ↑") { it.copy(warmth = (it.warmth + 0.15f).coerceAtMost(1f)) }
        }
        if (listOf("plus drôle", "plus taquine", "plus d'humour", "fais des blagues").any { it in lower }) {
            bump("Humour ↑") { it.copy(humor = (it.humor + 0.15f).coerceAtMost(1f)) }
        }
        if (listOf("plus romantique", "plus amoureuse", "plus câline").any { it in lower }) {
            bump("Romantisme ↑") { it.copy(romance = (it.romance + 0.15f).coerceAtMost(1f)) }
        }
        if ("plus timide" in lower) {
            bump("Timidité ↑") { it.copy(shyness = (it.shyness + 0.18f).coerceAtMost(1f)) }
        } else if ("moins timide" in lower || "plus confiante" in lower) {
            bump("Timidité ↓") { it.copy(shyness = (it.shyness - 0.18f).coerceAtLeast(0f)) }
        }
        if (listOf("plus franche", "plus directe", "dis-moi la vérité").any { it in lower }) {
            bump("Franc-parler ↑") { it.copy(directness = (it.directness + 0.15f).coerceAtMost(1f)) }
        }
        if (listOf("plus énergique", "plus dynamique", "plus joyeuse").any { it in lower }) {
            bump("Énergie ↑") { it.copy(energy = (it.energy + 0.15f).coerceAtMost(1f)) }
        }
        if (traitChanged) {
            ch = ch.copy(traits = traits)
        }

        // 7. Extraction automatique des souvenirs durables (Goûts, Vie, Lieu, Proches, Métier…)
        extractStructuredMemory(msg)?.let { fact ->
            val exists = ch.memories.any { it.equals(fact, ignoreCase = true) }
            if (!exists) {
                ch = ch.copy(memories = (ch.memories + fact).takeLast(30))
                badges += "Mémoire +1"
            }
        }

        return AutoLearnResult(ch, badges)
    }

    fun mergeLlmMemoryNote(character: CompanionCharacter, memoryNote: String?): AutoLearnResult {
        val clean = memoryNote?.trim().orEmpty()
        if (clean.length !in 6..120) return AutoLearnResult(character, emptyList())
        if (character.memories.any { it.equals(clean, ignoreCase = true) }) {
            return AutoLearnResult(character, emptyList())
        }
        val next = character.copy(memories = (character.memories + clean).takeLast(30))
        return AutoLearnResult(next, listOf("Souvenir : ${clean.take(32)}"))
    }

    private fun extractStructuredMemory(msg: String): String? {
        if (msg.length !in 10..160 || msg.endsWith("?")) return null
        val lower = msg.lowercase()
        val taggedPrefix = when {
            listOf("j'habite", "je vis à", "je viens de", "ma ville").any { it in lower } -> "Lieu"
            listOf("je travaille", "mon métier", "je suis étudiant", "je fais des études", "mon boulot", "mon job").any { it in lower } -> "Vie"
            listOf("j'adore", "j'aime ", "je kiffe", "ma passion", "mon film préféré", "mon jeu préféré", "ma musique préférée", "je préfère").any { it in lower } -> "Goûts"
            listOf("je déteste", "j'aime pas", "je n'aime pas", "je suis allergique").any { it in lower } -> "À éviter"
            listOf("mon chien", "mon chat", "mon frère", "ma sœur", "ma famille", "mon meilleur ami").any { it in lower } -> "Proches"
            listOf("mon anniversaire", "je suis né", "j'ai ", " ans").any { it in lower } && ("ans" in lower || "anniversaire" in lower || "né" in lower) -> "Identité"
            else -> null
        } ?: return null

        val cleanSentence = msg.replace(Regex("\\s+"), " ").trim().take(125)
        return "[$taggedPrefix] $cleanSentence"
    }
}
