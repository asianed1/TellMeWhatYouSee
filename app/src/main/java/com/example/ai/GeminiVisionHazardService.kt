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
            "has_drop_off" to Schema.boolean(
                description = "True if an abrupt drop-off, curb edge, or platform gap is detected"
            ),
            "has_head_overhang" to Schema.boolean(
                description = "True if low-hanging branches, awnings, or signs are present at head/chest height"
            ),
            "obstacles" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "name" to Schema.string(description = "Identified object name, e.g. chair, bollard, curb"),
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
                
                SAFETY RULES:
                1. IMMINENT_DANGER: Descending stairs/steps going down, drop-offs, open holes, platform gaps, head-height branches/awnings, or physical barriers blocking the path within 2 meters.
                   Spoken format: 'STOP! [Hazard] directly ahead, [X] steps away.'
                2. CAUTION: Curbs, parked bikes, poles, trash cans, or obstacles on path.
                   Spoken format: 'Caution: [Obstacle] at [Clock Direction], [X] steps ahead.'
                3. SAFE: If the forward path is clear or obstacles are far/harmless.
                   UNDER QUIET-BY-DEFAULT: spoken_alert MUST BE AN EMPTY STRING (""). Do NOT output pleasantries or say 'path is clear'.
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
        val hasDropOff = root.optBoolean("has_drop_off", false)
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
            hasDropOff = hasDropOff,
            hasHeadOverhang = hasHeadOverhang,
            isOfflineEngine = false
        )
    }
}
