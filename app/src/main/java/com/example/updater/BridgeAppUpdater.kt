package com.example.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.BuildConfig
import com.example.network.BridgeHttpClient
import com.example.plugin.PluginDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

data class RemoteApkRelease(
    val sourceLabel: String,
    val versionCode: Int?,
    val versionName: String,
    val apkName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val remoteSha: String = "",
    val isUpdateAvailable: Boolean,
    val isRebuildWithSameVersion: Boolean = false
)

sealed class BridgeUpdateState {
    data object Idle : BridgeUpdateState()
    data object Checking : BridgeUpdateState()
    data class UpToDate(val checkedAtVersion: String) : BridgeUpdateState()
    data class UpdateAvailable(val release: RemoteApkRelease) : BridgeUpdateState()
    data class Downloading(
        val release: RemoteApkRelease,
        val progress: Float?,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : BridgeUpdateState()
    data class NeedsInstallPermission(
        val apkFile: File,
        val release: RemoteApkRelease
    ) : BridgeUpdateState()
    data class Installing(val statusText: String) : BridgeUpdateState()
    data object Installed : BridgeUpdateState()
    data class Error(val message: String) : BridgeUpdateState()
}

/**
 * Hybrid In-App APK Updater for https://github.com/tsobbb/Clawd-Bridge.
 * 1) Checks /releases/latest for .apk assets (comparing semver against BuildConfig.VERSION_NAME).
 * 2) Falls back to main branch: raw/main/version.json (with cache-buster) -> raw/main/app/build.gradle.kts,
 *    plus /contents/... metadata and raw/main/ClawdBridge.apk download.
 * 3) Installs natively via PackageInstaller.Session + InstallStatusReceiver (STATUS_PENDING_USER_ACTION).
 */
object BridgeAppUpdater {

    private const val TAG = "BridgeAppUpdater"
    const val REPO_OWNER = "tsobbb"
    const val REPO_NAME = "Clawd-Bridge"
    const val REPO_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"
    private const val DEFAULT_APK_NAME = "ClawdBridge.apk"
    private const val UPDATES_SUBDIR = "updates"
    private const val PREFS_NAME = "clawd_updater_prefs"
    private const val KEY_PENDING_SHA = "pending_install_sha"
    private const val KEY_INSTALLED_SHA = "last_installed_sha"
    private const val KEY_INSTALLED_VER_CODE = "last_installed_version_code"

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null

    private val _state = MutableStateFlow<BridgeUpdateState>(BridgeUpdateState.Idle)
    val state: StateFlow<BridgeUpdateState> = _state.asStateFlow()

    @Volatile
    private var lastDownloadedApk: File? = null

    @Volatile
    private var lastReleaseMeta: RemoteApkRelease? = null

    fun checkForUpdates(context: Context) {
        if (activeJob?.isActive == true) return
        val appCtx = context.applicationContext
        activeJob = ioScope.launch {
            _state.value = BridgeUpdateState.Checking
            try {
                val token = PluginDownloader.getSavedToken(appCtx)
                val localFingerprint = readLocalApkFingerprint(appCtx)
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lastInstalledSha = prefs.getString(KEY_INSTALLED_SHA, null)
                val lastInstalledVerCode = prefs.getInt(KEY_INSTALLED_VER_CODE, -1)

                val release = fetchLatestBridgeRelease(
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    localSize = localFingerprint.sizeBytes,
                    localGitSha = localFingerprint.gitBlobSha,
                    lastInstalledSha = lastInstalledSha,
                    lastInstalledVersionCode = lastInstalledVerCode,
                    githubToken = token
                )
                if (release == null) {
                    _state.value = BridgeUpdateState.Error("APK не найден в релизах или ветке main")
                    return@launch
                }
                lastReleaseMeta = release
                if (release.isUpdateAvailable) {
                    _state.value = BridgeUpdateState.UpdateAvailable(release)
                } else {
                    _state.value = BridgeUpdateState.UpToDate(
                        release.versionName.ifBlank { BuildConfig.VERSION_NAME }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed: ${e.message}", e)
                _state.value = BridgeUpdateState.Error(e.message ?: "Не удалось проверить обновления")
            }
        }
    }

    fun downloadAndInstall(context: Context, release: RemoteApkRelease) {
        if (activeJob?.isActive == true) return
        val appCtx = context.applicationContext
        activeJob = ioScope.launch {
            try {
                _state.value = BridgeUpdateState.Downloading(
                    release = release,
                    progress = null,
                    downloadedBytes = 0L,
                    totalBytes = release.sizeBytes
                )
                val token = PluginDownloader.getSavedToken(appCtx)
                val apkFile = downloadApkFile(appCtx, release, token) { progress, downloaded, total ->
                    _state.value = BridgeUpdateState.Downloading(
                        release = release,
                        progress = progress,
                        downloadedBytes = downloaded,
                        totalBytes = total
                    )
                }
                lastDownloadedApk = apkFile
                lastReleaseMeta = release
                installDownloadedApk(context, apkFile, release)
            } catch (e: Exception) {
                Log.e(TAG, "APK download/install error: ${e.message}", e)
                _state.value = BridgeUpdateState.Error(e.message ?: "Ошибка загрузки APK")
            }
        }
    }

    fun retryInstallIfPermitted(context: Context) {
        val current = _state.value
        val file = when (current) {
            is BridgeUpdateState.NeedsInstallPermission -> current.apkFile
            else -> lastDownloadedApk
        } ?: return
        val release = when (current) {
            is BridgeUpdateState.NeedsInstallPermission -> current.release
            else -> lastReleaseMeta
        } ?: return

        if (file.exists() && canRequestPackageInstalls(context)) {
            ioScope.launch {
                installDownloadedApk(context, file, release)
            }
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    internal fun onAwaitingUserConfirmation() {
        _state.value = BridgeUpdateState.Installing("Ожидание подтверждения системы...")
    }

    internal fun onInstallSucceeded(context: Context? = null) {
        context?.applicationContext?.let { markPendingInstallCompleted(it) }
        _state.value = BridgeUpdateState.Installed
    }

    internal fun onPackageReplaced(context: Context) {
        markPendingInstallCompleted(context.applicationContext)
    }

    internal fun onInstallFailed(message: String) {
        _state.value = BridgeUpdateState.Error(message)
    }

    private fun savePendingInstallSha(context: Context, sha: String) {
        if (sha.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_SHA, sha)
            .apply()
    }

    private fun markPendingInstallCompleted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getString(KEY_PENDING_SHA, null)
        if (!pending.isNullOrBlank()) {
            prefs.edit()
                .putString(KEY_INSTALLED_SHA, pending)
                .putInt(KEY_INSTALLED_VER_CODE, BuildConfig.VERSION_CODE)
                .remove(KEY_PENDING_SHA)
                .apply()
        }
    }

    private suspend fun installDownloadedApk(
        context: Context,
        apkFile: File,
        release: RemoteApkRelease
    ) {
        if (!canRequestPackageInstalls(context)) {
            _state.value = BridgeUpdateState.NeedsInstallPermission(apkFile, release)
            return
        }

        _state.value = BridgeUpdateState.Installing("Подготовка сессии PackageInstaller...")
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val effectiveSha = release.remoteSha.ifBlank { computeGitBlobSha1(apkFile).orEmpty() }
            savePendingInstallSha(appCtx, effectiveSha)

            val packageInstaller = appCtx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setSize(apkFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            var session: PackageInstaller.Session? = null
            try {
                val sessionId = packageInstaller.createSession(params)
                session = packageInstaller.openSession(sessionId)

                session.openWrite("clawd_bridge_update.apk", 0, apkFile.length()).use { out ->
                    FileInputStream(apkFile).use { input ->
                        input.copyTo(out)
                    }
                    session.fsync(out)
                }

                // Nuance #1: Explicit BroadcastReceiver Intent with FLAG_MUTABLE | FLAG_UPDATE_CURRENT
                val callbackIntent = Intent(appCtx, InstallStatusReceiver::class.java)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appCtx,
                    sessionId,
                    callbackIntent,
                    flags
                )

                session.commit(pendingIntent.intentSender)
                session.close()
                session = null
                _state.value = BridgeUpdateState.Installing("Запуск системного установщика...")
            } catch (e: Exception) {
                runCatching { session?.abandon() }
                runCatching { session?.close() }
                throw e
            }
        }
    }

    private suspend fun fetchLatestBridgeRelease(
        currentVersionCode: Int,
        currentVersionName: String,
        localSize: Long,
        localGitSha: String?,
        lastInstalledSha: String?,
        lastInstalledVersionCode: Int,
        githubToken: String
    ): RemoteApkRelease? = withContext(Dispatchers.IO) {
        // Step 1: Check GitHub Releases /latest (filter strictly for .apk, compare semver with VERSION_NAME)
        val releaseCandidate = tryFetchFromReleasesLatest(
            currentVersionName = currentVersionName,
            localSize = localSize,
            token = githubToken
        )
        if (releaseCandidate != null) {
            return@withContext releaseCandidate
        }

        // Step 2: Fallback to main branch (version.json -> app/build.gradle.kts + /contents for APK size & git blob sha)
        tryFetchFromMainBranch(
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            localSize = localSize,
            localGitSha = localGitSha,
            lastInstalledSha = lastInstalledSha,
            lastInstalledVersionCode = lastInstalledVersionCode,
            token = githubToken
        )
    }

    private fun tryFetchFromReleasesLatest(
        currentVersionName: String,
        localSize: Long,
        token: String
    ): RemoteApkRelease? {
        val cacheBust = System.currentTimeMillis()
        val url = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest?_=$cacheBust"
        val body = httpGetText(url, token = token, acceptJson = true, noCache = true) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tagName = json.optString("tag_name").trim()
        val assets = json.optJSONArray("assets") ?: return null

        var bestApkName = ""
        var bestUrl = ""
        var bestSize = -1L

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val dl = asset.optString("browser_download_url")
            if (name.lowercase(Locale.US).endsWith(".apk") && dl.isNotBlank()) {
                if (bestApkName.isEmpty() || name.contains("clawd", ignoreCase = true)) {
                    bestApkName = name
                    bestUrl = dl
                    bestSize = asset.optLong("size", -1L)
                }
            }
        }

        if (bestUrl.isBlank()) return null

        // Nuance #3: Releases API has tag_name semver (no integer versionCode) -> compare semver vs VERSION_NAME
        val cleanRemoteVer = normalizeVersionLabel(tagName)
        val semverCmp = if (cleanRemoteVer.isNotBlank()) {
            compareSemver(cleanRemoteVer, currentVersionName)
        } else 0
        val sizeRebuild = semverCmp == 0 && bestSize > 0L && localSize > 0L && bestSize != localSize
        val isNewer = when {
            cleanRemoteVer.isBlank() -> true
            semverCmp > 0 -> true
            sizeRebuild -> true
            else -> false
        }

        return RemoteApkRelease(
            sourceLabel = "GitHub Releases ($tagName)",
            versionCode = null,
            versionName = cleanRemoteVer.ifBlank { tagName },
            apkName = bestApkName,
            downloadUrl = bestUrl,
            sizeBytes = bestSize,
            remoteSha = "",
            isUpdateAvailable = isNewer,
            isRebuildWithSameVersion = sizeRebuild
        )
    }

    private fun tryFetchFromMainBranch(
        currentVersionCode: Int,
        currentVersionName: String,
        localSize: Long,
        localGitSha: String?,
        lastInstalledSha: String?,
        lastInstalledVersionCode: Int,
        token: String
    ): RemoteApkRelease? {
        val cacheBust = System.currentTimeMillis()
        // Nuance #2: Cache-buster query & Cache-Control: no-cache for version.json
        val versionJsonUrl = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/version.json?t=$cacheBust"
        val versionJsonText = httpGetText(versionJsonUrl, token = token, acceptJson = false, noCache = true)
        var parsedMeta = versionJsonText?.let { parseVersionJson(it) }

        // Nuance #2: Fallback parsing MUST read app/build.gradle.kts (NOT root build.gradle.kts)
        if (parsedMeta == null) {
            val gradleUrl = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/app/build.gradle.kts?t=$cacheBust"
            val gradleText = httpGetText(gradleUrl, token = token, acceptJson = false, noCache = true)
            if (gradleText != null) {
                parsedMeta = parseGradleVersionInfo(gradleText)
            }
        }

        val targetApkName = parsedMeta?.apkName?.ifBlank { DEFAULT_APK_NAME } ?: DEFAULT_APK_NAME

        var foundApkName = targetApkName
        var foundDownloadUrl = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/$targetApkName?t=$cacheBust"
        var foundSize = -1L
        var foundSha = ""

        // Step A: Direct GitHub Contents API check on exact targetApkName (returns exact size + git blob sha)
        val singleContentUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$targetApkName?ref=main&_=$cacheBust"
        httpGetText(singleContentUrl, token = token, acceptJson = true, noCache = true)?.let { body ->
            runCatching {
                val obj = JSONObject(body)
                if (obj.optString("type") == "file") {
                    foundApkName = obj.optString("name", targetApkName)
                    foundSize = obj.optLong("size", -1L)
                    foundSha = obj.optString("sha", "").trim()
                    val dl = obj.optString("download_url")
                    if (dl.isNotBlank()) foundDownloadUrl = appendCacheBusterIfSafe(dl, cacheBust)
                }
            }
        }

        // Step B: Fallback to root /contents?ref=main if exact filename wasn't matched
        if (foundSize <= 0L) {
            val contentsRootUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents?ref=main&_=$cacheBust"
            val contentsText = httpGetText(contentsRootUrl, token = token, acceptJson = true, noCache = true)
            if (contentsText != null) {
                runCatching {
                    val arr = JSONArray(contentsText)
                    val apkCandidates = mutableListOf<JSONObject>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val name = item.optString("name")
                        if (item.optString("type") == "file" && name.lowercase(Locale.US).endsWith(".apk")) {
                            apkCandidates.add(item)
                        }
                    }
                    val chosen = apkCandidates.firstOrNull { it.optString("name").equals(targetApkName, ignoreCase = true) }
                        ?: apkCandidates.firstOrNull { it.optString("name").contains("clawd", ignoreCase = true) }
                        ?: apkCandidates.firstOrNull()
                    if (chosen != null) {
                        foundApkName = chosen.optString("name", targetApkName)
                        val dl = chosen.optString("download_url")
                        if (dl.isNotBlank()) foundDownloadUrl = appendCacheBusterIfSafe(dl, cacheBust)
                        foundSize = chosen.optLong("size", -1L)
                        foundSha = chosen.optString("sha", "").trim()
                    }
                }
            }
        }

        val remoteCode = parsedMeta?.versionCode
        val remoteName = parsedMeta?.versionName?.let(::normalizeVersionLabel) ?: ""

        if (remoteCode == null && remoteName.isBlank() && foundSize <= 0L) {
            return null
        }

        val evaluation = evaluateMainBranchUpdate(
            remoteCode = remoteCode,
            remoteName = remoteName,
            remoteSize = foundSize,
            remoteSha = foundSha,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            localSize = localSize,
            localGitSha = localGitSha,
            lastInstalledSha = lastInstalledSha,
            lastInstalledVersionCode = lastInstalledVersionCode
        )

        return RemoteApkRelease(
            sourceLabel = "Ветка main",
            versionCode = remoteCode,
            versionName = remoteName.ifBlank { if (remoteCode != null) "build $remoteCode" else "main" },
            apkName = foundApkName,
            downloadUrl = foundDownloadUrl,
            sizeBytes = foundSize,
            remoteSha = foundSha,
            isUpdateAvailable = evaluation.isUpdateAvailable,
            isRebuildWithSameVersion = evaluation.matchedByArtifactDiff
        )
    }

    private fun appendCacheBusterIfSafe(url: String, ts: Long): String {
        if (url.contains("token=", ignoreCase = true) || url.contains("Signature=", ignoreCase = true)) {
            return url
        }
        val sep = if (url.contains('?')) '&' else '?'
        return "${url}${sep}t=$ts"
    }

    private fun readLocalApkFingerprint(context: Context): LocalApkFingerprint {
        val sourcePath = runCatching { context.applicationInfo.sourceDir }.getOrNull().orEmpty()
        if (sourcePath.isBlank()) return LocalApkFingerprint(-1L, null)
        val file = File(sourcePath)
        if (!file.exists()) return LocalApkFingerprint(-1L, null)
        val size = file.length()
        val sha = computeGitBlobSha1(file)
        return LocalApkFingerprint(sizeBytes = size, gitBlobSha = sha)
    }

    private fun downloadApkFile(
        context: Context,
        release: RemoteApkRelease,
        token: String,
        onProgress: (Float?, Long, Long) -> Unit
    ): File {
        val dir = File(context.cacheDir, UPDATES_SUBDIR).apply {
            if (!exists()) mkdirs()
        }
        dir.listFiles()?.forEach { runCatching { it.delete() } }

        val safeName = PluginDownloader.sanitizeFileName(release.apkName.ifBlank { DEFAULT_APK_NAME })
        val partFile = File(dir, "$safeName.part")
        val targetFile = File(dir, safeName)

        val headers = buildMap {
            put("User-Agent", "${BridgeHttpClient.GITHUB_USER_AGENT}/${BuildConfig.VERSION_NAME}")
            if (token.isNotBlank()) put("Authorization", "Bearer ${token.trim()}")
        }
        BridgeHttpClient.downloadFile(
            url = release.downloadUrl,
            partFile = partFile,
            expectedSizeBytes = release.sizeBytes,
            headers = headers,
            maxRetries = 2
        ) { downloaded, totalBytes ->
            val progress = if (totalBytes > 0L) {
                (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else null
            onProgress(progress, downloaded, totalBytes)
        }

        if (targetFile.exists()) targetFile.delete()
        if (!partFile.renameTo(targetFile)) {
            partFile.copyTo(targetFile, overwrite = true)
            partFile.delete()
        }
        return targetFile
    }

    private fun httpGetText(
        targetUrl: String,
        token: String,
        acceptJson: Boolean,
        noCache: Boolean
    ): String? {
        val headers = buildMap {
            put("User-Agent", "${BridgeHttpClient.GITHUB_USER_AGENT}/${BuildConfig.VERSION_NAME}")
            if (acceptJson) put("Accept", "application/vnd.github+json")
            if (noCache) {
                put("Cache-Control", "no-cache, no-store, must-revalidate")
                put("Pragma", "no-cache")
            }
            if (token.isNotBlank()) put("Authorization", "Bearer ${token.trim()}")
        }
        return runCatching {
            val (code, text) = BridgeHttpClient.getTextWithCode(
                url = targetUrl,
                useCatalogClient = false,
                headers = headers,
                maxRetries = 2
            )
            if (code in 200..299) text else null
        }.getOrNull()
    }

    internal data class ParsedRemoteVersion(
        val versionCode: Int?,
        val versionName: String,
        val apkName: String?
    )

    internal fun parseVersionJson(jsonText: String): ParsedRemoteVersion? {
        val code = Regex(""""versionCode"\s*:\s*(\d+)""").find(jsonText)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it > 0 }
        val name = Regex(""""versionName"\s*:\s*"([^"]*)"""").find(jsonText)
            ?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val apkName = Regex(""""apkName"\s*:\s*"([^"]*)"""").find(jsonText)
            ?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
        return if (code == null && name.isBlank()) null
        else ParsedRemoteVersion(versionCode = code, versionName = name, apkName = apkName)
    }

    internal fun parseGradleVersionInfo(gradleText: String): ParsedRemoteVersion? {
        val codeMatch = Regex("""versionCode\s*=?\s*(\d+)""").find(gradleText)
        val nameMatch = Regex("""versionName\s*=?\s*["']([^"']+)["']""").find(gradleText)
        val code = codeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val name = nameMatch?.groupValues?.getOrNull(1)?.trim() ?: ""
        return if (code == null && name.isBlank()) null
        else ParsedRemoteVersion(versionCode = code, versionName = name, apkName = null)
    }

    internal fun normalizeVersionLabel(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V").trim()
    }

    /**
     * Pure-JVM semver comparison: returns > 0 if remote > current, 0 if equal, < 0 if remote < current.
     */
    internal fun compareSemver(remoteRaw: String, currentRaw: String): Int {
        val rParts = extractSemverSegments(remoteRaw)
        val cParts = extractSemverSegments(currentRaw)
        val len = maxOf(rParts.size, cParts.size)
        for (i in 0 until len) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r != c) return r.compareTo(c)
        }
        return 0
    }

    private fun extractSemverSegments(version: String): List<Int> {
        val cleaned = normalizeVersionLabel(version)
            .substringBefore('-')
            .substringBefore('+')
        val nums = Regex("""\d+""").findAll(cleaned).mapNotNull { it.value.toIntOrNull() }.toList()
        return nums.ifEmpty { listOf(0) }
    }

    internal data class LocalApkFingerprint(
        val sizeBytes: Long,
        val gitBlobSha: String?
    )

    internal data class UpdateEvaluation(
        val isUpdateAvailable: Boolean,
        val matchedByArtifactDiff: Boolean
    )

    internal fun computeGitBlobSha1(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val len = file.length()
            val md = MessageDigest.getInstance("SHA-1")
            md.update("blob $len\u0000".toByteArray(Charsets.UTF_8))
            FileInputStream(file).use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read == -1) break
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    internal fun evaluateMainBranchUpdate(
        remoteCode: Int?,
        remoteName: String,
        remoteSize: Long,
        remoteSha: String,
        currentVersionCode: Int,
        currentVersionName: String,
        localSize: Long,
        localGitSha: String?,
        lastInstalledSha: String?,
        lastInstalledVersionCode: Int
    ): UpdateEvaluation {
        if (remoteCode != null) {
            if (remoteCode > currentVersionCode) {
                return UpdateEvaluation(isUpdateAvailable = true, matchedByArtifactDiff = false)
            }
            if (remoteCode < currentVersionCode) {
                return UpdateEvaluation(isUpdateAvailable = false, matchedByArtifactDiff = false)
            }
        } else if (remoteName.isNotBlank()) {
            val cmp = compareSemver(remoteName, currentVersionName)
            if (cmp > 0) {
                return UpdateEvaluation(isUpdateAvailable = true, matchedByArtifactDiff = false)
            }
            if (cmp < 0) {
                return UpdateEvaluation(isUpdateAvailable = false, matchedByArtifactDiff = false)
            }
        }

        // Same versionCode / versionName -> detect updated APK on main via exact byte size or git blob sha
        if (remoteSha.isNotBlank() &&
            remoteSha.equals(lastInstalledSha, ignoreCase = true) &&
            lastInstalledVersionCode == currentVersionCode
        ) {
            return UpdateEvaluation(isUpdateAvailable = false, matchedByArtifactDiff = false)
        }

        val sizeDiffers = remoteSize > 0L && localSize > 0L && remoteSize != localSize
        val shaDiffers = remoteSha.isNotBlank() &&
            !localGitSha.isNullOrBlank() &&
            !remoteSha.equals(localGitSha, ignoreCase = true)

        return if (sizeDiffers || shaDiffers) {
            UpdateEvaluation(isUpdateAvailable = true, matchedByArtifactDiff = true)
        } else {
            UpdateEvaluation(isUpdateAvailable = false, matchedByArtifactDiff = false)
        }
    }
}
