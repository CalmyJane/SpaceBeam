package com.calmyjane.spacebeam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface

class SensorHelper(private val activity: MainActivity) : SensorEventListener {
    private val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    @Volatile var accelX: Float = 0f
    @Volatile var accelY: Float = 0f
    @Volatile var accelZ: Float = 0f
    @Volatile var pitch: Float = 0f
    @Volatile var roll: Float = 0f
    @Volatile var yaw: Float = 0f

    // Global rotation smoothing: 0 = none, 1000 = maximum (10% default = 100)
    var pitchSmoothing: Int = 100
    var rollSmoothing:  Int = 100
    var yawSmoothing:   Int = 100
    private var smoothedPitch: Float = 0f
    private var smoothedRoll:  Float = 0f
    private var smoothedYaw:   Float = 0f

    fun start() {
        linearAccelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotationVectorSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Normalize ±10 m/s² → ±1.0
                accelX = (event.values[0] / 10f).coerceIn(-1f, 1f)
                accelY = (event.values[1] / 10f).coerceIn(-1f, 1f)
                accelZ = (event.values[2] / 10f).coerceIn(-1f, 1f)
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val rotMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                val adjustedRotMatrix = FloatArray(9)
                val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.display?.rotation ?: Surface.ROTATION_0
                } else {
                    @Suppress("DEPRECATION")
                    activity.windowManager.defaultDisplay.rotation
                }
                // Remap coordinate system to match current screen orientation
                when (rotation) {
                    Surface.ROTATION_90  -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, adjustedRotMatrix)
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, adjustedRotMatrix)
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, adjustedRotMatrix)
                    else -> rotMatrix.copyInto(adjustedRotMatrix)
                }
                val orientation = FloatArray(3)
                SensorManager.getOrientation(adjustedRotMatrix, orientation)
                // orientation[0]=azimuth/yaw (±π), [1]=pitch (±π/2), [2]=roll (±π/2)
                val halfPi = (Math.PI / 2).toFloat()
                // R[8] = screen normal's vertical (world-Z) component:
                //   0 when phone is upright (screen faces horizontally)
                //   +1 when screen faces up, -1 when screen faces down
                val rawPitch = adjustedRotMatrix[8].coerceIn(-1f, 1f)
                val rawRoll  = (orientation[2] / halfPi).coerceIn(-1f, 1f)
                val rawYaw   = (orientation[0] / Math.PI.toFloat()).coerceIn(-1f, 1f)
                // Apply per-axis EMA smoothing
                val pa = 1f - (pitchSmoothing / 1000f) * 0.97f
                val ra = 1f - (rollSmoothing  / 1000f) * 0.97f
                val ya = 1f - (yawSmoothing   / 1000f) * 0.97f
                smoothedPitch += (rawPitch - smoothedPitch) * pa
                smoothedRoll  += (rawRoll  - smoothedRoll)  * ra
                smoothedYaw   += (rawYaw   - smoothedYaw)   * ya
                pitch = smoothedPitch
                roll  = smoothedRoll
                yaw   = smoothedYaw
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

