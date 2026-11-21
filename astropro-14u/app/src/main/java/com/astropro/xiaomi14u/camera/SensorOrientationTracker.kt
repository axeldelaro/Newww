package com.astropro.xiaomi14u.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class VibrationSample(
    val peakGyroRadS: Float = 0f,
    val peakAccelMs2: Float = 0f,
    val wasShaken: Boolean = false
)

/**
 * Provides:
 * 1. Smoothed real-time 2-axis Pitch/Roll bubble level & 3D Camera Optical-Axis Pointing
 *    (cameraAzimuthDeg, cameraAltitudeDeg, cameraRollDeg) for the AR Sky Overlay & Sky Compass.
 * 2. Built-in high-frequency VibrationGuard (TYPE_LINEAR_ACCELERATION + TYPE_GYROSCOPE at
 *    SENSOR_DELAY_GAME active ONLY during capture so battery drain is zero when idle!).
 */
class SensorOrientationTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val rotMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val lastAccel = FloatArray(3)
    private val lastMag = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    private var smoothRoll = 0f
    private var smoothPitch = 0f
    private var smoothAzSin = 0f
    private var smoothAzCos = 1f
    private var smoothAlt = 0f
    private var orientationStarted = false

    private val _state = MutableStateFlow(LevelOrientationState())
    val state: StateFlow<LevelOrientationState> = _state.asStateFlow()

    // ---------------- High-Frequency VibrationGuard (active ONLY while capturing) ----------------
    private val vibLock = Any()
    @Volatile
    private var vibMonitoring = false
    @Volatile
    private var exposureWindowActive = false
    @Volatile
    private var liveGyroRadS = 0f
    @Volatile
    private var liveAccelMs2 = 0f
    @Volatile
    private var lastVibSampleMs = 0L

    private var windowPeakGyroRadS = 0f
    private var windowPeakAccelMs2 = 0f
    private var windowSampleCount = 0

    private val vibrationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val mag = sqrt(
                event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
            )
            synchronized(vibLock) {
                lastVibSampleMs = System.currentTimeMillis()
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> {
                        liveGyroRadS = mag
                        if (exposureWindowActive) {
                            windowPeakGyroRadS = max(windowPeakGyroRadS, mag)
                            windowSampleCount++
                        }
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        liveAccelMs2 = mag
                        if (exposureWindowActive) {
                            windowPeakAccelMs2 = max(windowPeakAccelMs2, mag)
                            windowSampleCount++
                        }
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (orientationStarted) return
        orientationStarted = true
        if (rotSensor != null) {
            sensorManager.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun stop() {
        if (orientationStarted) {
            orientationStarted = false
            sensorManager.unregisterListener(this)
        }
        stopVibrationMonitoring()
    }

    fun startVibrationMonitoring() {
        if (vibMonitoring) return
        if (gyroSensor == null && linearAccelSensor == null) return
        vibMonitoring = true
        synchronized(vibLock) {
            liveGyroRadS = 0f
            liveAccelMs2 = 0f
            lastVibSampleMs = 0L
            exposureWindowActive = false
            windowPeakGyroRadS = 0f
            windowPeakAccelMs2 = 0f
            windowSampleCount = 0
        }
        gyroSensor?.let {
            sensorManager.registerListener(vibrationListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAccelSensor?.let {
            sensorManager.registerListener(vibrationListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopVibrationMonitoring() {
        if (!vibMonitoring) return
        vibMonitoring = false
        exposureWindowActive = false
        sensorManager.unregisterListener(vibrationListener)
    }

    /**
     * Briefly waits before firing the shutter until hand-touch / tripod wobble decays below
     * astrophotography stability thresholds (peakGyro < 0.035 rad/s & peakAccel < 0.22 m/s²).
     * Returns immediately if no gyro/linear-accel sensor is present or already rock-steady.
     */
    suspend fun waitUntilStable(maxWaitMs: Long = 2000L): Boolean {
        if (!vibMonitoring || (gyroSensor == null && linearAccelSensor == null)) return true
        val startMs = System.currentTimeMillis()
        var stableStreakMs = 0L
        val pollMs = 40L

        while (System.currentTimeMillis() - startMs < maxWaitMs) {
            val gyro: Float
            val accel: Float
            val hasFreshSample: Boolean
            synchronized(vibLock) {
                gyro = liveGyroRadS
                accel = liveAccelMs2
                hasFreshSample = (lastVibSampleMs > 0L)
            }
            if (!hasFreshSample && (System.currentTimeMillis() - startMs) >= 160L) {
                // Device/emulator not emitting gyro/accel samples -> never block shutter
                return true
            }
            val steady = gyro < GYRO_THRESHOLD_RAD_S && accel < ACCEL_THRESHOLD_MS2
            if (steady) {
                stableStreakMs += pollMs
                if (stableStreakMs >= 160L) return true
            } else {
                stableStreakMs = 0L
            }
            delay(pollMs)
        }
        return false
    }

    fun beginExposureWindow() {
        synchronized(vibLock) {
            windowPeakGyroRadS = 0f
            windowPeakAccelMs2 = 0f
            windowSampleCount = 0
            exposureWindowActive = true
        }
    }

    fun endExposureWindow(): VibrationSample {
        synchronized(vibLock) {
            exposureWindowActive = false
            val peakG = windowPeakGyroRadS
            val peakA = windowPeakAccelMs2
            val hasData = windowSampleCount > 0
            val shaken = hasData && (peakG >= GYRO_THRESHOLD_RAD_S || peakA >= ACCEL_THRESHOLD_MS2)
            return VibrationSample(
                peakGyroRadS = peakG,
                peakAccelMs2 = peakA,
                wasShaken = shaken
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ok = when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccel, 0, 3)
                hasAccel = true
                hasMag && SensorManager.getRotationMatrix(rotMatrix, null, lastAccel, lastMag)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMag, 0, 3)
                hasMag = true
                hasAccel && SensorManager.getRotationMatrix(rotMatrix, null, lastAccel, lastMag)
            }
            else -> false
        }
        if (!ok) return

        // Remap coordinate system so +Y aligns with the rear camera optical axis (portrait upright hold)
        SensorManager.remapCoordinateSystem(
            rotMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            remappedMatrix
        )
        SensorManager.getOrientation(remappedMatrix, orientationAngles)

        val azRad = orientationAngles[0].toDouble()
        val azDeg = ((Math.toDegrees(azRad) + 360.0) % 360.0).toFloat()
        // Rear camera elevation above horizon: -pitch in remapped frame (-90°..+90°)
        val altDeg = (-Math.toDegrees(orientationAngles[1].toDouble())).toFloat().coerceIn(-90f, 90f)
        val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat().coerceIn(-180f, 180f)

        // Smooth low-pass damping so AR labels and bubble level NEVER jitter
        val alpha = 0.16f
        // Shortest-arc delta for roll so crossing +-180° never glitches
        var rollDelta = rollDeg - smoothRoll
        while (rollDelta > 180f) rollDelta -= 360f
        while (rollDelta < -180f) rollDelta += 360f
        smoothRoll += alpha * rollDelta

        smoothPitch += alpha * (altDeg - smoothPitch)
        smoothAlt += alpha * (altDeg - smoothAlt)

        // Circular EMA smoothing for azimuth so crossing 0°/360° never jumps
        val rad = azDeg * (PI / 180.0)
        smoothAzSin += alpha * (sin(rad).toFloat() - smoothAzSin)
        smoothAzCos += alpha * (cos(rad).toFloat() - smoothAzCos)
        val finalAz = ((Math.toDegrees(atan2(smoothAzSin.toDouble(), smoothAzCos.toDouble())) + 360.0) % 360.0).toFloat()

        _state.value = LevelOrientationState(
            rollDeg = smoothRoll,
            pitchDeg = smoothPitch,
            azimuthDeg = finalAz,
            altitudeDeg = smoothAlt,
            cameraAzimuthDeg = finalAz,
            cameraAltitudeDeg = smoothAlt,
            cameraRollDeg = smoothRoll
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val GYRO_THRESHOLD_RAD_S = 0.035f
        const val ACCEL_THRESHOLD_MS2 = 0.22f
    }
}
