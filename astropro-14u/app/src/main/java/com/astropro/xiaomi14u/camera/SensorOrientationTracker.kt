package com.astropro.xiaomi14u.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Provides smoothed real-time 2-axis Pitch/Roll bubble level (for vibration-free tripod leveling)
 * and Magnetic Sky Compass (azimuth + elevation pointed by the rear camera optical axis).
 */
class SensorOrientationTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

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
    private var started = false

    private val _state = MutableStateFlow(LevelOrientationState())
    val state: StateFlow<LevelOrientationState> = _state.asStateFlow()

    fun start() {
        if (started) return
        started = true
        if (rotSensor != null) {
            sensorManager.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager.unregisterListener(this)
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

        // Remap coordinate system so Z points out the rear camera lens (portrait camera orientation)
        SensorManager.remapCoordinateSystem(
            rotMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            remappedMatrix
        )
        SensorManager.getOrientation(remappedMatrix, orientationAngles)

        val azRad = orientationAngles[0].toDouble()
        val azDeg = ((Math.toDegrees(azRad) + 360.0) % 360.0).toFloat()
        // Rear camera elevation above horizon: -pitch in remapped frame
        val altDeg = (-Math.toDegrees(orientationAngles[1].toDouble())).toFloat().coerceIn(-90f, 90f)
        val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

        // Also compute phone horizontal roll/pitch relative to flat upright tripod hold
        val alpha = 0.18f
        smoothRoll += alpha * (rollDeg - smoothRoll)
        smoothPitch += alpha * (altDeg - smoothPitch)
        smoothAlt += alpha * (altDeg - smoothAlt)

        // Circular EMA smoothing for azimuth so crossing 0°/360° never glitches!
        val rad = azDeg * (PI / 180.0)
        smoothAzSin += alpha * (sin(rad).toFloat() - smoothAzSin)
        smoothAzCos += alpha * (cos(rad).toFloat() - smoothAzCos)
        val finalAz = ((Math.toDegrees(atan2(smoothAzSin.toDouble(), smoothAzCos.toDouble())) + 360.0) % 360.0).toFloat()

        _state.value = LevelOrientationState(
            rollDeg = smoothRoll,
            pitchDeg = smoothPitch,
            azimuthDeg = finalAz,
            altitudeDeg = smoothAlt
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
