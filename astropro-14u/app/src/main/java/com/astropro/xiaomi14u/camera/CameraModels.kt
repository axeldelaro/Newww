package com.astropro.xiaomi14u.camera

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.util.Size

enum class LensAccessTier {
    DIRECT_PHYSICAL,
    LOGICAL_PHYSICAL_STREAM,
    LOGICAL_ZOOM_RATIO
}

data class LensInfo(
    val id: String,
    val openCameraId: String,
    val physicalCameraId: String? = null,
    val accessTier: LensAccessTier = LensAccessTier.DIRECT_PHYSICAL,
    val zoomRatioFallback: Float = 1.0f,
    val zoomBadge: String,              // "0.5", "1", "3.2", "5"
    val focalLengthMm: Float,           // Real physical focal length in mm
    val focalLength35mmEq: Int,         // e.g. 12, 23, 75, 120
    val displayName: String,            // e.g. "23mm"
    val sensorHint: String,             // e.g. "LYT-900 1\""
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
    val pixelPitchUm: Float,
    val availableApertures: List<Float>,
    val defaultAperture: Float,
    val minIso: Int,
    val maxIso: Int,
    val minExposureNs: Long,
    val maxExposureNs: Long,
    val minFocusDistance: Float,        // diopters (0 = infinity)
    val supportsRaw: Boolean,
    val rawSize: Size?,
    val yuvCaptureSize: Size,
    val previewSize: Size,
    val cfaPattern: Int,                // 0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR
    val blackLevel: FloatArray,
    val whiteLevel: Float,
    val hardwareLevel: Int,
    val sensorOrientation: Int,
    val isFront: Boolean,
    val isLogicalMultiCamera: Boolean,
    val physicalIds: List<String>
) {
    val minExposureSec: Double get() = minExposureNs / 1_000_000_000.0
    val maxExposureSec: Double get() = maxExposureNs / 1_000_000_000.0
    val hasVariableAperture: Boolean get() = availableApertures.size > 1

    fun npfSeconds(apertureF: Float = defaultAperture, declinationDeg: Double = 0.0): Double {
        return NpfCalculator.computeNpfSeconds(
            apertureF = apertureF.toDouble(),
            pixelPitchUm = pixelPitchUm.toDouble(),
            realFocalLengthMm = focalLengthMm.toDouble(),
            declinationDeg = declinationDeg
        )
    }

    fun hardwareLevelLabel(): String = when (hardwareLevel) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "LEGACY"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LensInfo) return false
        return id == other.id && openCameraId == other.openCameraId && physicalCameraId == other.physicalCameraId
    }

    override fun hashCode(): Int = id.hashCode() * 31 + openCameraId.hashCode()
}

data class RawProbeEntry(
    val cameraId: String,
    val facing: String,
    val isLogicalMulti: Boolean,
    val physicalSubIds: List<String>,
    val focalLengthsMm: List<Float>,
    val eq35mm: Int,
    val apertures: List<Float>,
    val sensorSizeMm: String,
    val activeArray: String,
    val pixelPitchUm: Float,
    val isoRange: String,
    val exposureRangeSec: String,
    val supportsRaw: Boolean,
    val rawMaxSize: String,
    val hardwareLevel: String,
    val cfaName: String,
    val npfSec: Double,
    val discoveredVia: String
)

enum class CaptureMode(val label: String, val shortLabel: String) {
    CALIBRATION("CALIBRATION", "CALIBRATION"),
    TIMELAPSE("TIMELAPSE", "TIMELAPSE"),
    STAR_TRAILS("STAR TRAILS", "STAR TRAILS"),
    HDR_ASTRO("HDR ASTRO", "HDR Astro"),
    ASTRO_PRO("ASTRO PRO", "ASTRO PRO"),
    DEEP_SKY("CIEL PROFOND", "CIEL PROFOND"),
    LUNAR_PLANETARY("LUNE / PLANÈTES", "LUNE / PLANÈTES")
}

enum class ProDialType(val tileLabel: String) {
    WB("WB"),
    FOCUS("F"),
    SHUTTER("S"),
    ISO("ISO"),
    APERTURE("f/"),
    EV("EV")
}

enum class TimerOption(val seconds: Int, val label: String) {
    OFF(0, "OFF"),
    S2(2, "2s"),
    S5(5, "5s"),
    S10(10, "10s")
}

enum class OutputFormatOption(val label: String) {
    DNG_16B("DNG 16b"),
    RAW_PLUS_JPG("RAW+JPG")
}

enum class StarTrailStyle(val label: String) {
    STANDARD("Standard"),
    COMET("Comète")
}

enum class CometTailLength(val label: String, val decay: Float) {
    SHORT("Court", 0.93f),
    MEDIUM("Moyen", 0.965f),
    LONG("Long", 0.988f)
}

enum class CalibrationKind(val label: String, val nativeType: Int) {
    DARK("DARK", 0),
    FLAT("FLAT", 1),
    BIAS("BIAS", 2)
}

data class ManualSettings(
    val wbAuto: Boolean = false,
    val wbKelvin: Int = 4200,
    val focusAuto: Boolean = false,
    val focusDistance: Float = 0.0f, // 0.0f = infinity
    val shutterAuto: Boolean = false,
    val shutterSeconds: Double = 8.0,
    val isoAuto: Boolean = false,
    val iso: Int = 1600,
    val aperture: Float = 1.63f,
    val evCompensation: Int = 0 // tenths of EV * 3 or discrete -12..+12 representing -4.0..+4.0 in 1/3 EV
) {
    val evFloat: Float get() = evCompensation / 3.0f
}

data class ModeOptions(
    // Deep Sky
    val deepSkyFrames: Int = 40,
    val deepSkySigmaKappa: Float = 2.5f,
    val useAutoDark: Boolean = true,
    val useAutoFlat: Boolean = true,
    val deepSkyLandscapeAuto: Boolean = true,
    val deepSkyDrizzle2x: Boolean = false,
    // HDR Astro
    val hdrBrackets: Int = 3, // 3 (-2/0/+2 EV) or 5 (-3/-1.5/0/+1.5/+3 EV)
    // Lunar / Planetary
    val planetaryFrames: Int = 60,
    val planetaryKeepPercent: Float = 0.25f,
    val planetaryAutoWavelets: Boolean = true,
    val planetaryDrizzle2x: Boolean = false,
    // Star Trails
    val starTrailStyle: StarTrailStyle = StarTrailStyle.STANDARD,
    val cometTailLength: CometTailLength = CometTailLength.MEDIUM,
    val starTrailMaxFrames: Int = 120,
    // Timelapse
    val timelapseIntervalSec: Int = 5,
    val timelapseCount: Int = 120, // 0 = unlimited
    val timelapseFps: Int = 24,
    val timelapseArchiveRaw: Boolean = false,
    // Calibration
    val calibrationKind: CalibrationKind = CalibrationKind.DARK,
    val calibrationFrames: Int = 15
)

data class AutoFocusSample(
    val diopter: Float,
    val fwhmPx: Float,
    val hfdPx: Float,
    val sharpnessScore: Float,
    val starFound: Boolean
)

data class AutoFocusState(
    val running: Boolean = false,
    val currentStep: Int = 0,
    val totalSteps: Int = 12,
    val currentDiopter: Float = 0.0f,
    val samples: List<AutoFocusSample> = emptyList(),
    val bestDiopter: Float? = null,
    val bestFwhmPx: Float? = null,
    val statusText: String = ""
)

data class HistogramData(
    val r: IntArray = IntArray(64),
    val g: IntArray = IntArray(64),
    val b: IntArray = IntArray(64)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HistogramData) return false
        return r.contentEquals(other.r) && g.contentEquals(other.g) && b.contentEquals(other.b)
    }

    override fun hashCode(): Int = r.contentHashCode()
}

data class FocusLoupeState(
    val enabled: Boolean = false,
    val normX: Float = 0.5f,
    val normY: Float = 0.5f,
    val fwhm: Float = 0f,
    val hfd: Float = 0f,
    val snr: Float = 0f,
    val tenengrad: Float = 0f,
    val starFound: Boolean = false,
    val bitmap: Bitmap? = null,
    val history: List<Float> = emptyList() // recent sharpness/inverse-FWHM sparkline
)

data class FocusPeakingState(
    val pointsXY: FloatArray = FloatArray(0),
    val pointCount: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FocusPeakingState) return false
        return pointCount == other.pointCount && pointsXY.contentEquals(other.pointsXY)
    }

    override fun hashCode(): Int = 31 * pointCount + pointsXY.contentHashCode()
}

data class CaptureProgressState(
    val active: Boolean = false,
    val mode: CaptureMode = CaptureMode.ASTRO_PRO,
    val countdownSec: Int = 0,
    val framesCaptured: Int = 0,
    val framesTarget: Int = 1, // 0 = unlimited
    val framesAccepted: Int = 0,
    val framesRejected: Int = 0,
    val vibrationRejectedCount: Int = 0,
    val landscapeHorizonDetected: Boolean = false,
    val starsDetected: Int = 0,
    val residualPx: Float = 0f,
    val elapsedExposureSec: Double = 0.0,
    val exposureProgress: Float = 0f,
    val remainingExposureSec: Double = 0.0,
    val estimatedRemainingTotalSec: Double = 0.0,
    val statusText: String = "",
    val liveStackPreview: Bitmap? = null,
    val isFinalizing: Boolean = false
)

data class CalibrationMasterInfo(
    val id: String,
    val kind: CalibrationKind,
    val filePath: String,
    val lensLabel: String,
    val focalMm: Float,
    val iso: Int,
    val exposureSec: Float,
    val frameCount: Int,
    val isRawBayer: Boolean,
    val createdAt: String
)

data class AstroSessionItem(
    val id: String,
    val astro32Path: String,
    val thumbPath: String,
    val title: String,
    val modeName: String,
    val lensName: String,
    val exposureSec: Float,
    val iso: Int,
    val stackCount: Int,
    val dateObs: String
)

data class LevelOrientationState(
    val rollDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val azimuthDeg: Float = 0f,
    val altitudeDeg: Float = 0f,
    val cameraAzimuthDeg: Float = 0f,
    val cameraAltitudeDeg: Float = 0f,
    val cameraRollDeg: Float = 0f
) {
    val isLevel: Boolean get() = kotlin.math.abs(rollDeg) < 0.5f && kotlin.math.abs(pitchDeg) < 0.5f
}

enum class AppScreen {
    CAMERA,
    LABO,
    EPHEMERIS,
    LENS_PROBE,
    CALIBRATION
}
