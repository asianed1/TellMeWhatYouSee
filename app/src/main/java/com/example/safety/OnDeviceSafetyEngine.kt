package com.example.safety

import android.graphics.Bitmap
import com.example.model.CameraAimZone
import com.example.model.DetectedObstacle
import com.example.model.HazardAssessment
import com.example.model.HazardSeverity
import kotlin.math.abs

/**
 * 100% Self-Contained On-Device Safety Engine.
 * Runs exclusively on local phone hardware using LiteRT monocular depth estimation (MiDaS)
 * paired with geometric floor-plane and elevation-zone analysis.
 *
 * Classifies hazards into 4 actionable elevation tiers without noun guessing:
 * - Ground hazard (or high-confidence downward stairs)
 * - Knee hazard
 * - Eye-level hazard
 * - Overhead hazard
 */
class OnDeviceSafetyEngine(
    private val depthEstimator: DepthEstimator? = null
) {

    private enum class Lane(val label: String, val xStart: Int, val xEnd: Int) {
        LEFT("slightly left", 16, 88),
        AHEAD("ahead", 88, 168),
        RIGHT("slightly right", 168, 240)
    }

    private enum class ElevationTier(val spokenLabel: String, val elevationText: String, val rank: Int) {
        OVERHEAD("overhead hazard", "Overhead level", 4),
        EYE_LEVEL("eye-level hazard", "Eye level", 3),
        KNEE("knee hazard", "Knee level", 2),
        GROUND("ground hazard", "Ground level", 1)
    }

    private data class ZoneDetection(
        val tier: ElevationTier,
        val lane: Lane,
        val isNear: Boolean,
        val isDownwardStairs: Boolean = false,
        val score: Float
    )

    /**
     * Evaluates a camera snapshot completely on-device according to the user's aim intent and pitch.
     */
    fun analyzeFrame(
        bitmap: Bitmap,
        aimZone: CameraAimZone = CameraAimZone.FORWARD_PATH,
        pitchDegrees: Float = 0f
    ): HazardAssessment {
        val depthResult = depthEstimator?.estimateDepthMap(bitmap)
        if (depthResult != null && depthResult.depthGrid.size >= depthResult.size * depthResult.size) {
            return analyzeDepthMap(depthResult, bitmap, aimZone, pitchDegrees)
        }

        // Fallback if native LiteRT depth estimator is not attached (e.g., unsupported hardware)
        return analyzeFallbackWithoutDepth(bitmap, aimZone)
    }

    /**
     * Core spatial hazard analyzer operating on a normalized [0f..1f] inverse-depth grid
     * (1.0f = closest to camera, 0.0f = farthest from camera).
     */
    fun analyzeDepthMap(
        depthResult: DepthMapResult,
        bitmap: Bitmap? = null,
        aimZone: CameraAimZone = CameraAimZone.FORWARD_PATH,
        pitchDegrees: Float = 0f
    ): HazardAssessment {
        // Featureless / covered lens guard: flat uniform depth has near-zero dynamic range
        if (depthResult.rawRange in 0.0001f..0.04f) {
            return HazardAssessment.clearSafe(isOffline = true)
        }

        val grid = depthResult.depthGrid
        val size = depthResult.size
        val candidates = mutableListOf<ZoneDetection>()

        // Continuous pitch compensation inside FORWARD_PATH (-14..+14 deg):
        // Negative pitch (slightly downward) shifts horizon up (smaller y); positive shifts down.
        val pitchShiftY = if (aimZone == CameraAimZone.FORWARD_PATH) {
            (pitchDegrees.coerceIn(-14f, 14f) * 2.5f).toInt()
        } else 0

        // 1. Check Ground Plane Hazards (holes, drop-offs, downward stairs, ground protrusions)
        if (aimZone != CameraAimZone.OVERHEAD_CLEARANCE) {
            val groundHazard = detectGroundPlaneHazard(grid, size, bitmap, aimZone, pitchShiftY)
            if (groundHazard != null) {
                candidates.add(groundHazard)
            }
        }

        // 2. Check Protruding Spatial Obstacles across Overhead, Eye-Level, and Knee tiers
        val protruding = detectProtrudingObstacles(grid, size, aimZone, pitchShiftY)
        candidates.addAll(protruding)

        if (candidates.isEmpty()) {
            return HazardAssessment.clearSafe(isOffline = true)
        }

        // Merge vertical spans within the same lane (Knee+Eye or Eye+Overhead -> Eye-Level)
        val merged = mergeVerticalSpansByLane(candidates)

        // Pick highest-priority hazard for ultra-concise single-sentence delivery
        val primary = pickPrimaryHazard(merged)
        val isUrgent = primary.isDownwardStairs ||
            (primary.isNear && primary.lane == Lane.AHEAD) ||
            (primary.tier == ElevationTier.GROUND && primary.isNear)

        val severity = if (isUrgent) HazardSeverity.IMMINENT_DANGER else HazardSeverity.CAUTION
        val proximityWord = if (primary.isNear) "near" else "far"

        val spokenAlert = when {
            primary.isDownwardStairs -> "STOP! Possible downward stairs, $proximityWord ${primary.lane.label}."
            severity == HazardSeverity.IMMINENT_DANGER -> "STOP! Possible ${primary.tier.spokenLabel}, $proximityWord ${primary.lane.label}."
            else -> "Possible ${primary.tier.spokenLabel}, $proximityWord ${primary.lane.label}."
        }

        val obstacles = merged.map { det ->
            DetectedObstacle(
                name = if (det.isDownwardStairs) "Possible downward stairs" else "Possible ${det.tier.spokenLabel}",
                direction = if (det.lane == Lane.AHEAD) "straight ahead" else det.lane.label,
                proximity = if (det.isNear) "near ahead" else "far ahead",
                elevation = det.tier.elevationText,
                approxDistanceScore = if (det.isNear) 1.0 else 2.8
            )
        }

        return HazardAssessment(
            severity = severity,
            spokenAlert = spokenAlert,
            detailedSummary = spokenAlert,
            obstacles = obstacles,
            hasStairsDown = primary.isDownwardStairs,
            hasDropOff = primary.tier == ElevationTier.GROUND && primary.isNear && !primary.isDownwardStairs,
            hasPotholeOrVoid = primary.tier == ElevationTier.GROUND && !primary.isDownwardStairs,
            hasHeadOverhang = primary.tier == ElevationTier.OVERHEAD,
            isOfflineEngine = true
        )
    }

    private fun detectGroundPlaneHazard(
        grid: FloatArray,
        size: Int,
        bitmap: Bitmap?,
        aimZone: CameraAimZone,
        pitchShiftY: Int = 0
    ): ZoneDetection? {
        val scanTopY = if (aimZone == CameraAimZone.GROUND_NEAR) {
            96
        } else {
            (186 + pitchShiftY).coerceIn(156, 200)
        }
        val scanBottomY = 236
        val midSplitY = if (aimZone == CameraAimZone.GROUND_NEAR) {
            172
        } else {
            (scanTopY + 18).coerceIn(184, 214)
        }

        // A. Downward Drop-Off / Stairwell Cliff Check along center walking lane
        val nearFootDepth = regionMean(grid, size, Lane.AHEAD.xStart, Lane.AHEAD.xEnd, 212, 238)
        val midGroundDepth = regionMean(grid, size, Lane.AHEAD.xStart, Lane.AHEAD.xEnd, 166, 200)
        val forwardDrop = nearFootDepth - midGroundDepth

        val hasStepTexture = bitmap != null && detectParallelStepLines(bitmap)
        if (forwardDrop > 0.32f && nearFootDepth > 0.58f) {
            val isStairs = hasStepTexture && isStairsDescending(bitmap!!)
            return ZoneDetection(
                tier = ElevationTier.GROUND,
                lane = Lane.AHEAD,
                isNear = true,
                isDownwardStairs = isStairs,
                score = forwardDrop + 0.5f
            )
        }

        // B. Localized Ground Hole / Pothole Cavity Check (depth recess relative to floor median baseline)
        var recessedTotal = 0
        var recessedNear = 0
        var totalSamples = 0
        var maxRecessDelta = 0f

        for (y in scanTopY until scanBottomY step 3) {
            // Estimate pavement rim depth using median across the row so narrow obstacles do not inflate baseline
            val rowRimDepth = rowPercentile(grid, size, 24, 232, y, 0.55f)
            if (rowRimDepth < 0.35f || rowRimDepth > nearFootDepth + 0.08f) continue

            for (x in Lane.AHEAD.xStart until Lane.AHEAD.xEnd step 3) {
                val d = grid[y * size + x]
                val delta = rowRimDepth - d
                totalSamples++
                if (delta > 0.21f) {
                    recessedTotal++
                    if (y >= midSplitY) recessedNear++
                    if (delta > maxRecessDelta) maxRecessDelta = delta
                }
            }
        }

        if (totalSamples >= 20) {
            val recessRatio = recessedTotal.toFloat() / totalSamples
            if (recessRatio in 0.045f..0.45f && maxRecessDelta > 0.23f) {
                val isNear = aimZone == CameraAimZone.GROUND_NEAR || recessedNear >= (recessedTotal / 2)
                return ZoneDetection(
                    tier = ElevationTier.GROUND,
                    lane = Lane.AHEAD,
                    isNear = isNear,
                    isDownwardStairs = false,
                    score = maxRecessDelta + recessRatio
                )
            }
        }

        // C. High-confidence step pattern with downward drop in pixel gradient when depth drop is mild
        if (hasStepTexture && bitmap != null && isStairsDescending(bitmap) && forwardDrop > 0.18f) {
            return ZoneDetection(
                tier = ElevationTier.GROUND,
                lane = Lane.AHEAD,
                isNear = true,
                isDownwardStairs = true,
                score = 0.65f
            )
        }

        // D. Protruding low ground obstacle dead ahead (curb block / debris sticking above surrounding floor)
        val aheadGroundP80 = regionPercentile(grid, size, Lane.AHEAD.xStart, Lane.AHEAD.xEnd, 188, 234, 0.80f)
        val leftGroundMed = regionPercentile(grid, size, Lane.LEFT.xStart, Lane.LEFT.xEnd, 188, 234, 0.50f)
        val rightGroundMed = regionPercentile(grid, size, Lane.RIGHT.xStart, Lane.RIGHT.xEnd, 188, 234, 0.50f)
        val sideFloorBaseline = (leftGroundMed + rightGroundMed) * 0.5f
        if (aheadGroundP80 - sideFloorBaseline > 0.22f && aheadGroundP80 > 0.72f) {
            return ZoneDetection(
                tier = ElevationTier.GROUND,
                lane = Lane.AHEAD,
                isNear = true,
                score = aheadGroundP80 - sideFloorBaseline
            )
        }

        return null
    }

    private fun detectProtrudingObstacles(
        grid: FloatArray,
        size: Int,
        aimZone: CameraAimZone,
        pitchShiftY: Int = 0
    ): List<ZoneDetection> {
        val found = mutableListOf<ZoneDetection>()

        val ohStart = 6
        val ohEnd = (48 + pitchShiftY).coerceIn(24, 72)
        val eyeStart = (60 + pitchShiftY).coerceIn(32, 92)
        val eyeEnd = (136 + pitchShiftY).coerceIn(106, 166)
        val kneeStart = eyeEnd
        val kneeEnd = (kneeStart + 50).coerceIn(156, 206)

        // Baseline background depths + global far horizon anchor (immune to wide center cars/barriers)
        val overheadBg = regionPercentile(grid, size, 16, 240, ohStart, ohEnd, 0.40f)
        val eyeBg = regionPercentile(grid, size, 16, 240, eyeStart, eyeEnd, 0.40f)
        val globalFarAnchor = minOf(
            regionPercentile(grid, size, 16, 240, 4, 42, 0.25f),
            regionPercentile(grid, size, 16, 240, eyeStart, eyeEnd, 0.15f)
        )

        for (lane in Lane.entries) {
            if (aimZone == CameraAimZone.OVERHEAD_CLEARANCE) {
                // When user aims upward, evaluate upper & center bands strictly as Overhead clearance
                val cellP80 = regionPercentile(grid, size, lane.xStart, lane.xEnd, 36, 168, 0.80f)
                val nearCoverage = regionFractionAbove(grid, size, lane.xStart, lane.xEnd, 36, 168, 0.62f)
                if (nearCoverage >= 0.16f && cellP80 > 0.64f) {
                    found.add(ZoneDetection(ElevationTier.OVERHEAD, lane, isNear = true, score = cellP80))
                } else if (cellP80 > 0.50f && regionFractionAbove(grid, size, lane.xStart, lane.xEnd, 36, 168, 0.48f) >= 0.22f) {
                    found.add(ZoneDetection(ElevationTier.OVERHEAD, lane, isNear = false, score = cellP80))
                }
                continue
            }

            // Evaluate Eye-Level metrics first so Overhead can require open clearance underneath
            val eyeNearFrac = regionFractionAbove(grid, size, lane.xStart, lane.xEnd, eyeStart, eyeEnd, 0.66f)
            val eyeFarFrac = regionFractionAbove(grid, size, lane.xStart, lane.xEnd, eyeStart, eyeEnd, 0.50f)
            val eyeP80 = regionPercentile(grid, size, lane.xStart, lane.xEnd, eyeStart, eyeEnd, 0.80f)
            val eyeMed = regionPercentile(grid, size, lane.xStart, lane.xEnd, eyeStart, eyeEnd, 0.50f)

            // 1. Overhead band (top ~18% shifted by pitch): ONLY true overhang if open space exists below
            val ohNearFrac = regionFractionAbove(grid, size, lane.xStart, lane.xEnd, ohStart, ohEnd, 0.64f)
            val ohP80 = regionPercentile(grid, size, lane.xStart, lane.xEnd, ohStart, ohEnd, 0.80f)
            val hasClearanceUnderneath = (ohP80 - eyeMed) > 0.18f && eyeMed < 0.52f
            if (ohNearFrac >= 0.18f && (ohP80 - overheadBg) > 0.20f && hasClearanceUnderneath) {
                found.add(ZoneDetection(ElevationTier.OVERHEAD, lane, isNear = true, score = ohP80))
            }

            // 2. Eye-Level band (middle-upper horizon) + Wide-Barrier / 2m Car detector for center lane
            if (eyeNearFrac >= 0.18f && eyeP80 > 0.68f) {
                found.add(ZoneDetection(ElevationTier.EYE_LEVEL, lane, isNear = true, score = eyeP80))
            } else if (eyeFarFrac >= 0.22f && (eyeP80 - eyeBg) > 0.20f && eyeP80 > 0.52f) {
                found.add(ZoneDetection(ElevationTier.EYE_LEVEL, lane, isNear = false, score = eyeP80))
            } else if (lane == Lane.AHEAD) {
                val eyeAheadBlockFrac = regionFractionAbove(grid, size, lane.xStart, lane.xEnd, eyeStart, eyeEnd, 0.42f)
                val kneeAheadMed = regionPercentile(grid, size, lane.xStart, lane.xEnd, kneeStart, kneeEnd, 0.50f)
                val isVerticalPlateau = abs(kneeAheadMed - eyeMed) < 0.24f
                if (eyeMed >= 0.43f &&
                    eyeP80 >= 0.50f &&
                    (eyeMed - globalFarAnchor) >= 0.22f &&
                    eyeAheadBlockFrac >= 0.40f &&
                    (isVerticalPlateau || eyeMed >= 0.50f)
                ) {
                    val isNearBarrier = eyeMed >= 0.48f && eyeP80 >= 0.52f
                    found.add(
                        ZoneDetection(
                            ElevationTier.EYE_LEVEL,
                            lane,
                            isNear = isNearBarrier,
                            score = eyeP80.coerceAtLeast(0.70f)
                        )
                    )
                }
            }

            // 3. Knee-Level band (must clearly protrude closer than adjacent floor baseline)
            if (aimZone == CameraAimZone.FORWARD_PATH) {
                val kneeP80 = regionPercentile(grid, size, lane.xStart, lane.xEnd, kneeStart, kneeEnd, 0.80f)
                val otherLaneMed = when (lane) {
                    Lane.AHEAD -> (regionPercentile(grid, size, Lane.LEFT.xStart, Lane.LEFT.xEnd, kneeStart, kneeEnd, 0.50f) +
                        regionPercentile(grid, size, Lane.RIGHT.xStart, Lane.RIGHT.xEnd, kneeStart, kneeEnd, 0.50f)) * 0.5f
                    Lane.LEFT -> regionPercentile(grid, size, Lane.AHEAD.xStart, Lane.AHEAD.xEnd, kneeStart, kneeEnd, 0.50f)
                    Lane.RIGHT -> regionPercentile(grid, size, Lane.AHEAD.xStart, Lane.AHEAD.xEnd, kneeStart, kneeEnd, 0.50f)
                }
                val kneeNearFrac = regionFractionAbove(grid, size, lane.xStart, lane.xEnd, kneeStart, kneeEnd, 0.72f)
                if (kneeNearFrac >= 0.20f && (kneeP80 - otherLaneMed) > 0.17f) {
                    found.add(ZoneDetection(ElevationTier.KNEE, lane, isNear = true, score = kneeP80))
                }
            }
        }

        return found
    }

    private fun mergeVerticalSpansByLane(detections: List<ZoneDetection>): List<ZoneDetection> {
        val byLane = detections.groupBy { it.lane }
        val result = mutableListOf<ZoneDetection>()

        for ((_, laneItems) in byLane) {
            val hasEye = laneItems.firstOrNull { it.tier == ElevationTier.EYE_LEVEL }
            val hasKnee = laneItems.firstOrNull { it.tier == ElevationTier.KNEE }
            val hasOverhead = laneItems.firstOrNull { it.tier == ElevationTier.OVERHEAD }

            for (item in laneItems) {
                if (item == hasKnee && hasEye != null) {
                    // Skip lower knee alert when eye-level already blocks the same lane
                    continue
                }
                if (item == hasOverhead && hasEye != null) {
                    // Continuous tall obstacle blocking straight ahead is Eye-Level, not pure Overhead
                    continue
                }
                result.add(item)
            }
        }
        return result
    }

    private fun pickPrimaryHazard(detections: List<ZoneDetection>): ZoneDetection {
        return detections.maxWithOrNull(
            compareBy<ZoneDetection>(
                { it.isDownwardStairs },
                { it.lane == Lane.AHEAD && it.isNear },
                { it.isNear },
                { it.lane == Lane.AHEAD },
                { it.tier.rank },
                { it.score }
            )
        ) ?: detections.first()
    }

    /**
     * Minimal fallback when depth model is unavailable: checks high-confidence floor discontinuity
     * or descending step texture without guessing object nouns.
     */
    private fun analyzeFallbackWithoutDepth(bitmap: Bitmap, aimZone: CameraAimZone): HazardAssessment {
        if (aimZone != CameraAimZone.OVERHEAD_CLEARANCE) {
            if (detectGroundDiscontinuity(bitmap)) {
                return HazardAssessment(
                    severity = HazardSeverity.IMMINENT_DANGER,
                    spokenAlert = "STOP! Possible ground hazard, near ahead.",
                    detailedSummary = "Ground discontinuity detected near ahead.",
                    hasDropOff = true,
                    isOfflineEngine = true
                )
            }
            if (detectParallelStepLines(bitmap) && isStairsDescending(bitmap)) {
                return HazardAssessment(
                    severity = HazardSeverity.IMMINENT_DANGER,
                    spokenAlert = "STOP! Possible downward stairs, near ahead.",
                    detailedSummary = "Possible descending step edges detected near ahead.",
                    hasStairsDown = true,
                    isOfflineEngine = true
                )
            }
        }
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
     * Detects severe ground drop-off discontinuity in lower walking sector.
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

    /**
     * Distinguishes descending stairs (downward drop) from ascending stairs.
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

    private fun regionMean(grid: FloatArray, size: Int, x0: Int, x1: Int, y0: Int, y1: Int): Float {
        var sum = 0f
        var count = 0
        for (y in y0 until y1 step 3) {
            val rowOffset = y * size
            for (x in x0 until x1 step 3) {
                sum += grid[rowOffset + x]
                count++
            }
        }
        return if (count > 0) sum / count else 0f
    }

    private fun regionFractionAbove(
        grid: FloatArray,
        size: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        threshold: Float
    ): Float {
        var above = 0
        var total = 0
        for (y in y0 until y1 step 3) {
            val rowOffset = y * size
            for (x in x0 until x1 step 3) {
                total++
                if (grid[rowOffset + x] > threshold) above++
            }
        }
        return if (total > 0) above.toFloat() / total else 0f
    }

    private fun regionPercentile(
        grid: FloatArray,
        size: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        percentile: Float
    ): Float {
        val samples = mutableListOf<Float>()
        for (y in y0 until y1 step 4) {
            val rowOffset = y * size
            for (x in x0 until x1 step 4) {
                samples.add(grid[rowOffset + x])
            }
        }
        if (samples.isEmpty()) return 0f
        samples.sort()
        val idx = (samples.size * percentile).toInt().coerceIn(0, samples.lastIndex)
        return samples[idx]
    }

    private fun rowPercentile(
        grid: FloatArray,
        size: Int,
        x0: Int,
        x1: Int,
        y: Int,
        percentile: Float
    ): Float {
        val rowOffset = y * size
        val samples = FloatArray(((x1 - x0) + 3) / 4)
        var count = 0
        var x = x0
        while (x < x1 && count < samples.size) {
            samples[count++] = grid[rowOffset + x]
            x += 4
        }
        if (count == 0) return 0f
        samples.sort(0, count)
        val idx = (count * percentile).toInt().coerceIn(0, count - 1)
        return samples[idx]
    }

    private fun getLuminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
