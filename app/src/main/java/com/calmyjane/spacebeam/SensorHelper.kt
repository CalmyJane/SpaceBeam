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

    // Sensor modulation switch (gyro button). Off by default; fades in/out via [blend] so parameters never jump.
    // The orientation at switch-on becomes the neutral reference: modulation is relative to how the phone is held.
    @Volatile var enabled = false; private set
    @Volatile var blend = 0f; private set
    private var blendLinear = 0f
    private var refPitch = 0f
    private var refRoll = 0f
    private var refYaw = 0f

    fun setEnabled(on: Boolean) {
        if (on && blend <= 0f) { refPitch = pitch; refRoll = roll; refYaw = yaw }
        enabled = on
    }

    /** Called once per rendered frame. */
    fun tick(deltaTime: Float) {
        val goal = if (enabled) 1f else 0f
        val step = deltaTime / 0.8f
        blendLinear = if (blendLinear < goal) minOf(goal, blendLinear + step) else maxOf(goal, blendLinear - step)
        blend = blendLinear * blendLinear * (3f - 2f * blendLinear)
    }

    /** Channel relative to the neutral reference, -1..1: 0 pitch, 1 roll, 2 yaw (wraps), 3-5 accel X/Y/Z. */
    fun relative(channel: Int): Float = when (channel) {
        0 -> pitch - refPitch
        1 -> roll - refRoll
        2 -> { var d = yaw - refYaw; if (d > 1f) d -= 2f; if (d < -1f) d += 2f; d }
        3 -> accelX
        4 -> accelY
        else -> accelZ
    }

    // Spike filter for the rotation angles (the roll angle can flip when the phone passes vertical):
    // a sample that jumps more than JUMP_LIMIT from the last accepted one is ignored until CONFIRM_SAMPLES
    // consecutive samples agree on the new value. After that, MAX_STEP turns the jump into a short glide.
    private val accepted = FloatArray(3)   // pitch, roll, yaw
    private val candidate = FloatArray(3)
    private val candidateCount = IntArray(3)
    private var hasRotation = false

    private fun wrapDiff(a: Float, b: Float, wraps: Boolean): Float {
        var d = a - b
        if (wraps) { if (d > 1f) d -= 2f; if (d < -1f) d += 2f }
        return d
    }

    private fun despike(i: Int, raw: Float, wraps: Boolean): Float {
        if (!hasRotation || kotlin.math.abs(wrapDiff(raw, accepted[i], wraps)) <= JUMP_LIMIT) {
            accepted[i] = raw
            candidateCount[i] = 0
            return raw
        }
        if (candidateCount[i] > 0 && kotlin.math.abs(wrapDiff(raw, candidate[i], wraps)) <= CONFIRM_TOL) candidateCount[i]++ else candidateCount[i] = 1
        candidate[i] = raw
        if (candidateCount[i] >= CONFIRM_SAMPLES) { accepted[i] = raw; candidateCount[i] = 0 }
        return accepted[i]
    }

    private companion object {
        const val JUMP_LIMIT = 0.3f      // of the -1..1 range, per ~20 ms sample
        const val CONFIRM_TOL = 0.1f
        const val CONFIRM_SAMPLES = 5    // ~100 ms
        const val MAX_STEP = 0.06f       // per sample, ~3 range units per second
    }

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
                val rawPitch = despike(0, adjustedRotMatrix[8].coerceIn(-1f, 1f), false)
                val rawRoll  = despike(1, (orientation[2] / halfPi).coerceIn(-1f, 1f), false)
                val rawYaw   = despike(2, (orientation[0] / Math.PI.toFloat()).coerceIn(-1f, 1f), true)
                hasRotation = true
                // Apply per-axis EMA smoothing
                val pa = 1f - (pitchSmoothing / 1000f) * 0.97f
                val ra = 1f - (rollSmoothing  / 1000f) * 0.97f
                val ya = 1f - (yawSmoothing   / 1000f) * 0.97f
                smoothedPitch += ((rawPitch - smoothedPitch) * pa).coerceIn(-MAX_STEP, MAX_STEP)
                smoothedRoll  += ((rawRoll  - smoothedRoll)  * ra).coerceIn(-MAX_STEP, MAX_STEP)
                // Yaw is circular (-1 and 1 are the same direction): move the short way and wrap
                smoothedYaw   += (wrapDiff(rawYaw, smoothedYaw, true) * ya).coerceIn(-MAX_STEP, MAX_STEP)
                if (smoothedYaw > 1f) smoothedYaw -= 2f
                if (smoothedYaw < -1f) smoothedYaw += 2f
                pitch = smoothedPitch
                roll  = smoothedRoll
                yaw   = smoothedYaw
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

