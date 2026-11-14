package com.example.lyrics

import com.example.media.ArtworkCatalogResolver
import com.example.media.ArtworkExtractor
import com.example.media.TrackQuerySpec
import com.example.network.BridgeHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class LyricWord(
    val startMs: Long,
    val text: String
)

data class LyricsLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
)

data class LyricsInterlude(
    val startMs: Long,
    val endMs: Long
)

data class LyricsResult(
    val found: Boolean,
    val source: String,
    val artist: String,
    val title: String,
    val synced: Boolean,
    val syncedLyrics: String,
    val plainLyrics: String,
    val lines: List<LyricsLine>
) {
    fun toJsonString(): String {
        val linesJson = lines.joinToString(separator = ",", prefix = "[", postfix = "]") { line ->
            """{"time_ms":${line.timeMs},"text":"${escapeJson(line.text)}"}"""
        }
        return """{"found":$found,"source":"${escapeJson(source)}","artist":"${escapeJson(artist)}","title":"${escapeJson(title)}","synced":$synced,"synced_lyrics":"${escapeJson(syncedLyrics)}","plain_lyrics":"${escapeJson(plainLyrics)}","lines":$linesJson}"""
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

/**
 * Multi-provider 2-phase Lyrics aggregator:
 * Phase 1 (Synced-Only): LRCLIB (exact + fuzzy search) -> NetEase LRC
 * Phase 2 (Unsynced Fallback only if NO synced LRC exists anywhere):
 *   LRCLIB plain -> NetEase plain -> Genius -> Lyrics.ovh
 */
object LyricsCatalogResolver {

    private const val MAX_CACHE_ENTRIES = 40
    private val cacheLock = Any()
    private val lyricsCache = object : LinkedHashMap<String, LyricsResult>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    fun resolveLyrics(
        rawArtist: String,
        rawTitle: String,
        forceRefresh: Boolean = false,
        preferredProvider: String? = null
    ): LyricsResult {
        val spec = ArtworkCatalogResolver.buildTrackQuerySpec(rawArtist, rawTitle)
        if (spec.title.isBlank() && spec.artist.isBlank()) {
            return emptyNotFound(rawArtist, rawTitle)
        }

        val providerKey = preferredProvider?.lowercase(Locale.US)?.takeIf { it.isNotBlank() && it != "auto" }
        val cacheKey = "${spec.artist.lowercase(Locale.US)}|${spec.title.lowercase(Locale.US)}|${providerKey ?: "auto"}"
        if (!forceRefresh) {
            synchronized(cacheLock) {
                lyricsCache[cacheKey]?.let { return it }
            }
        }

        if (providerKey != null) {
            val picked = when (providerKey) {
                "lrclib" -> queryLrcLib(spec)?.let { it.syncedResult ?: it.plainResult }
                "netease" -> queryNetEase(spec)?.let { it.syncedResult ?: it.plainResult }
                "genius" -> queryGeniusLyrics(spec)
                "lyrics_ovh" -> queryLyricsOvh(spec)
                else -> null
            }
            val result = if (picked != null && picked.found) picked else emptyNotFound(spec.artist, spec.title)
            if (result.found) cacheResult(cacheKey, result)
            return result
        }

        // Phase 1: Synced-first across LRCLIB and NetEase in parallel so a slow provider never stalls
        var lrcLibOutcome: ProviderLyricsOutcome? = null
        var netEaseOutcome: ProviderLyricsOutcome? = null
        val phase1Pool = Executors.newFixedThreadPool(2)
        try {
            val fLrc = phase1Pool.submit(Callable { queryLrcLib(spec) })
            val fNet = phase1Pool.submit(Callable { queryNetEase(spec) })
            lrcLibOutcome = runCatching { fLrc.get() }.getOrNull()
            val lrcSynced = lrcLibOutcome?.syncedResult
            if (lrcSynced != null && lrcSynced.found && lrcSynced.synced) {
                cacheResult(cacheKey, lrcSynced)
                return lrcSynced
            }
            netEaseOutcome = runCatching { fNet.get() }.getOrNull()
            val netSynced = netEaseOutcome?.syncedResult
            if (netSynced != null && netSynced.found && netSynced.synced) {
                cacheResult(cacheKey, netSynced)
                return netSynced
            }
        } finally {
            phase1Pool.shutdownNow()
        }

        // Phase 2: Unsynced fallback ONLY when no provider returned synced LRC
        lrcLibOutcome?.plainResult?.takeIf { it.found }?.let { plain ->
            cacheResult(cacheKey, plain)
            return plain
        }
        netEaseOutcome?.plainResult?.takeIf { it.found }?.let { plain ->
            cacheResult(cacheKey, plain)
            return plain
        }

        val phase2Pool = Executors.newFixedThreadPool(2)
        try {
            val fGenius = phase2Pool.submit(Callable { queryGeniusLyrics(spec) })
            val fOvh = phase2Pool.submit(Callable { queryLyricsOvh(spec) })

            val geniusRes = runCatching { fGenius.get() }.getOrNull()
            if (geniusRes?.found == true) {
                cacheResult(cacheKey, geniusRes)
                return geniusRes
            }
            val ovhRes = runCatching { fOvh.get() }.getOrNull()
            if (ovhRes?.found == true) {
                cacheResult(cacheKey, ovhRes)
                return ovhRes
            }
        } finally {
            phase2Pool.shutdownNow()
        }

        val notFound = emptyNotFound(spec.artist, spec.title)
        cacheResult(cacheKey, notFound)
        return notFound
    }

    private data class ProviderLyricsOutcome(
        val syncedResult: LyricsResult? = null,
        val plainResult: LyricsResult? = null
    )

    private fun emptyNotFound(artist: String, title: String) = LyricsResult(
        found = false,
        source = "none",
        artist = artist,
        title = title,
        synced = false,
        syncedLyrics = "",
        plainLyrics = "",
        lines = emptyList()
    )

    private fun cacheResult(key: String, result: LyricsResult) {
        synchronized(cacheLock) {
            lyricsCache[key] = result
        }
    }

    private fun queryLrcLib(spec: TrackQuerySpec): ProviderLyricsOutcome? {
        var bestPlain: LyricsResult? = null
        try {
            if (spec.artist.isNotBlank() && spec.title.isNotBlank()) {
                val url = "https://lrclib.net/api/get?artist_name=${URLEncoder.encode(spec.artist, "UTF-8")}&track_name=${URLEncoder.encode(spec.title, "UTF-8")}"
                val (bytes, _) = ArtworkExtractor.downloadHttpBytes(url, timeoutMs = 4500)
                if (bytes != null) {
                    val obj = JSONObject(String(bytes, Charsets.UTF_8))
                    parseLrcLibItem(obj, spec)?.let { item ->
                        if (item.synced) {
                            return ProviderLyricsOutcome(syncedResult = item, plainResult = item)
                        }
                        bestPlain = item
                    }
                }
            }

            // Always search if exact /get lacked synced lyrics (fuzzy entries often have LRC)
            for (q in spec.searchQueries.take(2)) {
                val searchUrl = "https://lrclib.net/api/search?q=${URLEncoder.encode(q, "UTF-8")}"
                val (bytes, _) = ArtworkExtractor.downloadHttpBytes(searchUrl, timeoutMs = 4500)
                if (bytes == null) continue
                val arr = JSONArray(String(bytes, Charsets.UTF_8))
                for (i in 0 until arr.length().coerceAtMost(8)) {
                    val item = arr.optJSONObject(i) ?: continue
                    val itemArtist = item.optString("artistName")
                    val itemTitle = item.optString("trackName")
                    if (!ArtworkCatalogResolver.isReasonableMatch(spec, itemArtist, itemTitle)) continue
                    val parsed = parseLrcLibItem(item, spec) ?: continue
                    if (parsed.synced) {
                        return ProviderLyricsOutcome(syncedResult = parsed, plainResult = bestPlain ?: parsed)
                    }
                    if (bestPlain == null) {
                        bestPlain = parsed
                    }
                }
            }
        } catch (_: Throwable) {}
        return bestPlain?.let { ProviderLyricsOutcome(plainResult = it) }
    }

    private fun parseLrcLibItem(obj: JSONObject, spec: TrackQuerySpec): LyricsResult? {
        if (obj.optBoolean("instrumental", false)) return null
        val syncedRaw = obj.optString("syncedLyrics").takeIf { it != "null" && it.isNotBlank() } ?: ""
        val plainRaw = obj.optString("plainLyrics").takeIf { it != "null" && it.isNotBlank() } ?: ""
        if (syncedRaw.isBlank() && plainRaw.isBlank()) return null

        val lines = if (syncedRaw.isNotBlank()) parseLrcLines(syncedRaw) else emptyList()
        val effectivePlain = plainRaw.ifBlank { lines.joinToString("\n") { it.text } }

        return LyricsResult(
            found = true,
            source = "lrclib",
            artist = obj.optString("artistName").ifBlank { spec.artist },
            title = obj.optString("trackName").ifBlank { spec.title },
            synced = lines.isNotEmpty(),
            syncedLyrics = syncedRaw,
            plainLyrics = effectivePlain,
            lines = lines
        )
    }

    private fun queryNetEase(spec: TrackQuerySpec): ProviderLyricsOutcome? {
        var bestPlain: LyricsResult? = null
        for (q in spec.searchQueries.take(2)) {
            try {
                val encoded = URLEncoder.encode(q, "UTF-8")
                val (code, body) = BridgeHttpClient.getTextWithCode(
                    url = "https://music.163.com/api/search/get/web?s=$encoded&type=1&offset=0&total=true&limit=5",
                    useCatalogClient = true,
                    headers = mapOf(
                        "User-Agent" to BridgeHttpClient.BROWSER_USER_AGENT,
                        "Referer" to "https://music.163.com/"
                    ),
                    maxRetries = 1
                )
                if (code !in 200..299 || body == null) continue
                val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: continue
                for (i in 0 until songs.length()) {
                    val song = songs.optJSONObject(i) ?: continue
                    val songId = song.optLong("id", -1L)
                    if (songId <= 0L) continue
                    val songTitle = song.optString("name")
                    val artistsArr = song.optJSONArray("artists")
                    val songArtist = if (artistsArr != null && artistsArr.length() > 0) {
                        artistsArr.optJSONObject(0)?.optString("name") ?: ""
                    } else ""
                    if (!ArtworkCatalogResolver.isReasonableMatch(spec, songArtist, songTitle)) continue

                    val lyricUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=1&tv=-1"
                    val (lrcBytes, _) = BridgeHttpClient.getBytes(
                        url = lyricUrl,
                        useCatalogClient = true,
                        headers = mapOf(
                            "User-Agent" to BridgeHttpClient.BROWSER_USER_AGENT,
                            "Referer" to "https://music.163.com/"
                        ),
                        maxRetries = 1
                    )
                    if (lrcBytes == null) continue
                    val lyricJson = JSONObject(String(lrcBytes, Charsets.UTF_8))
                    val rawLrc = lyricJson.optJSONObject("lrc")?.optString("lyric") ?: ""
                    if (rawLrc.isBlank()) continue
                    val parsedLines = parseLrcLines(rawLrc)
                    if (parsedLines.isNotEmpty()) {
                        val plain = parsedLines.joinToString("\n") { it.text }
                        val res = LyricsResult(
                            found = true,
                            source = "netease",
                            artist = songArtist.ifBlank { spec.artist },
                            title = songTitle.ifBlank { spec.title },
                            synced = true,
                            syncedLyrics = rawLrc,
                            plainLyrics = plain,
                            lines = parsedLines
                        )
                        return ProviderLyricsOutcome(syncedResult = res, plainResult = res)
                    } else if (bestPlain == null) {
                        val cleanedPlain = rawLrc
                            .replace(Regex("""\[[a-zA-Z]+:[^\]]*]"""), "")
                            .trim()
                        if (cleanedPlain.isNotBlank()) {
                            bestPlain = LyricsResult(
                                found = true,
                                source = "netease",
                                artist = songArtist.ifBlank { spec.artist },
                                title = songTitle.ifBlank { spec.title },
                                synced = false,
                                syncedLyrics = "",
                                plainLyrics = cleanedPlain,
                                lines = emptyList()
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return bestPlain?.let { ProviderLyricsOutcome(plainResult = it) }
    }

    private fun queryGeniusLyrics(spec: TrackQuerySpec): LyricsResult? {
        for (q in spec.searchQueries.take(2)) {
            try {
                val encoded = URLEncoder.encode(q, "UTF-8")
                val (bytes, _) = ArtworkExtractor.downloadHttpBytes(
                    "https://genius.com/api/search/multi?q=$encoded",
                    timeoutMs = 4500
                )
                if (bytes == null) continue
                val root = JSONObject(String(bytes, Charsets.UTF_8))
                val sections = root.optJSONObject("response")?.optJSONArray("sections") ?: continue
                for (s in 0 until sections.length()) {
                    val sec = sections.optJSONObject(s) ?: continue
                    val type = sec.optString("type")
                    if (type != "song" && type != "top_hit") continue
                    val hits = sec.optJSONArray("hits") ?: continue
                    for (h in 0 until hits.length()) {
                        val result = hits.optJSONObject(h)?.optJSONObject("result") ?: continue
                        val hitTitle = result.optString("title")
                        val hitArtist = result.optJSONObject("primary_artist")?.optString("name") ?: ""
                        if (!ArtworkCatalogResolver.isReasonableMatch(spec, hitArtist, hitTitle)) continue
                        val pageUrl = result.optString("url").takeIf { it.startsWith("http") }
                            ?: result.optString("path").takeIf { it.startsWith("/") }?.let { "https://genius.com$it" }
                            ?: continue

                        val (pageBytes, _) = ArtworkExtractor.downloadHttpBytes(pageUrl, timeoutMs = 5000)
                        if (pageBytes == null) continue
                        val html = String(pageBytes, Charsets.UTF_8)
                        val extractedPlain = extractGeniusHtmlLyrics(html)
                        if (extractedPlain.isNotBlank()) {
                            return LyricsResult(
                                found = true,
                                source = "genius",
                                artist = hitArtist.ifBlank { spec.artist },
                                title = hitTitle.ifBlank { spec.title },
                                synced = false,
                                syncedLyrics = "",
                                plainLyrics = extractedPlain,
                                lines = emptyList()
                            )
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun queryLyricsOvh(spec: TrackQuerySpec): LyricsResult? {
        if (spec.artist.isBlank() || spec.title.isBlank()) return null
        return try {
            val a = URLEncoder.encode(spec.artist, "UTF-8").replace("+", "%20")
            val t = URLEncoder.encode(spec.title, "UTF-8").replace("+", "%20")
            val (bytes, _) = ArtworkExtractor.downloadHttpBytes(
                "https://api.lyrics.ovh/v1/$a/$t",
                timeoutMs = 4000
            )
            if (bytes == null) return null
            val lyrics = JSONObject(String(bytes, Charsets.UTF_8)).optString("lyrics").trim()
            if (lyrics.isBlank()) return null
            LyricsResult(
                found = true,
                source = "lyrics_ovh",
                artist = spec.artist,
                title = spec.title,
                synced = false,
                syncedLyrics = "",
                plainLyrics = lyrics,
                lines = emptyList()
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Extracts ALL `data-lyrics-container="true"` blocks using balanced `<div` / `</div>` tag depth
     * scanning so nested annotation / header / ad `<div>`s never truncate lyrics mid-song.
     */
    internal fun extractGeniusHtmlLyrics(html: String): String {
        val markerRegex = Regex("""<div\b[^>]*data-lyrics-container\s*=\s*["']true["'][^>]*>""", RegexOption.IGNORE_CASE)
        val openDivRegex = Regex("""<div\b[^>]*>""", RegexOption.IGNORE_CASE)
        val closeDivRegex = Regex("""</div\s*>""", RegexOption.IGNORE_CASE)

        val blocks = mutableListOf<String>()
        var searchFrom = 0
        while (searchFrom < html.length) {
            val startMatch = markerRegex.find(html, searchFrom) ?: break
            val contentStart = startMatch.range.last + 1
            var cursor = contentStart
            var depth = 1
            var contentEnd = html.length

            while (cursor < html.length && depth > 0) {
                val nextOpen = openDivRegex.find(html, cursor)
                val nextClose = closeDivRegex.find(html, cursor)
                if (nextClose == null) break
                if (nextOpen != null && nextOpen.range.first < nextClose.range.first) {
                    depth++
                    cursor = nextOpen.range.last + 1
                } else {
                    depth--
                    if (depth == 0) {
                        contentEnd = nextClose.range.first
                    }
                    cursor = nextClose.range.last + 1
                }
            }

            if (contentEnd > contentStart) {
                blocks.add(html.substring(contentStart, contentEnd))
            }
            searchFrom = maxOf(cursor, startMatch.range.last + 1)
        }

        if (blocks.isEmpty()) return ""
        val combined = blocks.joinToString("\n")
            // Strip Genius header/sidebar metadata blocks inside containers
            .replace(Regex("""<div\b[^>]*data-exclude-from-selection\s*=\s*["']true["'][^>]*>.*?</div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<(script|style|svg|button)\b[^>]*>.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</(?:p|div|li|h\d)>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")

        val decoded = decodeHtmlEntities(combined)
        return decoded
            .lines()
            .map { it.trimEnd() }
            .joinToString("\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun decodeHtmlEntities(input: String): String {
        var text = input
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&rsquo;", "’")
            .replace("&lsquo;", "‘")
            .replace("&rdquo;", "”")
            .replace("&ldquo;", "“")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        text = Regex("""&#(\d+);""").replace(text) { m ->
            m.groupValues[1].toIntOrNull()?.let { code ->
                if (code in 32..0x10FFFF) String(Character.toChars(code)) else m.value
            } ?: m.value
        }
        text = Regex("""&#x([0-9a-fA-F]+);""").replace(text) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { code ->
                if (code in 32..0x10FFFF) String(Character.toChars(code)) else m.value
            } ?: m.value
        }
        return text
    }

    internal fun parseLrcLines(lrcText: String): List<LyricsLine> {
        val lineTagRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val wordTagRegex = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
        val result = mutableListOf<LyricsLine>()

        lrcText.lineSequence().forEach { rawLine ->
            val tags = lineTagRegex.findAll(rawLine).toList()
            if (tags.isEmpty()) return@forEach
            val bodyWithWordTags = lineTagRegex.replace(rawLine, "").trim()
            if (bodyWithWordTags.isEmpty()) return@forEach

            val cleanText = wordTagRegex.replace(bodyWithWordTags, "").trim()
            if (cleanText.isEmpty()) return@forEach

            val parsedBaseTimeMs = parseTimestampMs(tags.first().groupValues[1], tags.first().groupValues[2], tags.first().groupValues[3]) ?: 0L
            val parsedWordsBase = parseEnhancedWordTags(bodyWithWordTags, parsedBaseTimeMs, wordTagRegex)

            for (match in tags) {
                val totalMs = parseTimestampMs(match.groupValues[1], match.groupValues[2], match.groupValues[3]) ?: continue
                val delta = totalMs - parsedBaseTimeMs
                val shiftedWords = if (parsedWordsBase.isEmpty()) {
                    emptyList()
                } else if (delta == 0L) {
                    parsedWordsBase
                } else {
                    parsedWordsBase.map { w -> w.copy(startMs = (w.startMs + delta).coerceAtLeast(0L)) }
                }
                result.add(LyricsLine(timeMs = totalMs, text = cleanText, words = shiftedWords))
            }
        }

        return result.sortedBy { it.timeMs }
    }

    private fun parseEnhancedWordTags(
        body: String,
        lineStartMs: Long,
        wordTagRegex: Regex
    ): List<LyricWord> {
        val matches = wordTagRegex.findAll(body).toList()
        if (matches.isEmpty()) return emptyList()

        val words = mutableListOf<LyricWord>()
        val firstStart = matches.first().range.first
        if (firstStart > 0) {
            val prefix = body.substring(0, firstStart)
            if (prefix.isNotBlank()) {
                words.add(LyricWord(startMs = lineStartMs, text = prefix))
            }
        }
        for (i in matches.indices) {
            val m = matches[i]
            val segStart = m.range.last + 1
            val segEnd = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
            val chunk = if (segEnd > segStart) body.substring(segStart, segEnd) else ""
            val wordMs = parseTimestampMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]) ?: lineStartMs
            if (chunk.isNotEmpty()) {
                words.add(LyricWord(startMs = wordMs, text = chunk))
            }
        }
        return words
    }

    private fun parseTimestampMs(minStr: String, secStr: String, fracRaw: String): Long? {
        val mins = minStr.toLongOrNull() ?: return null
        val secs = secStr.toLongOrNull() ?: return null
        val millis = when (fracRaw.length) {
            0 -> 0L
            1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
            2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
            else -> fracRaw.take(3).toLongOrNull() ?: 0L
        }
        return mins * 60_000L + secs * 1000L + millis
    }

    /**
     * Returns an instrumental interlude window before line [index] if there is a >= 4s intro
     * before line 0 or a >= 5s gap between line [index - 1] and line [index].
     */
    internal fun computeInterludeBefore(
        lines: List<LyricsLine>,
        index: Int,
        minIntroMs: Long = 4_000L,
        minInterLineGapMs: Long = 5_000L
    ): LyricsInterlude? {
        if (index < 0 || index >= lines.size) return null
        val targetStart = lines[index].timeMs
        return if (index == 0) {
            if (targetStart >= minIntroMs) LyricsInterlude(startMs = 0L, endMs = targetStart) else null
        } else {
            val prevStart = lines[index - 1].timeMs
            val estimatedPrevEnd = prevStart + 2_200L
            if ((targetStart - prevStart) >= minInterLineGapMs && targetStart > estimatedPrevEnd) {
                LyricsInterlude(startMs = estimatedPrevEnd, endMs = targetStart)
            } else {
                null
            }
        }
    }
}
