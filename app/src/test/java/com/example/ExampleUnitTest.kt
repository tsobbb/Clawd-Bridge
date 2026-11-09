package com.example

import com.example.model.NowPlayingData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `verify NowPlayingData JSON format when playing matches ТЗ specification`() {
        val sample = NowPlayingData(
            hasMedia = true,
            player = "com.soundcloud.android",
            artist = "Juice WRLD",
            title = "Lean Wit Me",
            positionMs = 24350L,
            durationMs = 175000L,
            positionSec = 24.35,
            isPlaying = true,
            positionKnown = true,
            timestamp = 1730868200123L
        )

        val jsonString = sample.toJsonString()

        assertTrue(jsonString.contains(""""has_media":true"""))
        assertTrue(jsonString.contains(""""player":"com.soundcloud.android""""))
        assertTrue(jsonString.contains(""""artist":"Juice WRLD""""))
        assertTrue(jsonString.contains(""""title":"Lean Wit Me""""))
        assertTrue(jsonString.contains(""""position_ms":24350"""))
        assertTrue(jsonString.contains(""""duration_ms":175000"""))
        assertTrue(jsonString.contains(""""position_sec":24.35"""))
        assertTrue(jsonString.contains(""""is_playing":true"""))
        assertTrue(jsonString.contains(""""position_known":true"""))
        assertTrue(jsonString.contains(""""timestamp":1730868200123"""))

        val backupJsonString = sample.toBackupJsonString(512)
        assertTrue("Backup JSON must be <= 512 bytes per ТЗ v1.3", backupJsonString.toByteArray(Charsets.UTF_8).size <= 512)
    }

    @Test
    fun `verify NowPlayingData JSON format when idle or paused`() {
        val idle = NowPlayingData(hasMedia = false, isPlaying = false)
        val jsonString = idle.toJsonString()

        assertEquals("""{"has_media":false,"is_playing":false}""", jsonString)
        assertFalse(jsonString.contains("artist"))
        assertFalse(jsonString.contains("position_ms"))
    }

    @Test
    fun `verify backup JSON size limitation truncates long artist and title`() {
        val veryLongTitle = "A".repeat(500)
        val veryLongArtist = "B".repeat(500)
        val longData = NowPlayingData(
            hasMedia = true,
            player = "com.spotify.music",
            artist = veryLongArtist,
            title = veryLongTitle,
            positionMs = 12000L,
            durationMs = 60000L,
            positionSec = 12.0,
            isPlaying = true,
            positionKnown = true,
            timestamp = 1730868200000L
        )

        val backup = longData.toBackupJsonString(512)
        assertTrue("Backup string length in bytes must be <= 512", backup.toByteArray(Charsets.UTF_8).size <= 512)
        assertTrue(backup.contains(""""has_media":true"""))
        assertTrue(backup.contains(""""is_playing":true"""))
    }

    @Test
    fun `verify JSON escaping handles quotes and newlines`() {
        val dataWithQuotes = NowPlayingData(
            hasMedia = true,
            player = "com.test",
            artist = "Artist with \"Quotes\"",
            title = "Title\nNewline",
            isPlaying = true
        )
        val json = dataWithQuotes.toJsonString()
        assertTrue(json.contains("""Artist with \"Quotes\""""))
        assertTrue(json.contains("""Title\nNewline"""))
    }
}
