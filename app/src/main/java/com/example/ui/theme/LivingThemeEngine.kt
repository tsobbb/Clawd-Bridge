package com.example.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.NowPlayingData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-JVM HSL Color Normalization & Contrast Guard.
 * Ensures every album artwork color maintains legibility and balanced dark-mode aesthetics:
 * - AmbientBlobColor: Saturation 0.35..0.65, Lightness 0.25..0.42
 * - PlayerAccent: Saturation 0.50..0.80, Lightness 0.72..0.84 (contrast >= 4.5:1 on #12141A)
 * - CardTint: Saturation 0.15..0.25, Lightness 0.09..0.13
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

        if (d < 1e-5f) {
            return Hsl(0f, 0f, l)
        }

        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        var h = when (maxC) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h *= 60f
        return Hsl(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
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
        val s = if (hsl.s < 0.05f) 0.14f else hsl.s.coerceIn(0.35f, 0.65f)
        val l = hsl.l.coerceIn(0.25f, 0.42f)
        return hslToRgb(hsl.h, s, l)
    }

    fun normalizePlayerAccentRgb(rgb: Int): Int {
        val hsl = rgbToHsl(rgb)
        val s = if (hsl.s < 0.05f) 0.10f else hsl.s.coerceIn(0.50f, 0.80f)
        val l = hsl.l.coerceIn(0.72f, 0.84f)
        return hslToRgb(hsl.h, s, l)
    }

    fun normalizeCardTintRgb(rgb: Int): Int {
        val hsl = rgbToHsl(rgb)
        val s = if (hsl.s < 0.05f) 0.06f else hsl.s.coerceIn(0.15f, 0.25f)
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
    val blobPrimary: Color,
    val blobSecondary: Color,
    val blobTertiary: Color,
    val playerAccent: Color,
    val playerAccentSecondary: Color,
    val playerAccentTertiary: Color,
    val cardTint: Color,
    val phase: Float, // 0f..1f across 26s calm cycle
    val meshIntensity: Float, // 1.0f playing, 0.50f idle/paused
    val isTrackActive: Boolean,
    val isMotionActive: Boolean
)

@Composable
fun rememberLivingPaletteState(
    liveState: NowPlayingData,
    dynamicBackgroundEnabled: Boolean = true
): LivingPaletteState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()

    val isInForeground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val reduceMotion = remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }

    val isTrackActive = liveState.hasMedia && liveState.isPlaying
    val isMotionActive = dynamicBackgroundEnabled && isInForeground && !reduceMotion

    val targets = remember(liveState.accentColorHex, liveState.accentColorsHex, isTrackActive) {
        if (!isTrackActive) {
            listOf(
                IdleBlobPrimary,
                IdleBlobSecondary,
                IdleBlobTertiary,
                MintCta,
                Color(0xFF93C5FD),
                Color(0xFFC4B5FD),
                Color(0xFF14181F)
            )
        } else {
            val fallbackRgb = 0x38BDF8
            val primaryRaw = ContrastGuard.parseHexRgb(liveState.accentColorHex, fallbackRgb)
            val parsedRaws = liveState.accentColorsHex.map { ContrastGuard.parseHexRgb(it, primaryRaw) }
            val r0 = parsedRaws.getOrElse(0) { primaryRaw }
            val r1 = parsedRaws.getOrElse(1) { primaryRaw }
            val r2 = parsedRaws.getOrElse(2) { r0 }

            listOf(
                ContrastGuard.normalizeAmbientBlobRgb(r0).toComposeColor(),
                ContrastGuard.normalizeAmbientBlobRgb(r1).toComposeColor(),
                ContrastGuard.normalizeAmbientBlobRgb(r2).toComposeColor(),
                ContrastGuard.normalizePlayerAccentRgb(r0).toComposeColor(),
                ContrastGuard.normalizePlayerAccentRgb(r1).toComposeColor(),
                ContrastGuard.normalizePlayerAccentRgb(r2).toComposeColor(),
                ContrastGuard.normalizeCardTintRgb(r0).toComposeColor()
            )
        }
    }

    val crossfadeSpec = remember { tween<Color>(durationMillis = 1400, easing = FastOutSlowInEasing) }

    val blob1 by animateColorAsState(targets[0], crossfadeSpec, label = "blob_1")
    val blob2 by animateColorAsState(targets[1], crossfadeSpec, label = "blob_2")
    val blob3 by animateColorAsState(targets[2], crossfadeSpec, label = "blob_3")
    val pAccent1 by animateColorAsState(targets[3], crossfadeSpec, label = "player_accent_1")
    val pAccent2 by animateColorAsState(targets[4], crossfadeSpec, label = "player_accent_2")
    val pAccent3 by animateColorAsState(targets[5], crossfadeSpec, label = "player_accent_3")
    val cardTint by animateColorAsState(targets[6], crossfadeSpec, label = "card_tint")

    val meshIntensity by animateFloatAsState(
        targetValue = if (isTrackActive) 1.0f else 0.50f,
        animationSpec = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
        label = "mesh_intensity"
    )

    // 26-second calm cycle; pauses cleanly in-place when toggled off, Reduce Motion is on, or backgrounded
    var phase by remember { mutableFloatStateOf(0.12f) }
    LaunchedEffect(isMotionActive) {
        if (!isMotionActive) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos != 0L) {
                    val dtSec = ((frameNanos - lastNanos).coerceIn(0L, 100_000_000L)) / 1_000_000_000f
                    phase = (phase + dtSec / 26.0f) % 1.0f
                }
                lastNanos = frameNanos
            }
        }
    }

    return LivingPaletteState(
        blobPrimary = blob1,
        blobSecondary = blob2,
        blobTertiary = blob3,
        playerAccent = pAccent1,
        playerAccentSecondary = pAccent2,
        playerAccentTertiary = pAccent3,
        cardTint = cardTint,
        phase = phase,
        meshIntensity = meshIntensity,
        isTrackActive = isTrackActive,
        isMotionActive = isMotionActive
    )
}

@Composable
fun LivingAuroraBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AgslAmbientMeshBackdrop(palette = palette, modifier = modifier)
    } else {
        CanvasFallbackMeshBackdrop(palette = palette, modifier = modifier)
    }
}

private const val AMBIENT_MESH_AGSL = """
    uniform float2 uResolution;
    uniform float uPhase;
    uniform float uIntensity;
    uniform float3 uBaseColor;
    uniform float3 uColor1;
    uniform float3 uColor2;
    uniform float3 uColor3;

    float ditherNoise(float2 coord) {
        return fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    }

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / max(uResolution, float2(1.0, 1.0));
        float aspect = uResolution.x / max(uResolution.y, 1.0);
        float angle = uPhase * 6.2831853;

        // 4 smooth Lissajous orbital centers
        float2 p1 = float2(0.32 + 0.24 * cos(angle), 0.22 + 0.14 * sin(angle * 1.1));
        float2 p2 = float2(0.72 + 0.22 * cos(angle * 1.2 + 2.1), 0.48 + 0.18 * sin(angle * 0.9 + 1.0));
        float2 p3 = float2(0.45 + 0.26 * cos(-angle * 0.85 + 4.1), 0.80 + 0.14 * sin(angle * 1.15 + 3.4));
        float2 p4 = float2(0.55 + 0.18 * sin(angle * 0.7 + 0.5), 0.36 + 0.20 * cos(angle * 1.05 + 2.7));

        float2 scale = float2(aspect, 1.0);
        float d1 = length((uv - p1) * scale);
        float d2 = length((uv - p2) * scale);
        float d3 = length((uv - p3) * scale);
        float d4 = length((uv - p4) * scale);

        float w1 = exp(-d1 * d1 * 2.1) * (0.72 * uIntensity);
        float w2 = exp(-d2 * d2 * 2.3) * (0.64 * uIntensity);
        float w3 = exp(-d3 * d3 * 2.2) * (0.60 * uIntensity);
        float w4 = exp(-d4 * d4 * 2.8) * (0.36 * uIntensity);

        float3 col = uBaseColor;
        col = mix(col, uColor1, clamp(w1, 0.0, 0.75));
        col = mix(col, uColor2, clamp(w2, 0.0, 0.70));
        col = mix(col, uColor3, clamp(w3, 0.0, 0.65));
        col += uColor1 * w4 * 0.35;

        // ~1.5% micro-noise dither eliminates 8-bit OLED banding on dark backgrounds
        col += ditherNoise(fragCoord) * 0.015;
        col = clamp(col, 0.0, 1.0);

        return half4(half3(col), 1.0);
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AgslAmbientMeshBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    val shader = remember { RuntimeShader(AMBIENT_MESH_AGSL) }
    val brush = remember(shader) { ShaderBrush(shader) }

    Canvas(modifier = modifier.fillMaxSize()) {
        shader.setFloatUniform("uResolution", size.width, size.height)
        shader.setFloatUniform("uPhase", palette.phase)
        shader.setFloatUniform("uIntensity", palette.meshIntensity)
        shader.setFloatUniform("uBaseColor", BaseCanvas.red, BaseCanvas.green, BaseCanvas.blue)
        shader.setFloatUniform("uColor1", palette.blobPrimary.red, palette.blobPrimary.green, palette.blobPrimary.blue)
        shader.setFloatUniform("uColor2", palette.blobSecondary.red, palette.blobSecondary.green, palette.blobSecondary.blue)
        shader.setFloatUniform("uColor3", palette.blobTertiary.red, palette.blobTertiary.green, palette.blobTertiary.blue)

        drawRect(brush = brush)
    }
}

@Composable
private fun CanvasFallbackMeshBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = BaseCanvas)

        val w = size.width
        val h = size.height
        val angle = palette.phase * 2.0 * PI
        val intensity = 0.42f * palette.meshIntensity

        val c1 = Offset(
            x = w * (0.32f + 0.24f * cos(angle).toFloat()),
            y = h * (0.22f + 0.14f * sin(angle * 1.1).toFloat())
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.blobPrimary.copy(alpha = intensity),
                    palette.blobPrimary.copy(alpha = intensity * 0.35f),
                    Color.Transparent
                ),
                center = c1,
                radius = w * 0.90f
            ),
            radius = w * 0.90f,
            center = c1
        )

        val c2 = Offset(
            x = w * (0.72f + 0.22f * cos(angle * 1.2 + 2.1).toFloat()),
            y = h * (0.48f + 0.18f * sin(angle * 0.9 + 1.0).toFloat())
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.blobSecondary.copy(alpha = intensity * 0.92f),
                    palette.blobSecondary.copy(alpha = intensity * 0.30f),
                    Color.Transparent
                ),
                center = c2,
                radius = w * 0.85f
            ),
            radius = w * 0.85f,
            center = c2
        )

        val c3 = Offset(
            x = w * (0.45f + 0.26f * cos(-angle * 0.85 + 4.1).toFloat()),
            y = h * (0.80f + 0.14f * sin(angle * 1.15 + 3.4).toFloat())
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    palette.blobTertiary.copy(alpha = intensity * 0.88f),
                    palette.blobTertiary.copy(alpha = intensity * 0.28f),
                    Color.Transparent
                ),
                center = c3,
                radius = w * 0.84f
            ),
            radius = w * 0.84f,
            center = c3
        )
    }
}
