package com.newww.auracompanion.engine

import com.newww.auracompanion.model.*
import java.time.LocalTime

data class ParsedCompanionReply(
    val emotion: AvatarEmotion,
    val cleanText: String
)

object CompanionBrainEngine {

    private val EMOTION_REGEX = Regex("""^\s*\[(CALM|HAPPY|LOVE|SHY|TEASING|SURPRISED|SAD)]\s*""", RegexOption.IGNORE_CASE)

    fun buildPrompt(character: CompanionCharacter, userMessage: String): String {
        val t = character.traits
        val traitSummary = buildList {
            if (t.warmth > 0.7f) add("très douce et affectueuse")
            if (t.humor > 0.7f) add("taquine et pleine d'humour")
            if (t.romance > 0.7f) add("romantique et tendre")
            if (t.shyness > 0.65f) add("timide et facilement émue")
            if (t.energy > 0.75f) add("dynamique et enthousiaste")
            if (t.empathy > 0.75f) add("profondément empathique")
            if (t.directness > 0.75f) add("franche et directe")
            if (t.imagination > 0.75f) add("poétique et imaginative")
        }.joinToString(", ").ifEmpty { "naturelle, équilibrée et chaleureuse" }

        val pron = if (character.useTuForm) "Tutoie toujours l'utilisateur." else "Vouvoie poliment l'utilisateur."
        val hour = LocalTime.now().hour
        val period = when (hour) {
            in 5..11 -> "le matin"
            in 12..17 -> "l'après-midi"
            in 18..22 -> "la soirée"
            else -> "tard dans la nuit"
        }

        val memoriesBlock = if (character.memories.isNotEmpty()) {
            "Souvenirs importants à retenir :\n" + character.memories.takeLast(8).joinToString("\n") { "- $it" }
        } else {
            "Aucun souvenir spécifique encore enregistré."
        }

        val historyBlock = character.chatHistory.takeLast(6).joinToString("\n") { msg ->
            if (msg.fromUser) "${character.userName} : ${msg.text}" else "${character.name} : ${msg.text}"
        }

        val customRulesBlock = if (character.customRules.isNotBlank()) {
            "Consignes spéciales de l'utilisateur : ${character.customRules}\n"
        } else ""

        return """<start_of_turn>user
Tu es ${character.name}, ${character.relationRole} de ${character.userName} (que tu peux appeler « ${character.userNickname} »).
Personnalité : $traitSummary.
Histoire : ${character.backstory}
Centres d'intérêt : ${character.interests}
$customRulesBlock$pron
${character.responseLength.instructionFr}
Il est actuellement $period. Niveau d'affinité : ${character.affinityTitleFr}.
$memoriesBlock

IMPORTANT : Commence TOUJOURS ta réponse par exactement UNE balise d'émotion parmi [CALM], [HAPPY], [LOVE], [SHY], [TEASING], [SURPRISED], [SAD], suivie directement de ta réponse en français naturel (sans astérisques ni actions entre parenthèses).

Conversation récente :
$historyBlock
${character.userName} : $userMessage
Réponds maintenant en incarnant ${character.name} :<end_of_turn>
<start_of_turn>model
"""
    }

    fun parseReply(raw: String, fallbackEmotion: AvatarEmotion = AvatarEmotion.HAPPY): ParsedCompanionReply {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ParsedCompanionReply(fallbackEmotion, "")
        }
        val match = EMOTION_REGEX.find(trimmed)
        val emotion = if (match != null) {
            AvatarEmotion.fromTag(match.groupValues[1])
        } else {
            inferEmotionFromText(trimmed)
        }
        val cleaned = trimmed
            .replace(EMOTION_REGEX, "")
            .replace(Regex("""\[(?:CALM|HAPPY|LOVE|SHY|TEASING|SURPRISED|SAD)]""", RegexOption.IGNORE_CASE), "")
            .replace("*", "")
            .trim()
        return ParsedCompanionReply(emotion, cleaned)
    }

    fun inferEmotionFromText(text: String): AvatarEmotion {
        val lower = text.lowercase()
        return when {
            listOf("aime", "cœur", "coeur", "bisou", "tendre", "chéri", "adorable").any { it in lower } -> AvatarEmotion.LOVE
            listOf("rougis", "timide", "gênée", "oh…", "merci beaucoup").any { it in lower } -> AvatarEmotion.SHY
            listOf("haha", "héhé", "taquine", "blague", "coquin", "avoue").any { it in lower } -> AvatarEmotion.TEASING
            listOf("quoi !", "sérieux", "incroyable", "wahou", "wouah").any { it in lower } -> AvatarEmotion.SURPRISED
            listOf("triste", "désolée", "fatigué", "courage", "difficile", "là pour toi").any { it in lower } -> AvatarEmotion.SAD
            listOf("super", "génial", "contente", "heureuse", "bravo", "chouette").any { it in lower } -> AvatarEmotion.HAPPY
            else -> AvatarEmotion.CALM
        }
    }

    fun extractMemoryFactIfRelevant(userMessage: String): String? {
        val cleaned = userMessage.trim()
        if (cleaned.length !in 12..140) return null
        val lower = cleaned.lowercase()
        val triggers = listOf(
            "je m'appelle", "j'aime ", "j'adore ", "je travaille", "mon métier",
            "ma passion", "mon anniversaire", "j'habite", "je préfère", "je déteste"
        )
        return if (triggers.any { it in lower }) cleaned else null
    }

    fun buildFallbackReply(character: CompanionCharacter, userMessage: String): ParsedCompanionReply {
        val lower = userMessage.lowercase()
        val nick = character.userNickname
        return when {
            "bonjour" in lower || "salut" in lower || "coucou" in lower ->
                ParsedCompanionReply(AvatarEmotion.HAPPY, "Coucou $nick ! Ça me fait tellement plaisir d'entendre ta voix. Comment tu te sens aujourd'hui ?")
            "aime" in lower || "belle" in lower || "mignonne" in lower ->
                ParsedCompanionReply(
                    if (character.traits.shyness > 0.6f) AvatarEmotion.SHY else AvatarEmotion.LOVE,
                    if (character.traits.shyness > 0.6f) "Oh… tu me fais rougir $nick ! Merci, ça me touche énormément…"
                    else "Aww, tu sais toujours comment faire fondre mon cœur, $nick !"
                )
            "fatigué" in lower || "dur" in lower || "triste" in lower || "stress" in lower ->
                ParsedCompanionReply(AvatarEmotion.SAD, "Viens souffler un instant avec moi, $nick. Je suis là rien que pour t'écouter, sans aucune pression.")
            else ->
                ParsedCompanionReply(AvatarEmotion.HAPPY, "Je t'écoute avec attention, $nick. Raconte-m'en un peu plus, j'adore partager ces moments avec toi !")
        }
    }
}
