package com.example.updater

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.network.BridgeHttpClient
import com.example.network.CooldownInfo
import com.example.network.GitHubRateLimitStatus
import com.example.network.HostCooldownGuard
import com.example.network.NetworkCategory
import com.example.network.NetworkLogStore
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class RemoteApkRelease(
    val sourceLabel: String,
    val versionCode: Int?,
    val versionName: String,
    val apkName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val remoteSha: String = "",
    val isUpdateAvailable: Boolean,
    val isRebuildWithSameVersion: Boolean = false,
    val commitShaShort: String = "",
    val commitDateIso: String = "",
    val commitAuthor: String = "",
    val commitMessage: String = "",
    val changelogItems: List<String> = emptyList(),
    val checkedAtMs: Long = System.currentTimeMillis()
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
    data class WaitingUserConfirmation(
        val release: RemoteApkRelease?,
        val sessionId: Int = -1,
        val statusText: String = "Ожидание подтверждения системы..."
    ) : BridgeUpdateState()
    data object Installed : BridgeUpdateState()
    data class Error(val message: String) : BridgeUpdateState()
}

/**
 * Hybrid In-App APK Updater for https://github.com/tsobbb/Clawd-Bridge.
 * 1) Checks /releases/latest for .apk assets (comparing semver against BuildConfig.VERSION_NAME).
 * 2) Falls back to main branch: raw/main/version.json -> raw/main/app/build.gradle.kts,
 *    plus /contents metadata and commit/changelog details.
 * 3) Supports HTTP ETag (If-None-Match -> 304 Not Modified) for 10-minute background polling
 *    with 0 GitHub rate-limit waste and deduplicated update notifications.
 * 4) Installs natively via PackageInstaller.Session + InstallStatusReceiver.
 */
object BridgeAppUpdater {

    private const val TAG = "BridgeAppUpdater"
    const val REPO_OWNER = "tsobbb"
    const val REPO_NAME = "Clawd-Bridge"
    const val REPO_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"
    const val AUTO_CHECK_INTERVAL_MS = 10 * 60 * 1000L
    const val EXTRA_OPEN_UPDATE_SHEET = "open_update_sheet"

    private const val UPDATE_CHANNEL_ID = "clawd_bridge_updates_channel"
    private const val UPDATE_CHANNEL_NAME = "Обновления Clawd Bridge"
    private const val UPDATE_NOTIFICATION_ID = 44445

    private const val DEFAULT_APK_NAME = "ClawdBridge.apk"
    private const val UPDATES_SUBDIR = "updates"
    private const val PREFS_NAME = "clawd_updater_prefs"
    private const val KEY_PENDING_SHA = "pending_install_sha"
    private const val KEY_INSTALLED_SHA = "last_installed_sha"
    private const val KEY_INSTALLED_VER_CODE = "last_installed_version_code"
    private const val KEY_LAST_CHECK_AT_MS = "last_check_at_ms"
    private const val KEY_LAST_NOTIFIED_KEY = "last_notified_update_key"
    private const val KEY_ETAG_RELEASES = "etag_releases_latest"
    private const val KEY_ETAG_APK_CONTENTS = "etag_main_apk_contents"
    private const val KEY_ETAG_VERSION_JSON = "etag_main_version_json"

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null

    private val _state = MutableStateFlow<BridgeUpdateState>(BridgeUpdateState.Idle)
    val state: StateFlow<BridgeUpdateState> = _state.asStateFlow()

    @Volatile
    private var lastDownloadedApk: File? = null

    @Volatile
    private var lastReleaseMeta: RemoteApkRelease? = null

    fun checkForUpdates(context: Context, isBackground: Boolean = false) {
        val cur = _state.value
        if (cur is BridgeUpdateState.Downloading ||
            cur is BridgeUpdateState.Installing ||
            cur is BridgeUpdateState.WaitingUserConfirmation
        ) {
            return
        }
        if (activeJob?.isActive == true) return
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (isBackground) {
            val lastCheck = prefs.getLong(KEY_LAST_CHECK_AT_MS, 0L)
            if (lastCheck > 0L && (System.currentTimeMillis() - lastCheck) < 60_000L) {
                return
            }
        }

        activeJob = ioScope.launch {
            if (!isBackground) {
                _state.value = BridgeUpdateState.Checking
            }
            try {
                val token = PluginDownloader.getSavedToken(appCtx)
                val localFingerprint = readLocalApkFingerprint(appCtx)
                val lastInstalledSha = prefs.getString(KEY_INSTALLED_SHA, null)
                val lastInstalledVerCode = prefs.getInt(KEY_INSTALLED_VER_CODE, -1)

                val release = fetchLatestBridgeRelease(
                    prefs = prefs,
                    useEtag = isBackground,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    localSize = localFingerprint.sizeBytes,
                    localGitSha = localFingerprint.gitBlobSha,
                    lastInstalledSha = lastInstalledSha,
                    lastInstalledVersionCode = lastInstalledVerCode,
                    githubToken = token
                )
                prefs.edit().putLong(KEY_LAST_CHECK_AT_MS, System.currentTimeMillis()).apply()

                if (release == null) {
                    if (!isBackground) {
                        val rateMsg = formatGitHubRateLimitMessage(
                            rateLimit = HostCooldownGuard.getLastGitHubRateLimit(),
                            cooldown = HostCooldownGuard.checkCooldown(NetworkCategory.GITHUB_API.cooldownBucket)
                        )
                        _state.value = BridgeUpdateState.Error(
                            rateMsg ?: "APK не найден в релизах или ветке main"
                        )
                    }
                    return@launch
                }

                lastReleaseMeta = release
                if (release.isUpdateAvailable) {
                    _state.value = BridgeUpdateState.UpdateAvailable(release)
                    if (isBackground) {
                        postUpdateNotificationIfNew(appCtx, prefs, release)
                    }
                } else {
                    _state.value = BridgeUpdateState.UpToDate(
                        release.versionName.ifBlank { BuildConfig.VERSION_NAME }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed (bg=$isBackground): ${e.message}", e)
                if (!isBackground) {
                    val rateMsg = formatGitHubRateLimitMessage(
                        rateLimit = HostCooldownGuard.getLastGitHubRateLimit(),
                        cooldown = HostCooldownGuard.checkCooldown(NetworkCategory.GITHUB_API.cooldownBucket)
                    )
                    _state.value = BridgeUpdateState.Error(
                        rateMsg ?: e.message ?: "Не удалось проверить обновления"
                    )
                }
            }
        }
    }

    internal fun formatGitHubRateLimitMessage(
        rateLimit: GitHubRateLimitStatus?,
        cooldown: CooldownInfo?,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        val isRateLimited = (cooldown != null && cooldown.untilMs > nowMs) ||
            (rateLimit != null && (rateLimit.httpCode == 403 || rateLimit.httpCode == 429) &&
                (rateLimit.remaining == null || rateLimit.remaining == 0))
        if (!isRateLimited) return null

        val resetEpochSec = cooldown?.rateLimitResetEpochSec ?: rateLimit?.resetEpochSec
        val resetMs = when {
            resetEpochSec != null && resetEpochSec > 0L -> resetEpochSec * 1000L
            cooldown != null -> cooldown.untilMs
            rateLimit?.cooldownUntilMs != null -> rateLimit.cooldownUntilMs
            else -> nowMs + HostCooldownGuard.DEFAULT_COOLDOWN_MS
        }
        val formattedTime = NetworkLogStore.formatTimestampHHmm(resetMs)
        return "Лимит GitHub API (сброс в $formattedTime). Добавьте read-only Token во вкладке «Плагин»."
    }

    fun checkIfIntervalElapsed(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_CHECK_AT_MS, 0L)
        if ((System.currentTimeMillis() - last) >= AUTO_CHECK_INTERVAL_MS) {
            checkForUpdates(context, isBackground = true)
        }
    }

    fun downloadAndInstall(context: Context, release: RemoteApkRelease) {
        val cur = _state.value
        if (cur is BridgeUpdateState.Downloading ||
            cur is BridgeUpdateState.Installing ||
            cur is BridgeUpdateState.WaitingUserConfirmation
        ) {
            return
        }
        if (activeJob?.isActive == true) return
        val appCtx = context.applicationContext
        lastReleaseMeta = release
        activeJob = ioScope.launch {
            try {
                val cachedApk = findValidCachedApk(appCtx, release)
                val apkFile = if (cachedApk != null) {
                    Log.i(TAG, "Using valid cached APK for release ${release.remoteSha.ifBlank { release.apkName }}")
                    cachedApk
                } else {
                    _state.value = BridgeUpdateState.Downloading(
                        release = release,
                        progress = null,
                        downloadedBytes = 0L,
                        totalBytes = release.sizeBytes
                    )
                    val token = PluginDownloader.getSavedToken(appCtx)
                    downloadApkFile(appCtx, release, token) { progress, downloaded, total ->
                        _state.value = BridgeUpdateState.Downloading(
                            release = release,
                            progress = progress,
                            downloadedBytes = downloaded,
                            totalBytes = total
                        )
                    }
                }
                lastDownloadedApk = apkFile
                lastReleaseMeta = release
                installDownloadedApk(appCtx, apkFile, release)
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

    internal fun onAwaitingUserConfirmation(sessionId: Int = -1) {
        _state.value = BridgeUpdateState.WaitingUserConfirmation(
            release = lastReleaseMeta,
            sessionId = sessionId
        )
    }

    internal fun onInstallSucceeded(context: Context? = null) {
        context?.applicationContext?.let { markPendingInstallCompleted(it) }
        _state.value = BridgeUpdateState.Installed
    }

    internal fun onPackageReplaced(context: Context) {
        markPendingInstallCompleted(context.applicationContext)
    }

    internal fun onInstallAborted() {
        _state.value = resolvePostInstallFailureState(
            current = _state.value,
            lastRelease = lastReleaseMeta,
            isAborted = true,
            errorMessage = null
        )
    }

    internal fun onInstallFailed(message: String) {
        _state.value = resolvePostInstallFailureState(
            current = _state.value,
            lastRelease = lastReleaseMeta,
            isAborted = false,
            errorMessage = message
        )
    }

    internal fun resolvePostInstallFailureState(
        current: BridgeUpdateState,
        lastRelease: RemoteApkRelease?,
        isAborted: Boolean,
        errorMessage: String?
    ): BridgeUpdateState {
        val wasAwaitingOrInstalling = current is BridgeUpdateState.WaitingUserConfirmation ||
            current is BridgeUpdateState.Installing
        if ((isAborted || wasAwaitingOrInstalling) && lastRelease != null) {
            return BridgeUpdateState.UpdateAvailable(lastRelease)
        }
        if (isAborted && current is BridgeUpdateState.WaitingUserConfirmation && current.release != null) {
            return BridgeUpdateState.UpdateAvailable(current.release)
        }
        return BridgeUpdateState.Error(errorMessage?.takeIf { it.isNotBlank() } ?: "Ошибка установки")
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

    private fun postUpdateNotificationIfNew(
        context: Context,
        prefs: android.content.SharedPreferences,
        release: RemoteApkRelease
    ) {
        val uniqueKey = "${release.remoteSha}|${release.versionName}|${release.sizeBytes}"
        if (prefs.getString(KEY_LAST_NOTIFIED_KEY, null) == uniqueKey) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(UPDATE_CHANNEL_ID) == null
        ) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                UPDATE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о новых сборках Clawd Bridge на GitHub"
            }
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_UPDATE_SHEET, true)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPending = PendingIntent.getActivity(context, 441, openIntent, piFlags)

        val sizeText = if (release.sizeBytes > 0) {
            String.format(Locale.US, " • %.1f МБ", release.sizeBytes / (1024.0 * 1024.0))
        } else ""
        val title = "Доступно обновление Clawd Bridge ${release.versionName}$sizeText"
        val details = buildString {
            if (release.commitMessage.isNotBlank()) {
                append(release.commitMessage.lineSequence().firstOrNull().orEmpty())
            }
            if (release.changelogItems.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                release.changelogItems.take(3).forEachIndexed { idx, line ->
                    if (idx > 0) append("\n")
                    append("• ").append(line)
                }
            }
            if (isEmpty()) {
                append("Нажмите «Обновить», чтобы посмотреть изменения и установить сборку.")
            }
        }

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(details.lineSequence().firstOrNull() ?: details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setContentIntent(contentPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(0, "Обновить", contentPending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION_ID, notification)
            prefs.edit().putString(KEY_LAST_NOTIFIED_KEY, uniqueKey).apply()
        }.onFailure {
            Log.w(TAG, "Failed to post update notification: ${it.message}")
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
            runCatching {
                packageInstaller.mySessions.forEach { existing ->
                    runCatching { packageInstaller.abandonSession(existing.sessionId) }
                }
            }
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

                val callbackIntent = Intent(appCtx, InstallStatusReceiver::class.java).apply {
                    putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
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
                _state.value = BridgeUpdateState.WaitingUserConfirmation(
                    release = release,
                    sessionId = sessionId
                )
            } catch (e: Exception) {
                runCatching { session?.abandon() }
                runCatching { session?.close() }
                throw e
            }
        }
    }

    private suspend fun fetchLatestBridgeRelease(
        prefs: android.content.SharedPreferences,
        useEtag: Boolean,
        currentVersionCode: Int,
        currentVersionName: String,
        localSize: Long,
        localGitSha: String?,
        lastInstalledSha: String?,
        lastInstalledVersionCode: Int,
        githubToken: String
    ): RemoteApkRelease? = withContext(Dispatchers.IO) {
        val releaseCandidate = tryFetchFromReleasesLatest(
            prefs = prefs,
            useEtag = useEtag,
            currentVersionName = currentVersionName,
            localSize = localSize,
            token = githubToken
        )
        if (releaseCandidate != null) {
            return@withContext releaseCandidate
        }

        tryFetchFromMainBranch(
            prefs = prefs,
            useEtag = useEtag,
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
        prefs: android.content.SharedPreferences,
        useEtag: Boolean,
        currentVersionName: String,
        localSize: Long,
        token: String
    ): RemoteApkRelease? {
        val url = if (useEtag) {
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        } else {
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest?_=${System.currentTimeMillis()}"
        }
        val prevEtag = if (useEtag) prefs.getString(KEY_ETAG_RELEASES, null) else null
        val res = httpGetWithEtag(url, token = token, acceptJson = true, ifNoneMatch = prevEtag, noCache = !useEtag)
        if (res.notModified) {
            return lastReleaseMeta?.takeIf { it.sourceLabel.startsWith("GitHub Releases") }
        }
        res.etag?.let { prefs.edit().putString(KEY_ETAG_RELEASES, it).apply() }
        val body = res.body ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val tagName = json.optString("tag_name").trim()
        val releaseName = json.optString("name").trim()
        val releaseBody = json.optString("body").trim()
        val publishedAt = json.optString("published_at").trim()
        val authorLogin = json.optJSONObject("author")?.optString("login").orEmpty()
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

        val changelog = buildChangelogItems(
            versionJsonChangelog = emptyList(),
            commitMessages = emptyList(),
            releaseBody = releaseBody
        )

        return RemoteApkRelease(
            sourceLabel = "GitHub Releases ($tagName)",
            versionCode = null,
            versionName = cleanRemoteVer.ifBlank { tagName },
            apkName = bestApkName,
            downloadUrl = bestUrl,
            sizeBytes = bestSize,
            remoteSha = "",
            isUpdateAvailable = isNewer,
            isRebuildWithSameVersion = sizeRebuild,
            commitShaShort = tagName.take(12),
            commitDateIso = publishedAt,
            commitAuthor = authorLogin,
            commitMessage = releaseName.ifBlank { "Релиз $tagName" },
            changelogItems = changelog
        )
    }

    private fun tryFetchFromMainBranch(
        prefs: android.content.SharedPreferences,
        useEtag: Boolean,
        currentVersionCode: Int,
        currentVersionName: String,
        localSize: Long,
        localGitSha: String?,
        lastInstalledSha: String?,
        lastInstalledVersionCode: Int,
        token: String
    ): RemoteApkRelease? {
        val cacheBust = System.currentTimeMillis()
        val versionJsonUrl = if (useEtag) {
            "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/version.json"
        } else {
            "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/version.json?t=$cacheBust"
        }
        val prevVerEtag = if (useEtag) prefs.getString(KEY_ETAG_VERSION_JSON, null) else null
        val versionRes = httpGetWithEtag(
            versionJsonUrl,
            token = token,
            acceptJson = false,
            ifNoneMatch = prevVerEtag,
            noCache = !useEtag
        )
        if (!versionRes.notModified) {
            versionRes.etag?.let { prefs.edit().putString(KEY_ETAG_VERSION_JSON, it).apply() }
        }
        var parsedMeta = versionRes.body?.let { parseVersionJson(it) }

        if (parsedMeta == null && !versionRes.notModified) {
            val gradleUrl = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/app/build.gradle.kts?t=$cacheBust"
            val gradleRes = httpGetWithEtag(gradleUrl, token = token, acceptJson = false, ifNoneMatch = null, noCache = true)
            gradleRes.body?.let { parsedMeta = parseGradleVersionInfo(it) }
        }

        val targetApkName = parsedMeta?.apkName?.ifBlank { DEFAULT_APK_NAME } ?: DEFAULT_APK_NAME

        var foundApkName = targetApkName
        var foundDownloadUrl = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/$targetApkName?t=$cacheBust"
        var foundSize = -1L
        var foundSha = ""

        val singleContentUrl = if (useEtag) {
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$targetApkName?ref=main"
        } else {
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$targetApkName?ref=main&_=$cacheBust"
        }
        val prevApkEtag = if (useEtag) prefs.getString(KEY_ETAG_APK_CONTENTS, null) else null
        val apkContentRes = httpGetWithEtag(
            singleContentUrl,
            token = token,
            acceptJson = true,
            ifNoneMatch = prevApkEtag,
            noCache = !useEtag
        )
        if (apkContentRes.notModified && versionRes.notModified && lastReleaseMeta != null) {
            return lastReleaseMeta
        }
        if (!apkContentRes.notModified) {
            apkContentRes.etag?.let { prefs.edit().putString(KEY_ETAG_APK_CONTENTS, it).apply() }
        }

        apkContentRes.body?.let { body ->
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

        if (foundSize <= 0L) {
            val contentsRootUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents?ref=main&_=$cacheBust"
            val contentsRes = httpGetWithEtag(contentsRootUrl, token = token, acceptJson = true, ifNoneMatch = null, noCache = true)
            contentsRes.body?.let { contentsText ->
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

        var commitShaShort = ""
        var commitDateIso = ""
        var commitAuthor = ""
        var commitMessage = ""
        val recentCommitMessages = mutableListOf<String>()

        if (evaluation.isUpdateAvailable || !useEtag) {
            // Fetch latest commit touching the APK
            val apkCommitUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/commits?path=$foundApkName&sha=main&per_page=1"
            httpGetWithEtag(apkCommitUrl, token = token, acceptJson = true, ifNoneMatch = null, noCache = false).body?.let { jsonBody ->
                runCatching {
                    val arr = JSONArray(jsonBody)
                    val first = arr.optJSONObject(0)
                    if (first != null) {
                        commitShaShort = first.optString("sha").take(7)
                        val commitObj = first.optJSONObject("commit")
                        commitMessage = commitObj?.optString("message").orEmpty().trim()
                        val authorObj = commitObj?.optJSONObject("author")
                        commitAuthor = authorObj?.optString("name").orEmpty().trim()
                        commitDateIso = authorObj?.optString("date").orEmpty().trim()
                    }
                }
            }

            // Fetch recent commits on main for short important changelog bullets
            val recentCommitsUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/commits?sha=main&per_page=6"
            httpGetWithEtag(recentCommitsUrl, token = token, acceptJson = true, ifNoneMatch = null, noCache = false).body?.let { jsonBody ->
                runCatching {
                    val arr = JSONArray(jsonBody)
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i)?.optJSONObject("commit")?.optString("message").orEmpty().trim()
                        if (c.isNotBlank()) recentCommitMessages.add(c)
                    }
                    if (commitMessage.isBlank() && arr.length() > 0) {
                        val first = arr.optJSONObject(0)
                        commitShaShort = first.optString("sha").take(7)
                        val commitObj = first.optJSONObject("commit")
                        commitMessage = commitObj?.optString("message").orEmpty().trim()
                        commitAuthor = commitObj?.optJSONObject("author")?.optString("name").orEmpty().trim()
                        commitDateIso = commitObj?.optJSONObject("author")?.optString("date").orEmpty().trim()
                    }
                }
            }
        }

        val changelog = buildChangelogItems(
            versionJsonChangelog = parsedMeta?.changelog.orEmpty(),
            commitMessages = recentCommitMessages,
            releaseBody = commitMessage
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
            isRebuildWithSameVersion = evaluation.matchedByArtifactDiff,
            commitShaShort = commitShaShort,
            commitDateIso = commitDateIso,
            commitAuthor = commitAuthor,
            commitMessage = commitMessage,
            changelogItems = changelog
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

    internal fun findValidCachedApk(context: Context, release: RemoteApkRelease): File? {
        val dir = File(context.cacheDir, UPDATES_SUBDIR)
        return findValidCachedApkInDir(dir, release)
    }

    internal fun findValidCachedApkInDir(dir: File, release: RemoteApkRelease): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        val safeName = PluginDownloader.sanitizeFileName(release.apkName.ifBlank { DEFAULT_APK_NAME })
        val candidate = File(dir, safeName)
        if (!candidate.exists() || !candidate.isFile || candidate.length() < 100_000L) return null
        if (release.sizeBytes > 0L && candidate.length() != release.sizeBytes) return null
        if (!hasZipMagicHeader(candidate)) return null

        if (release.remoteSha.isNotBlank()) {
            val localSha = computeGitBlobSha1(candidate) ?: return null
            return if (localSha.equals(release.remoteSha.trim(), ignoreCase = true)) candidate else null
        }
        return if (release.sizeBytes > 0L && candidate.length() == release.sizeBytes) candidate else null
    }

    private fun hasZipMagicHeader(file: File): Boolean {
        return runCatching {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read == 4 &&
                    header[0] == 0x50.toByte() &&
                    header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() &&
                    header[3] == 0x04.toByte()
            }
        }.getOrDefault(false)
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
        val safeName = PluginDownloader.sanitizeFileName(release.apkName.ifBlank { DEFAULT_APK_NAME })
        val partFile = File(dir, "$safeName.part")
        val targetFile = File(dir, safeName)
        dir.listFiles()?.forEach { existing ->
            if (existing.name != safeName) {
                runCatching { existing.delete() }
            }
        }

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

    private fun httpGetWithEtag(
        targetUrl: String,
        token: String,
        acceptJson: Boolean,
        ifNoneMatch: String?,
        noCache: Boolean
    ): BridgeHttpClient.ConditionalHttpResult {
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
            BridgeHttpClient.getTextWithEtag(
                url = targetUrl,
                ifNoneMatch = ifNoneMatch,
                headers = headers,
                maxRetries = 1
            )
        }.getOrElse {
            BridgeHttpClient.ConditionalHttpResult(-1, null, ifNoneMatch, notModified = false)
        }
    }

    internal data class ParsedRemoteVersion(
        val versionCode: Int?,
        val versionName: String,
        val apkName: String?,
        val changelog: List<String> = emptyList()
    )

    internal fun parseVersionJson(jsonText: String): ParsedRemoteVersion? {
        runCatching {
            val obj = JSONObject(jsonText)
            val code = obj.optInt("versionCode", -1).takeIf { it > 0 }
            val name = obj.optString("versionName").trim()
            val apkName = obj.optString("apkName").trim().ifBlank { null }
            val arr = obj.optJSONArray("changelog")
            val changelogList = if (arr != null) {
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i)?.trim()?.takeIf { it.isNotBlank() }
                }
            } else {
                emptyList()
            }
            if (code != null || name.isNotBlank()) {
                return ParsedRemoteVersion(code, name, apkName, changelogList)
            }
        }

        val code = Regex(""""versionCode"\s*:\s*(\d+)""").find(jsonText)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it > 0 }
        val name = Regex(""""versionName"\s*:\s*"([^"]*)"""").find(jsonText)
            ?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val apkName = Regex(""""apkName"\s*:\s*"([^"]*)"""").find(jsonText)
            ?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
        val changelogRaw = Regex(""""changelog"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(jsonText)?.groupValues?.getOrNull(1).orEmpty()
        val changelogFallback = if (changelogRaw.isNotBlank()) {
            Regex(""""((?:\\.|[^"\\])*)"""")
                .findAll(changelogRaw)
                .map { m -> m.groupValues[1].replace("\\\"", "\"").trim() }
                .filter { it.isNotBlank() }
                .toList()
        } else emptyList()
        return if (code == null && name.isBlank()) null
        else ParsedRemoteVersion(
            versionCode = code,
            versionName = name,
            apkName = apkName,
            changelog = changelogFallback
        )
    }

    internal fun parseGradleVersionInfo(gradleText: String): ParsedRemoteVersion? {
        val codeMatch = Regex("""versionCode\s*=?\s*(\d+)""").find(gradleText)
        val nameMatch = Regex("""versionName\s*=?\s*["']([^"']+)["']""").find(gradleText)
        val code = codeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val name = nameMatch?.groupValues?.getOrNull(1)?.trim() ?: ""
        return if (code == null && name.isBlank()) null
        else ParsedRemoteVersion(versionCode = code, versionName = name, apkName = null)
    }

    internal fun buildChangelogItems(
        versionJsonChangelog: List<String>,
        commitMessages: List<String>,
        releaseBody: String
    ): List<String> {
        if (versionJsonChangelog.isNotEmpty()) {
            return versionJsonChangelog.take(6)
        }
        val fromBody = releaseBody.lineSequence()
            .map { it.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim() }
            .filter {
                it.length >= 4 &&
                    !it.startsWith("Merge ", ignoreCase = true) &&
                    !it.startsWith("Signed-off-by", ignoreCase = true)
            }
            .distinct()
            .take(5)
            .toList()
        if (fromBody.size >= 2) {
            return fromBody
        }
        val fromCommits = commitMessages
            .mapNotNull { it.lineSequence().firstOrNull()?.trim() }
            .filter {
                it.length >= 4 &&
                    !it.startsWith("Merge ", ignoreCase = true) &&
                    !it.equals("Update apk", ignoreCase = true)
            }
            .distinct()
            .take(5)
        return if (fromCommits.isNotEmpty()) fromCommits else fromBody
    }

    internal fun formatCommitDateLabel(isoDate: String): String {
        if (isoDate.isBlank()) return ""
        return runCatching {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = parser.parse(isoDate.replace(Regex("""\.\d+Z$"""), "Z")) ?: return isoDate
            val formatter = SimpleDateFormat("dd.MM.yyyy • HH:mm", Locale.getDefault())
            formatter.format(parsed)
        }.getOrElse { isoDate.take(16).replace('T', ' ') }
    }

    internal fun normalizeVersionLabel(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V").trim()
    }

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
