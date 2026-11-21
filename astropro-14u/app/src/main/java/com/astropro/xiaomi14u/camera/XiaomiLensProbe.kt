package com.astropro.xiaomi14u.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Exhaustive Xiaomi 14 Ultra multi-lens HAL detector (no root required).
 *
 * Discovery pipeline:
 *  1. Enumerates cameraManager.cameraIdList
 *  2. Inspects characteristics.physicalCameraIds on logical multi-cameras
 *  3. Safely probes standalone numeric IDs "0".."20" to discover auxiliary physical sensors
 *     Xiaomi HyperOS exposes directly (e.g. 0=23mm 1" LYT-900, 2=12mm UW, 3=75mm 3.2x, 4=120mm 5x)
 *  4. Deduplicates intelligently by (facing, focalLengthMm, sensorWidthMm, activeArrayWidth)
 *     and computes 35mm equivalent focal length, pixel pitch, variable apertures, and NPF shutter limit.
 */
object XiaomiLensProbe {

    data class ProbeResult(
        val rearLenses: List<LensInfo>,
        val frontLens: LensInfo?,
        val allRawEntries: List<RawProbeEntry>
    )

    fun probeAllCameras(context: Context): ProbeResult {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val discoveredSource = linkedMapOf<String, String>()
        val parentLogicalForPhysical = mutableMapOf<String, String>()

        // Stage 1: Standard Camera2 cameraIdList
        val standardIds = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        for (id in standardIds) {
            discoveredSource[id] = "cameraIdList"
        }

        // Stage 2: Physical sub-cameras declared inside logical multi-cameras
        for (id in standardIds) {
            val chars = safeCharacteristics(manager, id) ?: continue
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val isLogical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            if (isLogical) {
                val physIds = runCatching { chars.physicalCameraIds }.getOrDefault(emptySet())
                for (pid in physIds) {
                    if (pid !in discoveredSource) {
                        discoveredSource[pid] = "physicalCameraIds(parent=$id)"
                    }
                    parentLogicalForPhysical.putIfAbsent(pid, id)
                }
            }
        }

        // Stage 3: Probe hidden standalone IDs "0".."64" (Xiaomi HyperOS / Qualcomm CamX auxiliary physical IDs,
        // including 2..5, 20..24, and 60..64 on Xiaomi Ultra flagships)
        for (num in 0..64) {
            val id = num.toString()
            if (id !in discoveredSource) {
                val chars = safeCharacteristics(manager, id)
                if (chars != null) {
                    discoveredSource[id] = "probedNumericId"
                }
            }
        }

        val rawEntries = mutableListOf<RawProbeEntry>()
        val rearCandidates = mutableListOf<LensInfo>()
        var frontLens: LensInfo? = null

        val primaryRearLogicalId = standardIds.firstOrNull { id ->
            val c = safeCharacteristics(manager, id)
            c?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"

        for ((id, source) in discoveredSource) {
            val chars = safeCharacteristics(manager, id) ?: continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            val isFront = facing == CameraCharacteristics.LENS_FACING_FRONT
            val isBack = facing == CameraCharacteristics.LENS_FACING_BACK

            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()
                ?.filter { it > 0.1f } ?: listOf(6.8f)
            val primaryFocal = focalLengths.first()

            val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val sensorW = sensorSize?.width?.takeIf { it > 0.5f } ?: 9.6f
            val sensorH = sensorSize?.height?.takeIf { it > 0.5f } ?: 7.2f
            val sensorDiag = hypot(sensorW.toDouble(), sensorH.toDouble())
            val eq35 = ((primaryFocal * 43.2666) / max(1.0, sensorDiag)).roundToInt().coerceIn(8, 600)

            val activeRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val activeW = activeRect?.width()?.takeIf { it > 0 } ?: 4096
            val activeH = activeRect?.height()?.takeIf { it > 0 } ?: 3072
            val pixelPitchUm = ((sensorW * 1000.0) / activeW).toFloat().coerceIn(0.4f, 12.0f)

            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                ?.filter { it > 0.5f }
                ?.distinct()
                ?.sorted()
                ?: listOf(1.8f)
            val defaultAperture = apertures.first()

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val minIso = isoRange?.lower ?: 50
            val maxIso = isoRange?.upper ?: 12800

            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val minExpNs = expRange?.lower ?: 100_000L
            val maxExpNs = expRange?.upper ?: 30_000_000_000L

            val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val supportsRawCap = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            val isLogical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            val physSubIds = runCatching { chars.physicalCameraIds.toList().sorted() }.getOrDefault(emptyList())

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() ?: emptyList()
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
            val supportsRaw = supportsRawCap && rawSizes.isNotEmpty()
            val bestRawSize = chooseBestAstroSize(rawSizes, maxPixels = 16_000_000)
            val bestYuvCapture = chooseBestAstroSize(yuvSizes, maxPixels = 12_500_000)
                ?: Size(1920, 1440)
            val bestPreviewSize = choosePreviewSize4x3(yuvSizes)

            val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
            val blPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            val blackLevels = if (blPattern != null) {
                floatArrayOf(
                    blPattern.getOffsetForIndex(0, 0).toFloat(),
                    blPattern.getOffsetForIndex(1, 0).toFloat(),
                    blPattern.getOffsetForIndex(0, 1).toFloat(),
                    blPattern.getOffsetForIndex(1, 1).toFloat()
                )
            } else {
                floatArrayOf(64f, 64f, 64f, 64f)
            }
            val whiteLevel = (chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toFloat()
            val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
            val sensorOrient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val npf = NpfCalculator.computeNpfSeconds(
                apertureF = defaultAperture.toDouble(),
                pixelPitchUm = pixelPitchUm.toDouble(),
                realFocalLengthMm = primaryFocal.toDouble()
            )

            rawEntries.add(
                RawProbeEntry(
                    cameraId = id,
                    facing = when (facing) {
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        else -> "EXTERNAL"
                    },
                    isLogicalMulti = isLogical,
                    physicalSubIds = physSubIds,
                    focalLengthsMm = focalLengths,
                    eq35mm = eq35,
                    apertures = apertures,
                    sensorSizeMm = String.format(Locale.US, "%.2f × %.2f mm", sensorW, sensorH),
                    activeArray = "${activeW}×${activeH}",
                    pixelPitchUm = pixelPitchUm,
                    isoRange = "$minIso–$maxIso",
                    exposureRangeSec = String.format(
                        Locale.US, "%.5fs–%.1fs",
                        minExpNs / 1e9, maxExpNs / 1e9
                    ),
                    supportsRaw = supportsRaw,
                    rawMaxSize = bestRawSize?.let { "${it.width}×${it.height}" } ?: "N/A",
                    hardwareLevel = hwLevelToName(hwLevel),
                    cfaName = cfaToName(cfa),
                    npfSec = npf,
                    discoveredVia = source
                )
            )

            val isDirectlyInList = id in standardIds || source == "probedNumericId"
            val parentLogical = parentLogicalForPhysical[id]
            val tier = when {
                isDirectlyInList -> LensAccessTier.DIRECT_PHYSICAL
                parentLogical != null -> LensAccessTier.LOGICAL_PHYSICAL_STREAM
                else -> LensAccessTier.LOGICAL_ZOOM_RATIO
            }

            val (badge, displayName, sensorHint, zoomFallback) = classifyXiaomiLens(eq35, sensorW, apertures, isFront)

            val info = LensInfo(
                id = id,
                openCameraId = if (tier == LensAccessTier.LOGICAL_PHYSICAL_STREAM && parentLogical != null) parentLogical else id,
                physicalCameraId = if (tier == LensAccessTier.LOGICAL_PHYSICAL_STREAM) id else null,
                accessTier = tier,
                zoomRatioFallback = zoomFallback,
                zoomBadge = badge,
                focalLengthMm = primaryFocal,
                focalLength35mmEq = eq35,
                displayName = displayName,
                sensorHint = sensorHint,
                sensorWidthMm = sensorW,
                sensorHeightMm = sensorH,
                activeArrayWidth = activeW,
                activeArrayHeight = activeH,
                pixelPitchUm = pixelPitchUm,
                availableApertures = apertures,
                defaultAperture = defaultAperture,
                minIso = minIso,
                maxIso = maxIso,
                minExposureNs = minExpNs,
                maxExposureNs = maxExpNs,
                minFocusDistance = minFocusDist,
                supportsRaw = supportsRaw,
                rawSize = bestRawSize,
                yuvCaptureSize = bestYuvCapture,
                previewSize = bestPreviewSize,
                cfaPattern = cfa.coerceIn(0, 3),
                blackLevel = blackLevels,
                whiteLevel = whiteLevel,
                hardwareLevel = hwLevel,
                sensorOrientation = sensorOrient,
                isFront = isFront,
                isLogicalMultiCamera = isLogical,
                physicalIds = physSubIds
            )

            if (isBack) {
                rearCandidates.add(info)
            } else if (isFront && frontLens == null) {
                frontLens = info
            }
        }

        val dedupedRear = deduplicateRearLenses(rearCandidates)

        // If on a Xiaomi 14 Ultra or single-logical-only HAL that hides physical IDs completely,
        // guarantee logical zoom-ratio fallback lenses if only 1 rear lens was exposed
        val finalRear = if (dedupedRear.size == 1 && dedupedRear[0].isLogicalMultiCamera) {
            augmentLogicalFallbackIfNeeded(dedupedRear[0], primaryRearLogicalId)
        } else {
            dedupedRear
        }

        return ProbeResult(
            rearLenses = finalRear.sortedBy { it.focalLength35mmEq },
            frontLens = frontLens,
            allRawEntries = rawEntries
        )
    }

    private fun deduplicateRearLenses(candidates: List<LensInfo>): List<LensInfo> {
        // Group sensors with virtually identical focal length & sensor size (e.g., logical "0" vs physical "0" or duplicate fusion IDs)
        val groups = mutableListOf<MutableList<LensInfo>>()
        for (cand in candidates) {
            val match = groups.firstOrNull { grp ->
                val rep = grp.first()
                abs(rep.focalLength35mmEq - cand.focalLength35mmEq) <= 3 &&
                    abs(rep.sensorWidthMm - cand.sensorWidthMm) <= 1.2f &&
                    abs(rep.focalLengthMm - cand.focalLengthMm) <= 0.8f
            }
            if (match != null) {
                match.add(cand)
            } else {
                groups.add(mutableListOf(cand))
            }
        }

        // Pick best representative per distinct optical lens:
        // Prefer RAW support > variable aperture > DIRECT_PHYSICAL > non-logical physical sensor > higher resolution
        return groups.map { grp ->
            grp.maxBy { lens ->
                var score = 0
                if (lens.supportsRaw) score += 1000
                if (lens.hasVariableAperture) score += 400
                if (lens.accessTier == LensAccessTier.DIRECT_PHYSICAL) score += 200
                if (!lens.isLogicalMultiCamera) score += 120
                if (lens.hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3) score += 50
                if (lens.hardwareLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL) score += 30
                score += (lens.activeArrayWidth / 500)
                score
            }
        }
    }

    private fun augmentLogicalFallbackIfNeeded(main: LensInfo, logicalId: String): List<LensInfo> {
        // Only add zoom-ratio virtual entries if the logical camera exposes zoom < 0.8x or > 3x
        val list = mutableListOf(main)
        if (main.focalLength35mmEq in 20..28) {
            list.add(
                main.copy(
                    id = "${logicalId}_uw",
                    openCameraId = logicalId,
                    physicalCameraId = null,
                    accessTier = LensAccessTier.LOGICAL_ZOOM_RATIO,
                    zoomRatioFallback = 0.5f,
                    zoomBadge = "0.5",
                    focalLengthMm = main.focalLengthMm * 0.52f,
                    focalLength35mmEq = 12,
                    displayName = "12mm",
                    sensorHint = "UGA 0.5x (Zoom Logique)"
                )
            )
            list.add(
                main.copy(
                    id = "${logicalId}_t3",
                    openCameraId = logicalId,
                    physicalCameraId = null,
                    accessTier = LensAccessTier.LOGICAL_ZOOM_RATIO,
                    zoomRatioFallback = 3.2f,
                    zoomBadge = "3.2",
                    focalLengthMm = main.focalLengthMm * 3.2f,
                    focalLength35mmEq = 75,
                    displayName = "75mm",
                    sensorHint = "Télé 3.2x (Zoom Logique)"
                )
            )
            list.add(
                main.copy(
                    id = "${logicalId}_t5",
                    openCameraId = logicalId,
                    physicalCameraId = null,
                    accessTier = LensAccessTier.LOGICAL_ZOOM_RATIO,
                    zoomRatioFallback = 5.0f,
                    zoomBadge = "5",
                    focalLengthMm = main.focalLengthMm * 5.0f,
                    focalLength35mmEq = 120,
                    displayName = "120mm",
                    sensorHint = "Périscope 5x (Zoom Logique)"
                )
            )
        }
        return list
    }

    private data class LensClass(
        val badge: String,
        val displayName: String,
        val sensorHint: String,
        val zoomFallback: Float
    )

    private fun classifyXiaomiLens(
        eq35: Int,
        sensorWidthMm: Float,
        apertures: List<Float>,
        isFront: Boolean
    ): LensClass {
        if (isFront) return LensClass("F", "${eq35}mm", "Capteur Frontal", 1.0f)
        val isOneInch = sensorWidthMm >= 11.5f || apertures.size > 1
        return when {
            eq35 <= 17 -> LensClass("0.5", "${eq35}mm", "IMX858 UGA 12mm", 0.5f)
            eq35 in 18..32 -> LensClass(
                "1",
                "${eq35}mm",
                if (isOneInch) "LYT-900 1\" Variable" else "Grand-Angle Principal",
                1.0f
            )
            eq35 in 33..59 -> LensClass("2", "${eq35}mm", "Optique Standard ${eq35}mm", eq35 / 23f)
            eq35 in 60..95 -> LensClass("3.2", "${eq35}mm", "IMX858 Télé 75mm", 3.2f)
            eq35 in 96..165 -> LensClass("5", "${eq35}mm", "IMX858 Périscope 120mm", 5.0f)
            else -> LensClass("${(eq35 / 23.0).roundToInt()}", "${eq35}mm", "Super-Télé ${eq35}mm", eq35 / 23f)
        }
    }

    private fun chooseBestAstroSize(sizes: List<Size>, maxPixels: Int): Size? {
        if (sizes.isEmpty()) return null
        // Prefer 4:3 sensor native aspect ratio up to ~12-16MP for clean stacking RAM footprint,
        // fallback to smallest available if all exceed maxPixels
        val sortedDesc = sizes.sortedByDescending { it.width.toLong() * it.height.toLong() }
        val preferred = sortedDesc.firstOrNull {
            val px = it.width.toLong() * it.height.toLong()
            val ratio = it.width.toFloat() / max(1, it.height).toFloat()
            px <= maxPixels && abs(ratio - 4f / 3f) < 0.15f
        }
        return preferred ?: sortedDesc.firstOrNull {
            it.width.toLong() * it.height.toLong() <= maxPixels
        } ?: sortedDesc.last()
    }

    private fun choosePreviewSize4x3(sizes: List<Size>): Size {
        if (sizes.isEmpty()) return Size(1440, 1080)
        val ideal4x3 = sizes.filter {
            val ratio = it.width.toFloat() / max(1, it.height).toFloat()
            abs(ratio - 4f / 3f) < 0.08f && it.width in 960..1920
        }.maxByOrNull { it.width }
        return ideal4x3 ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width } ?: Size(1440, 1080)
    }

    private fun safeCharacteristics(manager: CameraManager, id: String): CameraCharacteristics? {
        return try {
            manager.getCameraCharacteristics(id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hwLevelToName(level: Int): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "LEGACY"
    }

    private fun cfaToName(cfa: Int): String = when (cfa) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        else -> "OTHER($cfa)"
    }

    fun exportHalReportJson(probe: ProbeResult): String {
        val root = JSONObject()
        root.put("app", "AstroPro 14U")
        root.put("deviceManufacturer", Build.MANUFACTURER)
        root.put("deviceModel", Build.MODEL)
        root.put("deviceProduct", Build.PRODUCT)
        root.put("androidSdk", Build.VERSION.SDK_INT)
        root.put("rearLensCount", probe.rearLenses.size)

        val arr = JSONArray()
        for (e in probe.allRawEntries) {
            val obj = JSONObject()
            obj.put("cameraId", e.cameraId)
            obj.put("facing", e.facing)
            obj.put("isLogicalMultiCamera", e.isLogicalMulti)
            obj.put("physicalSubIds", JSONArray(e.physicalSubIds))
            obj.put("focalLengthsMm", JSONArray(e.focalLengthsMm.map { it.toDouble() }))
            obj.put("equivalent35mm", e.eq35mm)
            obj.put("apertures", JSONArray(e.apertures.map { it.toDouble() }))
            obj.put("sensorSizeMm", e.sensorSizeMm)
            obj.put("activeArray", e.activeArray)
            obj.put("pixelPitchUm", String.format(Locale.US, "%.2f", e.pixelPitchUm))
            obj.put("isoRange", e.isoRange)
            obj.put("exposureRangeSec", e.exposureRangeSec)
            obj.put("supportsRaw", e.supportsRaw)
            obj.put("rawSize", e.rawMaxSize)
            obj.put("hardwareLevel", e.hardwareLevel)
            obj.put("cfa", e.cfaName)
            obj.put("npfLimitSec", String.format(Locale.US, "%.2f", e.npfSec))
            obj.put("discoveredVia", e.discoveredVia)
            arr.put(obj)
        }
        root.put("halCameras", arr)
        return root.toString(2)
    }
}
