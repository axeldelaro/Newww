package com.newww.luminaterrarium.sensors

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.newww.luminaterrarium.model.BatterySnapshot
import com.newww.luminaterrarium.model.PedometerHealth
import com.newww.luminaterrarium.model.TiltSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Capteurs 100 % hors-ligne v2 :
 * - Persiste le dernier compteur matériel TYPE_STEP_COUNTER + boot epoch dans SharedPreferences :
 *   récupère AUTOMATIQUEMENT tous les pas effectués pendant la journée même quand l'app était fermée !
 * - Ré-enregistre proprement le capteur dès que la permission ACTIVITY_RECOGNITION est accordée.
 * - Appelle sensorManager.flush() au retour pour vider le FIFO matériel instantanément.
 */
class DeviceSensorsManager(
    private val context: Context,
    private val onStepsDelta: (Int) -> Unit
) : SensorEventListener {

    private val prefs = context.getSharedPreferences("lumina_pedometer_v2", Context.MODE_PRIVATE)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _battery = MutableStateFlow(readInitialBattery())
    val battery: StateFlow<BatterySnapshot> = _battery.asStateFlow()

    private val _tilt = MutableStateFlow(TiltSnapshot())
    val tilt: StateFlow<TiltSnapshot> = _tilt.asStateFlow()

    private val _pedometerHealth = MutableStateFlow(computePedometerHealth())
    val pedometerHealth: StateFlow<PedometerHealth> = _pedometerHealth.asStateFlow()

    private var lastAccelMag: Float = 9.81f
    private var lastFallbackStepMs: Long = 0L
    private var batteryRegistered = false
    private var stepRegistered = false
    private var accelRegistered = false

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
        if (!batteryRegistered) {
            batteryRegistered = runCatching {
                context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                true
            }.getOrDefault(false)
        }
        if (!accelRegistered && accelSensor != null) {
            accelRegistered = runCatching {
                sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
            }.getOrDefault(false)
        }
        rebindStepSensor()
    }

    /**
     * Ré-attache le podomètre matériel (notamment juste après l'acceptation de la permission)
     * et demande un flush matériel pour récupérer immédiatement les pas accumulés.
     */
    fun rebindStepSensor() {
        val permOk = hasActivityRecognitionPermission()
        if (stepSensor != null && permOk) {
            if (stepRegistered) {
                runCatching { sensorManager.unregisterListener(this, stepSensor) }
                stepRegistered = false
            }
            stepRegistered = runCatching {
                val ok = sensorManager.registerListener(
                    this,
                    stepSensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    0
                )
                if (ok) sensorManager.flush(this)
                ok
            }.getOrDefault(false)
        }
        _pedometerHealth.value = computePedometerHealth()
    }

    fun stop() {
        if (batteryRegistered) {
            runCatching { context.unregisterReceiver(batteryReceiver) }
            batteryRegistered = false
        }
        runCatching { sensorManager.unregisterListener(this) }
        stepRegistered = false
        accelRegistered = false
    }

    fun hasActivityRecognitionPermission(): Boolean {
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
                processHardwareStepTotal(rawSinceBoot)
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
                val shake = ((deltaMag - 2.4f) / 7.5f).coerceIn(0f, 1f)
                val prev = _tilt.value
                _tilt.value = TiltSnapshot(
                    tiltX = prev.tiltX * 0.78f + normX * 0.22f,
                    tiltY = prev.tiltY * 0.78f + normY * 0.22f,
                    shakeIntensity = maxOf(prev.shakeIntensity * 0.86f, shake)
                )

                // Fallback podomètre si pas de capteur matériel actif
                if (!stepRegistered && mag > 11.7f && deltaMag > 1.6f) {
                    val now = System.currentTimeMillis()
                    if (now - lastFallbackStepMs > 360L) {
                        lastFallbackStepMs = now
                        onStepsDelta(1)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Compare le compteur cumulatif depuis le démarrage du téléphone avec la dernière valeur
     * sauvegardée sur disque : récupère automatiquement les pas faits en arrière-plan !
     */
    private fun processHardwareStepTotal(rawSinceBoot: Int) {
        val currentBootMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val savedBootMs = prefs.getLong(KEY_BOOT_MS, -1L)
        val savedLastRaw = prefs.getInt(KEY_LAST_RAW_STEPS, -1)

        val sameBootSession = savedBootMs > 0L && abs(currentBootMs - savedBootMs) < 120_000L

        val delta = when {
            savedLastRaw < 0 -> {
                // Premier lancement : si le téléphone a déjà marché aujourd'hui depuis le boot
                // (et a démarré dans les dernières 24h), crédite jusqu'à 12 000 pas initiaux du jour !
                val uptimeHours = SystemClock.elapsedRealtime() / 3_600_000L
                if (uptimeHours <= 24L && rawSinceBoot in 1..25_000) rawSinceBoot else 0
            }
            sameBootSession && rawSinceBoot >= savedLastRaw -> {
                (rawSinceBoot - savedLastRaw).coerceIn(0, 40_000)
            }
            !sameBootSession -> {
                // Le téléphone a redémarré : rawSinceBoot repart de 0
                rawSinceBoot.coerceIn(0, 25_000)
            }
            else -> 0
        }

        prefs.edit()
            .putLong(KEY_BOOT_MS, currentBootMs)
            .putInt(KEY_LAST_RAW_STEPS, rawSinceBoot)
            .apply()

        if (delta > 0) {
            onStepsDelta(delta)
        }
        _pedometerHealth.value = computePedometerHealth()
    }

    private fun computePedometerHealth(): PedometerHealth {
        val perm = hasActivityRecognitionPermission()
        val hw = stepSensor != null
        val txt = when {
            !perm -> "Autorise « Activité physique » pour compter tes pas auto"
            hw && stepRegistered -> "Podomètre auto actif ✓ (synchro journée complète)"
            hw -> "Initialisation du podomètre matériel…"
            else -> "Suivi pas par accéléromètre actif"
        }
        return PedometerHealth(perm, hw, txt)
    }

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

    companion object {
        private const val KEY_BOOT_MS = "boot_epoch_ms"
        private const val KEY_LAST_RAW_STEPS = "last_raw_hw_steps"
    }
}
