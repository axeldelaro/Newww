package com.kinetix.coach.data

enum class KnowledgeCategory(val dbKey: String, val labelFr: String) {
    ALL("all", "Tout"),
    EXERCICE("exercice", "Exercices"),
    NUTRITION("nutrition", "Nutrition"),
    PROGRAMME("programme", "Programmes"),
    BIOMECANIQUE("biomecanique", "Biomécanique"),
    BLESSURES("blessures", "Blessures");

    companion object {
        fun fromDbKey(key: String): KnowledgeCategory =
            entries.firstOrNull { it.dbKey.equals(key, ignoreCase = true) } ?: EXERCICE
    }
}

data class KnowledgeEntry(
    val id: Long,
    val slug: String,
    val category: String,
    val subcategory: String,
    val title: String,
    val summary: String,
    val body: String,
    val tags: String,
    val metadataJson: String
)

data class RagHit(
    val entry: KnowledgeEntry,
    val score: Double
)

data class RagSourceRef(
    val id: Long,
    val title: String,
    val category: String,
    val subcategory: String
)

enum class ExerciseType(
    val id: String,
    val labelFr: String,
    val musclesFr: String,
    val cameraAngleHint: String,
    val targetCueFr: String,
    val isIsometric: Boolean = false
) {
    SQUAT(
        "SQUAT", "Squat", "Quadriceps · Fessiers",
        "Profil 3/4", "Cassez la parallèle (< 95° genou), dos neutre, talons au sol."
    ),
    PUSHUP(
        "PUSHUP", "Pompes", "Pectoraux · Triceps · Gainage",
        "Profil", "Corps aligné épaule-hanche-cheville, descendez coudes < 95°."
    ),
    LUNGE(
        "LUNGE", "Fentes", "Quadriceps · Fessiers",
        "Profil", "Genou avant vers 90°, buste vertical contrôlé."
    ),
    DEADLIFT(
        "DEADLIFT", "Soulevé de terre", "Ischios · Fessiers · Dos",
        "Profil", "Charnière de hanche pure, dos plat, verrouillage complet debout."
    ),
    OVERHEAD_PRESS(
        "OVERHEAD_PRESS", "Développé militaire", "Deltoïdes · Triceps",
        "Face ou 3/4", "Poussée verticale coudes > 160° sans cambrer les lombaires."
    ),
    BICEP_CURL(
        "BICEP_CURL", "Curl biceps", "Biceps · Brachial",
        "Profil", "Coudes fixes près des côtes, flexion complète < 55°."
    ),
    TRICEP_DIP(
        "TRICEP_DIP", "Dips / Extensions triceps", "Triceps · Pectoraux",
        "Profil", "Descendez coudes à 90°, épaules abaissées, extension complète."
    ),
    PLANK(
        "PLANK", "Gainage Planche", "Transverse · Abdos · Fessiers",
        "Profil", "Alignement strict 160°–195° épaule-hanche-cheville (chrono actif).",
        isIsometric = true
    ),
    BURPEE(
        "BURPEE", "Burpees", "Corps complet · Cardio",
        "Profil", "Cycle fluide debout -> planche basse -> extension complète."
    ),
    JUMPING_JACK(
        "JUMPING_JACK", "Jumping Jacks", "Cardio · Épaules · Mollets",
        "Face", "Synchronisez bras au-dessus de la tête et ouverture jambes."
    ),
    PULL_UP(
        "PULL_UP", "Tractions", "Grand dorsal · Biceps",
        "Face / Dos", "Suspension bras tendus -> menton/poitrine au-dessus des mains."
    ),
    BENT_ROW(
        "BENT_ROW", "Rowing penché", "Dos · Rhomboïdes · Biceps",
        "Profil", "Buste incliné fixe 35–55°, tirez les coudes vers les hanches."
    ),
    GLUTE_BRIDGE(
        "GLUTE_BRIDGE", "Pont fessier", "Grand fessier · Ischios",
        "Profil", "Montez les hanches jusqu'à alignement complet > 165° sans cambrer."
    ),
    LEG_RAISE(
        "LEG_RAISE", "Relevé de jambes", "Abdos inférieurs · Hanches",
        "Profil", "Jambes quasi tendues vers 90°, descente lente lombaires plaquées."
    ),
    LATERAL_RAISE(
        "LATERAL_RAISE", "Élévations latérales", "Deltoïde moyen",
        "Face", "Élevez les bras à 85–95° sans hausser les trapèzes."
    ),
    BULGARIAN_SQUAT(
        "BULGARIAN_SQUAT", "Squat Bulgare", "Quadriceps · Fessiers",
        "Profil", "Descendez genou avant < 95°, buste stable légèrement incliné."
    );

    companion object {
        fun fromId(id: String): ExerciseType =
            entries.firstOrNull { it.id == id } ?: SQUAT
    }
}

enum class MovementPhase(val labelFr: String) {
    READY("PRÊT"),
    ECCENTRIC("DESCENTE"),
    BOTTOM("BAS"),
    CONCENTRIC("MONTÉE"),
    HOLD("MAINTIEN"),
    PAUSE("PAUSE")
}

data class WorkoutSession(
    val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val exerciseType: ExerciseType,
    val reps: Int,
    val durationSec: Int,
    val avgFormScore: Int,
    val avgRomPct: Int,
    val topFaults: List<String>,
    val notes: String = ""
)

data class ChatMessage(
    val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val isUser: Boolean,
    val text: String,
    val sources: List<RagSourceRef> = emptyList(),
    val tokensPerSec: Float = 0f,
    val isStreaming: Boolean = false
)

data class AthleteProfile(
    val name: String = "Athlète",
    val age: Int = 28,
    val sex: String = "Homme", // "Homme" | "Femme"
    val weightKg: Float = 75f,
    val heightCm: Int = 178,
    val goal: String = "Hypertrophie & Performance",
    val level: String = "Intermédiaire",
    val equipment: String = "Salle complète",
    val injuries: String = "Aucune",
    val activityFactor: Float = 1.55f
) {
    fun toPromptSummary(): String = buildString {
        append("Athlète : $name, $sex, $age ans, ${weightKg.toInt()} kg, $heightCm cm. ")
        append("Objectif : $goal | Niveau : $level | Matériel : $equipment. ")
        if (injuries.isNotBlank() && !injuries.equals("Aucune", ignoreCase = true)) {
            append("Antécédents/blessures : $injuries.")
        }
    }
}
