package com.kinetix.coach.progression

import com.kinetix.coach.data.ExerciseType
import com.kinetix.coach.vision.BipGoTempo
import com.kinetix.coach.vision.ChallengeConfig
import com.kinetix.coach.vision.ChallengeType
import com.kinetix.coach.vision.WorkoutMusicTrack

/**
 * Athlete Ranks (E -> S) with subtle studio-pro accent colors and XP milestones.
 * Advancing from one rank to the next unlocks a real camera-verified Rank Exam.
 */
enum class AthleteRank(
    val code: String,
    val labelFr: String,
    val minRankXp: Int,
    val targetRankXp: Int,
    val accentHex: Long
) {
    E("E", "Recrue", 0, 500, 0xFF94A3B8L),
    D("D", "Initié", 500, 1500, 0xFF22C55EL),
    C("C", "Confirmé", 1500, 3500, 0xFF38BDF8L),
    B("B", "Athlète", 3500, 7500, 0xFFA855F7L),
    A("A", "Élite", 7500, 15000, 0xFFF59E0BL),
    S("S", "Maître", 15000, 15000, 0xFFEF4444L);

    fun nextRank(): AthleteRank? = when (this) {
        E -> D
        D -> C
        C -> B
        B -> A
        A -> S
        S -> null
    }

    fun rankExamSpec(): RankExamSpec? = when (this) {
        E -> RankExamSpec(
            fromRank = E,
            toRank = D,
            titleFr = "Épreuve Rang D · Fondations",
            descriptionFr = "Validez 20 Squats propres devant la caméra en moins de 90s.",
            challengeConfig = ChallengeConfig(
                type = ChallengeType.AMRAP_CHRONO,
                exercise = ExerciseType.SQUAT,
                amrapDurationSec = 90,
                musicTrack = WorkoutMusicTrack.TECHNO_PULSE
            ),
            minScoreToPass = 20,
            unitFr = "squats",
            xpBonus = 180
        )
        D -> RankExamSpec(
            fromRank = D,
            toRank = C,
            titleFr = "Épreuve Rang C · Force Haut du Corps",
            descriptionFr = "Validez 25 Pompes propres devant la caméra en 2 minutes.",
            challengeConfig = ChallengeConfig(
                type = ChallengeType.AMRAP_CHRONO,
                exercise = ExerciseType.PUSHUP,
                amrapDurationSec = 120,
                musicTrack = WorkoutMusicTrack.HIIT_DRIVE
            ),
            minScoreToPass = 25,
            unitFr = "pompes",
            xpBonus = 260
        )
        C -> RankExamSpec(
            fromRank = C,
            toRank = B,
            titleFr = "Épreuve Rang B · Gainage Acier",
            descriptionFr = "Maintenez une Planche alignée pendant 60 secondes devant la caméra.",
            challengeConfig = ChallengeConfig(
                type = ChallengeType.PLANK_MUSIC_HOLD,
                exercise = ExerciseType.PLANK,
                plankTargetSec = 60,
                musicTrack = WorkoutMusicTrack.FOCUS_SYNTH
            ),
            minScoreToPass = 60,
            unitFr = "secondes",
            xpBonus = 360
        )
        B -> RankExamSpec(
            fromRank = B,
            toRank = A,
            titleFr = "Épreuve Rang A · Puissance & Volume",
            descriptionFr = "Réalisez 45 Pompes validées caméra en 2 minutes maximum.",
            challengeConfig = ChallengeConfig(
                type = ChallengeType.AMRAP_CHRONO,
                exercise = ExerciseType.PUSHUP,
                amrapDurationSec = 120,
                musicTrack = WorkoutMusicTrack.HIIT_DRIVE
            ),
            minScoreToPass = 45,
            unitFr = "pompes",
            xpBonus = 500
        )
        A -> RankExamSpec(
            fromRank = A,
            toRank = S,
            titleFr = "Épreuve Rang S · Maître Athlète",
            descriptionFr = "Validez 30 cycles « Bip & Go » Pompes (tempo Militaire) sans faillir.",
            challengeConfig = ChallengeConfig(
                type = ChallengeType.BIP_AND_GO_PUSHUPS,
                exercise = ExerciseType.PUSHUP,
                bipGoTempo = BipGoTempo.MILITARY,
                bipGoTargetCycles = 30,
                musicTrack = WorkoutMusicTrack.NONE
            ),
            minScoreToPass = 30,
            unitFr = "cycles",
            xpBonus = 750
        )
        S -> null
    }

    companion object {
        fun fromCode(code: String): AthleteRank =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: E
    }
}

data class RankExamSpec(
    val fromRank: AthleteRank,
    val toRank: AthleteRank,
    val titleFr: String,
    val descriptionFr: String,
    val challengeConfig: ChallengeConfig,
    val minScoreToPass: Int,
    val unitFr: String,
    val xpBonus: Int
)

/** 5 real biomechanical & athletic attributes visualized on the Radar Chart. */
data class AthleteAttributes(
    val force: Int = 10,
    val endurance: Int = 10,
    val agility: Int = 10,
    val vitality: Int = 10,
    val mastery: Int = 10
) {
    fun weakestLabelFr(): String {
        val list = listOf(
            "Force" to force,
            "Endurance" to endurance,
            "Agilité" to agility,
            "Vitalité" to vitality,
            "Maîtrise" to mastery
        )
        return list.minByOrNull { it.second }?.first ?: "Force"
    }

    fun asLabeledList(): List<Pair<String, Int>> = listOf(
        "Force" to force,
        "Endurance" to endurance,
        "Agilité" to agility,
        "Vitalité" to vitality,
        "Maîtrise" to mastery
    )
}

/** Athletic titles earned strictly by merit (zero virtual currency, zero shop). */
enum class MeritTitle(
    val id: String,
    val labelFr: String,
    val requirementFr: String
) {
    ATHLETE_KINETIX("ATHLETE_KINETIX", "Athlète KINETIX", "Titre de départ"),
    REGULIER("REGULIER", "Régulier", "Maintenir un streak de 3 jours"),
    INARRETABLE("INARRETABLE", "Inarrêtable 7/7", "Valider une semaine complète (7 jours)"),
    FORCE_PURE("FORCE_PURE", "Force Pure", "Cumuler 250 pompes ou 100 tractions"),
    ENDURANT_10K("ENDURANT_10K", "Endurant 10K", "Atteindre 10 000 pas sur une journée"),
    EXECUTION_PRO("EXECUTION_PRO", "Exécution Pro", "5 séances caméra avec posture ≥ 90 %"),
    EXPLOSIF_VBT("EXPLOSIF_VBT", "Vitesse & Puissance", "3 séances mesurées en vitesse VBT"),
    RANG_C("RANG_C", "Athlète Confirmé", "Atteindre le Rang C"),
    RANG_A("RANG_A", "Élite Biomécanique", "Atteindre le Rang A"),
    RANG_S("RANG_S", "Maître Athlète", "Atteindre le Rang S");

    companion object {
        fun fromId(id: String): MeritTitle =
            entries.firstOrNull { it.id == id } ?: ATHLETE_KINETIX
    }
}

/** Merit badges unlocked automatically through workouts, steps, quests, and rank exams. */
enum class MeritBadge(
    val id: String,
    val titleFr: String,
    val detailFr: String
) {
    FIRST_WORKOUT("FIRST_WORKOUT", "Premier Pas Caméra", "1re séance validée à la caméra"),
    STREAK_7("STREAK_7", "Semaine Parfaite", "7 jours actifs consécutifs"),
    PUSHUPS_100("PUSHUPS_100", "100 Pompes", "100 pompes cumulées à vie"),
    PUSHUPS_500("PUSHUPS_500", "500 Pompes", "500 pompes cumulées à vie"),
    PULLUPS_100("PULLUPS_100", "100 Tractions", "100 tractions cumulées à vie"),
    SQUATS_500("SQUATS_500", "500 Squats", "500 squats cumulés à vie"),
    STEPS_10K("STEPS_10K", "Cap 10 000 Pas", "10 000 pas en une journée"),
    STEPS_100K("STEPS_100K", "100 000 Pas", "100 000 pas cumulés"),
    GOLDEN_FORM("GOLDEN_FORM", "Posture d'Or", "Séance caméra ≥ 92 % de posture"),
    QUESTS_10("QUESTS_10", "10 Quêtes", "10 quêtes quotidiennes validées"),
    RANK_D("RANK_D", "Rang D Validé", "Épreuve caméra Rang D réussie"),
    RANK_B("RANK_B", "Rang B Validé", "Épreuve caméra Rang B réussie");

    companion object {
        fun fromId(id: String): MeritBadge? = entries.firstOrNull { it.id == id }
    }
}

data class DailyQuest(
    val id: String,
    val dateKey: String,
    val titleFr: String,
    val subtitleFr: String,
    val exerciseType: ExerciseType?, // null => walking / daily steps quest
    val modifierLabelFr: String,
    val formatLabelFr: String,
    val targetAmount: Int,
    val currentProgress: Int = 0,
    val unitFr: String,
    val xpReward: Int,
    val attributeKey: String,
    val completed: Boolean = false
) {
    val progressFraction: Float
        get() = if (targetAmount > 0) (currentProgress.toFloat() / targetAmount.toFloat()).coerceIn(0f, 1f) else 0f
}

data class DayActivityEntry(
    val dateKey: String,
    val steps: Int = 0,
    val goalReached: Boolean = false,
    val workoutDone: Boolean = false
) {
    val isActiveDay: Boolean
        get() = workoutDone || goalReached || steps >= 4000
}

/** Single immutable progression snapshot observed across Tracker, Vision, and Coach. */
data class AthleteProgressState(
    val level: Int = 1,
    val totalXp: Int = 0,
    val xpInCurrentLevel: Int = 0,
    val xpToNextLevel: Int = 150,
    val rank: AthleteRank = AthleteRank.E,
    val rankXp: Int = 0,
    val rankExamReady: Boolean = false,
    val attributes: AthleteAttributes = AthleteAttributes(),
    val streakDays: Int = 0,
    val longestStreak: Int = 0,
    val lastActiveDateKey: String = "",
    val todayDateKey: String = "",
    val todaySteps: Int = 0,
    val stepGoal: Int = 10_000,
    val todayStepGoalRewarded: Boolean = false,
    val todayStepBucketsAwarded: Int = 0,
    val totalStepsLifetime: Long = 0L,
    val totalQuestsCompleted: Int = 0,
    val totalWorkoutsCompleted: Int = 0,
    val highFormSessionsCount: Int = 0,
    val vbtSessionsCount: Int = 0,
    val exerciseTotals: Map<String, Int> = emptyMap(),
    val unlockedTitleIds: Set<String> = setOf(MeritTitle.ATHLETE_KINETIX.id),
    val equippedTitleId: String = MeritTitle.ATHLETE_KINETIX.id,
    val unlockedBadgeIds: Set<String> = emptySet(),
    val todayQuests: List<DailyQuest> = emptyList(),
    val recentDays: List<DayActivityEntry> = emptyList()
) {
    val equippedTitle: MeritTitle
        get() = MeritTitle.fromId(equippedTitleId)

    val levelProgressFraction: Float
        get() = if (xpToNextLevel > 0) (xpInCurrentLevel.toFloat() / xpToNextLevel.toFloat()).coerceIn(0f, 1f) else 1f

    val rankProgressFraction: Float
        get() {
            if (rank == AthleteRank.S) return 1f
            val span = (rank.targetRankXp - rank.minRankXp).coerceAtLeast(1)
            return ((rankXp - rank.minRankXp).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        }

    val rankXpRemaining: Int
        get() = if (rank == AthleteRank.S) 0 else (rank.targetRankXp - rankXp).coerceAtLeast(0)

    val stepsProgressFraction: Float
        get() = if (stepGoal > 0) (todaySteps.toFloat() / stepGoal.toFloat()).coerceIn(0f, 1f) else 0f

    fun toCoachContextLine(): String {
        val openQuests = todayQuests.filter { !it.completed }
            .joinToString(", ") { "${it.titleFr} (${it.currentProgress}/${it.targetAmount} ${it.unitFr})" }
            .ifBlank { "Toutes validées" }
        return "Progression : Rang ${rank.code} (${rank.labelFr}), Niv. $level, Streak ${streakDays}j, " +
            "Pas aujourd'hui $todaySteps/$stepGoal, Point faible radar : ${attributes.weakestLabelFr()}. " +
            "Quêtes du jour : $openQuests."
    }
}

/** Delta emitted after recording a workout, quest, rank exam, or milestone. */
data class ProgressionDelta(
    val xpGained: Int = 0,
    val rankXpGained: Int = 0,
    val completedQuestTitles: List<String> = emptyList(),
    val leveledUpFrom: Int? = null,
    val leveledUpTo: Int? = null,
    val rankedUpFrom: AthleteRank? = null,
    val rankedUpTo: AthleteRank? = null,
    val newlyUnlockedBadges: List<MeritBadge> = emptyList(),
    val newlyUnlockedTitles: List<MeritTitle> = emptyList(),
    val weeklyStreakBonusAwarded: Boolean = false,
    val stepGoal10kAwarded: Boolean = false
) {
    val hasCelebration: Boolean
        get() = leveledUpTo != null || rankedUpTo != null || newlyUnlockedBadges.isNotEmpty() || completedQuestTitles.isNotEmpty()
}
