package com.astropro.xiaomi14u.storage

import android.content.Context
import com.astropro.xiaomi14u.camera.OutputFormatOption
import com.astropro.xiaomi14u.camera.TimerOption
import com.astropro.xiaomi14u.ephemeris.AstroEphemeris
import com.astropro.xiaomi14u.ephemeris.ObserverLocation

data class SavedAstroPreferences(
    val nightVision: Boolean = false,
    val showGridLevel: Boolean = true,
    val showHistogram: Boolean = true,
    val focusPeakingEnabled: Boolean = false,
    val arSkyOverlayEnabled: Boolean = false,
    val vibrationGuardEnabled: Boolean = true,
    val outputFormat: OutputFormatOption = OutputFormatOption.DNG_16B,
    val timerOption: TimerOption = TimerOption.OFF,
    val dimScreenDuringStack: Boolean = false,
    val location: ObserverLocation = AstroEphemeris.PRESET_LOCATIONS.first()
)

/**
 * Lightweight SharedPreferences persistence for field astrophotography toggles,
 * per-lens calibrated infinity focus diopters, and last observer location.
 */
class AstroPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): SavedAstroPreferences {
        val defaultLoc = AstroEphemeris.PRESET_LOCATIONS.first()
        val formatName = prefs.getString(KEY_OUTPUT_FORMAT, OutputFormatOption.DNG_16B.name)
        val timerName = prefs.getString(KEY_TIMER_OPTION, TimerOption.OFF.name)

        val format = OutputFormatOption.entries.firstOrNull { it.name == formatName }
            ?: OutputFormatOption.DNG_16B
        val timer = TimerOption.entries.firstOrNull { it.name == timerName }
            ?: TimerOption.OFF

        val locName = prefs.getString(KEY_LOC_NAME, null)
        val locLatBits = prefs.getLong(KEY_LOC_LAT_BITS, java.lang.Double.doubleToRawLongBits(defaultLoc.latitudeDeg))
        val locLonBits = prefs.getLong(KEY_LOC_LON_BITS, java.lang.Double.doubleToRawLongBits(defaultLoc.longitudeDeg))
        val location = if (!locName.isNullOrBlank()) {
            ObserverLocation(
                name = locName,
                latitudeDeg = java.lang.Double.longBitsToDouble(locLatBits),
                longitudeDeg = java.lang.Double.longBitsToDouble(locLonBits)
            )
        } else {
            defaultLoc
        }

        return SavedAstroPreferences(
            nightVision = prefs.getBoolean(KEY_NIGHT_VISION, false),
            showGridLevel = prefs.getBoolean(KEY_SHOW_GRID_LEVEL, true),
            showHistogram = prefs.getBoolean(KEY_SHOW_HISTOGRAM, true),
            focusPeakingEnabled = prefs.getBoolean(KEY_FOCUS_PEAKING, false),
            arSkyOverlayEnabled = prefs.getBoolean(KEY_AR_SKY_OVERLAY, false),
            vibrationGuardEnabled = prefs.getBoolean(KEY_VIBRATION_GUARD, true),
            outputFormat = format,
            timerOption = timer,
            dimScreenDuringStack = prefs.getBoolean(KEY_DIM_DURING_STACK, false),
            location = location
        )
    }

    fun setNightVision(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NIGHT_VISION, enabled).apply()
    }

    fun setShowGridLevel(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_GRID_LEVEL, enabled).apply()
    }

    fun setShowHistogram(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HISTOGRAM, enabled).apply()
    }

    fun setFocusPeaking(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FOCUS_PEAKING, enabled).apply()
    }

    fun setArSkyOverlay(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AR_SKY_OVERLAY, enabled).apply()
    }

    fun setVibrationGuard(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION_GUARD, enabled).apply()
    }

    fun setOutputFormat(option: OutputFormatOption) {
        prefs.edit().putString(KEY_OUTPUT_FORMAT, option.name).apply()
    }

    fun setTimerOption(option: TimerOption) {
        prefs.edit().putString(KEY_TIMER_OPTION, option.name).apply()
    }

    fun setDimScreenDuringStack(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DIM_DURING_STACK, enabled).apply()
    }

    fun setObserverLocation(location: ObserverLocation) {
        prefs.edit()
            .putString(KEY_LOC_NAME, location.name)
            .putLong(KEY_LOC_LAT_BITS, java.lang.Double.doubleToRawLongBits(location.latitudeDeg))
            .putLong(KEY_LOC_LON_BITS, java.lang.Double.doubleToRawLongBits(location.longitudeDeg))
            .apply()
    }

    /**
     * Persists the V-Curve calibrated infinity diopter for a specific Xiaomi 14 Ultra physical lens ID.
     */
    fun saveCalibratedInfinity(lensId: String, diopter: Float) {
        prefs.edit().putFloat("$KEY_CAL_INF_PREFIX$lensId", diopter.coerceAtLeast(0.0f)).apply()
    }

    /**
     * Returns the saved calibrated infinity diopter for a lens ID, defaulting to 0.0f if not yet calibrated.
     */
    fun getCalibratedInfinity(lensId: String): Float {
        return prefs.getFloat("$KEY_CAL_INF_PREFIX$lensId", 0.0f).coerceAtLeast(0.0f)
    }

    fun hasCalibratedInfinity(lensId: String): Boolean {
        return prefs.contains("$KEY_CAL_INF_PREFIX$lensId")
    }

    fun getAllCalibratedInfinities(): Map<String, Float> {
        val result = mutableMapOf<String, Float>()
        for ((k, v) in prefs.all) {
            if (k.startsWith(KEY_CAL_INF_PREFIX) && v is Float) {
                result[k.removePrefix(KEY_CAL_INF_PREFIX)] = v
            }
        }
        return result
    }

    private companion object {
        const val PREFS_NAME = "astropro_14u_prefs"
        const val KEY_NIGHT_VISION = "night_vision"
        const val KEY_SHOW_GRID_LEVEL = "show_grid_level"
        const val KEY_SHOW_HISTOGRAM = "show_histogram"
        const val KEY_FOCUS_PEAKING = "focus_peaking"
        const val KEY_AR_SKY_OVERLAY = "ar_sky_overlay"
        const val KEY_VIBRATION_GUARD = "vibration_guard"
        const val KEY_OUTPUT_FORMAT = "output_format"
        const val KEY_TIMER_OPTION = "timer_option"
        const val KEY_DIM_DURING_STACK = "dim_during_stack"
        const val KEY_LOC_NAME = "obs_loc_name"
        const val KEY_LOC_LAT_BITS = "obs_loc_lat_bits"
        const val KEY_LOC_LON_BITS = "obs_loc_lon_bits"
        const val KEY_CAL_INF_PREFIX = "cal_infinity_diopter_"
    }
}
