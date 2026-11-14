package com.example.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap

data class ResolvedAppInfo(
    val packageName: String,
    val displayName: String,
    val icon: ImageBitmap?
)

/**
 * Maps media player & Telegram client package names to concise, user-friendly labels
 * and caches their PackageManager launcher icons as ImageBitmaps.
 */
object AppMetadataResolver {

    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()
    private val labelCache = ConcurrentHashMap<String, String>()

    private val knownFriendlyNames = mapOf(
        "com.soundcloud.android" to "SoundCloud",
        "com.spotify.music" to "Spotify",
        "ru.yandex.music" to "Яндекс Музыка",
        "app.revanced.android.youtube" to "YouTube ReVanced",
        "app.rvx.android.youtube" to "YouTube RVX",
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.vkontakte.android" to "VK",
        "com.vk.music" to "VK Музыка",
        "com.uma.musicvk" to "VK Музыка",
        "com.apple.android.music" to "Apple Music",
        "com.maxmpz.audioplayer" to "Poweramp",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.ss.android.ugc.trill" to "TikTok",
        "com.instagram.android" to "Instagram",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram Web",
        "com.exteragram.messenger" to "exteraGram",
        "com.radolyn.ayugram" to "AyuGram",
        "by.aprelteam.gram" to "AprelGram",
        "com.aprelgram.messenger" to "AprelGram",
        "org.aprelgram.messenger" to "AprelGram",
        "app.aprelgram.messenger" to "AprelGram",
        "com.clawd.simulator" to "Clawd Simulator",
        "com.android.chrome" to "Chrome"
    )

    fun resolveFriendlyName(context: Context?, packageName: String): String {
        val cleaned = packageName.trim()
        if (cleaned.isEmpty()) return "Плеер"
        knownFriendlyNames[cleaned]?.let { return it }
        labelCache[cleaned]?.let { return it }

        if (context != null) {
            val pmLabel = queryPmLabel(context, cleaned)
            if (!pmLabel.isNullOrBlank()) {
                labelCache[cleaned] = pmLabel
                return pmLabel
            }
        }
        return cleaned.substringAfterLast('.').replaceFirstChar { it.uppercaseChar() }.ifBlank { cleaned }
    }

    fun resolveAppIcon(context: Context?, packageName: String): ImageBitmap? {
        val cleaned = packageName.trim()
        if (cleaned.isEmpty() || context == null) return null
        iconCache[cleaned]?.let { return it }

        return try {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(cleaned)
            val bitmap = drawableToBitmap(drawable, sizePx = 96)
            val imageBitmap = bitmap.asImageBitmap()
            iconCache[cleaned] = imageBitmap
            imageBitmap
        } catch (_: Throwable) {
            null
        }
    }

    fun resolveApp(context: Context?, packageName: String, fallbackLabel: String? = null): ResolvedAppInfo {
        val friendly = knownFriendlyNames[packageName]
            ?: fallbackLabel?.takeIf { it.isNotBlank() && it != packageName }
            ?: resolveFriendlyName(context, packageName)
        return ResolvedAppInfo(
            packageName = packageName,
            displayName = friendly,
            icon = resolveAppIcon(context, packageName)
        )
    }

    private fun queryPmLabel(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString().takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            val bmp = drawable.bitmap
            return if (bmp.width > 128 || bmp.height > 128) {
                Bitmap.createScaledBitmap(bmp, sizePx, sizePx, true)
            } else {
                bmp
            }
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(sizePx) ?: sizePx
        val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(sizePx) ?: sizePx
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
