package com.example

import android.content.Context
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.example.model.NowPlayingData
import com.example.state.MediaBridgeStateHolder
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
            positionKnown = true,
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
        assertTrue("position_known must be true", json.getBoolean("position_known"))
        assertEquals(1730868200123L, json.getLong("timestamp"))

        val backupJsonString = sample.toBackupJsonString(512)
        assertTrue("Backup JSON must be <= 512 bytes per ТЗ v1.3", backupJsonString.toByteArray(Charsets.UTF_8).size <= 512)
    }

    @Test
    fun `verify PLAYBACK_POSITION_UNKNOWN produces position 0 and position_known false`() {
        val unknownState = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()

        val (posMs, positionKnown) = MediaBridgeStateHolder.calculatePosition(unknownState, 180000L)
        assertEquals(0L, posMs)
        assertFalse(positionKnown)

        val data = NowPlayingData(
            hasMedia = true,
            player = "com.spotify.music",
            artist = "Unknown",
            title = "Stream",
            positionMs = posMs,
            durationMs = 180000L,
            positionSec = 0.0,
            isPlaying = true,
            positionKnown = positionKnown,
            timestamp = System.currentTimeMillis()
        )

        val json = JSONObject(data.toJsonString())
        assertEquals(0L, json.getLong("position_ms"))
        assertFalse(json.getBoolean("position_known"))
    }

    @Test
    fun `verify delta is added only when STATE_PLAYING and frozen on pause`() {
        val now = SystemClock.elapsedRealtime()
        val pastUpdateTime = now - 5000L // 5 seconds ago

        // Case 1: Playing -> delta is added
        val playingState = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 10000L, 1.0f, pastUpdateTime)
            .build()

        val (posPlaying, _) = MediaBridgeStateHolder.calculatePosition(playingState, 60000L)
        assertTrue("Playing position must advance: $posPlaying >= 15000", posPlaying >= 15000L)

        // Case 2: Paused -> position remains frozen at 10000L, no delta added
        val pausedState = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED, 10000L, 0.0f, pastUpdateTime)
            .build()

        val (posPaused, knownPaused) = MediaBridgeStateHolder.calculatePosition(pausedState, 60000L)
        assertEquals("Paused position must be frozen at 10000L", 10000L, posPaused)
        assertTrue(knownPaused)
    }

    @Test
    fun `verify position is clamped between 0 and duration`() {
        val now = SystemClock.elapsedRealtime()
        val farPast = now - 500000L // 500 seconds ago

        val duration = 120000L // 2 minutes
        val stateExceeding = PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, 100000L, 1.0f, farPast)
            .build()

        val (clampedPos, _) = MediaBridgeStateHolder.calculatePosition(stateExceeding, duration)
        assertEquals("Position cannot exceed duration", duration, clampedPos)
        assertTrue("Position cannot be negative", clampedPos >= 0L)
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
