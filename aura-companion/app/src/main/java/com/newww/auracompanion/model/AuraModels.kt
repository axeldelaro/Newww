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
    SEMI_REALISTIC("Semi-Réaliste"),
    MIMICO_GLAM("Glamour Doux"),
    CHIBI_CUTE("Chibi Mignon"),
    CYBER_NEON("Cyber Néon"),
    WATERCOLOR("Aquarelle Poétique")
}

@Serializable
enum class PhotoFilterStyle(val labelFr: String) {
    NATURAL("Naturel"),
    SOFT_PORTRAIT("Portrait Doux"),
    CINEMA_WARM("Cinéma Doré"),
    SAKURA_GLOW("Éclat Sakura"),
    POLAROID("Rétro Polaroid"),
    CYBER_GLOW("Néon Cyber"),
    MOONLIGHT("Clair de Lune"),
    WARM_VELVET("Velours Chaud"),
    STUDIO_GLAM("Studio Glamour"),
    ANIME_BLOOM("Lumière Anime")
}

@Serializable
enum class SceneBackdrop(val labelFr: String, val topHex: Long, val bottomHex: Long, val accentHex: Long) {
    COZY_ROOM("Chambre Cosy", 0xFF2B1C30, 0xFF150E1C, 0xFFFFAE70),
    RAINY_CAFE("Café Pluvieux", 0xFF162432, 0xFF0E141E, 0xFF7DD3FC),
    GOLDEN_SUNSET("Balcon Coucher de Soleil", 0xFF421F3B, 0xFF9C4B56, 0xFFFFCC70),
    CYBER_CITY("Tokyo Néon", 0xFF0E0B24, 0xFF1B1040, 0xFF00F0FF),
    SAKURA_GARDEN("Jardin Sakura", 0xFF2D1934, 0xFF1B152A, 0xFFFF9BD2),
    NIGHT_BEACH("Plage Nocturne", 0xFF0A192F, 0xFF12324A, 0xFF7CE8FF),
    COZY_LIBRARY("Bibliothèque Chaleureuse", 0xFF291A16, 0xFF140D0B, 0xFFFFBE76),
    SNOW_CHALET("Chalet Neige & Feu", 0xFF162032, 0xFF241A1C, 0xFFFF9E5E),
    STAR_PENTHOUSE("Penthouse Étoilé", 0xFF0A0E22, 0xFF1D1838, 0xFFC59BFF),
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
    BOHO_BANGS("Longs avec frange"),
    SIDE_BRAID("Tresse de côté"),
    HIME_CUT("Coupe Hime")
}

@Serializable
enum class OutfitStyle(val labelFr: String) {
    COZY_SWEATER("Pull cosy"),
    SUMMER_DRESS("Robe d'été"),
    CYBER_JACKET("Veste cyber"),
    ELEGANT_SHIRT("Chemise élégante"),
    STAR_PAJAMA("Pyjama étoilé"),
    ACADEMY_BLAZER("Blazer stylé"),
    EVENING_GOWN("Robe de soirée"),
    HOODIE_CHILL("Hoodie confort")
}

@Serializable
enum class AccessoryStyle(val labelFr: String) {
    NONE("Aucun"),
    GLASSES("Lunettes fines"),
    CAT_EARS("Oreilles de chat"),
    RIBBON("Ruban cheveux"),
    HEADPHONES("Casque audio"),
    GOLD_EARRINGS("Boucles dorées"),
    PEARL_NECKLACE("Collier perles"),
    HAIR_CLIPS("Barrettes étoiles")
}

@Serializable
enum class VoicePack(
    val labelFr: String,
    val subtitleFr: String,
    val modelKey: String,
    val speakerId: Int
) {
    SIWIS_SOFT("Siwis — Féminine douce", "Chaleureuse, tendre et fluide", "siwis", 0),
    JESSICA_LIVELY("Jessica — Féminine vive", "Expressive et pétillante", "upmc", 0),
    PIERRE_WARM("Pierre — Masculine posée", "Grave et rassurante", "upmc", 1)
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
        fun fromTag(tag: String): AvatarEmotion =
            entries.firstOrNull { it.tag.equals(tag.trim(), ignoreCase = true) } ?: CALM
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
    MIMICO_ROSE("Mimico Rose Néon", 0xFF120A18, 0xFF21132B, 0xFFFF6EB4, 0xFFB892FF),
    VELVET_NIGHT("Velours Nuit", 0xFF0E0B14, 0xFF1C1628, 0xFFF48FB1, 0xFFFFD580),
    INDIGO_MIDNIGHT("Minuit Indigo", 0xFF0A0F1F, 0xFF161E38, 0xFF8AB4FF, 0xFF8EF5D4),
    CYBER_TOKYO("Cyber Tokyo", 0xFF090818, 0xFF151232, 0xFF00F0FF, 0xFFFF4FD8),
    GOLDEN_HOUR("Coucher Doré", 0xFF1A1110, 0xFF2C1D1A, 0xFFFFBE76, 0xFFFF8F8F),
    SAKURA_PASTEL("Sakura Pastel", 0xFF1C121D, 0xFF2D1E2E, 0xFFFFA8D5, 0xFFFFE0A3),
    EMERALD_LUXE("Émeraude Luxe", 0xFF091615, 0xFF132927, 0xFF6EF5B6, 0xFF7CD8FF),
    ROYAL_PURPLE("Pourpre Royal", 0xFF130B20, 0xFF23163A, 0xFFC88CFF, 0xFFFF96C5),
    OBSIDIAN_GOLD("Obsidienne Or", 0xFF0D0D10, 0xFF1B1B22, 0xFFF5C96B, 0xFFE5E7EB),
    AURORA_BOREALIS("Aurore Boréale", 0xFF081420, 0xFF112538, 0xFF5CF2C2, 0xFFA68BFF)
}

@Serializable
enum class ChatBubbleStyle(val labelFr: String) {
    FROSTED_GLASS("Verre Givré (Mimico)"),
    FLOATING_MINIMAL("Flottant Minimal"),
    NEON_GLOW("Bordure Néon"),
    SOFT_CLASSIC("Bulles Douces")
}

@Serializable
enum class AuraAmbientSound(val labelFr: String, val emoji: String) {
    SILENT("Silence", "🔇"),
    SOFT_RAIN("Pluie douce", "🌧️"),
    COZY_FIRE("Cheminée", "🔥"),
    NIGHT_CALM("Nuit étoilée", "🌙")
}

@Serializable
enum class RoleplayScenario(val labelFr: String, val emoji: String, val promptHintFr: String) {
    FREE_DAILY("Discussion libre", "💬", "Conversation naturelle du quotidien en tête-à-tête."),
    COZY_MOVIE("Soirée film cosy", "🎬", "Nous sommes blottis sous un plaid devant un bon film pendant qu'il pleut dehors."),
    PARIS_CAFE("Rendez-vous café", "☕", "Nous sommes assis à une petite table d'un café chaleureux à discuter de tout."),
    TOKYO_NIGHT("Balade à Tokyo", "🌃", "Nous marchons main dans la main sous les néons de Tokyo le soir."),
    TRUTH_OR_DARE("Action ou Vérité", "🎲", "Nous jouons à Action ou Vérité ensemble de façon complice, drôle et tendre."),
    TWENTY_QUESTIONS("Jeu des 20 questions", "❓", "Nous jouons au jeu des 20 questions ou aux devinettes pour mieux nous connaître."),
    CUSTOM("Scénario libre", "✨", "")
}

@Serializable
enum class ResponseLength(val labelFr: String, val maxTokens: Int, val instructionFr: String) {
    SHORT("Courte (1-2 phrases)", 85, "Réponds en 1 à 2 phrases concises, naturelles et directes."),
    BALANCED("Équilibrée (2-4 phrases)", 150, "Réponds en 2 à 4 phrases naturelles, fluides et expressives."),
    DETAILED("Détaillée (4-6 phrases)", 230, "Réponds avec richesse et nuance en 4 à 6 phrases fluides.")
}

@Serializable
data class PhotoAnimationConfig(
    val lipSyncIntensity: Float = 1.15f,
    val headMotionIntensity: Float = 1.10f,
    val photoZoom: Float = 1.05f,
    val showFloatingParticles: Boolean = true
)

@Serializable
data class PersonalityTraits(
    val warmth: Float = 0.85f,
    val humor: Float = 0.65f,
    val romance: Float = 0.80f,
    val shyness: Float = 0.30f,
    val energy: Float = 0.65f,
    val empathy: Float = 0.90f,
    val directness: Float = 0.55f,
    val imagination: Float = 0.70f
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
    val scenario: RoleplayScenario = RoleplayScenario.FREE_DAILY,
    val customScenarioText: String = "",
    val traits: PersonalityTraits = PersonalityTraits(),
    val avatarMode: AvatarSourceMode = AvatarSourceMode.STUDIO_MODEL,
    val photoPath: String? = null,
    val photoFilter: PhotoFilterStyle = PhotoFilterStyle.NATURAL,
    val photoAnim: PhotoAnimationConfig = PhotoAnimationConfig(),
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
    val themePreset: AppThemePreset = AppThemePreset.MIMICO_ROSE,
    val bubbleStyle: ChatBubbleStyle = ChatBubbleStyle.FROSTED_GLASS,
    val ambientSound: AuraAmbientSound = AuraAmbientSound.SILENT,
    val autoSpeakReplies: Boolean = true,
    val continuousCallMode: Boolean = false,
    val autoLearnSettings: Boolean = true,
    val remindersEnabled: Boolean = true
) {
    val activeCharacter: CompanionCharacter
        get() = characters.firstOrNull { it.id == activeCharacterId } ?: characters.first()

    companion object {
        fun defaultCharacters(): List<CompanionCharacter> = listOf(
            CompanionCharacter(
                id = "clara",
                name = "Clara",
                tagline = "Douce, romantique, attentive & curieuse de ta journée",
                relationRole = "Petite amie attentionnée",
                userNickname = "mon cœur",
                backstory = "Clara adore les soirées cosy, les cafés chauds, le cinéma et prendre soin des personnes qu'elle aime. Elle écoute toujours vraiment ce qu'on lui dit et retient les petits détails.",
                interests = "Cinéma, musique douce, cuisine maison, voyages, balades",
                traits = PersonalityTraits(warmth = 0.95f, humor = 0.62f, romance = 0.90f, shyness = 0.30f, energy = 0.62f, empathy = 0.95f),
                backdrop = SceneBackdrop.COZY_ROOM,
                studio = StudioAppearance(StudioArtStyle.MIMICO_GLAM, 0xFFFFDFC8, HairStyle.LONG_WAVY, 0xFF5A342B, 0xFFC78566, 0xFF4F8A6B, OutfitStyle.COZY_SWEATER, 0xFFE87A9A, AccessoryStyle.GOLD_EARRINGS),
                voicePack = VoicePack.SIWIS_SOFT,
                voiceSpeed = 0.98f,
                voicePitch = 1.02f,
                chatHistory = listOf(
                    AuraChatMessage("clara_hi", false, "Coucou mon cœur ! Dis-moi comment tu t'appelles ou ce que tu as envie de faire : je m'adapte à toi et je retiens ce que tu me confies !", AvatarEmotion.LOVE)
                )
            ),
            CompanionCharacter(
                id = "sora",
                name = "Sora",
                tagline = "Taquine, pétillante, gameuse & pleine d'esprit",
                relationRole = "Petite amie espiègle",
                userNickname = "héros",
                backstory = "Sora adore les jeux vidéo, les animes, les défis drôles et te taquiner gentiment avec beaucoup de répartie et d'affection.",
                interests = "Jeux vidéo, anime, ramen nocturnes, défis, synthwave",
                traits = PersonalityTraits(warmth = 0.78f, humor = 0.95f, romance = 0.72f, shyness = 0.15f, energy = 0.95f, empathy = 0.80f, directness = 0.85f),
                backdrop = SceneBackdrop.CYBER_CITY,
                studio = StudioAppearance(StudioArtStyle.ANIME_LIVE2D, 0xFFFFE5D4, HairStyle.TWIN_TAILS, 0xFF2B2D6E, 0xFF00E5FF, 0xFFFF5CA8, OutfitStyle.CYBER_JACKET, 0xFF3E3BFF, AccessoryStyle.HEADPHONES),
                voicePack = VoicePack.JESSICA_LIVELY,
                voiceSpeed = 1.06f,
                voicePitch = 1.06f,
                chatHistory = listOf(
                    AuraChatMessage("sora_hi", false, "Héhé, te voilà enfin ! On se fait un appel vocal, un Action ou Vérité ou tu me racontes ta journée ?", AvatarEmotion.TEASING)
                )
            ),
            CompanionCharacter(
                id = "elena",
                name = "Élena",
                tagline = "Calme, cultivée, élégante & confidente du soir",
                relationRole = "Compagne complice & confidente",
                userNickname = "cher ami",
                backstory = "Élena aime la littérature, le jazz dans un café pluvieux, les sciences et les conversations profondes sans jugement.",
                interests = "Livres, astronomie, jazz, philosophie, thé noir",
                traits = PersonalityTraits(warmth = 0.82f, humor = 0.58f, romance = 0.65f, shyness = 0.20f, energy = 0.48f, empathy = 0.96f, directness = 0.75f, imagination = 0.85f),
                backdrop = SceneBackdrop.RAINY_CAFE,
                studio = StudioAppearance(StudioArtStyle.SEMI_REALISTIC, 0xFFF4CEB4, HairStyle.SLEEK_BOB, 0xFF1F1D26, 0xFF6B6580, 0xFF4A78B8, OutfitStyle.ELEGANT_SHIRT, 0xFF2D465E, AccessoryStyle.GLASSES),
                voicePack = VoicePack.SIWIS_SOFT,
                voiceSpeed = 0.93f,
                voicePitch = 0.96f,
                chatHistory = listOf(
                    AuraChatMessage("elena_hi", false, "Bonsoir… installe-toi confortablement. De quoi as-tu envie qu'on discute ce soir ?", AvatarEmotion.CALM)
                )
            ),
            CompanionCharacter(
                id = "mina",
                name = "Mina",
                tagline = "Timide, poétique, artiste & infiniment tendre",
                relationRole = "Petite amie timide & rêveuse",
                userNickname = "mon étoile",
                backstory = "Mina peint à l'aquarelle sous les cerisiers. Elle rougit vite devant un compliment mais adore écouter tes rêves.",
                interests = "Aquarelle, fleurs, étoiles, piano, chats",
                traits = PersonalityTraits(warmth = 0.95f, humor = 0.45f, romance = 0.86f, shyness = 0.88f, energy = 0.45f, empathy = 0.92f, imagination = 0.95f),
                backdrop = SceneBackdrop.SAKURA_GARDEN,
                studio = StudioAppearance(StudioArtStyle.WATERCOLOR, 0xFFFFE8DC, HairStyle.BOHO_BANGS, 0xFFD69AC4, 0xFFFFE0F5, 0xFF9E7BFF, OutfitStyle.SUMMER_DRESS, 0xFFA28BFF, AccessoryStyle.RIBBON, blushIntensity = 0.78f, freckles = true),
                voicePack = VoicePack.JESSICA_LIVELY,
                voiceSpeed = 0.95f,
                voicePitch = 1.10f,
                chatHistory = listOf(
                    AuraChatMessage("mina_hi", false, "Oh… bonjour ! Je suis un peu timide au début, mais je suis vraiment heureuse de discuter avec toi…", AvatarEmotion.SHY)
                )
            ),
            CompanionCharacter(
                id = "kira",
                name = "Kira",
                tagline = "Charismatique, sarcastique, audacieuse & protectrice",
                relationRole = "Partenaire audacieuse",
                userNickname = "partenaire",
                backstory = "Kira a un humour piquant, un style affirmé et une loyauté sans faille. Elle dit toujours ce qu'elle pense avec style.",
                interests = "Moto, musique électro, design, café fort, voyages nocturnes",
                traits = PersonalityTraits(warmth = 0.68f, humor = 0.86f, romance = 0.75f, shyness = 0.10f, energy = 0.84f, empathy = 0.78f, directness = 0.95f),
                backdrop = SceneBackdrop.GOLDEN_SUNSET,
                studio = StudioAppearance(StudioArtStyle.CYBER_NEON, 0xFFDFB090, HairStyle.SHORT_PIXIE, 0xFFE84A6F, 0xFFFFB86C, 0xFF00D4B4, OutfitStyle.CYBER_JACKET, 0xFF231F30, AccessoryStyle.CAT_EARS),
                voicePack = VoicePack.SIWIS_SOFT,
                voiceSpeed = 1.04f,
                voicePitch = 0.94f,
                chatHistory = listOf(
                    AuraChatMessage("kira_hi", false, "Alors, prêt à conquérir la journée ou on s'offre une vraie pause rien que tous les deux ?", AvatarEmotion.TEASING)
                )
            ),
            CompanionCharacter(
                id = "lea",
                name = "Léa",
                tagline = "Solaire, sportive, optimiste & super complice",
                relationRole = "Petite amie complice & dynamique",
                userNickname = "champion",
                backstory = "Léa déborde d'énergie positive, aime la plage, le sport, la photo et te motiver chaque matin avec le sourire.",
                interests = "Surf, randonnée, photographie, smoothies, musique pop",
                traits = PersonalityTraits(warmth = 0.90f, humor = 0.80f, romance = 0.78f, shyness = 0.15f, energy = 0.94f, empathy = 0.86f),
                backdrop = SceneBackdrop.NIGHT_BEACH,
                studio = StudioAppearance(StudioArtStyle.SEMI_REALISTIC, 0xFFE3B18E, HairStyle.HIGH_PONY, 0xFFB87B4B, 0xFFFFE082, 0xFF3DA5D9, OutfitStyle.HOODIE_CHILL, 0xFF2FA87C, AccessoryStyle.NONE, freckles = true),
                voicePack = VoicePack.JESSICA_LIVELY,
                voiceSpeed = 1.04f,
                voicePitch = 1.02f,
                chatHistory = listOf(
                    AuraChatMessage("lea_hi", false, "Hey !! Comment tu vas aujourd'hui ? Raconte-moi ton programme, je suis prête à te booster !", AvatarEmotion.HAPPY)
                )
            ),
            CompanionCharacter(
                id = "yumi",
                name = "Yumi",
                tagline = "Mystérieuse, raffinée, passionnée de culture japonaise",
                relationRole = "Compagne élégante & poétique",
                userNickname = "mon trésor",
                backstory = "Yumi adore les jardins zen, le matcha, la musique lo-fi et les promenades nocturnes sous les lanternes.",
                interests = "Thé matcha, calligraphie, jardins zen, lo-fi, cinéma japonais",
                traits = PersonalityTraits(warmth = 0.86f, humor = 0.55f, romance = 0.84f, shyness = 0.45f, energy = 0.52f, empathy = 0.90f, imagination = 0.92f),
                backdrop = SceneBackdrop.STAR_PENTHOUSE,
                studio = StudioAppearance(StudioArtStyle.ANIME_LIVE2D, 0xFFFFECE0, HairStyle.HIME_CUT, 0xFF1A1826, 0xFF7B61FF, 0xFFE84A6F, OutfitStyle.EVENING_GOWN, 0xFF4A2559, AccessoryStyle.HAIR_CLIPS),
                voicePack = VoicePack.SIWIS_SOFT,
                voiceSpeed = 0.95f,
                voicePitch = 1.05f,
                chatHistory = listOf(
                    AuraChatMessage("yumi_hi", false, "Bonsoir mon trésor… le ciel est magnifique ce soir. Qu'est-ce qui te ferait plaisir maintenant ?", AvatarEmotion.CALM)
                )
            ),
            CompanionCharacter(
                id = "ambre",
                name = "Ambre",
                tagline = "Chaleureuse, gourmande, passionnée & protectrice",
                relationRole = "Petite amie cocooning",
                userNickname = "mon amour",
                backstory = "Ambre adore préparer des pâtisseries, les soirées chalet au coin du feu et t'écouter sans compter le temps.",
                interests = "Pâtisserie, chocolat chaud, séries, animaux, voyages en montagne",
                traits = PersonalityTraits(warmth = 0.98f, humor = 0.72f, romance = 0.92f, shyness = 0.25f, energy = 0.66f, empathy = 0.96f),
                backdrop = SceneBackdrop.SNOW_CHALET,
                studio = StudioAppearance(StudioArtStyle.MIMICO_GLAM, 0xFFF4CEB4, HairStyle.SIDE_BRAID, 0xFFC45C36, 0xFFFFB86C, 0xFF4F8A6B, OutfitStyle.COZY_SWEATER, 0xFFE68A4A, AccessoryStyle.PEARL_NECKLACE),
                voicePack = VoicePack.SIWIS_SOFT,
                voiceSpeed = 0.97f,
                voicePitch = 1.00f,
                chatHistory = listOf(
                    AuraChatMessage("ambre_hi", false, "Coucou mon amour ! Le feu crépite et j'ai préparé du chocolat chaud. Viens me raconter ta journée !", AvatarEmotion.LOVE)
                )
            )
        )
    }
}
