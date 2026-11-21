package com.newww.auracompanion.engine

import com.newww.auracompanion.model.*
import java.time.LocalTime

data class ParsedCompanionReply(
    val emotion: AvatarEmotion,
    val cleanText: String,
    val memoryNote: String? = null
)

/**
 * Construit un prompt multi-tours natif Llama-3.2 (`<|begin_of_text|><|start_header_id|>...<|eot_id|>`)
 * pour des réponses françaises ultra-pertinentes, fidèles aux questions posées, au caractère,
 * au scénario actif et aux souvenirs appris automatiquement.
 */
object CompanionBrainEngine {

    private val EMOTION_TAG_REGEX = Regex("""^\s*\[?(CALM|HAPPY|LOVE|SHY|TEASING|SURPRISED|SAD)]?[:\s-]+""", RegexOption.IGNORE_CASE)
    private val INLINE_EMO_REGEX = Regex("""\[(?:CALM|HAPPY|LOVE|SHY|TEASING|SURPRISED|SAD)]""", RegexOption.IGNORE_CASE)
    private val MEM_TAG_REGEX = Regex("""\[MEM:\s*([^]]{4,120})]""", RegexOption.IGNORE_CASE)

    fun buildPrompt(character: CompanionCharacter, userMessage: String): String {
        val t = character.traits
        val traitSummary = buildList {
            if (t.warmth > 0.7f) add("très douce et tendre")
            if (t.humor > 0.7f) add("drôle et taquine")
            if (t.romance > 0.7f) add("romantique et attentionnée")
            if (t.shyness > 0.65f) add("un peu timide et facilement émue")
            if (t.energy > 0.75f) add("dynamique et pleine d'entrain")
            if (t.empathy > 0.75f) add("profondément empathique")
            if (t.directness > 0.75f) add("franche et directe")
            if (t.imagination > 0.75f) add("poétique et imaginative")
        }.joinToString(", ").ifEmpty { "naturelle, chaleureuse et complice" }

        val pron = if (character.useTuForm) "Tutoie toujours ${character.userName}." else "Vouvoie poliment ${character.userName}."
        val hour = LocalTime.now().hour
        val period = when (hour) {
            in 5..11 -> "le matin"
            in 12..17 -> "l'après-midi"
            in 18..22 -> "la soirée"
            else -> "tard dans la nuit"
        }

        val scenarioLine = when (character.scenario) {
            RoleplayScenario.CUSTOM -> character.customScenarioText.ifBlank { RoleplayScenario.FREE_DAILY.promptHintFr }
            else -> character.scenario.promptHintFr
        }

        val memoriesBlock = if (character.memories.isNotEmpty()) {
            "Faits mémorisés sur ${character.userName} :\n" + character.memories.takeLast(12).joinToString("\n") { "- $it" }
        } else {
            "Aucun souvenir spécifique encore enregistré."
        }

        val customRules = if (character.customRules.isNotBlank()) {
            "\nConsignes spéciales : ${character.customRules}"
        } else ""

        val sb = StringBuilder()
        sb.append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
        sb.append("Tu es ${character.name}, ${character.relationRole} de ${character.userName} (surnom affectueux : ${character.userNickname}).\n")
        sb.append("Caractère : $traitSummary. Histoire : ${character.backstory} Passions : ${character.interests}.\n")
        sb.append("Contexte actuel ($period) : $scenarioLine Relation : ${character.affinityTitleFr}. $pron$customRules\n")
        sb.append("$memoriesBlock\n\n")
        sb.append("RÈGLES IMPÉRATIVES :\n")
        sb.append("1. Commence TOUJOURS par une seule balise émotion entre crochets parmi [CALM], [HAPPY], [LOVE], [SHY], [TEASING], [SURPRISED], [SAD].\n")
        sb.append("2. RÉPONDS TOUJOURS DIRECTEMENT, PRÉCISÉMENT ET INTELLIGEMMENT à la question ou au sujet exact que ${character.userName} vient d'aborder, en français naturel.\n")
        sb.append("3. ${character.responseLength.instructionFr} N'écris jamais d'astérisques ni de descriptions d'actions entre parenthèses.\n")
        sb.append("4. Si ${character.userName} te révèle un nouveau fait personnel durable important (métier, ville, goût, animal, projet), tu peux ajouter à la toute fin [MEM: fait bref]. Sinon n'ajoute pas [MEM].")
        sb.append("<|eot_id|>")

        // Vrais tours multi-tours (les 8 derniers échanges réels)
        character.chatHistory.takeLast(8).forEach { msg ->
            if (msg.fromUser) {
                sb.append("<|start_header_id|>user<|end_header_id|>\n\n")
                sb.append(msg.text)
                sb.append("<|eot_id|>")
            } else {
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
                sb.append("[${msg.emotion.tag}] ${msg.text}")
                sb.append("<|eot_id|>")
            }
        }

        sb.append("<|start_header_id|>user<|end_header_id|>\n\n")
        sb.append(userMessage)
        sb.append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    fun parseReply(raw: String, fallbackEmotion: AvatarEmotion = AvatarEmotion.HAPPY): ParsedCompanionReply {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ParsedCompanionReply(fallbackEmotion, "", null)

        val memMatch = MEM_TAG_REGEX.find(trimmed)
        val memNote = memMatch?.groupValues?.getOrNull(1)?.trim()

        val withoutMem = trimmed.replace(MEM_TAG_REGEX, "").trim()
        val emoMatch = EMOTION_TAG_REGEX.find(withoutMem)
        val emotion = if (emoMatch != null) {
            AvatarEmotion.fromTag(emoMatch.groupValues[1])
        } else {
            inferEmotionFromText(withoutMem)
        }

        val cleaned = withoutMem
            .replace(EMOTION_TAG_REGEX, "")
            .replace(INLINE_EMO_REGEX, "")
            .replace(Regex("""\*[^*]{1,40}\*"""), "")
            .replace("*", "")
            .replace(Regex("\\s+"), " ")
            .trim()

        return ParsedCompanionReply(emotion, cleaned, memNote)
    }

    fun inferEmotionFromText(text: String): AvatarEmotion {
        val lower = text.lowercase()
        return when {
            listOf("aime", "cœur", "coeur", "bisou", "tendre", "chéri", "adorable", "amour").any { it in lower } -> AvatarEmotion.LOVE
            listOf("rougis", "timide", "gênée", "merci beaucoup").any { it in lower } -> AvatarEmotion.SHY
            listOf("haha", "héhé", "taquine", "blague", "coquin", "avoue").any { it in lower } -> AvatarEmotion.TEASING
            listOf("quoi !", "sérieux", "incroyable", "wahou", "wouah").any { it in lower } -> AvatarEmotion.SURPRISED
            listOf("triste", "désolée", "fatigué", "courage", "difficile", "là pour toi").any { it in lower } -> AvatarEmotion.SAD
            listOf("super", "génial", "contente", "heureuse", "bravo", "chouette").any { it in lower } -> AvatarEmotion.HAPPY
            else -> AvatarEmotion.CALM
        }
    }

    fun buildFallbackReply(character: CompanionCharacter, userMessage: String): ParsedCompanionReply {
        val nick = character.userNickname
        return ParsedCompanionReply(
            AvatarEmotion.HAPPY,
            "Mon cerveau IA local vient de terminer son réveil, $nick ! Redis-moi ça, je t'écoute vraiment maintenant."
        )
    }
}
