package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.feedback.AudioHapticFeedbackController
import com.example.model.DetectedObstacle
import com.example.model.HazardAssessment
import com.example.model.HazardSeverity
import com.example.model.OperatingMode
import com.example.model.SensorTelemetry
import com.example.safety.MiDaSDepthEstimator
import com.example.safety.OnDeviceSafetyEngine
import com.example.sensor.DeviceSensorAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "VisionViewModel"

data class AssistantUiState(
    val isAnalyzing: Boolean = false,
    val currentAssessment: HazardAssessment? = null,
    val lastAlertText: String = "Ready. Tap screen or press Volume Up/Down to scan.",
    val operatingMode: OperatingMode = OperatingMode.ON_DEMAND,
    val isTorchActive: Boolean = false,
    val isDepthModelReady: Boolean = false,
    val telemetry: SensorTelemetry = SensorTelemetry(),
    val assessmentHistory: List<HazardAssessment> = emptyList(),
    val errorMessage: String? = null
)

class VisionAssistantViewModel(
    application: Application
) : AndroidViewModel(application) {

    private sealed class CaptureOutcome {
        data class Success(val bitmap: Bitmap) : CaptureOutcome()
        data object MotionBlurred : CaptureOutcome()
        data object Unavailable : CaptureOutcome()
    }

    private val feedbackController = AudioHapticFeedbackController(application)
    private val sensorAnalyzer = DeviceSensorAnalyzer(application)
    private val depthEstimator = MiDaSDepthEstimator(application)
    private val onDeviceSafetyEngine = OnDeviceSafetyEngine(depthEstimator)

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var continuousScanJob: Job? = null
    private var isCapturing: Boolean = false
    var frameProvider: (suspend () -> Bitmap?)? = null
    var torchController: ((Boolean) -> Unit)? = null

    init {
        sensorAnalyzer.startListening()

        // Warm up on-device MiDaS depth model in background for fast first scan
        viewModelScope.launch(Dispatchers.Default) {
            val ready = depthEstimator.warmUp()
            _uiState.value = _uiState.value.copy(isDepthModelReady = ready)
        }

        // Sync sensor telemetry
        viewModelScope.launch {
            sensorAnalyzer.telemetry.collectLatest { tel ->
                _uiState.value = _uiState.value.copy(telemetry = tel)
            }
        }
    }

    /**
     * Captures a frame with a lightweight sensor-based motion blur guard.
     * At night or in low-light (< 35 lux), triggers a synchronized quick flash pulse
     * (turns torch on for ~260ms for 3A convergence, captures, and turns it off).
     */
    private suspend fun captureFrameWithFlashPulse(forcePulse: Boolean = false): CaptureOutcome {
        val provider = frameProvider ?: return CaptureOutcome.Unavailable
        val isLowLight = forcePulse || sensorAnalyzer.isLowLight()
        val shouldPulse = isLowLight && !_uiState.value.isTorchActive

        sensorAnalyzer.beginCaptureMotionWindow()
        val capturedBitmap: Bitmap? = if (shouldPulse) {
            try {
                torchController?.invoke(true)
                delay(260L) // Allow camera sensor 3A (Auto-Exposure/Auto-Focus) to settle under light
                provider.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Flash pulse capture encountered error", e)
                provider.invoke()
            } finally {
                torchController?.invoke(false)
            }
        } else {
            provider.invoke()
        }

        val wasShaken = sensorAnalyzer.endCaptureMotionWindowAndCheckShake()
        if (capturedBitmap == null) return CaptureOutcome.Unavailable
        if (wasShaken) return CaptureOutcome.MotionBlurred
        return CaptureOutcome.Success(capturedBitmap)
    }

    /**
     * Executes analysis of the provided bitmap using the user's current camera aim intent.
     */
    fun analyzeBitmap(bitmap: Bitmap) {
        if (_uiState.value.isAnalyzing) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAnalyzing = true, errorMessage = null)

            // 1. Analyze frame luminance for darkness telemetry
            val lux = sensorAnalyzer.analyzeFrameLuminance(bitmap)

            // If an un-pulsed first capture was pitch black (< 18 lux), quickly re-capture with flash pulse
            var targetBitmap = bitmap
            if (lux < 18f && !_uiState.value.isTorchActive) {
                when (val retry = captureFrameWithFlashPulse(forcePulse = true)) {
                    is CaptureOutcome.Success -> {
                        targetBitmap = retry.bitmap
                        sensorAnalyzer.analyzeFrameLuminance(targetBitmap)
                    }
                    is CaptureOutcome.MotionBlurred -> {
                        _uiState.value = _uiState.value.copy(
                            isAnalyzing = false,
                            lastAlertText = "Camera moved. Hold steady and try again."
                        )
                        feedbackController.playMotionBlurWarning()
                        return@launch
                    }
                    is CaptureOutcome.Unavailable -> Unit
                }
            }

            try {
                val telemetry = _uiState.value.telemetry
                val assessment = withContext(Dispatchers.Default) {
                    onDeviceSafetyEngine.analyzeFrame(
                        targetBitmap,
                        telemetry.aimZone,
                        telemetry.pitchDegrees
                    )
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
     * Triggered by user tap anywhere on the screen OR pressing Volume Up / Volume Down.
     */
    fun onUserTriggerScan() {
        if (_uiState.value.isAnalyzing || isCapturing) return
        viewModelScope.launch {
            isCapturing = true
            try {
                when (val outcome = captureFrameWithFlashPulse()) {
                    is CaptureOutcome.Success -> {
                        // Immediately inform user snapshot is locked in so they can relax/move normally
                        feedbackController.playShutterLockedCue()
                        analyzeBitmap(outcome.bitmap)
                    }
                    is CaptureOutcome.MotionBlurred -> {
                        _uiState.value = _uiState.value.copy(
                            lastAlertText = "Camera moved. Hold steady and try again."
                        )
                        feedbackController.playMotionBlurWarning()
                    }
                    is CaptureOutcome.Unavailable -> {
                        feedbackController.speakInformative("Camera initializing, please try in a moment.")
                    }
                }
            } finally {
                isCapturing = false
            }
        }
    }

    /**
     * Toggles between Continuous Scanning (every 3 seconds) and On-Demand mode.
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
            feedbackController.speakInformative("On demand mode. Tap screen or press a volume button anytime to scan.")
        }
    }

    private fun startContinuousLoop() {
        continuousScanJob?.cancel()
        continuousScanJob = viewModelScope.launch {
            while (_uiState.value.operatingMode == OperatingMode.CONTINUOUS_AUTO) {
                if (!_uiState.value.isAnalyzing && !isCapturing) {
                    val outcome = captureFrameWithFlashPulse()
                    if (outcome is CaptureOutcome.Success) {
                        analyzeBitmap(outcome.bitmap)
                    }
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
            isOfflineEngine = true
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
        depthEstimator.close()
        feedbackController.release()
    }
}
