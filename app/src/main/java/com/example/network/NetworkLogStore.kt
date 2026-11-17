package com.example.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class NetworkCategory(val label: String, val cooldownBucket: String) {
    GITHUB_API("GitHub API", "github_api"),
    GITHUB_RAW("GitHub Raw", "github_raw"),
    LRCLIB("LRCLIB", "lrclib"),
    NETEASE("NetEase", "netease"),
    KUGOU("KuGou", "kugou"),
    GENIUS("Genius", "genius"),
    LYRICS_OVH("Lyrics.ovh", "lyrics_ovh"),
    ITUNES("iTunes", "itunes"),
    DEEZER("Deezer", "deezer"),
    SOUNDCLOUD("SoundCloud", "soundcloud"),
    OTHER("Сеть", "other")
}

data class NetworkLogEntry(
    val id: Long,
    val timestampMs: Long,
    val category: NetworkCategory,
    val method: String,
    val host: String,
    val pathAndQuery: String,
    val httpCode: Int?,
    val errorType: String?,
    val durationMs: Long,
    val rateLimitRemaining: Int? = null,
    val rateLimitResetEpochSec: Long? = null,
    val isCooldownBlocked: Boolean = false
) {
    val isError: Boolean
        get() = isCooldownBlocked ||
            errorType != null ||
            (httpCode != null && (httpCode == 403 || httpCode == 429 || httpCode >= 500 || httpCode < 0))
}

class HostCooldownException(
    val bucket: String,
    val cooldownUntilMs: Long,
    val httpCode: Int = 429
) : IOException("Host cooldown active for $bucket until $cooldownUntilMs (HTTP $httpCode)")

data class CooldownInfo(
    val bucket: String,
    val untilMs: Long,
    val reasonCode: Int,
    val rateLimitRemaining: Int? = null,
    val rateLimitResetEpochSec: Long? = null
)

data class GitHubRateLimitStatus(
    val httpCode: Int,
    val remaining: Int?,
    val resetEpochSec: Long?,
    val cooldownUntilMs: Long?,
    val updatedAtMs: Long
)

/**
 * Per-host cooldown registry separating api.github.com from raw.githubusercontent.com
 * and tracking HTTP 403/429 bans across music catalogs so rate limits never trigger IP bans.
 */
object HostCooldownGuard {

    const val DEFAULT_COOLDOWN_MS = 5 * 60 * 1000L
    const val SOFT_NETWORK_COOLDOWN_MS = 3 * 60 * 1000L
    private const val MAX_COOLDOWN_MS = 60 * 60 * 1000L

    private val cooldowns = ConcurrentHashMap<String, CooldownInfo>()
    private val timeoutStreaks = ConcurrentHashMap<String, Int>()

    @Volatile
    private var lastGitHubRateLimit: GitHubRateLimitStatus? = null

    fun resolveCategory(rawHost: String): NetworkCategory {
        val host = rawHost.trim().lowercase(Locale.US)
        return when {
            host == "api.github.com" || host.endsWith(".api.github.com") -> NetworkCategory.GITHUB_API
            host.contains("githubusercontent.com") || host == "github.com" || host.endsWith(".github.com") -> NetworkCategory.GITHUB_RAW
            host.contains("lrclib.net") -> NetworkCategory.LRCLIB
            host.contains("163.com") || host.contains("netease") -> NetworkCategory.NETEASE
            host.contains("kugou.com") -> NetworkCategory.KUGOU
            host.contains("genius.com") -> NetworkCategory.GENIUS
            host.contains("lyrics.ovh") -> NetworkCategory.LYRICS_OVH
            host.contains("itunes.apple.com") || host.contains("mzstatic.com") -> NetworkCategory.ITUNES
            host.contains("deezer.com") || host.contains("dzcdn.net") -> NetworkCategory.DEEZER
            host.contains("soundcloud.com") || host.contains("sndcdn.com") -> NetworkCategory.SOUNDCLOUD
            else -> NetworkCategory.OTHER
        }
    }

    fun checkCooldown(bucket: String, nowMs: Long = System.currentTimeMillis()): CooldownInfo? {
        val info = cooldowns[bucket] ?: return null
        if (nowMs >= info.untilMs) {
            cooldowns.remove(bucket, info)
            return null
        }
        return info
    }

    internal fun computeCooldownUntilMs(
        httpCode: Int,
        retryAfterHeader: String?,
        rateLimitRemainingHeader: String?,
        rateLimitResetHeader: String?,
        nowMs: Long = System.currentTimeMillis()
    ): Long? {
        if (httpCode != 403 && httpCode != 429) return null

        val retrySeconds = retryAfterHeader?.trim()?.toLongOrNull()
        if (retrySeconds != null && retrySeconds > 0L) {
            return nowMs + (retrySeconds * 1000L).coerceIn(5_000L, MAX_COOLDOWN_MS)
        }

        val remaining = rateLimitRemainingHeader?.trim()?.toIntOrNull()
        val resetEpochSec = rateLimitResetHeader?.trim()?.toLongOrNull()
        if ((remaining == null || remaining == 0) && resetEpochSec != null && resetEpochSec > 0L) {
            val resetMs = resetEpochSec * 1000L
            if (resetMs > nowMs) {
                return (resetMs + 2_000L).coerceAtMost(nowMs + MAX_COOLDOWN_MS)
            }
        }

        return nowMs + DEFAULT_COOLDOWN_MS
    }

    fun recordHttpOutcome(
        category: NetworkCategory,
        httpCode: Int,
        retryAfterHeader: String?,
        rateLimitRemainingHeader: String?,
        rateLimitResetHeader: String?,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (httpCode > 0) {
            timeoutStreaks.remove(category.cooldownBucket)
        }
        val remaining = rateLimitRemainingHeader?.trim()?.toIntOrNull()
        val resetEpochSec = rateLimitResetHeader?.trim()?.toLongOrNull()
        val untilMs = computeCooldownUntilMs(
            httpCode = httpCode,
            retryAfterHeader = retryAfterHeader,
            rateLimitRemainingHeader = rateLimitRemainingHeader,
            rateLimitResetHeader = rateLimitResetHeader,
            nowMs = nowMs
        )

        if (category == NetworkCategory.GITHUB_API) {
            lastGitHubRateLimit = GitHubRateLimitStatus(
                httpCode = httpCode,
                remaining = remaining,
                resetEpochSec = resetEpochSec,
                cooldownUntilMs = untilMs,
                updatedAtMs = nowMs
            )
        }

        if (untilMs != null) {
            cooldowns[category.cooldownBucket] = CooldownInfo(
                bucket = category.cooldownBucket,
                untilMs = untilMs,
                reasonCode = httpCode,
                rateLimitRemaining = remaining,
                rateLimitResetEpochSec = resetEpochSec
            )
        } else if (httpCode in 200..299 || httpCode == 304) {
            cooldowns.remove(category.cooldownBucket)
        }
    }

    fun recordNetworkFailure(
        category: NetworkCategory,
        errorSimpleName: String,
        nowMs: Long = System.currentTimeMillis()
    ): Long? {
        val normalized = errorSimpleName.trim()
        val isImmediateSoftBan = normalized.contains("UnknownHostException", ignoreCase = true) ||
            normalized.contains("StreamResetException", ignoreCase = true)
        val isTimeout = normalized.contains("SocketTimeoutException", ignoreCase = true) ||
            normalized.contains("InterruptedIOException", ignoreCase = true)

        val shouldCooldown = when {
            isImmediateSoftBan -> true
            isTimeout -> {
                val nextCount = (timeoutStreaks[category.cooldownBucket] ?: 0) + 1
                timeoutStreaks[category.cooldownBucket] = nextCount
                nextCount >= 2
            }
            else -> false
        }

        if (!shouldCooldown) return null
        timeoutStreaks.remove(category.cooldownBucket)
        val untilMs = nowMs + SOFT_NETWORK_COOLDOWN_MS
        cooldowns[category.cooldownBucket] = CooldownInfo(
            bucket = category.cooldownBucket,
            untilMs = untilMs,
            reasonCode = -1
        )
        return untilMs
    }

    fun getLastGitHubRateLimit(): GitHubRateLimitStatus? = lastGitHubRateLimit

    fun clearAll() {
        cooldowns.clear()
        timeoutStreaks.clear()
        lastGitHubRateLimit = null
    }
}

/**
 * Thread-safe in-memory ring buffer storing the last 40 outbound HTTP requests
 * for DiagnosticsTab observation and troubleshooting.
 */
object NetworkLogStore {

    const val MAX_ENTRIES = 40
    private val idSeq = AtomicLong(0L)
    private val lock = Any()
    private val buffer = ArrayDeque<NetworkLogEntry>(MAX_ENTRIES + 1)

    private val _entries = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
    val entries: StateFlow<List<NetworkLogEntry>> = _entries.asStateFlow()

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount.asStateFlow()

    fun record(
        category: NetworkCategory,
        method: String,
        host: String,
        pathAndQuery: String,
        httpCode: Int?,
        errorType: String?,
        durationMs: Long,
        rateLimitRemaining: Int? = null,
        rateLimitResetEpochSec: Long? = null,
        isCooldownBlocked: Boolean = false,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        val entry = NetworkLogEntry(
            id = idSeq.incrementAndGet(),
            timestampMs = timestampMs,
            category = category,
            method = method,
            host = host,
            pathAndQuery = pathAndQuery.take(180),
            httpCode = httpCode,
            errorType = errorType,
            durationMs = durationMs.coerceAtLeast(0L),
            rateLimitRemaining = rateLimitRemaining,
            rateLimitResetEpochSec = rateLimitResetEpochSec,
            isCooldownBlocked = isCooldownBlocked
        )
        synchronized(lock) {
            buffer.addFirst(entry)
            while (buffer.size > MAX_ENTRIES) {
                buffer.removeLast()
            }
            val snapshot = buffer.toList()
            _entries.value = snapshot
            _errorCount.value = snapshot.count { it.isError }
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
            _errorCount.value = 0
        }
    }

    fun formatTimestampHHmmss(ms: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    fun formatTimestampHHmm(ms: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(ms))
    }
}
