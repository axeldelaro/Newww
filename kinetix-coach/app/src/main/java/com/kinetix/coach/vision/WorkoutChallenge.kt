package com.kinetix.coach.vision

import com.kinetix.coach.data.ExerciseType
import java.util.Locale

enum class ChallengeType(
    val id: String,
    val titleFr: String,
    val shortTitleFr: String,
    val descriptionFr: String,
    val lowerIsBetter: Boolean = false,
    val fixedExercise: ExerciseType? = null,
    val defaultMusic: WorkoutMusicTrack = WorkoutMusicTrack.TECHNO_PULSE
) {
    BIP_AND_GO_PUSHUPS(
        id = "BIP_AND_GO_PUSHUPS",
        titleFr = "Pompes « Bip & Go »",
        shortTitleFr = "Bip & Go",
        descriptionFr = "Descendez au bip grave, tenez en bas, poussez au signal GO !",
        lowerIsBetter = false,
        fixedExercise = ExerciseType.PUSHUP,
        defaultMusic = WorkoutMusicTrack.NONE
    ),
    LUC_LEGER_CADENCE(
        id = "LUC_LEGER_CADENCE",
        titleFr = "Test Luc Léger",
        shortTitleFr = "Luc Léger",
        descriptionFr = "Paliers progressifs de 3,6s à 1,1s par rep validée caméra.",
        lowerIsBetter = false,
        fixedExercise = null,
        defaultMusic = WorkoutMusicTrack.TECHNO_PULSE
    ),
    PLANK_MUSIC_HOLD(
        id = "PLANK_MUSIC_HOLD",
        titleFr = "Gainage Chrono Musical",
        shortTitleFr = "Gainage Musical",
        descriptionFr = "Chrono et musique actifs uniquement en planche alignée.",
        lowerIsBetter = false,
        fixedExercise = ExerciseType.PLANK,
        defaultMusic = WorkoutMusicTrack.FOCUS_SYNTH
    ),
    TARGET_REPS_RACE(
        id = "TARGET_REPS_RACE",
        titleFr = "Défi 50 / 100 Reps",
        shortTitleFr = "Course Reps",
        descriptionFr = "Atteignez votre objectif le plus vite possible avec cadence live.",
        lowerIsBetter = true,
        fixedExercise = null,
        defaultMusic = WorkoutMusicTrack.HIIT_DRIVE
    ),
    AMRAP_CHRONO(
        id = "AMRAP_CHRONO",
        titleFr = "AMRAP Max Reps",
        shortTitleFr = "AMRAP",
        descriptionFr = "Maximum de répétitions caméra validées avant la fin du chrono.",
        lowerIsBetter = false,
        fixedExercise = null,
        defaultMusic = WorkoutMusicTrack.HIIT_DRIVE
    ),
    TABATA_HIIT(
        id = "TABATA_HIIT",
        titleFr = "Tabata 20/10 × 8",
        shortTitleFr = "Tabata",
        descriptionFr = "8 tours de 20s d'effort / 10s de repos avec ducking musical.",
        lowerIsBetter = false,
        fixedExercise = null,
        defaultMusic = WorkoutMusicTrack.HIIT_DRIVE
    ),
    EMOM_CHALLENGE(
        id = "EMOM_CHALLENGE",
        titleFr = "EMOM Cadence",
        shortTitleFr = "EMOM",
        descriptionFr = "Validez vos reps chaque minute : le reste devient votre repos.",
        lowerIsBetter = false,
        fixedExercise = null,
        defaultMusic = WorkoutMusicTrack.TECHNO_PULSE
    );

    companion object {
        fun fromId(id: String): ChallengeType =
            entries.firstOrNull { it.id == id } ?: BIP_AND_GO_PUSHUPS

        val LUC_LEGER_EXERCISES: List<ExerciseType> = listOf(
            ExerciseType.PUSHUP,
            ExerciseType.SQUAT,
            ExerciseType.BURPEE,
            ExerciseType.JUMPING_JACK
        )

        val DYNAMIC_EXERCISES: List<ExerciseType> =
            ExerciseType.entries.filter { !it.isIsometric }
    }
}

enum class BipGoTempo(
    val labelFr: String,
    val downSec: Float,
    val holdSec: Float,
    val upSec: Float
) {
    BEGINNER("Débutant 2s", downSec = 2.0f, holdSec = 1.0f, upSec = 2.2f),
    MILITARY("Militaire 3s", downSec = 2.5f, holdSec = 1.5f, upSec = 2.5f),
    COMMANDO("Commando 4s", downSec = 3.0f, holdSec = 2.0f, upSec = 2.5f)
}

data class ChallengeConfig(
    val type: ChallengeType,
    val exercise: ExerciseType = type.fixedExercise ?: ExerciseType.PUSHUP,
    val bipGoTempo: BipGoTempo = BipGoTempo.MILITARY,
    val bipGoTargetCycles: Int = 25, // 0 = Infini
    val plankTargetSec: Int = 90,    // 0 = Infini
    val targetRepsGoal: Int = 50,    // 25 / 50 / 100
    val amrapDurationSec: Int = 120, // 60 / 120 / 180
    val tabataRounds: Int = 8,
    val tabataWorkSec: Int = 20,
    val tabataRestSec: Int = 10,
    val emomMinutes: Int = 5,        // 5 / 10
    val emomRepsPerMin: Int = 10,    // 8 / 10 / 12 / 15
    val musicTrack: WorkoutMusicTrack = type.defaultMusic
) {
    fun effectiveExercise(): ExerciseType = type.fixedExercise ?: exercise

    fun summarySubtitleFr(): String = when (type) {
        ChallengeType.BIP_AND_GO_PUSHUPS -> {
            val goal = if (bipGoTargetCycles <= 0) "Infini" else "$bipGoTargetCycles cycles"
            "${bipGoTempo.labelFr} · $goal"
        }
        ChallengeType.LUC_LEGER_CADENCE -> "${effectiveExercise().labelFr} · 12 paliers progressifs"
        ChallengeType.PLANK_MUSIC_HOLD -> {
            val goal = if (plankTargetSec <= 0) "Infini" else "${plankTargetSec}s"
            "Objectif $goal · ${musicTrack.labelFr}"
        }
        ChallengeType.TARGET_REPS_RACE -> "$targetRepsGoal ${effectiveExercise().labelFr}"
        ChallengeType.AMRAP_CHRONO -> "${amrapDurationSec / 60} min · ${effectiveExercise().labelFr}"
        ChallengeType.TABATA_HIIT -> "${tabataWorkSec}s/${tabataRestSec}s × $tabataRounds · ${effectiveExercise().labelFr}"
        ChallengeType.EMOM_CHALLENGE -> "$emomMinutes min · $emomRepsPerMin reps/min · ${effectiveExercise().labelFr}"
    }
}

enum class ChallengePhase {
    IDLE,
    COUNTDOWN,
    ACTIVE,
    BIP_DOWN,
    BIP_HOLD,
    BIP_GO,
    WORK,
    REST,
    HOLDING,
    PAUSED_POSTURE,
    COMPLETED,
    FAILED
}

data class ChallengeSnapshot(
    val config: ChallengeConfig,
    val isRunning: Boolean,
    val phase: ChallengePhase,
    val phaseLabelFr: String,
    val subtitleFr: String,
    val primaryMetricLabelFr: String,
    val primaryMetricValue: String,
    val progressFraction: Float,
    val countdownSec: Int = 0,
    val elapsedSec: Int = 0,
    val remainingSec: Int = 0,
    val currentScoreValue: Double = 0.0,
    val currentScoreLabel: String = "",
    val syncStateFr: String? = null,
    val currentStage: Int = 1,
    val totalStages: Int = 1,
    val missedCount: Int = 0,
    val isUrgent: Boolean = false
)

data class ChallengeResult(
    val config: ChallengeConfig,
    val completedGoal: Boolean,
    val scoreValue: Double,
    val scoreLabel: String,
    val durationSec: Int,
    val totalReps: Int,
    val avgFormScore: Int,
    val caloriesKcal: Double,
    val isNewRecord: Boolean = false,
    val previousRecordLabel: String? = null
)

data class ChallengeOutput(
    val snapshot: ChallengeSnapshot,
    val beepsToPlay: List<BeepType> = emptyList(),
    val voiceAnnouncementFr: String? = null,
    val musicShouldPlay: Boolean = false,
    val musicDucked: Boolean = false,
    val musicFinalSprint: Boolean = false,
    val completedResult: ChallengeResult? = null
)

fun formatChallengeClock(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val mins = safe / 60
    val secs = safe % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
