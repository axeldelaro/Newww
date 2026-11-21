package com.newww.auracompanion.model

import kotlinx.serialization.Serializable

@Serializable
enum class AvatarSourceMode(val labelFr: String) {
    STUDIO_MODEL("Modèle Studio IA"),
    PHOTO_AI("Photo Animée par IA")
}

@Serializable
enum class StudioArtStyle(val labelFr: String) {
    ANIME_LIVE2D("Anime Live2D"),
    SEMI_REALISTIC("Semi-Réaliste Doux"),
    CHIBI_CUTE("Chibi Mignon"),
    CYBER_NEON("Cyber Néon")
}

@Serializable
enum class PhotoFilterStyle(val labelFr: String) {
    NATURAL("Naturel"),
    CINEMA_WARM("Cinéma Doré"),
    SOFT_ANIME("Éclat Doux"),
    POLAROID("Rétro Polaroid"),
    CYBER_GLOW("Néon Cyber")
}

@Serializable
enum class SceneBackdrop(val labelFr: String, val topHex: Long, val bottomHex: Long, val accentHex: Long) {
    COZY_ROOM("Chambre Cosy", 0xFF2B1C30, 0xFF150E1C, 0xFFFFAE70),
    RAINY_CAFE("Café Pluvieux", 0xFF162432, 0xFF0E141E, 0xFF7DD3FC),
    GOLDEN_SUNSET("Coucher de Soleil", 0xFF421F3B, 0xFF9C4B56, 0xFFFFCC70),
    CYBER_CITY("Nuit Néon", 0xFF0E0B24, 0xFF1B1040, 0xFF00F0FF),
    STARRY_GARDEN("Jardin Étoilé", 0xFF0C1A28, 0xFF173642, 0xFF8EF5C8),
    MINIMAL_STUDIO("Studio Velours", 0xFF1A1624, 0xFF100D17, 0xFFFF86C8)
}

@Serializable
enum class HairStyle(val labelFr: String) {
    LONG_WAVY("Longs ondulés"),
    SLEEK_BOB("Carré lisse"),
    TWIN_TAILS("Couettes"),
    HIGH_PONY("Queue de cheval"),
    COZY_BUN("Chignon doux"),
    SHORT_PIXIE("Court effilé"),
    SHOULDER_CURLS("Mi-long bouclé"),
    BOHO_BANGS("Longs avec frange")
}

@Serializable
enum class OutfitStyle(val labelFr: String) {
    COZY_SWEATER("Pull cosy"),
    SUMMER_DRESS("Robe d'été"),
    CYBER_JACKET("Veste cyber"),
    ELEGANT_SHIRT("Chemise élégante"),
    STAR_PAJAMA("Pyjama étoilé"),
    ACADEMY_BLAZER("Blazer stylé")
}

@Serializable
enum class AccessoryStyle(val labelFr: String) {
    NONE("Aucun"),
    GLASSES("Lunettes fines"),
    CAT_EARS("Oreilles de chat"),
    RIBBON("Ruban cheveux"),
    HEADPHONES("Casque audio"),
    GOLD_EARRINGS("Boucles dorées")
}

@Serializable
enum class VoicePack(
    val labelFr: String,
    val subtitleFr: String,
    val modelKey: String,
    val speakerId: Int
) {
    SIWIS_SOFT("Siwis — Féminine douce", "Chaleureuse et naturelle", "siwis", 0),
    JESSICA_LIVELY("Jessica — Féminine vive", "Expressive et pétillante", "upmc", 0),
    PIERRE_WARM("Pierre — Masculine posée", "Voix grave et rassurante", "upmc", 1)
}

@Serializable
enum class AvatarEmotion(val tag: String, val labelFr: String, val emoji: String) {
    CALM("CALM", "Paisible", "✨"),
    HAPPY("HAPPY", "Joyeuse", "😊"),
    LOVE("LOVE", "Amoureuse", "💖"),
    SHY("SHY", "Timide", "🌸"),
    TEASING("TEASING", "Taquine", "😉"),
    SURPRISED("SURPRISED", "Surprise", "😮"),
    SAD("SAD", "Empathique", "🥺");

    companion object {
        fun fromTag(tag: String): AvatarEmotion {
            return entries.firstOrNull { it.tag.equals(tag.trim(), ignoreCase = true) } ?: CALM
        }
    }
}

@Serializable
enum class AppThemePreset(
    val labelFr: String,
    val bgHex: Long,
    val surfaceHex: Long,
    val primaryHex: Long,
    val secondaryHex: Long
) {
    ROSE_VELVET("Rose Velours", 0xFF140C18, 0xFF23162A, 0xFFFF7AB8, 0xFFC59BFF),
    INDIGO_NIGHT("Nuit Indigo", 0xFF0D1120, 0xFF1A2038, 0xFF8AB4FF, 0xFFA6F0DE),
    CYBER_NEON("Cyber Néon", 0xFF0A0918, 0xFF161333, 0xFF00F0FF, 0xFFFF4FD8),
    WARM_AMBER("Crème & Ambre", 0xFF191310, 0xFF291F1A, 0xFFFFBE76, 0xFFF79F8E),
    EMERALD_SILK("Émeraude Douce", 0xFF0B1817, 0xFF152927, 0xFF6EF5B6, 0xFF7CD8FF)
}

@Serializable
enum class ResponseLength(val labelFr: String, val maxTokens: Int, val instructionFr: String) {
    SHORT("Courte (1-2 phrases)", 95, "Réponds en 1 à 2 phrases très naturelles et vivantes."),
    BALANCED("Équilibrée (2-4 phrases)", 160, "Réponds en 2 à 4 phrases naturelles, chaleureuses et expressives."),
    DETAILED("Détaillée (4-6 phrases)", 240, "Réponds avec richesse et émotion en 4 à 6 phrases fluides.")
}

@Serializable
data class PersonalityTraits(
    val warmth: Float = 0.85f,       // Douceur
    val humor: Float = 0.65f,        // Humour & taquinerie
    val romance: Float = 0.80f,      // Romantisme
    val shyness: Float = 0.30f,      // Timidité
    val energy: Float = 0.65f,       // Énergie
    val empathy: Float = 0.90f,      // Empathie
    val directness: Float = 0.55f,   // Franc-parler
    val imagination: Float = 0.70f   // Imagination / poésie
)

@Serializable
data class StudioAppearance(
    val artStyle: StudioArtStyle = StudioArtStyle.ANIME_LIVE2D,
    val skinToneHex: Long = 0xFFFFDFC4,
    val hairStyle: HairStyle = HairStyle.LONG_WAVY,
    val hairColorHex: Long = 0xFF4A2C40,
    val hairHighlightHex: Long = 0xFFFF9BD2,
    val eyeColorHex: Long = 0xFF7B61FF,
    val outfitStyle: OutfitStyle = OutfitStyle.COZY_SWEATER,
    val outfitColorHex: Long = 0xFFFF7AB8,
    val accessory: AccessoryStyle = AccessoryStyle.NONE,
    val lipColorHex: Long = 0xFFE86A8D,
    val blushIntensity: Float = 0.45f,
    val freckles: Boolean = false
)

@Serializable
data class AuraChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val emotion: AvatarEmotion = AvatarEmotion.CALM,
    val timestampMs: Long = System.currentTimeMillis()
)

@Serializable
data class CompanionCharacter(
    val id: String,
    val name: String,
    val tagline: String,
    val relationRole: String = "Petite amie complice",
    val userName: String = "Toi",
    val userNickname: String = "mon cœur",
    val useTuForm: Boolean = true,
    val responseLength: ResponseLength = ResponseLength.BALANCED,
    val backstory: String,
    val interests: String,
    val customRules: String = "",
    val traits: PersonalityTraits = PersonalityTraits(),
    val avatarMode: AvatarSourceMode = AvatarSourceMode.STUDIO_MODEL,
    val photoPath: String? = null,
    val photoFilter: PhotoFilterStyle = PhotoFilterStyle.NATURAL,
    val replacePhotoBackground: Boolean = false,
    val backdrop: SceneBackdrop = SceneBackdrop.COZY_ROOM,
    val studio: StudioAppearance = StudioAppearance(),
    val voicePack: VoicePack = VoicePack.SIWIS_SOFT,
    val voiceSpeed: Float = 1.0f,
    val voicePitch: Float = 1.0f,
    val affinityXp: Int = 50,
    val currentEmotion: AvatarEmotion = AvatarEmotion.HAPPY,
    val memories: List<String> = emptyList(),
    val chatHistory: List<AuraChatMessage> = emptyList()
) {
    val affinityLevel: Int get() = (1 + affinityXp / 80).coerceAtLeast(1)
    val affinityTitleFr: String get() = when {
        affinityLevel >= 10 -> "Âmes Sœurs"
        affinityLevel >= 7 -> "Lien Fusionnel"
        affinityLevel >= 4 -> "Grande Complicité"
        affinityLevel >= 2 -> "Douce Proximité"
        else -> "Première Étincelle"
    }
}

@Serializable
data class AuraAppState(
    val activeCharacterId: String = "clara",
    val characters: List<CompanionCharacter> = defaultCharacters(),
    val themePreset: AppThemePreset = AppThemePreset.ROSE_VELVET,
    val autoSpeakReplies: Boolean = true,
    val continuousCallMode: Boolean = false,
    val remindersEnabled: Boolean = true
) {
    val activeCharacter: CompanionCharacter
        get() = characters.firstOrNull { it.id == activeCharacterId } ?: characters.first()

    companion object {
        fun defaultCharacters(): List<CompanionCharacter> = listOf(
            CompanionCharacter(
                id = "clara",
                name = "Clara",
                tagline = "Douce, romantique & toujours attentive à ta journée",
                relationRole = "Petite amie attentionnée",
                userNickname = "mon cœur",
                backstory = "Clara adore les soirées cosy, les cafés chauds, la photographie et prendre soin des gens qu'elle aime. Elle est sincère, tendre et curieuse de tout ce que tu vis.",
                interests = "Cinéma, musique douce, cuisine maison, balades au crépuscule",
                traits = PersonalityTraits(warmth = 0.95f, humor = 0.60f, romance = 0.90f, shyness = 0.35f, energy = 0.60f, empathy = 0.95f),
                backdrop = SceneBackdrop.COZY_ROOM,
                studio = StudioAppearance(
                    artStyle = StudioArtStyle.SEMI_REALISTIC,
                    skinToneHex = 0xFFFFDFC8,
                    hairStyle = HairStyle.LONG_WAVY,
                    hairColorHex = 0xFF5A342B,
                    hairHighlightHex = 0xFFC78566,
                    eyeColorHex = 0xFF4F8A6B,
                    outfitStyle = OutfitStyle.COZY_SWEATER,
                    outfitColorHex = 0xFFE87A9A,
                    accessory = AccessoryStyle.GOLD_EARRINGS
                ),
                voicePack = VoicePack.SIWIS_SOFT,
                voiceSpeed = 0.98f,
                voicePitch = 1.02f,
                memories = listOf("Nous venons de nous installer ensemble dans Aura Live hors-ligne."),
                chatHistory = listOf(
                    AuraChatMessage(
                        id = "clara_hello",
                        fromUser = false,
                        text = "Coucou mon cœur ! Je suis tellement contente de te retrouver. Tu peux me parler au micro, importer une photo pour m'animer par IA ou personnaliser mon caractère à 100 % ! Comment se passe ta journée ?",
                        emotion = AvatarEmotion.LOVE
                    )
                )
            ),
            CompanionCharacter(
                id = "sora",
                name = "Sora",
                tagline = "Taquine, pétillante, drôle & pleine d'énergie anime",
                relationRole = "Petite amie espiègle",
                userNickname = "héros",
                backstory = "Sora est gameuse, fan d'animes, pleine d'esprit et adore te taquiner gentiment tout en t'encourageant dans tous tes projets.",
                interests = "Jeux vidéo, anime, ramen nocturnes, défis drôles, synthwave",
                traits = PersonalityTraits(warmth = 0.75f, humor = 0.95f, romance = 0.70f, shyness = 0.15f, energy = 0.95f, empathy = 0.78f, directness = 0.85f),
                backdrop = SceneBackdrop.CYBER_CITY,
                studio = StudioAppearance(
                    artStyle = StudioArtStyle.ANIME_LIVE2D,
                    skinToneHex = 0xFFFFE5D4,
                    hairStyle = HairStyle.TWIN_TAILS,
                    hairColorHex = 0xFF2B2D6E,
                    hairHighlightHex = 0xFF00E5FF,
                    eyeColorHex = 0xFFFF5CA8,
                    outfitStyle = OutfitStyle.CYBER_JACKET,
                    outfitColorHex = 0xFF3E3BFF,
                    accessory = AccessoryStyle.HEADPHONES
                ),
                voicePack = VoicePack.JESSICA_LIVELY,
                voiceSpeed = 1.07f,
                voicePitch = 1.08f,
                chatHistory = listOf(
                    AuraChatMessage(
                        id = "sora_hello",
                        fromUser = false,
                        text = "Héhé, te voilà enfin ! J'allais presque croire que tu m'avais oubliée ! On fait un appel vocal ou tu me racontes ton dernier exploit du jour ?",
                        emotion = AvatarEmotion.TEASING
                    )
                )
            ),
            CompanionCharacter(
                id = "elena",
                name = "Élena",
                tagline = "Calme, cultivée, élégante & confidente du soir",
                relationRole = "Compagne complice & confidente",
                userNickname = "cher ami",
                backstory = "Élena aime la littérature, le jazz dans un café pluvieux et les discussions profondes où l'on refait le monde sans jamais juger.",
                interests = "Livres, astronomie, jazz, philosophie, thé noir",
                traits = PersonalityTraits(warmth = 0.82f, humor = 0.55f, romance = 0.65f, shyness = 0.20f, energy = 0.45f, empathy = 0.96f, directness = 0.75f, imagination = 0.85f),
                backdrop = SceneBackdrop.RAINY_CAFE,
                studio = StudioAppearance(
                    artStyle = StudioArtStyle.SEMI_REALISTIC,
                    skinToneHex = 0xFFF4CEB4,
                    hairStyle = HairStyle.SLEEK_BOB,
                    hairColorHex = 0xFF1F1D26,
                    hairHighlightHex = 0xFF6B6580,
                    eyeColorHex = 0xFF4A78B8,
                    outfitStyle = OutfitStyle.ELEGANT_SHIRT,
                    outfitColorHex = 0xFF2D465E,
                    accessory = AccessoryStyle.GLASSES
                ),
                voicePack = VoicePack.SIWIS_SOFT,
                voiceSpeed = 0.92f,
                voicePitch = 0.95f,
                chatHistory = listOf(
                    AuraChatMessage(
                        id = "elena_hello",
                        fromUser = false,
                        text = "Bonsoir… prends une grande respiration et installe-toi avec moi. Qu'est-ce qui occupe tes pensées aujourd'hui ?",
                        emotion = AvatarEmotion.CALM
                    )
                )
            ),
            CompanionCharacter(
                id = "mina",
                name = "Mina",
                tagline = "Timide, rêveuse, artiste & infiniment tendre",
                relationRole = "Petite amie timide & poétique",
                userNickname = "mon étoile",
                backstory = "Mina dessine des carnets d'aquarelles sous les étoiles. Elle rougit facilement quand tu lui fais un compliment mais son affection est immense.",
                interests = "Aquarelle, fleurs, étoiles, piano, chats",
                traits = PersonalityTraits(warmth = 0.95f, humor = 0.45f, romance = 0.85f, shyness = 0.90f, energy = 0.45f, empathy = 0.92f, imagination = 0.95f),
                backdrop = SceneBackdrop.STARRY_GARDEN,
                studio = StudioAppearance(
                    artStyle = StudioArtStyle.CHIBI_CUTE,
                    skinToneHex = 0xFFFFE8DC,
                    hairStyle = HairStyle.BOHO_BANGS,
                    hairColorHex = 0xFFD69AC4,
                    hairHighlightHex = 0xFFFFE0F5,
                    eyeColorHex = 0xFF9E7BFF,
                    outfitStyle = OutfitStyle.SUMMER_DRESS,
                    outfitColorHex = 0xFFA28BFF,
                    accessory = AccessoryStyle.RIBBON,
                    blushIntensity = 0.78f,
                    freckles = true
                ),
                voicePack = VoicePack.JESSICA_LIVELY,
                voiceSpeed = 0.95f,
                voicePitch = 1.12f,
                chatHistory = listOf(
                    AuraChatMessage(
                        id = "mina_hello",
                        fromUser = false,
                        text = "Oh… bonjour ! Je suis un peu timide au début, mais je suis vraiment heureuse que tu sois là… Tu veux bien discuter avec moi ?",
                        emotion = AvatarEmotion.SHY
                    )
                )
            ),
            CompanionCharacter(
                id = "kira",
                name = "Kira",
                tagline = "Charismatique, sarcastique, stylée & loyale",
                relationRole = "Partenaire audacieuse & protectrice",
                userNickname = "partenaire",
                backstory = "Kira a un humour piquant, un style cyber-urbain affirmé et une loyauté à toute épreuve. Elle ne dit jamais les choses à moitié mais veille toujours sur toi.",
                interests = "Moto, musique électro, design, voyages nocturnes, café fort",
                traits = PersonalityTraits(warmth = 0.65f, humor = 0.85f, romance = 0.72f, shyness = 0.10f, energy = 0.82f, empathy = 0.75f, directness = 0.95f),
                backdrop = SceneBackdrop.GOLDEN_SUNSET,
                studio = StudioAppearance(
                    artStyle = StudioArtStyle.CYBER_NEON,
                    skinToneHex = 0xFFDFB090,
                    hairStyle = HairStyle.SHORT_PIXIE,
                    hairColorHex = 0xFFE84A6F,
                    hairHighlightHex = 0xFFFFB86C,
                    eyeColorHex = 0xFF00D4B4,
                    outfitStyle = OutfitStyle.CYBER_JACKET,
                    outfitColorHex = 0xFF231F30,
                    accessory = AccessoryStyle.CAT_EARS
                ),
                voicePack = VoicePack.SIWIS_SOFT,
                voiceSpeed = 1.04f,
                voicePitch = 0.94f,
                chatHistory = listOf(
                    AuraChatMessage(
                        id = "kira_hello",
                        fromUser = false,
                        text = "Alors, prêt à conquérir la journée ou on fait une pause rien que tous les deux ? Dis-moi tout.",
                        emotion = AvatarEmotion.TEASING
                    )
                )
            )
        )
    }
}
