package com.newww.luminaterrarium.model

import kotlinx.serialization.Serializable

@Serializable
enum class SpiritElement(
    val defaultName: String,
    val titleFr: String,
    val subtitleFr: String,
    val primaryColorHex: Long,
    val secondaryColorHex: Long,
    val groundColorHex: Long,
    val favoriteFood: FoodType,
    val idealHumidityMin: Float,
    val idealHumidityMax: Float,
    val idealLightMin: Float,
    val idealLightMax: Float
) {
    MOSS(
        defaultName = "Sylva",
        titleFr = "Esprit de Mousse",
        subtitleFr = "Paisible, curieux et amoureux de la rosée matinale",
        primaryColorHex = 0xFF6EF5A8,
        secondaryColorHex = 0xFF28B56D,
        groundColorHex = 0xFF23593D,
        favoriteFood = FoodType.LUMINA_BERRY,
        idealHumidityMin = 55f,
        idealHumidityMax = 90f,
        idealLightMin = 40f,
        idealLightMax = 80f
    ),
    WATER(
        defaultName = "Ondine",
        titleFr = "Esprit de Goutte",
        subtitleFr = "Fluide, joueur et bercé par les ondulations",
        primaryColorHex = 0xFF66DCFF,
        secondaryColorHex = 0xFF2A8CF2,
        groundColorHex = 0xFF1A4E7A,
        favoriteFood = FoodType.SWEET_DEW,
        idealHumidityMin = 70f,
        idealHumidityMax = 100f,
        idealLightMin = 30f,
        idealLightMax = 75f
    ),
    EMBER(
        defaultName = "Braise",
        titleFr = "Esprit de Flammèche",
        subtitleFr = "Chaleureux, pétillant et plein d'entrain",
        primaryColorHex = 0xFFFFA552,
        secondaryColorHex = 0xFFFF5A36,
        groundColorHex = 0xFF4A2525,
        favoriteFood = FoodType.SOLAR_CRYSTAL,
        idealHumidityMin = 15f,
        idealHumidityMax = 55f,
        idealLightMin = 55f,
        idealLightMax = 100f
    ),
    CLOUD(
        defaultName = "Nimbo",
        titleFr = "Esprit de Nuage",
        subtitleFr = "Rêveur, léger et ami des étoiles",
        primaryColorHex = 0xFFE3ECFF,
        secondaryColorHex = 0xFF9EB5FF,
        groundColorHex = 0xFF333C66,
        favoriteFood = FoodType.STAR_PETAL,
        idealHumidityMin = 45f,
        idealHumidityMax = 85f,
        idealLightMin = 25f,
        idealLightMax = 70f
    )
}

@Serializable
enum class EvolutionStage(val level: Int, val labelFr: String, val minXp: Int) {
    SEED(1, "Graine d'Éveil", 0),
    SPROUT(2, "Petit Éclos", 100),
    SPIRIT(3, "Esprit Compagnon", 300),
    GUARDIAN(4, "Gardien Lumineux", 700);

    companion object {
        fun fromXp(xp: Int): EvolutionStage = when {
            xp >= GUARDIAN.minXp -> GUARDIAN
            xp >= SPIRIT.minXp -> SPIRIT
            xp >= SPROUT.minXp -> SPROUT
            else -> SEED
        }
    }
}

@Serializable
enum class JarStyle(val labelFr: String, val costDew: Int, val descriptionFr: String) {
    ROUND_TERRARIUM("Bocal Rond", 0, "Classique en verre soufflé avec bouchon de liège"),
    APOTHECARY("Flacon Apothicaire", 40, "Silhouette élancée de laboratoire botanique"),
    LANTERN("Lanterne Céleste", 85, "Cadre doré suspendu à lumière douce"),
    CRYSTAL_GEODE("Géode Cristalline", 140, "Prisme facetté aux reflets d'aurore")
}

@Serializable
enum class FoodType(
    val labelFr: String,
    val hungerGain: Float,
    val energyGain: Float,
    val moodGain: Float,
    val xpGain: Int,
    val colorHex: Long
) {
    SWEET_DEW("Rosée Sucrée", 24f, 10f, 10f, 8, 0xFF6AE4FF),
    LUMINA_BERRY("Baie Lumineuse", 32f, 8f, 12f, 10, 0xFF7CF7A0),
    SOLAR_CRYSTAL("Cristal Solaire", 28f, 18f, 10f, 12, 0xFFFFB84D),
    STAR_PETAL("Pétale d'Étoile", 22f, 14f, 20f, 14, 0xFFE59EFF)
}

@Serializable
enum class DecorItem(
    val id: String,
    val labelFr: String,
    val costDew: Int,
    val categoryFr: String
) {
    MOSS_STONES("moss_stones", "Galets Mousseux", 0, "Sol"),
    GLOW_MUSHROOMS("glow_mushrooms", "Champignons Lucioles", 20, "Flore"),
    FERN_CLUSTER("fern_cluster", "Fougères Douces", 25, "Flore"),
    WATER_LILY("water_lily", "Nénuphar Blanc", 30, "Eau"),
    QUARTZ_CRYSTAL("quartz_crystal", "Cristaux de Quartz", 40, "Minéral"),
    FAIRY_LANTERN("fairy_lantern", "Mini Lanterne", 55, "Lumière"),
    SEA_SHELL("sea_shell", "Coquillage Nacré", 35, "Eau"),
    FIREFLIES_NEST("fireflies_nest", "Essaim de Lucioles", 65, "Magie"),
    BONSAI_TWIG("bonsai_twig", "Branche Bonsaï", 75, "Flore"),
    MOON_PEBBLE("moon_pebble", "Pierre de Lune", 60, "Minéral"),
    MINI_TORII("mini_torii", "Portique Zen", 95, "Décor"),
    STAR_CHIME("star_chime", "Carillon d'Étoiles", 110, "Magie")
}

@Serializable
enum class AmbientSoundscape(val labelFr: String, val subtitleFr: String) {
    SILENT("Silence", "Sons d'interface discrets uniquement"),
    SOFT_RAIN("Pluie sur le verre", "Averse douce synthétisée hors ligne"),
    NIGHT_FOREST("Forêt nocturne", "Brise légère et échos de lucioles"),
    COZY_FIRE("Foyer chaleureux", "Crépitement doux de braises"),
    WIND_CHIMES("Carillon zen", "Notes pentatoniques apaisantes")
}

@Serializable
enum class SpiritMood(val labelFr: String, val emoji: String) {
    ECSTATIC("Rayonnant", "✨"),
    HAPPY("Heureux", "😊"),
    CALM("Paisible", "🌿"),
    PLAYFUL("Joueur", "🎈"),
    SLEEPY("Endormi", "🌙"),
    HUNGRY("Affamé", "🍓"),
    THIRSTY("Assoiffé", "💧"),
    LOW_BATTERY("Batterie faible…", "🔋")
}

@Serializable
enum class DayPhase(val labelFr: String) {
    DAWN("Aube"),
    MORNING("Matin"),
    AFTERNOON("Après-midi"),
    DUSK("Crépuscule"),
    NIGHT("Nuit étoilée");

    companion object {
        fun fromHour(hourOfDay: Int): DayPhase = when (hourOfDay) {
            in 5..7 -> DAWN
            in 8..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..19 -> DUSK
            else -> NIGHT
        }
    }
}

@Serializable
data class JournalEntry(
    val timestampMs: Long,
    val title: String,
    val detail: String,
    val icon: String = "🌿"
)

@Serializable
data class DailyQuestProgress(
    val dayKey: String = "",
    val petsCount: Int = 0,
    val feedsCount: Int = 0,
    val mistsCount: Int = 0,
    val miniGamesWon: Int = 0,
    val stepsToday: Int = 0,
    val claimedIds: List<String> = emptyList()
)

@Serializable
data class SpiritProfile(
    val element: SpiritElement,
    val name: String = element.defaultName,
    val hunger: Float = 84f,       // 0..100 (100 = bien nourri)
    val hydration: Float = 82f,    // 0..100 (humidité / hydratation)
    val energy: Float = 88f,       // 0..100
    val happiness: Float = 86f,    // 0..100
    val affection: Float = 20f,    // 0..100
    val lightLevel: Float = 65f,   // 0..100 (contrôlé par l'utilisateur)
    val xp: Int = 0,
    val jarStyle: JarStyle = JarStyle.ROUND_TERRARIUM,
    val activeDecorIds: List<String> = listOf(DecorItem.MOSS_STONES.id),
    val customAuraHex: Long = element.primaryColorHex,
    val isSleeping: Boolean = false,
    val adoptedAtMs: Long = System.currentTimeMillis(),
    val lastSpeech: String = "Bonjour ! Tapote le verre ou caresse-moi doucement.",
    val lastSpeechAtMs: Long = System.currentTimeMillis()
) {
    val stage: EvolutionStage get() = EvolutionStage.fromXp(xp)
}

@Serializable
data class TerrariumAppState(
    val activeElement: SpiritElement = SpiritElement.MOSS,
    val spirits: Map<SpiritElement, SpiritProfile> = mapOf(
        SpiritElement.MOSS to SpiritProfile(SpiritElement.MOSS),
        SpiritElement.WATER to SpiritProfile(SpiritElement.WATER),
        SpiritElement.EMBER to SpiritProfile(SpiritElement.EMBER),
        SpiritElement.CLOUD to SpiritProfile(SpiritElement.CLOUD)
    ),
    val dewCurrency: Int = 45,
    val unlockedDecorIds: List<String> = listOf(DecorItem.MOSS_STONES.id),
    val unlockedJarStyles: List<JarStyle> = listOf(JarStyle.ROUND_TERRARIUM),
    val totalStepsConverted: Int = 0,
    val lastHardwareStepBaseline: Int = -1,
    val soundscape: AmbientSoundscape = AmbientSoundscape.SILENT,
    val sfxEnabled: Boolean = true,
    val remindersEnabled: Boolean = true,
    val journal: List<JournalEntry> = listOf(
        JournalEntry(
            timestampMs = System.currentTimeMillis(),
            title = "Bienvenue dans Lumina Terrarium",
            detail = "Ton premier bocal s'est illuminé. Prends soin de tes esprits au fil de la journée !",
            icon = "✨"
        )
    ),
    val dailyQuest: DailyQuestProgress = DailyQuestProgress(),
    val lastTickTimestampMs: Long = System.currentTimeMillis()
) {
    val activeSpirit: SpiritProfile
        get() = spirits[activeElement] ?: SpiritProfile(activeElement)
}

data class BatterySnapshot(
    val levelPercent: Int = 80,
    val isCharging: Boolean = false
)

data class TiltSnapshot(
    val tiltX: Float = 0f, // -1..1
    val tiltY: Float = 0f, // -1..1
    val shakeIntensity: Float = 0f
)
