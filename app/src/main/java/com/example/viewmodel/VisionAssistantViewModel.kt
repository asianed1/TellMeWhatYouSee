package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiVisionHazardService
import com.example.feedback.AudioHapticFeedbackController
import com.example.model.DetectedObstacle
import com.example.model.HazardAssessment
import com.example.model.HazardSeverity
import com.example.model.OperatingMode
import com.example.model.SensorTelemetry
import com.example.network.NetworkMonitor
import com.example.safety.OnDeviceSafetyEngine
import com.example.sensor.DeviceSensorAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "VisionViewModel"

data class AssistantUiState(
    val isAnalyzing: Boolean = false,
    val currentAssessment: HazardAssessment? = null,
    val lastAlertText: String = "Ready. Tap screen to inspect path ahead.",
    val operatingMode: OperatingMode = OperatingMode.ON_DEMAND,
    val isTorchActive: Boolean = false,
    val isOnline: Boolean = true,
    val telemetry: SensorTelemetry = SensorTelemetry(),
    val assessmentHistory: List<HazardAssessment> = emptyList(),
    val errorMessage: String? = null
)

class VisionAssistantViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val feedbackController = AudioHapticFeedbackController(application)
    private val sensorAnalyzer = DeviceSensorAnalyzer(application)
    private val networkMonitor = NetworkMonitor(application)
    private val onDeviceSafetyEngine = OnDeviceSafetyEngine()
    private val geminiService = GeminiVisionHazardService()

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var continuousScanJob: Job? = null
    var frameProvider: (suspend () -> Bitmap?)? = null
    var torchController: ((Boolean) -> Unit)? = null

    init {
        sensorAnalyzer.startListening()

        // Sync sensor telemetry
        viewModelScope.launch {
            sensorAnalyzer.telemetry.collectLatest { tel ->
                _uiState.value = _uiState.value.copy(telemetry = tel)
            }
        }

        // Sync online status and notify user when status changes
        viewModelScope.launch {
            networkMonitor.isOnline.collectLatest { online ->
                val prev = _uiState.value.isOnline
                _uiState.value = _uiState.value.copy(isOnline = online)
                if (prev != online) {
                    if (!online) {
                        feedbackController.speakInformative("Offline mode. Local safety core active.")
                    } else {
                        feedbackController.playCautionChime()
                        feedbackController.speakInformative("Online. Cloud AI restored.")
                    }
                }
            }
        }
    }

    /**
     * Executes analysis of the provided bitmap.
     */
    fun analyzeBitmap(bitmap: Bitmap) {
        if (_uiState.value.isAnalyzing) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAnalyzing = true, errorMessage = null)

            // 1. Analyze frame luminance for darkness & auto-torch
            val lux = sensorAnalyzer.analyzeFrameLuminance(bitmap)
            // Proactively illuminate at night or in dim indoor hallways (< 35 lux)
            if (lux < 35f && !_uiState.value.isTorchActive) {
                setTorch(true)
                feedbackController.playTorchToggled(true)
                feedbackController.speakInformative("Low light detected. Flashlight turned on.")
            }

            try {
                val isOnline = _uiState.value.isOnline
                val assessment = if (isOnline) {
                    try {
                        geminiService.analyzeFrame(bitmap)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Cloud AI failed, falling back to local safety engine", e)
                        onDeviceSafetyEngine.analyzeFrame(bitmap)
                    }
                } else {
                    onDeviceSafetyEngine.analyzeFrame(bitmap)
                }

                // Multi-sensory feedback dispatch
                feedbackController.dispatchAssessment(assessment)

                val newHistory = (listOf(assessment) + _uiState.value.assessmentHistory).take(15)
                val alertDisplay = assessment.spokenAlert ?: "Path clear."

                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    currentAssessment = assessment,
                    lastAlertText = alertDisplay,
                    assessmentHistory = newHistory
                )

            } catch (e: CancellationException) {
                _uiState.value = _uiState.value.copy(isAnalyzing = false)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed analyzing scene", e)
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    errorMessage = e.localizedMessage ?: "Analysis failed"
                )
            }
        }
    }

    /**
     * Triggered by user tap anywhere on the tactile surface.
     */
    fun onUserTriggerScan() {
        viewModelScope.launch {
            val bmp = frameProvider?.invoke()
            if (bmp != null) {
                analyzeBitmap(bmp)
            } else {
                feedbackController.speakInformative("Camera initializing, please try in a moment.")
            }
        }
    }

    /**
     * Toggles between Continuous Scanning (every 3 seconds) and On-Demand Tap mode.
     */
    fun toggleOperatingMode() {
        val newMode = if (_uiState.value.operatingMode == OperatingMode.ON_DEMAND) {
            OperatingMode.CONTINUOUS_AUTO
        } else {
            OperatingMode.ON_DEMAND
        }

        _uiState.value = _uiState.value.copy(operatingMode = newMode)

        if (newMode == OperatingMode.CONTINUOUS_AUTO) {
            feedbackController.speakInformative("Continuous safety scan enabled.")
            startContinuousLoop()
        } else {
            continuousScanJob?.cancel()
            feedbackController.speakInformative("On demand mode. Tap screen anytime to scan.")
        }
    }

    private fun startContinuousLoop() {
        continuousScanJob?.cancel()
        continuousScanJob = viewModelScope.launch {
            while (_uiState.value.operatingMode == OperatingMode.CONTINUOUS_AUTO) {
                val bmp = frameProvider?.invoke()
                if (bmp != null && !_uiState.value.isAnalyzing) {
                    analyzeBitmap(bmp)
                }
                delay(3000L)
            }
        }
    }

    /**
     * Toggles the LED flashlight.
     */
    fun toggleTorch() {
        val newState = !_uiState.value.isTorchActive
        setTorch(newState)
        feedbackController.playTorchToggled(newState)
        val msg = if (newState) "Flashlight on" else "Flashlight off"
        feedbackController.speakInformative(msg)
    }

    private fun setTorch(active: Boolean) {
        _uiState.value = _uiState.value.copy(isTorchActive = active)
        sensorAnalyzer.updateTorchState(active)
        torchController?.invoke(active)
    }

    /**
     * Speaks the last alert or detailed description again.
     */
    fun repeatLastAlert() {
        val assessment = _uiState.value.currentAssessment
        if (assessment != null) {
            val text = assessment.spokenAlert ?: assessment.detailedSummary ?: "Path is clear."
            feedbackController.speakInformative(text)
        } else {
            feedbackController.speakInformative(_uiState.value.lastAlertText)
        }
    }

    /**
     * Halts all ongoing audio/speech immediately.
     */
    fun stopAllSpeech() {
        feedbackController.stopAllSpeech()
    }

    /**
     * Simulates a test hazard scenario for accessibility testing and safety verification.
     */
    fun simulateHazard(
        severity: HazardSeverity,
        title: String,
        spoken: String?,
        obstacles: List<DetectedObstacle> = emptyList(),
        hasStairsDown: Boolean = false,
        hasStairsUp: Boolean = false,
        hasDropOff: Boolean = false,
        hasPothole: Boolean = false
    ) {
        val assessment = HazardAssessment(
            severity = severity,
            spokenAlert = spoken,
            detailedSummary = title,
            obstacles = obstacles,
            hasStairsDown = hasStairsDown,
            hasStairsUp = hasStairsUp,
            hasDropOff = hasDropOff,
            hasPotholeOrVoid = hasPothole,
            isOfflineEngine = !_uiState.value.isOnline
        )
        feedbackController.dispatchAssessment(assessment)
        val newHistory = (listOf(assessment) + _uiState.value.assessmentHistory).take(15)
        _uiState.value = _uiState.value.copy(
            currentAssessment = assessment,
            lastAlertText = spoken ?: "Path clear.",
            assessmentHistory = newHistory
        )
    }

    override fun onCleared() {
        super.onCleared()
        continuousScanJob?.cancel()
        sensorAnalyzer.stopListening()
        feedbackController.release()
    }
}
