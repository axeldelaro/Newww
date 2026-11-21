package com.newww.luminaterrarium.engine

import com.newww.luminaterrarium.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Moteur déterministe 100 % hors-ligne gérant :
 * - le cycle circadien selon l'heure réelle,
 * - l'impact de la batterie et de la charge,
 * - la conversion des pas réels en Rosée de marche,
 * - la décroissance bienveillante (l'esprit ne meurt jamais),
 * - les évolutions, quêtes quotidiennes, et dialogues contextuels.
 */
object TerrariumSimulationEngine {

    const val STEPS_PER_DEW = 25

    fun currentDayKey(nowMs: Long = System.currentTimeMillis()): String {
        val date = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.toString()
    }

    fun currentHour(nowMs: Long = System.currentTimeMillis()): Int {
        return Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).hour
    }

    fun computeMood(
        spirit: SpiritProfile,
        battery: BatterySnapshot,
        dayPhase: DayPhase
    ): SpiritMood {
        if (!battery.isCharging && battery.levelPercent <= 20) return SpiritMood.LOW_BATTERY
        if (spirit.isSleeping || (dayPhase == DayPhase.NIGHT && spirit.energy < 45f)) return SpiritMood.SLEEPY
        if (spirit.hunger < 28f) return SpiritMood.HUNGRY
        if (spirit.hydration < 28f) return SpiritMood.THIRSTY
        if (spirit.energy < 25f) return SpiritMood.SLEEPY
        if (spirit.happiness >= 90f && spirit.hunger >= 70f && spirit.hydration >= 70f) return SpiritMood.ECSTATIC
        if (spirit.happiness >= 75f && spirit.energy >= 60f) return SpiritMood.PLAYFUL
        if (spirit.happiness >= 55f) return SpiritMood.HAPPY
        return SpiritMood.CALM
    }

    /**
     * Met à jour les besoins écoulés entre [TerrariumAppState.lastTickTimestampMs] et [nowMs].
     */
    fun applyTimeTick(
        state: TerrariumAppState,
        battery: BatterySnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        val elapsedMs = (nowMs - state.lastTickTimestampMs).coerceIn(0L, 48L * 3600L * 1000L)
        val hours = elapsedMs / 3_600_000f
        val dayKey = currentDayKey(nowMs)
        val dayPhase = DayPhase.fromHour(currentHour(nowMs))

        val resetQuest = if (state.dailyQuest.dayKey != dayKey) {
            DailyQuestProgress(dayKey = dayKey)
        } else {
            state.dailyQuest
        }

        if (hours < 0.005f) {
            return state.copy(dailyQuest = resetQuest, lastTickTimestampMs = nowMs)
        }

        val updatedSpirits = state.spirits.mapValues { (element, spirit) ->
            val sleepFactor = if (spirit.isSleeping || dayPhase == DayPhase.NIGHT) 0.45f else 1.0f
            val hungerDecay = 4.2f * hours * sleepFactor
            val hydrationRate = if (element == SpiritElement.WATER) 5.0f else if (element == SpiritElement.EMBER) 2.2f else 3.8f
            val hydrationDecay = hydrationRate * hours * sleepFactor

            val lightMatch = spirit.lightLevel in element.idealLightMin..element.idealLightMax
            val humidityMatch = spirit.hydration in element.idealHumidityMin..element.idealHumidityMax

            val energyDelta = when {
                battery.isCharging -> +9.0f * hours
                !battery.isCharging && battery.levelPercent <= 20 -> -5.0f * hours
                spirit.isSleeping || dayPhase == DayPhase.NIGHT -> +11.0f * hours
                else -> -2.8f * hours
            }

            val happyDelta = when {
                lightMatch && humidityMatch && spirit.hunger > 45f -> +1.5f * hours
                spirit.hunger < 25f || spirit.hydration < 25f -> -4.5f * hours
                else -> -1.2f * hours
            }

            val autoWake = spirit.isSleeping && dayPhase in listOf(DayPhase.MORNING, DayPhase.AFTERNOON) && spirit.energy > 85f
            val autoSleep = !spirit.isSleeping && dayPhase == DayPhase.NIGHT && spirit.energy < 35f

            val nextSpirit = spirit.copy(
                hunger = (spirit.hunger - hungerDecay).coerceIn(10f, 100f),
                hydration = (spirit.hydration - hydrationDecay).coerceIn(10f, 100f),
                energy = (spirit.energy + energyDelta).coerceIn(12f, 100f),
                happiness = (spirit.happiness + happyDelta).coerceIn(18f, 100f),
                isSleeping = when {
                    autoWake -> false
                    autoSleep -> true
                    else -> spirit.isSleeping
                }
            )
            if (element == state.activeElement && hours >= 0.5f) {
                nextSpirit.copy(
                    lastSpeech = pickContextSpeech(nextSpirit, battery, dayPhase),
                    lastSpeechAtMs = nowMs
                )
            } else {
                nextSpirit
            }
        }

        return state.copy(
            spirits = updatedSpirits,
            dailyQuest = resetQuest,
            lastTickTimestampMs = nowMs
        )
    }

    fun petSpirit(
        state: TerrariumAppState,
        battery: BatterySnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        val spirit = state.activeSpirit
        val nextXp = spirit.xp + 6
        val evolved = EvolutionStage.fromXp(nextXp).level > spirit.stage.level
        val updated = spirit.copy(
            happiness = (spirit.happiness + 9f).coerceAtMost(100f),
            affection = (spirit.affection + 3.5f).coerceAtMost(100f),
            xp = nextXp,
            isSleeping = false,
            lastSpeech = pickPetSpeech(spirit, battery, evolved),
            lastSpeechAtMs = nowMs
        )
        val quest = ensureDayQuest(state.dailyQuest, nowMs).let {
            it.copy(petsCount = it.petsCount + 1)
        }
        return commitSpiritUpdate(
            state = state.copy(dailyQuest = quest, dewCurrency = state.dewCurrency + 2),
            updated = updated,
            evolved = evolved,
            nowMs = nowMs
        )
    }

    fun tapGlass(
        state: TerrariumAppState,
        battery: BatterySnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        val spirit = state.activeSpirit
        val speech = when {
            !battery.isCharging && battery.levelPercent <= 20 ->
                "Mmmh… *bâille* ta batterie est basse, je garde mes forces…"
            spirit.isSleeping ->
                "Zzz… oh ? Toc toc ? Encore cinq minutes…"
            else -> listOf(
                "Toc toc ! Je t'ai vu derrière la vitre !",
                "Héhé, ça chatouille les vibrations du bocal !",
                "Tu veux jouer aux lucioles avec moi ?",
                "Je suis là ! Regarde comme le bocal brille."
            ).random()
        }
        val updated = spirit.copy(
            happiness = (spirit.happiness + 2f).coerceAtMost(100f),
            lastSpeech = speech,
            lastSpeechAtMs = nowMs
        )
        return state.copy(spirits = state.spirits + (spirit.element to updated))
    }

    fun feedSpirit(
        state: TerrariumAppState,
        food: FoodType,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        val spirit = state.activeSpirit
        val isFav = spirit.element.favoriteFood == food
        val bonus = if (isFav) 1.35f else 1.0f
        val nextXp = spirit.xp + (food.xpGain * bonus).roundToInt()
        val evolved = EvolutionStage.fromXp(nextXp).level > spirit.stage.level
        val speech = if (isFav) {
            "Oh oui !! ${food.labelFr}, c'est mon péché mignon ! ✨"
        } else {
            "Miam ! ${food.labelFr} était délicieux, merci !"
        }
        val updated = spirit.copy(
            hunger = (spirit.hunger + food.hungerGain * bonus).coerceAtMost(100f),
            energy = (spirit.energy + food.energyGain * bonus).coerceAtMost(100f),
            happiness = (spirit.happiness + food.moodGain * bonus).coerceAtMost(100f),
            affection = (spirit.affection + if (isFav) 4.5f else 2.5f).coerceAtMost(100f),
            xp = nextXp,
            isSleeping = false,
            lastSpeech = speech,
            lastSpeechAtMs = nowMs
        )
        val quest = ensureDayQuest(state.dailyQuest, nowMs).let {
            it.copy(feedsCount = it.feedsCount + 1)
        }
        return commitSpiritUpdate(
            state = state.copy(dailyQuest = quest, dewCurrency = state.dewCurrency + 3),
            updated = updated,
            evolved = evolved,
            nowMs = nowMs
        )
    }

    fun mistTerrarium(
        state: TerrariumAppState,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        val spirit = state.activeSpirit
        val isEmber = spirit.element == SpiritElement.EMBER
        val hydrationGain = if (isEmber) 14f else 28f
        val nextXp = spirit.xp + 8
        val evolved = EvolutionStage.fromXp(nextXp).level > spirit.stage.level
        val speech = if (isEmber) {
            "Pshhh ! Doucement avec l'eau, juste une petite brume pour dépoussiérer !"
        } else {
            "Pshhh… ah quelle fraîcheur ! Les gouttelettes scintillent sur le verre !"
        }
        val updated = spirit.copy(
            hydration = (spirit.hydration + hydrationGain).coerceAtMost(100f),
            happiness = (spirit.happiness + if (isEmber) 4f else 11f).coerceAtMost(100f),
            affection = (spirit.affection + 2f).coerceAtMost(100f),
            xp = nextXp,
            lastSpeech = speech,
            lastSpeechAtMs = nowMs
        )
        val quest = ensureDayQuest(state.dailyQuest, nowMs).let {
            it.copy(mistsCount = it.mistsCount + 1)
        }
        return commitSpiritUpdate(
            state = state.copy(dailyQuest = quest, dewCurrency = state.dewCurrency + 2),
            updated = updated,
            evolved = evolved,
            nowMs = nowMs
        )
    }

    fun setLightLevel(
        state: TerrariumAppState,
        newLight: Float,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        val spirit = state.activeSpirit
        val clamped = newLight.coerceIn(5f, 100f)
        val ideal = clamped in spirit.element.idealLightMin..spirit.element.idealLightMax
        val speech = when {
            clamped < 22f -> "Lumière tamisée… parfait pour se reposer doucement."
            ideal -> "Cette lumière est exactement comme je l'aime ! ✨"
            clamped > 88f && spirit.element != SpiritElement.EMBER -> "Ouh, ça brille fort ici !"
            else -> "J'ajuste mes pétales à cette belle lueur."
        }
        val updated = spirit.copy(
            lightLevel = clamped,
            happiness = (spirit.happiness + if (ideal) 3f else 0f).coerceAtMost(100f),
            lastSpeech = speech,
            lastSpeechAtMs = nowMs
        )
        return state.copy(spirits = state.spirits + (spirit.element to updated))
    }

    fun toggleLullabySleep(
        state: TerrariumAppState,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        val spirit = state.activeSpirit
        val nextSleep = !spirit.isSleeping
        val updated = spirit.copy(
            isSleeping = nextSleep,
            energy = (spirit.energy + if (nextSleep) 16f else 5f).coerceAtMost(100f),
            happiness = (spirit.happiness + 8f).coerceAtMost(100f),
            affection = (spirit.affection + 3f).coerceAtMost(100f),
            lastSpeech = if (nextSleep) {
                "Merci pour la berceuse… bonne nuit, je veille sur tes rêves. 🌙"
            } else {
                "Oh ! J'ai super bien dormi, prêt pour notre journée ! ☀️"
            },
            lastSpeechAtMs = nowMs
        )
        return state.copy(spirits = state.spirits + (spirit.element to updated))
    }

    fun recordStepsDelta(
        state: TerrariumAppState,
        stepsDelta: Int,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        if (stepsDelta <= 0) return state
        val quest = ensureDayQuest(state.dailyQuest, nowMs)
        val previousConvertedUnits = state.totalStepsConverted / STEPS_PER_DEW
        val newTotal = state.totalStepsConverted + stepsDelta
        val newUnits = newTotal / STEPS_PER_DEW
        val dewEarned = (newUnits - previousConvertedUnits).coerceAtLeast(0)

        val spirit = state.activeSpirit
        val xpGain = dewEarned * 2
        val nextXp = spirit.xp + xpGain
        val evolved = EvolutionStage.fromXp(nextXp).level > spirit.stage.level

        val updatedSpirit = if (dewEarned > 0) {
            spirit.copy(
                xp = nextXp,
                happiness = (spirit.happiness + (dewEarned * 0.8f)).coerceAtMost(100f),
                hydration = (spirit.hydration + (dewEarned * 0.5f)).coerceAtMost(100f),
                lastSpeech = "Tes $stepsDelta pas ont fait perler +$dewEarned Rosée dans mon bocal ! 🌿",
                lastSpeechAtMs = nowMs
            )
        } else {
            spirit
        }

        return commitSpiritUpdate(
            state = state.copy(
                dewCurrency = state.dewCurrency + dewEarned,
                totalStepsConverted = newTotal,
                dailyQuest = quest.copy(stepsToday = quest.stepsToday + stepsDelta)
            ),
            updated = updatedSpirit,
            evolved = evolved,
            nowMs = nowMs
        )
    }

    fun completeMiniGame(
        state: TerrariumAppState,
        caughtOrbs: Int,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        val spirit = state.activeSpirit
        val dewReward = (caughtOrbs * 3).coerceIn(5, 60)
        val xpGain = (caughtOrbs * 4).coerceIn(10, 80)
        val nextXp = spirit.xp + xpGain
        val evolved = EvolutionStage.fromXp(nextXp).level > spirit.stage.level
        val updated = spirit.copy(
            happiness = (spirit.happiness + 18f).coerceAtMost(100f),
            affection = (spirit.affection + 5f).coerceAtMost(100f),
            energy = (spirit.energy - 6f).coerceAtLeast(15f),
            xp = nextXp,
            isSleeping = false,
            lastSpeech = "Bravo !! $caughtOrbs lucioles attrapées (+${dewReward} Rosée) ! ✨",
            lastSpeechAtMs = nowMs
        )
        val quest = ensureDayQuest(state.dailyQuest, nowMs).let {
            it.copy(miniGamesWon = it.miniGamesWon + 1)
        }
        return commitSpiritUpdate(
            state = state.copy(
                dewCurrency = state.dewCurrency + dewReward,
                dailyQuest = quest
            ),
            updated = updated,
            evolved = evolved,
            nowMs = nowMs,
            extraJournal = JournalEntry(
                timestampMs = nowMs,
                title = "Chasse aux Lucioles avec ${spirit.name}",
                detail = "$caughtOrbs lucioles capturées (+$dewReward Rosée, +$xpGain XP).",
                icon = "✨"
            )
        )
    }

    fun unlockOrToggleDecor(
        state: TerrariumAppState,
        decor: DecorItem,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        val spirit = state.activeSpirit
        val isUnlocked = decor.id in state.unlockedDecorIds
        if (!isUnlocked) {
            if (state.dewCurrency < decor.costDew) return state
            val nextUnlocked = state.unlockedDecorIds + decor.id
            val nextActive = (spirit.activeDecorIds + decor.id).distinct()
            val updated = spirit.copy(
                activeDecorIds = nextActive,
                happiness = (spirit.happiness + 10f).coerceAtMost(100f),
                lastSpeech = "Oh ! Tu as ajouté « ${decor.labelFr} » dans mon bocal !",
                lastSpeechAtMs = nowMs
            )
            return state.copy(
                dewCurrency = state.dewCurrency - decor.costDew,
                unlockedDecorIds = nextUnlocked,
                spirits = state.spirits + (spirit.element to updated),
                journal = (listOf(
                    JournalEntry(nowMs, "Nouveau décor : ${decor.labelFr}", "Installé pour ${spirit.name}.", "🏡")
                ) + state.journal).take(40)
            )
        } else {
            val nextActive = if (decor.id in spirit.activeDecorIds) {
                spirit.activeDecorIds - decor.id
            } else {
                spirit.activeDecorIds + decor.id
            }
            val updated = spirit.copy(activeDecorIds = nextActive)
            return state.copy(spirits = state.spirits + (spirit.element to updated))
        }
    }

    fun unlockOrSelectJar(
        state: TerrariumAppState,
        jarStyle: JarStyle,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        val spirit = state.activeSpirit
        val unlocked = jarStyle in state.unlockedJarStyles
        if (!unlocked) {
            if (state.dewCurrency < jarStyle.costDew) return state
            val updated = spirit.copy(
                jarStyle = jarStyle,
                lastSpeech = "Wouah, mon nouveau « ${jarStyle.labelFr} » est magnifique !",
                lastSpeechAtMs = nowMs
            )
            return state.copy(
                dewCurrency = state.dewCurrency - jarStyle.costDew,
                unlockedJarStyles = state.unlockedJarStyles + jarStyle,
                spirits = state.spirits + (spirit.element to updated)
            )
        }
        val updated = spirit.copy(jarStyle = jarStyle)
        return state.copy(spirits = state.spirits + (spirit.element to updated))
    }

    fun claimDailyQuest(
        state: TerrariumAppState,
        questId: String,
        rewardDew: Int,
        nowMs: Long = System.currentTimeMillis()
    ): TerrariumAppState {
        val quest = ensureDayQuest(state.dailyQuest, nowMs)
        if (questId in quest.claimedIds) return state
        return state.copy(
            dewCurrency = state.dewCurrency + rewardDew,
            dailyQuest = quest.copy(claimedIds = quest.claimedIds + questId),
            journal = (listOf(
                JournalEntry(nowMs, "Rituel du jour accompli", "+$rewardDew Rosée ajoutée à ta réserve.", "🎁")
            ) + state.journal).take(40)
        )
    }

    fun switchElement(state: TerrariumAppState, element: SpiritElement): TerrariumAppState {
        return state.copy(activeElement = element)
    }

    fun customizeSpirit(
        state: TerrariumAppState,
        newName: String,
        auraHex: Long
    ): TerrariumAppState {
        val cleanName = newName.trim().ifEmpty { state.activeElement.defaultName }.take(20)
        val updated = state.activeSpirit.copy(name = cleanName, customAuraHex = auraHex)
        return state.copy(spirits = state.spirits + (state.activeElement to updated))
    }

    fun pickContextSpeech(
        spirit: SpiritProfile,
        battery: BatterySnapshot,
        dayPhase: DayPhase
    ): String {
        if (battery.isCharging) {
            return "Tu recharges le téléphone ? Je sens une douce énergie qui me réchauffe ! ⚡"
        }
        if (battery.levelPercent <= 20) {
            return "Ta batterie est à ${battery.levelPercent}%… je fais une petite sieste pour économiser nos forces."
        }
        if (spirit.hunger < 30f) {
            return "Mon petit ventre gargouille… tu aurais un peu de ${spirit.element.favoriteFood.labelFr} ?"
        }
        if (spirit.hydration < 30f) {
            return "L'air du bocal est un peu sec, une petite brume me ferait tant de bien 💧"
        }
        return when (dayPhase) {
            DayPhase.DAWN -> "L'aube se lève… bonjour ! Prêt pour une douce journée ensemble ?"
            DayPhase.MORNING -> "Belle matinée ! Chaque pas que tu fais nourrit mon terrarium."
            DayPhase.AFTERNOON -> "Coucou ! Une petite pause caresse ou une chasse aux lucioles ?"
            DayPhase.DUSK -> "Le ciel vire au doré… j'adore cette lumière du soir dans mon bocal."
            DayPhase.NIGHT -> "La nuit est calme et étoilée. Baisse un peu la lampe quand tu iras dormir 🌙"
        }
    }

    private fun pickPetSpeech(spirit: SpiritProfile, battery: BatterySnapshot, evolved: Boolean): String {
        if (evolved) {
            return "✨ Je grandis !! Je suis devenu ${spirit.stage.labelFr} grâce à toi !"
        }
        if (!battery.isCharging && battery.levelPercent <= 20) {
            return "Mmmh… tes caresses sont si douces même quand la batterie fatigue…"
        }
        val pool = when (spirit.element) {
            SpiritElement.MOSS -> listOf(
                "Hihi, mes petites feuilles frétillent ! 🌿",
                "Ton doigt est tout chaud, j'adore ça…",
                "Je ronronne comme la mousse sous la pluie 💚"
            )
            SpiritElement.WATER -> listOf(
                "Bloop bloop ! Ça fait plein de petites bulles joyeuses ! 💧",
                "Je scintille dès que tu me caresses !",
                "Douce vague de bonheur dans mon bocal 💙"
            )
            SpiritElement.EMBER -> listOf(
                "Crac-crac ! Mes étincelles dansent de joie ! 🔥",
                "Chaleur maximale dans mon petit cœur !",
                "Héhé, attention je pétille quand je suis content !"
            )
            SpiritElement.CLOUD -> listOf(
                "Je flotte encore plus haut quand tu me caresses ☁️",
                "Doux comme un oreiller d'étoiles… ✨",
                "Un mini arc-en-ciel rien que pour toi !"
            )
        }
        return pool[Random.nextInt(pool.size)]
    }

    private fun ensureDayQuest(quest: DailyQuestProgress, nowMs: Long): DailyQuestProgress {
        val key = currentDayKey(nowMs)
        return if (quest.dayKey == key) quest else DailyQuestProgress(dayKey = key)
    }

    private fun commitSpiritUpdate(
        state: TerrariumAppState,
        updated: SpiritProfile,
        evolved: Boolean,
        nowMs: Long,
        extraJournal: JournalEntry? = null
    ): TerrariumAppState {
        val entries = mutableListOf<JournalEntry>()
        if (evolved) {
            entries += JournalEntry(
                timestampMs = nowMs,
                title = "${updated.name} a évolué !",
                detail = "Nouveau stade atteint : ${updated.stage.labelFr} (Niveau ${updated.stage.level}).",
                icon = "🌟"
            )
        }
        if (extraJournal != null) entries += extraJournal
        val nextJournal = if (entries.isNotEmpty()) {
            (entries + state.journal).take(40)
        } else {
            state.journal
        }
        return state.copy(
            spirits = state.spirits + (updated.element to updated),
            journal = nextJournal
        )
    }
}
