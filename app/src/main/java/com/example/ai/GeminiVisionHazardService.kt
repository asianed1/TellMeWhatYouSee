package com.example.ai

import android.graphics.Bitmap
import android.util.Log
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
 * Enforces structured JSON schema and the Quiet-by-Default principle.
 */
class GeminiVisionHazardService {

    private val hazardSchema = Schema.obj(
        properties = mapOf(
            "severity" to Schema.enumeration(
                values = listOf("IMMINENT_DANGER", "CAUTION", "SAFE"),
                description = "IMMINENT_DANGER for stairs down, drop-offs, or direct collision; CAUTION for obstacles; SAFE for clear path"
            ),
            "spoken_alert" to Schema.string(
                description = "Immediate 1-sentence urgent command for IMMINENT_DANGER or CAUTION. MUST BE EMPTY STRING IF SAFE"
            ),
            "detailed_summary" to Schema.string(
                description = "Short plain description of the forward scene"
            ),
            "has_stairs_down" to Schema.boolean(
                description = "True if descending stairs or steps going downward are present directly in path"
            ),
            "has_stairs_up" to Schema.boolean(
                description = "True if ascending steps going upward are present in path"
            ),
            "has_drop_off" to Schema.boolean(
                description = "True if an abrupt drop-off, curb edge, or platform gap is detected"
            ),
            "has_pothole_or_hole" to Schema.boolean(
                description = "True if an open hole, road pothole, sunken grate, or pavement depression is in path"
            ),
            "has_head_overhang" to Schema.boolean(
                description = "True if low-hanging branches, awnings, or signs are present at head/chest height"
            ),
            "obstacles" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "name" to Schema.string(description = "Identified object name, e.g. chair, bollard, curb, pothole"),
                        "clock_position" to Schema.string(description = "Clock direction e.g. 12 o'clock, 1 o'clock, 11 o'clock"),
                        "distance_meters" to Schema.double(description = "Estimated distance in meters"),
                        "elevation" to Schema.string(description = "Ground level, Knee level, or Head level")
                    )
                ),
                description = "List of distinct obstacles in the camera field of view"
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
     * Evaluates a camera frame for imminent walking hazards.
     */
    suspend fun analyzeFrame(bitmap: Bitmap): HazardAssessment {
        return try {
            val prompt = """
                You are the safety vision engine for a visually impaired user walking forward.
                Analyze the image strictly for physical hazards in the direct walking path within the camera field of view.
                
                NIGHT & LOW-LIGHT INSTRUCTION:
                Inspect ground shadows and flash highlights. Holes, potholes, and downward steps often appear as sharp shadow edges or abrupt dark voids.
                
                SAFETY RULES:
                1. IMMINENT_DANGER (Demand STOP):
                   - Descending stairs / steps going DOWN (high risk of fall): 'STOP! Stairs going down directly ahead, [X] steps away.'
                   - Potholes, open holes, sunken grates, or trenches directly in path: 'STOP! Pothole directly ahead in your path.'
                   - Drop-offs, subway/train platform edges, elevated curbs: 'STOP! Drop-off directly ahead.'
                   - Hard barriers or head-height overhanging branches within 2 meters.
                
                2. CAUTION (Navigable or Offset Hazard):
                   - Ascending stairs / steps going UP (navigable elevation): 'Caution: Steps going up directly ahead, [X] steps away.'
                   - Pothole or curb offset to the side: 'Caution: Pothole on your right at [Clock Direction], [X] steps ahead.'
                   - Parked bikes, chairs, poles, trash cans.
                
                3. SAFE (Quiet-by-Default):
                   - If path is clear or obstacles are far/harmless: spoken_alert MUST BE AN EMPTY STRING (""). Do NOT speak.
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
                val clock = item.optString("clock_position", "12 o'clock")
                val dist = item.optDouble("distance_meters", 1.5)
                val elevation = item.optString("elevation", "Ground level")
                val steps = (dist / 0.7).toInt().coerceAtLeast(1)

                obstaclesList.add(
                    DetectedObstacle(
                        name = name,
                        clockPosition = clock,
                        approxDistanceMeters = dist,
                        approxSteps = steps,
                        elevation = elevation
                    )
                )
            }
        }

        return HazardAssessment(
            severity = severity,
            spokenAlert = if (severity == HazardSeverity.SAFE) null else spokenAlert,
            detailedSummary = detailedSummary ?: "Path is clear.",
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
