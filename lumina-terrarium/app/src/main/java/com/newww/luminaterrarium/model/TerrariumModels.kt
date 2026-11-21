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
enum class LampTint(val labelFr: String, val colorHex: Long) {
    GOLDEN_SUN("Soleil Doré", 0xFFFFE599),
    MOON_BLUE("Clair de Lune", 0xFFA6D8FF),
    AURORA_ROSE("Aurore Rose", 0xFFFF9BD2),
    FOREST_EMERALD("Lueur Émeraude", 0xFF8EF5BE)
}

@Serializable
enum class JarStyle(val labelFr: String, val costDew: Int, val descriptionFr: String) {
    ROUND_TERRARIUM("Bocal Rond", 0, "Verre soufflé classique et bouchon de liège gravé"),
    APOTHECARY("Flacon Apothicaire", 40, "Silhouette élancée de laboratoire botanique"),
    LANTERN("Lanterne Céleste", 85, "Cadre doré suspendu à lumière douce"),
    CRYSTAL_GEODE("Géode Cristalline", 140, "Prisme facetté aux reflets d'aurore"),
    TEARDROP_DOME("Goutte Suspendue", 190, "Bulle aérienne aux reflets irisés"),
    OBSERVATORY("Dôme Astral", 250, "Coupole d'observatoire sertie de laiton")
}

@Serializable
enum class FoodType(
    val labelFr: String,
    val emoji: String,
    val hungerGain: Float,
    val energyGain: Float,
    val moodGain: Float,
    val xpGain: Int,
    val colorHex: Long
) {
    SWEET_DEW("Rosée Sucrée", "💧", 24f, 10f, 10f, 8, 0xFF6AE4FF),
    LUMINA_BERRY("Baie Lumineuse", "🫐", 32f, 8f, 12f, 10, 0xFF7CF7A0),
    SOLAR_CRYSTAL("Cristal Solaire", "✨", 28f, 18f, 10f, 12, 0xFFFFB84D),
    STAR_PETAL("Pétale d'Étoile", "🌸", 22f, 14f, 20f, 14, 0xFFE59EFF)
}

@Serializable
enum class DecorItem(
    val id: String,
    val labelFr: String,
    val costDew: Int,
    val categoryFr: String,
    val defaultX: Float,
    val defaultY: Float
) {
    MOSS_STONES("moss_stones", "Galets Mousseux", 0, "Sol", 0.25f, 0.78f),
    GLOW_MUSHROOMS("glow_mushrooms", "Champignons Lucioles", 20, "Flore", 0.34f, 0.76f),
    FERN_CLUSTER("fern_cluster", "Fougères Douces", 25, "Flore", 0.74f, 0.76f),
    WATER_LILY("water_lily", "Nénuphar Blanc", 30, "Eau", 0.45f, 0.80f),
    QUARTZ_CRYSTAL("quartz_crystal", "Cristaux de Quartz", 40, "Minéral", 0.80f, 0.77f),
    FAIRY_LANTERN("fairy_lantern", "Mini Lanterne", 55, "Lumière", 0.28f, 0.30f),
    SEA_SHELL("sea_shell", "Coquillage Nacré", 35, "Eau", 0.62f, 0.80f),
    FIREFLIES_NEST("fireflies_nest", "Essaim de Lucioles", 65, "Magie", 0.52f, 0.42f),
    BONSAI_TWIG("bonsai_twig", "Branche Bonsaï", 75, "Flore", 0.23f, 0.74f),
    MOON_PEBBLE("moon_pebble", "Pierre de Lune", 60, "Minéral", 0.54f, 0.81f),
    MINI_TORII("mini_torii", "Portique Zen", 95, "Décor", 0.72f, 0.75f),
    STAR_CHIME("star_chime", "Carillon d'Étoiles", 110, "Magie", 0.73f, 0.28f),
    SAKURA_BRANCH("sakura_branch", "Rameau Sakura", 125, "Flore", 0.76f, 0.44f),
    CRYSTAL_FOUNTAIN("crystal_fountain", "Source Cristalline", 140, "Eau", 0.38f, 0.79f),
    MINI_CABIN("mini_cabin", "Cabane Fée", 160, "Décor", 0.64f, 0.75f),
    AURORA_PRISM("aurora_prism", "Prisme d'Aurore", 180, "Magie", 0.50f, 0.26f)
}

@Serializable
data class NormPos(val x: Float, val y: Float)

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

enum class MiniGameMode(val labelFr: String, val emoji: String, val descFr: String) {
    NONE("", "", ""),
    FIREFLIES("Chasse aux Lucioles", "✨", "Attrape les lucioles dorées (×2 en combo) !"),
    STAR_RAIN("Pluie d'Étoiles", "🌠", "Glisse ton doigt pour guider ton esprit sous les étoiles !"),
    MELODY("Carillon Mémoire", "🎵", "Répète la mélodie lumineuse jouée par ton esprit !")
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
    val wipesCount: Int = 0,
    val miniGamesWon: Int = 0,
    val stepsToday: Int = 0,
    val claimedIds: List<String> = emptyList()
)

@Serializable
data class SpiritProfile(
    val element: SpiritElement,
    val name: String = element.defaultName,
    val hunger: Float = 84f,
    val hydration: Float = 82f,
    val energy: Float = 88f,
    val happiness: Float = 86f,
    val affection: Float = 20f,
    val lightLevel: Float = 68f,
    val lampTint: LampTint = LampTint.GOLDEN_SUN,
    val glassFog: Float = 0.25f,
    val xp: Int = 0,
    val jarStyle: JarStyle = JarStyle.ROUND_TERRARIUM,
    val activeDecorIds: List<String> = listOf(DecorItem.MOSS_STONES.id, DecorItem.GLOW_MUSHROOMS.id),
    val decorPositions: Map<String, NormPos> = emptyMap(),
    val customAuraHex: Long = element.primaryColorHex,
    val isSleeping: Boolean = false,
    val adoptedAtMs: Long = System.currentTimeMillis(),
    val lastSpeech: String = "Bonjour ! Caresse-moi, essuie la buée ou lance-moi une friandise !",
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
    val dewCurrency: Int = 65,
    val unlockedDecorIds: List<String> = listOf(DecorItem.MOSS_STONES.id, DecorItem.GLOW_MUSHROOMS.id),
    val unlockedJarStyles: List<JarStyle> = listOf(JarStyle.ROUND_TERRARIUM),
    val totalStepsConverted: Int = 0,
    val weeklySteps: Map<String, Int> = emptyMap(),
    val soundscape: AmbientSoundscape = AmbientSoundscape.SILENT,
    val sfxEnabled: Boolean = true,
    val remindersEnabled: Boolean = true,
    val journal: List<JournalEntry> = listOf(
        JournalEntry(
            timestampMs = System.currentTimeMillis(),
            title = "Bienvenue dans Lumina Terrarium v2",
            detail = "Ton bocal s'est illuminé ! Tes pas de la journée sont synchronisés automatiquement.",
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
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val shakeIntensity: Float = 0f
)

data class PedometerHealth(
    val permissionGranted: Boolean = true,
    val hasHardwareSensor: Boolean = true,
    val statusText: String = "Podomètre automatique actif"
)
