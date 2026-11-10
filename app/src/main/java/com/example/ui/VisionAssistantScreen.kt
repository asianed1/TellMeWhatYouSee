package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.DetectedObstacle
import com.example.model.HazardSeverity
import com.example.model.OperatingMode
import com.example.ui.theme.CautionAmber
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DangerRed
import com.example.ui.theme.HighContrastBackground
import com.example.ui.theme.HighContrastSurface
import com.example.ui.theme.HighContrastSurfaceElevated
import com.example.ui.theme.HighContrastTextPrimary
import com.example.ui.theme.HighContrastTextSecondary
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SafetyYellow
import com.example.viewmodel.VisionAssistantViewModel

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VisionAssistantScreen(
    viewModel: VisionAssistantViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val insets = WindowInsets.statusBars.asPaddingValues()

    // Pulsing animation for Red Alert
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val currentSeverity = uiState.currentAssessment?.severity ?: HazardSeverity.SAFE

    val borderColor by animateColorAsState(
        targetValue = when (currentSeverity) {
            HazardSeverity.IMMINENT_DANGER -> DangerRed.copy(alpha = pulseAlpha)
            HazardSeverity.CAUTION -> CautionAmber
            HazardSeverity.SAFE -> Color.Transparent
        },
        label = "borderColor"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HighContrastBackground)
            .border(width = 6.dp, color = borderColor)
            .testTag("main_tactile_surface")
            .combinedClickable(
                onClick = { viewModel.onUserTriggerScan() },
                onDoubleClick = { viewModel.repeatLastAlert() },
                onLongClick = { viewModel.toggleTorch() }
            )
            .semantics {
                contentDescription = "Tactile screen surface. Tap to scan path. Double tap to repeat alert. Long press for flashlight."
            }
    ) {
        // Camera viewfinder layer
        if (hasCameraPermission) {
            CameraViewfinder(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.6f),
                onCameraReady = { captureLambda, torchLambda ->
                    viewModel.frameProvider = captureLambda
                    viewModel.torchController = torchLambda
                }
            )
        }

        // Dark gradient scrim for maximum text contrast
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            HighContrastBackground.copy(alpha = 0.88f),
                            Color.Transparent,
                            HighContrastBackground.copy(alpha = 0.94f)
                        )
                    )
                )
        )

        // Main UI Controls and Status
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // TOP STATUS BAR
            TopAccessibilityBar(
                isOnline = uiState.isOnline,
                operatingMode = uiState.operatingMode,
                isTorchOn = uiState.isTorchActive,
                angleGuidance = uiState.telemetry.angleGuidanceText,
                isAngleOptimal = uiState.telemetry.isWalkingAngleOptimal,
                onToggleMode = { viewModel.toggleOperatingMode() },
                onToggleTorch = { viewModel.toggleTorch() },
                onStopAudio = { viewModel.stopAllSpeech() }
            )

            // CENTER: HAZARD ASSESSMENT HERO BANNER
            CenterHazardHero(
                severity = currentSeverity,
                alertText = uiState.lastAlertText,
                isAnalyzing = uiState.isAnalyzing,
                isOffline = !uiState.isOnline,
                hasStairs = uiState.currentAssessment?.hasStairsDown == true,
                hasDropOff = uiState.currentAssessment?.hasDropOff == true,
                onTapScan = { viewModel.onUserTriggerScan() }
            )

            // BOTTOM: OBSTACLES RADAR & SIMULATOR TEST PANEL
            BottomAssistancePanel(
                obstacles = uiState.currentAssessment?.obstacles ?: emptyList(),
                onSimulate = { severity, title, spoken, stairs, dropOff ->
                    viewModel.simulateHazard(
                        severity = severity,
                        title = title,
                        spoken = spoken,
                        hasStairs = stairs,
                        hasDropOff = dropOff
                    )
                }
            )
        }
    }
}

@Composable
fun TopAccessibilityBar(
    isOnline: Boolean,
    operatingMode: OperatingMode,
    isTorchOn: Boolean,
    angleGuidance: String,
    isAngleOptimal: Boolean,
    onToggleMode: () -> Unit,
    onToggleTorch: () -> Unit,
    onStopAudio: () -> Unit
) {
    Surface(
        color = HighContrastSurface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Online/Offline Status Indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics {
                        contentDescription = if (isOnline) "Connected to Cloud Gemini Vision" else "Offline. Local Safety Core active."
                    }
                ) {
                    Icon(
                        imageVector = if (isOnline) Icons.Default.Cloud else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (isOnline) SafeGreen else CautionAmber,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isOnline) "Cloud AI" else "Local Core",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isOnline) SafeGreen else CautionAmber
                    )
                }

                // Action Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Flashlight Toggle
                    IconButton(
                        onClick = onToggleTorch,
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (isTorchOn) SafetyYellow else HighContrastSurfaceElevated,
                                CircleShape
                            )
                            .testTag("torch_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = if (isTorchOn) "Turn off flashlight" else "Turn on flashlight",
                            tint = if (isTorchOn) Color.Black else HighContrastTextPrimary
                        )
                    }

                    // Mode Switcher (Tap vs Auto Continuous)
                    Button(
                        onClick = onToggleMode,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (operatingMode == OperatingMode.CONTINUOUS_AUTO) CyberCyan else HighContrastSurfaceElevated
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .height(44.dp)
                            .testTag("mode_toggle_button")
                    ) {
                        Text(
                            text = if (operatingMode == OperatingMode.CONTINUOUS_AUTO) "Auto Scan" else "Tap Mode",
                            color = if (operatingMode == OperatingMode.CONTINUOUS_AUTO) Color.Black else HighContrastTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    // Stop Speech
                    IconButton(
                        onClick = onStopAudio,
                        modifier = Modifier
                            .size(44.dp)
                            .background(HighContrastSurfaceElevated, CircleShape)
                            .testTag("stop_speech_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop speech audio",
                            tint = DangerRed
                        )
                    }
                }
            }

            // Tilt Angle Indicator Bar
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics {
                    contentDescription = "Camera tilt status: $angleGuidance"
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (isAngleOptimal) SafeGreen else CautionAmber,
                            CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = angleGuidance,
                    fontSize = 11.sp,
                    color = if (isAngleOptimal) SafeGreen else CautionAmber,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun CenterHazardHero(
    severity: HazardSeverity,
    alertText: String,
    isAnalyzing: Boolean,
    isOffline: Boolean,
    hasStairs: Boolean,
    hasDropOff: Boolean,
    onTapScan: () -> Unit
) {
    val cardColor = when (severity) {
        HazardSeverity.IMMINENT_DANGER -> DangerRed
        HazardSeverity.CAUTION -> CautionAmber
        HazardSeverity.SAFE -> HighContrastSurface.copy(alpha = 0.95f)
    }

    val contentColor = when (severity) {
        HazardSeverity.IMMINENT_DANGER -> Color.White
        HazardSeverity.CAUTION -> Color.Black
        HazardSeverity.SAFE -> HighContrastTextPrimary
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag("hazard_hero_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isAnalyzing) {
                CircularProgressIndicator(
                    color = contentColor,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("scanning_spinner")
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (isOffline) "Local Safety Core Scanning..." else "Gemini Analyzing Path...",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
            } else {
                when (severity) {
                    HazardSeverity.IMMINENT_DANGER -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Critical Warning",
                                tint = contentColor,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "CRITICAL STOP",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                color = contentColor
                            )
                        }
                    }
                    HazardSeverity.CAUTION -> {
                        Text(
                            text = "CAUTION OBSTACLE",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = contentColor
                        )
                    }
                    HazardSeverity.SAFE -> {
                        Text(
                            text = "CLEAR PATH",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = SafeGreen
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = alertText,
                    fontSize = 19.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    textAlign = TextAlign.Center
                )

                if (hasStairs || hasDropOff) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = Color.Black.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (hasStairs) "⚠️ DESCENDING STAIRS DETECTED" else "⚠️ DROP-OFF AHEAD",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Big tactile button for scan trigger
                Button(
                    onClick = onTapScan,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (severity == HazardSeverity.SAFE) SafetyYellow else Color.Black.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("scan_now_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = if (severity == HazardSeverity.SAFE) Color.Black else Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TAP SCREEN TO SCAN",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = if (severity == HazardSeverity.SAFE) Color.Black else Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BottomAssistancePanel(
    obstacles: List<DetectedObstacle>,
    onSimulate: (HazardSeverity, String, String?, Boolean, Boolean) -> Unit
) {
    var showSimulator by remember { mutableStateOf(false) }

    Surface(
        color = HighContrastSurface.copy(alpha = 0.95f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Detected Obstacles Clock Radar Strip
            if (obstacles.isNotEmpty()) {
                Text(
                    text = "Obstacles in Camera FOV:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = HighContrastTextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(obstacles) { item ->
                        Surface(
                            color = HighContrastSurfaceElevated,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "${item.name} (${item.clockPosition})",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SafetyYellow
                                )
                                Text(
                                    text = "${item.approxSteps} steps (${item.approxDistanceMeters}m) • ${item.elevation}",
                                    fontSize = 11.sp,
                                    color = HighContrastTextPrimary
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Simulator Toggle Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Double-tap screen to repeat last alert",
                    fontSize = 11.sp,
                    color = HighContrastTextSecondary
                )

                Button(
                    onClick = { showSimulator = !showSimulator },
                    colors = ButtonDefaults.buttonColors(containerColor = HighContrastSurfaceElevated),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("toggle_simulator_button")
                ) {
                    Text(
                        text = if (showSimulator) "Hide Tests" else "Test Hazards",
                        fontSize = 12.sp,
                        color = CyberCyan
                    )
                }
            }

            // Hazard Simulator Drawer
            AnimatedVisibility(visible = showSimulator) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Text(
                        text = "Safety Test Scenarios (Instant Alarm & Haptic Verification):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = SafetyYellow
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Scenario 1: Descending Stairs
                        Button(
                            onClick = {
                                onSimulate(
                                    HazardSeverity.IMMINENT_DANGER,
                                    "Concrete staircase going downwards",
                                    "STOP! Stairs going down directly ahead, 2 steps away.",
                                    true,
                                    false
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sim_stairs_button")
                        ) {
                            Text("Stairs Down", fontSize = 11.sp, color = Color.White)
                        }

                        // Scenario 2: Drop-Off Void
                        Button(
                            onClick = {
                                onSimulate(
                                    HazardSeverity.IMMINENT_DANGER,
                                    "Curved subway platform edge",
                                    "STOP! Drop-off detected directly ahead, 1 step away.",
                                    false,
                                    true
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sim_dropoff_button")
                        ) {
                            Text("Drop-Off", fontSize = 11.sp, color = Color.White)
                        }

                        // Scenario 3: Caution Obstacle
                        Button(
                            onClick = {
                                onSimulate(
                                    HazardSeverity.CAUTION,
                                    "Trash can at 1 o'clock",
                                    "Caution: Trash can at 1 o'clock, 3 steps ahead.",
                                    false,
                                    false
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CautionAmber),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sim_caution_button")
                        ) {
                            Text("Obstacle", fontSize = 11.sp, color = Color.Black)
                        }

                        // Scenario 4: Clear Path (Quiet-by-Default)
                        Button(
                            onClick = {
                                onSimulate(
                                    HazardSeverity.SAFE,
                                    "Open sidewalk path",
                                    null, // Silent under Quiet-by-Default!
                                    false,
                                    false
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sim_safe_button")
                        ) {
                            Text("Clear", fontSize = 11.sp, color = Color.Black)
                        }
                    }
                }
            }
        }
    }
}
