package com.zaagforge.studio.dsp

import com.zaagforge.studio.model.KickType
import com.zaagforge.studio.model.RemixParams
import com.zaagforge.studio.model.RemixRenderResult
import com.zaagforge.studio.model.SectionKind
import com.zaagforge.studio.model.SectionMarker
import com.zaagforge.studio.model.TrackAnalysis
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight JNI return containers (~2 KB each; full stereo PCM lives strictly
 * in C++ native heap -> ZERO Android 256 MB Java heap OOM!).
 */
class NativeAnalysisOutput(
    val peaks: FloatArray,
    val metadataJson: String
)

class NativeRemixMetadata(
    val peaks: FloatArray,
    val metadataJson: String
)

object NativeDspBridge {
    init {
        System.loadLibrary("zaagforge_dsp")
    }

    @JvmStatic
    external fun nativeSessionGetProgress(): Int

    @JvmStatic
    external fun nativeSessionRequestCancel()

    @JvmStatic
    external fun nativeSessionBeginDecode(srcSampleRate: Int, srcChannels: Int, estimatedFrames: Int)

    @JvmStatic
    external fun nativeSessionUpdateFormat(srcSampleRate: Int, srcChannels: Int)

    @JvmStatic
    external fun nativeSessionAppendPcm16(pcm16Chunk: ShortArray, sampleCount: Int): Boolean

    @JvmStatic
    external fun nativeSessionFinalizeAndAnalyze(): NativeAnalysisOutput?

    @JvmStatic
    external fun nativeSessionLoadDemo(style: Int, durationSec: Float): NativeAnalysisOutput?

    @JvmStatic
    external fun nativeSessionPreviewSections(
        dropMode: Int,
        buildupBars: Int,
        enableFakeDrop: Boolean,
        drop1BarNudge: Int,
        drop2BarNudge: Int,
        customDrop1Frac: Float,
        customDrop2Frac: Float,
        sourceBpmOverride: Float,
        gridPhaseNudgeMs: Float,
        gridBeatShift: Int
    ): String

    @JvmStatic
    external fun nativeSessionRenderRemix(
        targetBpm: Float,
        genreTheme: Int,
        tempoSyncMode: Int,
        drop1Kick: Int,
        drop2Kick: Int,
        drive: Float,
        zaagAggression: Float,
        bassSaturation: Float,
        originalMix: Float,
        vocalClarity: Float,
        epicVocalBoost: Float,
        vocalSidechain: Float,
        kickLevel: Float,
        bassCutHz: Float,
        synthLayerAmount: Float,
        percGrooveAmount: Float,
        masterMode: Int,
        vocalPitchSemitones: Float,
        stutterAmount: Float,
        enableFakeDrop: Boolean,
        enableScreeches: Boolean,
        enablePitchKickMelody: Boolean,
        enableTripletRolls: Boolean,
        kickSwitchBars: Int,
        sourceBpmOverride: Float,
        gridPhaseNudgeMs: Float,
        gridBeatShift: Int,
        dropMode: Int,
        buildupBars: Int,
        drop1BarNudge: Int,
        drop2BarNudge: Int,
        customDrop1Frac: Float,
        customDrop2Frac: Float
    ): NativeRemixMetadata?

    @JvmStatic
    external fun nativeSessionGetFrameCount(isRemix: Boolean): Int

    @JvmStatic
    external fun nativeSessionReadFloatChunk(
        isRemix: Boolean,
        startFrame: Int,
        numFrames: Int,
        outBuf: FloatArray
    ): Int

    @JvmStatic
    external fun nativeSessionReadPcm16Chunk(
        isRemix: Boolean,
        startFrame: Int,
        numFrames: Int,
        outBuf: ShortArray
    ): Int

    @JvmStatic
    external fun nativeSessionClear()

    @JvmStatic
    external fun nativeSynthesizeKickPreview(
        kickType: Int,
        rootHz: Float,
        bpm: Float,
        drive: Float,
        aggression: Float,
        bassSaturation: Float
    ): FloatArray

    fun finalizeDecodeAndAnalyze(): TrackAnalysis {
        val raw = nativeSessionFinalizeAndAnalyze()
            ?: throw IllegalStateException("Décodage audio vide.")
        return parseAnalysisOutput(raw)
    }

    fun loadDemoAndAnalyze(styleIndex: Int, durationSec: Float = 30f): TrackAnalysis {
        val raw = nativeSessionLoadDemo(styleIndex, durationSec)
            ?: throw IllegalStateException("Impossible de générer la piste démo.")
        return parseAnalysisOutput(raw)
    }

    fun previewSections(params: RemixParams): List<SectionMarker> {
        val p = params.sanitized()
        val json = nativeSessionPreviewSections(
            dropMode = p.dropMode.nativeId,
            buildupBars = p.buildupBars,
            enableFakeDrop = p.enableFakeDrop,
            drop1BarNudge = p.drop1BarNudge,
            drop2BarNudge = p.drop2BarNudge,
            customDrop1Frac = p.customDrop1Frac,
            customDrop2Frac = p.customDrop2Frac,
            sourceBpmOverride = p.sourceBpmOverride,
            gridPhaseNudgeMs = p.gridPhaseNudgeMs,
            gridBeatShift = p.gridBeatShift
        )
        return runCatching { parseSections(JSONArray(json)) }.getOrDefault(emptyList())
    }

    fun renderSessionRemix(params: RemixParams, fallbackAnalysis: TrackAnalysis): RemixRenderResult? {
        val p = params.sanitized()
        val raw = nativeSessionRenderRemix(
            targetBpm = p.targetBpm,
            genreTheme = p.genreTheme.nativeId,
            tempoSyncMode = p.tempoSyncMode.nativeId,
            drop1Kick = p.drop1Kick.nativeId,
            drop2Kick = p.drop2Kick.nativeId,
            drive = p.drive,
            zaagAggression = p.zaagAggression,
            bassSaturation = p.bassSaturation,
            originalMix = p.originalMix,
            vocalClarity = p.vocalClarity,
            epicVocalBoost = p.epicVocalBoost,
            vocalSidechain = p.vocalSidechain,
            kickLevel = p.kickLevel,
            bassCutHz = p.bassCutHz,
            synthLayerAmount = p.synthLayerAmount,
            percGrooveAmount = p.percGrooveAmount,
            masterMode = p.masterMode.nativeId,
            vocalPitchSemitones = p.vocalPitchSemitones.toFloat(),
            stutterAmount = p.stutterAmount,
            enableFakeDrop = p.enableFakeDrop,
            enableScreeches = p.enableScreeches,
            enablePitchKickMelody = p.enablePitchKickMelody,
            enableTripletRolls = p.enableTripletRolls,
            kickSwitchBars = p.kickSwitchBars,
            sourceBpmOverride = p.sourceBpmOverride,
            gridPhaseNudgeMs = p.gridPhaseNudgeMs,
            gridBeatShift = p.gridBeatShift,
            dropMode = p.dropMode.nativeId,
            buildupBars = p.buildupBars,
            drop1BarNudge = p.drop1BarNudge,
            drop2BarNudge = p.drop2BarNudge,
            customDrop1Frac = p.customDrop1Frac,
            customDrop2Frac = p.customDrop2Frac
        ) ?: return null

        val obj = JSONObject(raw.metadataJson)
        val sections = parseSections(obj.optJSONArray("sections"))
        val totalFrames = obj.optInt("totalFrames", nativeSessionGetFrameCount(true))
        return RemixRenderResult(
            totalFrames = totalFrames,
            waveformPeaks = raw.peaks,
            sections = sections,
            sourceBpm = obj.optDouble("sourceBpm", fallbackAnalysis.bpm.toDouble()).toFloat(),
            effectiveTargetBpm = obj.optDouble("effectiveTargetBpm", p.targetBpm.toDouble()).toFloat(),
            tempoStretchRatio = obj.optDouble("tempoStretchRatio", 1.0).toFloat(),
            rootHz = obj.optDouble("rootHz", fallbackAnalysis.rootHz.toDouble()).toFloat(),
            keyName = obj.optString("keyName", fallbackAnalysis.keyName),
            durationSec = obj.optDouble("durationSec", totalFrames / 44100.0).toFloat(),
            peakDb = obj.optDouble("peakDb", -0.2).toFloat(),
            rmsDb = obj.optDouble("rmsDb", -8.5).toFloat(),
            renderTimeMs = obj.optDouble("renderTimeMs", 0.0).toFloat()
        )
    }

    fun synthesizeKickPreview(
        kickType: KickType,
        rootHz: Float,
        bpm: Float,
        drive: Float,
        aggression: Float,
        bassSaturation: Float
    ): FloatArray = nativeSynthesizeKickPreview(
        kickType.nativeId,
        rootHz.coerceIn(41.2f, 73.5f),
        bpm.coerceIn(124f, 240f),
        drive.coerceIn(0.1f, 1f),
        aggression.coerceIn(0.1f, 1f),
        bassSaturation.coerceIn(0f, 1f)
    )

    private fun parseAnalysisOutput(raw: NativeAnalysisOutput): TrackAnalysis {
        val obj = JSONObject(raw.metadataJson)
        val sections = parseSections(obj.optJSONArray("sections"))
        val totalFrames = obj.optInt("totalFrames", 0)
        return TrackAnalysis(
            bpm = obj.optDouble("bpm", 128.0).toFloat(),
            bpmConfidence = obj.optDouble("bpmConfidence", 0.5).toFloat(),
            beatOffsetSec = obj.optDouble("beatOffsetSec", 0.0).toFloat(),
            downbeatBeatIndex = obj.optInt("downbeatBeatIndex", 0),
            downbeatSec = obj.optDouble("downbeatSec", 0.0).toFloat(),
            totalBars = obj.optInt("totalBars", 8),
            chorus1Bar = obj.optInt("chorus1Bar", 4),
            chorus2Bar = obj.optInt("chorus2Bar", 8),
            chorus1Sec = obj.optDouble("chorus1Sec", 0.0).toFloat(),
            chorus2Sec = obj.optDouble("chorus2Sec", 0.0).toFloat(),
            keyIndex = obj.optInt("keyIndex", 5),
            isMinor = obj.optBoolean("isMinor", true),
            rootMidi = obj.optInt("rootMidi", 29),
            rootHz = obj.optDouble("rootHz", 43.65).toFloat(),
            keyName = obj.optString("keyName", "Fm"),
            durationSec = obj.optDouble("durationSec", totalFrames / 44100.0).toFloat(),
            avgRmsDb = obj.optDouble("avgRmsDb", -14.0).toFloat(),
            totalFrames = totalFrames,
            sections = sections,
            waveformPeaks = raw.peaks
        )
    }

    private fun parseSections(array: JSONArray?): List<SectionMarker> {
        if (array == null || array.length() == 0) {
            return listOf(SectionMarker(0f, 1f, SectionKind.DROP_1, "DROP 1"))
        }
        val list = ArrayList<SectionMarker>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val kind = SectionKind.fromNativeId(item.optInt("type", SectionKind.DROP_1.nativeId))
            list.add(
                SectionMarker(
                    startFrac = item.optDouble("startFrac", 0.0).toFloat(),
                    endFrac = item.optDouble("endFrac", 1.0).toFloat(),
                    kind = kind,
                    label = item.optString("label", kind.shortLabel),
                    startBar = item.optInt("startBar", 0),
                    endBar = item.optInt("endBar", 0)
                )
            )
        }
        return list
    }
}
