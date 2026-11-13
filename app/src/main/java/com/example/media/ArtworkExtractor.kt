package com.example.media

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

data class ExtractedArtwork(
    val thumbnail: Bitmap,
    val jpegBytes: ByteArray,
    val accentColorInt: Int,
    val accentColorHex: String,
    val accentColorInts: List<Int>,
    val accentColorsHex: List<String>
)

object ArtworkExtractor {

    private const val ARTWORK_SIZE_PX = 160
    private const val MAX_DECODE_DIMENSION = 512
    private const val MAX_REMOTE_IMAGE_BYTES = 5 * 1024 * 1024
    private const val JPEG_QUALITY = 84
    private const val DEFAULT_ACCENT_INT = 0xFF00E5FF.toInt()
    private val DEFAULT_PALETTE_INTS = listOf(0xFF00E5FF.toInt(), 0xFF3B82F6.toInt(), 0xFF7C3AED.toInt())

    /**
     * Synchronously extracts artwork from inline MediaMetadata bitmaps.
     */
    fun extract(metadata: MediaMetadata?): ExtractedArtwork? {
        val rawBitmap = extractInlineBitmap(metadata) ?: return null
        return fromBitmap(rawBitmap)
    }

    /**
     * Returns any inline Bitmap attached directly inside MediaMetadata.
     */
    fun extractInlineBitmap(metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        val candidate = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        return candidate?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    }

    /**
     * Returns the first non-blank artwork URI from MediaMetadata (content://, file://, or http(s)://).
     */
    fun extractArtworkUri(metadata: MediaMetadata?): String? {
        if (metadata == null) return null
        val keys = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        )
        for (key in keys) {
            val value = metadata.getString(key)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    /**
     * Extracts album artwork Bitmap from an active MediaStyle / Transport Notification.
     * Android SystemUI uses this exact icon for the lockscreen & shade player card.
     */
    fun extractBitmapFromNotification(context: Context, notification: Notification?): Bitmap? {
        if (notification == null) return null

        // 1. Check Notification.getLargeIcon() (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val largeIcon = notification.getLargeIcon()
            val fromLargeIcon = iconToBitmap(context, largeIcon)
            if (isValidCoverBitmap(fromLargeIcon)) return fromLargeIcon
        }

        // 2. Check legacy & BigPicture / LargeIcon extras
        val extras: Bundle = notification.extras ?: return null
        val bitmapOrIconKeys = listOf(
            Notification.EXTRA_PICTURE,
            Notification.EXTRA_LARGE_ICON_BIG,
            Notification.EXTRA_LARGE_ICON
        )
        for (key in bitmapOrIconKeys) {
            val bmp = getExtraBitmap(context, extras, key)
            if (isValidCoverBitmap(bmp)) return bmp
        }

        return null
    }

    @Suppress("DEPRECATION")
    private fun getExtraBitmap(context: Context, extras: Bundle, key: String): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(key, Bitmap::class.java)
                    ?: iconToBitmap(context, extras.getParcelable(key, Icon::class.java))
            } else {
                when (val raw = extras.getParcelable<android.os.Parcelable>(key)) {
                    is Bitmap -> raw
                    is Icon -> iconToBitmap(context, raw)
                    else -> null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun iconToBitmap(context: Context, icon: Icon?): Bitmap? {
        if (icon == null) return null
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            drawableToSoftwareBitmap(drawable)
        } catch (_: Throwable) {
            null
        }
    }

    private fun drawableToSoftwareBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable) {
            val bmp = drawable.bitmap
            if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                return ensureSoftwareArgb8888(bmp)
            }
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: ARTWORK_SIZE_PX
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: ARTWORK_SIZE_PX
        val targetW = width.coerceAtMost(MAX_DECODE_DIMENSION)
        val targetH = height.coerceAtMost(MAX_DECODE_DIMENSION)
        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun isValidCoverBitmap(bitmap: Bitmap?): Boolean {
        return bitmap != null && !bitmap.isRecycled && bitmap.width >= 32 && bitmap.height >= 32
    }

    /**
     * Loads and extracts artwork asynchronously from content://, file://, android.resource://, or http(s):// URIs.
     * Must be called off the main thread.
     */
    fun loadFromUri(context: Context, uriString: String): ExtractedArtwork? {
        val bytes = readUriBytes(context, uriString) ?: return null
        if (bytes.isEmpty()) return null
        val decoded = decodeSampledBitmap(bytes, MAX_DECODE_DIMENSION) ?: return null
        return fromBitmap(decoded)
    }

    private fun readUriBytes(context: Context, uriString: String): ByteArray? {
        val lower = uriString.lowercase(Locale.US)
        return if (lower.startsWith("http://") || lower.startsWith("https://")) {
            downloadHttpBytes(uriString)
        } else {
            try {
                val uri = Uri.parse(uriString)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    readLimitedBytes(stream, MAX_REMOTE_IMAGE_BYTES)
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun downloadHttpBytes(urlString: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ClawdBridge/2.0")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream?.use { stream ->
                readLimitedBytes(stream, MAX_REMOTE_IMAGE_BYTES)
            }
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun readLimitedBytes(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var inSampleSize = 1
        while (bounds.outWidth / inSampleSize > maxDim * 2 || bounds.outHeight / inSampleSize > maxDim * 2) {
            inSampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            this.inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }

    /**
     * Processes any source Bitmap into a normalized 160x160 JPEG thumbnail
     * plus a harmonious 3-color dark-UI gradient accent palette.
     */
    fun fromBitmap(rawBitmap: Bitmap): ExtractedArtwork? {
        if (rawBitmap.isRecycled || rawBitmap.width <= 0 || rawBitmap.height <= 0) {
            return null
        }

        val thumbnail = createSquareThumbnail(rawBitmap, ARTWORK_SIZE_PX)
        val jpegBytes = compressToJpeg(thumbnail)
        val paletteInts = extractAccentPalette(thumbnail)
        val primaryInt = paletteInts.firstOrNull() ?: DEFAULT_ACCENT_INT
        val paletteHex = paletteInts.map { toHexString(it) }
        val primaryHex = paletteHex.firstOrNull() ?: toHexString(primaryInt)

        return ExtractedArtwork(
            thumbnail = thumbnail,
            jpegBytes = jpegBytes,
            accentColorInt = primaryInt,
            accentColorHex = primaryHex,
            accentColorInts = paletteInts,
            accentColorsHex = paletteHex
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
        val paletteInts = listOf(0xFFFF6D7A.toInt(), 0xFFFFAF7B.toInt(), 0xFF8B5CF6.toInt())
        val paletteHex = paletteInts.map { toHexString(it) }
        return ExtractedArtwork(
            thumbnail = bmp,
            jpegBytes = bytes,
            accentColorInt = paletteInts[0],
            accentColorHex = paletteHex[0],
            accentColorInts = paletteInts,
            accentColorsHex = paletteHex
        )
    }

    /**
     * Safely center-crops and scales any source Bitmap (including API 26+ HARDWARE bitmaps)
     * into a mutable software ARGB_8888 square thumbnail so pixel sampling never throws.
     */
    internal fun createSquareThumbnail(source: Bitmap, targetSize: Int): Bitmap {
        val softwareSource = ensureSoftwareArgb8888(source)
        val minSide = min(softwareSource.width, softwareSource.height)
        val xOffset = (softwareSource.width - minSide) / 2
        val yOffset = (softwareSource.height - minSide) / 2

        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val srcRect = Rect(xOffset, yOffset, xOffset + minSide, yOffset + minSide)
        val dstRect = Rect(0, 0, targetSize, targetSize)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(softwareSource, srcRect, dstRect, paint)

        if (softwareSource !== source && !softwareSource.isRecycled) {
            softwareSource.recycle()
        }
        return output
    }

    private fun ensureSoftwareArgb8888(bitmap: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        return if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun compressToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return stream.toByteArray()
    }

    internal fun extractVibrantAccentColor(bitmap: Bitmap): Int {
        return extractAccentPalette(bitmap).firstOrNull() ?: DEFAULT_ACCENT_INT
    }

    /**
     * Computes 3 harmonious dominant accent colors suited for dark UI borders and gradients.
     * Handles both saturated colorful album covers and monochrome/B&W covers gracefully.
     */
    internal fun extractAccentPalette(bitmap: Bitmap): List<Int> {
        val sw = ensureSoftwareArgb8888(bitmap)
        val w = sw.width
        val h = sw.height
        if (w <= 0 || h <= 0) return DEFAULT_PALETTE_INTS

        val stepX = (w / 20).coerceAtLeast(1)
        val stepY = (h / 20).coerceAtLeast(1)

        // Bucket by 12-degree hue bins (30 buckets)
        val binWeight = FloatArray(30)
        val binRed = FloatArray(30)
        val binGreen = FloatArray(30)
        val binBlue = FloatArray(30)
        val hsv = FloatArray(3)

        var totalSampleWeight = 0f
        var weightedSatSum = 0f
        var avgR = 0f
        var avgG = 0f
        var avgB = 0f
        var avgCount = 0

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = sw.getPixel(x, y)
                if (Color.alpha(pixel) >= 128) {
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    avgR += r
                    avgG += g
                    avgB += b
                    avgCount++

                    Color.colorToHSV(pixel, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]
                    if (value > 0.14f && !(sat < 0.10f && value > 0.90f)) {
                        val weight = (sat * sat * 3.5f + 0.10f) * (0.35f + value * 0.65f)
                        val bin = ((hsv[0] / 12f).toInt()).coerceIn(0, 29)
                        binWeight[bin] += weight
                        binRed[bin] += r * weight
                        binGreen[bin] += g * weight
                        binBlue[bin] += b * weight
                        totalSampleWeight += weight
                        weightedSatSum += sat * weight
                    }
                }
                x += stepX
            }
            y += stepY
        }

        if (sw !== bitmap && !sw.isRecycled) {
            sw.recycle()
        }

        if (totalSampleWeight <= 0f || avgCount == 0) {
            return DEFAULT_PALETTE_INTS
        }

        val meanSat = weightedSatSum / totalSampleWeight
        val rankedBins = binWeight.indices
            .filter { binWeight[it] > 0f }
            .sortedByDescending { binWeight[it] }

        if (rankedBins.isEmpty()) {
            return DEFAULT_PALETTE_INTS
        }

        // Monochrome / B&W cover (like greyscale portraits): derive an atmospheric metallic/rosy-slate triad
        if (meanSat < 0.10f) {
            val rMean = avgR / avgCount
            val gMean = avgG / avgCount
            val bMean = avgB / avgCount
            val warmth = rMean - bMean
            return when {
                warmth > 4f -> listOf(0xFFD8B4A6.toInt(), 0xFFB88B8A.toInt(), 0xFF8C6B78.toInt())
                warmth < -4f -> listOf(0xFF93C5FD.toInt(), 0xFF64748B.toInt(), 0xFF6366F1.toInt())
                gMean > rMean + 3f -> listOf(0xFF94D2BD.toInt(), 0xFF6B9080.toInt(), 0xFF52796F.toInt())
                else -> listOf(0xFFD6C7C7.toInt(), 0xFFA89098.toInt(), 0xFF7B6D8D.toInt())
            }
        }

        fun normalizeBinColor(bin: Int): Int {
            val wt = binWeight[bin]
            val r = (binRed[bin] / wt).toInt().coerceIn(0, 255)
            val g = (binGreen[bin] / wt).toInt().coerceIn(0, 255)
            val b = (binBlue[bin] / wt).toInt().coerceIn(0, 255)
            Color.RGBToHSV(r, g, b, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.46f).coerceAtMost(0.92f)
            hsv[2] = hsv[2].coerceIn(0.68f, 0.96f)
            return Color.HSVToColor(hsv)
        }

        fun hueDistanceBins(a: Int, b: Int): Int {
            val diff = abs(a - b)
            return min(diff, 30 - diff)
        }

        val bin1 = rankedBins.first()
        val weight1 = binWeight[bin1]
        val color1 = normalizeBinColor(bin1)

        // Second distinct bin (>= 24 deg away, >= 16% of primary weight)
        val bin2 = rankedBins.firstOrNull { candidate ->
            candidate != bin1 &&
                hueDistanceBins(candidate, bin1) >= 2 &&
                binWeight[candidate] >= weight1 * 0.16f
        }
        val color2 = if (bin2 != null) {
            normalizeBinColor(bin2)
        } else {
            deriveAnalogousColor(color1, hueShift = 28f, satScale = 0.90f, valScale = 0.92f)
        }

        // Third distinct bin (>= 24 deg from both, >= 10% weight) or analogous opposite shift
        val bin3 = rankedBins.firstOrNull { candidate ->
            candidate != bin1 &&
                candidate != bin2 &&
                hueDistanceBins(candidate, bin1) >= 2 &&
                (bin2 == null || hueDistanceBins(candidate, bin2) >= 2) &&
                binWeight[candidate] >= weight1 * 0.10f
        }
        val color3 = if (bin3 != null) {
            normalizeBinColor(bin3)
        } else {
            deriveAnalogousColor(color1, hueShift = -28f, satScale = 0.85f, valScale = 0.78f)
        }

        return listOf(color1, color2, color3)
    }

    private fun deriveAnalogousColor(
        baseColor: Int,
        hueShift: Float,
        satScale: Float,
        valScale: Float
    ): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(baseColor, hsv)
        hsv[0] = ((hsv[0] + hueShift) % 360f + 360f) % 360f
        hsv[1] = (hsv[1] * satScale).coerceIn(0.35f, 0.95f)
        hsv[2] = (hsv[2] * valScale).coerceIn(0.55f, 0.96f)
        return Color.HSVToColor(hsv)
    }

    fun toHexString(colorInt: Int): String {
        return String.format(Locale.US, "#%06X", 0xFFFFFF and colorInt)
    }
}
