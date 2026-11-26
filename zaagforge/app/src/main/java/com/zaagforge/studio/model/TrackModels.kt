package com.zaagforge.studio.model

enum class SectionKind(val nativeId: Int, val shortLabel: String) {
    INTRO(0, "INTRO"),
    VOCAL_BREAK(1, "BREAK"),
    BUILDUP(2, "BUILD"),
    FAKE_DROP(3, "FAKE-DROP"),
    DROP_1(4, "DROP 1"),
    MID_BREAK(5, "MID-BREAK"),
    DROP_2(6, "DROP 2");

    companion object {
        fun fromNativeId(id: Int): SectionKind = entries.firstOrNull { it.nativeId == id } ?: DROP_1
    }
}

enum class TempoSyncMode(val nativeId: Int, val label: String, val subtitle: String) {
    NEVER_SLOW(0, "JAMAIS RALENTI ≥1×", "Garde la vitesse (≥1.00×) ou accélère proprement"),
    ORIGINAL_SPEED_LOCK(1, "VITESSE 100% ORIG.", "Vitesse d'origine 1.00× exacte · Rendu ultra-rapide"),
    FORCE_TARGET_BPM(2, "FORCER BPM CIBLE", "Accélère strictement au BPM cible (jamais ralenti)");

    companion object {
        fun fromNativeId(id: Int): TempoSyncMode = entries.firstOrNull { it.nativeId == id } ?: NEVER_SLOW
    }
}

enum class RemixGenreTheme(
    val nativeId: Int,
    val shortLabel: String,
    val displayName: String,
    val artistInspo: String,
    val accentHex: Long,
    val dimBgHex: Long
) {
    UPTEMPO_ZAAG(
        nativeId = 0,
        shortLabel = "UPTEMPO",
        displayName = "Uptempo / Piep / Cosy / Zaag",
        artistInspo = "Unicorn On Ketamine · Cosy Nightmare · Noiseflow · Dimitri K",
        accentHex = 0xFFFF2E1F,
        dimBgHex = 0xFF3A1012
    ),
    HARDTEK_TRIBECORE(
        nativeId = 6,
        shortLabel = "HARDTEK",
        displayName = "Hardtek / Tribecore Bounce",
        artistInspo = "Darktek · Maissouille · Billx",
        accentHex = 0xFFD500F9,
        dimBgHex = 0xFF2B0E36
    ),
    FRENCHCORE(
        nativeId = 3,
        shortLabel = "FRENCHCORE",
        displayName = "Vrai Frenchcore Euphorique",
        artistInspo = "Dr. Peacock · Sefa",
        accentHex = 0xFF1ED760,
        dimBgHex = 0xFF0E2B1A
    ),
    RAWSTYLE(
        nativeId = 5,
        shortLabel = "RAWSTYLE",
        displayName = "Overdose Raw / PVC-Zaag",
        artistInspo = "Rebelion · Dual Damage · Warface",
        accentHex = 0xFFFFB800,
        dimBgHex = 0xFF30240A
    ),
    HARD_TECHNO(
        nativeId = 2,
        shortLabel = "HARD TECHNO",
        displayName = "Hard Techno / Rave / Acid 303",
        artistInspo = "Sara Landry · Nico Moreno · 999999999 · Klangkuenstler",
        accentHex = 0xFFFF7A00,
        dimBgHex = 0xFF331C0B
    ),
    TECHNO_CLUB(
        nativeId = 1,
        shortLabel = "TECHNO",
        displayName = "Techno Normale (Club / Hypnotic)",
        artistInspo = "Charlotte de Witte · Amelie Lens · Adam Beyer",
        accentHex = 0xFF00E5FF,
        dimBgHex = 0xFF0C2A33
    ),
    GABBER(
        nativeId = 4,
        shortLabel = "GABBER",
        displayName = "Vrai Gabber 909 Millennium",
        artistInspo = "Angerfist · Neophyte · Tha Playah",
        accentHex = 0xFFFF3B5C,
        dimBgHex = 0xFF33111C
    );

    companion object {
        fun fromNativeId(id: Int): RemixGenreTheme = entries.firstOrNull { it.nativeId == id } ?: UPTEMPO_ZAAG
    }
}

enum class MasterMode(val nativeId: Int, val label: String, val subtitle: String) {
    STUDIO_CLEAN(0, "STUDIO CLEAN", "Voix limpides & basses nettes sans saturation"),
    CLUB_PUNCH(1, "CLUB PUNCH", "Glue club & punch maîtrisé"),
    FESTIVAL_HARD(2, "FESTIVAL HARD", "Loudness max & clipper 2× anti-aliasing");

    companion object {
        fun fromNativeId(id: Int): MasterMode = entries.firstOrNull { it.nativeId == id } ?: STUDIO_CLEAN
    }
}

enum class DropAlignmentMode(val nativeId: Int, val label: String) {
    AUTO_CHORUS(0, "AUTO REFRAIN"),
    DIRECT_DROP(1, "DROP DIRECT"),
    VOCALS_FIRST(2, "PAROLES D'ABORD"),
    DOUBLE_DROP(3, "DOUBLE DROP");

    companion object {
        fun fromNativeId(id: Int): DropAlignmentMode = entries.firstOrNull { it.nativeId == id } ?: AUTO_CHORUS
    }
}

data class SectionMarker(
    val startFrac: Float,
    val endFrac: Float,
    val kind: SectionKind,
    val label: String = kind.shortLabel,
    val startBar: Int = 0,
    val endBar: Int = 0
)

data class TrackAnalysis(
    val bpm: Float,
    val bpmConfidence: Float,
    val beatOffsetSec: Float,
    val downbeatBeatIndex: Int = 0,
    val downbeatSec: Float = 0f,
    val totalBars: Int = 8,
    val chorus1Bar: Int = 4,
    val chorus2Bar: Int = 8,
    val chorus1Sec: Float = 0f,
    val chorus2Sec: Float = 0f,
    val keyIndex: Int,
    val isMinor: Boolean,
    val rootMidi: Int,
    val rootHz: Float,
    val keyName: String,
    val durationSec: Float,
    val avgRmsDb: Float,
    val totalFrames: Int = 0,
    val sections: List<SectionMarker>,
    val waveformPeaks: FloatArray
)

/**
 * Lightweight metadata returned from native C++ render (actual PCM stays in C++ native heap -> ZERO OOM!).
 */
data class RemixRenderResult(
    val totalFrames: Int,
    val waveformPeaks: FloatArray,
    val sections: List<SectionMarker>,
    val sourceBpm: Float,
    val effectiveTargetBpm: Float,
    val tempoStretchRatio: Float,
    val rootHz: Float,
    val keyName: String,
    val durationSec: Float,
    val peakDb: Float,
    val rmsDb: Float,
    val renderTimeMs: Float = 0f
)

enum class TrackSourceType {
    LOCAL_MEDIASTORE,
    SAF_IMPORT,
    DEEZER_PREVIEW,
    BUILTIN_DEMO
}

data class AudioTrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val sourceType: TrackSourceType,
    val uriString: String? = null,
    val localFilePath: String? = null,
    val previewUrl: String? = null,
    val coverUrl: String? = null,
    val demoStyleIndex: Int = -1
)

data class DeezerSearchTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val previewUrl: String,
    val coverUrl: String
)

enum class StreamingProvider {
    SPOTIFY,
    SOUNDCLOUD,
    DEEZER,
    UNKNOWN
}

data class ParsedStreamingLink(
    val provider: StreamingProvider,
    val cleanUrl: String,
    val trackId: String? = null
)

data class ResolvedStreamingTrack(
    val provider: StreamingProvider,
    val title: String,
    val artist: String,
    val sourceUrl: String,
    val matchedLocalTrack: AudioTrackItem? = null,
    val deezerFallback: DeezerSearchTrack? = null
)

enum class ExportFormat(val extension: String, val mimeType: String, val badge: String) {
    M4A("m4a", "audio/mp4", "M4A 256K"),
    WAV("wav", "audio/wav", "WAV 16B")
}

data class ExportedRemix(
    val id: String,
    val displayName: String,
    val uriString: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val format: ExportFormat
)

data class MeterReading(
    val leftPeak: Float = 0f,
    val rightPeak: Float = 0f,
    val leftPeakHold: Float = 0f,
    val rightPeakHold: Float = 0f
)
