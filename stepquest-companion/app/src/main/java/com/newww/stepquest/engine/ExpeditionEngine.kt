package com.newww.stepquest.engine

import com.newww.stepquest.model.*
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

object ExpeditionEngine {

    val WORLD_BIOMES: List<WorldBiome> = listOf(
        WorldBiome(0, "Vallée des Brumes Dorées", 0, 3_000, 0xFF1A2844, 0xFF475C7A, 0xFF2E6B4E, "Des collines verdoyantes où la rosée brille comme de l'ambre.", listOf("Moulin des Vents", "Pont de Pierre", "Clairière des Lucioles", "Belvédère de l'Aube")),
        WorldBiome(1, "Forêt des Lanternes Suspendues", 3_000, 8_000, 0xFF10242A, 0xFF1F4E54, 0xFF1F543D, "Des chênes géants éclairés par des lanternes de verre soufflé.", listOf("Sentier des Mousses", "Arbre-Bibliothèque", "Ruisseau Chantant", "Sanctuaire des Hiboux")),
        WorldBiome(2, "Ponts d'Opale & Cascades", 8_000, 15_000, 0xFF12304C, 0xFF28698F, 0xFF2B6777, "Des arches cristallines surplombant des chutes d'eau turquoise.", listOf("Arche de Cristal", "Grotte d'Écume", "Belvédère Arc-en-Ciel", "Écluse Ancienne")),
        WorldBiome(3, "Cité Marchande de Solis", 15_000, 25_000, 0xFF2D1E3E, 0xFF8C4A5E, 0xFF8A5A44, "Une ville dorée aux marchés d'épices, d'horloges et de cartes rares.", listOf("Porte du Levant", "Bazar des Sabliers", "Tour des Horlogers", "Taverne du Compas")),
        WorldBiome(4, "Dunes de Verre Chantant", 25_000, 38_000, 0xFF2A1B3D, 0xFFA45D5D, 0xFFB87B4B, "Un désert scintillant où le vent fait vibrer les grains de quartz.", listOf("Oasis Mirage", "Obélisque Solaire", "Canyon de Quartz", "Caravansérail d'Or")),
        WorldBiome(5, "Cimes des Aurores", 38_000, 55_000, 0xFF0C1836, 0xFF1F5C6E, 0xFF476985, "Des sommets enneigés caressés par des rideaux d'aurores boréales.", listOf("Col du Givre", "Refuge des Étoiles", "Pont Suspendu", "Observatoire Boréal")),
        WorldBiome(6, "Citadelle des Étoiles", 55_000, 80_000, 0xFF0A0D24, 0xFF33266B, 0xFF2B284C, "La cité céleste où les voyageurs inscrivent leur nom dans les constellations.", listOf("Escalier Astral", "Jardin de Lune", "Salle des Cartographes", "Couronne Stellaire"))
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

    val ENCOUNTER_POOL: List<InteractiveEncounter> = listOf(
        InteractiveEncounter(
            "enc_bridge", "🌉", "Le Pont des Murmures",
            "Un vieux pont de corde surplombe une gorge lumineuse. En contrebas scintille une grotte d'herbes rares.",
            listOf(
                EncounterChoice("Traverser d'un pas vif", "Passage réussi avec panache ! Une bourse oubliée pendait au pilier.", xpDelta = 30, coinsDelta = 18, moraleDelta = 10f),
                EncounterChoice("Descendre cueillir les herbes", "Des Herbes Lunaires intactes ! Le familier frétille de fierté.", xpDelta = 25, coinsDelta = 8, rewardIngredient = IngredientType.HERB, relicChance = 0.40f),
                EncounterChoice("Graver nos initiales & souffler", "Un moment paisible qui recharge le moral et l'énergie.", xpDelta = 20, coinsDelta = 5, moraleDelta = 18f, energyDelta = 15f)
            )
        ),
        InteractiveEncounter(
            "enc_merchant", "🐪", "Le Marchand d'Horloges",
            "Un marchand itinérant vous propose d'échanger une histoire de ta journée contre une curiosité de sa charrette.",
            listOf(
                EncounterChoice("Lui raconter notre marche", "Ravi, il t'offre un éclat de cristal rare et quelques pièces !", xpDelta = 30, coinsDelta = 14, rewardIngredient = IngredientType.CRYSTAL, relicChance = 0.45f),
                EncounterChoice("Partager un thé chaud ensemble", "Conversation chaleureuse : moral et énergie au sommet !", xpDelta = 20, coinsDelta = 6, moraleDelta = 22f, energyDelta = 18f),
                EncounterChoice("Aider à réparer sa roue", "Il te remercie généreusement en bois précieux et or.", xpDelta = 35, coinsDelta = 24, rewardIngredient = IngredientType.WOOD)
            )
        ),
        InteractiveEncounter(
            "enc_shrine", "⛩️", "Sanctuaire des Lucioles",
            "Au détour du sentier apparaît une arche couverte de mousse où dansent des dizaines de lucioles dorées.",
            listOf(
                EncounterChoice("Suivre les lucioles hors sentier", "Elles te mènent droit vers un recoin secret plein de trésors !", xpDelta = 40, coinsDelta = 20, rewardIngredient = IngredientType.CRYSTAL, relicChance = 0.55f),
                EncounterChoice("Récolter le miel doré voisin", "Un pot de Miel Sauvage parfumé rejoint ton sac.", xpDelta = 24, coinsDelta = 10, rewardIngredient = IngredientType.HONEY, moraleDelta = 12f),
                EncounterChoice("Méditer quelques instants", "Une sérénité lumineuse apaise toute fatigue.", xpDelta = 20, coinsDelta = 8, energyDelta = 24f, moraleDelta = 16f)
            )
        ),
        InteractiveEncounter(
            "enc_fox", "🦊", "Voyageur Égaré",
            "Un jeune cartographe cherche son chemin vers le prochain belvédère avant la tombée du soir.",
            listOf(
                EncounterChoice("Lui prêter notre boussole", "Il te récompense d'une pièce ancienne et d'une carte annotée !", xpDelta = 35, coinsDelta = 22, relicChance = 0.42f),
                EncounterChoice("Lui offrir des herbes de route", "En retour, il partage son miel et sa bonne humeur.", xpDelta = 28, coinsDelta = 12, rewardIngredient = IngredientType.HONEY, moraleDelta = 18f),
                EncounterChoice("Marcher un bout ensemble", "Belle camaraderie sur le chemin !", xpDelta = 32, coinsDelta = 14, moraleDelta = 15f, energyDelta = 10f)
            )
        ),
        InteractiveEncounter(
            "enc_ruins", "🏛️", "Arche Runique Ensevelie",
            "Des inscriptions brillantes pulsent sous les racines d'un arbre millénaire.",
            listOf(
                EncounterChoice("Déchiffrer les runes", "Les glyphes révèlent une cache ancienne !", xpDelta = 45, coinsDelta = 20, rewardIngredient = IngredientType.CRYSTAL, relicChance = 0.60f),
                EncounterChoice("Ramasser le bois runique", "Du Bois Ancien de première qualité pour le campement.", xpDelta = 25, coinsDelta = 10, rewardIngredient = IngredientType.WOOD),
                EncounterChoice("Croquer la scène dans le carnet", "Superbe souvenir pour la lettre de ce soir !", xpDelta = 30, coinsDelta = 12, moraleDelta = 20f)
            )
        )
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
        else -> "Soir — Feu de camp & Lettre IA"
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
            choicesMadeToday = emptyList(),
            claimedStepMilestonesToday = emptyList(),
            streakDays = nextStreak,
            energy = (state.energy + 30f).coerceAtMost(100f),
            health = (state.health + 20f).coerceAtMost(100f),
            lastUpdatedMs = nowMs
        )
    }

    fun triggerEncounter(state: StepQuestState): StepQuestState {
        if (state.activeEncounter != null) return state
        val picked = ENCOUNTER_POOL[Random.nextInt(ENCOUNTER_POOL.size)]
        return state.copy(activeEncounter = picked)
    }

    fun resolveEncounterChoice(
        state: StepQuestState,
        choiceIndex: Int,
        nowMs: Long = System.currentTimeMillis()
    ): StepQuestState {
        val enc = state.activeEncounter ?: return state
        val choice = enc.choices.getOrNull(choiceIndex) ?: return state
        val biome = biomeForSteps(state.totalSteps)

        val nextIngredients = state.ingredients.toMutableMap()
        choice.rewardIngredient?.let { ing ->
            val bonus = if (state.familiar == FamiliarCompanion.FIREFLY) 2 else 1
            nextIngredients[ing] = (nextIngredients[ing] ?: 0) + bonus
        }

        var nextRelics = state.discoveredRelics
        val extraEvents = mutableListOf(
            WorldEventLog(nowMs, enc.emoji, "${enc.titleFr} — ${choice.labelFr}", choice.outcomeTextFr)
        )

        val relicBoost = if (state.familiar == FamiliarCompanion.OWL) 0.15f else 0f
        val undiscovered = RELIC_CATALOG.filter { r -> nextRelics.none { it.id == r.id } }
        if (undiscovered.isNotEmpty() && Random.nextFloat() < (choice.relicChance + relicBoost)) {
            val preferred = undiscovered.filter { it.biomeName == biome.nameFr }
            val found = (if (preferred.isNotEmpty()) preferred else undiscovered).random().copy(foundAtMs = nowMs)
            nextRelics = nextRelics + found
            extraEvents += WorldEventLog(nowMs, found.emoji, "Relique trouvée : ${found.nameFr} !", found.loreFr)
        }

        val coinMult = if (state.familiar == FamiliarCompanion.FOX) 1.15f else 1.0f
        val nextXp = state.xp + choice.xpDelta

        return state.copy(
            activeEncounter = null,
            xp = nextXp,
            level = 1 + (nextXp / 250),
            coins = state.coins + (choice.coinsDelta * coinMult).roundToInt(),
            morale = (state.morale + choice.moraleDelta).coerceIn(15f, 100f),
            energy = (state.energy + choice.energyDelta).coerceIn(15f, 100f),
            ingredients = nextIngredients,
            discoveredRelics = nextRelics,
            choicesMadeToday = (state.choicesMadeToday + "${enc.titleFr} (${choice.labelFr})").takeLast(6),
            events = (extraEvents + state.events).take(50),
            lastUpdatedMs = nowMs
        )
    }

    fun collectMapTreasure(state: StepQuestState, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        val ing = IngredientType.entries[Random.nextInt(IngredientType.entries.size)]
        val nextIngs = state.ingredients.toMutableMap()
        val qty = if (state.familiar == FamiliarCompanion.FIREFLY) 2 else 1
        nextIngs[ing] = (nextIngs[ing] ?: 0) + qty
        val coinsGain = 5 + Random.nextInt(8)
        return state.copy(
            coins = state.coins + coinsGain,
            xp = state.xp + 12,
            level = 1 + ((state.xp + 12) / 250),
            morale = (state.morale + 4f).coerceAtMost(100f),
            ingredients = nextIngs,
            events = (listOf(
                WorldEventLog(nowMs, ing.emoji, "Trésor ramassé sur le sentier !", "+$qty ${ing.labelFr} et +$coinsGain pièces.")
            ) + state.events).take(50)
        )
    }

    fun craftRecipe(state: StepQuestState, recipe: CraftRecipe, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        val inv = state.ingredients
        if ((inv[IngredientType.WOOD] ?: 0) < recipe.costWood ||
            (inv[IngredientType.HERB] ?: 0) < recipe.costHerb ||
            (inv[IngredientType.CRYSTAL] ?: 0) < recipe.costCrystal ||
            (inv[IngredientType.HONEY] ?: 0) < recipe.costHoney
        ) return state

        val nextInv = inv.toMutableMap().apply {
            put(IngredientType.WOOD, (get(IngredientType.WOOD) ?: 0) - recipe.costWood)
            put(IngredientType.HERB, (get(IngredientType.HERB) ?: 0) - recipe.costHerb)
            put(IngredientType.CRYSTAL, (get(IngredientType.CRYSTAL) ?: 0) - recipe.costCrystal)
            put(IngredientType.HONEY, (get(IngredientType.HONEY) ?: 0) - recipe.costHoney)
        }

        var next = state.copy(ingredients = nextInv)
        next = when (recipe) {
            CraftRecipe.VITAL_ELIXIR -> next.copy(
                health = (next.health + 45f).coerceAtMost(100f),
                energy = (next.energy + 45f).coerceAtMost(100f),
                xp = next.xp + 25
            )
            CraftRecipe.ROYAL_FEAST -> next.copy(
                morale = (next.morale + 50f).coerceAtMost(100f),
                energy = (next.energy + 40f).coerceAtMost(100f),
                xp = next.xp + 40
            )
            CraftRecipe.SOLAR_CHARM -> next.copy(
                coins = next.coins + 35,
                xp = next.xp + 120
            )
            CraftRecipe.SECRET_MAP -> {
                val withEnc = triggerEncounter(next.copy(xp = next.xp + 80))
                withEnc
            }
        }
        return next.copy(
            level = 1 + (next.xp / 250),
            events = (listOf(WorldEventLog(nowMs, recipe.emoji, "Atelier : ${recipe.labelFr} fabriqué !", recipe.descFr)) + next.events).take(50),
            lastUpdatedMs = nowMs
        )
    }

    fun claimStepMilestone(state: StepQuestState, targetSteps: Int, rewardCoins: Int): StepQuestState {
        if (state.todaySteps < targetSteps || targetSteps in state.claimedStepMilestonesToday) return state
        return state.copy(
            coins = state.coins + rewardCoins,
            xp = state.xp + 35,
            level = 1 + ((state.xp + 35) / 250),
            claimedStepMilestonesToday = state.claimedStepMilestonesToday + targetSteps
        )
    }

    fun togglePackGear(state: StepQuestState, gear: GearItem, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        val s = ensureDayRollover(state, nowMs)
        val nextGear = if (gear.id in s.packedGearIds) s.packedGearIds - gear.id
        else if (s.packedGearIds.size >= 3) s.packedGearIds.drop(1) + gear.id else s.packedGearIds + gear.id
        return s.copy(packedGearIds = nextGear, lastUpdatedMs = nowMs)
    }

    fun confirmMorningPack(state: StepQuestState, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        val s = ensureDayRollover(state, nowMs)
        val today = dayKey(nowMs)
        val already = s.morningPackDoneDayKey == today
        val gearObjects = s.packedGearIds.mapNotNull { id -> GearItem.entries.find { it.id == id } }
        val moraleBonus = gearObjects.sumOf { it.moraleBoost.toDouble() }.toFloat()
        val gearNames = gearObjects.joinToString(", ") { it.labelFr }.ifEmpty { "équipement léger" }
        return s.copy(
            morningPackDoneDayKey = today,
            morale = (s.morale + 12f + moraleBonus * 0.4f).coerceAtMost(100f),
            xp = s.xp + if (already) 0 else 25,
            coins = s.coins + if (already) 0 else 10,
            events = (listOf(WorldEventLog(nowMs, "🎒", "Sac prêt pour l'expédition !", "${s.adventurerName} part avec : $gearNames.")) + s.events).take(50),
            lastUpdatedMs = nowMs
        )
    }

    fun feedProvision(state: StepQuestState, item: ProvisionItem, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        val s = ensureDayRollover(state, nowMs)
        if (s.coins < item.costCoins) return s
        return s.copy(
            coins = s.coins - item.costCoins,
            health = (s.health + item.healthGain).coerceAtMost(100f),
            energy = (s.energy + item.energyGain).coerceAtMost(100f),
            morale = (s.morale + item.moraleGain).coerceAtMost(100f),
            xp = s.xp + 15,
            middayMealDoneDayKey = dayKey(nowMs),
            mealsFedToday = (s.mealsFedToday + item.labelFr).takeLast(5),
            events = (listOf(WorldEventLog(nowMs, item.emoji, "Ravitaillement : ${item.labelFr}", "${s.adventurerName} reprend des forces.")) + s.events).take(50),
            lastUpdatedMs = nowMs
        )
    }

    fun restAtCamp(state: StepQuestState, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        val s = ensureDayRollover(state, nowMs)
        return s.copy(
            health = (s.health + 22f).coerceAtMost(100f),
            energy = (s.energy + 28f).coerceAtMost(100f),
            morale = (s.morale + 10f).coerceAtMost(100f),
            events = (listOf(WorldEventLog(nowMs, "🔥", "Pause au feu de camp", "${s.adventurerName} et ${s.familiar.labelFr} se reposent près des braises.")) + s.events).take(50),
            lastUpdatedMs = nowMs
        )
    }

    fun advanceSteps(state: StepQuestState, deltaSteps: Int, nowMs: Long = System.currentTimeMillis()): StepQuestState {
        if (deltaSteps <= 0) return state
        val s = ensureDayRollover(state, nowMs)
        val today = dayKey(nowMs)
        val prevBiome = biomeForSteps(s.totalSteps)
        val newTotal = s.totalSteps + deltaSteps
        val newToday = s.todaySteps + deltaSteps
        val nextBiome = biomeForSteps(newTotal)

        val gear = s.packedGearIds.mapNotNull { id -> GearItem.entries.find { it.id == id } }
        val wolfSave = if (s.familiar == FamiliarCompanion.WOLF) 0.15f else 0f
        val energySave = (gear.sumOf { it.energySave.toDouble() }.toFloat() + wolfSave).coerceAtMost(0.65f)
        val relicBonus = gear.sumOf { it.relicBoost.toDouble() }.toFloat() + if (s.familiar == FamiliarCompanion.OWL) 0.15f else 0f

        val xpGain = ((deltaSteps / 15f) * (if (GearItem.COPPER_COMPASS.id in s.packedGearIds) 1.15f else 1f)).roundToInt().coerceAtLeast(1)
        val coinsGain = ((deltaSteps / 35f) * (if (s.familiar == FamiliarCompanion.FOX) 1.15f else 1f)).roundToInt().coerceAtLeast(if (deltaSteps >= 25) 1 else 0)
        val energyLoss = (deltaSteps / 150f) * (1f - energySave)

        val nextXp = s.xp + xpGain
        val nextLevel = 1 + (nextXp / 250)
        val nextWeekly = (s.weeklySteps + (today to newToday))
            .entries.sortedByDescending { it.key }.take(7).associate { it.key to it.value }

        val newEvents = mutableListOf<WorldEventLog>()
        var updatedRelics = s.discoveredRelics
        val nextIngs = s.ingredients.toMutableMap()

        if (nextBiome.index != prevBiome.index) {
            newEvents += WorldEventLog(nowMs, "🗺️", "Nouveau biome : ${nextBiome.nameFr} !", nextBiome.descriptionFr)
        }

        val oldMilestones = s.totalSteps / 250
        val newMilestones = newTotal / 250
        if (newMilestones > oldMilestones) {
            val foundIng = IngredientType.entries[Random.nextInt(IngredientType.entries.size)]
            nextIngs[foundIng] = (nextIngs[foundIng] ?: 0) + 1
            val undiscovered = RELIC_CATALOG.filter { r -> updatedRelics.none { it.id == r.id } }
            val chance = (0.28f + relicBonus).coerceAtMost(0.75f)
            if (undiscovered.isNotEmpty() && Random.nextFloat() < chance) {
                val preferred = undiscovered.filter { it.biomeName == nextBiome.nameFr }
                val picked = (if (preferred.isNotEmpty()) preferred else undiscovered).random().copy(foundAtMs = nowMs)
                updatedRelics = updatedRelics + picked
                newEvents += WorldEventLog(nowMs, picked.emoji, "Relique trouvée : ${picked.nameFr}", picked.loreFr)
            }
        }

        val shouldTriggerEnc = s.activeEncounter == null && (newTotal - s.lastEncounterStepMark) >= 400
        val nextEncounter = if (shouldTriggerEnc) ENCOUNTER_POOL[Random.nextInt(ENCOUNTER_POOL.size)] else s.activeEncounter

        return s.copy(
            totalSteps = newTotal,
            todaySteps = newToday,
            weeklySteps = nextWeekly,
            xp = nextXp,
            level = nextLevel,
            coins = s.coins + coinsGain,
            energy = (s.energy - energyLoss).coerceIn(15f, 100f),
            morale = (s.morale + (deltaSteps / 400f)).coerceIn(20f, 100f),
            ingredients = nextIngs,
            discoveredRelics = updatedRelics,
            activeEncounter = nextEncounter,
            lastEncounterStepMark = if (shouldTriggerEnc) newTotal else s.lastEncounterStepMark,
            events = (newEvents + s.events).take(50),
            lastUpdatedMs = nowMs
        )
    }

    fun selectAdventurer(
        state: StepQuestState,
        cls: AdventurerClass,
        customName: String,
        cloakHex: Long,
        familiar: FamiliarCompanion
    ): StepQuestState {
        return state.copy(
            adventurerClass = cls,
            adventurerName = customName.trim().ifEmpty { cls.defaultName }.take(20),
            customCloakHex = cloakHex,
            familiar = familiar
        )
    }

    /**
     * Format multi-tours Gemma-3 natif (vérifié en français) : réponses précises et directement liées à ta question.
     */
    fun buildLetterPrompt(state: StepQuestState): String {
        val biome = biomeForSteps(state.totalSteps)
        val landmark = currentLandmark(state.totalSteps)
        val gearText = state.packedGearIds.mapNotNull { id -> GearItem.entries.find { it.id == id }?.labelFr }.joinToString(", ").ifEmpty { "un sac léger" }
        val mealsText = state.mealsFedToday.joinToString(", ").ifEmpty { "des baies de la route" }
        val choicesText = state.choicesMadeToday.joinToString(" ; ").ifEmpty { "une belle exploration tranquille" }
        val km = String.format("%.1f", state.todaySteps * 0.00075f)

        return """<start_of_turn>user
Tu es ${state.adventurerName}, ${state.adventurerClass.titleFr}, accompagné de ton familier ${state.familiar.labelFr}. Ta personnalité : ${state.adventurerClass.personalityFr}.
Écris une lettre de voyage chaleureuse et immersive en français (120 à 170 mots) pour ce soir au feu de camp basée sur notre vraie journée :
- Pas parcourus aujourd'hui : ${state.todaySteps} pas ($km km)
- Lieu atteint : $landmark dans ${biome.nameFr}
- Équipement dans ton sac : $gearText
- Provisions reçues : $mealsText
- Péripéties du jour : $choicesText
Commence par « Cher compagnon, » et signe « — ${state.adventurerName} ».<end_of_turn>
<start_of_turn>model
"""
    }

    fun buildChatPrompt(state: StepQuestState, userMessage: String): String {
        val biome = biomeForSteps(state.totalSteps)
        val landmark = currentLandmark(state.totalSteps)
        val sb = StringBuilder()
        sb.append("<start_of_turn>user\n")
        sb.append("Consigne permanente : Tu es ${state.adventurerName}, ${state.adventurerClass.titleFr} (${state.adventurerClass.personalityFr}), accompagné de ${state.familiar.labelFr}.\n")
        sb.append("Lieu actuel : $landmark (${biome.nameFr}). Pas du jour : ${state.todaySteps} (Total : ${state.totalSteps}, Niveau ${state.level}). Santé ${state.health.toInt()}%, Énergie ${state.energy.toInt()}%, Moral ${state.morale.toInt()}%.\n")
        sb.append("Réponds TOUJOURS directement et précisément à la question posée en français naturel (2 à 3 phrases maximum).<end_of_turn>\n")
        sb.append("<start_of_turn>model\nCompris compagnon, je suis près du feu de camp et je t'écoute !<end_of_turn>\n")

        state.campChat.takeLast(6).forEach { msg ->
            if (msg.fromUser) sb.append("<start_of_turn>user\n${msg.text}<end_of_turn>\n")
            else sb.append("<start_of_turn>model\n${msg.text}<end_of_turn>\n")
        }
        sb.append("<start_of_turn>user\n$userMessage<end_of_turn>\n<start_of_turn>model\n")
        return sb.toString()
    }

    fun buildFallbackLetter(state: StepQuestState): String {
        val biome = biomeForSteps(state.totalSteps)
        val landmark = currentLandmark(state.totalSteps)
        val gear = state.packedGearIds.mapNotNull { id -> GearItem.entries.find { it.id == id }?.labelFr }.joinToString(" et ").ifEmpty { "mon bâton de marche" }
        return "Cher compagnon,\n\nQuel périple aujourd'hui ! Grâce à tes ${state.todaySteps} pas, nous avons atteint $landmark dans ${biome.nameFr} avec ${state.familiar.labelFr}. " +
            "Heurement que tu avais préparé $gear dans mon sac ce matin ! Demain, de nouveaux sentiers nous attendent.\n\n— ${state.adventurerName}"
    }
}
