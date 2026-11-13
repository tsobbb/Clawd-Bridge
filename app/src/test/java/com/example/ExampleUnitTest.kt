package com.example

import com.example.filter.AppFilterManager
import com.example.model.NowPlayingData
import com.example.plugin.ParsedSource
import com.example.plugin.PluginDownloader
import com.example.plugin.RemotePluginFile
import com.example.server.LocalHttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `verify NowPlayingData JSON format when playing matches specification`() {
        val sample = NowPlayingData(
            hasMedia = true,
            player = "com.soundcloud.android",
            artist = "Juice WRLD",
            title = "Lean Wit Me",
            album = "Goodbye & Good Riddance",
            positionMs = 24350L,
            durationMs = 175000L,
            positionSec = 24.35,
            playbackSpeed = 1.25f,
            isPlaying = true,
            positionKnown = true,
            accentColorHex = "#FF6D7A",
            hasArtwork = true,
            timestamp = 1730868200123L
        )

        val jsonString = sample.toJsonString()

        assertTrue(jsonString.contains(""""has_media":true"""))
        assertTrue(jsonString.contains(""""player":"com.soundcloud.android""""))
        assertTrue(jsonString.contains(""""artist":"Juice WRLD""""))
        assertTrue(jsonString.contains(""""title":"Lean Wit Me""""))
        assertTrue(jsonString.contains(""""album":"Goodbye & Good Riddance""""))
        assertTrue(jsonString.contains(""""position_ms":24350"""))
        assertTrue(jsonString.contains(""""duration_ms":175000"""))
        assertTrue(jsonString.contains(""""position_sec":24.35"""))
        assertTrue(jsonString.contains(""""playback_speed":1.25"""))
        assertTrue(jsonString.contains(""""is_playing":true"""))
        assertTrue(jsonString.contains(""""position_known":true"""))
        assertTrue(jsonString.contains(""""accent_color":"#FF6D7A""""))
        assertTrue(jsonString.contains(""""has_artwork":true"""))
        assertTrue(jsonString.contains(""""timestamp":1730868200123"""))

        val backupJsonString = sample.toBackupJsonString(512)
        assertTrue("Backup JSON must be <= 512 bytes", backupJsonString.toByteArray(Charsets.UTF_8).size <= 512)
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
            album = "C".repeat(500),
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

    @Test
    fun `verify PluginDownloader prioritizes latest clawd plugin and filters readme`() {
        val files = listOf(
            RemotePluginFile("README.md", "https://example.com/README.md", 100),
            RemotePluginFile("clawd v3.3.plugin", "https://example.com/3.3", 5000),
            RemotePluginFile("clawd v3.4.plugin", "https://example.com/3.4", 5200),
            RemotePluginFile("other_tool.py", "https://example.com/other.py", 1200)
        )
        val sorted = PluginDownloader.sortAndFilterPlugins(files)
        assertEquals(3, sorted.size)
        assertEquals("clawd v3.4.plugin", sorted.first().name)
    }

    @Test
    fun `verify PluginDownloader sanitizes unsafe filenames and converts py to plugin`() {
        assertEquals("clawd v3.4.plugin", PluginDownloader.sanitizeFileName("public/clawd v3.4.plugin"))
        assertEquals("test_file.plugin", PluginDownloader.sanitizeFileName("../test:file.plugin"))

        assertEquals("clawd_v3_4.plugin", PluginDownloader.resolveTargetFileName("clawd_v3_4.py", true))
        assertEquals("clawd_v3_4.py", PluginDownloader.resolveTargetFileName("clawd_v3_4.py", false))
        assertEquals("clawd_v3_4.plugin", PluginDownloader.resolveTargetFileName("clawd_v3_4.plugin", true))
    }

    @Test
    fun `verify PluginDownloader parses tree blob repo and direct urls`() {
        val tree = PluginDownloader.parseSourceUrl("https://github.com/tsobbb/Clawd-plugin/tree/main/public/")
        assertEquals(ParsedSource.GitHubDirectory("tsobbb", "Clawd-plugin", "main", "public"), tree)

        val blob = PluginDownloader.parseSourceUrl("https://github.com/tsobbb/Clawd-plugin/blob/main/public/clawd.plugin")
        assertEquals(ParsedSource.GitHubSingleFile("tsobbb", "Clawd-plugin", "main", "public/clawd.plugin"), blob)

        val root = PluginDownloader.parseSourceUrl("https://github.com/tsobbb/Clawd-plugin")
        assertEquals(ParsedSource.GitHubDirectory("tsobbb", "Clawd-plugin", null, ""), root)

        val direct = PluginDownloader.parseSourceUrl("https://raw.githubusercontent.com/tsobbb/Clawd-plugin/main/public/clawd.plugin")
        assertTrue(direct is ParsedSource.DirectUrl)
        assertEquals("clawd.plugin", (direct as ParsedSource.DirectUrl).fileName)
    }

    @Test
    fun `verify PluginDownloader picks latest underscore py plugin over bridge and kt files`() {
        val screenshotFiles = listOf(
            RemotePluginFile("ClawdBridgeService.kt", "https://example.com/ClawdBridgeService.kt", 3000),
            RemotePluginFile("clawd_bridge.py", "https://example.com/clawd_bridge.py", 4000),
            RemotePluginFile("clawd_v3_4.py", "https://example.com/clawd_v3_4.py", 5000)
        )
        val sortedScreenshot = PluginDownloader.sortAndFilterPlugins(screenshotFiles)
        assertEquals("clawd_v3_4.py", sortedScreenshot.first().name)
        assertEquals("ClawdBridgeService.kt", sortedScreenshot.last().name)

        val newerVersions = screenshotFiles + listOf(
            RemotePluginFile("clawd_v3_5.py", "https://example.com/clawd_v3_5.py", 5100),
            RemotePluginFile("clawd_v3_10.py", "https://example.com/clawd_v3_10.py", 5200)
        )
        val sortedNewer = PluginDownloader.sortAndFilterPlugins(newerVersions)
        assertEquals("clawd_v3_10.py", sortedNewer.first().name)
    }

    @Test
    fun `verify AppFilterManager ignores only when enabled`() {
        val ignored = setOf("com.google.android.youtube")
        assertFalse(AppFilterManager.evaluateIgnore(false, ignored, "com.google.android.youtube"))
        assertTrue(AppFilterManager.evaluateIgnore(true, ignored, "com.google.android.youtube"))
        assertFalse(AppFilterManager.evaluateIgnore(true, ignored, "com.spotify.music"))
    }

    @Test
    fun `verify SSE frame formatting matches standard text event stream format`() {
        val frame = LocalHttpServer.formatSseFrame("now_playing", """{"has_media":false}""")
        assertEquals("event: now_playing\ndata: {\"has_media\":false}\n\n", frame)
    }
}
