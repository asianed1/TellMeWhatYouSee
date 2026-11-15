package com.example.safety

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.model.CameraAimZone
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
 * Combines deterministic ground/stair heuristics with local Google ML Kit object detection,
 * adapted to the user's intentional camera aim (straight ahead, ground near, or overhead).
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
    private var lastObjectDistanceMetric: Float = 0f
    private var lastObjectName: String = ""
    private var lastScanTimeMs: Long = 0L

    /**
     * Evaluates a camera frame bitmap on-device according to user aim intent.
     */
    suspend fun analyzeFrame(
        bitmap: Bitmap,
        aimZone: CameraAimZone = CameraAimZone.FORWARD_PATH
    ): HazardAssessment {
        // 1. Ground-specific checks only apply when NOT intentionally aiming upward at ceilings/branches
        if (aimZone != CameraAimZone.OVERHEAD_CLEARANCE) {
            val hasDropOffVoid = detectGroundDiscontinuity(bitmap)
            if (hasDropOffVoid) {
                return HazardAssessment(
                    severity = HazardSeverity.IMMINENT_DANGER,
                    spokenAlert = "STOP! Possible drop-off near straight ahead.",
                    detailedSummary = "Sudden ground discontinuity or dark drop detected straight ahead.",
                    hasDropOff = true,
                    isOfflineEngine = true
                )
            }

            val potholeResult = evaluatePotholeOrGroundCavity(bitmap, aimZone)
            if (potholeResult.detected) {
                val proximityLabel = if (aimZone == CameraAimZone.GROUND_NEAR || potholeResult.isNearBottom) {
                    "near ahead"
                } else {
                    "far ahead"
                }
                val isUrgent = proximityLabel == "near ahead"
                return HazardAssessment(
                    severity = if (isUrgent) HazardSeverity.IMMINENT_DANGER else HazardSeverity.CAUTION,
                    spokenAlert = if (isUrgent) {
                        "STOP! Possible pothole near straight ahead."
                    } else {
                        "Caution: Possible pothole far straight ahead."
                    },
                    detailedSummary = "Localized ground depression or dark cavity detected ($proximityLabel).",
                    hasPotholeOrVoid = true,
                    isOfflineEngine = true
                )
            }

            val hasStairsPattern = detectParallelStepLines(bitmap)
            if (hasStairsPattern) {
                val isDescending = isStairsDescending(bitmap)
                return if (isDescending) {
                    HazardAssessment(
                        severity = HazardSeverity.IMMINENT_DANGER,
                        spokenAlert = "STOP! Possible stairs going down near straight ahead.",
                        detailedSummary = "Possible descending staircase detected ahead.",
                        hasStairsDown = true,
                        isOfflineEngine = true
                    )
                } else {
                    HazardAssessment(
                        severity = HazardSeverity.CAUTION,
                        spokenAlert = "Caution: Possible steps going up straight ahead.",
                        detailedSummary = "Possible ascending steps detected ahead.",
                        hasStairsUp = true,
                        isOfflineEngine = true
                    )
                }
            }
        }

        // 2. Local ML Kit Object Detection (with tightened dead-ahead center corridor sensitivity)
        val mlKitObstacles = detectMlKitObjects(bitmap, aimZone)
        if (mlKitObstacles.isNotEmpty()) {
            val closest = mlKitObstacles.minByOrNull { it.approxDistanceScore } ?: mlKitObstacles.first()

            val isImminent = closest.proximity == "near ahead" && closest.direction == "straight ahead"
            val severity = if (isImminent && aimZone != CameraAimZone.OVERHEAD_CLEARANCE) {
                HazardSeverity.IMMINENT_DANGER
            } else {
                HazardSeverity.CAUTION
            }

            // Check if closest target is actively approaching across consecutive scans
            val now = System.currentTimeMillis()
            val timeDelta = now - lastScanTimeMs
            val currentDistanceMetric = (1.0f / (closest.approxDistanceScore.toFloat() + 0.1f))
            val isApproaching = lastScanTimeMs > 0L &&
                    timeDelta in 300L..6000L &&
                    lastObjectName == closest.name &&
                    currentDistanceMetric > lastObjectDistanceMetric * 1.22f

            lastObjectDistanceMetric = currentDistanceMetric
            lastObjectName = closest.name
            lastScanTimeMs = now

            val finalObstacles = mlKitObstacles.map {
                if (it == closest && isApproaching) it.copy(isApproaching = true) else it
            }

            val objectLabel = closest.name.lowercase()
            val alertText = when {
                aimZone == CameraAimZone.OVERHEAD_CLEARANCE -> {
                    "Caution: Possible overhead obstacle ${closest.proximity} ${closest.direction}."
                }
                isApproaching && closest.name == "Person" -> {
                    if (severity == HazardSeverity.IMMINENT_DANGER) {
                        "STOP! Possible person approaching near straight ahead."
                    } else {
                        "Caution: Possible person approaching ${closest.proximity} ${closest.direction}."
                    }
                }
                isApproaching && closest.name == "Animal" -> {
                    "Caution: Possible animal moving toward path ${closest.direction}."
                }
                isApproaching -> {
                    "Caution: Possible $objectLabel approaching ${closest.direction}."
                }
                severity == HazardSeverity.IMMINENT_DANGER -> {
                    "STOP! Possible $objectLabel ${closest.proximity} ${closest.direction}."
                }
                else -> {
                    "Caution: Possible $objectLabel ${closest.proximity} ${closest.direction}."
                }
            }

            return HazardAssessment(
                severity = severity,
                spokenAlert = alertText,
                detailedSummary = "On-device scan noted ${mlKitObstacles.size} possible item(s) in view.",
                obstacles = finalObstacles,
                hasHeadOverhang = aimZone == CameraAimZone.OVERHEAD_CLEARANCE,
                isOfflineEngine = true
            )
        }

        // Under Quiet-by-Default: Clear aimed view emits no speech
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

        return (avgFoot > 40 && avgGround < 12)
    }

    private suspend fun detectMlKitObjects(
        bitmap: Bitmap,
        aimZone: CameraAimZone
    ): List<DetectedObstacle> {
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

                        val direction = when {
                            centerX < width * 0.36f -> "slightly left"
                            centerX > width * 0.64f -> "slightly right"
                            else -> "straight ahead"
                        }

                        // Dead-ahead center corridor allows smaller foreshortened obstacles (1.5%+)
                        val minAreaThreshold = if (direction == "straight ahead") 0.015f else 0.035f
                        if (boxAreaPercent > minAreaThreshold) {
                            val isNear = aimZone == CameraAimZone.GROUND_NEAR ||
                                boxAreaPercent > 0.14f ||
                                box.bottom > height * 0.78f
                            val proximity = if (isNear) "near ahead" else "far ahead"
                            val distanceScore = when {
                                boxAreaPercent > 0.25f -> 0.8
                                isNear -> 1.4
                                else -> 2.8
                            }

                            val boxWidth = box.width().toFloat()
                            val boxHeight = box.height().toFloat()
                            val aspectRatio = boxHeight / boxWidth.coerceAtLeast(1f)

                            val rawLabel = obj.labels.firstOrNull()?.text?.lowercase().orEmpty()
                            val detectedType = when {
                                rawLabel.contains("person") || rawLabel.contains("human") -> "Person"
                                rawLabel.contains("dog") || rawLabel.contains("cat") || rawLabel.contains("animal") -> "Animal"
                                rawLabel.contains("car") || rawLabel.contains("vehicle") -> "Vehicle"
                                aspectRatio >= 1.6f && box.bottom > height * 0.5f -> "Person"
                                aspectRatio in 0.5f..1.3f && box.bottom > height * 0.65f && boxAreaPercent in 0.03f..0.35f -> "Animal"
                                else -> obj.labels.firstOrNull()?.text ?: "Obstacle"
                            }

                            val elevation = when {
                                aimZone == CameraAimZone.OVERHEAD_CLEARANCE -> "Head/Overhead level"
                                box.bottom > height * 0.7f -> "Ground level"
                                else -> "Knee level"
                            }

                            obstacles.add(
                                DetectedObstacle(
                                    name = detectedType,
                                    direction = direction,
                                    proximity = proximity,
                                    elevation = elevation,
                                    approxDistanceScore = distanceScore
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

    data class PotholeScanResult(val detected: Boolean, val isNearBottom: Boolean)

    /**
     * Detects potholes, open holes, or sunken pavement depressions in the forward walking corridor.
     */
    fun detectPotholeOrGroundCavity(
        bitmap: Bitmap,
        aimZone: CameraAimZone = CameraAimZone.FORWARD_PATH
    ): Boolean {
        return evaluatePotholeOrGroundCavity(bitmap, aimZone).detected
    }

    fun evaluatePotholeOrGroundCavity(
        bitmap: Bitmap,
        aimZone: CameraAimZone = CameraAimZone.FORWARD_PATH
    ): PotholeScanResult {
        if (aimZone == CameraAimZone.OVERHEAD_CLEARANCE) return PotholeScanResult(false, false)

        val width = bitmap.width
        val height = bitmap.height
        val startY = if (aimZone == CameraAimZone.GROUND_NEAR) (height * 0.25).toInt() else (height * 0.50).toInt()
        val endY = (height * 0.94).toInt()
        val midSplitY = (height * 0.74).toInt()
        val startX = (width * 0.28).toInt()
        val endX = (width * 0.72).toInt()
        val step = (width / 25).coerceAtLeast(4)

        var totalLum = 0.0
        var totalSamples = 0
        val cellLuminance = mutableListOf<Double>()
        val cellY = mutableListOf<Int>()

        for (y in startY until endY step step) {
            for (x in startX until endX step step) {
                val lum = getLuminance(bitmap.getPixel(x, y)).toDouble()
                totalLum += lum
                totalSamples++
                cellLuminance.add(lum)
                cellY.add(y)
            }
        }

        if (totalSamples < 12) return PotholeScanResult(false, false)
        val avgPavementLum = totalLum / totalSamples
        if (avgPavementLum < 30.0) return PotholeScanResult(false, false)

        var darkCount = 0
        var nearDarkCount = 0
        for (i in cellLuminance.indices) {
            if (cellLuminance[i] < avgPavementLum * 0.45) {
                darkCount++
                if (cellY[i] >= midSplitY) {
                    nearDarkCount++
                }
            }
        }

        val depressionRatio = darkCount.toDouble() / totalSamples
        // Lower min ratio to 0.035 to catch foreshortened potholes dead ahead
        val detected = depressionRatio in 0.035..0.38
        val isNearBottom = darkCount > 0 && nearDarkCount >= (darkCount / 2)
        return PotholeScanResult(detected = detected, isNearBottom = isNearBottom)
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

        return avgMid < avgLower * 0.75 || avgMid < 30.0
    }

    private fun getLuminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
