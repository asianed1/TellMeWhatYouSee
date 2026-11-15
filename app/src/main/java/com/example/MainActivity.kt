package com.example

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.appcheck.configureAppCheck
import com.example.ui.VisionAssistantScreen
import com.example.ui.theme.HighContrastBackground
import com.example.ui.theme.TellMeWhatYouSeeTheme
import com.example.viewmodel.VisionAssistantViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: VisionAssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Mandatory App Check configuration with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        // STEP 2: Initialize Accessible Compose UI
        setContent {
            TellMeWhatYouSeeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = HighContrastBackground
                ) {
                    VisionAssistantScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CAMERA -> {
                if (event?.repeatCount == 0) {
                    viewModel.onUserTriggerScan()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
