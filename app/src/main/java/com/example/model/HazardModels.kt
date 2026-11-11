package com.example.model

/**
 * Severity level of detected hazards in the user's path.
 */
enum class HazardSeverity {
    /**
     * Urgent, immediate hazard demanding an instant STOP command (e.g., stairs going down, drop-offs, imminent barrier).
     */
    IMMINENT_DANGER,

    /**
     * Moderate obstacle requiring caution/slight detour (e.g., curb, chair, pole on path, trash can).
     */
    CAUTION,

    /**
     * Clear path within the camera FOV. Under the Quiet-by-Default paradigm, no speech is emitted.
     */
    SAFE
}

/**
 * Tactile vibration profiles dispatched through Android Vibrator.
 */
enum class HapticPattern {
    TRIPLE_STOP_BURST,
    DOUBLE_CAUTION_PULSE,
    SAFE_TICK,
    NONE
}

/**
 * A discrete obstacle detected within the camera's Field of View.
 */
data class DetectedObstacle(
    val name: String,
    val clockPosition: String, // e.g. "12 o'clock", "1 o'clock", "11 o'clock"
    val approxDistanceMeters: Double,
    val approxSteps: Int,
    val elevation: String, // "Ground level", "Knee level", "Head/Torso level"
    val isApproaching: Boolean = false
)

/**
 * Complete assessment produced either by Gemini Vision or On-Device Safety Core.
 */
data class HazardAssessment(
    val severity: HazardSeverity,
    val spokenAlert: String?, // Null or empty if SAFE, to preserve user's natural hearing
    val detailedSummary: String?, // Available if user double-taps for details
    val obstacles: List<DetectedObstacle> = emptyList(),
    val hasStairsDown: Boolean = false,
    val hasStairsUp: Boolean = false,
    val hasDropOff: Boolean = false,
    val hasPotholeOrVoid: Boolean = false,
    val hasHeadOverhang: Boolean = false,
    val isOfflineEngine: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
    val confidence: Float = 1.0f
) {
    companion object {
        fun clearSafe(isOffline: Boolean = false) = HazardAssessment(
            severity = HazardSeverity.SAFE,
            spokenAlert = null,
            detailedSummary = "Path directly ahead is clear of detected obstacles.",
            obstacles = emptyList(),
            isOfflineEngine = isOffline
        )
    }
}

/**
 * Operation mode of the vision assistant.
 */
enum class OperatingMode {
    ON_DEMAND,       // Tap anywhere on screen to trigger snapshot analysis
    CONTINUOUS_AUTO  // Runs regular non-intrusive safety sweeps (silent unless hazard)
}

/**
 * Live sensor telemetry for camera positioning and ambient lighting.
 */
data class SensorTelemetry(
    val pitchDegrees: Float = 0f,
    val isWalkingAngleOptimal: Boolean = true,
    val angleGuidanceText: String = "Good angle",
    val ambientLux: Float = 50f,
    val isTorchActive: Boolean = false,
    val isLensOccluded: Boolean = false
)
