package com.example.model

import java.util.Locale

/**
 * Data model representing the live playback state captured from media sessions.
 */
data class NowPlayingData(
    val hasMedia: Boolean = false,
    val player: String = "",
    val artist: String = "",
    val title: String = "",
    val album: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val positionSec: Double = 0.0,
    val playbackSpeed: Float = 1.0f,
    val isPlaying: Boolean = false,
    val positionKnown: Boolean = true,
    val accentColorHex: String = DEFAULT_ACCENT_HEX,
    val accentColorsHex: List<String> = DEFAULT_ACCENT_COLORS,
    val hasArtwork: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Formats JSON response adhering strictly to ТЗ v1.2, v1.3 and v2.0 extensions:
     * - When music is playing: full JSON with track info, album, playback_speed,
     *   accent_color, accent_colors (3-stop gradient), has_artwork, position_ms,
     *   position_sec, position_known, timestamp.
     * - When idle or paused: minimal {"has_media": false, "is_playing": false}
     */
    fun toJsonString(): String {
        return if (hasMedia && isPlaying) {
            val safePlayer = escapeJson(player)
            val safeArtist = escapeJson(artist)
            val safeTitle = escapeJson(title)
            val safeAlbum = escapeJson(album)
            val safeColor = escapeJson(accentColorHex.ifBlank { DEFAULT_ACCENT_HEX })
            val palette = resolvedPalette(safeColor)
            val paletteJson = palette.joinToString(separator = ",", prefix = "[", postfix = "]") {
                "\"${escapeJson(it)}\""
            }
            val formattedSec = String.format(Locale.US, "%.2f", positionSec)
            val formattedSpeed = String.format(Locale.US, "%.2f", playbackSpeed)

            """{"has_media":true,"player":"$safePlayer","artist":"$safeArtist","title":"$safeTitle","album":"$safeAlbum","position_ms":$positionMs,"duration_ms":$durationMs,"position_sec":$formattedSec,"playback_speed":$formattedSpeed,"is_playing":true,"position_known":$positionKnown,"accent_color":"$safeColor","accent_colors":$paletteJson,"has_artwork":$hasArtwork,"timestamp":$timestamp}"""
        } else {
            """{"has_media":false,"is_playing":false}"""
        }
    }

    /**
     * Compact JSON strictly <= maxBytes (default 512 bytes per ТЗ v1.3) for flash-memory backup.
     * Excludes heavy/optional metadata so track + position always fits cleanly.
     */
    fun toBackupJsonString(maxBytes: Int = 512): String {
        if (!hasMedia || !isPlaying) {
            return """{"has_media":false,"is_playing":false}"""
        }
        val safePlayer = escapeJson(player.take(40))
        val formattedSec = String.format(Locale.US, "%.2f", positionSec)

        fun buildCompact(a: String, t: String): String {
            return """{"has_media":true,"player":"$safePlayer","artist":"$a","title":"$t","position_ms":$positionMs,"duration_ms":$durationMs,"position_sec":$formattedSec,"is_playing":true,"position_known":$positionKnown,"timestamp":$timestamp}"""
        }

        var str = buildCompact(escapeJson(artist), escapeJson(title))
        var bytes = str.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return str

        var titleLen = title.length
        var artistLen = artist.length
        while (bytes.size > maxBytes && (titleLen > 5 || artistLen > 5)) {
            if (titleLen > artistLen) {
                titleLen = (titleLen - 4).coerceAtLeast(5)
            } else {
                artistLen = (artistLen - 4).coerceAtLeast(5)
            }
            val safeT = escapeJson(title.take(titleLen))
            val safeA = escapeJson(artist.take(artistLen))
            str = buildCompact(safeA, safeT)
            bytes = str.toByteArray(Charsets.UTF_8)
        }
        return str
    }

    /**
     * Pretty printed JSON for in-app diagnostic view
     */
    fun toPrettyJson(): String {
        return if (hasMedia && isPlaying) {
            val safePlayer = escapeJson(player)
            val safeArtist = escapeJson(artist)
            val safeTitle = escapeJson(title)
            val safeAlbum = escapeJson(album)
            val safeColor = escapeJson(accentColorHex.ifBlank { DEFAULT_ACCENT_HEX })
            val palette = resolvedPalette(safeColor)
            val paletteJson = palette.joinToString(separator = ", ", prefix = "[", postfix = "]") {
                "\"${escapeJson(it)}\""
            }
            val formattedSec = String.format(Locale.US, "%.2f", positionSec)
            val formattedSpeed = String.format(Locale.US, "%.2f", playbackSpeed)

            """{
  "has_media": true,
  "player": "$safePlayer",
  "artist": "$safeArtist",
  "title": "$safeTitle",
  "album": "$safeAlbum",
  "position_ms": $positionMs,
  "duration_ms": $durationMs,
  "position_sec": $formattedSec,
  "playback_speed": $formattedSpeed,
  "is_playing": true,
  "position_known": $positionKnown,
  "accent_color": "$safeColor",
  "accent_colors": $paletteJson,
  "has_artwork": $hasArtwork,
  "timestamp": $timestamp
}"""
        } else {
            """{
  "has_media": false,
  "is_playing": false
}"""
        }
    }

    private fun resolvedPalette(primaryHex: String): List<String> {
        val cleaned = accentColorsHex.filter { it.isNotBlank() }
        return if (cleaned.size >= 2) cleaned.take(3) else listOf(primaryHex, primaryHex, primaryHex)
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

    companion object {
        const val DEFAULT_ACCENT_HEX = "#00E5FF"
        val DEFAULT_ACCENT_COLORS = listOf("#00E5FF", "#3B82F6", "#7C3AED")
        val IDLE = NowPlayingData(hasMedia = false, isPlaying = false, positionKnown = true)
    }
}
