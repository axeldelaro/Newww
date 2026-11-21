package com.astropro.xiaomi14u.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Exact astronomical NPF rule calculator for pinpoint untracked stars on tripod.
 *
 * Simplified NPF: t = (35 * N + 30 * p) / f
 * Full NPF with declination: t = (16.9 * N + 0.10 * f + 13.7 * p) / (f * cos(declination))
 *   (or standard conservative French SAHavre NPF: (35 * N + 30 * p) / (f * cos(declination)))
 *
 * Where:
 *  - N = aperture f-number (e.g. 1.63 on Xiaomi 14 Ultra 1" LYT-900)
 *  - p = pixel pitch in microns (µm)
 *  - f = REAL physical focal length in mm (NOT 35mm equivalent!)
 */
object NpfCalculator {

    val STANDARD_SHUTTER_SECONDS: List<Double> = listOf(
        1.0 / 4000.0, 1.0 / 2000.0, 1.0 / 1000.0, 1.0 / 500.0,
        1.0 / 250.0, 1.0 / 125.0, 1.0 / 60.0, 1.0 / 30.0,
        1.0 / 15.0, 1.0 / 8.0, 1.0 / 4.0, 0.5,
        1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0,
        10.0, 13.0, 15.0, 20.0, 25.0, 30.0
    )

    /**
     * Computes exact NPF pinpoint-star shutter time in seconds.
     *
     * @param apertureF lens f-number N (e.g., 1.63f)
     * @param pixelPitchUm sensor pixel pitch in micrometers (e.g., 3.2f binned 12.5MP or 1.6f native)
     * @param realFocalLengthMm real physical focal length of lens in mm (e.g., 8.7f for 23mm eq 1" sensor)
     * @param declinationDeg celestial declination of target in degrees (0° = worst-case celestial equator)
     * @param accuracyFactor 1.0 for strict pinpoint (1px trail), 1.5–2.0 for tolerant widefield
     */
    fun computeNpfSeconds(
        apertureF: Double,
        pixelPitchUm: Double,
        realFocalLengthMm: Double,
        declinationDeg: Double = 0.0,
        accuracyFactor: Double = 1.0
    ): Double {
        if (realFocalLengthMm <= 0.1 || apertureF <= 0.5 || pixelPitchUm <= 0.1) return 1.0
        val cosDec = max(0.25, cos(Math.toRadians(declinationDeg.coerceIn(-75.0, 75.0))))
        val raw = ((35.0 * apertureF + 30.0 * pixelPitchUm) / realFocalLengthMm) / cosDec
        return (raw * accuracyFactor).coerceIn(0.05, 60.0)
    }

    /**
     * Classical Rule of 500 using 35mm equivalent focal length (for comparison).
     */
    fun computeRule500Seconds(focalLength35mmEq: Double): Double {
        if (focalLength35mmEq <= 1.0) return 30.0
        return (500.0 / focalLength35mmEq).coerceIn(0.2, 60.0)
    }

    /**
     * Snaps a target NPF time to the highest standard camera shutter speed <= target (or nearest within limits)
     * clamped to the lens hardware exposure bounds.
     */
    fun snapToCameraShutter(
        targetSeconds: Double,
        minExposureSec: Double,
        maxExposureSec: Double
    ): Double {
        val clamped = targetSeconds.coerceIn(minExposureSec, maxExposureSec)
        val valid = STANDARD_SHUTTER_SECONDS.filter { it in minExposureSec..maxExposureSec }
        if (valid.isEmpty()) return clamped
        // Pick largest standard speed <= clamped * 1.05 so stars remain razor-sharp
        val conservative = valid.lastOrNull { it <= clamped * 1.05 }
        return conservative ?: valid.minByOrNull { abs(it - clamped) } ?: clamped
    }

    fun formatShutter(seconds: Double): String {
        return when {
            seconds < 0.45 -> {
                val denom = max(1, (1.0 / max(1e-6, seconds) + 0.5).toInt())
                "1/${denom}s"
            }
            seconds < 10.0 -> String.format(Locale.US, "%.1fs", seconds)
            else -> String.format(Locale.US, "%.0fs", seconds)
        }
    }

    fun formatIntegrationTime(totalSeconds: Double): String {
        val s = max(0, totalSeconds.toInt())
        val h = s / 3600
        val m = (s % 3600) / 60
        val rem = s % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${rem.toString().padStart(2, '0')}s"
            else -> "${rem}s"
        }
    }

    fun clampToRange(seconds: Double, minNs: Long, maxNs: Long): Long {
        val ns = (seconds * 1_000_000_000.0).toLong()
        return min(maxNs, max(minNs, ns))
    }
}
