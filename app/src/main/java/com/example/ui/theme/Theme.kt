package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ClawdGlassColorScheme = darkColorScheme(
    primary = MintCta,
    onPrimary = MintCtaContent,
    primaryContainer = Color(0xFF132E24),
    onPrimaryContainer = MintCta,
    secondary = SecondaryGlassText,
    onSecondary = Color(0xFF101217),
    secondaryContainer = Color(0xFF1F232C),
    onSecondaryContainer = TextPrimary,
    tertiary = SemanticEmerald,
    onTertiary = MintCtaContent,
    background = BaseCanvas,
    onBackground = TextPrimary,
    surface = Color(0xFF12141A),
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF1A1D25),
    onSurfaceVariant = TextMuted,
    outline = SpecularStroke,
    outlineVariant = HairlineDivider,
    error = SemanticCoral,
    onError = Color(0xFF280808)
)

@Composable
fun ClawdBridgeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ClawdGlassColorScheme,
        typography = Typography,
        content = content
    )
}
