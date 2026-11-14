package com.example.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.media.ArtworkBlurHelper
import com.example.media.SmartColorExtractor
import com.example.model.NowPlayingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-JVM HSL Color Normalization & Contrast Guard.
 * Crucially preserves monochrome / B&W covers as pure silver-white instead of inventing colors.
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

private class FlowTextureHolder(
    val key: String,
    val shader: BitmapShader,
    val anchors: List<Color>,
    val isArtwork: Boolean
)

@Immutable
data class LivingPaletteState(
    val playerAccent: Color,
    val dot1: Color,
    val dot2: Color,
    val dot3: Color,
    val cardTint: Color,
    val phase: Float,
    val isTrackActive: Boolean,
    val isMotionActive: Boolean,
    internal val timeSecondsState: State<Float>,
    internal val mixState: State<Float>,
    internal val activeModeState: State<Float>,
    internal val motionFadeState: State<Float>,
    internal val prevShader: BitmapShader,
    internal val currShader: BitmapShader,
    internal val fallbackAnchors: List<Color>
)

private fun buildIdleBlurred64Texture(): Bitmap {
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(0xFF06080C.toInt(), 0xFF0D201D.toInt(), 0xFF101929.toInt(), 0xFF06080C.toInt()),
            floatArrayOf(0f, 0.35f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    return bmp
}

private fun buildTriadBlurred64Texture(colors: List<Int>): Bitmap {
    val size = 64
    val c0 = 0xFF000000.toInt() or (colors.getOrElse(0) { 0x1E293B } and 0xFFFFFF)
    val c1 = 0xFF000000.toInt() or (colors.getOrElse(1) { 0x0F766E } and 0xFFFFFF)
    val c2 = 0xFF000000.toInt() or (colors.getOrElse(2) { 0x06080C } and 0xFFFFFF)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(c0, c1, c2),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    return ArtworkBlurHelper.createBlurred64Texture(bmp, radius = 12).also { bmp.recycle() }
}

@Composable
fun rememberLivingPaletteState(
    liveState: NowPlayingData,
    artworkBitmap: Bitmap? = null,
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

    // Honest UI palette targets (PlayerAccent + 3 real cover dots + CardTint)
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

    val idleHolder = remember {
        val bmp = buildIdleBlurred64Texture()
        FlowTextureHolder(
            key = "idle",
            shader = BitmapShader(bmp, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
            anchors = listOf(Color(0xFF0A1618), Color(0xFF0F2622), Color(0xFF101B2D), Color(0xFF090D14)),
            isArtwork = false
        )
    }

    var prevHolder by remember { mutableStateOf(idleHolder) }
    var currHolder by remember { mutableStateOf(idleHolder) }
    val mixAnim = remember { Animatable(1f) }

    val artKey = remember(isTrackActive, artworkBitmap, liveState.title, liveState.accentColorHex) {
        when {
            !isTrackActive -> "idle"
            artworkBitmap != null && !artworkBitmap.isRecycled ->
                "bmp_${artworkBitmap.generationId}_${artworkBitmap.width}x${artworkBitmap.height}"
            else -> "triad_${liveState.accentColorHex}_${liveState.accentColorsHex.joinToString(",")}"
        }
    }

    LaunchedEffect(artKey) {
        if (currHolder.key == artKey) return@LaunchedEffect
        val newHolder = withContext(Dispatchers.Default) {
            if (!isTrackActive) {
                idleHolder
            } else if (artworkBitmap != null && !artworkBitmap.isRecycled) {
                val blurred64 = ArtworkBlurHelper.createBlurred64Texture(artworkBitmap, radius = 14)
                val pixels = IntArray(64 * 64)
                blurred64.getPixels(pixels, 0, 64, 0, 0, 64, 64)
                val anchors = SmartColorExtractor.extractFromPixels(pixels, 64, 64)
                    .radialAnchorRgbs
                    .map { it.toComposeColor() }
                FlowTextureHolder(
                    key = artKey,
                    shader = BitmapShader(blurred64, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
                    anchors = anchors,
                    isArtwork = true
                )
            } else {
                val fallbackCols = liveState.accentColorsHex.map { ContrastGuard.parseHexRgb(it, 0x1E293B) }
                val blurred64 = buildTriadBlurred64Texture(fallbackCols)
                val anchors = fallbackCols.map { it.toComposeColor() } + Color(0xFF0B111A)
                FlowTextureHolder(
                    key = artKey,
                    shader = BitmapShader(blurred64, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
                    anchors = anchors.take(4),
                    isArtwork = true
                )
            }
        }
        prevHolder = currHolder
        currHolder = newHolder
        mixAnim.snapTo(0f)
        mixAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing)
        )
    }

    val activeModeState = animateFloatAsState(
        targetValue = if (isTrackActive) 1f else 0f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "flow_active_mode"
    )
    val motionFadeState = animateFloatAsState(
        targetValue = if (isTrackActive) 1f else 0.52f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "flow_motion_fade"
    )

    // Continuous monotonic clock read directly inside Modifier.drawBehind (zero Skia cache freeze!)
    val timeSecondsState = remember { mutableFloatStateOf(1.5f) }
    LaunchedEffect(isMotionActive) {
        if (!isMotionActive) return@LaunchedEffect
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos != 0L) {
                    val dt = ((frameNanos - lastNanos).coerceIn(0L, 100_000_000L)) / 1_000_000_000f
                    timeSecondsState.floatValue = (timeSecondsState.floatValue + dt) % 10000f
                }
                lastNanos = frameNanos
            }
        }
    }

    val phase = (timeSecondsState.floatValue / 26f) % 1f

    return LivingPaletteState(
        playerAccent = playerAccent,
        dot1 = dot1,
        dot2 = dot2,
        dot3 = dot3,
        cardTint = cardTint,
        phase = phase,
        isTrackActive = isTrackActive,
        isMotionActive = isMotionActive,
        timeSecondsState = timeSecondsState,
        mixState = mixAnim.asState(),
        activeModeState = activeModeState,
        motionFadeState = motionFadeState,
        prevShader = prevHolder.shader,
        currShader = currHolder.shader,
        fallbackAnchors = currHolder.anchors
    )
}

@Composable
fun LivingAuroraBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AppleMusicFlowBackdrop(palette = palette, modifier = modifier)
    } else {
        CanvasFallbackFlowBackdrop(palette = palette, modifier = modifier)
    }
}

private const val APPLE_MUSIC_FLOW_AGSL = """
    uniform shader uTexPrev;
    uniform shader uTexCurr;
    uniform float2 uResolution;
    uniform float uTime;
    uniform float uMix;
    uniform float uActiveMode;
    uniform float uMotionFade;

    float ditherNoise(float2 coord) {
        return fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    }

    float2 warpCoords(float2 uv, float angle, float zoom, float phase, float2 offsetShift) {
        float2 p = uv - float2(0.5, 0.5);
        float c = cos(angle);
        float s = sin(angle);
        float2 rot = float2(p.x * c - p.y * s, p.x * s + p.y * c) / max(zoom, 0.25);
        rot += float2(0.5, 0.5) + offsetShift;
        rot.x += sin(uv.y * 3.0 + uTime * 0.40 + phase) * 0.14;
        rot.y += cos(uv.x * 2.5 - uTime * 0.32 + phase) * 0.14;
        // Textures are 64x64 BitmapShaders wrapped with TileMode.MIRROR -> scale to [0..64] texels!
        return rot * 64.0;
    }

    float3 samplePrev(float2 coordA, float2 coordB, float blendFactor) {
        float3 colA = float3(uTexPrev.eval(coordA).rgb);
        float3 colB = float3(uTexPrev.eval(coordB).rgb);
        return mix(colA, colB, blendFactor);
    }

    float3 sampleCurr(float2 coordA, float2 coordB, float blendFactor) {
        float3 colA = float3(uTexCurr.eval(coordA).rgb);
        float3 colB = float3(uTexCurr.eval(coordB).rgb);
        return mix(colA, colB, blendFactor);
    }

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / max(uResolution, float2(1.0, 1.0));
        float aspect = uResolution.x / max(uResolution.y, 1.0);
        float2 centeredUv = float2((uv.x - 0.5) * aspect + 0.5, uv.y);

        // Two-layer Domain-Warped counter-rotating flow
        float zoomA = 0.96 + 0.12 * sin(uTime * 0.18);
        float zoomB = 1.04 + 0.11 * cos(uTime * 0.15);
        float2 coordA = warpCoords(centeredUv, uTime * 0.14, zoomA, 0.0, float2(0.0, 0.0));
        float2 coordB = warpCoords(centeredUv, -uTime * 0.10 + 1.05, zoomB, 2.1, float2(0.35, 0.28));

        float waveMix = clamp(0.5 + 0.25 * sin(uTime * 0.25 + uv.x * 2.0 + uv.y * 1.4), 0.15, 0.85);
        float3 colPrev = samplePrev(coordA, coordB, waveMix);
        float3 colCurr = sampleCurr(coordA, coordB, waveMix);
        float3 col = mix(colPrev, colCurr, clamp(uMix, 0.0, 1.0));

        // Subtle chroma lift ONLY for already colorful pixels; preserve pure gray/B&W (sat == 0.0)
        float maxC = max(col.r, max(col.g, col.b));
        float minC = min(col.r, min(col.g, col.b));
        float chroma = maxC - minC;
        float lum0 = dot(col, float3(0.2126, 0.7152, 0.0722));
        if (chroma > 0.08) {
            col = mix(float3(lum0), col, 1.15);
            col = clamp(col, 0.0, 1.0);
        }

        // Non-linear OLED deep shadow push + drifting diagonal light/shadow ribbon
        col = pow(max(col, float3(0.0)), float3(1.45));
        float diagWave = 0.5 + 0.5 * sin(uv.x * 2.3 - uv.y * 2.1 + uTime * 0.22 + 0.55 * cos(uv.y * 1.6 - uTime * 0.14));
        float shadowBand = mix(0.22, 1.0, smoothstep(0.08, 0.92, diagWave));
        col *= shadowBand;

        // Soft-clamp max luminance to 0.36..0.42 (pearl silver on B&W, ~0.10 on Idle) for WCAG text contrast
        float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
        float targetMaxLum = mix(0.10, 0.40, uActiveMode);
        float mappedLum = targetMaxLum * (1.0 - exp(-lum * 2.5));
        float lumScale = (lum > 0.0005) ? (mappedLum / lum) : 0.0;
        col = col * lumScale * uMotionFade;

        // Deep OLED canvas floor + 1.2% dither noise against banding
        col = max(col, float3(0.0235, 0.0314, 0.0471));
        col += ditherNoise(fragCoord) * 0.012;
        col = clamp(col, 0.0, 1.0);

        return half4(half3(col), 1.0);
    }
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AppleMusicFlowBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    val shader = remember {
        runCatching { RuntimeShader(APPLE_MUSIC_FLOW_AGSL) }
            .onFailure { err ->
                Log.e("LivingThemeEngine", "AGSL shader compile failed, falling back to Canvas", err)
            }
            .getOrNull()
    }
    var runtimeFailed by remember { mutableStateOf(shader == null) }

    if (runtimeFailed || shader == null) {
        CanvasFallbackFlowBackdrop(palette = palette, modifier = modifier)
        return
    }

    // CRITICAL: Read animated state INSIDE drawBehind and create fresh ShaderBrush(shader) per draw
    // so Compose + Skia invalidate & redraw on every frame at 60/120 FPS!
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val success = runCatching {
                    val t = palette.timeSecondsState.value
                    val mixVal = palette.mixState.value
                    val activeMode = palette.activeModeState.value
                    val motionFade = palette.motionFadeState.value

                    shader.setInputShader("uTexPrev", palette.prevShader)
                    shader.setInputShader("uTexCurr", palette.currShader)
                    shader.setFloatUniform("uResolution", size.width, size.height)
                    shader.setFloatUniform("uTime", t)
                    shader.setFloatUniform("uMix", mixVal)
                    shader.setFloatUniform("uActiveMode", activeMode)
                    shader.setFloatUniform("uMotionFade", motionFade)

                    drawRect(brush = ShaderBrush(shader))
                }.onFailure { err ->
                    Log.e("LivingThemeEngine", "AGSL draw failed, falling back to Canvas", err)
                }.isSuccess

                if (!success) {
                    runtimeFailed = true
                }
            }
    )
}

@Composable
private fun CanvasFallbackFlowBackdrop(
    palette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(color = Color(0xFF06070A))

                val w = size.width
                val h = size.height
                val t = palette.timeSecondsState.value
                val activeAlpha = if (palette.isTrackActive) 0.44f else 0.16f
                val fade = palette.motionFadeState.value
                val anchors = palette.fallbackAnchors

                val c0 = anchors.getOrElse(0) { Color(0xFF0D201D) }
                val c1 = anchors.getOrElse(1) { Color(0xFF101929) }
                val c2 = anchors.getOrElse(2) { c0 }
                val c3 = anchors.getOrElse(3) { c1 }

                val centers = listOf(
                    Offset(w * (0.30f + 0.26f * cos(t * 0.24f)), h * (0.20f + 0.15f * sin(t * 0.21f))),
                    Offset(w * (0.72f + 0.24f * cos(t * 0.19f + 2.1f)), h * (0.45f + 0.18f * sin(t * 0.25f + 1.1f))),
                    Offset(w * (0.42f + 0.28f * cos(-t * 0.22f + 3.9f)), h * (0.78f + 0.15f * sin(t * 0.18f + 2.9f))),
                    Offset(w * (0.58f + 0.20f * sin(t * 0.17f + 0.8f)), h * (0.34f + 0.22f * cos(t * 0.20f + 2.4f)))
                )
                val colors = listOf(c0, c1, c2, c3)
                val radii = listOf(w * 0.92f, w * 0.88f, w * 0.90f, w * 0.78f)

                for (i in 0 until 4) {
                    val base = colors[i]
                    val a = activeAlpha * fade * (if (i == 3) 0.78f else 1f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                base.copy(alpha = a),
                                base.copy(alpha = a * 0.32f),
                                Color.Transparent
                            ),
                            center = centers[i],
                            radius = radii[i]
                        ),
                        radius = radii[i],
                        center = centers[i],
                        blendMode = BlendMode.Screen
                    )
                }
            }
    )
}
