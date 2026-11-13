package com.example.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class TrackQuerySpec(
    val artist: String,
    val title: String,
    val searchQueries: List<String>
)

/**
 * Smart multi-provider cover resolver (Local MediaStore -> SoundCloud -> Genius -> Deezer -> iTunes -> MusicBrainz).
 * Includes query normalization for SoundCloud uploads ("Artist - Title", prod./feat./remix tags) and an in-memory LRU cache.
 */
object ArtworkCatalogResolver {

    private const val MAX_CACHE_ENTRIES = 48
    private val cacheLock = Any()
    private val artworkCache = object : LinkedHashMap<String, UriArtLoadResult>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UriArtLoadResult>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    fun buildTrackQuerySpec(rawArtist: String, rawTitle: String, rawAlbum: String = ""): TrackQuerySpec {
        var cleanTitle = cleanTrackTokens(rawTitle)
        var cleanArtist = cleanTrackTokens(rawArtist)

        // SoundCloud / YouTube uploads frequently embed "Artist - Title" inside title
        val splitPair = splitEmbeddedArtistTitle(cleanTitle)
        val parsedArtist: String
        val parsedTitle: String
        if (splitPair != null) {
            parsedArtist = splitPair.first
            parsedTitle = splitPair.second
        } else {
            parsedArtist = cleanArtist
            parsedTitle = cleanTitle
        }

        val candidates = linkedSetOf<String>()
        if (parsedArtist.isNotBlank() && parsedTitle.isNotBlank()) {
            candidates.add("$parsedArtist $parsedTitle")
            candidates.add("$parsedTitle $parsedArtist")
        }
        if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) {
            candidates.add("$cleanArtist $cleanTitle")
        }
        val firstArtist = parsedArtist.split(Regex("""[,&x×/]|\bfeat\b|\bft\b""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim().orEmpty()
        if (firstArtist.isNotBlank() && parsedTitle.isNotBlank()) {
            candidates.add("$firstArtist $parsedTitle")
        }
        if (rawAlbum.isNotBlank() && parsedArtist.isNotBlank()) {
            candidates.add("$parsedArtist ${cleanTrackTokens(rawAlbum)}")
        }
        if (parsedTitle.length >= 4) {
            candidates.add(parsedTitle)
        }
        if (cleanTitle.length >= 4 && cleanTitle != parsedTitle) {
            candidates.add(cleanTitle)
        }

        return TrackQuerySpec(
            artist = parsedArtist.ifBlank { cleanArtist },
            title = parsedTitle.ifBlank { cleanTitle },
            searchQueries = candidates.map { it.trim() }.filter { it.isNotBlank() }
        )
    }

    internal fun splitEmbeddedArtistTitle(title: String): Pair<String, String>? {
        val separators = listOf(" — ", " – ", " - ", " | ")
        for (sep in separators) {
            val idx = title.indexOf(sep)
            if (idx in 2 until (title.length - sep.length - 1)) {
                val left = title.substring(0, idx).trim()
                val right = title.substring(idx + sep.length).trim()
                if (left.isNotBlank() && right.isNotBlank()) {
                    return Pair(left, right)
                }
            }
        }
        return null
    }

    internal fun cleanTrackTokens(raw: String): String {
        return raw
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(
                Regex("""\((feat\.?|ft\.?|prod\.?|with|official|audio|video|visualizer|lyric|lyrics|slowed|reverb|speed\s*up|nightcore|remix)[^)]*\)""", RegexOption.IGNORE_CASE),
                " "
            )
            .replace(Regex("""\b(prod\.?\s+by|feat\.?|ft\.?)\s+[^-–—|(]+""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun resolveCoverWaterfall(
        context: Context?,
        rawArtist: String,
        rawTitle: String,
        rawAlbum: String = "",
        preferSoundCloudFirst: Boolean = false
    ): UriArtLoadResult {
        val cacheKey = "${rawArtist.trim().lowercase(Locale.US)}|${rawTitle.trim().lowercase(Locale.US)}"
        synchronized(cacheLock) {
            artworkCache[cacheKey]?.let { return it }
        }

        val spec = buildTrackQuerySpec(rawArtist, rawTitle, rawAlbum)
        if (spec.searchQueries.isEmpty()) {
            return UriArtLoadResult(null, "catalog:empty_query")
        }

        // 1. Local Android MediaStore audio artwork (for phone stock players / offline files)
        if (context != null) {
            val localStore = tryLocalMediaStoreArtwork(context, spec)
            if (localStore?.artwork != null) {
                cacheResult(cacheKey, localStore)
                return localStore
            }
        }

        // 2. Public Multi-Provider Online Waterfall
        val providers: List<Pair<String, (TrackQuerySpec) -> UriArtLoadResult?>> = if (preferSoundCloudFirst) {
            listOf(
                "soundcloud" to ::querySoundCloud,
                "genius" to ::queryGenius,
                "deezer" to ::queryDeezer,
                "itunes" to ::queryItunes,
                "musicbrainz" to ::queryMusicBrainz
            )
        } else {
            listOf(
                "deezer" to ::queryDeezer,
                "itunes" to ::queryItunes,
                "genius" to ::queryGenius,
                "soundcloud" to ::querySoundCloud,
                "musicbrainz" to ::queryMusicBrainz
            )
        }

        for ((_, fetcher) in providers) {
            val result = try {
                fetcher(spec)
            } catch (_: Throwable) {
                null
            }
            if (result?.artwork != null) {
                cacheResult(cacheKey, result)
                return result
            }
        }

        return UriArtLoadResult(null, "catalog:not_found(${spec.artist} - ${spec.title})")
    }

    private fun cacheResult(key: String, result: UriArtLoadResult) {
        synchronized(cacheLock) {
            artworkCache[key] = result
        }
    }

    private fun tryLocalMediaStoreArtwork(context: Context, spec: TrackQuerySpec): UriArtLoadResult? {
        if (spec.title.isBlank()) return null
        return try {
            val projection = arrayOf(
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.TITLE
            )
            val selection = "${MediaStore.Audio.Media.TITLE} LIKE ?"
            val args = arrayOf("%${spec.title.take(32)}%")
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                while (cursor.moveToNext()) {
                    val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else continue
                    val rowArtist = if (artistCol >= 0) cursor.getString(artistCol) ?: "" else ""
                    if (spec.artist.isNotBlank() &&
                        rowArtist.isNotBlank() &&
                        !rowArtist.contains(spec.artist, ignoreCase = true) &&
                        !spec.artist.contains(rowArtist, ignoreCase = true)
                    ) {
                        continue
                    }
                    val albumUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val bmp = context.contentResolver.loadThumbnail(albumUri, Size(256, 256), null)
                            val art = ArtworkExtractor.fromBitmap(bmp)
                            if (art != null) {
                                return UriArtLoadResult(art, "mediastore:thumb ok")
                            }
                        } catch (_: Throwable) {}
                    }
                    val uriRes = ArtworkExtractor.loadFromUriDetailed(context, albumUri.toString())
                    if (uriRes.artwork != null) {
                        return UriArtLoadResult(uriRes.artwork, "mediastore:uri ok")
                    }
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun querySoundCloud(spec: TrackQuerySpec): UriArtLoadResult? {
        val queries = spec.searchQueries.take(2)
        val artworkRegex = Regex("""https://i1\.sndcdn\.com/artworks-[A-Za-z0-9_-]+-[a-z0-9]+\.(?:jpg|jpeg|png|webp)""")
        for (q in queries) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            // 1. Mobile & desktop SoundCloud search pages often embed artworks directly in SSR HTML/JSON
            val searchUrls = listOf(
                "https://soundcloud.com/search/sounds?q=$encoded",
                "https://m.soundcloud.com/search?q=$encoded"
            )
            for (pageUrl in searchUrls) {
                val (bytes, _) = ArtworkExtractor.downloadHttpBytes(pageUrl, timeoutMs = 3500)
                if (bytes == null) continue
                val html = String(bytes, Charsets.UTF_8)
                val match = artworkRegex.find(html)?.value ?: continue
                val upgradedUrl = upgradeSoundCloudArtworkUrl(match)
                val loaded = loadImageUrl(upgradedUrl, "soundcloud:web ok")
                    ?: loadImageUrl(match, "soundcloud:web ok")
                if (loaded != null) return loaded
            }
        }
        return null
    }

    private fun upgradeSoundCloudArtworkUrl(rawUrl: String): String {
        return rawUrl
            .replace("-t50x50.", "-t300x300.")
            .replace("-large.", "-t300x300.")
            .replace("-small.", "-t300x300.")
            .replace("-badge.", "-t300x300.")
    }

    private fun queryGenius(spec: TrackQuerySpec): UriArtLoadResult? {
        for (q in spec.searchQueries.take(3)) {
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
                    val title = result.optString("title")
                    val artist = result.optJSONObject("primary_artist")?.optString("name") ?: ""
                    if (!isReasonableMatch(spec, artist, title)) continue
                    val artUrl = result.optString("song_art_image_thumbnail_url")
                        .ifBlank { result.optString("song_art_image_url") }
                        .ifBlank { result.optString("header_image_thumbnail_url") }
                    if (artUrl.isBlank() || artUrl.contains("default_cover", ignoreCase = true)) continue
                    val loaded = loadImageUrl(artUrl, "genius ok")
                    if (loaded != null) return loaded
                }
            }
        }
        return null
    }

    private fun queryDeezer(spec: TrackQuerySpec): UriArtLoadResult? {
        for (q in spec.searchQueries.take(3)) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val (bytes, _) = ArtworkExtractor.downloadHttpBytes(
                "https://api.deezer.com/search?q=$encoded&limit=4",
                timeoutMs = 3500
            )
            if (bytes == null) continue
            val data = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("data") ?: continue
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val title = item.optString("title")
                val artist = item.optJSONObject("artist")?.optString("name") ?: ""
                if (!isReasonableMatch(spec, artist, title)) continue
                val album = item.optJSONObject("album") ?: continue
                val coverUrl = album.optString("cover_medium")
                    .ifBlank { album.optString("cover_big") }
                    .ifBlank { album.optString("cover") }
                if (coverUrl.isBlank()) continue
                val loaded = loadImageUrl(coverUrl, "deezer ok")
                if (loaded != null) return loaded
            }
        }
        return null
    }

    private fun queryItunes(spec: TrackQuerySpec): UriArtLoadResult? {
        for (q in spec.searchQueries.take(3)) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val (bytes, _) = ArtworkExtractor.downloadHttpBytes(
                "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=4",
                timeoutMs = 3500
            )
            if (bytes == null) continue
            val results = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val title = item.optString("trackName")
                val artist = item.optString("artistName")
                if (!isReasonableMatch(spec, artist, title)) continue
                val rawUrl = item.optString("artworkUrl100").ifBlank { item.optString("artworkUrl60") }
                if (rawUrl.isBlank()) continue
                val hiRes = rawUrl.replace("100x100bb", "300x300bb").replace("60x60bb", "300x300bb")
                val loaded = loadImageUrl(hiRes, "itunes ok")
                if (loaded != null) return loaded
            }
        }
        return null
    }

    private fun queryMusicBrainz(spec: TrackQuerySpec): UriArtLoadResult? {
        if (spec.title.isBlank()) return null
        val query = if (spec.artist.isNotBlank()) {
            """recording:"${spec.title}" AND artist:"${spec.artist}""""
        } else {
            """recording:"${spec.title}""""
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=2").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ClawdBridge/2.1 (https://github.com/tsobbb/Clawd-plugin)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) return null
            val json = conn.inputStream?.bufferedReader()?.use { it.readText() } ?: return null
            val recordings = JSONObject(json).optJSONArray("recordings") ?: return null
            for (i in 0 until recordings.length()) {
                val rec = recordings.optJSONObject(i) ?: continue
                val releases = rec.optJSONArray("releases") ?: continue
                for (r in 0 until releases.length().coerceAtMost(2)) {
                    val mbid = releases.optJSONObject(r)?.optString("id") ?: continue
                    if (mbid.isBlank()) continue
                    val coverUrl = "https://coverartarchive.org/release/$mbid/front-250"
                    val loaded = loadImageUrl(coverUrl, "musicbrainz ok")
                    if (loaded != null) return loaded
                }
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun loadImageUrl(url: String, tag: String): UriArtLoadResult? {
        val (bytes, _) = ArtworkExtractor.downloadHttpBytes(url, timeoutMs = 3500)
        if (bytes == null || bytes.isEmpty()) return null
        val decoded = ArtworkExtractor.decodeSampledBitmap(bytes) ?: return null
        val extracted = ArtworkExtractor.fromBitmap(decoded) ?: return null
        return UriArtLoadResult(extracted, tag)
    }

    internal fun isReasonableMatch(spec: TrackQuerySpec, candidateArtist: String, candidateTitle: String): Boolean {
        val normCandTitle = normalizeCompare(candidateTitle)
        val normSpecTitle = normalizeCompare(spec.title)
        if (normSpecTitle.isBlank() || normCandTitle.isBlank()) return false

        val titleMatches = normCandTitle.contains(normSpecTitle) || normSpecTitle.contains(normCandTitle)
        if (!titleMatches) {
            // Word overlap check for transliterated / remixed titles
            val specWords = normSpecTitle.split(" ").filter { it.length >= 2 }.toSet()
            val candWords = normCandTitle.split(" ").filter { it.length >= 2 }.toSet()
            if (specWords.isEmpty() || specWords.intersect(candWords).isEmpty()) return false
        }

        val normSpecArtist = normalizeCompare(spec.artist)
        val normCandArtist = normalizeCompare(candidateArtist)
        if (normSpecArtist.isBlank() || normCandArtist.isBlank()) return titleMatches

        val artistMatches = normCandArtist.contains(normSpecArtist) ||
            normSpecArtist.contains(normCandArtist) ||
            normSpecArtist.split(" ").any { it.length >= 2 && normCandArtist.contains(it) }

        return titleMatches && (artistMatches || normCandTitle == normSpecTitle)
    }

    private fun normalizeCompare(s: String): String {
        return s.lowercase(Locale.US)
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
