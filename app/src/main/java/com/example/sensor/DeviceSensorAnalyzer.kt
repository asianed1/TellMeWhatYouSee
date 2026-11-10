package com.example.sensor

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.model.SensorTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Tracks device tilt angle and ambient camera frame brightness.
 * Ensures the camera is pointing at the user's forward walking path.
 */
class DeviceSensorAnalyzer(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _telemetry = MutableStateFlow(SensorTelemetry())
    val telemetry: StateFlow<SensorTelemetry> = _telemetry.asStateFlow()

    fun startListening() {
        gravitySensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate pitch angle in degrees relative to vertical/horizontal
        val pitchRad = atan2(y.toDouble(), sqrt((x * x + z * z).toDouble()))
        val pitchDeg = Math.toDegrees(pitchRad).toFloat()

        // Optimal walking stance: 30° to 65° forward downward tilt
        val isOptimal = pitchDeg in 25.0f..70.0f
        val guidance = when {
            pitchDeg < 20.0f -> "Tilt down slightly to scan walking path"
            pitchDeg > 75.0f -> "Tilt up slightly, currently pointing at feet"
            else -> "Angle optimal"
        }

        val current = _telemetry.value
        _telemetry.value = current.copy(
            pitchDegrees = pitchDeg,
            isWalkingAngleOptimal = isOptimal,
            angleGuidanceText = guidance
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
        val isOccluded = avgLux < 5f

        val current = _telemetry.value
        _telemetry.value = current.copy(
            ambientLux = avgLux,
            isLensOccluded = isOccluded
        )

        return avgLux
    }

    fun updateTorchState(isActive: Boolean) {
        _telemetry.value = _telemetry.value.copy(isTorchActive = isActive)
    }
}
