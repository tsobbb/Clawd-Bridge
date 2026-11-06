package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.NowPlayingData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Clawd Bridge", appName)
    }

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
            timestamp = 1730868200123L
        )

        val jsonString = sample.toJsonString()
        val json = JSONObject(jsonString)

        assertTrue(json.getBoolean("has_media"))
        assertEquals("com.soundcloud.android", json.getString("player"))
        assertEquals("Juice WRLD", json.getString("artist"))
        assertEquals("Lean Wit Me", json.getString("title"))
        assertEquals(24350L, json.getLong("position_ms"))
        assertEquals(175000L, json.getLong("duration_ms"))
        assertEquals(24.35, json.getDouble("position_sec"), 0.001)
        assertTrue(json.getBoolean("is_playing"))
        assertEquals(1730868200123L, json.getLong("timestamp"))
        val backupJsonString = sample.toBackupJsonString()
        assertTrue("Backup JSON must be <= 200 bytes per ТЗ", backupJsonString.toByteArray(Charsets.UTF_8).size <= 200)
    }

    @Test
    fun `verify NowPlayingData JSON format when idle or paused`() {
        val idle = NowPlayingData(hasMedia = false, isPlaying = false)
        val jsonString = idle.toJsonString()
        val json = JSONObject(jsonString)

        assertFalse(json.getBoolean("has_media"))
        assertFalse(json.getBoolean("is_playing"))
        assertFalse(json.has("artist"))
        assertFalse(json.has("position_ms"))
    }
}
