package com.newww.luminaterrarium.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import com.newww.luminaterrarium.model.BatterySnapshot
import com.newww.luminaterrarium.model.TiltSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Capteurs 100 % hors-ligne :
 * - Batterie & état de charge en temps réel
 * - Podomètre matériel (TYPE_STEP_COUNTER) avec fallback accéléromètre
 * - Inclinaison / secousse (accéléromètre) pour la physique du bocal
 */
class DeviceSensorsManager(
    private val context: Context,
    private val onStepsDelta: (Int) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _battery = MutableStateFlow(readInitialBattery())
    val battery: StateFlow<BatterySnapshot> = _battery.asStateFlow()

    private val _tilt = MutableStateFlow(TiltSnapshot())
    val tilt: StateFlow<TiltSnapshot> = _tilt.asStateFlow()

    private var lastRawStepCounter: Int = -1
    private var lastAccelMag: Float = 9.81f
    private var lastFallbackStepMs: Long = 0L
    private var started = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val pct = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 80
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            _battery.value = BatterySnapshot(pct, charging)
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        stepSensor?.let {
            runCatching { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        accelSensor?.let {
            runCatching { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { sensorManager.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull()?.toInt() ?: return
                if (lastRawStepCounter < 0) {
                    lastRawStepCounter = total
                } else {
                    val delta = (total - lastRawStepCounter).coerceIn(0, 5000)
                    lastRawStepCounter = total
                    if (delta > 0) onStepsDelta(delta)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val mag = sqrt(ax * ax + ay * ay + az * az)
                val deltaMag = abs(mag - lastAccelMag)
                lastAccelMag = mag

                val normX = (-ax / 9.81f).coerceIn(-1f, 1f)
                val normY = ((ay - 5.5f) / 9.81f).coerceIn(-1f, 1f)
                val shake = ((deltaMag - 2.6f) / 8f).coerceIn(0f, 1f)
                val prev = _tilt.value
                _tilt.value = TiltSnapshot(
                    tiltX = prev.tiltX * 0.80f + normX * 0.20f,
                    tiltY = prev.tiltY * 0.80f + normY * 0.20f,
                    shakeIntensity = maxOf(prev.shakeIntensity * 0.86f, shake)
                )

                // Fallback podomètre si le téléphone n'expose pas TYPE_STEP_COUNTER
                if (stepSensor == null && mag > 12.2f && deltaMag > 1.8f) {
                    val now = System.currentTimeMillis()
                    if (now - lastFallbackStepMs > 380L) {
                        lastFallbackStepMs = now
                        onStepsDelta(1)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun readInitialBattery(): BatterySnapshot {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return BatterySnapshot()
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 80)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatterySnapshot(((level * 100f) / scale).toInt().coerceIn(0, 100), charging)
    }
}
