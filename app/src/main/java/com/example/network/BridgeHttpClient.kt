package com.example.network

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Custom DNS resolver prioritizing IPv4 over IPv6 addresses so hung IPv6 routes
 * never stall GitHub releases or music catalog lookups on mobile networks.
 */
object Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val raw = Dns.SYSTEM.lookup(hostname)
        return sortAddressesIpv4First(raw)
    }

    internal fun sortAddressesIpv4First(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size <= 1) return addresses
        return addresses.sortedBy { addr -> if (addr is Inet4Address) 0 else 1 }
    }
}

private object NetworkDiagnosticsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val host = url.host
        val pathWithQuery = buildString {
            append(url.encodedPath)
            url.encodedQuery?.let { q ->
                append('?').append(q)
            }
        }
        val category = HostCooldownGuard.resolveCategory(host)
        val nowMs = System.currentTimeMillis()
        val activeCooldown = HostCooldownGuard.checkCooldown(category.cooldownBucket, nowMs)
        if (activeCooldown != null) {
            NetworkLogStore.record(
                category = category,
                method = request.method,
                host = host,
                pathAndQuery = pathWithQuery,
                httpCode = activeCooldown.reasonCode,
                errorType = "Cooldown до ${NetworkLogStore.formatTimestampHHmm(activeCooldown.untilMs)}",
                durationMs = 0L,
                rateLimitRemaining = activeCooldown.rateLimitRemaining,
                rateLimitResetEpochSec = activeCooldown.rateLimitResetEpochSec,
                isCooldownBlocked = true,
                timestampMs = nowMs
            )
            throw HostCooldownException(
                bucket = category.cooldownBucket,
                cooldownUntilMs = activeCooldown.untilMs,
                httpCode = activeCooldown.reasonCode
            )
        }

        val startedNs = System.nanoTime()
        return try {
            val response = chain.proceed(request)
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
            val remainingRaw = response.header("X-RateLimit-Remaining")
            val resetRaw = response.header("X-RateLimit-Reset")
            val retryAfterRaw = response.header("Retry-After")
            HostCooldownGuard.recordHttpOutcome(
                category = category,
                httpCode = response.code,
                retryAfterHeader = retryAfterRaw,
                rateLimitRemainingHeader = remainingRaw,
                rateLimitResetHeader = resetRaw
            )
            NetworkLogStore.record(
                category = category,
                method = request.method,
                host = host,
                pathAndQuery = pathWithQuery,
                httpCode = response.code,
                errorType = null,
                durationMs = elapsedMs,
                rateLimitRemaining = remainingRaw?.trim()?.toIntOrNull(),
                rateLimitResetEpochSec = resetRaw?.trim()?.toLongOrNull(),
                isCooldownBlocked = false
            )
            response
        } catch (t: Throwable) {
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
            if (t !is HostCooldownException) {
                NetworkLogStore.record(
                    category = category,
                    method = request.method,
                    host = host,
                    pathAndQuery = pathWithQuery,
                    httpCode = null,
                    errorType = t.javaClass.simpleName,
                    durationMs = elapsedMs,
                    isCooldownBlocked = false
                )
            }
            throw t
        }
    }
}

/**
 * Shared OkHttp networking layer with shared ConnectionPool & IPv4-first DNS:
 * - githubClient: resilient 20s/45s/60s timeouts for GitHub APIs, raw files & APK/plugin streams
 * - catalogClient: snappy 8s/10s/15s timeouts derived from githubClient for artwork & lyrics providers
 */
object BridgeHttpClient {

    private const val TAG = "BridgeHttpClient"
    const val GITHUB_USER_AGENT = "ClawdBridge-Android"
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val sharedConnectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    val githubClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .dns(Ipv4FirstDns)
            .addInterceptor(NetworkDiagnosticsInterceptor)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val catalogClient: OkHttpClient by lazy {
        githubClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class DetailedTextResult(
        val httpCode: Int,
        val body: String?,
        val errorLabel: String? = null,
        val isCooldown: Boolean = false
    )

    fun getTextDetailed(
        url: String,
        useCatalogClient: Boolean = true,
        headers: Map<String, String> = emptyMap(),
        maxRetries: Int = 1,
        customCallTimeoutMs: Long? = null
    ): DetailedTextResult {
        val baseClient = if (useCatalogClient) catalogClient else githubClient
        val client = if (customCallTimeoutMs != null && customCallTimeoutMs > 0L) {
            baseClient.newBuilder()
                .callTimeout(customCallTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
        } else {
            baseClient
        }
        val attempts = maxRetries.coerceAtLeast(0) + 1
        var lastErrorLabel: String? = null
        for (attempt in 0 until attempts) {
            try {
                val reqBuilder = Request.Builder().url(url).get()
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                client.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (response.isSuccessful) {
                        return DetailedTextResult(httpCode = code, body = response.body?.string())
                    }
                    if (code == 403 || code == 429) {
                        val serverHeader = response.header("Server").orEmpty()
                        val cfRay = response.header("CF-RAY")
                        val label = when {
                            code == 403 && (serverHeader.contains("cloudflare", ignoreCase = true) || cfRay != null) ->
                                "HTTP 403 Cloudflare"
                            code == 429 -> "HTTP 429"
                            else -> "HTTP $code"
                        }
                        return DetailedTextResult(httpCode = code, body = null, errorLabel = label)
                    }
                    if (code in 500..599 && attempt < attempts - 1) {
                        lastErrorLabel = "HTTP $code"
                        Thread.sleep(600L)
                    } else {
                        return DetailedTextResult(
                            httpCode = code,
                            body = null,
                            errorLabel = if (code == 404) null else "HTTP $code"
                        )
                    }
                }
            } catch (e: HostCooldownException) {
                return DetailedTextResult(
                    httpCode = e.httpCode,
                    body = null,
                    errorLabel = "Пауза HTTP ${e.httpCode} до ${NetworkLogStore.formatTimestampHHmm(e.cooldownUntilMs)}",
                    isCooldown = true
                )
            } catch (e: SocketTimeoutException) {
                lastErrorLabel = "Timeout"
                if (attempt < attempts - 1) runCatching { Thread.sleep(600L) }
            } catch (e: InterruptedIOException) {
                lastErrorLabel = "Timeout"
                if (Thread.currentThread().isInterrupted) break
                if (attempt < attempts - 1) runCatching { Thread.sleep(600L) }
            } catch (e: IOException) {
                lastErrorLabel = e.javaClass.simpleName
                if (Thread.currentThread().isInterrupted) break
                if (attempt < attempts - 1) runCatching { Thread.sleep(600L) }
            } catch (t: Throwable) {
                lastErrorLabel = t.javaClass.simpleName
                break
            }
        }
        return DetailedTextResult(httpCode = -1, body = null, errorLabel = lastErrorLabel ?: "Error")
    }

    fun getBytes(
        url: String,
        useCatalogClient: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = 5 * 1024 * 1024,
        maxRetries: Int = 2
    ): Pair<ByteArray?, String> {
        val client = if (useCatalogClient) catalogClient else githubClient
        var lastNote = "http:fail"
        val attempts = maxRetries.coerceAtLeast(0) + 1
        for (attempt in 0 until attempts) {
            try {
                val reqBuilder = Request.Builder().url(url).get()
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                client.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (!response.isSuccessful) {
                        lastNote = "http:$code"
                        if (code in 500..599 && attempt < attempts - 1) {
                            Thread.sleep(700L)
                            return@use
                        }
                        return Pair(null, lastNote)
                    }
                    val bodyStream = response.body?.byteStream() ?: return Pair(null, "http:empty_body")
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = bodyStream.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > maxBytes) return Pair(null, "http:too_large")
                        out.write(buffer, 0, read)
                    }
                    return Pair(out.toByteArray(), "http:$code")
                }
            } catch (e: HostCooldownException) {
                return Pair(null, "cooldown:${e.httpCode}")
            } catch (e: InterruptedIOException) {
                lastNote = "http:${e.javaClass.simpleName}"
                if (Thread.currentThread().isInterrupted) break
                if (attempt < attempts - 1) {
                    runCatching { Thread.sleep(700L) }
                }
            } catch (e: IOException) {
                lastNote = "http:${e.javaClass.simpleName}"
                if (attempt < attempts - 1) {
                    runCatching { Thread.sleep(700L) }
                }
            } catch (t: Throwable) {
                lastNote = "http:${t.javaClass.simpleName}"
                break
            }
        }
        return Pair(null, lastNote)
    }

    fun getTextWithCode(
        url: String,
        useCatalogClient: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        maxRetries: Int = 2
    ): Pair<Int, String?> {
        val client = if (useCatalogClient) catalogClient else githubClient
        val attempts = maxRetries.coerceAtLeast(0) + 1
        var lastError: Throwable? = null
        for (attempt in 0 until attempts) {
            try {
                val reqBuilder = Request.Builder().url(url).get()
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                client.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (response.isSuccessful) {
                        return Pair(code, response.body?.string())
                    }
                    if (code in 500..599 && attempt < attempts - 1) {
                        Thread.sleep(700L)
                    } else {
                        return Pair(code, null)
                    }
                }
            } catch (e: HostCooldownException) {
                throw e
            } catch (e: SocketTimeoutException) {
                lastError = e
                Log.w(TAG, "Timeout on attempt ${attempt + 1}/$attempts for $url: ${e.message}")
                if (attempt < attempts - 1) runCatching { Thread.sleep(700L) }
            } catch (e: IOException) {
                lastError = e
                if (Thread.currentThread().isInterrupted) break
                if (attempt < attempts - 1) runCatching { Thread.sleep(700L) }
            } catch (t: Throwable) {
                throw t
            }
        }
        if (lastError != null) throw lastError
        return Pair(-1, null)
    }

    data class ConditionalHttpResult(
        val code: Int,
        val body: String?,
        val etag: String?,
        val notModified: Boolean
    )

    fun getTextWithEtag(
        url: String,
        ifNoneMatch: String? = null,
        headers: Map<String, String> = emptyMap(),
        maxRetries: Int = 1
    ): ConditionalHttpResult {
        val attempts = maxRetries.coerceAtLeast(0) + 1
        var lastError: Throwable? = null
        for (attempt in 0 until attempts) {
            try {
                val reqBuilder = Request.Builder().url(url).get()
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                if (!ifNoneMatch.isNullOrBlank()) {
                    reqBuilder.header("If-None-Match", ifNoneMatch)
                }
                githubClient.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    val respEtag = response.header("ETag") ?: ifNoneMatch
                    if (code == 304) {
                        return ConditionalHttpResult(
                            code = 304,
                            body = null,
                            etag = respEtag,
                            notModified = true
                        )
                    }
                    if (response.isSuccessful) {
                        return ConditionalHttpResult(
                            code = code,
                            body = response.body?.string(),
                            etag = respEtag,
                            notModified = false
                        )
                    }
                    if (code in 500..599 && attempt < attempts - 1) {
                        Thread.sleep(700L)
                    } else {
                        return ConditionalHttpResult(code = code, body = null, etag = respEtag, notModified = false)
                    }
                }
            } catch (e: HostCooldownException) {
                return ConditionalHttpResult(code = e.httpCode, body = null, etag = ifNoneMatch, notModified = false)
            } catch (e: IOException) {
                lastError = e
                if (Thread.currentThread().isInterrupted) break
                if (attempt < attempts - 1) runCatching { Thread.sleep(700L) }
            } catch (t: Throwable) {
                throw t
            }
        }
        if (lastError != null) throw lastError
        return ConditionalHttpResult(code = -1, body = null, etag = ifNoneMatch, notModified = false)
    }

    fun downloadFile(
        url: String,
        partFile: File,
        expectedSizeBytes: Long = -1L,
        headers: Map<String, String> = emptyMap(),
        maxRetries: Int = 2,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Long {
        val attempts = maxRetries.coerceAtLeast(0) + 1
        var lastError: Throwable? = null

        for (attempt in 0 until attempts) {
            try {
                val reqBuilder = Request.Builder().url(url).get()
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
                githubClient.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    if (!response.isSuccessful) {
                        if (code in 500..599 && attempt < attempts - 1) {
                            Thread.sleep(700L)
                            return@use
                        }
                        throw IllegalStateException("Ошибка загрузки HTTP $code")
                    }

                    val body = response.body ?: throw IllegalStateException("Пустой ответ сервера")
                    val contentLen = body.contentLength().takeIf { it > 0L } ?: expectedSizeBytes
                    var downloaded = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(partFile, false).use { output ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read == -1) break
                                output.write(buf, 0, read)
                                downloaded += read
                                onProgress(downloaded, contentLen)
                            }
                            output.flush()
                        }
                    }

                    if (downloaded <= 0L) {
                        partFile.delete()
                        throw IllegalStateException("Скачанный файл пуст")
                    }
                    return downloaded
                }
            } catch (e: HostCooldownException) {
                runCatching { partFile.delete() }
                throw e
            } catch (e: IOException) {
                lastError = e
                runCatching { partFile.delete() }
                if (Thread.currentThread().isInterrupted) break
                if (attempt < attempts - 1) runCatching { Thread.sleep(700L) }
            } catch (t: Throwable) {
                runCatching { partFile.delete() }
                throw t
            }
        }
        throw (lastError ?: IllegalStateException("Ошибка сети при скачивании файла"))
    }
}
