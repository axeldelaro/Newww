package com.kinetix.coach.progression

import com.kinetix.coach.data.ExerciseType
import kotlin.math.roundToInt

/**
 * Pure-Kotlin deterministic procedural daily quest generator.
 * Combines 3 orthogonal bricks — Exercise × Biomechanical Modifier × Set Format —
 * calibrated dynamically to the athlete's [AthleteRank] (E -> S) and available equipment.
 * Uses a platform-independent SplitMix64 PRNG seeded by dateKey so every day yields
 * 3 fresh, balanced, non-repeating quests directly linked to KINETIX's camera exercises & steps.
 */
object ProceduralQuestEngine {

    private data class ExerciseTemplate(
        val exercise: ExerciseType,
        val baseRepsAtRankE: Int,
        val unitFr: String,
        val attributeKey: String,
        val bodyweightOnly: Boolean,
        val modifiers: List<Pair<String, Float>> // modifierLabelFr -> difficulty multiplier
    )

    private val UPPER_FORCE_TEMPLATES = listOf(
        ExerciseTemplate(
            exercise = ExerciseType.PUSHUP,
            baseRepsAtRankE = 18,
            unitFr = "reps",
            attributeKey = "force",
            bodyweightOnly = true,
            modifiers = listOf(
                "Classique" to 1.0f,
                "Large" to 1.05f,
                "Serrée" to 1.15f,
                "Diamant" to 1.25f,
                "Tempo lent" to 1.20f,
                "Inclinée" to 0.90f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.PULL_UP,
            baseRepsAtRankE = 8,
            unitFr = "reps",
            attributeKey = "force",
            bodyweightOnly = true,
            modifiers = listOf(
                "Classique" to 1.0f,
                "Prise large" to 1.15f,
                "Prise serrée" to 1.05f,
                "Contrôlé" to 1.20f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.TRICEP_DIP,
            baseRepsAtRankE = 14,
            unitFr = "reps",
            attributeKey = "force",
            bodyweightOnly = true,
            modifiers = listOf(
                "Triceps" to 1.0f,
                "Pectoraux" to 1.10f,
                "Banc" to 0.90f,
                "Tempo lent" to 1.15f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.OVERHEAD_PRESS,
            baseRepsAtRankE = 18,
            unitFr = "reps",
            attributeKey = "force",
            bodyweightOnly = false,
            modifiers = listOf(
                "Strict" to 1.0f,
                "Alterné" to 1.05f,
                "Tempo contrôlé" to 1.15f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.BENT_ROW,
            baseRepsAtRankE = 20,
            unitFr = "reps",
            attributeKey = "force",
            bodyweightOnly = false,
            modifiers = listOf(
                "Strict" to 1.0f,
                "Yates" to 1.05f,
                "Unilatéral" to 1.10f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.BICEP_CURL,
            baseRepsAtRankE = 22,
            unitFr = "reps",
            attributeKey = "force",
            bodyweightOnly = false,
            modifiers = listOf(
                "Strict" to 1.0f,
                "Alterné" to 1.05f,
                "Descente lente" to 1.15f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.LATERAL_RAISE,
            baseRepsAtRankE = 22,
            unitFr = "reps",
            attributeKey = "mastery",
            bodyweightOnly = false,
            modifiers = listOf(
                "Strict 90°" to 1.0f,
                "Alterné" to 1.05f,
                "Tempo contrôlé" to 1.15f
            )
        )
    )

    private val LOWER_CORE_TEMPLATES = listOf(
        ExerciseTemplate(
            exercise = ExerciseType.SQUAT,
            baseRepsAtRankE = 22,
            unitFr = "reps",
            attributeKey = "agility",
            bodyweightOnly = true,
            modifiers = listOf(
                "Classique" to 1.0f,
                "Sumo" to 1.05f,
                "Profond ATG" to 1.20f,
                "Tempo lent" to 1.15f,
                "Sauté" to 1.25f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.LUNGE,
            baseRepsAtRankE = 18,
            unitFr = "reps",
            attributeKey = "agility",
            bodyweightOnly = true,
            modifiers = listOf(
                "Alternées" to 1.0f,
                "Contrôlées" to 1.10f,
                "Amplitude complète" to 1.15f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.BULGARIAN_SQUAT,
            baseRepsAtRankE = 16,
            unitFr = "reps",
            attributeKey = "agility",
            bodyweightOnly = true,
            modifiers = listOf(
                "Quadriceps" to 1.05f,
                "Fessiers" to 1.05f,
                "Tempo lent" to 1.15f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.PLANK,
            baseRepsAtRankE = 35,
            unitFr = "s",
            attributeKey = "endurance",
            bodyweightOnly = true,
            modifiers = listOf(
                "Avant-bras" to 1.0f,
                "Haute" to 0.95f,
                "Alignement strict" to 1.15f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.GLUTE_BRIDGE,
            baseRepsAtRankE = 24,
            unitFr = "reps",
            attributeKey = "agility",
            bodyweightOnly = true,
            modifiers = listOf(
                "Classique" to 1.0f,
                "Unilatéral" to 1.20f,
                "Pause 2s en haut" to 1.15f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.LEG_RAISE,
            baseRepsAtRankE = 16,
            unitFr = "reps",
            attributeKey = "endurance",
            bodyweightOnly = true,
            modifiers = listOf(
                "Jambes tendues" to 1.15f,
                "Alterné" to 1.0f,
                "Descente lente" to 1.20f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.DEADLIFT,
            baseRepsAtRankE = 18,
            unitFr = "reps",
            attributeKey = "force",
            bodyweightOnly = false,
            modifiers = listOf(
                "Conventionnel" to 1.0f,
                "RDL" to 1.10f,
                "Sumo" to 1.05f
            )
        )
    )

    private val CARDIO_TEMPLATES = listOf(
        ExerciseTemplate(
            exercise = ExerciseType.BURPEE,
            baseRepsAtRankE = 12,
            unitFr = "reps",
            attributeKey = "endurance",
            bodyweightOnly = true,
            modifiers = listOf(
                "Classique" to 1.0f,
                "Explosif" to 1.20f,
                "Rythme continu" to 1.10f
            )
        ),
        ExerciseTemplate(
            exercise = ExerciseType.JUMPING_JACK,
            baseRepsAtRankE = 35,
            unitFr = "reps",
            attributeKey = "endurance",
            bodyweightOnly = true,
            modifiers = listOf(
                "Classique" to 1.0f,
                "Cadence rapide" to 1.15f,
                "Amplitude haute" to 1.05f
            )
        )
    )

    private fun rankVolumeMultiplier(rank: AthleteRank): Float = when (rank) {
        AthleteRank.E -> 1.00f
        AthleteRank.D -> 1.35f
        AthleteRank.C -> 1.75f
        AthleteRank.B -> 2.25f
        AthleteRank.A -> 2.90f
        AthleteRank.S -> 3.60f
    }

    private fun rankStepsTarget(rank: AthleteRank): Int = when (rank) {
        AthleteRank.E -> 5_000
        AthleteRank.D -> 6_500
        AthleteRank.C -> 8_000
        AthleteRank.B -> 9_000
        AthleteRank.A -> 10_000
        AthleteRank.S -> 12_000
    }

    private fun rankBaseXp(rank: AthleteRank): Int = when (rank) {
        AthleteRank.E -> 45
        AthleteRank.D -> 65
        AthleteRank.C -> 90
        AthleteRank.B -> 120
        AthleteRank.A -> 160
        AthleteRank.S -> 210
    }

    /**
     * Generates 3 balanced daily quests deterministically for [dateKey] ("YYYY-MM-DD"),
     * scaled by [rank] and filtered for [equipmentFr].
     */
    fun generateDailyQuests(
        dateKey: String,
        rank: AthleteRank,
        equipmentFr: String = "Salle complète"
    ): List<DailyQuest> {
        val rng = DeterministicRng(seedFromString("$dateKey|${rank.code}|KINETIX_V1"))
        val bodyweightOnly = equipmentFr.contains("Poids du corps", ignoreCase = true)

        val upperPool = if (bodyweightOnly) UPPER_FORCE_TEMPLATES.filter { it.bodyweightOnly } else UPPER_FORCE_TEMPLATES
        val lowerPool = if (bodyweightOnly) LOWER_CORE_TEMPLATES.filter { it.bodyweightOnly } else LOWER_CORE_TEMPLATES

        val q1 = buildExerciseQuest(
            slotIndex = 0,
            dateKey = dateKey,
            rank = rank,
            template = upperPool[rng.nextInt(upperPool.size)],
            rng = rng
        )
        val q2 = buildExerciseQuest(
            slotIndex = 1,
            dateKey = dateKey,
            rank = rank,
            template = lowerPool[rng.nextInt(lowerPool.size)],
            rng = rng
        )
        // Slot 3 alternates cleanly between a daily Steps/Vitality goal (60%) and a Camera Cardio quest (40%)
        val q3 = if (rng.nextInt(10) < 6) {
            buildStepsQuest(slotIndex = 2, dateKey = dateKey, rank = rank, rng = rng)
        } else {
            buildExerciseQuest(
                slotIndex = 2,
                dateKey = dateKey,
                rank = rank,
                template = CARDIO_TEMPLATES[rng.nextInt(CARDIO_TEMPLATES.size)],
                rng = rng
            )
        }
        return listOf(q1, q2, q3)
    }

    private fun buildExerciseQuest(
        slotIndex: Int,
        dateKey: String,
        rank: AthleteRank,
        template: ExerciseTemplate,
        rng: DeterministicRng
    ): DailyQuest {
        val (modLabel, modMult) = template.modifiers[rng.nextInt(template.modifiers.size)]
        val rawAmount = (template.baseRepsAtRankE * rankVolumeMultiplier(rank)).roundToInt()
        val target = if (template.unitFr == "s") {
            // Round isometric holds to clean 5-second multiples
            ((rawAmount + 2) / 5 * 5).coerceIn(25, 240)
        } else {
            // Round reps cleanly to multiples of 5 (except small pull-up counts)
            if (rawAmount <= 12) rawAmount.coerceAtLeast(6)
            else ((rawAmount + 2) / 5 * 5).coerceAtLeast(10)
        }

        val formatLabel = buildFormatLabel(target, template.unitFr, rng)
        val xp = (rankBaseXp(rank) * modMult).roundToInt().coerceAtLeast(35)
        val title = if (modLabel == "Classique" || modLabel == "Strict") {
            template.exercise.labelFr
        } else {
            "${template.exercise.labelFr} · $modLabel"
        }

        return DailyQuest(
            id = "${dateKey}_$slotIndex",
            dateKey = dateKey,
            titleFr = title,
            subtitleFr = "$formatLabel · Vérifié caméra",
            exerciseType = template.exercise,
            modifierLabelFr = modLabel,
            formatLabelFr = formatLabel,
            targetAmount = target,
            currentProgress = 0,
            unitFr = template.unitFr,
            xpReward = xp,
            attributeKey = template.attributeKey,
            completed = false
        )
    }

    private fun buildStepsQuest(
        slotIndex: Int,
        dateKey: String,
        rank: AthleteRank,
        rng: DeterministicRng
    ): DailyQuest {
        val base = rankStepsTarget(rank)
        val bonusOffset = (rng.nextInt(3) - 1) * 500 // -500, 0, or +500 steps variation
        val target = (base + bonusOffset).coerceIn(4_000, 14_000)
        val xp = (rankBaseXp(rank) * 1.10f).roundToInt()
        val formatted = formatThousandsFr(target)
        return DailyQuest(
            id = "${dateKey}_$slotIndex",
            dateKey = dateKey,
            titleFr = "Marche Active",
            subtitleFr = "$formatted pas dans la journée",
            exerciseType = null,
            modifierLabelFr = "Endurance",
            formatLabelFr = "$formatted pas",
            targetAmount = target,
            currentProgress = 0,
            unitFr = "pas",
            xpReward = xp,
            attributeKey = "vitality",
            completed = false
        )
    }

    private fun buildFormatLabel(target: Int, unitFr: String, rng: DeterministicRng): String {
        if (unitFr == "s") {
            return if (target >= 60 && rng.nextInt(2) == 0) {
                val sets = if (target >= 90) 3 else 2
                val perSet = (target / sets / 5 * 5).coerceAtLeast(20)
                "$sets×${perSet}s (${target}s tot.)"
            } else {
                "${target}s cumulées"
            }
        }
        return when (rng.nextInt(3)) {
            0 -> if (target >= 18) {
                val sets = 3
                val perSet = (target / sets).coerceAtLeast(5)
                "$sets×$perSet reps ($target tot.)"
            } else {
                "$target reps cumulées"
            }
            1 -> "$target reps cumulées"
            else -> "Objectif $target reps"
        }
    }

    private fun formatThousandsFr(value: Int): String {
        val s = value.toString()
        if (s.length <= 3) return s
        val rev = s.reversed().chunked(3).joinToString(" ").reversed()
        return rev
    }

    private fun seedFromString(input: String): Long {
        // 64-bit FNV-1a hash
        var hash = -0x340d631b7bdddcdbL
        for (ch in input) {
            hash = hash xor ch.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    /** Platform-independent SplitMix64 PRNG for 100% deterministic daily seeds. */
    private class DeterministicRng(private var state: Long) {
        fun nextLong(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun nextInt(bound: Int): Int {
            require(bound > 0)
            val raw = (nextLong() ushr 1) % bound.toLong()
            return raw.toInt()
        }
    }
}
