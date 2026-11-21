package com.newww.stepquest.model

import kotlinx.serialization.Serializable

@Serializable
enum class AdventurerClass(
    val defaultName: String,
    val titleFr: String,
    val personalityFr: String,
    val cloakHex: Long,
    val accentHex: Long,
    val emoji: String
) {
    CARTOGRAPHER(
        defaultName = "Élio",
        titleFr = "Cartographe des Brumes",
        personalityFr = "curieux, enthousiaste, attentif aux paysages et toujours prêt à noter chaque détail dans son carnet",
        cloakHex = 0xFFE8844A,
        accentHex = 0xFFFFD166,
        emoji = "🧭"
    ),
    RANGER(
        defaultName = "Lyra",
        titleFr = "Rôdeuse d'Émeraude",
        personalityFr = "poétique, calme, proche de la forêt et des créatures sauvages, très bienveillante",
        cloakHex = 0xFF39B982,
        accentHex = 0xFF8BF5C5,
        emoji = "🌿"
    ),
    BARD(
        defaultName = "Bram",
        titleFr = "Barde Voyageur",
        personalityFr = "drôle, chaleureux, gourmand, invente de petits refrains sur tes marches du jour",
        cloakHex = 0xFFD95B7A,
        accentHex = 0xFFFFB5C2,
        emoji = "🪕"
    ),
    ALCHEMIST(
        defaultName = "Nyx",
        titleFr = "Alchimiste Errante",
        personalityFr = "malicieuse, érudite, fascinée par les ruines anciennes, les cristaux et les étoiles",
        cloakHex = 0xFF7B61FF,
        accentHex = 0xFFC5B4FF,
        emoji = "⚗️"
    )
}

@Serializable
enum class GearItem(
    val id: String,
    val labelFr: String,
    val emoji: String,
    val bonusDescFr: String,
    val relicBoost: Float = 0f,
    val energySave: Float = 0f,
    val moraleBoost: Float = 0f
) {
    RUNIC_LANTERN("lantern", "Lanterne Runique", "🏮", "Éclaire les ruines et grottes (+15 % reliques)", relicBoost = 0.15f),
    COPPER_COMPASS("compass", "Boussole de Cuivre", "🧭", "Trouve des raccourcis (+15 % XP de marche)", moraleBoost = 5f),
    RAIN_CLOAK("cloak", "Cape Imperméable", "🧥", "Protège de la fatigue (-25 % perte énergie)", energySave = 0.25f),
    SKETCHBOOK("sketch", "Carnet d'Esquisses", "📖", "Enrichit les lettres du soir (+10 Moral)", moraleBoost = 10f),
    TRAVEL_FLUTE("flute", "Flûte de Voyage", "🎶", "Attire les créatures amicales (+10 % reliques)", relicBoost = 0.10f, moraleBoost = 6f),
    CLIMBING_ROPE("rope", "Corde d'Escalade", "🪢", "Accède aux coffres cachés (+18 % reliques)", relicBoost = 0.18f),
    ALCHEMIST_LENS("lens", "Loupe d'Alchimiste", "🔍", "Détecte les cristaux rares (+20 % reliques)", relicBoost = 0.20f),
    MAGIC_FLASK("flask", "Gourde Enchantée", "🧪", "Hydrate toute la journée (-30 % perte énergie)", energySave = 0.30f)
}

@Serializable
enum class ProvisionItem(
    val id: String,
    val labelFr: String,
    val emoji: String,
    val healthGain: Float,
    val energyGain: Float,
    val moraleGain: Float,
    val costCoins: Int
) {
    WARM_BREAD("bread", "Pain Chaud aux Herbes", "🥖", 15f, 22f, 10f, 0),
    FOREST_SOUP("soup", "Soupe Forestière", "🍲", 28f, 25f, 16f, 12),
    GLOW_BERRIES("berries", "Baies Énergétiques", "🫐", 12f, 34f, 14f, 10),
    STAR_TEA("tea", "Tisane Étoilée", "🍵", 20f, 20f, 28f, 15),
    HONEY_PIE("pie", "Tarte au Miel Doré", "🥧", 32f, 32f, 32f, 22)
}

@Serializable
data class WorldBiome(
    val index: Int,
    val nameFr: String,
    val minSteps: Int,
    val maxSteps: Int,
    val skyTopHex: Long,
    val skyBottomHex: Long,
    val groundHex: Long,
    val descriptionFr: String,
    val landmarks: List<String>
)

@Serializable
data class RelicEntry(
    val id: String,
    val nameFr: String,
    val emoji: String,
    val rarityFr: String,
    val biomeName: String,
    val loreFr: String,
    val foundAtMs: Long = 0L
)

@Serializable
data class ExpeditionLetter(
    val id: String,
    val dayKey: String,
    val timestampMs: Long,
    val biomeName: String,
    val stepsCount: Int,
    val title: String,
    val body: String,
    val generatedByLlm: Boolean
)

@Serializable
data class CampChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

@Serializable
data class WorldEventLog(
    val timestampMs: Long,
    val emoji: String,
    val title: String,
    val description: String
)

@Serializable
data class StepQuestState(
    val adventurerClass: AdventurerClass = AdventurerClass.CARTOGRAPHER,
    val adventurerName: String = AdventurerClass.CARTOGRAPHER.defaultName,
    val customCloakHex: Long = AdventurerClass.CARTOGRAPHER.cloakHex,
    val level: Int = 1,
    val xp: Int = 0,
    val coins: Int = 40,
    val health: Float = 95f,
    val energy: Float = 88f,
    val morale: Float = 90f,
    val totalSteps: Int = 0,
    val todaySteps: Int = 0,
    val todayDayKey: String = "",
    val streakDays: Int = 1,
    val packedGearIds: List<String> = listOf(GearItem.COPPER_COMPASS.id, GearItem.SKETCHBOOK.id),
    val morningPackDoneDayKey: String = "",
    val middayMealDoneDayKey: String = "",
    val eveningLetterDayKey: String = "",
    val mealsFedToday: List<String> = emptyList(),
    val discoveredRelics: List<RelicEntry> = emptyList(),
    val letters: List<ExpeditionLetter> = emptyList(),
    val events: List<WorldEventLog> = listOf(
        WorldEventLog(
            timestampMs = System.currentTimeMillis(),
            emoji = "🧭",
            title = "Départ de l'expédition !",
            description = "Prépare mon sac ce matin, puis marche dans la vraie vie pour me faire avancer."
        )
    ),
    val campChat: List<CampChatMessage> = listOf(
        CampChatMessage(
            id = "welcome",
            fromUser = false,
            text = "Salut compagnon de route ! Prépare mon sac pour la journée, et dès que tu marches hors-ligne, j'avance à travers les royaumes. Ce soir je t'écrirai ma lettre d'expédition !"
        )
    ),
    val remindersEnabled: Boolean = true,
    val lastUpdatedMs: Long = System.currentTimeMillis()
)
