package com.example.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import com.example.model.NowPlayingData
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Immutable
data class LivingPaletteState(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val orbitAngle: Float,
    val auraPulse: Float,
    val isTrackActive: Boolean
)

@Composable
fun rememberLivingPaletteState(liveState: NowPlayingData): LivingPaletteState {
    val isTrackActive = liveState.hasMedia && liveState.isPlaying

    val targetTriad = remember(liveState.accentColorHex, liveState.accentColorsHex, isTrackActive) {
        if (!isTrackActive) {
            listOf(NeonEmerald, ElectricCyan, ElectricViolet)
        } else {
            val primary = parseHexOrDefault(liveState.accentColorHex, ElectricCyan)
            val parsed = liveState.accentColorsHex.map { parseHexOrDefault(it, primary) }
            when {
                parsed.size >= 3 -> listOf(parsed[0], parsed[1], parsed[2])
                parsed.size == 2 -> listOf(parsed[0], parsed[1], primary)
                else -> listOf(primary, ElectricCyan, ElectricViolet)
            }
        }
    }

    val primary by animateColorAsState(
        targetValue = targetTriad[0],
        animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        label = "living_palette_primary"
    )
    val secondary by animateColorAsState(
        targetValue = targetTriad[1],
        animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        label = "living_palette_secondary"
    )
    val tertiary by animateColorAsState(
        targetValue = targetTriad[2],
        animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        label = "living_palette_tertiary"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "living_aurora_transition")
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "living_orbit_angle"
    )
    val auraPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "living_aura_pulse"
    )

    return LivingPaletteState(
        primary = primary,
        secondary = secondary,
        tertiary = tertiary,
        orbitAngle = orbitAngle,
        auraPulse = auraPulse,
        isTrackActive = isTrackActive
    )
}

@Composable
fun LivingAuroraBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = Color(0xFF090B10))

        val w = size.width
        val h = size.height
        val rad = Math.toRadians(palette.orbitAngle.toDouble())
        val rad2 = Math.toRadians((palette.orbitAngle * 1.15f + 125.0))
        val rad3 = Math.toRadians((-palette.orbitAngle * 0.85f + 245.0))

        val intensity = if (palette.isTrackActive) {
            0.30f + palette.auraPulse * 0.10f
        } else {
            0.15f + palette.auraPulse * 0.06f
        }

        // Blob 1: Primary orbiting across upper-left / top half
        val c1 = Offset(
            x = w * (0.34f + 0.28f * cos(rad).toFloat()),
            y = h * (0.20f + 0.14f * sin(rad).toFloat())
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.primary.copy(alpha = intensity),
                    palette.primary.copy(alpha = intensity * 0.35f),
                    Color.Transparent
                ),
                center = c1,
                radius = w * 0.85f
            ),
            radius = w * 0.85f,
            center = c1
        )

        // Blob 2: Secondary gliding across middle-right
        val c2 = Offset(
            x = w * (0.68f + 0.25f * cos(rad2).toFloat()),
            y = h * (0.48f + 0.20f * sin(rad2).toFloat())
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.secondary.copy(alpha = intensity * 0.90f),
                    palette.secondary.copy(alpha = intensity * 0.28f),
                    Color.Transparent
                ),
                center = c2,
                radius = w * 0.82f
            ),
            radius = w * 0.82f,
            center = c2
        )

        // Blob 3: Tertiary breathing near lower third
        val c3 = Offset(
            x = w * (0.48f + 0.30f * cos(rad3).toFloat()),
            y = h * (0.82f + 0.13f * sin(rad3).toFloat())
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.tertiary.copy(alpha = intensity * 0.85f),
                    palette.tertiary.copy(alpha = intensity * 0.25f),
                    Color.Transparent
                ),
                center = c3,
                radius = w * 0.78f
            ),
            radius = w * 0.78f,
            center = c3
        )
    }
}

fun rotatingBorderBrush(
    palette: LivingPaletteState,
    alpha: Float = 0.45f,
    angleOffsetDeg: Float = 0f
): Brush {
    return RotatingLinearShaderBrush(
        colors = listOf(
            palette.primary.copy(alpha = alpha),
            palette.secondary.copy(alpha = alpha * 0.88f),
            palette.tertiary.copy(alpha = alpha * 0.80f),
            palette.primary.copy(alpha = alpha)
        ),
        angleDegrees = palette.orbitAngle + angleOffsetDeg
    )
}

fun heroRotatingBorderBrush(palette: LivingPaletteState): Brush {
    val primaryAlpha = if (palette.isTrackActive) 0.96f else 0.58f
    val secondaryAlpha = if (palette.isTrackActive) 0.88f else 0.46f
    return RotatingLinearShaderBrush(
        colors = listOf(
            palette.primary.copy(alpha = primaryAlpha),
            palette.secondary.copy(alpha = secondaryAlpha),
            palette.tertiary.copy(alpha = secondaryAlpha * 0.92f),
            palette.primary.copy(alpha = primaryAlpha)
        ),
        angleDegrees = palette.orbitAngle
    )
}

fun shimmeringHorizontalBrush(
    palette: LivingPaletteState,
    alpha: Float = 1f
): Brush {
    val phase = ((palette.orbitAngle % 360f) / 120f).coerceIn(0f, 3f)
    val c1: Color
    val c2: Color
    val c3: Color
    when {
        phase < 1f -> {
            c1 = androidx.compose.ui.graphics.lerp(palette.primary, palette.secondary, phase)
            c2 = androidx.compose.ui.graphics.lerp(palette.secondary, palette.tertiary, phase)
            c3 = androidx.compose.ui.graphics.lerp(palette.tertiary, palette.primary, phase)
        }
        phase < 2f -> {
            val t = phase - 1f
            c1 = androidx.compose.ui.graphics.lerp(palette.secondary, palette.tertiary, t)
            c2 = androidx.compose.ui.graphics.lerp(palette.tertiary, palette.primary, t)
            c3 = androidx.compose.ui.graphics.lerp(palette.primary, palette.secondary, t)
        }
        else -> {
            val t = phase - 2f
            c1 = androidx.compose.ui.graphics.lerp(palette.tertiary, palette.primary, t)
            c2 = androidx.compose.ui.graphics.lerp(palette.primary, palette.secondary, t)
            c3 = androidx.compose.ui.graphics.lerp(palette.secondary, palette.tertiary, t)
        }
    }
    return Brush.horizontalGradient(
        listOf(c1.copy(alpha = alpha), c2.copy(alpha = alpha), c3.copy(alpha = alpha))
    )
}

private class RotatingLinearShaderBrush(
    private val colors: List<Color>,
    private val angleDegrees: Float
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = hypot(cx, cy).coerceAtLeast(1f)
        val rad = Math.toRadians(angleDegrees.toDouble())
        val dx = (cos(rad) * radius).toFloat()
        val dy = (sin(rad) * radius).toFloat()
        return LinearGradientShader(
            from = Offset(cx - dx, cy - dy),
            to = Offset(cx + dx, cy + dy),
            colors = colors,
            tileMode = TileMode.Clamp
        )
    }
}

private fun parseHexOrDefault(hex: String, fallback: Color): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        fallback
    }
}
