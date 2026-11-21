package com.newww.stepquest.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Podomètre 100 % hors-ligne : utilise le capteur matériel TYPE_STEP_COUNTER,
 * avec détection de pas par accéléromètre en secours.
 */
class StepTracker(
    context: Context,
    private val onStepsDelta: (Int) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastRawTotal = -1
    private var lastAccelMag = 9.81f
    private var lastFallbackMs = 0L
    private var running = false

    val hasHardwareStepSensor: Boolean get() = stepSensor != null

    fun start() {
        if (running) return
        running = true
        if (stepSensor != null) {
            runCatching { sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL) }
        } else if (accelSensor != null) {
            runCatching { sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { sensorManager.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull()?.toInt() ?: return
                if (lastRawTotal < 0) {
                    lastRawTotal = total
                } else {
                    val delta = (total - lastRawTotal).coerceIn(0, 10_000)
                    lastRawTotal = total
                    if (delta > 0) onStepsDelta(delta)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val mag = sqrt(ax * ax + ay * ay + az * az)
                val diff = abs(mag - lastAccelMag)
                lastAccelMag = mag
                if (mag > 11.8f && diff > 1.7f) {
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
}
