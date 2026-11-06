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
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val positionSec: Double = 0.0,
    val isPlaying: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Formats JSON response adhering strictly to ТЗ v1.2 specification:
     * - When music is playing: full JSON with track info and precise sub-millisecond timestamps
     * - When idle or paused: minimal {"has_media": false, "is_playing": false}
     */
    fun toJsonString(): String {
        return if (hasMedia && isPlaying) {
            val safePlayer = escapeJson(player)
            val safeArtist = escapeJson(artist)
            val safeTitle = escapeJson(title)
            val formattedSec = String.format(Locale.US, "%.2f", positionSec)

            """{"has_media":true,"player":"$safePlayer","artist":"$safeArtist","title":"$safeTitle","position_ms":$positionMs,"duration_ms":$durationMs,"position_sec":$formattedSec,"is_playing":true,"timestamp":$timestamp}"""
        } else {
            """{"has_media":false,"is_playing":false}"""
        }
    }

    /**
     * Compact JSON strictly <= maxBytes (default 200 bytes) for flash-memory backup (ТЗ 3.4)
     */
    fun toBackupJsonString(maxBytes: Int = 200): String {
        var str = toJsonString()
        var bytes = str.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return str

        var titleLen = title.length
        var artistLen = artist.length
        while (bytes.size > maxBytes && (titleLen > 5 || artistLen > 5)) {
            if (titleLen > artistLen) {
                titleLen -= 2
            } else {
                artistLen -= 2
            }
            val safeT = escapeJson(title.take(titleLen))
            val safeA = escapeJson(artist.take(artistLen))
            val safeP = escapeJson(player.take(30))
            val formattedSec = String.format(Locale.US, "%.2f", positionSec)
            str = """{"has_media":true,"player":"$safeP","artist":"$safeA","title":"$safeT","position_ms":$positionMs,"duration_ms":$durationMs,"position_sec":$formattedSec,"is_playing":true,"timestamp":$timestamp}"""
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
            val formattedSec = String.format(Locale.US, "%.2f", positionSec)

            """{
  "has_media": true,
  "player": "$safePlayer",
  "artist": "$safeArtist",
  "title": "$safeTitle",
  "position_ms": $positionMs,
  "duration_ms": $durationMs,
  "position_sec": $formattedSec,
  "is_playing": true,
  "timestamp": $timestamp
}"""
        } else {
            """{
  "has_media": false,
  "is_playing": false
}"""
        }
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
        val IDLE = NowPlayingData(hasMedia = false, isPlaying = false)
    }
}
