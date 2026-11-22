package com.kinetix.coach.progression

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import com.kinetix.coach.data.UserDatabaseHelper
import kotlin.math.roundToInt

/**
 * 100% offline Android hardware pedometer using Sensor.TYPE_STEP_COUNTER (near-zero battery).
 * Handles midnight rollover, device reboots mid-day, and manual step additions cleanly without
 * ever losing steps or producing negative counts.
 */
class NativeStepTracker(
    private val context: Context,
    private val userDb: UserDatabaseHelper,
    private val onDailyStepsChanged: (Int) -> Unit
) : SensorEventListener {

    companion object {
        private const val PREF_BASELINE_DATE = "step_baseline_date"
        private const val PREF_BASELINE_HW = "step_baseline_hw"
        private const val PREF_LAST_HW = "step_last_hw"
        private const val PREF_CARRYOVER_TODAY = "step_carryover_today"
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val stepSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val lock = Any()
    private var isRegistered = false

    val hasHardwareSensor: Boolean
        get() = stepSensor != null

    fun hasActivityPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    fun startIfPermitted(): Boolean {
        synchronized(lock) {
            if (isRegistered) return true
            val mgr = sensorManager ?: return false
            val sensor = stepSensor ?: return false
            if (!hasActivityPermission()) return false
            val ok = runCatching {
                mgr.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }.getOrDefault(false)
            isRegistered = ok
            return ok
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!isRegistered) return
            runCatching { sensorManager?.unregisterListener(this) }
            isRegistered = false
        }
    }

    /** Records manual step entry in sync with hardware carryover so sensor ticks never overwrite it. */
    fun recordManualDelta(deltaSteps: Int, currentTodaySteps: Int, todayKey: String): Int {
        if (deltaSteps <= 0) return currentTodaySteps
        synchronized(lock) {
            val savedDate = userDb.getPref(PREF_BASELINE_DATE, "")
            val carryover = if (savedDate == todayKey) {
                userDb.getPref(PREF_CARRYOVER_TODAY, "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
            } else {
                userDb.setPref(PREF_BASELINE_DATE, todayKey)
                userDb.setPref(PREF_BASELINE_HW, "-1")
                userDb.setPref(PREF_LAST_HW, "-1")
                0
            }
            userDb.setPref(PREF_CARRYOVER_TODAY, (carryover + deltaSteps).toString())
            return (currentTodaySteps + deltaSteps).coerceAtMost(150_000)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_STEP_COUNTER || event.values.isEmpty()) return
        val hwTotal = event.values[0].roundToInt().coerceAtLeast(0)
        val todayKey = RpgProgressionEngine.todayDateKey()

        val computedTodaySteps: Int
        synchronized(lock) {
            val savedDate = userDb.getPref(PREF_BASELINE_DATE, "")
            var baselineHw = userDb.getPref(PREF_BASELINE_HW, "-1").toIntOrNull() ?: -1
            val lastHw = userDb.getPref(PREF_LAST_HW, "-1").toIntOrNull() ?: -1
            var carryover = userDb.getPref(PREF_CARRYOVER_TODAY, "0").toIntOrNull()?.coerceAtLeast(0) ?: 0

            if (savedDate != todayKey) {
                // Fresh calendar day: anchor hardware counter cleanly at current cumulative reading
                userDb.setPref(PREF_BASELINE_DATE, todayKey)
                userDb.setPref(PREF_BASELINE_HW, hwTotal.toString())
                userDb.setPref(PREF_LAST_HW, hwTotal.toString())
                userDb.setPref(PREF_CARRYOVER_TODAY, "0")
                computedTodaySteps = 0
            } else {
                if (baselineHw < 0) {
                    baselineHw = hwTotal
                    userDb.setPref(PREF_BASELINE_HW, baselineHw.toString())
                } else if (hwTotal < baselineHw) {
                    // Phone rebooted mid-day: preserve prior hardware delta in carryover and re-zero baseline
                    val priorDelta = if (lastHw >= baselineHw) (lastHw - baselineHw) else 0
                    carryover += priorDelta
                    baselineHw = 0
                    userDb.setPref(PREF_CARRYOVER_TODAY, carryover.toString())
                    userDb.setPref(PREF_BASELINE_HW, "0")
                }
                userDb.setPref(PREF_LAST_HW, hwTotal.toString())
                val hwDelta = (hwTotal - baselineHw).coerceAtLeast(0)
                computedTodaySteps = (carryover + hwDelta).coerceAtMost(150_000)
            }
        }

        if (computedTodaySteps > 0) {
            onDailyStepsChanged(computedTodaySteps)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
