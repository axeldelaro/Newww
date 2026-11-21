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
    CARTOGRAPHER("Élio", "Cartographe des Brumes", "curieux, enthousiaste, attentif aux paysages et toujours prêt à noter chaque détail dans son carnet", 0xFFE8844A, 0xFFFFD166, "🧭"),
    RANGER("Lyra", "Rôdeuse d'Émeraude", "poétique, calme, proche de la forêt et des animaux sauvages, très bienveillante", 0xFF39B982, 0xFF8BF5C5, "🌿"),
    BARD("Bram", "Barde Voyageur", "drôle, chaleureux, gourmand, invente de petits refrains joyeux sur tes marches", 0xFFD95B7A, 0xFFFFB5C2, "🪕"),
    ALCHEMIST("Nyx", "Alchimiste Errante", "malicieuse, érudite, fascinée par les ruines anciennes, les cristaux et les étoiles", 0xFF7B61FF, 0xFFC5B4FF, "⚗️")
}

@Serializable
enum class FamiliarCompanion(val labelFr: String, val emoji: String, val bonusFr: String, val colorHex: Long) {
    FOX("Renard d'Ambre", "🦊", "+15 % pièces sur la route", 0xFFFF8A4C),
    OWL("Hibou Astral", "🦉", "+15 % chance de reliques", 0xFFA5B4FC),
    FIREFLY("Esprit Luciole", "✨", "+15 % ingrédients récoltés", 0xFFFFF176),
    WOLF("Louveteau Boréal", "🐺", "-15 % fatigue de marche", 0xFF93C5FD)
}

@Serializable
enum class IngredientType(val labelFr: String, val emoji: String) {
    WOOD("Bois Ancien", "🪵"),
    HERB("Herbe Lunaire", "🌿"),
    CRYSTAL("Éclat de Cristal", "💎"),
    HONEY("Miel Sauvage", "🍯")
}

@Serializable
enum class CraftRecipe(
    val id: String,
    val labelFr: String,
    val emoji: String,
    val descFr: String,
    val costWood: Int = 0,
    val costHerb: Int = 0,
    val costCrystal: Int = 0,
    val costHoney: Int = 0
) {
    VITAL_ELIXIR("elixir", "Élixir Lunaire", "🧪", "+45 Santé & +45 Énergie instantanés", costHerb = 2, costHoney = 1),
    ROYAL_FEAST("feast", "Festin du Camp", "🍲", "+50 Moral, +40 Énergie, +40 XP", costWood = 1, costHerb = 1, costHoney = 2),
    SOLAR_CHARM("charm", "Amulette Solaire", "☀️", "+120 XP & +35 Pièces d'or", costCrystal = 2, costWood = 1),
    SECRET_MAP("map", "Carte des Trésors", "🗺️", "Déclenche une découverte garantie (+80 XP)", costWood = 2, costCrystal = 1)
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
    RUNIC_LANTERN("lantern", "Lanterne Runique", "🏮", "+15 % reliques dans les ruines", relicBoost = 0.15f),
    COPPER_COMPASS("compass", "Boussole de Cuivre", "🧭", "+15 % XP de marche", moraleBoost = 5f),
    RAIN_CLOAK("cloak", "Cape Imperméable", "🧥", "-25 % fatigue sur le sentier", energySave = 0.25f),
    SKETCHBOOK("sketch", "Carnet d'Esquisses", "📖", "+10 Moral & récits riches", moraleBoost = 10f),
    TRAVEL_FLUTE("flute", "Flûte de Voyage", "🎶", "Attire les créatures (+10 % reliques)", relicBoost = 0.10f, moraleBoost = 6f),
    CLIMBING_ROPE("rope", "Corde d'Escalade", "🪢", "Accède aux coffres cachés (+18 %)", relicBoost = 0.18f),
    ALCHEMIST_LENS("lens", "Loupe d'Alchimiste", "🔍", "Détecte les cristaux rares (+20 %)", relicBoost = 0.20f),
    MAGIC_FLASK("flask", "Gourde Enchantée", "🧪", "-30 % perte d'énergie", energySave = 0.30f)
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
data class EncounterChoice(
    val labelFr: String,
    val outcomeTextFr: String,
    val xpDelta: Int = 20,
    val coinsDelta: Int = 10,
    val moraleDelta: Float = 8f,
    val energyDelta: Float = 0f,
    val rewardIngredient: IngredientType? = null,
    val relicChance: Float = 0.25f
)

@Serializable
data class InteractiveEncounter(
    val id: String,
    val emoji: String,
    val titleFr: String,
    val storyFr: String,
    val choices: List<EncounterChoice>
)

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
    val familiar: FamiliarCompanion = FamiliarCompanion.FOX,
    val level: Int = 1,
    val xp: Int = 0,
    val coins: Int = 55,
    val health: Float = 95f,
    val energy: Float = 88f,
    val morale: Float = 90f,
    val totalSteps: Int = 0,
    val todaySteps: Int = 0,
    val todayDayKey: String = "",
    val weeklySteps: Map<String, Int> = emptyMap(),
    val streakDays: Int = 1,
    val packedGearIds: List<String> = listOf(GearItem.COPPER_COMPASS.id, GearItem.SKETCHBOOK.id),
    val ingredients: Map<IngredientType, Int> = mapOf(
        IngredientType.WOOD to 2,
        IngredientType.HERB to 2,
        IngredientType.CRYSTAL to 1,
        IngredientType.HONEY to 1
    ),
    val activeEncounter: InteractiveEncounter? = null,
    val lastEncounterStepMark: Int = 0,
    val choicesMadeToday: List<String> = emptyList(),
    val claimedStepMilestonesToday: List<Int> = emptyList(),
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
            title = "Bienvenue dans StepQuest v2 !",
            description = "Tes pas de la journée sont synchronisés automatiquement. Touche les trésors sur la carte ou explore les rencontres !"
        )
    ),
    val campChat: List<CampChatMessage> = listOf(
        CampChatMessage(
            id = "welcome",
            fromUser = false,
            text = "Salut compagnon ! Je suis prêt à explorer le monde avec toi et mon fidèle familier. Pose-moi n'importe quelle question au feu de camp ou pars marcher pour déclencher des aventures !"
        )
    ),
    val remindersEnabled: Boolean = true,
    val lastUpdatedMs: Long = System.currentTimeMillis()
)

data class PedometerStatus(
    val permissionGranted: Boolean = true,
    val hasHardwareSensor: Boolean = true,
    val label: String = "Podomètre automatique actif"
)
