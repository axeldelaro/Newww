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
 * Raw JNI return containers (kept via proguard-rules.pro).
 */
class NativeAnalysisOutput(
    val peaks: FloatArray,
    val metadataJson: String
)

class NativeRemixOutput(
    val pcm: FloatArray,
    val peaks: FloatArray,
    val metadataJson: String
)

object NativeDspBridge {
    init {
        System.loadLibrary("zaagforge_dsp")
    }

    @JvmStatic
    external fun nativeAnalyzeTrack(stereoPcm: FloatArray, sampleRate: Int): NativeAnalysisOutput

    @JvmStatic
    external fun nativeRenderRemix(
        stereoPcm: FloatArray,
        sampleRate: Int,
        sourceBpm: Float,
        beatOffsetSec: Float,
        keyIndex: Int,
        isMinor: Boolean,
        rootHz: Float,
        targetBpm: Float,
        drop1Kick: Int,
        drop2Kick: Int,
        drive: Float,
        zaagAggression: Float,
        originalMix: Float,
        vocalPitchSemitones: Float,
        stutterAmount: Float,
        enableFakeDrop: Boolean,
        enableScreeches: Boolean,
        enablePitchKickMelody: Boolean,
        enableTripletRolls: Boolean,
        kickSwitchBars: Int,
        sectionStartFracs: FloatArray,
        sectionEndFracs: FloatArray,
        sectionTypes: IntArray
    ): NativeRemixOutput

    @JvmStatic
    external fun nativeSynthesizeKickPreview(
        kickType: Int,
        rootHz: Float,
        bpm: Float,
        drive: Float,
        aggression: Float
    ): FloatArray

    @JvmStatic
    external fun nativeGenerateDemoTrack(style: Int, durationSec: Float): FloatArray

    fun analyzeTrack(stereoPcm: FloatArray, sampleRate: Int = 44100): TrackAnalysis {
        val raw = nativeAnalyzeTrack(stereoPcm, sampleRate)
        val obj = JSONObject(raw.metadataJson)
        val sections = parseSections(obj.optJSONArray("sections"))
        return TrackAnalysis(
            bpm = obj.optDouble("bpm", 128.0).toFloat(),
            bpmConfidence = obj.optDouble("bpmConfidence", 0.5).toFloat(),
            beatOffsetSec = obj.optDouble("beatOffsetSec", 0.0).toFloat(),
            keyIndex = obj.optInt("keyIndex", 5),
            isMinor = obj.optBoolean("isMinor", true),
            rootMidi = obj.optInt("rootMidi", 29),
            rootHz = obj.optDouble("rootHz", 43.65).toFloat(),
            keyName = obj.optString("keyName", "Fm"),
            durationSec = obj.optDouble("durationSec", stereoPcm.size / 2.0 / sampleRate).toFloat(),
            avgRmsDb = obj.optDouble("avgRmsDb", -14.0).toFloat(),
            sections = sections,
            waveformPeaks = raw.peaks
        )
    }

    fun renderRemix(
        stereoPcm: FloatArray,
        analysis: TrackAnalysis,
        params: RemixParams,
        sampleRate: Int = 44100
    ): RemixRenderResult {
        val p = params.sanitized()
        val starts = FloatArray(analysis.sections.size) { analysis.sections[it].startFrac }
        val ends = FloatArray(analysis.sections.size) { analysis.sections[it].endFrac }
        val types = IntArray(analysis.sections.size) { analysis.sections[it].kind.nativeId }

        val raw = nativeRenderRemix(
            stereoPcm = stereoPcm,
            sampleRate = sampleRate,
            sourceBpm = analysis.bpm,
            beatOffsetSec = analysis.beatOffsetSec,
            keyIndex = analysis.keyIndex,
            isMinor = analysis.isMinor,
            rootHz = analysis.rootHz,
            targetBpm = p.targetBpm,
            drop1Kick = p.drop1Kick.nativeId,
            drop2Kick = p.drop2Kick.nativeId,
            drive = p.drive,
            zaagAggression = p.zaagAggression,
            originalMix = p.originalMix,
            vocalPitchSemitones = p.vocalPitchSemitones.toFloat(),
            stutterAmount = p.stutterAmount,
            enableFakeDrop = p.enableFakeDrop,
            enableScreeches = p.enableScreeches,
            enablePitchKickMelody = p.enablePitchKickMelody,
            enableTripletRolls = p.enableTripletRolls,
            kickSwitchBars = p.kickSwitchBars,
            sectionStartFracs = starts,
            sectionEndFracs = ends,
            sectionTypes = types
        )

        val obj = JSONObject(raw.metadataJson)
        val sections = parseSections(obj.optJSONArray("sections"))
        return RemixRenderResult(
            stereoPcm = raw.pcm,
            waveformPeaks = raw.peaks,
            sections = sections,
            sourceBpm = obj.optDouble("sourceBpm", analysis.bpm.toDouble()).toFloat(),
            effectiveTargetBpm = obj.optDouble("effectiveTargetBpm", p.targetBpm.toDouble()).toFloat(),
            tempoStretchRatio = obj.optDouble("tempoStretchRatio", 1.0).toFloat(),
            rootHz = obj.optDouble("rootHz", analysis.rootHz.toDouble()).toFloat(),
            keyName = obj.optString("keyName", analysis.keyName),
            durationSec = obj.optDouble("durationSec", raw.pcm.size / 2.0 / sampleRate).toFloat(),
            peakDb = obj.optDouble("peakDb", -0.2).toFloat(),
            rmsDb = obj.optDouble("rmsDb", -6.5).toFloat()
        )
    }

    fun synthesizeKickPreview(
        kickType: KickType,
        rootHz: Float,
        bpm: Float,
        drive: Float,
        aggression: Float
    ): FloatArray = nativeSynthesizeKickPreview(
        kickType.nativeId,
        rootHz.coerceIn(41.2f, 73.5f),
        bpm.coerceIn(140f, 240f),
        drive.coerceIn(0.1f, 1f),
        aggression.coerceIn(0.1f, 1f)
    )

    fun generateDemoTrack(styleIndex: Int, durationSec: Float = 32f): FloatArray =
        nativeGenerateDemoTrack(styleIndex, durationSec)

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
                    label = item.optString("label", kind.shortLabel)
                )
            )
        }
        return list
    }
}
