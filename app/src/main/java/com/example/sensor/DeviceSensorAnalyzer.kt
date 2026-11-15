package com.example.sensor

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.model.CameraAimZone
import com.example.model.SensorTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Tracks user camera aim intent (downward ground, straight forward, upward overhead),
 * ambient light levels, and lightweight motion shake during the snapshot shutter window.
 */
class DeviceSensorAnalyzer(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val lightSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAccelSensor: Sensor? = if (gyroSensor == null) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    } else null

    private val _telemetry = MutableStateFlow(SensorTelemetry())
    val telemetry: StateFlow<SensorTelemetry> = _telemetry.asStateFlow()

    private var lastMeasuredLux: Float = 100f
    private var hasHardwareLightSensor: Boolean = false

    // Lightweight capture-window motion guard (zero heap allocations)
    @Volatile
    private var isCaptureMotionWindowActive: Boolean = false
    @Volatile
    private var peakAngularVelocityRadPerSec: Float = 0f
    @Volatile
    private var peakLinearAccelMps2: Float = 0f

    fun startListening() {
        gravitySensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        lightSensor?.let { sensor ->
            hasHardwareLightSensor = true
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        gyroSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAccelSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
    }

    fun isLowLight(): Boolean {
        // Standard mobility low-light threshold: < 35 lux (twilight, night, or unlit room)
        return lastMeasuredLux < 35f
    }

    /**
     * Marks the start of a snapshot capture window to monitor camera shake.
     */
    fun beginCaptureMotionWindow() {
        peakAngularVelocityRadPerSec = 0f
        peakLinearAccelMps2 = 0f
        isCaptureMotionWindowActive = true
    }

    /**
     * Ends the capture window and returns true if excessive shake/motion likely blurred the frame.
     */
    fun endCaptureMotionWindowAndCheckShake(): Boolean {
        isCaptureMotionWindowActive = false
        return isExcessiveShake(peakAngularVelocityRadPerSec, peakLinearAccelMps2)
    }

    fun isExcessiveShake(angularRadPerSec: Float, linearAccelMps2: Float): Boolean {
        // > ~1.75 rad/s (~100 deg/s rotation) or > 4.5 m/s^2 linear jolt during shutter causes blur
        return angularRadPerSec > 1.75f || linearAccelMps2 > 4.5f
    }

    /**
     * Classifies signed pitch relative to upright straight-ahead (0 deg = Z-axis perpendicular to standing user).
     * Negative = tilted downward toward ground; Positive = tilted upward toward overhead.
     */
    fun classifyAimZone(pitchDegrees: Float): CameraAimZone {
        return when {
            pitchDegrees < -14.0f -> CameraAimZone.GROUND_NEAR
            pitchDegrees > 14.0f -> CameraAimZone.OVERHEAD_CLEARANCE
            else -> CameraAimZone.FORWARD_PATH
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val ambientLux = event.values[0]
                lastMeasuredLux = ambientLux
                _telemetry.value = _telemetry.value.copy(ambientLux = ambientLux)
                return
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!isCaptureMotionWindowActive) return
                val wx = event.values[0]
                val wy = event.values[1]
                val wz = event.values[2]
                val mag = sqrt((wx * wx + wy * wy + wz * wz).toDouble()).toFloat()
                if (mag > peakAngularVelocityRadPerSec) {
                    peakAngularVelocityRadPerSec = mag
                }
                return
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (!isCaptureMotionWindowActive) return
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val mag = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
                if (mag > peakLinearAccelMps2) {
                    peakLinearAccelMps2 = mag
                }
                return
            }
        }

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // 0 deg = held upright straight ahead (Z-axis perpendicular to standing user).
        // Tilting top forward to look down at ground yields positive +Z -> negative pitch angle.
        // Tilting top back to look up at ceiling yields negative -Z -> positive pitch angle.
        val pitchRad = -atan2(z.toDouble(), sqrt((x * x + y * y).toDouble()))
        val pitchDeg = Math.toDegrees(pitchRad).toFloat()

        val aimZone = classifyAimZone(pitchDeg)
        val aimLabel = when (aimZone) {
            CameraAimZone.GROUND_NEAR -> "Ground scan (aimed down)"
            CameraAimZone.OVERHEAD_CLEARANCE -> "Overhead scan (aimed up)"
            CameraAimZone.FORWARD_PATH -> "Forward path scan"
        }

        val current = _telemetry.value
        _telemetry.value = current.copy(
            pitchDegrees = pitchDeg,
            aimZone = aimZone,
            isWalkingAngleOptimal = true,
            angleGuidanceText = aimLabel
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Estimates average luminance of a camera frame from sampled bitmap pixels.
     * Scale down / stride sample for ultra-low CPU overhead (<2ms).
     */
    fun analyzeFrameLuminance(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val sampleStep = 8

        var totalLum = 0L
        var count = 0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // Standard ITU-R BT.601 perceptual luminance
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                totalLum += lum
                count++
                x += sampleStep
            }
            y += sampleStep
        }

        val avgLux = if (count > 0) (totalLum.toFloat() / count) else 50f
        if (!hasHardwareLightSensor || _telemetry.value.ambientLux == 0f) {
            lastMeasuredLux = avgLux
        }
        val isOccluded = avgLux < 5f

        val current = _telemetry.value
        _telemetry.value = current.copy(
            ambientLux = if (hasHardwareLightSensor && current.ambientLux > 0f) current.ambientLux else avgLux,
            isLensOccluded = isOccluded
        )

        return avgLux
    }

    fun updateTorchState(isActive: Boolean) {
        _telemetry.value = _telemetry.value.copy(isTorchActive = isActive)
    }
}
