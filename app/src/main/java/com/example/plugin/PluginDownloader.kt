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
import org.json.JSONObject
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
    val sizeBytes: Long,
    val apiFileUrl: String? = null
)

data class TelegramClientApp(
    val packageName: String,
    val label: String
)

sealed class ParsedSource {
    data class GitHubDirectory(
        val owner: String,
        val repo: String,
        val branch: String?,
        val path: String
    ) : ParsedSource()

    data class GitHubSingleFile(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String
    ) : ParsedSource()

    data class DirectUrl(val url: String, val fileName: String) : ParsedSource()
}

object PluginDownloader {

    const val DEFAULT_SOURCE_URL = "https://github.com/tsobbb/Clawd-plugin/tree/main/public"
    private const val PREFS_NAME = "clawd_plugin_prefs"
    private const val KEY_SOURCE_URL = "source_url"
    private const val KEY_GITHUB_TOKEN = "github_token"
    private const val KEY_CONVERT_PY_TO_PLUGIN = "convert_py_to_plugin"
    private const val CACHE_SUBDIR = "plugins"

    private val knownTelegramPackages = listOf(
        "by.aprelteam.gram",
        "com.aprelgram.messenger",
        "org.aprelgram.messenger",
        "app.aprelgram.messenger",
        "ru.aprel.gram",
        "com.exteragram.messenger",
        "com.radolyn.ayugram",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "uz.unnarsx.cherrygram",
        "org.thunderdog.challegram",
        "org.telegram.plus",
        "tw.nekomimi.nekogram",
        "nekox.messenger",
        "app.nicegram"
    )

    private val telegramKeywords = listOf(
        "aprel",
        "aprelgram",
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

    fun getSavedSourceUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SOURCE_URL, DEFAULT_SOURCE_URL)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SOURCE_URL
    }

    fun saveSourceUrl(context: Context, url: String) {
        val cleaned = url.trim().ifEmpty { DEFAULT_SOURCE_URL }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE_URL, cleaned)
            .apply()
    }

    fun getSavedToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""
    }

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GITHUB_TOKEN, token.trim())
            .apply()
    }

    fun getConvertPyToPlugin(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CONVERT_PY_TO_PLUGIN, true)
    }

    fun setConvertPyToPlugin(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONVERT_PY_TO_PLUGIN, enabled)
            .apply()
    }

    internal fun resolveTargetFileName(rawName: String, convertPyToPlugin: Boolean): String {
        val cleaned = sanitizeFileName(rawName)
        return if (convertPyToPlugin && cleaned.lowercase(Locale.US).endsWith(".py")) {
            cleaned.dropLast(3) + ".plugin"
        } else {
            cleaned
        }
    }

    internal fun parseSourceUrl(rawUrl: String): ParsedSource {
        val trimmed = rawUrl.trim().trimEnd('/')

        // https://github.com/owner/repo/blob/branch/path/to/file
        val blobRegex = Regex("""^https?://github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$""")
        blobRegex.find(trimmed)?.let { m ->
            return ParsedSource.GitHubSingleFile(
                owner = m.groupValues[1],
                repo = m.groupValues[2].removeSuffix(".git"),
                branch = m.groupValues[3],
                path = m.groupValues[4]
            )
        }

        // https://github.com/owner/repo/tree/branch[/optional/path]
        val treeRegex = Regex("""^https?://github\.com/([^/]+)/([^/]+)/tree/([^/]+)(?:/(.*))?$""")
        treeRegex.find(trimmed)?.let { m ->
            return ParsedSource.GitHubDirectory(
                owner = m.groupValues[1],
                repo = m.groupValues[2].removeSuffix(".git"),
                branch = m.groupValues[3],
                path = m.groupValues[4].trim('/')
            )
        }

        // https://github.com/owner/repo
        val repoRegex = Regex("""^https?://github\.com/([^/]+)/([^/]+)$""")
        repoRegex.find(trimmed)?.let { m ->
            return ParsedSource.GitHubDirectory(
                owner = m.groupValues[1],
                repo = m.groupValues[2].removeSuffix(".git"),
                branch = null,
                path = ""
            )
        }

        // https://raw.githubusercontent.com/... or any direct URL
        val fileName = sanitizeFileName(trimmed.substringAfterLast('/').substringBefore('?'))
        return ParsedSource.DirectUrl(url = trimmed, fileName = fileName)
    }

    suspend fun fetchAvailablePlugins(
        sourceUrl: String = DEFAULT_SOURCE_URL,
        githubToken: String = ""
    ): List<RemotePluginFile> = withContext(Dispatchers.IO) {
        val token = githubToken.trim()
        when (val parsed = parseSourceUrl(sourceUrl)) {
            is ParsedSource.DirectUrl -> {
                listOf(
                    RemotePluginFile(
                        name = parsed.fileName,
                        downloadUrl = parsed.url,
                        sizeBytes = -1L
                    )
                )
            }
            is ParsedSource.GitHubSingleFile -> {
                val rawUrl = "https://raw.githubusercontent.com/${parsed.owner}/${parsed.repo}/${parsed.branch}/${parsed.path}"
                val apiUrl = "https://api.github.com/repos/${parsed.owner}/${parsed.repo}/contents/${parsed.path}?ref=${parsed.branch}"
                listOf(
                    RemotePluginFile(
                        name = sanitizeFileName(parsed.path.substringAfterLast('/')),
                        downloadUrl = rawUrl,
                        sizeBytes = -1L,
                        apiFileUrl = apiUrl
                    )
                )
            }
            is ParsedSource.GitHubDirectory -> {
                fetchFromGitHubDirectory(parsed, token)
            }
        }
    }

    private fun fetchFromGitHubDirectory(
        dir: ParsedSource.GitHubDirectory,
        token: String
    ): List<RemotePluginFile> {
        var lastError: Exception? = null

        // 1. Try exact path + branch via GitHub Contents API
        try {
            val apiFiles = fetchViaGitHubApi(dir.owner, dir.repo, dir.path, dir.branch, token)
            if (apiFiles.isNotEmpty()) return sortAndFilterPlugins(apiFiles)
        } catch (e: Exception) {
            lastError = e
        }

        // 2. If branch was specified (e.g. "main") and failed, retry default branch (e.g. "master")
        if (dir.branch != null) {
            try {
                val defaultBranchFiles = fetchViaGitHubApi(dir.owner, dir.repo, dir.path, null, token)
                if (defaultBranchFiles.isNotEmpty()) return sortAndFilterPlugins(defaultBranchFiles)
            } catch (e: Exception) {
                lastError = e
            }
        }

        // 3. If a subfolder was specified (e.g. "public") and not found, inspect repo root & case-insensitive match
        if (dir.path.isNotEmpty()) {
            try {
                val rootEntries = fetchRawEntriesViaGitHubApi(dir.owner, dir.repo, "", null, token)
                // Look for case-insensitive directory match (e.g. "Public" instead of "public")
                val matchingSubdir = rootEntries.firstOrNull {
                    it.optString("type") == "dir" &&
                        it.optString("name").equals(dir.path, ignoreCase = true)
                }
                if (matchingSubdir != null) {
                    val actualPath = matchingSubdir.optString("path")
                    val subFiles = fetchViaGitHubApi(dir.owner, dir.repo, actualPath, null, token)
                    if (subFiles.isNotEmpty()) return sortAndFilterPlugins(subFiles)
                }
                // Otherwise check if plugin files are directly in the repo root
                val rootFiles = entriesToRemoteFiles(rootEntries)
                val pluginInRoot = rootFiles.filter {
                    val lower = it.name.lowercase(Locale.US)
                    lower.endsWith(".plugin") || lower.endsWith(".py") || lower.endsWith(".zip")
                }
                if (pluginInRoot.isNotEmpty()) return sortAndFilterPlugins(pluginInRoot)
            } catch (_: Exception) {
                // Keep original error
            }
        }

        // 4. Check GitHub Releases (if repo exists and is accessible, plugins are sometimes published as release assets)
        try {
            val releaseAssets = fetchViaGitHubReleases(dir.owner, dir.repo, token)
            if (releaseAssets.isNotEmpty()) return sortAndFilterPlugins(releaseAssets)
        } catch (_: Exception) {
            // Keep original error
        }

        // 5. Fallback to GitHub Web HTML if token is empty (handles unauthenticated API rate limits)
        if (token.isEmpty()) {
            try {
                val branch = dir.branch ?: "main"
                val pathSuffix = if (dir.path.isNotEmpty()) "/${dir.path}" else ""
                val webUrl = "https://github.com/${dir.owner}/${dir.repo}/tree/$branch$pathSuffix"
                val htmlFiles = fetchViaHtmlFallback(webUrl, dir.owner, dir.repo, branch, dir.path)
                if (htmlFiles.isNotEmpty()) return sortAndFilterPlugins(htmlFiles)
            } catch (e: Exception) {
                if (lastError == null) lastError = e
            }
        }

        throw buildFriendlyError(lastError, token.isNotEmpty())
    }

    private fun buildFriendlyError(error: Exception?, hasToken: Boolean): IllegalStateException {
        val msg = error?.message ?: "Не удалось загрузить список файлов"
        return if (msg.contains("404")) {
            if (hasToken) {
                IllegalStateException(
                    "GitHub 404: репозиторий, ветка или папка не найдены (проверьте ссылку и права токена 'repo')"
                )
            } else {
                IllegalStateException(
                    "GitHub 404: репозиторий приватный (если рядом с названием на GitHub есть замок 🔒) или путь не найден. Сделайте его Public в Settings → Danger Zone на GitHub либо укажите Token ниже."
                )
            }
        } else if (msg.contains("401") || msg.contains("403")) {
            IllegalStateException("Доступ запрещён ($msg). Проверьте GitHub Token или лимит API")
        } else {
            IllegalStateException(msg)
        }
    }

    private fun fetchRawEntriesViaGitHubApi(
        owner: String,
        repo: String,
        path: String,
        branch: String?,
        token: String
    ): List<JSONObject> {
        val cleanPath = path.trim('/')
        val pathPart = if (cleanPath.isEmpty()) "" else "/$cleanPath"
        val refPart = if (!branch.isNullOrBlank()) "?ref=${URLEncoder.encode(branch, "UTF-8")}" else ""
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents$pathPart$refPart"

        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ClawdBridge-Android")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("GitHub API HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                List(array.length()) { idx -> array.getJSONObject(idx) }
            } else {
                listOf(JSONObject(trimmed))
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchViaGitHubApi(
        owner: String,
        repo: String,
        path: String,
        branch: String?,
        token: String
    ): List<RemotePluginFile> {
        val entries = fetchRawEntriesViaGitHubApi(owner, repo, path, branch, token)
        return entriesToRemoteFiles(entries)
    }

    private fun entriesToRemoteFiles(entries: List<JSONObject>): List<RemotePluginFile> {
        val result = mutableListOf<RemotePluginFile>()
        for (obj in entries) {
            if (obj.optString("type") == "file") {
                val name = obj.optString("name")
                val downloadUrl = obj.optString("download_url")
                val apiFileUrl = obj.optString("url")
                val size = obj.optLong("size", -1L)
                if (name.isNotBlank() && (downloadUrl.isNotBlank() || apiFileUrl.isNotBlank())) {
                    result.add(
                        RemotePluginFile(
                            name = name,
                            downloadUrl = downloadUrl.ifBlank { apiFileUrl },
                            sizeBytes = size,
                            apiFileUrl = apiFileUrl.ifBlank { null }
                        )
                    )
                }
            }
        }
        return result
    }

    private fun fetchViaGitHubReleases(
        owner: String,
        repo: String,
        token: String
    ): List<RemotePluginFile> {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ClawdBridge-Android")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val assets = json.optJSONArray("assets") ?: return emptyList()
            val result = mutableListOf<RemotePluginFile>()
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                val downloadUrl = asset.optString("browser_download_url")
                val apiAssetUrl = asset.optString("url")
                val size = asset.optLong("size", -1L)
                if (name.isNotBlank() && downloadUrl.isNotBlank()) {
                    result.add(
                        RemotePluginFile(
                            name = name,
                            downloadUrl = downloadUrl,
                            sizeBytes = size,
                            apiFileUrl = apiAssetUrl.ifBlank { null }
                        )
                    )
                }
            }
            result
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchViaHtmlFallback(
        webUrl: String,
        owner: String,
        repo: String,
        branch: String,
        path: String
    ): List<RemotePluginFile> {
        val conn = (URL(webUrl).openConnection() as HttpURLConnection).apply {
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
            val escapedPrefix = Regex.escape("/$owner/$repo/blob/$branch/") +
                if (path.isNotEmpty()) Regex.escape("$path/") else ""
            val regex = Regex("""$escapedPrefix([^"\\?#<>\s]+)""")
            val seen = LinkedHashSet<String>()
            val result = mutableListOf<RemotePluginFile>()
            val rawPrefix = if (path.isNotEmpty()) "$path/" else ""
            for (match in regex.findAll(html)) {
                val encodedSegment = match.groupValues[1]
                val decodedName = runCatching {
                    URLDecoder.decode(encodedSegment, "UTF-8")
                }.getOrDefault(encodedSegment)
                if (decodedName.isNotBlank() && !decodedName.contains('/') && seen.add(decodedName)) {
                    val encodedPath = URLEncoder.encode(decodedName, "UTF-8").replace("+", "%20")
                    result.add(
                        RemotePluginFile(
                            name = decodedName,
                            downloadUrl = "https://raw.githubusercontent.com/$owner/$repo/$branch/$rawPrefix$encodedPath",
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
            compareByDescending<RemotePluginFile> { extensionPriority(it.name) }
                .thenByDescending { it.name.lowercase(Locale.US).contains("clawd") }
                .thenByDescending { extractVersionKey(it.name) }
                .thenBy { it.name.lowercase(Locale.US) }
        )
    }

    private fun extensionPriority(name: String): Int {
        val lower = name.lowercase(Locale.US)
        return when {
            lower.endsWith(".plugin") -> 3
            lower.endsWith(".py") -> 2
            lower.endsWith(".zip") || lower.endsWith(".js") -> 1
            else -> 0
        }
    }

    internal fun extractVersionKey(name: String): Long {
        val multiPart = Regex("""(?:^|[^0-9])v?(\d+)[._-](\d+)(?:[._-](\d+))?""", RegexOption.IGNORE_CASE)
            .find(name)
        if (multiPart != null) {
            val major = multiPart.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
            val minor = multiPart.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
            val patch = multiPart.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
            return major * 1_000_000L + minor * 1_000L + patch
        }
        val single = Regex("""(\d+)""").find(name) ?: return 0L
        val major = single.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
        return major * 1_000_000L
    }

    suspend fun downloadPluginToCache(
        context: Context,
        remoteFile: RemotePluginFile,
        githubToken: String = "",
        convertPyToPlugin: Boolean = true,
        onProgress: (Float?) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, CACHE_SUBDIR).apply {
            if (!exists()) mkdirs()
        }
        cacheDir.listFiles()?.forEach { old ->
            runCatching { old.delete() }
        }

        val safeName = resolveTargetFileName(remoteFile.name, convertPyToPlugin)
        val tempFile = File(cacheDir, "$safeName.part")
        val targetFile = File(cacheDir, safeName)

        // For private repos with token, GitHub Contents API with Accept: application/vnd.github.raw
        // is the most reliable way to download raw file bytes without signed-URL expiry issues.
        val token = githubToken.trim()
        val useApiRaw = token.isNotEmpty() && !remoteFile.apiFileUrl.isNullOrBlank()
        val requestUrl = if (useApiRaw) remoteFile.apiFileUrl!! else remoteFile.downloadUrl

        val conn = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ClawdBridge-Android")
            if (useApiRaw) {
                setRequestProperty("Accept", "application/vnd.github.raw")
            }
            if (token.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
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
