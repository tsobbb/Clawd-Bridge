package com.example.media

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
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

data class NotificationArtProbe(
    val bitmap: Bitmap?,
    val uriFallback: String?,
    val debugNote: String
)

data class UriArtLoadResult(
    val artwork: ExtractedArtwork?,
    val debugNote: String
)

object ArtworkExtractor {

    private const val ARTWORK_SIZE_PX = 160
    private const val MAX_DECODE_DIMENSION = 512
    private const val MAX_REMOTE_IMAGE_BYTES = 5 * 1024 * 1024
    private const val JPEG_QUALITY = 84
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
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
     * Generates a deterministic, vivid 3-color gradient triad from (artist + title) hash
     * when a rare track genuinely has no cover anywhere (~1% fallback), avoiding dull defaults.
     */
    fun generateTrackPalette(artist: String, title: String): List<String> {
        val seedStr = "${artist.trim().lowercase(Locale.US)}|${title.trim().lowercase(Locale.US)}"
        if (seedStr == "|") {
            return listOf("#00E5FF", "#3B82F6", "#8B5CF6")
        }
        val hash = seedStr.hashCode()
        val unsigned = hash.toLong() and 0xFFFFFFFFL
        val baseHue = (unsigned % 360L).toFloat()
        val sat1 = 0.68f + ((unsigned shr 8) % 20L) / 100f
        val val1 = 0.84f + ((unsigned shr 16) % 11L) / 100f

        val c1 = hsvColor(baseHue, sat1, val1)
        val c2 = hsvColor((baseHue + 38f) % 360f, (sat1 - 0.05f).coerceIn(0.55f, 0.92f), (val1 * 0.96f).coerceIn(0.70f, 0.96f))
        val c3 = hsvColor((baseHue + 318f) % 360f, (sat1 + 0.04f).coerceIn(0.58f, 0.95f), (val1 * 0.84f).coerceIn(0.65f, 0.92f))
        return listOf(toHexString(c1), toHexString(c2), toHexString(c3))
    }

    private fun hsvColor(hue: Float, sat: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = sat.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        // Pure JVM-safe HSV->RGB conversion (also safe on unit tests without Android graphics stub issues)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (rf, gf, bf) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((rf + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((gf + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((bf + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Returns any inline Bitmap attached directly inside MediaMetadata (checks standard + custom art keys).
     */
    fun extractInlineBitmap(metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        val standardKeys = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON
        )
        for (key in standardKeys) {
            val candidate = safeGetBitmap(metadata, key)
            if (isValidCoverBitmap(candidate)) return candidate
        }

        val descBmp = try {
            metadata.description?.iconBitmap
        } catch (_: Throwable) {
            null
        }
        if (isValidCoverBitmap(descBmp)) return descBmp

        val allKeys = try {
            metadata.keySet()
        } catch (_: Throwable) {
            emptySet()
        }
        for (key in allKeys) {
            val lower = key.lowercase(Locale.US)
            if (lower.contains("art") || lower.contains("icon") || lower.contains("thumb") || lower.contains("cover") || lower.contains("image")) {
                val candidate = safeGetBitmap(metadata, key)
                if (isValidCoverBitmap(candidate)) return candidate
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun safeGetBitmap(metadata: MediaMetadata, key: String): Bitmap? {
        return try {
            metadata.getBitmap(key)
        } catch (_: Throwable) {
            null
        }
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
            val value = safeGetString(metadata, key)
            if (!value.isNullOrBlank()) return value
        }

        val descUri = try {
            metadata.description?.iconUri?.toString()?.trim()
        } catch (_: Throwable) {
            null
        }
        if (!descUri.isNullOrBlank()) return descUri

        val allKeys = try {
            metadata.keySet()
        } catch (_: Throwable) {
            emptySet()
        }
        for (key in allKeys) {
            val lower = key.lowercase(Locale.US)
            if (lower.contains("art") || lower.contains("icon") || lower.contains("thumb") || lower.contains("cover") || lower.contains("image")) {
                val value = safeGetString(metadata, key)
                if (!value.isNullOrBlank() && (value.contains("://") || value.startsWith("/"))) {
                    return value
                }
            }
        }
        return null
    }

    private fun safeGetString(metadata: MediaMetadata, key: String): String? {
        return try {
            metadata.getString(key)?.trim()
        } catch (_: Throwable) {
            null
        }
    }

    fun summarizeMetadataKeys(metadata: MediaMetadata?): String {
        if (metadata == null) return "meta=null"
        return try {
            val keys = metadata.keySet()
            val artKeys = keys.filter {
                val l = it.lowercase(Locale.US)
                l.contains("art") || l.contains("icon") || l.contains("thumb") || l.contains("cover") || l.contains("uri")
            }
            if (artKeys.isEmpty()) "metaKeys(${keys.size})=none"
            else "metaArtKeys=${artKeys.joinToString(",") { it.substringAfterLast('.') }}"
        } catch (_: Throwable) {
            "metaKeys=err"
        }
    }

    fun probeBitmapFromNotification(context: Context, notification: Notification?): NotificationArtProbe {
        if (notification == null) return NotificationArtProbe(null, null, "notif=null")
        val notes = mutableListOf<String>()
        var fallbackUri: String? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val largeIcon = notification.getLargeIcon()
            if (largeIcon != null) {
                val iconRes = extractFromIcon(context, largeIcon, "largeIcon")
                if (isValidCoverBitmap(iconRes.bitmap)) {
                    return NotificationArtProbe(iconRes.bitmap, null, "notif:largeIcon")
                }
                if (iconRes.uriFallback != null && fallbackUri == null) {
                    fallbackUri = iconRes.uriFallback
                }
                notes.add(iconRes.debugNote)
            }
        }

        val extras: Bundle? = notification.extras
        if (extras != null) {
            val priorityKeys = listOf(
                Notification.EXTRA_PICTURE,
                Notification.EXTRA_LARGE_ICON_BIG,
                Notification.EXTRA_LARGE_ICON,
                Notification.EXTRA_BACKGROUND_IMAGE_URI
            )
            for (key in priorityKeys) {
                val res = extractFromBundleKey(context, extras, key)
                if (isValidCoverBitmap(res.bitmap)) {
                    return NotificationArtProbe(res.bitmap, null, "notif:${key.substringAfterLast('.')}")
                }
                if (res.uriFallback != null && fallbackUri == null) {
                    fallbackUri = res.uriFallback
                }
                if (res.debugNote.isNotEmpty()) notes.add(res.debugNote)
            }

            for (key in extras.keySet()) {
                if (key in priorityKeys || key == Notification.EXTRA_SMALL_ICON) continue
                val res = extractFromBundleKey(context, extras, key)
                if (isValidCoverBitmap(res.bitmap)) {
                    return NotificationArtProbe(res.bitmap, null, "notif:${key.substringAfterLast('.')}")
                }
                if (res.uriFallback != null && fallbackUri == null) {
                    fallbackUri = res.uriFallback
                }
            }
        }

        return NotificationArtProbe(
            bitmap = null,
            uriFallback = fallbackUri,
            debugNote = if (notes.isEmpty()) "notif=empty" else notes.joinToString(";")
        )
    }

    @Suppress("DEPRECATION")
    private fun extractFromBundleKey(context: Context, extras: Bundle, key: String): NotificationArtProbe {
        return try {
            val raw = extras.get(key) ?: return NotificationArtProbe(null, null, "")
            when (raw) {
                is Bitmap -> {
                    if (isValidCoverBitmap(raw)) NotificationArtProbe(raw, null, "$key:bmp")
                    else NotificationArtProbe(null, null, "$key:small(${raw.width}x${raw.height})")
                }
                is Icon -> extractFromIcon(context, raw, key.substringAfterLast('.'))
                is Uri -> NotificationArtProbe(null, raw.toString(), "$key:uri")
                is String -> {
                    if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("content://") || raw.startsWith("file://")) {
                        NotificationArtProbe(null, raw, "$key:strUri")
                    } else {
                        NotificationArtProbe(null, null, "")
                    }
                }
                else -> NotificationArtProbe(null, null, "")
            }
        } catch (t: Throwable) {
            NotificationArtProbe(null, null, "$key:${t.javaClass.simpleName}")
        }
    }

    private fun extractFromIcon(context: Context, icon: Icon, tag: String): NotificationArtProbe {
        var uriString: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val type = icon.type
                if (type == Icon.TYPE_URI || type == Icon.TYPE_URI_ADAPTIVE_BITMAP) {
                    uriString = icon.uri.toString()
                }
            } catch (_: Throwable) {}
        }

        return try {
            val drawable = icon.loadDrawable(context)
            if (drawable == null) {
                NotificationArtProbe(null, uriString, "$tag:loadDrawable=null")
            } else {
                val bmp = drawableToSoftwareBitmap(drawable)
                if (isValidCoverBitmap(bmp)) {
                    NotificationArtProbe(bmp, null, "$tag:ok")
                } else {
                    NotificationArtProbe(null, uriString, "$tag:small")
                }
            }
        } catch (t: Throwable) {
            NotificationArtProbe(null, uriString, "$tag:${t.javaClass.simpleName}")
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

    fun loadFromUriDetailed(context: Context, uriString: String): UriArtLoadResult {
        val lower = uriString.lowercase(Locale.US)
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val candidates = if (lower.startsWith("http://")) {
                listOf("https://" + uriString.substring(7), uriString)
            } else {
                listOf(uriString)
            }
            var lastNote = "http:fail"
            for (urlCandidate in candidates) {
                val (bytes, note) = downloadHttpBytes(urlCandidate)
                lastNote = note
                if (bytes != null && bytes.isNotEmpty()) {
                    val decoded = decodeSampledBitmap(bytes, MAX_DECODE_DIMENSION)
                    val extracted = decoded?.let { fromBitmap(it) }
                    if (extracted != null) {
                        return UriArtLoadResult(extracted, "uri:http ok (${bytes.size}B)")
                    }
                    lastNote = "http:decode_null(${bytes.size}B)"
                }
            }
            return UriArtLoadResult(null, lastNote)
        }

        return try {
            val uri = Uri.parse(uriString)
            val streamBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                readLimitedBytes(stream, MAX_REMOTE_IMAGE_BYTES)
            }
            if (streamBytes != null && streamBytes.isNotEmpty()) {
                val decoded = decodeSampledBitmap(streamBytes, MAX_DECODE_DIMENSION)
                val extracted = decoded?.let { fromBitmap(it) }
                if (extracted != null) {
                    return UriArtLoadResult(extracted, "uri:content ok")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val maxEdge = maxOf(info.size.width, info.size.height)
                    if (maxEdge > MAX_DECODE_DIMENSION) {
                        val scale = MAX_DECODE_DIMENSION.toFloat() / maxEdge.toFloat()
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
                val extracted = fromBitmap(bmp)
                if (extracted != null) {
                    return UriArtLoadResult(extracted, "uri:imageDecoder ok")
                }
            }
            UriArtLoadResult(null, "uri:local empty")
        } catch (t: Throwable) {
            UriArtLoadResult(null, "uri:${t.javaClass.simpleName}")
        }
    }

    internal fun downloadHttpBytes(urlString: String, timeoutMs: Int = 4000): Pair<ByteArray?, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,application/json,text/html;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return Pair(null, "http:$code")
            }
            val bytes = conn.inputStream?.use { stream ->
                readLimitedBytes(stream, MAX_REMOTE_IMAGE_BYTES)
            }
            Pair(bytes, "http:$code")
        } catch (t: Throwable) {
            Pair(null, "http:${t.javaClass.simpleName}")
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
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    internal fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int = MAX_DECODE_DIMENSION): Bitmap? {
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

    fun fromBitmap(rawBitmap: Bitmap): ExtractedArtwork? {
        if (rawBitmap.isRecycled || rawBitmap.width <= 0 || rawBitmap.height <= 0) {
            return null
        }

        val thumbnail = createSquareThumbnail(rawBitmap, ARTWORK_SIZE_PX)
        val jpegBytes = compressToJpeg(thumbnail)
        val pixels32 = ArtworkBlurHelper.extractPixels32(thumbnail)
        val smart = SmartColorExtractor.extractFromPixels(pixels32, 32, 32)
        val primaryInt = 0xFF000000.toInt() or smart.playerAccentRgb
        val paletteInts = smart.paletteTriadRgb.map { 0xFF000000.toInt() or it }
        val paletteHex = paletteInts.map { toHexString(it) }
        val primaryHex = toHexString(primaryInt)

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
        val pixels = ArtworkBlurHelper.extractPixels32(bitmap)
        return 0xFF000000.toInt() or SmartColorExtractor.extractFromPixels(pixels, 32, 32).playerAccentRgb
    }

    internal fun extractAccentPalette(bitmap: Bitmap): List<Int> {
        val pixels = ArtworkBlurHelper.extractPixels32(bitmap)
        return SmartColorExtractor.extractFromPixels(pixels, 32, 32).paletteTriadRgb.map {
            0xFF000000.toInt() or it
        }
    }

    fun toHexString(colorInt: Int): String {
        return String.format(Locale.US, "#%06X", 0xFFFFFF and colorInt)
    }
}
