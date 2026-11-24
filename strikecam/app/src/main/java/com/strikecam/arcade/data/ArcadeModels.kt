package com.strikecam.arcade.data

enum class Difficulty(
    val nativeId: Int,
    val labelFr: String,
    val shortLabel: String,
    val approachTimeMs: Float,
    val scoreMultiplier: Float
) {
    CHILL(0, "CHILL", "CHILL", 1450f, 1.0f),
    PRO(1, "PRO", "PRO", 1220f, 1.25f),
    EXPERT(2, "EXPERT", "EXPERT", 1020f, 1.5f),
    UPTEMPO_INSANE(3, "UPTEMPO", "UPTEMPO", 860f, 2.0f)
}

enum class GameMode(val labelFr: String, val subtitleFr: String) {
    SOLO_ARCADE("SOLO ARCADE", "Score, Combo & Rang S+"),
    SURVIVAL("SURVIE", "3 vies • Vitesse croissante"),
    DUEL_2P("DUEL 2P", "Tour par tour • Même beatmap")
}

enum class TargetType(val nativeId: Int) {
    JAB_LEFT(0),
    JAB_RIGHT(1),
    SLASH_UP(2),
    SLASH_DOWN(3),
    SLASH_IN(4),
    SLASH_OUT(5),
    DOUBLE_STRIKE(6),
    DODGE_DUCK(7),
    DODGE_SLIP_LEFT(8),
    DODGE_SLIP_RIGHT(9);

    val isDodge: Boolean
        get() = this == DODGE_DUCK || this == DODGE_SLIP_LEFT || this == DODGE_SLIP_RIGHT

    val isSlash: Boolean
        get() = this == SLASH_UP || this == SLASH_DOWN || this == SLASH_IN || this == SLASH_OUT

    companion object {
        fun fromNativeId(id: Int): TargetType =
            entries.firstOrNull { it.nativeId == id } ?: JAB_LEFT
    }
}

enum class TargetHand(val nativeId: Int) {
    LEFT(0),
    RIGHT(1),
    BOTH(2),
    BODY(3);

    companion object {
        fun fromNativeId(id: Int): TargetHand =
            entries.firstOrNull { it.nativeId == id } ?: LEFT
    }
}

data class BeatTarget(
    val id: Int,
    val timeMs: Float,
    val type: TargetType,
    val hand: TargetHand,
    val normX: Float,
    val normY: Float,
    val durationMs: Float = 0f,
    val intensity: Float = 0.7f
)

data class GeneratedBeatmap(
    val bpm: Float,
    val firstBeatMs: Float,
    val durationMs: Float,
    val dropCount: Int,
    val targets: List<BeatTarget>
)

enum class HitVerdict(val labelFr: String, val basePoints: Int) {
    PERFECT("PERFECT!", 300),
    GREAT("GREAT", 200),
    GOOD("GOOD", 100),
    DODGE_OK("ESQUIVE!", 250),
    MISS("MISS", 0)
}

enum class TrackSource {
    BUILTIN_SYNTH,
    LOCAL_ZAAGFORGE,
    LOCAL_FILE,
    DEEZER_PREVIEW,
    STREAMING_LIVE_SYNC
}

enum class StreamingPlatform(val label: String, val packageName: String) {
    SPOTIFY("Spotify", "com.spotify.music"),
    DEEZER("Deezer", "deezer.android.app"),
    SOUNDCLOUD("SoundCloud", "com.soundcloud.android"),
    UNKNOWN("Streaming", "")
}

data class ArcadeTrack(
    val id: String,
    val title: String,
    val artist: String,
    val bpm: Float,
    val durationMs: Long,
    val source: TrackSource,
    val genreTag: String = "",
    val builtInTrackId: Int = -1,
    val contentUri: String? = null,
    val previewUrl: String? = null,
    val externalUrl: String? = null,
    val coverUrl: String? = null,
    val streamingPlatform: StreamingPlatform = StreamingPlatform.UNKNOWN,
    val isZaagForgeRemix: Boolean = false
)

enum class CameraBackdropMode(val labelFr: String, val scrimAlpha: Float) {
    FULL_CAMERA("Caméra 100%", 0.08f),
    DIM_50("Sombre 50%", 0.52f),
    OLED_SILHOUETTE("Silhouette OLED", 0.94f)
}

enum class ArcadeRank(val label: String, val minAccuracyPct: Float) {
    S_PLUS("S+", 95f),
    S("S", 90f),
    A("A", 82f),
    B("B", 72f),
    C("C", 60f),
    D("D", 0f);

    companion object {
        fun compute(accuracyPct: Float, missCount: Int, totalTargets: Int): ArcadeRank {
            if (totalTargets <= 0) return D
            return when {
                accuracyPct >= 95f && missCount == 0 -> S_PLUS
                accuracyPct >= 90f -> S
                accuracyPct >= 82f -> A
                accuracyPct >= 72f -> B
                accuracyPct >= 60f -> C
                else -> D
            }
        }
    }
}

data class PlayerRoundSummary(
    val playerName: String,
    val score: Int,
    val rank: ArcadeRank,
    val maxCombo: Int,
    val accuracyPct: Float,
    val perfectCount: Int,
    val greatCount: Int,
    val goodCount: Int,
    val dodgeCount: Int,
    val missCount: Int,
    val avgReactionMs: Float,
    val totalPunches: Int,
    val caloriesKcal: Float,
    val survivedSec: Float = 0f,
    val peakSpeedMultiplier: Float = 1.0f,
    val failedOut: Boolean = false
)

enum class DuelPhase {
    NONE,
    PLAYER_1_PLAYING,
    HANDOFF_TO_PLAYER_2,
    PLAYER_2_PLAYING,
    DUEL_COMPLETE
}

data class DuelMatchState(
    val phase: DuelPhase = DuelPhase.NONE,
    val player1Name: String = "JOUEUR 1",
    val player2Name: String = "JOUEUR 2",
    val player1Summary: PlayerRoundSummary? = null,
    val player2Summary: PlayerRoundSummary? = null
) {
    val winnerLabel: String
        get() {
            val p1 = player1Summary ?: return ""
            val p2 = player2Summary ?: return ""
            return when {
                p1.score > p2.score -> "$player1Name VAINQUEUR !"
                p2.score > p1.score -> "$player2Name VAINQUEUR !"
                p1.accuracyPct > p2.accuracyPct -> "$player1Name (PRÉCISION) !"
                p2.accuracyPct > p1.accuracyPct -> "$player2Name (PRÉCISION) !"
                else -> "ÉGALITÉ PARFAITE !"
            }
        }
}

data class ArcadeScoreRecord(
    val id: Long = 0L,
    val trackId: String,
    val trackTitle: String,
    val sourceType: String,
    val mode: GameMode,
    val difficulty: Difficulty,
    val score: Int,
    val rank: ArcadeRank,
    val maxCombo: Int,
    val accuracyPct: Float,
    val perfectCount: Int,
    val greatCount: Int,
    val goodCount: Int,
    val dodgeCount: Int,
    val missCount: Int,
    val avgReactionMs: Float,
    val totalPunches: Int,
    val caloriesKcal: Float,
    val timestampMs: Long
)

data class CareerStats(
    val totalSessions: Int = 0,
    val totalPunches: Int = 0,
    val totalCaloriesKcal: Float = 0f,
    val bestScore: Int = 0,
    val bestCombo: Int = 0,
    val bestRank: ArcadeRank? = null
)

object BuiltInTracksCatalog {
    val tracks: List<ArcadeTrack> = listOf(
        ArcadeTrack(
            id = "builtin_0",
            title = "Neon Velocity",
            artist = "StrikeCam Synth",
            bpm = 135f,
            durationMs = 66_000L,
            source = TrackSource.BUILTIN_SYNTH,
            genreTag = "SYNTHWAVE DRIVE",
            builtInTrackId = 0
        ),
        ArcadeTrack(
            id = "builtin_1",
            title = "Iron Fist",
            artist = "StrikeCam Synth",
            bpm = 152f,
            durationMs = 64_000L,
            source = TrackSource.BUILTIN_SYNTH,
            genreTag = "RAW HARD TECHNO",
            builtInTrackId = 1
        ),
        ArcadeTrack(
            id = "builtin_2",
            title = "Cyber Rush",
            artist = "StrikeCam Synth",
            bpm = 174f,
            durationMs = 66_000L,
            source = TrackSource.BUILTIN_SYNTH,
            genreTag = "DRUM & BASS",
            builtInTrackId = 2
        ),
        ArcadeTrack(
            id = "builtin_3",
            title = "Zaag Overdrive",
            artist = "StrikeCam Synth",
            bpm = 200f,
            durationMs = 64_000L,
            source = TrackSource.BUILTIN_SYNTH,
            genreTag = "UPTEMPO ZAAG",
            builtInTrackId = 3
        ),
        ArcadeTrack(
            id = "builtin_4",
            title = "Piep Boss",
            artist = "StrikeCam Synth",
            bpm = 215f,
            durationMs = 64_000L,
            source = TrackSource.BUILTIN_SYNTH,
            genreTag = "EXTREME PIEPCORE",
            builtInTrackId = 4
        )
    )
}
