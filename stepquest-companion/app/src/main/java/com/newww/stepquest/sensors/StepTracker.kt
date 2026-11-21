package com.newww.stepquest.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.newww.stepquest.model.PedometerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Podomètre 100 % hors-ligne v2 :
 * - Persiste le compteur cumulatif matériel TYPE_STEP_COUNTER + boot epoch sur disque :
 *   récupère AUTOMATIQUEMENT tous les pas de la journée effectués même quand l'app était fermée !
 * - Ré-enregistre immédiatement le capteur après l'autorisation ACTIVITY_RECOGNITION + flush FIFO.
 */
class StepTracker(
    private val context: Context,
    private val onStepsDelta: (Int) -> Unit
) : SensorEventListener {

    private val prefs = context.getSharedPreferences("stepquest_pedometer_v2", Context.MODE_PRIVATE)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _status = MutableStateFlow(computeStatus(false))
    val status: StateFlow<PedometerStatus> = _status.asStateFlow()

    private var lastAccelMag = 9.81f
    private var lastFallbackMs = 0L
    private var stepRegistered = false
    private var accelRegistered = false

    val hasHardwareStepSensor: Boolean get() = stepSensor != null

    fun start() {
        rebind()
    }

    fun rebind() {
        val permOk = hasPermission()
        if (stepSensor != null && permOk) {
            if (stepRegistered) {
                runCatching { sensorManager.unregisterListener(this, stepSensor) }
                stepRegistered = false
            }
            stepRegistered = runCatching {
                val ok = sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, 0)
                if (ok) sensorManager.flush(this)
                ok
            }.getOrDefault(false)
        }
        if (!stepRegistered && !accelRegistered && accelSensor != null) {
            accelRegistered = runCatching {
                sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
            }.getOrDefault(false)
        }
        _status.value = computeStatus(stepRegistered)
    }

    fun stop() {
        runCatching { sensorManager.unregisterListener(this) }
        stepRegistered = false
        accelRegistered = false
    }

    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val rawSinceBoot = event.values.firstOrNull()?.toInt() ?: return
                handleHardwareStepCount(rawSinceBoot)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (stepRegistered) return
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val mag = sqrt(ax * ax + ay * ay + az * az)
                val diff = abs(mag - lastAccelMag)
                lastAccelMag = mag
                if (mag > 11.7f && diff > 1.6f) {
                    val now = System.currentTimeMillis()
                    if (now - lastFallbackMs > 360L) {
                        lastFallbackMs = now
                        onStepsDelta(1)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleHardwareStepCount(rawSinceBoot: Int) {
        val currentBootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val savedBootMs = prefs.getLong(KEY_BOOT_MS, -1L)
        val savedLastRaw = prefs.getInt(KEY_LAST_RAW, -1)
        val sameBoot = savedBootMs > 0L && abs(currentBootMs - savedBootMs) < 120_000L

        val delta = when {
            savedLastRaw < 0 -> {
                val uptimeHours = SystemClock.elapsedRealtime() / 3_600_000L
                if (uptimeHours <= 24L && rawSinceBoot in 1..25_000) rawSinceBoot else 0
            }
            sameBoot && rawSinceBoot >= savedLastRaw -> (rawSinceBoot - savedLastRaw).coerceIn(0, 40_000)
            !sameBoot -> rawSinceBoot.coerceIn(0, 25_000)
            else -> 0
        }

        prefs.edit()
            .putLong(KEY_BOOT_MS, currentBootMs)
            .putInt(KEY_LAST_RAW, rawSinceBoot)
            .apply()

        if (delta > 0) onStepsDelta(delta)
        _status.value = computeStatus(stepRegistered)
    }

    private fun computeStatus(hwBound: Boolean): PedometerStatus {
        val perm = hasPermission()
        val hw = stepSensor != null
        val txt = when {
            !perm -> "Autorise « Activité physique » pour synchroniser tes pas de la journée"
            hw && hwBound -> "Podomètre auto actif ✓ (synchro journée complète)"
            hw -> "Activation du podomètre…"
            else -> "Mode accéléromètre actif"
        }
        return PedometerStatus(perm, hw, txt)
    }

    companion object {
        private const val KEY_BOOT_MS = "sq_boot_ms"
        private const val KEY_LAST_RAW = "sq_last_raw"
    }
}
