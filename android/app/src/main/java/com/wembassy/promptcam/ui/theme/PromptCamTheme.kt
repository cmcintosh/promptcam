package com.wembassy.promptcam.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Dark theme color scheme — optimized for camera app (dark UI)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF2196F3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0D47A1),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF4CAF50),
    onSecondary = Color.White,
    tertiary = Color(0xFFFF9800),
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1A1A1A),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFE0E0E0),
    error = Color(0xFFF44336),
    onError = Color.White,
)

// Light theme color scheme
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color.White,
    secondary = Color(0xFF388E3C),
    onSecondary = Color.White,
    tertiary = Color(0xFFF57C00),
    onTertiary = Color.Black,
    background = Color(0xFFF5F5F5),
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    error = Color(0xFFD32F2F),
    onError = Color.White,
)

/**
 * PromptCam theme composable.
 *
 * Uses Material3 with a dark theme by default (camera apps typically use dark UI).
 * Supports dynamic color on Android 12+ if available.
 */
@Composable
fun PromptCamTheme(
    darkTheme: Boolean = true, // Default to dark theme for camera app
    dynamicColor: Boolean = false, // Disabled by default for consistent camera UI
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
