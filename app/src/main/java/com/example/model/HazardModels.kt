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
 * Intentional camera aim zone determined from the user's natural phone pitch.
 * The user dictates what the system analyzes rather than being coached on grip.
 */
enum class CameraAimZone {
    GROUND_NEAR,        // Tilted downward: inspecting ground near feet (potholes, steps, drop-offs)
    FORWARD_PATH,       // Held straight ahead (Z-axis roughly perpendicular to user): path ahead
    OVERHEAD_CLEARANCE  // Tilted upward: inspecting low ceilings, branches, awnings, signage
}

/**
 * A discrete obstacle detected within the camera's Field of View.
 */
data class DetectedObstacle(
    val name: String,
    val direction: String, // "straight ahead", "slightly left", "slightly right"
    val proximity: String, // "near ahead" or "far ahead"
    val elevation: String, // "Ground level", "Knee level", "Head/Overhead level"
    val approxDistanceScore: Double = 1.5, // Internal relative closeness for sorting & approach delta
    val isApproaching: Boolean = false
)

/**
 * Complete assessment produced either by Gemini Vision or On-Device Safety Core.
 */
data class HazardAssessment(
    val severity: HazardSeverity,
    val spokenAlert: String?, // Null or empty if SAFE, to preserve user's natural hearing
    val detailedSummary: String?, // Available if user double-aps for details
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
            detailedSummary = "Path directly ahead appears clear of detected obstacles.",
            obstacles = emptyList(),
            isOfflineEngine = isOffline
        )
    }
}

/**
 * Operation mode of the vision assistant.
 */
enum class OperatingMode {
    ON_DEMAND,       // Tap anywhere on screen or press Volume Up/Down to trigger snapshot analysis
    CONTINUOUS_AUTO  // Runs regular non-intrusive safety sweeps (silent unless hazard)
}

/**
 * Live sensor telemetry for user aim intent and ambient lighting.
 */
data class SensorTelemetry(
    val pitchDegrees: Float = 0f,
    val aimZone: CameraAimZone = CameraAimZone.FORWARD_PATH,
    val isWalkingAngleOptimal: Boolean = true,
    val angleGuidanceText: String = "Forward path scan",
    val ambientLux: Float = 50f,
    val isTorchActive: Boolean = false,
    val isLensOccluded: Boolean = false
)
