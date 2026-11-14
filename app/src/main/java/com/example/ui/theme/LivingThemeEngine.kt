package com.example.ui.theme

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.media.ArtworkBlurHelper
import com.example.media.SmartColorExtractor
import com.example.model.NowPlayingData
import com.example.state.BackgroundStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-JVM HSL Color Normalization & Contrast Guard.
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
    val backgroundStyle: BackgroundStyle,
    val isBrightCover: Boolean,
    val appleMusicCoverBitmap: ImageBitmap?,
    internal val uiTimeSecondsState: State<Float>,
    internal val bgTimeSecondsState: State<Float>,
    internal val cloudColorStates: List<State<Color>>,
    internal val extendedColorStates: List<State<Color>>,
    internal val gridColorStates: List<State<Color>>
)

@Composable
fun rememberLivingPaletteState(
    liveState: NowPlayingData,
    artworkBitmap: Bitmap? = null,
    dynamicBackgroundEnabled: Boolean = true,
    backgroundStyle: BackgroundStyle = BackgroundStyle.LIQUID_SILK
): LivingPaletteState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()

    val isInForeground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val isTrackActive = liveState.hasMedia && liveState.isPlaying

    // 1. UI accents (PlayerAccent + 3 real cover dots + CardTint)
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

    // 2. Extended 6-color palette + 3x3 grid color targets
    val targetClouds = remember(liveState.accentColorHex, liveState.accentColorsHex, isTrackActive) {
        computeCloudPalette(
            isTrackActive = isTrackActive,
            accentColorHex = liveState.accentColorHex,
            accentColorsHex = liveState.accentColorsHex
        )
    }

    val targetExtended6 = remember(
        liveState.accentColorHex,
        liveState.accentColorsHex,
        liveState.extendedColorsHex,
        isTrackActive
    ) {
        computeExtendedSixPalette(
            isTrackActive = isTrackActive,
            accentColorHex = liveState.accentColorHex,
            accentColorsHex = liveState.accentColorsHex,
            extendedColorsHex = liveState.extendedColorsHex
        )
    }

    val targetGrid9 = remember(
        liveState.gridColorsHex,
        targetExtended6,
        isTrackActive
    ) {
        computeGridNinePalette(
            isTrackActive = isTrackActive,
            gridColorsHex = liveState.gridColorsHex,
            extendedFallback = targetExtended6
        )
    }

    val isBrightCover = remember(liveState.extendedColorsHex, liveState.accentColorsHex, isTrackActive) {
        if (!isTrackActive) false
        else {
            val samples = (liveState.extendedColorsHex.ifEmpty { liveState.accentColorsHex })
                .map { ContrastGuard.rgbToHsl(ContrastGuard.parseHexRgb(it, 0x202028)).l }
            samples.isNotEmpty() && samples.average() > 0.56
        }
    }

    val cloudStates = remember { List(4) { i -> mutableStateOf(targetClouds[i]) } }
    val extendedStates = remember { List(6) { i -> mutableStateOf(targetExtended6[i]) } }
    val gridStates = remember { List(9) { i -> mutableStateOf(targetGrid9[i]) } }

    // Smooth 1200ms palette crossfade (runs on SystemClock.uptimeMillis(), works even when motion toggle OFF!)
    LaunchedEffect(targetClouds, targetExtended6, targetGrid9) {
        val startClouds = cloudStates.map { it.value }
        val startExt = extendedStates.map { it.value }
        val startGrid = gridStates.map { it.value }
        if (startClouds == targetClouds && startExt == targetExtended6 && startGrid == targetGrid9) {
            return@LaunchedEffect
        }
        val startMs = SystemClock.uptimeMillis()
        val durationMs = 1200f
        while (isActive) {
            var done = false
            withFrameNanos {
                val elapsed = (SystemClock.uptimeMillis() - startMs).coerceAtLeast(0L).toFloat()
                val rawT = (elapsed / durationMs).coerceIn(0f, 1f)
                val eased = FastOutSlowInEasing.transform(rawT)
                for (i in 0 until 4) cloudStates[i].value = lerp(startClouds[i], targetClouds[i], eased)
                for (i in 0 until 6) extendedStates[i].value = lerp(startExt[i], targetExtended6[i], eased)
                for (i in 0 until 9) gridStates[i].value = lerp(startGrid[i], targetGrid9[i], eased)
                if (rawT >= 1f) done = true
            }
            if (done) break
        }
        for (i in 0 until 4) cloudStates[i].value = targetClouds[i]
        for (i in 0 until 6) extendedStates[i].value = targetExtended6[i]
        for (i in 0 until 9) gridStates[i].value = targetGrid9[i]
    }

    // 3. Compact 144x144 (or pre-blurred on API < 31) ImageBitmap for ultra-fast 60/120fps Apple Music blur
    val appleMusicCover by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = artworkBitmap,
        key2 = isTrackActive
    ) {
        val bmp = artworkBitmap?.takeIf { !it.isRecycled }
        value = if (!isTrackActive || bmp == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    val prepared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ArtworkBlurHelper.createDownscaledBlurSource(bmp, targetSize = 144)
                    } else {
                        ArtworkBlurHelper.createBlurred64Texture(bmp, radius = 16)
                    }
                    prepared.asImageBitmap()
                }.getOrNull()
            }
        }
    }

    // 4. Independent foreground UI clock (MiniEqualizerBars, WaveLoadingDots, status pulses)
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

    // 5. Background orbit/shader clock — frozen ONLY when dynamicBackgroundEnabled is OFF or style == PURE_BLACK
    val bgTimeSecondsState = remember { mutableFloatStateOf(2.4f) }
    val shouldAnimateBg = dynamicBackgroundEnabled && isInForeground && backgroundStyle != BackgroundStyle.PURE_BLACK
    LaunchedEffect(shouldAnimateBg) {
        if (!shouldAnimateBg) return@LaunchedEffect
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
        backgroundStyle = backgroundStyle,
        isBrightCover = isBrightCover,
        appleMusicCoverBitmap = appleMusicCover,
        uiTimeSecondsState = uiTimeSecondsState,
        bgTimeSecondsState = bgTimeSecondsState,
        cloudColorStates = cloudStates,
        extendedColorStates = extendedStates,
        gridColorStates = gridStates
    )
}

internal fun computeCloudPalette(
    isTrackActive: Boolean,
    accentColorHex: String,
    accentColorsHex: List<String>
): List<Color> {
    if (!isTrackActive) {
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

internal fun computeExtendedSixPalette(
    isTrackActive: Boolean,
    accentColorHex: String,
    accentColorsHex: List<String>,
    extendedColorsHex: List<String>
): List<Color> {
    if (!isTrackActive) {
        return listOf(
            Color(0xFF0E4D43),
            Color(0xFF192B56),
            Color(0xFF113F5C),
            Color(0xFF28204E),
            Color(0xFF0A2A2D),
            Color(0xFF2B6B62)
        )
    }

    val sourceHex = extendedColorsHex.ifEmpty { accentColorsHex }
    val primaryRaw = ContrastGuard.parseHexRgb(accentColorHex, 0xE4E4E7)
    val parsed = sourceHex.map { ContrastGuard.parseHexRgb(it, primaryRaw) }
    val isMono = parsed.all { ContrastGuard.rgbToHsl(it).s < 0.11f }

    if (isMono) {
        return SmartColorExtractor.MONOCHROME_EXTENDED_RGB.map { it.toComposeColor() }
    }

    val normalized = parsed.take(6).mapIndexed { index, raw ->
        when (index) {
            3 -> ContrastGuard.normalizeAmbientCloudRgb(raw, targetMinL = 0.22f, targetMaxL = 0.34f).toComposeColor()
            4 -> ContrastGuard.normalizeAmbientCloudRgb(raw, targetMinL = 0.44f, targetMaxL = 0.62f).toComposeColor()
            else -> ContrastGuard.normalizeAmbientCloudRgb(raw, targetMinL = 0.34f, targetMaxL = 0.56f).toComposeColor()
        }
    }.toMutableList()

    val fallbackClouds = computeCloudPalette(true, accentColorHex, accentColorsHex)
    var idx = 0
    while (normalized.size < 6) {
        normalized.add(fallbackClouds[idx % fallbackClouds.size])
        idx++
    }
    return normalized
}

internal fun computeGridNinePalette(
    isTrackActive: Boolean,
    gridColorsHex: List<String>,
    extendedFallback: List<Color>
): List<Color> {
    if (!isTrackActive) {
        val base = listOf(
            Color(0xFF0F4A41), Color(0xFF163557), Color(0xFF1E244E),
            Color(0xFF103849), Color(0xFF1A4B4E), Color(0xFF262A59),
            Color(0xFF0A1D29), Color(0xFF142740), Color(0xFF1B1A38)
        )
        return base
    }
    if (gridColorsHex.size >= 9) {
        return gridColorsHex.take(9).map { hex ->
            val raw = ContrastGuard.parseHexRgb(hex, 0x181B24)
            ContrastGuard.normalizeAmbientCloudRgb(raw, targetMinL = 0.28f, targetMaxL = 0.54f).toComposeColor()
        }
    }
    return List(9) { i -> extendedFallback[i % extendedFallback.size] }
}

/**
 * Unified 5-style living background engine.
 * Switch between:
 *  1) LIQUID_SILK (AGSL 2D Value Noise + Domain Warping over 6 colors + 35% #07080B valleys + dithering)
 *  2) APPLE_MUSIC (Dual blurred 144px artwork layers drifting in opposite phases + adaptive scrim)
 *  3) MESH_GRID_3X3 (9-zone spatial color mesh with smooth breathing control points)
 *  4) AURORA (7 overlapping orbs in Offscreen layer with BlendMode.Screen)
 *  5) PURE_BLACK (Pure OLED #000000 black)
 *
 * When dynamicBackgroundEnabled == false ("Статичный градиент"), colors remain visible & reactive
 * while animation time stays frozen at 0 GPU frame draw cost.
 */
@Composable
fun LivingAuroraBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    when (palette.backgroundStyle) {
        BackgroundStyle.PURE_BLACK -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
        BackgroundStyle.APPLE_MUSIC -> {
            AppleMusicCoverBackdrop(palette = palette, modifier = modifier)
        }
        BackgroundStyle.MESH_GRID_3X3 -> {
            Grid3x3MeshBackdrop(palette = palette, modifier = modifier)
        }
        BackgroundStyle.AURORA -> {
            AuroraScreenOrbsBackdrop(palette = palette, modifier = modifier)
        }
        BackgroundStyle.LIQUID_SILK -> {
            LiquidSilkNoiseBackdrop(palette = palette, modifier = modifier)
        }
    }
}

@Composable
private fun LiquidSilkNoiseBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    val shaderHolder = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(LIQUID_SILK_AGSL) }.getOrNull()
        } else null
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Color(0xFF07080B))
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val t = palette.bgTimeSecondsState.value
        val colors = palette.extendedColorStates.map { it.value }
        val intensity = if (palette.isTrackActive) 1.0f else 0.78f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shaderHolder != null) {
            val rendered = runCatching {
                drawLiquidSilkAgsl(
                    shader = shaderHolder,
                    width = w,
                    height = h,
                    timeSec = t,
                    colors = colors,
                    intensity = intensity
                )
            }.isSuccess
            if (rendered) return@Canvas
        }

        // Smooth Canvas fallback for API < 33 or unsupported shader hardware
        drawSilkCanvasFallback(
            t = t,
            colors = colors,
            intensity = intensity
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawLiquidSilkAgsl(
    shader: RuntimeShader,
    width: Float,
    height: Float,
    timeSec: Float,
    colors: List<Color>,
    intensity: Float
) {
    shader.setFloatUniform("uResolution", width, height)
    shader.setFloatUniform("uTime", timeSec)
    shader.setFloatUniform("uIntensity", intensity)
    for (i in 0 until 6) {
        val c = colors[i]
        shader.setFloatUniform("uColor$i", c.red, c.green, c.blue)
    }
    drawRect(brush = ShaderBrush(shader))
}

private fun DrawScope.drawSilkCanvasFallback(
    t: Float,
    colors: List<Color>,
    intensity: Float
) {
    val w = size.width
    val h = size.height
    val coords = arrayOf(
        Offset(w * (0.24f + 0.22f * sin(t * 0.42f)), h * (0.20f + 0.18f * cos(t * 0.35f))),
        Offset(w * (0.76f - 0.24f * cos(t * 0.38f)), h * (0.34f + 0.22f * sin(t * 0.31f))),
        Offset(w * (0.38f + 0.26f * cos(t * 0.34f + 1.4f)), h * (0.68f - 0.20f * sin(t * 0.40f))),
        Offset(w * (0.72f - 0.22f * sin(t * 0.46f + 0.8f)), h * (0.78f - 0.18f * cos(t * 0.33f))),
        Offset(w * (0.50f + 0.18f * sin(t * 0.29f + 2.2f)), h * (0.48f + 0.20f * cos(t * 0.36f))),
        Offset(w * (0.18f + 0.16f * cos(t * 0.44f + 3.0f)), h * (0.46f - 0.16f * sin(t * 0.37f)))
    )
    val alphas = floatArrayOf(0.42f, 0.38f, 0.34f, 0.28f, 0.26f, 0.24f)
    for (i in 0 until 6) {
        drawSoftCloud(
            center = coords[i],
            radius = max(w, h) * (0.52f + (i % 3) * 0.08f),
            color = colors[i],
            alpha = alphas[i] * intensity,
            scaleX = 1.15f,
            scaleY = 0.88f
        )
    }
}

@Composable
private fun AppleMusicCoverBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    val cover = palette.appleMusicCoverBitmap
    val scrimAlpha = if (palette.isBrightCover) 0.60f else 0.50f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07080B))
    ) {
        if (cover != null) {
            val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.blur(90.dp)
            } else {
                Modifier
            }
            val t = palette.bgTimeSecondsState.value

            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val driftX = size.width * 0.08f * sin(t * 0.28f)
                        val driftY = size.height * 0.08f * cos(t * 0.23f)
                        scaleX = 2.45f
                        scaleY = 2.45f
                        translationX = driftX
                        translationY = driftY
                        rotationZ = t * 4.5f
                    }
                    .then(blurModifier)
            )

            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.55f)
                    .graphicsLayer {
                        val driftX = -size.width * 0.10f * cos(t * 0.24f + 1.2f)
                        val driftY = size.height * 0.09f * sin(t * 0.26f + 0.7f)
                        scaleX = 2.25f
                        scaleY = 2.25f
                        translationX = driftX
                        translationY = driftY
                        rotationZ = -t * 3.8f + 35f
                    }
                    .then(blurModifier)
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF07080B).copy(alpha = scrimAlpha))
            )
        } else {
            // Seamless ambient cloud fallback when idle/without decoded cover
            Canvas(modifier = Modifier.fillMaxSize()) {
                val t = palette.bgTimeSecondsState.value
                val clouds = palette.cloudColorStates.map { it.value }
                val intensity = if (palette.isTrackActive) 0.90f else 0.72f
                drawSoftCloud(
                    Offset(size.width * (0.3f + 0.2f * sin(t * 0.4f)), size.height * 0.3f),
                    size.width * 0.9f,
                    clouds[0],
                    0.42f * intensity,
                    1.1f,
                    0.95f
                )
                drawSoftCloud(
                    Offset(size.width * (0.7f - 0.2f * cos(t * 0.35f)), size.height * 0.65f),
                    size.width * 0.85f,
                    clouds[1],
                    0.36f * intensity,
                    0.95f,
                    1.1f
                )
            }
        }
    }
}

@Composable
private fun Grid3x3MeshBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    val shaderHolder = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(GRID_3X3_AGSL) }.getOrNull()
        } else null
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Color(0xFF07080B))
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val t = palette.bgTimeSecondsState.value
        val grid = palette.gridColorStates.map { it.value }
        val intensity = if (palette.isTrackActive) 1.0f else 0.80f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shaderHolder != null) {
            val rendered = runCatching {
                shaderHolder.setFloatUniform("uResolution", w, h)
                shaderHolder.setFloatUniform("uTime", t)
                shaderHolder.setFloatUniform("uIntensity", intensity)
                for (i in 0 until 9) {
                    val c = grid[i]
                    shaderHolder.setFloatUniform("uG$i", c.red, c.green, c.blue)
                }
                drawRect(brush = ShaderBrush(shaderHolder))
            }.isSuccess
            if (rendered) return@Canvas
        }

        // Smooth 9-node radial mesh fallback
        val anchorsX = floatArrayOf(0.16f, 0.50f, 0.84f)
        val anchorsY = floatArrayOf(0.16f, 0.50f, 0.84f)
        for (gy in 0 until 3) {
            for (gx in 0 until 3) {
                val idx = gy * 3 + gx
                val isCenter = gx == 1 && gy == 1
                val swayX = if (isCenter) 0.14f * sin(t * 0.55f) else 0.08f * sin(t * 0.42f + idx * 0.9f)
                val swayY = if (isCenter) 0.12f * cos(t * 0.48f) else 0.08f * cos(t * 0.39f + idx * 1.1f)
                val center = Offset(
                    x = w * (anchorsX[gx] + swayX),
                    y = h * (anchorsY[gy] + swayY)
                )
                drawSoftCloud(
                    center = center,
                    radius = max(w, h) * 0.46f,
                    color = grid[idx],
                    alpha = (if (isCenter) 0.52f else 0.40f) * intensity,
                    scaleX = 1.08f,
                    scaleY = 1.08f
                )
            }
        }
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0x5507080B),
                    Color(0x2207080B),
                    Color(0x6607080B)
                )
            )
        )
    }
}

@Composable
private fun AuroraScreenOrbsBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF06070B))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val t = palette.bgTimeSecondsState.value
            val ext = palette.extendedColorStates.map { it.value }
            val intensity = if (palette.isTrackActive) 1.0f else 0.78f

            val orbColors = listOf(
                ext[0], ext[1], ext[2], ext[4], ext[5], ext[0], ext[2]
            )
            val orbScales = floatArrayOf(0.72f, 0.64f, 0.68f, 0.52f, 0.56f, 0.44f, 0.48f)
            val orbAlphas = floatArrayOf(0.46f, 0.42f, 0.38f, 0.32f, 0.34f, 0.28f, 0.26f)

            for (i in 0 until 7) {
                val speedA = 0.34f + i * 0.055f
                val speedB = 0.29f + i * 0.048f
                val phase = i * 1.15f
                val cx = w * (0.5f + 0.36f * sin(t * speedA + phase))
                val cy = h * (0.5f + 0.34f * cos(t * speedB + phase * 1.3f))
                val radius = max(w, h) * orbScales[i]
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to orbColors[i].copy(alpha = orbAlphas[i] * intensity),
                            0.48f to orbColors[i].copy(alpha = orbAlphas[i] * 0.38f * intensity),
                            1.0f to Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = radius
                    ),
                    radius = radius,
                    center = Offset(cx, cy),
                    blendMode = BlendMode.Screen
                )
            }
        }

        // Subtle shadow veil ensuring text & glass card contrast stay crisp under Screen blend
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x5206070B),
                            Color(0x2806070B),
                            Color(0x6606070B)
                        )
                    )
                )
        )
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

// Pure procedural AGSL without ANY `shader` function arguments (safe on Android 13 / MIUI)
private const val LIQUID_SILK_AGSL = """
uniform float2 uResolution;
uniform float uTime;
uniform float uIntensity;
uniform float3 uColor0;
uniform float3 uColor1;
uniform float3 uColor2;
uniform float3 uColor3;
uniform float3 uColor4;
uniform float3 uColor5;

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    float2 shift = float2(17.3, 9.2);
    for (int i = 0; i < 3; i++) {
        v += a * vnoise(p);
        p = p * 1.92 + shift;
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0, 1.0));
    float2 uv = (fragCoord - 0.5 * res) / min(res.x, res.y);
    float t = uTime * 0.16;

    // 2-stage Domain Warping for silky organic flow
    float2 q = float2(
        fbm(uv * 1.35 + float2(t * 0.85, -t * 0.55)),
        fbm(uv * 1.35 + float2(-t * 0.60, t * 0.75) + float2(5.2, 1.3))
    );
    float2 r = float2(
        fbm(uv * 1.65 + 1.4 * q + float2(1.7, 9.2) + t * 0.35),
        fbm(uv * 1.65 + 1.4 * q + float2(8.3, 2.8) - t * 0.28)
    );

    float flow = fbm(uv * 1.15 + 1.6 * r);
    float w0 = smoothstep(0.18, 0.72, sin(flow * 4.2 + r.x * 2.1) * 0.5 + 0.5);
    float w1 = smoothstep(0.22, 0.78, cos(r.y * 3.8 - t * 1.4) * 0.5 + 0.5);
    float w2 = smoothstep(0.28, 0.82, fbm(uv * 2.1 - r * 1.3 + t * 0.4));
    float w3 = smoothstep(0.35, 0.88, q.x);

    float3 col = mix(uColor0, uColor1, w0);
    col = mix(col, uColor2, w1 * 0.78);
    col = mix(col, uColor4, w2 * 0.62);
    col = mix(col, uColor5, pow(w3, 1.5) * 0.55);
    col = mix(uColor3, col, smoothstep(0.08, 0.68, flow));

    // ~35% deep obsidian shadow valleys (#07080B) so contrast & depth stay dramatic
    float3 shadowBase = float3(0.027, 0.031, 0.043);
    float valley = smoothstep(0.24, 0.68, flow * 0.72 + q.y * 0.28);
    float vignette = smoothstep(1.35, 0.15, length(uv));
    float glowMask = clamp(valley * (0.58 + 0.42 * vignette) * uIntensity, 0.0, 0.76);
    float3 finalRgb = mix(shadowBase, col, glowMask);

    // 1.2% dithering to remove 8-bit gradient banding
    float dither = (hash21(fragCoord + fract(uTime) * 60.0) - 0.5) * 0.012;
    finalRgb = clamp(finalRgb + dither, 0.0, 1.0);
    return half4(finalRgb, 1.0);
}
"""

private const val GRID_3X3_AGSL = """
uniform float2 uResolution;
uniform float uTime;
uniform float uIntensity;
uniform float3 uG0;
uniform float3 uG1;
uniform float3 uG2;
uniform float3 uG3;
uniform float3 uG4;
uniform float3 uG5;
uniform float3 uG6;
uniform float3 uG7;
uniform float3 uG8;

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

half4 main(float2 fragCoord) {
    float2 res = max(uResolution, float2(1.0, 1.0));
    float2 uv = clamp(fragCoord / res, 0.0, 1.0);
    float t = uTime * 0.45;

    // Gentle sinusoidal breathing on interior control coordinates
    float2 warp = float2(
        sin(uv.y * 3.14159 + t) * 0.11 * sin(uv.x * 3.14159),
        cos(uv.x * 3.14159 - t * 0.82) * 0.11 * sin(uv.y * 3.14159)
    );
    float2 p = clamp(uv + warp, 0.0, 1.0) * 2.0;

    // Smooth hermite weights along 3x3 cell lattice [0..2]
    float tx = clamp(p.x, 0.0, 1.0);
    float sx = smoothstep(0.0, 1.0, tx);
    float tx2 = clamp(p.x - 1.0, 0.0, 1.0);
    float sx2 = smoothstep(0.0, 1.0, tx2);

    float ty = clamp(p.y, 0.0, 1.0);
    float sy = smoothstep(0.0, 1.0, ty);
    float ty2 = clamp(p.y - 1.0, 0.0, 1.0);
    float sy2 = smoothstep(0.0, 1.0, ty2);

    float3 row0 = (p.x < 1.0) ? mix(uG0, uG1, sx) : mix(uG1, uG2, sx2);
    float3 row1 = (p.x < 1.0) ? mix(uG3, uG4, sx) : mix(uG4, uG5, sx2);
    float3 row2 = (p.x < 1.0) ? mix(uG6, uG7, sx) : mix(uG7, uG8, sx2);

    float3 gridCol = (p.y < 1.0) ? mix(row0, row1, sy) : mix(row1, row2, sy2);

    float3 baseDark = float3(0.027, 0.031, 0.043);
    float distCenter = length(uv - float2(0.5, 0.46));
    float mask = clamp((0.86 - distCenter * 0.46) * uIntensity, 0.0, 0.80);
    float3 finalRgb = mix(baseDark, gridCol, mask);

    float dither = (hash21(fragCoord) - 0.5) * 0.01;
    finalRgb = clamp(finalRgb + dither, 0.0, 1.0);
    return half4(finalRgb, 1.0);
}
"""
