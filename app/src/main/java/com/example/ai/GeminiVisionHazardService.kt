package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.model.CameraAimZone
import com.example.model.DetectedObstacle
import com.example.model.HazardAssessment
import com.example.model.HazardSeverity
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiVisionHazard"

/**
 * Cloud AI hazard analysis using Firebase AI Logic and Gemini 3.8 Flash multimodal vision.
 * Adapts focus to user camera aim intent, enforces structured JSON schema, uses qualitative
 * hedged proximity ("near ahead" / "far ahead") and natural directions, and enforces Quiet-by-Default.
 */
class GeminiVisionHazardService {

    private val hazardSchema = Schema.obj(
        properties = mapOf(
            "severity" to Schema.enumeration(
                values = listOf("IMMINENT_DANGER", "CAUTION", "SAFE"),
                description = "IMMINENT_DANGER for near drop-offs, stairs down, near potholes/voids, or direct collision; CAUTION for far or offset obstacles, stairs up, or overhead warnings; SAFE for clear path"
            ),
            "spoken_alert" to Schema.string(
                description = "Immediate 1-sentence hedged warning using 'possible [object] near/far [straight ahead/slightly left/slightly right]' for IMMINENT_DANGER or CAUTION. Do NOT include exact step counts or meters. MUST BE EMPTY STRING IF SAFE"
            ),
            "detailed_summary" to Schema.string(
                description = "Short plain description of the aimed scene"
            ),
            "has_stairs_down" to Schema.boolean(
                description = "True if descending stairs or steps going downward are present in path"
            ),
            "has_stairs_up" to Schema.boolean(
                description = "True if ascending steps going upward are present in path"
            ),
            "has_drop_off" to Schema.boolean(
                description = "True if an abrupt drop-off, curb edge, or platform gap is detected"
            ),
            "has_pothole_or_hole" to Schema.boolean(
                description = "True if an open hole, road pothole, sunken grate, or pavement depression is detected"
            ),
            "has_head_overhang" to Schema.boolean(
                description = "True if low-hanging branches, low ceilings, beams, awnings, or signs are present at head/overhead height"
            ),
            "obstacles" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "name" to Schema.string(description = "Identified object or hazard name, e.g. pothole, car, person, chair, low awning"),
                        "direction" to Schema.enumeration(
                            values = listOf("straight ahead", "slightly left", "slightly right"),
                            description = "Horizontal placement relative to center of view"
                        ),
                        "proximity" to Schema.enumeration(
                            values = listOf("near ahead", "far ahead"),
                            description = "Qualitative distance: near ahead (immediate/lower frame) or far ahead (further down path/mid frame)"
                        ),
                        "elevation" to Schema.string(description = "Ground level, Knee level, or Head/Overhead level")
                    )
                ),
                description = "List of distinct hazards or obstacles in the camera field of view"
            )
        )
    )

    private val config = generationConfig {
        responseMimeType = "application/json"
        responseSchema = hazardSchema
    }

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = config
        )
    }

    /**
     * Evaluates a camera snapshot for walking hazards adapted to the user's aim intent.
     */
    suspend fun analyzeFrame(
        bitmap: Bitmap,
        aimZone: CameraAimZone = CameraAimZone.FORWARD_PATH
    ): HazardAssessment {
        return try {
            val aimInstruction = when (aimZone) {
                CameraAimZone.GROUND_NEAR ->
                    "USER AIM INTENT: The user tilted the camera DOWNWARD intentionally to inspect the ground right near their feet. Prioritize detecting potholes, sunken grates, uneven pavement lips, curbs, drop-offs, and stairs in the near walking path."
                CameraAimZone.OVERHEAD_CLEARANCE ->
                    "USER AIM INTENT: The user tilted the camera UPWARD intentionally to check overhead clearance. Prioritize low ceilings, beams, tree branches, scaffolding, hanging signs, and awnings at head height. Do NOT mistake overhead patterns for ground potholes or floor stairs."
                CameraAimZone.FORWARD_PATH ->
                    "USER AIM INTENT: The user is holding the camera roughly straight ahead (Z-axis perpendicular to standing posture). Inspect BOTH the lower-center zone for near-ahead potholes/steps/obstacles AND the foreshortened middle-center zone for far-ahead potholes, cars, people, stairs, and barriers dead ahead."
            }

            val prompt = """
                You are the vision safety assistant for a visually impaired user holding a smartphone camera.
                $aimInstruction
                
                NIGHT & LOW-LIGHT INSTRUCTION:
                Inspect ground shadows and flash highlights carefully. Potholes, open holes, and downward steps often appear as sharp shadow edges or abrupt dark voids.
                
                DIRECTION & PROXIMITY RULES (NO EXACT METROLOGY):
                - Never output exact meter measurements, step counts, or clock-face numbers in spoken_alert.
                - Use natural direction: "straight ahead", "slightly left", or "slightly right".
                - Use qualitative proximity: "near ahead" (immediate zone) or "far ahead" (further down the path).
                - Use honest, slightly hedged phrasing like "Possible pothole near straight ahead", "Possible car far ahead", or "Possible obstacle near slightly right".
                
                SAFETY PRIORITY RULES:
                1. IMMINENT_DANGER (Demand STOP):
                   - Near descending stairs / drop-offs / subway edges: "STOP! Possible stairs going down near straight ahead." or "STOP! Possible drop-off near ahead."
                   - Near potholes, open holes, or sunken grates dead ahead: "STOP! Possible pothole near straight ahead."
                   - Vehicles, people, or solid barriers blocking dead ahead at close range: "STOP! Possible [object] near straight ahead."
                
                2. CAUTION (Heads-Up):
                   - Far-ahead hazards dead ahead or off-center: "Caution: Possible [pothole/car/obstacle] far [straight ahead/slightly left/slightly right]."
                   - Ascending steps going UP: "Caution: Possible steps going up [near/far] straight ahead."
                   - Overhead hazards when checking upward or visible ahead: "Caution: Possible low overhead obstacle [near/far] ahead."
                
                3. SAFE (Quiet-by-Default):
                   - If the aimed path is clear or hazards are harmless/out of the path: spoken_alert MUST BE AN EMPTY STRING (""). Do NOT speak.
            """.trimIndent()

            // Realistic 30s timeout
            val response = withTimeout(30_000L) {
                model.generateContent(
                    content {
                        text(prompt)
                        image(bitmap)
                    }
                )
            }

            val jsonText = response.text ?: throw IllegalStateException("Model returned empty text.")
            parseHazardJson(jsonText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini vision analysis failed", e)
            throw e
        }
    }

    private fun parseHazardJson(jsonText: String): HazardAssessment {
        val root = JSONObject(jsonText)
        val severityStr = root.optString("severity", "SAFE")
        val severity = when (severityStr.uppercase()) {
            "IMMINENT_DANGER" -> HazardSeverity.IMMINENT_DANGER
            "CAUTION" -> HazardSeverity.CAUTION
            else -> HazardSeverity.SAFE
        }

        val spokenAlert = root.optString("spoken_alert").trim().takeIf { it.isNotEmpty() }
        val detailedSummary = root.optString("detailed_summary").trim().takeIf { it.isNotEmpty() }
        val hasStairsDown = root.optBoolean("has_stairs_down", false)
        val hasStairsUp = root.optBoolean("has_stairs_up", false)
        val hasDropOff = root.optBoolean("has_drop_off", false)
        val hasPothole = root.optBoolean("has_pothole_or_hole", false)
        val hasHeadOverhang = root.optBoolean("has_head_overhang", false)

        val obstaclesList = mutableListOf<DetectedObstacle>()
        val obstaclesArray = root.optJSONArray("obstacles")
        if (obstaclesArray != null) {
            for (i in 0 until obstaclesArray.length()) {
                val item = obstaclesArray.getJSONObject(i)
                val name = item.optString("name", "Obstacle")
                val direction = item.optString("direction", "straight ahead")
                val proximity = item.optString("proximity", "near ahead")
                val elevation = item.optString("elevation", "Ground level")
                val distanceScore = if (proximity.contains("far", ignoreCase = true)) 3.0 else 1.0

                obstaclesList.add(
                    DetectedObstacle(
                        name = name,
                        direction = direction,
                        proximity = proximity,
                        elevation = elevation,
                        approxDistanceScore = distanceScore
                    )
                )
            }
        }

        return HazardAssessment(
            severity = severity,
            spokenAlert = if (severity == HazardSeverity.SAFE) null else spokenAlert,
            detailedSummary = detailedSummary ?: "Path ahead appears clear.",
            obstacles = obstaclesList,
            hasStairsDown = hasStairsDown,
            hasStairsUp = hasStairsUp,
            hasDropOff = hasDropOff,
            hasPotholeOrVoid = hasPothole,
            hasHeadOverhang = hasHeadOverhang,
            isOfflineEngine = false
        )
    }
}
