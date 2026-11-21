package com.newww.stepquest.engine

import com.newww.stepquest.model.*
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

object ExpeditionEngine {

    val WORLD_BIOMES: List<WorldBiome> = listOf(
        WorldBiome(
            0, "Vallée des Brumes Dorées", 0, 3_000,
            0xFF1A2844, 0xFF475C7A, 0xFF2E6B4E,
            "Des collines verdoyantes où la rosée brille comme de l'ambre.",
            listOf("Moulin des Vents", "Pont de Pierre", "Clairière des Lucioles", "Belvédère de l'Aube")
        ),
        WorldBiome(
            1, "Forêt des Lanternes Suspendues", 3_000, 8_000,
            0xFF10242A, 0xFF1F4E54, 0xFF1F543D,
            "Des chênes géants éclairés par des lanternes de verre soufflé.",
            listOf("Sentier des Mousses", "Arbre-Bibliothèque", "Ruisseau Chantant", "Sanctuaire des Hiboux")
        ),
        WorldBiome(
            2, "Ponts d'Opale & Cascades", 8_000, 15_000,
            0xFF12304C, 0xFF28698F, 0xFF2B6777,
            "Des arches cristallines surplombant des chutes d'eau turquoise.",
            listOf("Arche de Cristal", "Grotte d'Écume", "Belvédère Arc-en-Ciel", "Écluse Ancienne")
        ),
        WorldBiome(
            3, "Cité Marchande de Solis", 15_000, 25_000,
            0xFF2D1E3E, 0xFF8C4A5E, 0xFF8A5A44,
            "Une ville dorée aux marchés d'épices, d'horloges et de cartes rares.",
            listOf("Porte du Levant", "Bazar des Sabliers", "Tour des Horlogers", "Taverne du Compas")
        ),
        WorldBiome(
            4, "Dunes de Verre Chantant", 25_000, 38_000,
            0xFF2A1B3D, 0xFFA45D5D, 0xFFB87B4B,
            "Un désert scintillant où le vent fait vibrer les grains de quartz.",
            listOf("Oasis Mirage", "Obélisque Solaire", "Canyon de Quartz", "Caravansérail d'Or")
        ),
        WorldBiome(
            5, "Cimes des Aurores", 38_000, 55_000,
            0xFF0C1836, 0xFF1F5C6E, 0xFF476985,
            "Des sommets enneigés caressés par des rideaux d'aurores boréales.",
            listOf("Col du Givre", "Refuge des Étoiles", "Pont Suspendu", "Observatoire Boréal")
        ),
        WorldBiome(
            6, "Citadelle des Étoiles", 55_000, 80_000,
            0xFF0A0D24, 0xFF33266B, 0xFF2B284C,
            "La cité céleste où les voyageurs inscrivent leur nom dans les constellations.",
            listOf("Escalier Astral", "Jardin de Lune", "Salle des Cartographes", "Couronne Stellaire")
        )
    )

    val RELIC_CATALOG: List<RelicEntry> = listOf(
        RelicEntry("r1", "Boussole d'Ambre", "🧭", "Commune", "Vallée des Brumes Dorées", "Indique toujours le sentier le plus paisible."),
        RelicEntry("r2", "Plume de Luciole", "🪶", "Commune", "Vallée des Brumes Dorées", "Émet une douce lueur quand on lit le soir."),
        RelicEntry("r3", "Lanterne de Mousse", "🏮", "Rare", "Forêt des Lanternes Suspendues", "Une petite flamme verte qui ne s'éteint jamais."),
        RelicEntry("r4", "Gland d'Argent", "🌰", "Commune", "Forêt des Lanternes Suspendues", "Porte-bonheur des anciens rôdeurs."),
        RelicEntry("r5", "Éclat d'Opale Liquide", "💎", "Rare", "Ponts d'Opale & Cascades", "Renferme le bruit apaisant d'une cascade."),
        RelicEntry("r6", "Coquillage d'Écho", "🐚", "Commune", "Ponts d'Opale & Cascades", "Garde en mémoire les chansons de voyage."),
        RelicEntry("r7", "Sablier de Solis", "⏳", "Épique", "Cité Marchande de Solis", "Le sable y coule à l'envers quand on sourit."),
        RelicEntry("r8", "Pièce du Soleil Levant", "🪙", "Commune", "Cité Marchande de Solis", "Frappée il y a trois siècles par la guilde des marcheurs."),
        RelicEntry("r9", "Prisme des Dunes", "🔮", "Rare", "Dunes de Verre Chantant", "Décompose la lumière du crépuscule en mélodie."),
        RelicEntry("r10", "Rose de Quartz", "🌹", "Épique", "Dunes de Verre Chantant", "Sculptée naturellement par les vents du désert."),
        RelicEntry("r11", "Étoile de Givre", "❄️", "Rare", "Cimes des Aurores", "Ne fond jamais dans la paume d'un voyageur fidèle."),
        RelicEntry("r12", "Clé de l'Aurore", "🗝️", "Légendaire", "Cimes des Aurores", "Ouvre les portes secrètes des observatoires oubliés."),
        RelicEntry("r13", "Astrolabe Céleste", "✨", "Légendaire", "Citadelle des Étoiles", "Relie chaque pas sur Terre à une étoile du ciel."),
        RelicEntry("r14", "Sceau des Constellations", "🌟", "Légendaire", "Citadelle des Étoiles", "Emblème suprême des grands explorateurs.")
    )

    fun dayKey(nowMs: Long = System.currentTimeMillis()): String {
        return Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }

    fun currentHour(nowMs: Long = System.currentTimeMillis()): Int {
        return Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).hour
    }

    fun currentDayPeriodFr(hour: Int = currentHour()): String = when (hour) {
        in 5..11 -> "Matin — Préparation du sac"
        in 12..17 -> "Midi — Marche & Ravitaillement"
        else -> "Soir — Feu de camp & Lettre"
    }

    fun biomeForSteps(totalSteps: Int): WorldBiome {
        val looped = totalSteps % 80_000
        return WORLD_BIOMES.lastOrNull { looped >= it.minSteps } ?: WORLD_BIOMES.first()
    }

    fun biomeProgress(totalSteps: Int): Float {
        val b = biomeForSteps(totalSteps)
        val looped = totalSteps % 80_000
        val span = (b.maxSteps - b.minSteps).coerceAtLeast(1)
        return ((looped - b.minSteps).toFloat() / span).coerceIn(0f, 1f)
    }

    fun currentLandmark(totalSteps: Int): String {
        val b = biomeForSteps(totalSteps)
        val pct = biomeProgress(totalSteps)
        val idx = (pct * b.landmarks.size).toInt().coerceIn(0, b.landmarks.lastIndex)
        return b.landmarks[idx]
    }

    fun ensureDayRollover(state: StepQuestState, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        val today = dayKey(nowMs)
        if (state.todayDayKey == today) return state
        val nextStreak = if (state.todayDayKey.isEmpty()) 1 else if (state.todaySteps >= 200) state.streakDays + 1 else 1
        return state.copy(
            todayDayKey = today,
            todaySteps = 0,
            mealsFedToday = emptyList(),
            streakDays = nextStreak,
            energy = (state.energy + 30f).coerceAtMost(100f),
            health = (state.health + 20f).coerceAtMost(100f),
            lastUpdatedMs = nowMs
        )
    }

    fun togglePackGear(state: StepQuestState, gear: GearItem, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        val s = ensureDayRollover(state, nowMs)
        val nextGear = if (gear.id in s.packedGearIds) {
            s.packedGearIds - gear.id
        } else {
            if (s.packedGearIds.size >= 3) s.packedGearIds.drop(1) + gear.id else s.packedGearIds + gear.id
        }
        return s.copy(packedGearIds = nextGear, lastUpdatedMs = nowMs)
    }

    fun confirmMorningPack(state: StepQuestState, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        val s = ensureDayRollover(state, nowMs)
        val today = dayKey(nowMs)
        val already = s.morningPackDoneDayKey == today
        val gearObjects = s.packedGearIds.mapNotNull { id -> GearItem.entries.find { it.id == id } }
        val moraleBonus = gearObjects.sumOf { it.moraleBoost.toDouble() }.toFloat()
        val gearNames = gearObjects.joinToString(", ") { it.labelFr }.ifEmpty { "équipement léger" }

        val event = WorldEventLog(
            timestampMs = nowMs,
            emoji = "🎒",
            title = "Sac prêt pour l'expédition !",
            description = "${s.adventurerName} part avec : $gearNames."
        )
        return s.copy(
            morningPackDoneDayKey = today,
            morale = (s.morale + 12f + moraleBonus * 0.4f).coerceAtMost(100f),
            xp = s.xp + if (already) 0 else 25,
            coins = s.coins + if (already) 0 else 10,
            events = (listOf(event) + s.events).take(50),
            lastUpdatedMs = nowMs
        )
    }

    fun feedProvision(state: StepQuestState, item: ProvisionItem, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        val s = ensureDayRollover(state, nowMs)
        if (s.coins < item.costCoins) return s
        val today = dayKey(nowMs)
        val event = WorldEventLog(
            timestampMs = nowMs,
            emoji = item.emoji,
            title = "Ravitaillement : ${item.labelFr}",
            description = "${s.adventurerName} reprend des forces (+${item.energyGain.toInt()} Énergie, +${item.moraleGain.toInt()} Moral)."
        )
        return s.copy(
            coins = s.coins - item.costCoins,
            health = (s.health + item.healthGain).coerceAtMost(100f),
            energy = (s.energy + item.energyGain).coerceAtMost(100f),
            morale = (s.morale + item.moraleGain).coerceAtMost(100f),
            xp = s.xp + 15,
            middayMealDoneDayKey = today,
            mealsFedToday = (s.mealsFedToday + item.labelFr).takeLast(5),
            events = (listOf(event) + s.events).take(50),
            lastUpdatedMs = nowMs
        )
    }

    fun restAtCamp(state: StepQuestState, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        val s = ensureDayRollover(state, nowMs)
        val event = WorldEventLog(
            timestampMs = nowMs,
            emoji = "🔥",
            title = "Pause au feu de camp",
            description = "${s.adventurerName} souffle un instant, soigne ses ampoules et contemple l'horizon."
        )
        return s.copy(
            health = (s.health + 22f).coerceAtMost(100f),
            energy = (s.energy + 28f).coerceAtMost(100f),
            morale = (s.morale + 10f).coerceAtMost(100f),
            events = (listOf(event) + s.events).take(50),
            lastUpdatedMs = nowMs
        )
    }

    fun advanceSteps(
        state: StepQuestState,
        deltaSteps: Int,
        nowMs: Long = System.currentTimeMillis()
    ): StepQuestState {
        if (deltaSteps <= 0) return state
        val s = ensureDayRollover(state, nowMs)
        val prevBiome = biomeForSteps(s.totalSteps)
        val newTotal = s.totalSteps + deltaSteps
        val newToday = s.todaySteps + deltaSteps
        val nextBiome = biomeForSteps(newTotal)

        val gear = s.packedGearIds.mapNotNull { id -> GearItem.entries.find { it.id == id } }
        val energySave = gear.sumOf { it.energySave.toDouble() }.toFloat().coerceAtMost(0.55f)
        val relicBonus = gear.sumOf { it.relicBoost.toDouble() }.toFloat()

        val xpGain = ((deltaSteps / 15f) * (if (GearItem.COPPER_COMPASS.id in s.packedGearIds) 1.15f else 1f)).roundToInt().coerceAtLeast(1)
        val coinsGain = (deltaSteps / 40).coerceAtLeast(if (deltaSteps >= 25) 1 else 0)
        val energyLoss = (deltaSteps / 140f) * (1f - energySave)

        val nextXp = s.xp + xpGain
        val nextLevel = 1 + (nextXp / 250)

        val newEvents = mutableListOf<WorldEventLog>()
        var updatedRelics = s.discoveredRelics

        if (nextBiome.index != prevBiome.index) {
            newEvents += WorldEventLog(
                timestampMs = nowMs,
                emoji = "🗺️",
                title = "Nouveau biome atteint : ${nextBiome.nameFr} !",
                description = nextBiome.descriptionFr
            )
        }

        // Chance de rencontre ou découverte de relique à chaque palier de 250 pas
        val oldMilestones = s.totalSteps / 250
        val newMilestones = newTotal / 250
        if (newMilestones > oldMilestones) {
            val undiscovered = RELIC_CATALOG.filter { r -> updatedRelics.none { it.id == r.id } }
            val chance = (0.28f + relicBonus).coerceAtMost(0.75f)
            if (undiscovered.isNotEmpty() && Random.nextFloat() < chance) {
                val preferred = undiscovered.filter { it.biomeName == nextBiome.nameFr }
                val picked = (if (preferred.isNotEmpty()) preferred else undiscovered).random().copy(foundAtMs = nowMs)
                updatedRelics = updatedRelics + picked
                newEvents += WorldEventLog(
                    timestampMs = nowMs,
                    emoji = picked.emoji,
                    title = "Relique trouvée : ${picked.nameFr} (${picked.rarityFr})",
                    description = picked.loreFr
                )
            } else {
                val landmark = currentLandmark(newTotal)
                val encounters = listOf(
                    "Un renard curieux a accompagné ${s.adventurerName} près de $landmark.",
                    "Un marchand itinérant a partagé une carte secrète près de $landmark.",
                    "${s.adventurerName} a découvert une pierre gravée ancienne à $landmark.",
                    "Un vent doux a porté un parfum d'épices sur le sentier de $landmark."
                )
                newEvents += WorldEventLog(
                    timestampMs = nowMs,
                    emoji = "✨",
                    title = "Étape : $landmark",
                    description = encounters.random()
                )
            }
        }

        return s.copy(
            totalSteps = newTotal,
            todaySteps = newToday,
            xp = nextXp,
            level = nextLevel,
            coins = s.coins + coinsGain,
            energy = (s.energy - energyLoss).coerceIn(15f, 100f),
            morale = (s.morale + (deltaSteps / 400f)).coerceIn(20f, 100f),
            discoveredRelics = updatedRelics,
            events = (newEvents + s.events).take(50),
            lastUpdatedMs = nowMs
        )
    }

    fun selectAdventurer(
        state: StepQuestState,
        cls: AdventurerClass,
        customName: String,
        cloakHex: Long
    ): StepQuestState {
        return state.copy(
            adventurerClass = cls,
            adventurerName = customName.trim().ifEmpty { cls.defaultName }.take(20),
            customCloakHex = cloakHex
        )
    }

    fun buildLetterPrompt(state: StepQuestState): String {
        val biome = biomeForSteps(state.totalSteps)
        val landmark = currentLandmark(state.totalSteps)
        val gearText = state.packedGearIds
            .mapNotNull { id -> GearItem.entries.find { it.id == id }?.labelFr }
            .joinToString(", ")
            .ifEmpty { "un petit sac léger" }
        val mealsText = state.mealsFedToday.joinToString(", ").ifEmpty { "quelques baies sauvages" }
        val latestRelic = state.discoveredRelics.lastOrNull()?.nameFr ?: "aucune nouvelle relique mais de superbes souvenirs"
        val km = String.format("%.1f", state.todaySteps * 0.00075f)

        return """<start_of_turn>user
Tu es ${state.adventurerName}, ${state.adventurerClass.titleFr}. Ta personnalité : ${state.adventurerClass.personalityFr}.
Écris une lettre de voyage chaleureuse et immersive en français (120 à 170 mots) adressée à ton compagnon humain ce soir au feu de camp.
Données réelles de la journée :
- Pas parcourus ensemble aujourd'hui : ${state.todaySteps} pas (environ $km km)
- Lieu atteint : $landmark dans ${biome.nameFr} (${biome.descriptionFr})
- Équipement dans le sac ce matin : $gearText
- Provisions reçues : $mealsText
- Dernière relique découverte : $latestRelic
- Moral : ${state.morale.toInt()}%, Énergie : ${state.energy.toInt()}%

Commence par « Cher compagnon, » et termine par ta signature « — ${state.adventurerName} ». Sois vivant, poétique et remercie-le pour sa marche.<end_of_turn>
<start_of_turn>model
"""
    }

    fun buildChatPrompt(state: StepQuestState, userMessage: String): String {
        val biome = biomeForSteps(state.totalSteps)
        val landmark = currentLandmark(state.totalSteps)
        val gearText = state.packedGearIds
            .mapNotNull { id -> GearItem.entries.find { it.id == id }?.labelFr }
            .joinToString(", ")
        val recentHistory = state.campChat.takeLast(4).joinToString("\n") { msg ->
            if (msg.fromUser) "Voyageur : ${msg.text}" else "${state.adventurerName} : ${msg.text}"
        }

        return """<start_of_turn>user
Tu incarnes ${state.adventurerName}, ${state.adventurerClass.titleFr} (${state.adventurerClass.personalityFr}).
Tu voyages dans un monde fantastique au rythme des vrais pas de ton ami.
Contexte actuel :
- Lieu : $landmark (${biome.nameFr})
- Pas du jour : ${state.todaySteps} pas (Total : ${state.totalSteps} pas, Niveau ${state.level})
- Sac du jour : $gearText
- Santé ${state.health.toInt()}%, Énergie ${state.energy.toInt()}%, Moral ${state.morale.toInt()}%
Historique récent :
$recentHistory

Réponds en français naturel, chaleureux et immersif (2 à 4 phrases maximum) à ce message :
"$userMessage"<end_of_turn>
<start_of_turn>model
"""
    }

    fun buildFallbackLetter(state: StepQuestState): String {
        val biome = biomeForSteps(state.totalSteps)
        val landmark = currentLandmark(state.totalSteps)
        val gear = state.packedGearIds.mapNotNull { id -> GearItem.entries.find { it.id == id }?.labelFr }.joinToString(" et ").ifEmpty { "mon bâton de marche" }
        val meals = state.mealsFedToday.lastOrNull() ?: "une poignée de baies"
        return "Cher compagnon,\n\nQuel périple aujourd'hui ! Grâce à tes ${state.todaySteps} pas, j'ai atteint $landmark au cœur de ${biome.nameFr}. " +
            "Heurement que tu avais glissé $gear dans mon sac ce matin, cela m'a servi tout au long du sentier ! " +
            "À midi, $meals m'a redonné toute mon énergie pour admirer les lueurs du crépuscule. " +
            "Je m'installe près du feu de camp pour reposer mes bottes. Demain, de nouveaux horizons nous attendent !\n\n— ${state.adventurerName}"
    }
}
