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
            accentColorsHex = listOf("#FF6D7A", "#FFAF7B", "#8B5CF6"),
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
        assertTrue(jsonString.contains(""""accent_colors":["#FF6D7A","#FFAF7B","#8B5CF6"]"""))
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

    @Test
    fun `verify MediaBridgeStateHolder applyOffset clamps cleanly within track bounds`() {
        assertEquals(1500L, com.example.state.MediaBridgeStateHolder.applyOffset(1000L, 5000L, 500))
        assertEquals(0L, com.example.state.MediaBridgeStateHolder.applyOffset(200L, 5000L, -500))
        assertEquals(5000L, com.example.state.MediaBridgeStateHolder.applyOffset(4800L, 5000L, 500))
        assertEquals(0L, com.example.state.MediaBridgeStateHolder.applyOffset(200L, 0L, -500))
    }

    @Test
    fun `verify ArtworkExtractor generateTrackPalette returns vivid deterministic 3-color triad`() {
        val triadA1 = com.example.media.ArtworkExtractor.generateTrackPalette("VILLIAN & madk1d", "ДИНАСТИЯ")
        val triadA2 = com.example.media.ArtworkExtractor.generateTrackPalette("  villian & madk1d ", "династия")
        val triadB = com.example.media.ArtworkExtractor.generateTrackPalette("дмитрий уткин", "ХЭППИ ХАУС")

        assertEquals(3, triadA1.size)
        assertEquals(triadA1, triadA2)
        assertTrue(triadA1.all { it.matches(Regex("^#[0-9A-F]{6}$")) })
        assertFalse("Different tracks must generate distinct triads", triadA1 == triadB)
    }

    @Test
    fun `verify ArtworkCatalogResolver cleans SoundCloud tags and splits embedded artist title`() {
        val spec = com.example.media.ArtworkCatalogResolver.buildTrackQuerySpec(
            rawArtist = "sc_reuploader",
            rawTitle = "VILLIAN & madk1d - ДИНАСТИЯ (prod. by bassgod) [slowed + reverb]"
        )
        assertEquals("VILLIAN & madk1d", spec.artist)
        assertEquals("ДИНАСТИЯ", spec.title)
        assertTrue(spec.searchQueries.any { it.contains("VILLIAN") && it.contains("ДИНАСТИЯ") })
        assertTrue(com.example.media.ArtworkCatalogResolver.isReasonableMatch(spec, "madk1d", "ДИНАСТИЯ"))
        assertFalse(com.example.media.ArtworkCatalogResolver.isReasonableMatch(spec, "Completely Other", "Random Song"))
    }

    @Test
    fun `verify LyricsCatalogResolver parses synced LRC timestamps and Genius HTML`() {
        val lrc = """
            [00:12.34]Первая строка
            [01:05.5][01:20.00]Припев
            [00:04.100]Интро
        """.trimIndent()
        val parsed = com.example.lyrics.LyricsCatalogResolver.parseLrcLines(lrc)
        assertEquals(4, parsed.size)
        assertEquals(4100L, parsed[0].timeMs)
        assertEquals("Интро", parsed[0].text)
        assertEquals(12340L, parsed[1].timeMs)
        assertEquals("Первая строка", parsed[1].text)
        assertEquals(65500L, parsed[2].timeMs)
        assertEquals("Припев", parsed[2].text)
        assertEquals(80000L, parsed[3].timeMs)

        val html = """<html><body><div data-lyrics-container="true">Line 1<br/>Line &amp; 2<p>Line 3</p></div></body></html>"""
        val extracted = com.example.lyrics.LyricsCatalogResolver.extractGeniusHtmlLyrics(html)
        assertTrue(extracted.contains("Line 1"))
        assertTrue(extracted.contains("Line & 2"))
        assertTrue(extracted.contains("Line 3"))
    }

    @Test
    fun `verify LocalHttpServer parses lyrics query params cleanly`() {
        val params = LocalHttpServer.parseQueryParams("artist=Juice%20WRLD&title=Lean+Wit+Me")
        assertEquals("Juice WRLD", params["artist"])
        assertEquals("Lean Wit Me", params["title"])
        assertTrue(LocalHttpServer.parseQueryParams("").isEmpty())
    }

    @Test
    fun `verify ContrastGuard normalizes dark burgundy into legible PlayerAccent and soft blobs`() {
        val darkBurgundy = com.example.ui.theme.ContrastGuard.parseHexRgb("#3A0A14", 0x38BDF8)
        val playerRgb = com.example.ui.theme.ContrastGuard.normalizePlayerAccentRgb(darkBurgundy)
        val playerHsl = com.example.ui.theme.ContrastGuard.rgbToHsl(playerRgb)
        assertTrue("PlayerAccent Lightness must be >= 0.70 for WCAG legibility", playerHsl.l >= 0.70f)
        assertTrue("PlayerAccent Lightness must be <= 0.86", playerHsl.l <= 0.86f)

        val blobRgb = com.example.ui.theme.ContrastGuard.normalizeAmbientBlobRgb(darkBurgundy)
        val blobHsl = com.example.ui.theme.ContrastGuard.rgbToHsl(blobRgb)
        assertTrue("AmbientBlob Lightness must stay in 0.24..0.43", blobHsl.l in 0.24f..0.43f)

        val cardTintRgb = com.example.ui.theme.ContrastGuard.normalizeCardTintRgb(darkBurgundy)
        val cardHsl = com.example.ui.theme.ContrastGuard.rgbToHsl(cardTintRgb)
        assertTrue("CardTint Lightness must stay in 0.08..0.14", cardHsl.l in 0.08f..0.14f)
    }

    @Test
    fun `verify BridgeAppUpdater semver, versionJson, and app build gradle parsing`() {
        assertTrue(com.example.updater.BridgeAppUpdater.compareSemver("v2.2.1", "2.2.0") > 0)
        assertTrue(com.example.updater.BridgeAppUpdater.compareSemver("2.3.0", "2.2.9") > 0)
        assertEquals(0, com.example.updater.BridgeAppUpdater.compareSemver("v2.2.0", "2.2.0"))
        assertTrue(com.example.updater.BridgeAppUpdater.compareSemver("2.1.5", "2.2.0") < 0)

        val jsonParsed = com.example.updater.BridgeAppUpdater.parseVersionJson(
            """{"versionCode": 5, "versionName": "2.4.0", "apkName": "ClawdBridge.apk"}"""
        )
        assertEquals(5, jsonParsed?.versionCode)
        assertEquals("2.4.0", jsonParsed?.versionName)
        assertEquals("ClawdBridge.apk", jsonParsed?.apkName)

        val gradleSample = """
            defaultConfig {
                applicationId = "com.aistudio.clawdbridge.msrv"
                versionCode = 7
                versionName = "2.5.1"
            }
        """.trimIndent()
        val gradleParsed = com.example.updater.BridgeAppUpdater.parseGradleVersionInfo(gradleSample)
        assertEquals(7, gradleParsed?.versionCode)
        assertEquals("2.5.1", gradleParsed?.versionName)
    }

    @Test
    fun `verify SmartColorExtractor preserves pure monochrome covers without inventing purple or olive`() {
        val pixels = IntArray(32 * 32) { idx ->
            when {
                idx % 5 == 0 -> 0xFF0A0A0A.toInt()
                idx % 3 == 0 -> 0xFF71717A.toInt()
                else -> 0xFFF4F4F5.toInt()
            }
        }
        val result = com.example.media.SmartColorExtractor.extractFromPixels(pixels, 32, 32)
        assertTrue("B&W manga cover must be detected as monochrome", result.isMonochrome)
        assertEquals(com.example.media.SmartColorExtractor.MONOCHROME_ACCENT_RGB, result.playerAccentRgb)
        assertEquals(listOf(0x525252, 0xA3A3A3, 0xF5F5F5), result.paletteTriadRgb)

        val grayAccent = com.example.ui.theme.ContrastGuard.normalizePlayerAccentRgb(0x808080)
        val grayHsl = com.example.ui.theme.ContrastGuard.rgbToHsl(grayAccent)
        assertTrue("ContrastGuard must keep monochrome gray saturation at 0", grayHsl.s < 0.01f)
    }

    @Test
    fun `verify SmartColorExtractor keeps scarlet hue on black-and-red cover`() {
        val pixels = IntArray(32 * 32) { idx ->
            if (idx % 4 == 0) 0xFFDC2626.toInt() else 0xFF09090B.toInt()
        }
        val result = com.example.media.SmartColorExtractor.extractFromPixels(pixels, 32, 32)
        assertFalse("Black+scarlet cover must not be classified monochrome", result.isMonochrome)

        val accentHsl = com.example.media.SmartColorExtractor.rgbToHsl(result.playerAccentRgb)
        assertTrue("PlayerAccent Hue must stay red (<=20 or >=340)", accentHsl.h <= 20f || accentHsl.h >= 340f)
        assertTrue("PlayerAccent Lightness must be in 0.72..0.82", accentHsl.l in 0.71f..0.83f)
        assertEquals(3, result.paletteTriadRgb.size)
    }

    @Test
    fun `verify waveEnvelopeFactor prevents hook artifact on short progress segments`() {
        val minWave = 60f
        val endFade = 30f
        assertEquals(0f, com.example.ui.components.waveEnvelopeFactor(2f, 5f, minWave, endFade), 1e-4f)
        assertEquals(0f, com.example.ui.components.waveEnvelopeFactor(0f, 300f, minWave, endFade), 1e-4f)
        assertEquals(0f, com.example.ui.components.waveEnvelopeFactor(300f, 300f, minWave, endFade), 1e-4f)
        assertEquals(1f, com.example.ui.components.waveEnvelopeFactor(150f, 300f, minWave, endFade), 1e-4f)
    }

    @Test
    fun `verify BridgeAppUpdater computes Git Blob SHA-1 and detects changed main APK size or sha`() {
        val tmp = java.io.File.createTempFile("git_blob_test", ".bin")
        try {
            tmp.writeText("hello world\n", Charsets.UTF_8)
            assertEquals("3b18e512dba79e4c8300dd08aeb37f8e728b8dad", com.example.updater.BridgeAppUpdater.computeGitBlobSha1(tmp))
        } finally {
            tmp.delete()
        }

        // Same versionCode, different APK size -> detected as new build
        val changedSizeEval = com.example.updater.BridgeAppUpdater.evaluateMainBranchUpdate(
            remoteCode = 2,
            remoteName = "2.2.0",
            remoteSize = 18_000_000L,
            remoteSha = "aaaa1111",
            currentVersionCode = 2,
            currentVersionName = "2.2.0",
            localSize = 17_200_000L,
            localGitSha = "bbbb2222",
            lastInstalledSha = null,
            lastInstalledVersionCode = -1
        )
        assertTrue(changedSizeEval.isUpdateAvailable)
        assertTrue(changedSizeEval.matchedByArtifactDiff)

        // Same versionCode, matching size AND sha -> up to date
        val sameEval = com.example.updater.BridgeAppUpdater.evaluateMainBranchUpdate(
            remoteCode = 2,
            remoteName = "2.2.0",
            remoteSize = 17_200_000L,
            remoteSha = "bbbb2222",
            currentVersionCode = 2,
            currentVersionName = "2.2.0",
            localSize = 17_200_000L,
            localGitSha = "bbbb2222",
            lastInstalledSha = null,
            lastInstalledVersionCode = -1
        )
        assertFalse(sameEval.isUpdateAvailable)

        // Same versionCode, just installed via updater (saved SHA matches) -> up to date
        val installedPrefEval = com.example.updater.BridgeAppUpdater.evaluateMainBranchUpdate(
            remoteCode = 2,
            remoteName = "2.2.0",
            remoteSize = 18_000_000L,
            remoteSha = "aaaa1111",
            currentVersionCode = 2,
            currentVersionName = "2.2.0",
            localSize = 17_200_000L,
            localGitSha = "bbbb2222",
            lastInstalledSha = "aaaa1111",
            lastInstalledVersionCode = 2
        )
        assertFalse(installedPrefEval.isUpdateAvailable)

        // Remote older versionCode -> never downgrade
        val downgradeEval = com.example.updater.BridgeAppUpdater.evaluateMainBranchUpdate(
            remoteCode = 1,
            remoteName = "2.1.0",
            remoteSize = 16_000_000L,
            remoteSha = "cccc3333",
            currentVersionCode = 2,
            currentVersionName = "2.2.0",
            localSize = 17_200_000L,
            localGitSha = "bbbb2222",
            lastInstalledSha = null,
            lastInstalledVersionCode = -1
        )
        assertFalse(downgradeEval.isUpdateAvailable)
    }

    @Test
    fun `verify 4-cloud normalization keeps dark covers glowing 0_35-0_55 and monochrome cool silver`() {
        val darkCrimsonCloud = com.example.ui.theme.ContrastGuard.normalizeAmbientCloudRgb(0x3A0A14)
        val crimsonHsl = com.example.ui.theme.ContrastGuard.rgbToHsl(darkCrimsonCloud)
        assertTrue("Dark cover cloud Lightness must be in 0.35..0.55 (was ${crimsonHsl.l})", crimsonHsl.l in 0.34f..0.56f)

        val monoClouds = com.example.ui.theme.computeCloudPalette(
            isTrackActive = true,
            accentColorHex = "#F4F4F5",
            accentColorsHex = listOf("#52525B", "#A1A1AA", "#F4F4F5")
        )
        assertEquals(4, monoClouds.size)
        monoClouds.forEach { color ->
            val rgb = ((color.red * 255f).toInt() shl 16) or ((color.green * 255f).toInt() shl 8) or (color.blue * 255f).toInt()
            val hsl = com.example.ui.theme.ContrastGuard.rgbToHsl(rgb)
            assertTrue("Monochrome clouds must stay cool silver-graphite, not saturated brown/purple (s=${hsl.s})", hsl.s <= 0.20f)
        }

        val idleClouds = com.example.ui.theme.computeCloudPalette(
            isTrackActive = false,
            accentColorHex = "#34D399",
            accentColorsHex = emptyList()
        )
        assertEquals(4, idleClouds.size)
    }

    @Test
    fun `verify SmartColorExtractor avoids turning off-white cream covers into muddy brown`() {
        // Off-white cream paper (#F0E6DA) + black artwork (#121212)
        val pixels = IntArray(32 * 32) { idx ->
            if (idx % 6 == 0) 0xFF121212.toInt() else 0xFFF0E6DA.toInt()
        }
        val result = com.example.media.SmartColorExtractor.extractFromPixels(pixels, 32, 32)
        assertTrue("Pale cream + black ink cover must avoid muddy brown saturation", result.isMonochrome)
    }

    @Test
    fun `verify SmartColorExtractor produces 6-color extended palette preserving rare accent and 3x3 spatial grid`() {
        // Build a 32x32 image: mostly dark/crimson, with a vivid cyan block in bottom-right zone (gx=2, gy=2)
        val pixels = IntArray(32 * 32) { idx ->
            val x = idx % 32
            val y = idx / 32
            when {
                x >= 24 && y >= 24 -> 0xFF00E5FF.toInt() // vivid cyan patch (~6% of image)
                y < 12 -> 0xFFB91C1C.toInt()              // crimson top band
                else -> 0xFF0B0D12.toInt()                // deep obsidian base
            }
        }
        val result = com.example.media.SmartColorExtractor.extractFromPixels(pixels, 32, 32)
        assertFalse(result.isMonochrome)
        assertEquals(6, result.paletteExtendedRgb.size)
        assertEquals(9, result.spatialGrid3x3Rgb.size)

        // Ensure cyan hue (~175..205 deg) survived in paletteExtendedRgb AND bottom-right grid cell (index 8) is bluish/cyan
        val hasCyanExtended = result.paletteExtendedRgb.any { rgb ->
            val hsl = com.example.media.SmartColorExtractor.rgbToHsl(rgb)
            hsl.s > 0.35f && hsl.h in 160f..215f
        }
        assertTrue("Extended 6-color palette must preserve contrasting cyan accent", hasCyanExtended)

        val bottomRightCell = result.spatialGrid3x3Rgb[8]
        val b = bottomRightCell and 0xFF
        val r = (bottomRightCell shr 16) and 0xFF
        assertTrue("Bottom-right 3x3 zone must reflect cyan/blue region over red (b=$b, r=$r)", b > r)
    }

    @Test
    fun `verify Ipv4FirstDns sorts IPv4 ahead of IPv6 cleanly`() {
        val ipv6a = java.net.InetAddress.getByAddress(
            byteArrayOf(0x26, 0x06, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        )
        val ipv4a = java.net.InetAddress.getByAddress(byteArrayOf(104, 16, 1, 1))
        val ipv6b = java.net.InetAddress.getByAddress(
            byteArrayOf(0x20, 0x01, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x88.toByte(), 0x88.toByte())
        )
        val ipv4b = java.net.InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))

        val sorted = com.example.network.Ipv4FirstDns.sortAddressesIpv4First(
            listOf(ipv6a, ipv4a, ipv6b, ipv4b)
        )
        assertEquals(4, sorted.size)
        assertTrue(sorted[0] is java.net.Inet4Address)
        assertTrue(sorted[1] is java.net.Inet4Address)
        assertTrue(sorted[2] is java.net.Inet6Address)
        assertTrue(sorted[3] is java.net.Inet6Address)
        assertEquals(ipv4a, sorted[0])
        assertEquals(ipv4b, sorted[1])
    }

    @Test
    fun `verify BackgroundStyle enum and 6-color and 3x3 LivingThemeEngine palettes`() {
        assertEquals(4, com.example.state.BackgroundStyle.entries.size)
        assertEquals(
            com.example.state.BackgroundStyle.PURE_BLACK,
            com.example.state.BackgroundStyle.fromId("pure_black")
        )
        assertEquals(
            com.example.state.BackgroundStyle.MESH_SILK,
            com.example.state.BackgroundStyle.fromId("liquid_silk")
        )
        assertEquals(
            com.example.state.BackgroundStyle.MESH_SILK,
            com.example.state.BackgroundStyle.fromId("mesh_grid_3x3")
        )
        assertEquals(
            com.example.state.BackgroundStyle.TONAL,
            com.example.state.BackgroundStyle.fromId("aurora")
        )
        assertEquals(
            com.example.state.BackgroundStyle.MESH_SILK,
            com.example.state.BackgroundStyle.fromId("unknown_style")
        )

        val ext = com.example.ui.theme.computeExtendedSixPalette(
            isTrackActive = true,
            accentColorHex = "#EF4444",
            accentColorsHex = listOf("#EF4444", "#06B6D4", "#111827"),
            extendedColorsHex = listOf("#EF4444", "#06B6D4", "#8B5CF6", "#111827", "#FDE047", "#38BDF8")
        )
        assertEquals(6, ext.size)

        val grid = com.example.ui.theme.computeGridNinePalette(
            isTrackActive = true,
            gridColorsHex = List(9) { "#3B82F6" },
            extendedFallback = ext
        )
        assertEquals(9, grid.size)
    }

    @Test
    fun `verify findActiveLyricsLineIndex and computeCompensatedSeekTargetMs`() {
        val lines = listOf(
            com.example.lyrics.LyricsLine(4_000L, "Line 1"),
            com.example.lyrics.LyricsLine(10_500L, "Line 2"),
            com.example.lyrics.LyricsLine(18_200L, "Line 3"),
            com.example.lyrics.LyricsLine(25_000L, "Line 4")
        )
        assertEquals(-1, com.example.ui.components.findActiveLyricsLineIndex(lines, 1_500L))
        assertEquals(0, com.example.ui.components.findActiveLyricsLineIndex(lines, 4_000L))
        assertEquals(0, com.example.ui.components.findActiveLyricsLineIndex(lines, 9_999L))
        assertEquals(1, com.example.ui.components.findActiveLyricsLineIndex(lines, 10_500L))
        assertEquals(2, com.example.ui.components.findActiveLyricsLineIndex(lines, 20_000L))
        assertEquals(3, com.example.ui.components.findActiveLyricsLineIndex(lines, 99_000L))
        assertEquals(-1, com.example.ui.components.findActiveLyricsLineIndex(emptyList(), 5_000L))

        // Compensating positive Bluetooth offset (+300 ms): line at 10500 display ms seeks raw player to 10200 ms
        assertEquals(
            10_200L,
            com.example.ui.components.computeCompensatedSeekTargetMs(10_500L, 180_000L, 300)
        )
        // Clamped within [0, durationMs]
        assertEquals(
            0L,
            com.example.ui.components.computeCompensatedSeekTargetMs(200L, 180_000L, 500)
        )
        assertEquals(
            180_000L,
            com.example.ui.components.computeCompensatedSeekTargetMs(179_800L, 180_000L, -500)
        )
    }

    @Test
    fun `verify LyricsCatalogResolver parses enhanced word tags and balanced Genius nested divs`() {
        val enhancedLrc = """
            [00:12.00]<00:12.00>Hello <00:12.50>world <00:13.20>today
            [00:20.00][00:40.00]<00:20.00>Chorus <00:20.60>line
        """.trimIndent()
        val parsed = com.example.lyrics.LyricsCatalogResolver.parseLrcLines(enhancedLrc)
        assertEquals(3, parsed.size)
        assertEquals("Hello world today", parsed[0].text)
        assertEquals(3, parsed[0].words.size)
        assertEquals(12_000L, parsed[0].words[0].startMs)
        assertEquals("Hello", parsed[0].words[0].text.trim())
        assertEquals(12_500L, parsed[0].words[1].startMs)
        assertEquals("world", parsed[0].words[1].text.trim())
        assertEquals(13_200L, parsed[0].words[2].startMs)
        assertEquals("today", parsed[0].words[2].text.trim())

        // Second chorus timestamp [00:40.00] preserves shifted word offsets relative to 20s -> 40s
        assertEquals("Chorus line", parsed[2].text)
        assertEquals(40_000L, parsed[2].words[0].startMs)
        assertEquals(40_600L, parsed[2].words[1].startMs)

        // Nested <div> inside Genius lyrics-container must NOT terminate lyrics extraction early
        val nestedGeniusHtml = """
            <html><body>
              <div data-lyrics-container="true">
                <div class="LyricsHeader"><div>12 Contributors</div></div>
                [Куплет 1]<br/>Первая строка &#8217;тест&#8217;<br/>
                <div class="InlineAnnotation"><div>annotation note</div></div>
                Вторая строка &mdash; продолжение
              </div>
              <div data-lyrics-container="true">Финальный куплет</div>
            </body></html>
        """.trimIndent()
        val extracted = com.example.lyrics.LyricsCatalogResolver.extractGeniusHtmlLyrics(nestedGeniusHtml)
        assertTrue(extracted.contains("Первая строка ’тест’"))
        assertTrue(extracted.contains("Вторая строка — продолжение"))
        assertTrue(extracted.contains("Финальный куплет"))
    }

    @Test
    fun `verify LyricsCatalogResolver computeInterludeBefore detects intro and long breaks`() {
        val lines = listOf(
            com.example.lyrics.LyricsLine(6_000L, "Intro ends"),
            com.example.lyrics.LyricsLine(10_000L, "Normal gap 4s"),
            com.example.lyrics.LyricsLine(18_000L, "After 8s instrumental break")
        )
        val intro = com.example.lyrics.LyricsCatalogResolver.computeInterludeBefore(lines, 0)
        assertEquals(0L, intro?.startMs)
        assertEquals(6_000L, intro?.endMs)

        assertEquals(null, com.example.lyrics.LyricsCatalogResolver.computeInterludeBefore(lines, 1))

        val midBreak = com.example.lyrics.LyricsCatalogResolver.computeInterludeBefore(lines, 2)
        assertEquals(12_200L, midBreak?.startMs)
        assertEquals(18_000L, midBreak?.endMs)
    }

    @Test
    fun `verify BridgeAppUpdater parses changelog, commit metadata helpers, and ConditionalHttpResult`() {
        val json = """
            {
              "versionCode": 9,
              "versionName": "3.1.0",
              "apkName": "ClawdBridge.apk",
              "changelog": ["120 Гц шейдеры", "Авто-обновление раз в 10 мин"]
            }
        """.trimIndent()
        val parsed = com.example.updater.BridgeAppUpdater.parseVersionJson(json)
        assertEquals(2, parsed?.changelog?.size)
        assertEquals("120 Гц шейдеры", parsed?.changelog?.firstOrNull())

        val prioritized = com.example.updater.BridgeAppUpdater.buildChangelogItems(
            versionJsonChangelog = listOf("Пункт из version.json"),
            commitMessages = listOf("Commit 1"),
            releaseBody = "- Body line 1\n- Body line 2"
        )
        assertEquals(listOf("Пункт из version.json"), prioritized)

        val formattedDate = com.example.updater.BridgeAppUpdater.formatCommitDateLabel("2026-11-14T15:30:00Z")
        assertTrue(formattedDate.isNotBlank())
        assertTrue(formattedDate.contains(":"))

        val notModifiedRes = com.example.network.BridgeHttpClient.ConditionalHttpResult(
            code = 304,
            body = null,
            etag = "\"abc123\"",
            notModified = true
        )
        assertTrue(notModifiedRes.notModified)
        assertEquals(304, notModifiedRes.code)
    }

    @Test
    fun `verify MediaBridgeForegroundService blocks background FGS starts on Android 12 plus`() {
        // Android 11 (API 30) and earlier allow starting FGS from background
        assertTrue(
            com.example.service.MediaBridgeForegroundService.canAttemptForegroundStart(
                sdkInt = 30,
                fromForegroundUi = false,
                isAppInForeground = false
            )
        )
        // Android 12+ (API 31+) must defer when app is in background
        assertFalse(
            com.example.service.MediaBridgeForegroundService.canAttemptForegroundStart(
                sdkInt = 31,
                fromForegroundUi = false,
                isAppInForeground = false
            )
        )
        assertFalse(
            com.example.service.MediaBridgeForegroundService.canAttemptForegroundStart(
                sdkInt = 34,
                fromForegroundUi = false,
                isAppInForeground = false
            )
        )
        // Allowed when triggered directly from Foreground UI or resumed Activity
        assertTrue(
            com.example.service.MediaBridgeForegroundService.canAttemptForegroundStart(
                sdkInt = 34,
                fromForegroundUi = true,
                isAppInForeground = false
            )
        )
        assertTrue(
            com.example.service.MediaBridgeForegroundService.canAttemptForegroundStart(
                sdkInt = 34,
                fromForegroundUi = false,
                isAppInForeground = true
            )
        )
    }

    @Test
    fun `verify SmartColorExtractor keeps dark crimson honest with max 8 deg hue spread`() {
        // Dark obsidian cover with ~2.5% subtle crimson lettering (must clear 1.2% cutoff)
        val pixels = IntArray(32 * 32) { idx ->
            if (idx % 40 == 0) 0xFF991B1B.toInt() else 0xFF0A0A0D.toInt()
        }
        val res = com.example.media.SmartColorExtractor.extractFromPixels(pixels, 32, 32)
        assertFalse("Cover with 2.5% dark crimson must NOT be treated as monochrome", res.isMonochrome)

        val baseHue = com.example.media.SmartColorExtractor.rgbToHsl(res.paletteTriadRgb.first()).h
        res.paletteExtendedRgb.forEach { rgb ->
            val hsl = com.example.media.SmartColorExtractor.rgbToHsl(rgb)
            if (hsl.s > 0.15f) {
                val rawDiff = kotlin.math.abs(hsl.h - baseHue)
                val circularDiff = minOf(rawDiff, 360f - rawDiff)
                assertTrue(
                    "Monotonic red cover hue drift ($circularDiff deg) must stay <= 12 deg (never mustard/orange)",
                    circularDiff <= 12f
                )
            }
        }
    }

    @Test
    fun `verify ArtworkExtractor computeTargetArtworkSidePx preserves HD and caps cleanly`() {
        assertEquals(160, com.example.media.ArtworkExtractor.computeTargetArtworkSidePx(160))
        assertEquals(600, com.example.media.ArtworkExtractor.computeTargetArtworkSidePx(600))
        assertEquals(768, com.example.media.ArtworkExtractor.computeTargetArtworkSidePx(1024))
    }

    @Test
    fun `verify LyricsSyncMode classification with BOM and computeLineWordHighlightState progression`() {
        val bomLrc = "\uFEFF[00:10.00]First line here\n[00:14.00]Second line"
        val lineParsed = com.example.lyrics.LyricsCatalogResolver.parseLrcLines(bomLrc)
        assertEquals(2, lineParsed.size)
        assertEquals(10_000L, lineParsed[0].timeMs)

        val lineSyncedRes = com.example.lyrics.LyricsResult(
            found = true,
            source = "lrclib",
            artist = "Artist",
            title = "Song",
            synced = true,
            syncedLyrics = bomLrc,
            plainLyrics = "First line here\nSecond line",
            lines = lineParsed
        )
        assertEquals(com.example.lyrics.LyricsSyncMode.LINE_SYNCED, lineSyncedRes.syncMode)

        val wordLine = com.example.lyrics.LyricsLine(
            timeMs = 10_000L,
            text = "alpha bravo charlie",
            words = listOf(
                com.example.lyrics.LyricWord(10_000L, "alpha "),
                com.example.lyrics.LyricWord(11_000L, "bravo "),
                com.example.lyrics.LyricWord(12_000L, "charlie")
            )
        )
        val wordSyncedRes = lineSyncedRes.copy(lines = listOf(wordLine))
        assertEquals(com.example.lyrics.LyricsSyncMode.WORD_SYNCED, wordSyncedRes.syncMode)

        val unsyncedRes = lineSyncedRes.copy(synced = false, syncedLyrics = "", lines = emptyList())
        assertEquals(com.example.lyrics.LyricsSyncMode.UNSYNCED, unsyncedRes.syncMode)

        // Word progression on LINE_SYNCED line across [10_000 .. 14_000]
        val lineNoWords = com.example.lyrics.LyricsLine(timeMs = 10_000L, text = "Hello bright world")
        val (idxStart, _) = com.example.ui.components.computeLineWordHighlightState(lineNoWords, 14_000L, 10_050L)
        val (idxMid, _) = com.example.ui.components.computeLineWordHighlightState(lineNoWords, 14_000L, 12_000L)
        val (idxEnd, fracEnd) = com.example.ui.components.computeLineWordHighlightState(lineNoWords, 14_000L, 13_900L)
        assertEquals(0, idxStart)
        assertTrue("Midpoint should advance past first word (was $idxMid)", idxMid >= 1)
        assertEquals(2, idxEnd)
        assertEquals(1f, fracEnd, 0.01f)

        // Exact word progression on WORD_SYNCED line
        val (wIdx, _) = com.example.ui.components.computeLineWordHighlightState(wordLine, 14_000L, 11_400L)
        assertEquals(1, wIdx)
    }

    @Test
    fun `verify MediaBridgeStateHolder resolveGraceAdjustedPositionMs holds seek during 650ms window`() {
        val heldPaused = com.example.state.MediaBridgeStateHolder.resolveGraceAdjustedPositionMs(
            calculatedPosMs = 5_000L,
            nowElapsedMs = 1_200L,
            graceUntilElapsedMs = 1_650L,
            graceTargetPosMs = 42_000L,
            durationMs = 180_000L,
            isPlaying = false
        )
        assertEquals(42_000L, heldPaused)

        val heldPlaying = com.example.state.MediaBridgeStateHolder.resolveGraceAdjustedPositionMs(
            calculatedPosMs = 5_000L,
            nowElapsedMs = 1_250L, // 250ms into grace started at 1000ms (1650 - 650)
            graceUntilElapsedMs = 1_650L,
            graceTargetPosMs = 42_000L,
            durationMs = 180_000L,
            isPlaying = true,
            playbackSpeed = 1.0f
        )
        assertEquals(42_250L, heldPlaying)

        val afterExpiry = com.example.state.MediaBridgeStateHolder.resolveGraceAdjustedPositionMs(
            calculatedPosMs = 42_700L,
            nowElapsedMs = 1_700L,
            graceUntilElapsedMs = 1_650L,
            graceTargetPosMs = 42_000L,
            durationMs = 180_000L,
            isPlaying = true
        )
        assertEquals(42_700L, afterExpiry)
    }

    @Test
    fun `verify LyricsSearchEngine v2 parser strips junk extracts feats and builds L-R R-L SoundCloud splits`() {
        val plan = com.example.lyrics.LyricsCatalogResolver.buildQueryPlan(
            rawArtist = "крапива",
            rawTitle = "i know - 6arelyhuman (prod. astral) [slowed + reverb] 🔥"
        )
        assertEquals("крапива", plan.cleanArtist)
        assertTrue(plan.hypotheses.any { it.isSplitHypothesis && it.artist == "i know" && it.title == "6arelyhuman" })
        assertTrue(plan.hypotheses.any { it.isSplitHypothesis && it.artist == "6arelyhuman" && it.title == "i know" })

        // Nuance 1: H2(R-L) matches (6arelyhuman, i know) despite noisy rawArtist "крапива"
        val matchEval = com.example.lyrics.LyricsCatalogResolver.evaluateCandidateMetadata(
            plan = plan,
            trackDurationSec = 142,
            candidateArtist = "6arelyhuman",
            candidateTitle = "i know",
            candidateDurationSec = 143,
            requireStrictNoDurationThresholds = false
        )
        assertTrue("Split hypothesis must ignore noisy rawArtist", matchEval != null && matchEval.accepted)

        // Multi-artist split on "," / "&" / "feat"
        val multiPlan = com.example.lyrics.LyricsCatalogResolver.buildQueryPlan(
            rawArtist = "reuploader_sc",
            rawTitle = "semmmyq, shinra - untitled (feat. asteria)"
        )
        assertTrue(multiPlan.featArtists.any { it.equals("asteria", ignoreCase = true) })
        val multiEval = com.example.lyrics.LyricsCatalogResolver.evaluateCandidateMetadata(
            plan = multiPlan,
            trackDurationSec = 120,
            candidateArtist = "semmmyq",
            candidateTitle = "untitled",
            candidateDurationSec = 121,
            requireStrictNoDurationThresholds = false
        )
        assertTrue("Comma-split multi-artist must match", multiEval != null && multiEval.accepted)
    }

    @Test
    fun `verify LyricsSearchEngine v2 symmetric Dice, 15s cutoff, anti-false-positive, and remix penalty`() {
        // Nuance 2: "EDM" must NOT score 1.0 against "EDM Mega Party Mix 2025"
        val dice = com.example.lyrics.LyricsCatalogResolver.computeSymmetricTokenSimilarity(
            "EDM",
            "EDM Mega Party Mix 2025"
        )
        assertTrue("Symmetric Dice similarity must reject bloated title (was $dice)", dice < 0.45f)

        val asteriaPlan = com.example.lyrics.LyricsCatalogResolver.buildQueryPlan("asteria", "MONSTER")
        // Unrelated artist "Skillet" for same title "Monster" must be REJECTED
        val falsePositive = com.example.lyrics.LyricsCatalogResolver.evaluateCandidateMetadata(
            plan = asteriaPlan,
            trackDurationSec = 0,
            candidateArtist = "Skillet",
            candidateTitle = "Monster",
            candidateDurationSec = 0,
            requireStrictNoDurationThresholds = true
        )
        assertEquals(null, falsePositive)

        // ±15s duration cutoff
        val tooFar = com.example.lyrics.LyricsCatalogResolver.evaluateCandidateMetadata(
            plan = asteriaPlan,
            trackDurationSec = 150,
            candidateArtist = "asteria",
            candidateTitle = "MONSTER",
            candidateDurationSec = 166,
            requireStrictNoDurationThresholds = false
        )
        assertEquals("Duration diff 16s must be rejected", null, tooFar)

        val originalEval = com.example.lyrics.LyricsCatalogResolver.evaluateCandidateMetadata(
            plan = asteriaPlan,
            trackDurationSec = 150,
            candidateArtist = "asteria",
            candidateTitle = "MONSTER",
            candidateDurationSec = 152,
            requireStrictNoDurationThresholds = false
        )!!
        val remixEval = com.example.lyrics.LyricsCatalogResolver.evaluateCandidateMetadata(
            plan = asteriaPlan,
            trackDurationSec = 150,
            candidateArtist = "asteria",
            candidateTitle = "MONSTER (Remix)",
            candidateDurationSec = 152,
            requireStrictNoDurationThresholds = false
        )!!
        assertTrue("Original score (${originalEval.score}) must exceed remix (${remixEval.score})", originalEval.score > remixEval.score + 30f)
    }

    @Test
    fun `verify NetEase YRC verbatim parser, Chinese credit filter, KuGou base64, and syncMode ranking`() {
        val sampleYrc = """
            [0,1000](0,500,0)作词：Test Writer
            [1000,1000](1000,500,0)作曲：Test Composer
            [12000,2500](12000,600,0)Neon (12600,800,0)lights (13400,1100,0)glow
            [15000,2000](15000,1000,0)Midnight (16000,1000,0)drive
            [18000,2000](18000,1000,0)City (19000,1000,0)wide
            [21000,2000](21000,1000,0)Feel (22000,1000,0)alive
        """.trimIndent()
        val rawYrcLines = com.example.lyrics.LyricsCatalogResolver.parseNetEaseYrc(sampleYrc)
        assertEquals(6, rawYrcLines.size)
        val filtered = com.example.lyrics.LyricsCatalogResolver.stripCreditMetaLinesIfBodyExists(rawYrcLines)
        assertEquals(4, filtered.size)
        assertEquals("Neon lights glow", filtered[0].text)
        assertEquals(3, filtered[0].words.size)
        assertEquals(12_600L, filtered[0].words[1].startMs)

        // KuGou Base64 LRC decoding
        val rawLrc = "[00:05.00]Line one\n[00:10.00]Line two\n[00:15.00]Line three"
        val b64 = java.util.Base64.getEncoder().encodeToString(rawLrc.toByteArray(Charsets.UTF_8))
        assertEquals(rawLrc, com.example.lyrics.LyricsCatalogResolver.decodeKuGouBase64Lrc(b64))

        // Ranking priority: WORD_SYNCED > LINE_SYNCED > UNSYNCED
        val plainCand = com.example.lyrics.LyricsCandidate(
            id = "p", source = "genius", artist = "A", title = "B", durationSec = 0,
            synced = false, syncedLyrics = "", plainLyrics = "text", lines = emptyList(),
            score = 190f, titleSimilarity = 1f, artistSimilarity = 1f
        )
        val lineCand = com.example.lyrics.LyricsCandidate(
            id = "l", source = "kugou", artist = "A", title = "B", durationSec = 150,
            synced = true, syncedLyrics = rawLrc, plainLyrics = "text",
            lines = com.example.lyrics.LyricsCatalogResolver.parseLrcLines(rawLrc),
            score = 160f, titleSimilarity = 0.9f, artistSimilarity = 0.9f
        )
        val wordCand = com.example.lyrics.LyricsCandidate(
            id = "w", source = "netease", artist = "A", title = "B", durationSec = 150,
            synced = true, syncedLyrics = "", plainLyrics = "text", lines = filtered,
            score = 150f, titleSimilarity = 0.9f, artistSimilarity = 0.9f
        )
        val ranked = com.example.lyrics.LyricsCatalogResolver.rankAndDeduplicateCandidates(listOf(plainCand, lineCand, wordCand))
        assertEquals(listOf("w", "l", "p"), ranked.map { it.id })
    }

    @Test
    fun `verify LocalHttpServer parses duration ms above 3600 and sec otherwise`() {
        assertEquals(185_000L, LocalHttpServer.parseDurationParamMs("185"))
        assertEquals(185_000L, LocalHttpServer.parseDurationParamMs("185000"))
        assertEquals(3_600_000L, LocalHttpServer.parseDurationParamMs("3600"))
        assertEquals(3_601L, LocalHttpServer.parseDurationParamMs("3601"))
        assertEquals(0L, LocalHttpServer.parseDurationParamMs("invalid"))
        assertEquals(0L, LocalHttpServer.parseDurationParamMs(null))
    }

    @Test
    fun `verify NetworkLogStore 40-entry ring buffer and separate GitHub API vs Raw cooldown buckets`() {
        com.example.network.NetworkLogStore.clear()
        com.example.network.HostCooldownGuard.clearAll()

        for (i in 1..45) {
            com.example.network.NetworkLogStore.record(
                category = com.example.network.NetworkCategory.LRCLIB,
                method = "GET",
                host = "lrclib.net",
                pathAndQuery = "/api/search?q=$i",
                httpCode = if (i % 10 == 0) 429 else 200,
                errorType = null,
                durationMs = 42L
            )
        }
        assertEquals(40, com.example.network.NetworkLogStore.entries.value.size)
        assertEquals("/api/search?q=45", com.example.network.NetworkLogStore.entries.value.first().pathAndQuery)
        assertEquals(4, com.example.network.NetworkLogStore.errorCount.value)

        // Separate buckets: exhausting github_api must NOT block github_raw
        val now = 1_700_000_000_000L
        com.example.network.HostCooldownGuard.recordHttpOutcome(
            category = com.example.network.NetworkCategory.GITHUB_API,
            httpCode = 403,
            retryAfterHeader = null,
            rateLimitRemainingHeader = "0",
            rateLimitResetHeader = "1700001800",
            nowMs = now
        )
        assertTrue(com.example.network.HostCooldownGuard.checkCooldown("github_api", now) != null)
        assertEquals(null, com.example.network.HostCooldownGuard.checkCooldown("github_raw", now))

        // Retry-After precedence
        val retryUntil = com.example.network.HostCooldownGuard.computeCooldownUntilMs(
            httpCode = 429,
            retryAfterHeader = "90",
            rateLimitRemainingHeader = null,
            rateLimitResetHeader = null,
            nowMs = now
        )
        assertEquals(now + 90_000L, retryUntil)

        val msg = com.example.updater.BridgeAppUpdater.formatGitHubRateLimitMessage(
            rateLimit = com.example.network.HostCooldownGuard.getLastGitHubRateLimit(),
            cooldown = com.example.network.HostCooldownGuard.checkCooldown("github_api", now),
            nowMs = now
        )
        assertTrue("Expected rate-limit message with token hint, got: $msg", msg != null && msg.contains("GitHub API") && msg.contains("Плагин"))

        com.example.network.NetworkLogStore.clear()
        com.example.network.HostCooldownGuard.clearAll()
    }

    @Test
    fun `verify Mesh Silk high-contrast palette has 2 deep shadows, vivid dominant, and crisp monochrome contrast`() {
        val darkCrimson = com.example.ui.theme.ContrastGuard.normalizeMeshSilkRgb(0x28050A)
        val crimsonHsl = com.example.ui.theme.ContrastGuard.rgbToHsl(darkCrimson)
        assertTrue("Mesh Silk vivid dominant Lightness must be in 0.38..0.52 (was ${crimsonHsl.l})", crimsonHsl.l in 0.37f..0.53f)
        assertTrue("Mesh Silk Saturation must be in 0.50..0.85 (was ${crimsonHsl.s})", crimsonHsl.s in 0.49f..0.86f)

        val meshPalette = com.example.ui.theme.computeMeshSilkPalette(
            isTrackActive = true,
            accentColorHex = "#EF4444",
            accentColorsHex = listOf("#EF4444", "#06B6D4", "#7F1D1D"),
            extendedColorsHex = listOf("#EF4444", "#06B6D4", "#991B1B", "#18181B")
        )
        assertEquals(6, meshPalette.size)

        fun toHsl(c: androidx.compose.ui.graphics.Color): com.example.ui.theme.ContrastGuard.Hsl {
            val rgb = ((c.red * 255f).toInt() shl 16) or ((c.green * 255f).toInt() shl 8) or (c.blue * 255f).toInt()
            return com.example.ui.theme.ContrastGuard.rgbToHsl(rgb)
        }
        val s0 = toHsl(meshPalette[0])
        val dom = toHsl(meshPalette[1])
        val sec = toHsl(meshPalette[2])
        val s1 = toHsl(meshPalette[3])
        assertTrue("Shadow anchor 0 L must be in 0.04..0.09 (was ${s0.l})", s0.l in 0.03f..0.10f)
        assertTrue("Shadow anchor 1 L must be in 0.04..0.09 (was ${s1.l})", s1.l in 0.03f..0.10f)
        assertTrue("Dominant L must be in 0.38..0.52 (was ${dom.l})", dom.l in 0.37f..0.53f)
        assertTrue("Secondary L must be visible (was ${sec.l})", sec.l >= 0.34f)
        assertTrue("Contrast spread maxL - minL must be >= 0.30", dom.l - s0.l >= 0.30f)

        // Monochrome Mesh Silk stays purely achromatic with strong dark-to-silver contrast
        val monoMesh = com.example.ui.theme.computeMeshSilkPalette(
            isTrackActive = true,
            accentColorHex = "#F5F5F5",
            accentColorsHex = listOf("#525252", "#A3A3A3", "#F5F5F5"),
            extendedColorsHex = com.example.media.SmartColorExtractor.MONOCHROME_EXTENDED_RGB.map {
                String.format("#%06X", it)
            }
        )
        val monoL = monoMesh.map { toHsl(it).l }
        assertTrue("Monochrome silk must stay neutral (S < 0.08)", monoMesh.all { toHsl(it).s < 0.08f })
        assertTrue("Monochrome silk contrast maxL - minL >= 0.40", (monoL.max() - monoL.min()) >= 0.40f)
    }

    @Test
    fun `verify computeWordAlphasForLine smoothstep illuminates 100 percent of words before line end`() {
        val longLine = com.example.lyrics.LyricsLine(
            timeMs = 10_000L,
            text = "First second third fourth fifth"
        )
        // Next line 10s away: singingDur caps at 5500ms -> by 15_500ms ALL words must reach 1.0f
        val atStart = com.example.ui.components.computeWordAlphasForLine(longLine, 20_000L, 10_000L, 180_000L)
        assertEquals(5, atStart.size)
        assertEquals(0f, atStart.last(), 1e-4f)

        val atHalf = com.example.ui.components.computeWordAlphasForLine(longLine, 20_000L, 12_750L, 180_000L)
        assertTrue("First word must be fully lit by midpoint", atHalf.first() > 0.99f)

        val atComplete = com.example.ui.components.computeWordAlphasForLine(longLine, 20_000L, 15_500L, 180_000L)
        assertTrue("All words must reach 1.0f (no 70 percent cutoff): $atComplete", atComplete.all { it >= 0.999f })

        // Fast 2000ms line: rawDur=2000, singingDur=1700ms -> fully lit by 11_700ms (85 percent)
        val fastLine = com.example.lyrics.LyricsLine(timeMs = 10_000L, text = "Quick short bar")
        val fastEnd = com.example.ui.components.computeWordAlphasForLine(fastLine, 12_000L, 11_750L, 180_000L)
        assertTrue("Fast line words must all hit 1.0f without artificial 3.5s minimum floor", fastEnd.all { it >= 0.999f })
    }

    @Test
    fun `verify HostCooldownGuard 3-minute soft cooldown on UnknownHost, StreamReset, and 2 timeouts`() {
        com.example.network.HostCooldownGuard.clearAll()
        val now = 1_700_000_000_000L

        // UnknownHostException triggers immediate 3-min cooldown on KuGou
        val kugouUntil = com.example.network.HostCooldownGuard.recordNetworkFailure(
            category = com.example.network.NetworkCategory.KUGOU,
            errorSimpleName = "UnknownHostException",
            nowMs = now
        )
        assertEquals(now + com.example.network.HostCooldownGuard.SOFT_NETWORK_COOLDOWN_MS, kugouUntil)
        assertTrue(com.example.network.HostCooldownGuard.checkCooldown("kugou", now + 60_000L) != null)
        assertEquals(null, com.example.network.HostCooldownGuard.checkCooldown("kugou", now + 180_001L))

        // StreamResetException triggers immediate 3-min cooldown
        val neteaseUntil = com.example.network.HostCooldownGuard.recordNetworkFailure(
            category = com.example.network.NetworkCategory.NETEASE,
            errorSimpleName = "StreamResetException",
            nowMs = now
        )
        assertEquals(now + com.example.network.HostCooldownGuard.SOFT_NETWORK_COOLDOWN_MS, neteaseUntil)

        // Single timeout does NOT trigger cooldown, 2nd consecutive timeout DOES, HTTP 200 resets streak
        val ovhTimeout1 = com.example.network.HostCooldownGuard.recordNetworkFailure(
            category = com.example.network.NetworkCategory.LYRICS_OVH,
            errorSimpleName = "SocketTimeoutException",
            nowMs = now
        )
        assertEquals(null, ovhTimeout1)

        // Reset via HTTP 200, then fail once again -> still no cooldown yet
        com.example.network.HostCooldownGuard.recordHttpOutcome(
            category = com.example.network.NetworkCategory.LYRICS_OVH,
            httpCode = 200,
            retryAfterHeader = null,
            rateLimitRemainingHeader = null,
            rateLimitResetHeader = null,
            nowMs = now + 100L
        )
        val ovhAfterReset = com.example.network.HostCooldownGuard.recordNetworkFailure(
            category = com.example.network.NetworkCategory.LYRICS_OVH,
            errorSimpleName = "SocketTimeoutException",
            nowMs = now + 200L
        )
        assertEquals(null, ovhAfterReset)

        // Second consecutive timeout triggers 3-min cooldown
        val ovhTimeout2 = com.example.network.HostCooldownGuard.recordNetworkFailure(
            category = com.example.network.NetworkCategory.LYRICS_OVH,
            errorSimpleName = "SocketTimeoutException",
            nowMs = now + 300L
        )
        assertEquals(now + 300L + com.example.network.HostCooldownGuard.SOFT_NETWORK_COOLDOWN_MS, ovhTimeout2)

        com.example.network.HostCooldownGuard.clearAll()
    }

    @Test
    fun `verify Genius cleaner strips title header, contributors, You might also like, and Embed tail`() {
        val dirtyHtml = """
            <html><body>
              <div data-lyrics-container="true">
                MAGICIAN Lyrics<br/>
                14 Contributors<br/>
                [Chorus]<br/>
                Watch me disappear<br/>
                You might also like<br/>
                Final line in verse<br/>
                27Embed
              </div>
            </body></html>
        """.trimIndent()
        val cleaned = com.example.lyrics.LyricsCatalogResolver.extractGeniusHtmlLyrics(dirtyHtml, "MAGICIAN")
        assertFalse("Must strip '<Title> Lyrics' line", cleaned.contains("MAGICIAN Lyrics"))
        assertFalse("Must strip Contributors header", cleaned.contains("Contributors"))
        assertFalse("Must strip You might also like", cleaned.contains("You might also like"))
        assertFalse("Must strip Embed suffix", cleaned.contains("Embed"))
        assertTrue(cleaned.contains("[Chorus]"))
        assertTrue(cleaned.contains("Final line in verse"))
    }

    @Test
    fun `verify formatArtworkDebugStatus omits technical uri note suffix`() {
        val hd = com.example.state.MediaBridgeStateHolder.formatArtworkDebugStatus(500, 500, "uri:content ok")
        assertEquals("Готова • 500×500 • HD", hd)
        val sd = com.example.state.MediaBridgeStateHolder.formatArtworkDebugStatus(200, 200, "MediaStyle")
        assertEquals("Готова • 200×200 • SD", sd)
    }

    @Test
    fun `verify BridgeAppUpdater deduplicates session confirmation, resets abort to UpdateAvailable, and reuses cached SHA APK`() {
        val sampleRelease = com.example.updater.RemoteApkRelease(
            sourceLabel = "Ветка main",
            versionCode = 12,
            versionName = "3.4.0",
            apkName = "ClawdBridge.apk",
            downloadUrl = "https://example.com/ClawdBridge.apk",
            sizeBytes = 120_000L,
            remoteSha = "",
            isUpdateAvailable = true
        )

        // WaitingUserConfirmation aborted -> restores UpdateAvailable
        val afterAbort = com.example.updater.BridgeAppUpdater.resolvePostInstallFailureState(
            current = com.example.updater.BridgeUpdateState.WaitingUserConfirmation(sampleRelease, sessionId = 42),
            lastRelease = sampleRelease,
            isAborted = true,
            errorMessage = null
        )
        assertTrue(afterAbort is com.example.updater.BridgeUpdateState.UpdateAvailable)

        // WaitingUserConfirmation failure -> restores UpdateAvailable instead of hanging
        val afterFail = com.example.updater.BridgeAppUpdater.resolvePostInstallFailureState(
            current = com.example.updater.BridgeUpdateState.WaitingUserConfirmation(sampleRelease, sessionId = 42),
            lastRelease = sampleRelease,
            isAborted = false,
            errorMessage = "Ошибка установки"
        )
        assertTrue(afterFail is com.example.updater.BridgeUpdateState.UpdateAvailable)

        // InstallStatusReceiver confirmation deduplication per sessionId
        com.example.updater.InstallStatusReceiver.clearHandledSessionsForTest()
        assertTrue(com.example.updater.InstallStatusReceiver.markConfirmationHandledIfNew(101))
        assertFalse(com.example.updater.InstallStatusReceiver.markConfirmationHandledIfNew(101))
        assertTrue(com.example.updater.InstallStatusReceiver.markConfirmationHandledIfNew(102))
        com.example.updater.InstallStatusReceiver.clearHandledSessionsForTest()

        // Cached APK SHA verification skips redundant downloads
        val tmpDir = java.nio.file.Files.createTempDirectory("updater_cache_test").toFile()
        try {
            val cachedFile = java.io.File(tmpDir, "ClawdBridge.apk")
            val payload = ByteArray(120_000) { i ->
                when (i) {
                    0 -> 0x50.toByte()
                    1 -> 0x4B.toByte()
                    2 -> 0x03.toByte()
                    3 -> 0x04.toByte()
                    else -> (i and 0x7F).toByte()
                }
            }
            cachedFile.writeBytes(payload)
            val actualSha = com.example.updater.BridgeAppUpdater.computeGitBlobSha1(cachedFile)!!

            val validHit = com.example.updater.BridgeAppUpdater.findValidCachedApkInDir(
                tmpDir,
                sampleRelease.copy(remoteSha = actualSha, sizeBytes = payload.size.toLong())
            )
            assertEquals(cachedFile.absolutePath, validHit?.absolutePath)

            val wrongShaHit = com.example.updater.BridgeAppUpdater.findValidCachedApkInDir(
                tmpDir,
                sampleRelease.copy(remoteSha = "0000000000000000000000000000000000000000", sizeBytes = payload.size.toLong())
            )
            assertEquals(null, wrongShaHit)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `verify LyricsCatalogResolver matches SoundCloud plus-feats and comma-glued artists against Genius`() {
        val plan = com.example.lyrics.LyricsCatalogResolver.buildQueryPlan(
            rawArtist = "lfy,дмитрий уткин",
            rawTitle = "fashion show + semmmyq + tachycardia"
        )
        assertEquals("fashion show", plan.cleanTitle)
        assertTrue(plan.featArtists.any { it.equals("дмитрий уткин", ignoreCase = true) })
        assertTrue(plan.featArtists.any { it.equals("semmmyq", ignoreCase = true) })
        assertTrue(plan.featArtists.any { it.equals("tachycardia", ignoreCase = true) })
        assertTrue(
            "Top hypotheses must query primary & secondary artist with clean coreTitle",
            plan.hypotheses.take(4).any {
                it.artist.equals("дмитрий уткин", ignoreCase = true) && it.title.equals("fashion show", ignoreCase = true)
            }
        )

        val eval = com.example.lyrics.LyricsCatalogResolver.evaluateCandidateMetadata(
            plan = plan,
            trackDurationSec = 0,
            candidateArtist = "дмитрий уткин (dmitry utkin), semmmyq & tachycardia",
            candidateTitle = "fashion show",
            candidateDurationSec = 0,
            requireStrictNoDurationThresholds = true
        )
        assertTrue("Genius multi-artist candidate must match cleanly", eval != null && eval.accepted)
        assertEquals(1.0f, eval!!.titleSim, 0.01f)
        assertEquals("Exact matching artist in multi-collab must receive full 1.0 similarity without penalty", 1.0f, eval.artistSim, 0.01f)
    }

    @Test
    fun `verify SmartColorExtractor rejects noisy dark gray and misty blue-gray pixels as strict monochrome`() {
        // Dark noisy fog (#141118) + muted cold mist (#2A2E38) with high HSL S but tiny RGB delta (< 30)
        val darkFogPixels = IntArray(32 * 32) { idx ->
            when (idx % 3) {
                0 -> 0xFF141118.toInt() // dark gray with tiny violet noise (chroma = 7/255)
                1 -> 0xFF2A2E38.toInt() // misty dark blue-gray (chroma = 14/255)
                else -> 0xFF444852.toInt() // smoke gray (chroma = 14/255)
            }
        }
        val fogRes = com.example.media.SmartColorExtractor.extractFromPixels(darkFogPixels, 32, 32)
        assertTrue("Dark noisy fog cover must be classified strictly monochrome", fogRes.isMonochrome)
        fogRes.paletteExtendedRgb.forEach { rgb ->
            assertEquals("Monochrome extended swatch must have zero chroma (S=0)", 0f, com.example.media.SmartColorExtractor.rgbToHsl(rgb).s, 0.001f)
        }

        // Light misty gray (#A8ACC4: chroma = 28/255 ~ 0.11, hsvS = 28/196 ~ 0.14) must also stay monochrome
        val mistyGrayPixels = IntArray(32 * 32) { 0xFFA8ACC4.toInt() }
        val mistyRes = com.example.media.SmartColorExtractor.extractFromPixels(mistyGrayPixels, 32, 32)
        assertTrue("Misty gray pixels must stay monochrome", mistyRes.isMonochrome)
    }
}





