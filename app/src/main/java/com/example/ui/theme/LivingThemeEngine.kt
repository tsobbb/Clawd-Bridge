package com.example.ui.theme

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.NowPlayingData
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-JVM HSL Color Normalization & Contrast Guard.
 * Normalizes cloud lightness into 0.35..0.55 for vivid dark-canvas luminescence,
 * and keeps B&W / light-paper covers strictly cool silver-graphite instead of muddy brown/purple.
 */
object ContrastGuard {

    data class Hsl(val h: Float, val s: Float, val l: Float)

    fun parseHexRgb(hex: String, fallbackRgb: Int): Int {
        val cleaned = hex.trim().removePrefix("#")
        if (cleaned.length != 6 && cleaned.length != 8) return fallbackRgb
        return cleaned.takeLast(6).toIntOrNull(16) ?: fallbackRgb
    }

    fun rgbToHsl(rgb: Int): Hsl {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f

        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2f
        val d = maxC - minC

        if (d < 1e-5f) return Hsl(0f, 0f, l)

        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        var h = when (maxC) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h *= 60f
        return Hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    fun hslToRgb(h: Float, s: Float, l: Float): Int {
        val normH = ((h % 360f) + 360f) % 360f
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((normH / 60f) % 2f - 1f))
        val m = l - c / 2f

        val (r1, g1, b1) = when {
            normH < 60f -> Triple(c, x, 0f)
            normH < 120f -> Triple(x, c, 0f)
            normH < 180f -> Triple(0f, c, x)
            normH < 240f -> Triple(0f, x, c)
            normH < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val r = ((r1 + m).coerceIn(0f, 1f) * 255f).toInt()
        val g = ((g1 + m).coerceIn(0f, 1f) * 255f).toInt()
        val b = ((b1 + m).coerceIn(0f, 1f) * 255f).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    fun normalizeAmbientBlobRgb(rgb: Int): Int {
        val hsl = rgbToHsl(rgb)
        if (hsl.s < 0.08f) {
            return hslToRgb(0f, 0f, hsl.l.coerceIn(0.18f, 0.34f))
        }
        val s = hsl.s.coerceIn(0.35f, 0.65f)
        val l = hsl.l.coerceIn(0.25f, 0.42f)
        return hslToRgb(hsl.h, s, l)
    }

    /**
     * Normalizes ambient Canvas clouds to Lightness 0.35..0.55 so dark covers (crimson/navy/teal)
     * glow vibrantly over #06070A instead of collapsing to black, while monochrome turns cool silver-graphite.
     */
    fun normalizeAmbientCloudRgb(rgb: Int, targetMinL: Float = 0.35f, targetMaxL: Float = 0.55f): Int {
        val hsl = rgbToHsl(rgb)
        if (hsl.s < 0.10f) {
            return hslToRgb(218f, 0.06f, hsl.l.coerceIn(targetMinL, targetMaxL))
        }
        val isWarmCream = hsl.h in 20f..55f && hsl.s < 0.34f && hsl.l > 0.65f
        if (isWarmCream) {
            return hslToRgb(36f, 0.22f, 0.50f)
        }
        val s = hsl.s.coerceIn(0.48f, 0.86f)
        val l = hsl.l.coerceIn(targetMinL, targetMaxL)
        return hslToRgb(hsl.h, s, l)
    }

    fun normalizePlayerAccentRgb(rgb: Int): Int {
        val hsl = rgbToHsl(rgb)
        if (hsl.s < 0.08f) {
            // Preserve achromatic monochrome as clean lunar silver-white
            return hslToRgb(0f, 0f, hsl.l.coerceIn(0.84f, 0.96f))
        }
        val s = hsl.s.coerceIn(0.50f, 0.88f)
        val l = hsl.l.coerceIn(0.72f, 0.82f)
        return hslToRgb(hsl.h, s, l)
    }

    fun normalizeCardTintRgb(rgb: Int): Int {
        val hsl = rgbToHsl(rgb)
        if (hsl.s < 0.08f) {
            return hslToRgb(0f, 0f, 0.11f)
        }
        val s = hsl.s.coerceIn(0.15f, 0.25f)
        val l = hsl.l.coerceIn(0.09f, 0.13f)
        return hslToRgb(hsl.h, s, l)
    }
}

private fun Int.toComposeColor(): Color = Color(
    red = ((this shr 16) and 0xFF) / 255f,
    green = ((this shr 8) and 0xFF) / 255f,
    blue = (this and 0xFF) / 255f,
    alpha = 1f
)

@Immutable
data class LivingPaletteState(
    val playerAccent: Color,
    val dot1: Color,
    val dot2: Color,
    val dot3: Color,
    val cardTint: Color,
    val isTrackActive: Boolean,
    val isDynamicBgEnabled: Boolean,
    internal val uiTimeSecondsState: State<Float>,
    internal val bgTimeSecondsState: State<Float>,
    internal val bgOpacityState: State<Float>,
    internal val cloudColorStates: List<State<Color>>
)

@Composable
fun rememberLivingPaletteState(
    liveState: NowPlayingData,
    @Suppress("UNUSED_PARAMETER") artworkBitmap: Bitmap? = null,
    dynamicBackgroundEnabled: Boolean = true
): LivingPaletteState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()

    // Pause animation ONLY when Activity leaves foreground; NEVER obey OS ANIMATOR_DURATION_SCALE / MIUI Reduce Motion
    val isInForeground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val isTrackActive = liveState.hasMedia && liveState.isPlaying

    // 1. Honest UI palette targets (PlayerAccent + 3 real cover dots + CardTint)
    val uiTargets = remember(liveState.accentColorHex, liveState.accentColorsHex, isTrackActive) {
        if (!isTrackActive) {
            listOf(
                MintCta,
                Color(0xFF34D399),
                Color(0xFF93C5FD),
                Color(0xFFC4B5FD),
                Color(0xFF10131A)
            )
        } else {
            val fallbackRgb = 0xE4E4E7
            val primaryRaw = ContrastGuard.parseHexRgb(liveState.accentColorHex, fallbackRgb)
            val dotRaws = liveState.accentColorsHex.map { ContrastGuard.parseHexRgb(it, primaryRaw) }
            val d0 = dotRaws.getOrElse(0) { primaryRaw }
            val d1 = dotRaws.getOrElse(1) { d0 }
            val d2 = dotRaws.getOrElse(2) { d1 }

            listOf(
                ContrastGuard.normalizePlayerAccentRgb(primaryRaw).toComposeColor(),
                d0.toComposeColor(),
                d1.toComposeColor(),
                d2.toComposeColor(),
                ContrastGuard.normalizeCardTintRgb(primaryRaw).toComposeColor()
            )
        }
    }

    val crossfadeSpec = remember { tween<Color>(durationMillis = 900, easing = FastOutSlowInEasing) }
    val playerAccent by animateColorAsState(uiTargets[0], crossfadeSpec, label = "player_accent")
    val dot1 by animateColorAsState(uiTargets[1], crossfadeSpec, label = "dot_1")
    val dot2 by animateColorAsState(uiTargets[2], crossfadeSpec, label = "dot_2")
    val dot3 by animateColorAsState(uiTargets[3], crossfadeSpec, label = "dot_3")
    val cardTint by animateColorAsState(uiTargets[4], crossfadeSpec, label = "card_tint")

    // 2. Pure 4-cloud color targets derived cleanly from SmartColorExtractor dots (zero bitmap mud!)
    val targetClouds = remember(liveState.accentColorHex, liveState.accentColorsHex, isTrackActive) {
        computeCloudPalette(
            isTrackActive = isTrackActive,
            accentColorHex = liveState.accentColorHex,
            accentColorsHex = liveState.accentColorsHex
        )
    }

    // Independent 1200ms cloud crossfade driven by SystemClock.uptimeMillis() (immune to MIUI animation scale 0f)
    val cloudState0 = remember { mutableStateOf(targetClouds[0]) }
    val cloudState1 = remember { mutableStateOf(targetClouds[1]) }
    val cloudState2 = remember { mutableStateOf(targetClouds[2]) }
    val cloudState3 = remember { mutableStateOf(targetClouds[3]) }
    val cloudStates = remember { listOf(cloudState0, cloudState1, cloudState2, cloudState3) }

    LaunchedEffect(targetClouds) {
        val startColors = cloudStates.map { it.value }
        if (startColors == targetClouds) return@LaunchedEffect
        val startMs = SystemClock.uptimeMillis()
        val durationMs = 1200f
        while (isActive) {
            var done = false
            withFrameNanos {
                val elapsed = (SystemClock.uptimeMillis() - startMs).coerceAtLeast(0L).toFloat()
                val rawT = (elapsed / durationMs).coerceIn(0f, 1f)
                val eased = FastOutSlowInEasing.transform(rawT)
                for (i in 0 until 4) {
                    cloudStates[i].value = lerp(startColors[i], targetClouds[i], eased)
                }
                if (rawT >= 1f) done = true
            }
            if (done) break
        }
        for (i in 0 until 4) {
            cloudStates[i].value = targetClouds[i]
        }
    }

    // 3. Independent 400ms fade on Dynamic Background toggle (fades cleanly down to #08090C even if OS scale == 0f)
    val bgOpacityState = remember { mutableFloatStateOf(if (dynamicBackgroundEnabled) 1f else 0f) }
    LaunchedEffect(dynamicBackgroundEnabled) {
        val startOpacity = bgOpacityState.floatValue
        val targetOpacity = if (dynamicBackgroundEnabled) 1f else 0f
        if (abs(startOpacity - targetOpacity) < 0.001f) {
            bgOpacityState.floatValue = targetOpacity
            return@LaunchedEffect
        }
        val startMs = SystemClock.uptimeMillis()
        val durationMs = 400f
        while (isActive) {
            var done = false
            withFrameNanos {
                val elapsed = (SystemClock.uptimeMillis() - startMs).coerceAtLeast(0L).toFloat()
                val rawT = (elapsed / durationMs).coerceIn(0f, 1f)
                val eased = FastOutSlowInEasing.transform(rawT)
                bgOpacityState.floatValue = startOpacity + (targetOpacity - startOpacity) * eased
                if (rawT >= 1f) done = true
            }
            if (done) break
        }
        bgOpacityState.floatValue = targetOpacity
    }

    // 4. Independent foreground UI clock (drives MiniEqualizerBars, WaveLoadingDots, status pulses)
    val uiTimeSecondsState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isInForeground) {
        if (!isInForeground) return@LaunchedEffect
        var lastUptimeMs = SystemClock.uptimeMillis()
        while (isActive) {
            withFrameNanos {
                val nowMs = SystemClock.uptimeMillis()
                val dt = ((nowMs - lastUptimeMs).coerceIn(0L, 100L)) / 1000f
                lastUptimeMs = nowMs
                if (dt > 0f) {
                    uiTimeSecondsState.floatValue = (uiTimeSecondsState.floatValue + dt) % 10000f
                }
            }
        }
    }

    // 5. Independent ambient background orbit clock (active when toggle ON & foreground)
    val bgTimeSecondsState = remember { mutableFloatStateOf(1.5f) }
    LaunchedEffect(dynamicBackgroundEnabled, isInForeground) {
        if (!dynamicBackgroundEnabled || !isInForeground) return@LaunchedEffect
        var lastUptimeMs = SystemClock.uptimeMillis()
        while (isActive) {
            withFrameNanos {
                val nowMs = SystemClock.uptimeMillis()
                val dt = ((nowMs - lastUptimeMs).coerceIn(0L, 100L)) / 1000f
                lastUptimeMs = nowMs
                if (dt > 0f) {
                    bgTimeSecondsState.floatValue = (bgTimeSecondsState.floatValue + dt) % 10000f
                }
            }
        }
    }

    return LivingPaletteState(
        playerAccent = playerAccent,
        dot1 = dot1,
        dot2 = dot2,
        dot3 = dot3,
        cardTint = cardTint,
        isTrackActive = isTrackActive,
        isDynamicBgEnabled = dynamicBackgroundEnabled,
        uiTimeSecondsState = uiTimeSecondsState,
        bgTimeSecondsState = bgTimeSecondsState,
        bgOpacityState = bgOpacityState,
        cloudColorStates = cloudStates
    )
}

internal fun computeCloudPalette(
    isTrackActive: Boolean,
    accentColorHex: String,
    accentColorsHex: List<String>
): List<Color> {
    if (!isTrackActive) {
        // Calm deep emerald + indigo clouds when idle/paused
        return listOf(
            Color(0xFF0F564B),
            Color(0xFF1D3261),
            Color(0xFF13425E),
            Color(0xFF2B2454)
        )
    }

    val primaryRaw = ContrastGuard.parseHexRgb(accentColorHex, 0xE4E4E7)
    val dotRaws = accentColorsHex.map { ContrastGuard.parseHexRgb(it, primaryRaw) }
    val raw0 = dotRaws.getOrElse(0) { primaryRaw }
    val raw1 = dotRaws.getOrElse(1) { raw0 }
    val raw2 = dotRaws.getOrElse(2) { raw1 }

    val hsls = listOf(raw0, raw1, raw2).map { ContrastGuard.rgbToHsl(it) }
    val isMonochrome = hsls.all { it.s < 0.11f }

    if (isMonochrome) {
        // Cool silver-graphite clouds + silver-pearl highlight for B&W / light minimal covers
        return listOf(
            Color(0xFF5D6472),
            Color(0xFF7C8494),
            Color(0xFF49505E),
            Color(0xFFD6DAE2)
        )
    }

    val hasLightAccent = hsls.any { it.l > 0.76f && it.s < 0.30f }
    val c1 = ContrastGuard.normalizeAmbientCloudRgb(raw0, targetMinL = 0.38f, targetMaxL = 0.54f).toComposeColor()
    val c2 = ContrastGuard.normalizeAmbientCloudRgb(raw1, targetMinL = 0.35f, targetMaxL = 0.52f).toComposeColor()
    val c3 = ContrastGuard.normalizeAmbientCloudRgb(raw2, targetMinL = 0.36f, targetMaxL = 0.55f).toComposeColor()
    val c4 = if (hasLightAccent) {
        Color(0xFFD6DAE2)
    } else {
        val h0 = ContrastGuard.rgbToHsl(raw0)
        ContrastGuard.hslToRgb(
            h = (h0.h + 18f) % 360f,
            s = h0.s.coerceIn(0.42f, 0.76f),
            l = 0.28f
        ).toComposeColor()
    }

    return listOf(c1, c2, c3, c4)
}

@Composable
fun LivingAuroraBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val opacity = palette.bgOpacityState.value.coerceIn(0f, 1f)
        val baseColor = lerp(Color(0xFF08090C), Color(0xFF06070A), opacity)
        drawRect(color = baseColor)

        if (opacity <= 0.005f) return@Canvas

        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val t = palette.bgTimeSecondsState.value
        val c1 = palette.cloudColorStates[0].value
        val c2 = palette.cloudColorStates[1].value
        val c3 = palette.cloudColorStates[2].value
        val c4 = palette.cloudColorStates[3].value

        val intensity = if (palette.isTrackActive) 1.0f else 0.78f

        // Wide Lissajous orbits (10-14s cycles, +/-0.28..0.34 sweep — clearly visible motion within 2-3s)
        val center1 = Offset(
            x = w * (0.28f + 0.30f * sin(t * 0.56f)),
            y = h * (0.22f + 0.26f * cos(t * 0.47f + 0.4f))
        )
        val center2 = Offset(
            x = w * (0.72f - 0.32f * sin(t * 0.51f + 0.7f)),
            y = h * (0.48f + 0.28f * cos(t * 0.44f + 2.8f))
        )
        val center3 = Offset(
            x = w * (0.48f + 0.33f * cos(t * 0.46f + 1.9f)),
            y = h * (0.78f - 0.27f * sin(t * 0.53f + 0.9f))
        )
        val center4 = Offset(
            x = w * (0.52f + 0.28f * sin(t * 0.60f + 3.5f)),
            y = h * (0.36f + 0.29f * cos(t * 0.55f + 1.4f))
        )

        drawSoftCloud(center1, w * 0.90f, c1, alpha = 0.40f * opacity * intensity, scaleX = 1.15f, scaleY = 0.90f)
        drawSoftCloud(center2, w * 0.84f, c2, alpha = 0.34f * opacity * intensity, scaleX = 0.92f, scaleY = 1.14f)
        drawSoftCloud(center3, w * 0.78f, c3, alpha = 0.28f * opacity * intensity, scaleX = 1.12f, scaleY = 0.92f)
        drawSoftCloud(center4, w * 0.68f, c4, alpha = 0.19f * opacity * intensity, scaleX = 1.00f, scaleY = 1.08f)
    }
}

private fun DrawScope.drawSoftCloud(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
    scaleX: Float,
    scaleY: Float
) {
    if (alpha <= 0.002f || radius <= 1f) return
    withTransform({
        scale(scaleX = scaleX, scaleY = scaleY, pivot = center)
    }) {
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to color.copy(alpha = alpha),
                    0.52f to color.copy(alpha = alpha * 0.40f),
                    1.00f to Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
}
