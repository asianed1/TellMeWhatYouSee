package com.example.safety

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.model.DetectedObstacle
import com.example.model.HazardAssessment
import com.example.model.HazardSeverity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

private const val TAG = "OnDeviceSafetyEngine"

/**
 * 100% On-Device Safety Engine. Operates with zero network and zero cloud connectivity.
 * Implements deterministic computer vision edge heuristics (staircase lines, drop-off void)
 * combined with local Google ML Kit object detection.
 */
class OnDeviceSafetyEngine {

    private val objectDetector by lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        ObjectDetection.getClient(options)
    }

    // Approach tracking state (compares consecutive on-demand or auto scans)
    private var lastObjectAreaMetric: Float = 0f
    private var lastObjectName: String = ""
    private var lastScanTimeMs: Long = 0L

    /**
     * Evaluates a camera frame bitmap on-device.
     */
    suspend fun analyzeFrame(bitmap: Bitmap): HazardAssessment {
        val width = bitmap.width
        val height = bitmap.height

        // 1. Check for Stairs Pattern (parallel horizontal edge gradients in lower half)
        val hasStairsPattern = detectParallelStepLines(bitmap)

        // 2. Check for Floor Discontinuity / Drop-Off Void in ground sector
        val hasDropOffVoid = detectGroundDiscontinuity(bitmap)

        // 3. Check for Pothole or localized ground cavity
        val hasPothole = detectPotholeOrGroundCavity(bitmap)

        if (hasDropOffVoid) {
            return HazardAssessment(
                severity = HazardSeverity.IMMINENT_DANGER,
                spokenAlert = "STOP! Drop-off detected directly ahead.",
                detailedSummary = "Ground discontinuity or sudden drop detected in immediate walking path.",
                hasDropOff = true,
                isOfflineEngine = true
            )
        }

        if (hasPothole) {
            return HazardAssessment(
                severity = HazardSeverity.IMMINENT_DANGER,
                spokenAlert = "STOP! Pothole or hole in path directly ahead.",
                detailedSummary = "Localized ground depression or pothole void detected.",
                hasPotholeOrVoid = true,
                isOfflineEngine = true
            )
        }

        if (hasStairsPattern) {
            val isDescending = isStairsDescending(bitmap)
            return if (isDescending) {
                HazardAssessment(
                    severity = HazardSeverity.IMMINENT_DANGER,
                    spokenAlert = "STOP! Stairs going down directly ahead.",
                    detailedSummary = "Descending staircase detected in path. High fall hazard.",
                    hasStairsDown = true,
                    isOfflineEngine = true
                )
            } else {
                HazardAssessment(
                    severity = HazardSeverity.CAUTION,
                    spokenAlert = "Caution: Steps going up directly ahead.",
                    detailedSummary = "Ascending staircase detected. Navigable steps ahead.",
                    hasStairsUp = true,
                    isOfflineEngine = true
                )
            }
        }

        // 3. Local ML Kit Object Detection in central walking corridor
        val mlKitObstacles = detectMlKitObjects(bitmap)
        if (mlKitObstacles.isNotEmpty()) {
            val closest = mlKitObstacles.minByOrNull { it.approxDistanceMeters } ?: mlKitObstacles.first()

            val severity = if (closest.approxDistanceMeters < 1.2 && closest.clockPosition == "12 o'clock") {
                HazardSeverity.IMMINENT_DANGER
            } else {
                HazardSeverity.CAUTION
            }

            // Check if closest target is actively approaching across consecutive scans
            val now = System.currentTimeMillis()
            val timeDelta = now - lastScanTimeMs
            val currentDistanceMetric = (1.0f / (closest.approxDistanceMeters.toFloat() + 0.1f))
            val isApproaching = lastScanTimeMs > 0L &&
                    timeDelta in 300L..6000L &&
                    lastObjectName == closest.name &&
                    currentDistanceMetric > lastObjectAreaMetric * 1.22f

            lastObjectAreaMetric = currentDistanceMetric
            lastObjectName = closest.name
            lastScanTimeMs = now

            val finalObstacles = mlKitObstacles.map {
                if (it == closest && isApproaching) it.copy(isApproaching = true) else it
            }

            val alertText = when {
                isApproaching && closest.name == "Person" -> {
                    if (severity == HazardSeverity.IMMINENT_DANGER) {
                        "STOP! Person approaching directly ahead!"
                    } else {
                        "Caution: Person approaching at ${closest.clockPosition}, ${closest.approxSteps} steps ahead."
                    }
                }
                isApproaching && closest.name == "Animal" -> {
                    "Caution: Animal moving toward your path at ${closest.clockPosition}."
                }
                isApproaching -> {
                    "Caution: ${closest.name} approaching at ${closest.clockPosition}."
                }
                severity == HazardSeverity.IMMINENT_DANGER -> {
                    "STOP! ${closest.name} directly ahead, ${closest.approxSteps} steps away."
                }
                else -> {
                    "Caution: ${closest.name} at ${closest.clockPosition}, ${closest.approxSteps} steps ahead."
                }
            }

            return HazardAssessment(
                severity = severity,
                spokenAlert = alertText,
                detailedSummary = "On-device detection found ${mlKitObstacles.size} obstacle(s) in field of view.",
                obstacles = finalObstacles,
                isOfflineEngine = true
            )
        }

        // Under Quiet-by-Default: Safe path produces silent assessment
        return HazardAssessment.clearSafe(isOffline = true)
    }

    /**
     * Detects parallel horizontal step lines in lower half of image (staircase signature).
     */
    fun detectParallelStepLines(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val startY = (height * 0.50).toInt()
        val endY = (height * 0.90).toInt().coerceAtMost(height - 2)
        val xStep = (width / 50).coerceAtLeast(2)

        val horizontalGradientEnergies = mutableListOf<Double>()

        for (y in startY until endY) {
            var rowGradSum = 0.0
            var count = 0
            for (x in (width * 0.20).toInt() until (width * 0.80).toInt() step xStep) {
                val p1 = getLuminance(bitmap.getPixel(x, y))
                val p2 = getLuminance(bitmap.getPixel(x, y + 1))
                rowGradSum += abs(p1 - p2)
                count++
            }
            horizontalGradientEnergies.add(if (count > 0) rowGradSum / count else 0.0)
        }

        // Count periodic sharp edge peaks
        var peakCount = 0
        for (i in 2 until horizontalGradientEnergies.size - 2) {
            val curr = horizontalGradientEnergies[i]
            val prev = horizontalGradientEnergies[i - 1]
            val next = horizontalGradientEnergies[i + 1]
            if (curr > 25.0 && curr >= prev && curr >= next) {
                peakCount++
            }
        }

        return peakCount >= 3
    }

    /**
     * Detects ground drop-off discontinuity in lower walking sector.
     */
    fun detectGroundDiscontinuity(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val groundY = (height * 0.78).toInt()
        val footY = (height * 0.92).toInt()

        var groundLum = 0.0
        var footLum = 0.0
        var samples = 0

        for (x in (width * 0.35).toInt() until (width * 0.65).toInt() step 6) {
            groundLum += getLuminance(bitmap.getPixel(x, groundY))
            footLum += getLuminance(bitmap.getPixel(x, footY))
            samples++
        }

        if (samples == 0) return false
        val avgGround = groundLum / samples
        val avgFoot = footLum / samples

        // Severe drop in luminance or darkness void in ground ahead
        return (avgFoot > 40 && avgGround < 12)
    }

    private suspend fun detectMlKitObjects(bitmap: Bitmap): List<DetectedObstacle> {
        return suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            objectDetector.process(inputImage)
                .addOnSuccessListener { detectedObjects ->
                    val obstacles = mutableListOf<DetectedObstacle>()
                    val width = bitmap.width.toFloat()
                    val height = bitmap.height.toFloat()

                    for (obj in detectedObjects) {
                        val box = obj.boundingBox
                        val centerX = box.centerX().toFloat()
                        val boxAreaPercent = (box.width() * box.height()) / (width * height)

                        // Only consider objects in the walking corridor (> 5% screen size)
                        if (boxAreaPercent > 0.04) {
                            val clockPos = when {
                                centerX < width * 0.38f -> "10 o'clock"
                                centerX > width * 0.62f -> "2 o'clock"
                                else -> "12 o'clock"
                            }

                            val approxDist = when {
                                boxAreaPercent > 0.25 -> 0.8  // Close within arm's reach
                                boxAreaPercent > 0.12 -> 1.5  // 2 steps away
                                else -> 2.5                   // 3-4 steps away
                            }
                            val approxSteps = (approxDist / 0.7).toInt().coerceAtLeast(1)

                            val boxWidth = box.width().toFloat()
                            val boxHeight = box.height().toFloat()
                            val aspectRatio = boxHeight / boxWidth.coerceAtLeast(1f)

                            val rawLabel = obj.labels.firstOrNull()?.text?.lowercase().orEmpty()
                            val detectedType = when {
                                rawLabel.contains("person") || rawLabel.contains("human") -> "Person"
                                rawLabel.contains("dog") || rawLabel.contains("cat") || rawLabel.contains("animal") -> "Animal"
                                // Upright vertical silhouette: Human
                                aspectRatio >= 1.6f && box.bottom > height * 0.5f -> "Person"
                                // Low-profile horizontal silhouette: Animal (dog, cat, pet)
                                aspectRatio in 0.5f..1.3f && box.bottom > height * 0.65f && boxAreaPercent in 0.04f..0.35f -> "Animal"
                                else -> obj.labels.firstOrNull()?.text ?: "Obstacle"
                            }

                            obstacles.add(
                                DetectedObstacle(
                                    name = detectedType,
                                    clockPosition = clockPos,
                                    approxDistanceMeters = approxDist,
                                    approxSteps = approxSteps,
                                    elevation = if (box.bottom > height * 0.7) "Ground level" else "Knee level"
                                )
                            )
                        }
                    }
                    continuation.resume(obstacles)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit object detection failed", e)
                    continuation.resume(emptyList())
                }
        }
    }

    /**
     * Detects potholes, open holes, or sunken pavement depressions in the forward walking corridor.
     */
    fun detectPotholeOrGroundCavity(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val startY = (height * 0.55).toInt()
        val endY = (height * 0.85).toInt()
        val startX = (width * 0.25).toInt()
        val endX = (width * 0.75).toInt()
        val step = (width / 25).coerceAtLeast(4)

        var totalLum = 0.0
        var totalSamples = 0
        val cellLuminance = mutableListOf<Double>()

        for (y in startY until endY step step) {
            for (x in startX until endX step step) {
                val lum = getLuminance(bitmap.getPixel(x, y)).toDouble()
                totalLum += lum
                totalSamples++
                cellLuminance.add(lum)
            }
        }

        if (totalSamples < 12) return false
        val avgPavementLum = totalLum / totalSamples
        if (avgPavementLum < 30.0) return false // Too dark to distinguish pothole from dark scene

        // Count dark cells representing an enclosed cavity/pothole
        val darkDepressionCount = cellLuminance.count { it < avgPavementLum * 0.45 }
        val depressionRatio = darkDepressionCount.toDouble() / totalSamples

        // A localized hole typically occupies between 6% and 35% of the forward corridor
        return depressionRatio in 0.06..0.38
    }

    /**
     * Distinguishes descending stairs (downward drop) from ascending stairs (upward steps).
     */
    fun isStairsDescending(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val midY = (height * 0.70).toInt()
        val lowerY = (height * 0.88).toInt().coerceAtMost(height - 1)

        var midLum = 0.0
        var lowerLum = 0.0
        var count = 0

        for (x in (width * 0.35).toInt() until (width * 0.65).toInt() step 6) {
            midLum += getLuminance(bitmap.getPixel(x, midY))
            lowerLum += getLuminance(bitmap.getPixel(x, lowerY))
            count++
        }

        if (count == 0) return true
        val avgMid = midLum / count
        val avgLower = lowerLum / count

        // In descending stairs, the steps drop away into lower shadow (mid/lower drops below near foot)
        return avgMid < avgLower * 0.75 || avgMid < 30.0
    }

    private fun getLuminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
