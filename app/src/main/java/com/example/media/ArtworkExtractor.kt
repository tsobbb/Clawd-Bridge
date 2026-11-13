package com.example.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.min

data class ExtractedArtwork(
    val thumbnail: Bitmap,
    val jpegBytes: ByteArray,
    val accentColorInt: Int,
    val accentColorHex: String
)

object ArtworkExtractor {

    private const val ARTWORK_SIZE_PX = 160
    private const val JPEG_QUALITY = 84
    private const val DEFAULT_ACCENT_INT = 0xFF00E5FF.toInt()
    private const val DEFAULT_ACCENT_HEX = "#00E5FF"

    fun extract(metadata: MediaMetadata?): ExtractedArtwork? {
        if (metadata == null) return null
        val rawBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null

        if (rawBitmap.isRecycled || rawBitmap.width <= 0 || rawBitmap.height <= 0) {
            return null
        }

        val thumbnail = createSquareThumbnail(rawBitmap, ARTWORK_SIZE_PX)
        val jpegBytes = compressToJpeg(thumbnail)
        val accentInt = extractVibrantAccentColor(thumbnail)
        val accentHex = toHexString(accentInt)

        return ExtractedArtwork(
            thumbnail = thumbnail,
            jpegBytes = jpegBytes,
            accentColorInt = accentInt,
            accentColorHex = accentHex
        )
    }

    fun createSimulatorArtwork(): ExtractedArtwork {
        val size = ARTWORK_SIZE_PX
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFFFFAF7B.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // Concentric vinyl ring accent
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.argb(110, 255, 255, 255)
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.34f, ringPaint)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.14f, ringPaint)

        val bytes = compressToJpeg(bmp)
        val accentInt = 0xFFFF6D7A.toInt()
        return ExtractedArtwork(
            thumbnail = bmp,
            jpegBytes = bytes,
            accentColorInt = accentInt,
            accentColorHex = toHexString(accentInt)
        )
    }

    internal fun createSquareThumbnail(source: Bitmap, targetSize: Int): Bitmap {
        val minSide = min(source.width, source.height)
        val xOffset = (source.width - minSide) / 2
        val yOffset = (source.height - minSide) / 2

        val cropped = if (xOffset == 0 && yOffset == 0 && source.width == source.height) {
            source
        } else {
            Bitmap.createBitmap(source, xOffset, yOffset, minSide, minSide)
        }

        val scaled = if (cropped.width == targetSize && cropped.height == targetSize) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        }
        if (cropped !== source && cropped !== scaled) {
            cropped.recycle()
        }
        return scaled
    }

    private fun compressToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    /**
     * Computes a vibrant dominant accent color suitable for a dark UI console.
     * Samples a 24x24 grid across the thumbnail, weights saturated colors higher,
     * and normalizes brightness so dark covers don't produce invisible black accents.
     */
    internal fun extractVibrantAccentColor(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return DEFAULT_ACCENT_INT

        val stepX = (w / 20).coerceAtLeast(1)
        val stepY = (h / 20).coerceAtLeast(1)

        // Bucket by 12-degree hue bins (30 buckets) to find the dominant vibrant hue
        val binWeight = FloatArray(30)
        val binRed = FloatArray(30)
        val binGreen = FloatArray(30)
        val binBlue = FloatArray(30)
        val hsv = FloatArray(3)

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= 128) {
                    Color.colorToHSV(pixel, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]
                    // Ignore near-black and near-white washed-out pixels
                    if (value > 0.16f && !(sat < 0.12f && value > 0.88f)) {
                        val weight = (sat * sat * 3.0f + 0.15f) * (0.4f + value * 0.6f)
                        val bin = ((hsv[0] / 12f).toInt()).coerceIn(0, 29)
                        binWeight[bin] += weight
                        binRed[bin] += Color.red(pixel) * weight
                        binGreen[bin] += Color.green(pixel) * weight
                        binBlue[bin] += Color.blue(pixel) * weight
                    }
                }
                x += stepX
            }
            y += stepY
        }

        var bestBin = -1
        var maxWeight = 0f
        for (i in binWeight.indices) {
            if (binWeight[i] > maxWeight) {
                maxWeight = binWeight[i]
                bestBin = i
            }
        }

        if (bestBin < 0 || maxWeight <= 0f) {
            return DEFAULT_ACCENT_INT
        }

        val r = (binRed[bestBin] / maxWeight).toInt().coerceIn(0, 255)
        val g = (binGreen[bestBin] / maxWeight).toInt().coerceIn(0, 255)
        val b = (binBlue[bestBin] / maxWeight).toInt().coerceIn(0, 255)

        Color.RGBToHSV(r, g, b, hsv)
        // Ensure crisp visibility on dark #0C0D11 background
        hsv[1] = hsv[1].coerceAtLeast(0.45f)
        hsv[2] = hsv[2].coerceIn(0.68f, 0.96f)
        return Color.HSVToColor(hsv)
    }

    fun toHexString(colorInt: Int): String {
        return String.format(Locale.US, "#%06X", 0xFFFFFF and colorInt)
    }
}
