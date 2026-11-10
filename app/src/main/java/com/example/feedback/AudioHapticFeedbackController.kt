package com.example.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.model.HazardAssessment
import com.example.model.HazardSeverity
import com.example.model.HapticPattern
import java.util.Locale

private const val TAG = "FeedbackController"

/**
 * Coordinates multi-sensory feedback (Tone alarms, Text-To-Speech priority queues,
 * and calibrated hardware vibration waveforms) for blind and low-vision accessibility.
 */
class AudioHapticFeedbackController(
    private val context: Context
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private var urgentToneGenerator: ToneGenerator? = null
    private var cautionToneGenerator: ToneGenerator? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
            urgentToneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            cautionToneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (e: Exception) {
            Log.e(TAG, "Failed initializing tone generators", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val result = engine.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "US English TTS language not supported, falling back to default")
                    engine.language = Locale.getDefault()
                }
                // Slightly elevated speech rate for quick safety delivery
                engine.setSpeechRate(1.1f)
                engine.setPitch(1.0f)
                isTtsReady = true
                Log.d(TAG, "TextToSpeech successfully initialized.")
            }
        } else {
            Log.e(TAG, "TTS initialization failed with code $status")
        }
    }

    /**
     * Dispatches multi-sensory feedback according to the Quiet-by-Default specification.
     */
    fun dispatchAssessment(assessment: HazardAssessment) {
        when (assessment.severity) {
            HazardSeverity.IMMINENT_DANGER -> {
                // 1. Play high-urgency STOP tone
                playUrgentStopAlarm()
                // 2. High-intensity tactile triple-stop burst
                vibratePattern(HapticPattern.TRIPLE_STOP_BURST)
                // 3. Spoken imperative preempting all speech
                assessment.spokenAlert?.let { text ->
                    speakUrgent(text)
                }
            }
            HazardSeverity.CAUTION -> {
                // 1. Informational two-tone caution chime
                playCautionChime()
                // 2. Double-tap haptic pulse
                vibratePattern(HapticPattern.DOUBLE_CAUTION_PULSE)
                // 3. Spoken caution advice
                assessment.spokenAlert?.let { text ->
                    speakInformative(text)
                }
            }
            HazardSeverity.SAFE -> {
                // Quiet-by-default: absolute silence, only a micro-haptic tick to confirm evaluation
                vibratePattern(HapticPattern.SAFE_TICK)
            }
        }
    }

    /**
     * Plays an unmistakable, piercing alarm tone for imminent hazards.
     */
    fun playUrgentStopAlarm() {
        try {
            // TONE_CDMA_EMERGENCY_RINGBACK or TONE_SUP_ERROR
            urgentToneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 350)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play urgent stop tone", e)
        }
    }

    /**
     * Plays a pleasant double caution chime.
     */
    fun playCautionChime() {
        try {
            cautionToneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play caution chime", e)
        }
    }

    /**
     * Plays an audible earcon when the flashlight / torch status changes.
     */
    fun playTorchToggled(isOn: Boolean) {
        try {
            val tone = if (isOn) ToneGenerator.TONE_PROP_PROMPT else ToneGenerator.TONE_PROP_ACK
            cautionToneGenerator?.startTone(tone, 120)
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing torch earcon", e)
        }
    }

    /**
     * Hardware vibration waveforms based on safety level.
     */
    fun vibratePattern(pattern: HapticPattern) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        try {
            when (pattern) {
                HapticPattern.TRIPLE_STOP_BURST -> {
                    // Fast, high-intensity rapid triple burst: 120ms vib, 40ms pause, 120ms vib, 40ms pause, 240ms vib
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val timings = longArrayOf(0, 120, 40, 120, 40, 240)
                        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
                        vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(longArrayOf(0, 120, 40, 120, 40, 240), -1)
                    }
                }
                HapticPattern.DOUBLE_CAUTION_PULSE -> {
                    // Moderate double tap: 80ms vib, 70ms pause, 80ms vib
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val timings = longArrayOf(0, 80, 70, 80)
                        val amplitudes = intArrayOf(0, 200, 0, 200)
                        vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(longArrayOf(0, 80, 70, 80), -1)
                    }
                }
                HapticPattern.SAFE_TICK -> {
                    // Subtle 25ms micro-click confirming scan completion without audio clutter
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vib.vibrate(VibrationEffect.createOneShot(25, 120))
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(25)
                    }
                }
                HapticPattern.NONE -> Unit
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    /**
     * Speaks an urgent command, flushing any current speech queue immediately.
     */
    fun speakUrgent(message: String) {
        if (!isTtsReady) return
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "URGENT_HAZARD")
    }

    /**
     * Speaks non-emergency guidance, queued politely.
     */
    fun speakInformative(message: String) {
        if (!isTtsReady) return
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "CAUTION_INFO")
    }

    /**
     * Halts all ongoing audio and speech playback immediately.
     */
    fun stopAllSpeech() {
        tts?.stop()
        urgentToneGenerator?.stopTone()
        cautionToneGenerator?.stopTone()
    }

    fun release() {
        try {
            stopAllSpeech()
            tts?.shutdown()
            urgentToneGenerator?.release()
            cautionToneGenerator?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error during feedback controller release", e)
        }
    }
}
