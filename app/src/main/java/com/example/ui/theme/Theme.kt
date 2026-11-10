package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HighContrastColorScheme = darkColorScheme(
    primary = SafetyYellow,
    onPrimary = Color.Black,
    primaryContainer = HighContrastSurfaceElevated,
    onPrimaryContainer = SafetyYellow,
    secondary = CyberCyan,
    onSecondary = Color.Black,
    error = DangerRed,
    onError = Color.White,
    background = HighContrastBackground,
    onBackground = HighContrastTextPrimary,
    surface = HighContrastSurface,
    onSurface = HighContrastTextPrimary,
    surfaceVariant = HighContrastSurfaceElevated,
    onSurfaceVariant = HighContrastTextSecondary
)

@Composable
fun TellMeWhatYouSeeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = HighContrastColorScheme,
        typography = Typography,
        content = content
    )
}
