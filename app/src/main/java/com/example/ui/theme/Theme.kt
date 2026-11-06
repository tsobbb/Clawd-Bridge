package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ClawdDarkColorScheme = darkColorScheme(
    primary = NeonEmerald,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF00381B),
    onPrimaryContainer = Color(0xFF6BFFAB),
    secondary = ElectricCyan,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF004953),
    onSecondaryContainer = Color(0xFF9CF4FF),
    tertiary = ElectricViolet,
    onTertiary = Color.Black,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = CoralRed,
    onError = Color.White
)

@Composable
fun ClawdBridgeTheme(
    darkTheme: Boolean = true, // Default to sleek audio console dark theme
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ClawdDarkColorScheme,
        typography = Typography,
        content = content
    )
}
