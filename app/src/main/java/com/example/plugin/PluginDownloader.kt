package com.example.plugin

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

data class RemotePluginFile(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class TelegramClientApp(
    val packageName: String,
    val label: String
)

object PluginDownloader {

    const val GITHUB_PUBLIC_TREE_URL = "https://github.com/tsobbb/Clawd-plugin/tree/main/public"
    private const val GITHUB_API_CONTENTS_URL = "https://api.github.com/repos/tsobbb/Clawd-plugin/contents/public"
    private const val RAW_BASE_URL = "https://raw.githubusercontent.com/tsobbb/Clawd-plugin/main/public/"
    private const val CACHE_SUBDIR = "plugins"

    private val knownTelegramPackages = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "com.exteragram.messenger",
        "com.radolyn.ayugram",
        "uz.unnarsx.cherrygram",
        "org.thunderdog.challegram",
        "org.telegram.plus",
        "tw.nekomimi.nekogram",
        "nekox.messenger",
        "app.nicegram"
    )

    private val telegramKeywords = listOf(
        "telegram",
        "exteragram",
        "ayugram",
        "challegram",
        "cherrygram",
        "nekogram",
        "octogram",
        "forkgram",
        "swiftgram",
        "nicegram"
    )

    suspend fun fetchAvailablePlugins(): List<RemotePluginFile> = withContext(Dispatchers.IO) {
        val apiResult = runCatching { fetchViaGitHubApi() }.getOrNull()
        val files = if (!apiResult.isNullOrEmpty()) {
            apiResult
        } else {
            fetchViaHtmlFallback()
        }
        sortAndFilterPlugins(files)
    }

    private fun fetchViaGitHubApi(): List<RemotePluginFile> {
        val conn = (URL(GITHUB_API_CONTENTS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ClawdBridge-Android")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub API HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(body)
            val result = mutableListOf<RemotePluginFile>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("type") == "file") {
                    val name = obj.optString("name")
                    val downloadUrl = obj.optString("download_url")
                    val size = obj.optLong("size", -1L)
                    if (name.isNotBlank() && downloadUrl.isNotBlank()) {
                        result.add(RemotePluginFile(name = name, downloadUrl = downloadUrl, sizeBytes = size))
                    }
                }
            }
            result
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchViaHtmlFallback(): List<RemotePluginFile> {
        val conn = (URL(GITHUB_PUBLIC_TREE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ClawdBridge")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub HTTP $code")
            }
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val regex = Regex("""/tsobbb/Clawd-plugin/blob/main/public/([^"\\?#<>\s]+)""")
            val seen = LinkedHashSet<String>()
            val result = mutableListOf<RemotePluginFile>()
            for (match in regex.findAll(html)) {
                val encodedSegment = match.groupValues[1]
                val decodedName = runCatching {
                    URLDecoder.decode(encodedSegment, "UTF-8")
                }.getOrDefault(encodedSegment)
                if (decodedName.isNotBlank() && seen.add(decodedName)) {
                    val encodedPath = URLEncoder.encode(decodedName, "UTF-8").replace("+", "%20")
                    result.add(
                        RemotePluginFile(
                            name = decodedName,
                            downloadUrl = "$RAW_BASE_URL$encodedPath",
                            sizeBytes = -1L
                        )
                    )
                }
            }
            result
        } finally {
            conn.disconnect()
        }
    }

    internal fun sortAndFilterPlugins(files: List<RemotePluginFile>): List<RemotePluginFile> {
        if (files.isEmpty()) return emptyList()
        val nonIgnored = files.filterNot { file ->
            val lower = file.name.lowercase(Locale.US)
            lower == ".gitkeep" || lower == ".ds_store" || lower.startsWith("readme")
        }
        val candidates = nonIgnored.ifEmpty { files }
        return candidates.sortedWith(
            compareByDescending<RemotePluginFile> { it.name.lowercase(Locale.US).endsWith(".plugin") }
                .thenByDescending { it.name.lowercase(Locale.US).contains("clawd") }
                .thenByDescending { extractVersionKey(it.name) }
                .thenBy { it.name.lowercase(Locale.US) }
        )
    }

    internal fun extractVersionKey(name: String): Long {
        val match = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(name) ?: return 0L
        val major = match.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
        val minor = match.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
        val patch = match.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
        return major * 1_000_000L + minor * 1_000L + patch
    }

    suspend fun downloadPluginToCache(
        context: Context,
        remoteFile: RemotePluginFile,
        onProgress: (Float?) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply {
            if (!exists()) mkdirs()
        }
        // Keep cache clean: remove older plugin files before saving the new one
        cacheDir.listFiles()?.forEach { old ->
            runCatching { old.delete() }
        }

        val safeName = sanitizeFileName(remoteFile.name)
        val tempFile = File(cacheDir, "$safeName.part")
        val targetFile = File(cacheDir, safeName)

        val conn = (URL(remoteFile.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ClawdBridge-Android")
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Ошибка загрузки HTTP $code")
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: remoteFile.sizeBytes
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(tempFile, false).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress((downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                        } else {
                            onProgress(null)
                        }
                    }
                    output.flush()
                }
            }

            if (downloadedBytes <= 0L) {
                tempFile.delete()
                throw IllegalStateException("Скачанный файл пуст")
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            targetFile
        } finally {
            conn.disconnect()
        }
    }

    fun getCachedPluginFile(context: Context): File? {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        if (!dir.exists()) return null
        return dir.listFiles()
            ?.firstOrNull { it.isFile && !it.name.endsWith(".part") && it.length() > 0L }
    }

    fun clearPluginCache(context: Context) {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun findInstalledTelegramClients(context: Context): List<TelegramClientApp> {
        val pm = context.packageManager
        val probeIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                probeIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }

        val matched = resolveInfos
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val lowerPkg = pkg.lowercase(Locale.US)
                val isTelegram = pkg in knownTelegramPackages || telegramKeywords.any { lowerPkg.contains(it) }
                if (!isTelegram) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                TelegramClientApp(packageName = pkg, label = label)
            }
            .distinctBy { it.packageName }

        return matched.sortedBy { client ->
            val idx = knownTelegramPackages.indexOf(client.packageName)
            if (idx >= 0) idx else Int.MAX_VALUE
        }
    }

    fun buildShareToTelegramIntent(
        context: Context,
        file: File,
        targetPackage: String? = null
    ): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newRawUri(file.name, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!targetPackage.isNullOrBlank()) {
                setPackage(targetPackage)
            }
        }
    }

    internal fun sanitizeFileName(rawName: String): String {
        val baseName = rawName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = baseName
            .replace("..", "_")
            .replace(Regex("""[/:*?"<>|]"""), "_")
            .trim()
        return cleaned.ifEmpty { "clawd_plugin.plugin" }
    }
}
