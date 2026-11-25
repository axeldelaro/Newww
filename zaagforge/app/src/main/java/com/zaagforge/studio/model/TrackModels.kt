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

enum class MasterMode(val nativeId: Int, val label: String, val subtitle: String) {
    STUDIO_CLEAN(0, "STUDIO CLEAN", "Voix limpides & basses nettes"),
    CLUB_PUNCH(1, "CLUB PUNCH", "Glue club & punch maîtrisé"),
    FESTIVAL_HARD(2, "FESTIVAL HARD", "Loudness max & clipper 2×");

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
    val rmsDb: Float
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
