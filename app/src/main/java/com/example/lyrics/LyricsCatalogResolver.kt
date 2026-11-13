package com.example.lyrics

import com.example.media.ArtworkCatalogResolver
import com.example.media.ArtworkExtractor
import com.example.media.TrackQuerySpec
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class LyricsLine(
    val timeMs: Long,
    val text: String
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
 * Multi-provider Lyrics aggregator expanding synced & plain lyrics coverage:
 * 1. LRCLIB (synced LRC + plain)
 * 2. NetEase Cloud Music (synced LRC + global electronic/indie)
 * 3. Genius (broad coverage for SoundCloud, Russian rap, hyperpop & underground)
 * 4. Lyrics.ovh (plain fallback)
 */
object LyricsCatalogResolver {

    private const val MAX_CACHE_ENTRIES = 40
    private val cacheLock = Any()
    private val lyricsCache = object : LinkedHashMap<String, LyricsResult>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsResult>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    fun resolveLyrics(rawArtist: String, rawTitle: String): LyricsResult {
        val spec = ArtworkCatalogResolver.buildTrackQuerySpec(rawArtist, rawTitle)
        if (spec.title.isBlank() && spec.artist.isBlank()) {
            return emptyNotFound(rawArtist, rawTitle)
        }

        val cacheKey = "${spec.artist.lowercase(Locale.US)}|${spec.title.lowercase(Locale.US)}"
        synchronized(cacheLock) {
            lyricsCache[cacheKey]?.let { return it }
        }

        // Priority 1: LRCLIB (best synced LRC source)
        queryLrcLib(spec)?.let { result ->
            if (result.found) {
                cacheResult(cacheKey, result)
                return result
            }
        }

        // Priority 2: NetEase Cloud Music (wide synced LRC catalog)
        queryNetEase(spec)?.let { result ->
            if (result.found) {
                cacheResult(cacheKey, result)
                return result
            }
        }

        // Priority 3: Genius (top catalog for SoundCloud / CIS / underground plain lyrics)
        queryGeniusLyrics(spec)?.let { result ->
            if (result.found) {
                cacheResult(cacheKey, result)
                return result
            }
        }

        // Priority 4: Lyrics.ovh
        queryLyricsOvh(spec)?.let { result ->
            if (result.found) {
                cacheResult(cacheKey, result)
                return result
            }
        }

        val notFound = emptyNotFound(spec.artist, spec.title)
        cacheResult(cacheKey, notFound)
        return notFound
    }

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

    private fun queryLrcLib(spec: TrackQuerySpec): LyricsResult? {
        try {
            if (spec.artist.isNotBlank() && spec.title.isNotBlank()) {
                val url = "https://lrclib.net/api/get?artist_name=${URLEncoder.encode(spec.artist, "UTF-8")}&track_name=${URLEncoder.encode(spec.title, "UTF-8")}"
                val (bytes, _) = ArtworkExtractor.downloadHttpBytes(url, timeoutMs = 4000)
                if (bytes != null) {
                    val obj = JSONObject(String(bytes, Charsets.UTF_8))
                    parseLrcLibItem(obj, spec)?.let { return it }
                }
            }

            for (q in spec.searchQueries.take(2)) {
                val searchUrl = "https://lrclib.net/api/search?q=${URLEncoder.encode(q, "UTF-8")}"
                val (bytes, _) = ArtworkExtractor.downloadHttpBytes(searchUrl, timeoutMs = 4000) ?: continue
                if (bytes == null) continue
                val arr = JSONArray(String(bytes, Charsets.UTF_8))
                for (i in 0 until arr.length().coerceAtMost(5)) {
                    val item = arr.optJSONObject(i) ?: continue
                    val itemArtist = item.optString("artistName")
                    val itemTitle = item.optString("trackName")
                    if (!ArtworkCatalogResolver.isReasonableMatch(spec, itemArtist, itemTitle)) continue
                    parseLrcLibItem(item, spec)?.let { return it }
                }
            }
        } catch (_: Throwable) {}
        return null
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

    private fun queryNetEase(spec: TrackQuerySpec): LyricsResult? {
        for (q in spec.searchQueries.take(2)) {
            var conn: HttpURLConnection? = null
            try {
                val encoded = URLEncoder.encode(q, "UTF-8")
                conn = (URL("https://music.163.com/api/search/get/web?s=$encoded&type=1&offset=0&total=true&limit=4").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", ArtworkExtractor.BROWSER_USER_AGENT)
                    setRequestProperty("Referer", "https://music.163.com/")
                }
                if (conn.responseCode !in 200..299) continue
                val body = conn.inputStream?.bufferedReader()?.use { it.readText() } ?: continue
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
                    val (lrcBytes, _) = ArtworkExtractor.downloadHttpBytes(lyricUrl, timeoutMs = 3500)
                    if (lrcBytes == null) continue
                    val lyricJson = JSONObject(String(lrcBytes, Charsets.UTF_8))
                    val rawLrc = lyricJson.optJSONObject("lrc")?.optString("lyric") ?: ""
                    if (rawLrc.isBlank()) continue
                    val parsedLines = parseLrcLines(rawLrc)
                    if (parsedLines.isEmpty()) continue
                    val plain = parsedLines.joinToString("\n") { it.text }
                    return LyricsResult(
                        found = true,
                        source = "netease",
                        artist = songArtist.ifBlank { spec.artist },
                        title = songTitle.ifBlank { spec.title },
                        synced = true,
                        syncedLyrics = rawLrc,
                        plainLyrics = plain,
                        lines = parsedLines
                    )
                }
            } catch (_: Throwable) {
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun queryGeniusLyrics(spec: TrackQuerySpec): LyricsResult? {
        for (q in spec.searchQueries.take(2)) {
            try {
                val encoded = URLEncoder.encode(q, "UTF-8")
                val (bytes, _) = ArtworkExtractor.downloadHttpBytes(
                    "https://genius.com/api/search/multi?q=$encoded",
                    timeoutMs = 3500
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

                        val (pageBytes, _) = ArtworkExtractor.downloadHttpBytes(pageUrl, timeoutMs = 4000)
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
                timeoutMs = 3500
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

    internal fun extractGeniusHtmlLyrics(html: String): String {
        val containerRegex = Regex(
            """data-lyrics-container="true"[^>]*>(.*?)</div>(?=<div[^>]+class="[^"]*(?:Lyrics__Footer|RightSidebar|StickyNav)|</div>\s*<div[^>]+data-lyrics-container|\s*</)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val matches = containerRegex.findAll(html).map { it.groupValues[1] }.toList()
        val rawChunk = if (matches.isNotEmpty()) {
            matches.joinToString("\n")
        } else {
            val fallbackRegex = Regex("""<div[^>]+data-lyrics-container="true"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
            fallbackRegex.findAll(html).joinToString("\n") { it.groupValues[1] }
        }
        if (rawChunk.isBlank()) return ""

        return rawChunk
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    internal fun parseLrcLines(lrcText: String): List<LyricsLine> {
        val tagRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val result = mutableListOf<LyricsLine>()

        lrcText.lineSequence().forEach { rawLine ->
            val tags = tagRegex.findAll(rawLine).toList()
            if (tags.isEmpty()) return@forEach
            val cleanText = tagRegex.replace(rawLine, "").trim()
            if (cleanText.isEmpty()) return@forEach

            for (match in tags) {
                val mins = match.groupValues[1].toLongOrNull() ?: continue
                val secs = match.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = match.groupValues[3]
                val millis = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                val totalMs = mins * 60_000L + secs * 1000L + millis
                result.add(LyricsLine(totalMs, cleanText))
            }
        }

        return result.sortedBy { it.timeMs }
    }
}
