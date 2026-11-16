package com.example.ui.theme

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
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
import com.mikepenz.hypnoticcanvas.shaderBackground
import com.mikepenz.hypnoticcanvas.shaders.MeshGradient
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
        if (hsl.s < 0.08f) {
            return hslToRgb(0f, 0f, hsl.l.coerceIn(targetMinL, targetMaxL))
        }
        val isWarmCream = hsl.h in 20f..55f && hsl.s < 0.34f && hsl.l > 0.65f
        if (isWarmCream) {
            return hslToRgb(0f, 0f, 0.50f)
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
    backgroundStyle: BackgroundStyle = BackgroundStyle.MESH_SILK,
    suppressBackgroundMotion: Boolean = false
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
            val fallbackRgb = 0xE5E5E5
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

    // 3. Compact 144x144 (or pre-blurred on API < 31) ImageBitmap strictly isolated inside Apple Music GPU blur
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

    // 5. Background orbit/shader clock — frozen ONLY when dynamicBackgroundEnabled is OFF, PURE_BLACK, or suppressed
    val bgTimeSecondsState = remember { mutableFloatStateOf(2.4f) }
    val shouldAnimateBg = dynamicBackgroundEnabled &&
        isInForeground &&
        !suppressBackgroundMotion &&
        backgroundStyle != BackgroundStyle.PURE_BLACK
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

    val primaryRaw = ContrastGuard.parseHexRgb(accentColorHex, 0xE5E5E5)
    val dotRaws = accentColorsHex.map { ContrastGuard.parseHexRgb(it, primaryRaw) }
    val raw0 = dotRaws.getOrElse(0) { primaryRaw }
    val raw1 = dotRaws.getOrElse(1) { raw0 }
    val raw2 = dotRaws.getOrElse(2) { raw1 }

    val hsls = listOf(raw0, raw1, raw2).map { ContrastGuard.rgbToHsl(it) }
    val isMonochrome = hsls.all { it.s < 0.08f }

    if (isMonochrome) {
        return listOf(
            Color(0xFF4A4A4A),
            Color(0xFF737373),
            Color(0xFF363636),
            Color(0xFFD4D4D4)
        )
    }

    val hasLightAccent = hsls.any { it.l > 0.76f && it.s < 0.30f }
    val c1 = ContrastGuard.normalizeAmbientCloudRgb(raw0, targetMinL = 0.36f, targetMaxL = 0.52f).toComposeColor()
    val c2 = ContrastGuard.normalizeAmbientCloudRgb(raw1, targetMinL = 0.32f, targetMaxL = 0.48f).toComposeColor()
    val c3 = ContrastGuard.normalizeAmbientCloudRgb(raw2, targetMinL = 0.34f, targetMaxL = 0.54f).toComposeColor()
    val c4 = if (hasLightAccent) {
        Color(0xFFD4D4D4)
    } else {
        val h0 = ContrastGuard.rgbToHsl(raw0)
        ContrastGuard.hslToRgb(
            h = (h0.h + 6f) % 360f,
            s = h0.s.coerceIn(0.42f, 0.78f),
            l = 0.24f
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
    val primaryRaw = ContrastGuard.parseHexRgb(accentColorHex, 0xE5E5E5)
    val parsed = sourceHex.map { ContrastGuard.parseHexRgb(it, primaryRaw) }
    val isMono = parsed.all { ContrastGuard.rgbToHsl(it).s < 0.08f }

    if (isMono) {
        return SmartColorExtractor.MONOCHROME_EXTENDED_RGB.map { it.toComposeColor() }
    }

    val normalized = parsed.take(6).mapIndexed { index, raw ->
        when (index) {
            3 -> ContrastGuard.normalizeAmbientCloudRgb(raw, targetMinL = 0.20f, targetMaxL = 0.32f).toComposeColor()
            4 -> ContrastGuard.normalizeAmbientCloudRgb(raw, targetMinL = 0.42f, targetMaxL = 0.58f).toComposeColor()
            else -> ContrastGuard.normalizeAmbientCloudRgb(raw, targetMinL = 0.32f, targetMaxL = 0.54f).toComposeColor()
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
        return listOf(
            Color(0xFF0F4A41), Color(0xFF163557), Color(0xFF1E244E),
            Color(0xFF103849), Color(0xFF1A4B4E), Color(0xFF262A59),
            Color(0xFF0A1D29), Color(0xFF142740), Color(0xFF1B1A38)
        )
    }
    if (gridColorsHex.size >= 9) {
        return gridColorsHex.take(9).map { hex ->
            val raw = ContrastGuard.parseHexRgb(hex, 0x181818)
            ContrastGuard.normalizeAmbientCloudRgb(raw, targetMinL = 0.26f, targetMaxL = 0.52f).toComposeColor()
        }
    }
    return List(9) { i -> extendedFallback[i % extendedFallback.size] }
}

/**
 * Unified 4-style living background engine over obsidian base #060709:
 *  1) MESH_SILK (HypnoticCanvas MeshGradient at 1/5 resolution + fallback)
 *  2) APPLE_MUSIC (Dual blurred 144px artwork layers + ColorMatrix saturation 1.35x + adaptive scrim)
 *  3) TONAL (Calm 2-3 tone atmospheric gradient built from cover hues)
 *  4) PURE_BLACK (#060709 pure dark base)
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
                    .background(Color(0xFF060709))
            )
        }
        BackgroundStyle.APPLE_MUSIC -> {
            AppleMusicCoverBackdrop(palette = palette, modifier = modifier)
        }
        BackgroundStyle.TONAL -> {
            TonalAtmosphericBackdrop(palette = palette, modifier = modifier)
        }
        BackgroundStyle.MESH_SILK -> {
            HypnoticMeshSilkBackdrop(palette = palette, modifier = modifier)
        }
    }
}

@Composable
private fun HypnoticMeshSilkBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    val ext = palette.extendedColorStates
    val c0 = ext[0].value
    val c1 = ext[1].value
    val c2 = ext[2].value
    val c3 = ext[3].value
    val c4 = ext[4].value
    val intensity = if (palette.isTrackActive) 1.0f else 0.82f
    val meshSpeed = if (palette.isDynamicBgEnabled) 0.42f else 0f

    val darkShadow = remember(c3) { lerp(Color(0xFF060709), c3, 0.45f) }
    val primaryGlow = remember(c0, intensity) { lerp(Color(0xFF060709), c0, 0.78f * intensity) }
    val secondaryGlow = remember(c1, intensity) { lerp(Color(0xFF060709), c1, 0.72f * intensity) }
    val highlightGlow = remember(c2, c4, intensity) {
        lerp(Color(0xFF060709), lerp(c2, c4, 0.35f), 0.68f * intensity)
    }

    val meshShader = remember(darkShadow, primaryGlow, secondaryGlow, highlightGlow) {
        MeshGradient(
            colors = arrayOf(
                primaryGlow,
                darkShadow,
                secondaryGlow,
                highlightGlow
            ),
            scale = 1.15f
        )
    }

    val veilBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0x40060709),
                Color(0x24060709),
                Color(0x66060709)
            )
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF060709))
    ) {
        val lowW = (maxWidth * 0.2f).coerceAtLeast(72.dp)
        val lowH = (maxHeight * 0.2f).coerceAtLeast(120.dp)

        // Render MeshGradient on a 1/5 resolution surface scaled smoothly 5x on GPU for effortless 120 FPS
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .requiredSize(width = lowW, height = lowH)
                .graphicsLayer {
                    scaleX = 5.05f
                    scaleY = 5.05f
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .shaderBackground(
                    shader = meshShader,
                    speed = meshSpeed,
                    fallback = {
                        Brush.linearGradient(
                            colors = listOf(primaryGlow, darkShadow, secondaryGlow, highlightGlow)
                        )
                    }
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(veilBrush)
        )
    }
}

@Composable
private fun AppleMusicCoverBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    val cover = palette.appleMusicCoverBitmap
    val scrimAlpha = if (palette.isBrightCover) 0.58f else 0.46f
    val saturatedFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.35f) })
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF060709))
    ) {
        if (cover != null) {
            val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier.blur(90.dp)
            } else {
                Modifier
            }
            val tState = palette.bgTimeSecondsState

            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = saturatedFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val t = tState.value
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
                colorFilter = saturatedFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.55f)
                    .graphicsLayer {
                        val t = tState.value
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
                    .background(Color(0xFF060709).copy(alpha = scrimAlpha))
            )
        } else {
            // Seamless ambient cloud fallback when idle/without decoded cover
            Canvas(modifier = Modifier.fillMaxSize()) {
                val t = palette.bgTimeSecondsState.value
                val clouds = palette.cloudColorStates
                val intensity = if (palette.isTrackActive) 0.90f else 0.72f
                drawSoftCloud(
                    Offset(size.width * (0.3f + 0.2f * sin(t * 0.4f)), size.height * 0.3f),
                    size.width * 0.9f,
                    clouds[0].value,
                    0.42f * intensity,
                    1.1f,
                    0.95f
                )
                drawSoftCloud(
                    Offset(size.width * (0.7f - 0.2f * cos(t * 0.35f)), size.height * 0.65f),
                    size.width * 0.85f,
                    clouds[1].value,
                    0.36f * intensity,
                    0.95f,
                    1.1f
                )
            }
        }
    }
}

@Composable
private fun TonalAtmosphericBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Color(0xFF060709))
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val t = palette.bgTimeSecondsState.value
        val clouds = palette.cloudColorStates
        val intensity = if (palette.isTrackActive) 1.0f else 0.78f

        val topTone = lerp(Color(0xFF060709), clouds[0].value, 0.56f * intensity)
        val midTone = lerp(Color(0xFF060709), clouds[1].value, 0.36f * intensity)
        val lowAccent = lerp(Color(0xFF060709), clouds[2].value, 0.26f * intensity)

        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to topTone,
                    0.46f to midTone,
                    0.82f to lowAccent,
                    1.0f to Color(0xFF060709)
                )
            )
        )

        // Subtle atmospheric bloom
        drawSoftCloud(
            center = Offset(
                w * (0.5f + 0.14f * sin(t * 0.25f)),
                h * (0.28f + 0.08f * cos(t * 0.21f))
            ),
            radius = max(w, h) * 0.65f,
            color = clouds[0].value,
            alpha = 0.26f * intensity,
            scaleX = 1.25f,
            scaleY = 0.90f
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

