package com.example.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-JVM honest color & monochrome extractor from 32x32 cover pixels.
 * Preserves black, white, and gray pixels instead of discarding them like Palette does.
 */
object SmartColorExtractor {

    data class Hsl(val h: Float, val s: Float, val l: Float)

    data class SmartPaletteResult(
        val isMonochrome: Boolean,
        val playerAccentRgb: Int,
        val paletteTriadRgb: List<Int>,
        val radialAnchorRgbs: List<Int>
    )

    val MONOCHROME_DOTS_RGB = listOf(0x52525B, 0xA1A1AA, 0xF4F4F5)
    const val MONOCHROME_ACCENT_RGB = 0xF4F4F5

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
        val sat = s.coerceIn(0f, 1f)
        val light = l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * light - 1f)) * sat
        val x = c * (1f - abs((normH / 60f) % 2f - 1f))
        val m = light - c / 2f

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

    fun extractFromPixels(pixels: IntArray, width: Int = 32, height: Int = 32): SmartPaletteResult {
        if (pixels.isEmpty() || width <= 0 || height <= 0) {
            return SmartPaletteResult(
                isMonochrome = true,
                playerAccentRgb = MONOCHROME_ACCENT_RGB,
                paletteTriadRgb = MONOCHROME_DOTS_RGB,
                radialAnchorRgbs = listOf(0x18181B, 0x3F3F46, 0x27272A, 0x52525B)
            )
        }

        val binCount = 16
        val binWeight = FloatArray(binCount)
        val binSin = FloatArray(binCount)
        val binCos = FloatArray(binCount)
        val binSat = FloatArray(binCount)
        val binLight = FloatArray(binCount)
        val binCountPixels = IntArray(binCount)

        // Quadrant accumulators for Android < 13 Canvas fallback
        val qR = LongArray(4)
        val qG = LongArray(4)
        val qB = LongArray(4)
        val qCount = IntArray(4)

        var totalOpaque = 0
        var colorfulCount = 0
        var darkPixelCount = 0
        var lightPixelCount = 0

        val halfW = (width / 2).coerceAtLeast(1)
        val halfH = (height / 2).coerceAtLeast(1)

        for (idx in pixels.indices) {
            val argb = pixels[idx]
            val alpha = (argb ushr 24) and 0xFF
            if (alpha < 128) continue

            val rgb = argb and 0xFFFFFF
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF

            val x = idx % width
            val y = idx / width
            val qIdx = (if (y >= halfH) 2 else 0) + (if (x >= halfW) 1 else 0)
            qR[qIdx] += r
            qG[qIdx] += g
            qB[qIdx] += b
            qCount[qIdx]++

            totalOpaque++
            val hsl = rgbToHsl(rgb)
            if (hsl.l < 0.24f) darkPixelCount++
            if (hsl.l > 0.72f) lightPixelCount++

            if (hsl.s > 0.16f && hsl.l in 0.10f..0.90f) {
                colorfulCount++
                val bin = ((hsl.h / (360f / binCount)).toInt()).coerceIn(0, binCount - 1)
                val weight = (hsl.s * hsl.s * 4.0f + 0.20f) * (1.0f - abs(hsl.l - 0.50f) * 0.45f)
                val rad = hsl.h * (PI.toFloat() / 180f)
                binWeight[bin] += weight
                binSin[bin] += sin(rad) * weight
                binCos[bin] += cos(rad) * weight
                binSat[bin] += hsl.s * weight
                binLight[bin] += hsl.l * weight
                binCountPixels[bin]++
            }
        }

        val anchors = (0 until 4).map { q ->
            if (qCount[q] > 0) {
                val r = (qR[q] / qCount[q]).toInt().coerceIn(0, 255)
                val g = (qG[q] / qCount[q]).toInt().coerceIn(0, 255)
                val b = (qB[q] / qCount[q]).toInt().coerceIn(0, 255)
                (r shl 16) or (g shl 8) or b
            } else {
                0x18181B
            }
        }

        if (totalOpaque == 0) {
            return SmartPaletteResult(
                isMonochrome = true,
                playerAccentRgb = MONOCHROME_ACCENT_RGB,
                paletteTriadRgb = MONOCHROME_DOTS_RGB,
                radialAnchorRgbs = anchors
            )
        }

        val colorfulRatio = colorfulCount.toFloat() / totalOpaque.toFloat()
        val rankedBins = (0 until binCount)
            .filter { binWeight[it] > 0f && binCountPixels[it] > 0 }
            .sortedByDescending { binWeight[it] }

        // Section 3.1: Monochrome / B&W cover (< 8% colorful pixels) -> pure graphite/silver/lunar white
        if (colorfulRatio < 0.08f || rankedBins.isEmpty()) {
            return SmartPaletteResult(
                isMonochrome = true,
                playerAccentRgb = MONOCHROME_ACCENT_RGB,
                paletteTriadRgb = MONOCHROME_DOTS_RGB,
                radialAnchorRgbs = anchors
            )
        }

        // Section 3.2: Colorful cover -> keep EXACT hue of dominant color cluster, adjust L to 0.72..0.82
        fun clusterHue(bin: Int): Float {
            val deg = atan2(binSin[bin], binCos[bin]) * (180f / PI.toFloat())
            return ((deg % 360f) + 360f) % 360f
        }

        fun clusterSat(bin: Int): Float {
            return (binSat[bin] / binWeight[bin]).coerceIn(0.50f, 0.90f)
        }

        fun clusterLight(bin: Int): Float {
            return (binLight[bin] / binWeight[bin]).coerceIn(0.24f, 0.80f)
        }

        val topBin = rankedBins.first()
        val topHue = clusterHue(topBin)
        val topSat = clusterSat(topBin)
        val topWeight = binWeight[topBin]

        // Preserve exact Hue of strongest cluster; normalize Lightness strictly to 0.72..0.82 for >= 4.5:1 contrast
        val playerAccentRgb = hslToRgb(
            h = topHue,
            s = topSat.coerceIn(0.55f, 0.88f),
            l = clusterLight(topBin).coerceIn(0.72f, 0.82f)
        )

        fun hueBinDistance(a: Int, b: Int): Int {
            val d = abs(a - b)
            return min(d, binCount - d)
        }

        val distinctClusters = mutableListOf(topBin)
        for (candidate in rankedBins.drop(1)) {
            if (binWeight[candidate] >= topWeight * 0.14f &&
                distinctClusters.all { hueBinDistance(it, candidate) >= 2 }
            ) {
                distinctClusters.add(candidate)
                if (distinctClusters.size == 3) break
            }
        }

        val dots = when {
            distinctClusters.size >= 3 -> {
                distinctClusters.map { bin ->
                    hslToRgb(
                        h = clusterHue(bin),
                        s = clusterSat(bin),
                        l = clusterLight(bin).coerceIn(0.32f, 0.80f)
                    )
                }
            }
            distinctClusters.size == 2 -> {
                val b0 = distinctClusters[0]
                val b1 = distinctClusters[1]
                val c0 = hslToRgb(clusterHue(b0), clusterSat(b0), clusterLight(b0).coerceIn(0.30f, 0.72f))
                val c1 = hslToRgb(clusterHue(b1), clusterSat(b1), clusterLight(b1).coerceIn(0.45f, 0.80f))
                val c2 = if (darkPixelCount > totalOpaque * 0.30f) {
                    hslToRgb(clusterHue(b0), clusterSat(b0).coerceAtMost(0.70f), 0.24f)
                } else {
                    hslToRgb(clusterHue(b0), clusterSat(b0), 0.76f)
                }
                listOf(c2, c0, c1)
            }
            else -> {
                // Single-hue cover (e.g., black + scarlet red or white + green): dark -> true mid -> bright tint
                val darkTone = hslToRgb(topHue, (topSat * 0.85f).coerceIn(0.45f, 0.80f), 0.26f)
                val midTone = hslToRgb(topHue, topSat, clusterLight(topBin).coerceIn(0.46f, 0.60f))
                val brightTone = if (lightPixelCount > totalOpaque * 0.35f) {
                    0xE4E4E7
                } else {
                    hslToRgb(topHue, topSat.coerceIn(0.55f, 0.85f), 0.76f)
                }
                listOf(darkTone, midTone, brightTone)
            }
        }

        return SmartPaletteResult(
            isMonochrome = false,
            playerAccentRgb = playerAccentRgb,
            paletteTriadRgb = dots,
            radialAnchorRgbs = anchors
        )
    }
}

/**
 * Generates ultra-fast 64x64 Box/Stack-blurred textures preserving true cover colors (black, white, chroma)
 * for smooth BitmapShader(TileMode.MIRROR) GPU flow rendering.
 */
object ArtworkBlurHelper {

    fun createBlurred64Texture(source: Bitmap, radius: Int = 14): Bitmap {
        val size = 64
        val sw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        ) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }

        val scaled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(sw, Rect(0, 0, sw.width, sw.height), Rect(0, 0, size, size), paint)
        if (sw !== source && !sw.isRecycled) sw.recycle()

        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)

        // Two fast separable BoxBlur passes (~Gaussian StackBlur approximation) on 64x64 (<0.4ms on CPU)
        val temp = IntArray(size * size)
        val r = radius.coerceIn(4, 24)
        boxBlurHorizontal(pixels, temp, size, size, r)
        boxBlurVertical(temp, pixels, size, size, r)
        boxBlurHorizontal(pixels, temp, size, size, r / 2 + 2)
        boxBlurVertical(temp, pixels, size, size, r / 2 + 2)

        scaled.setPixels(pixels, 0, size, 0, 0, size, size)
        return scaled
    }

    fun extractPixels32(source: Bitmap): IntArray {
        val size = 32
        val sw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            source.config == Bitmap.Config.HARDWARE
        ) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        val scaled = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(sw, Rect(0, 0, sw.width, sw.height), Rect(0, 0, size, size), paint)
        if (sw !== source && !sw.isRecycled) sw.recycle()

        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        scaled.recycle()
        return pixels
    }

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val div = radius * 2 + 1
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (kx in -radius..radius) {
                    val sx = (x + kx).coerceIn(0, w - 1)
                    val p = src[rowOffset + sx]
                    rSum += (p shr 16) and 0xFF
                    gSum += (p shr 8) and 0xFF
                    bSum += p and 0xFF
                }
                dst[rowOffset + x] = (0xFF shl 24) or
                    ((rSum / div) shl 16) or
                    ((gSum / div) shl 8) or
                    (bSum / div)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val div = radius * 2 + 1
        for (x in 0 until w) {
            for (y in 0 until h) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (ky in -radius..radius) {
                    val sy = (y + ky).coerceIn(0, h - 1)
                    val p = src[sy * w + x]
                    rSum += (p shr 16) and 0xFF
                    gSum += (p shr 8) and 0xFF
                    bSum += p and 0xFF
                }
                dst[y * w + x] = (0xFF shl 24) or
                    ((rSum / div) shl 16) or
                    ((gSum / div) shl 8) or
                    (bSum / div)
            }
        }
    }
}
