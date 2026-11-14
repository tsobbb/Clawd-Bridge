package com.example.simulator

import android.os.SystemClock
import com.example.model.NowPlayingData
import com.example.state.MediaBridgeStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SimulatorTrack(
    val playerPackage: String,
    val artist: String,
    val title: String,
    val album: String,
    val durationMs: Long,
    val color1: Int,
    val color2: Int,
    val color3: Int
)

/**
 * Built-in multi-track test simulator allowing instant diagnostics,
 * cover-swipe / next-prev verification, and synced lyrics testing without external apps.
 */
object TestMediaSimulator {

    private val playlist = listOf(
        SimulatorTrack(
            playerPackage = "com.soundcloud.android",
            artist = "Juice WRLD",
            title = "Lean Wit Me",
            album = "Goodbye & Good Riddance",
            durationMs = 175000L,
            color1 = 0xFF3A1C71.toInt(),
            color2 = 0xFFD76D77.toInt(),
            color3 = 0xFFFFAF7B.toInt()
        ),
        SimulatorTrack(
            playerPackage = "com.spotify.music",
            artist = "The Weeknd",
            title = "Blinding Lights",
            album = "After Hours",
            durationMs = 200000L,
            color1 = 0xFF7F1D1D.toInt(),
            color2 = 0xFFEF4444.toInt(),
            color3 = 0xFF06B6D4.toInt()
        ),
        SimulatorTrack(
            playerPackage = "ru.yandex.music",
            artist = "Daft Punk",
            title = "Get Lucky",
            album = "Random Access Memories",
            durationMs = 248000L,
            color1 = 0xFF1E1B4B.toInt(),
            color2 = 0xFFF59E0B.toInt(),
            color3 = 0xFF38BDF8.toInt()
        )
    )

    private var currentIndex = 0

    val currentTrack: SimulatorTrack
        get() = playlist[currentIndex]

    val playerPackage: String get() = currentTrack.playerPackage
    val artistName: String get() = currentTrack.artist
    val trackTitle: String get() = currentTrack.title
    val albumName: String get() = currentTrack.album
    val durationMs: Long get() = currentTrack.durationMs

    private val _isSimulatorActive = MutableStateFlow(false)
    val isSimulatorActive: StateFlow<Boolean> = _isSimulatorActive.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var currentPositionMs = 24350L
    private var lastStartTime = 0L

    fun toggleSimulator(enable: Boolean) {
        _isSimulatorActive.value = enable
        if (enable) {
            play()
        } else {
            _isPlaying.value = false
            MediaBridgeStateHolder.setSimulatedPlayback(null, forcePush = true)
        }
    }

    fun play() {
        if (!_isSimulatorActive.value) {
            _isSimulatorActive.value = true
        }
        _isPlaying.value = true
        lastStartTime = SystemClock.elapsedRealtime()
        syncState()
    }

    fun pause() {
        if (_isPlaying.value) {
            val elapsed = (SystemClock.elapsedRealtime() - lastStartTime).coerceAtLeast(0L)
            currentPositionMs = (currentPositionMs + elapsed).coerceIn(0L, durationMs)
            _isPlaying.value = false
            syncState()
        }
    }

    fun nextTrack() {
        currentIndex = (currentIndex + 1) % playlist.size
        currentPositionMs = 15000L
        lastStartTime = SystemClock.elapsedRealtime()
        if (!_isSimulatorActive.value) _isSimulatorActive.value = true
        _isPlaying.value = true
        syncState()
    }

    fun previousTrack() {
        currentIndex = (currentIndex - 1 + playlist.size) % playlist.size
        currentPositionMs = 15000L
        lastStartTime = SystemClock.elapsedRealtime()
        if (!_isSimulatorActive.value) _isSimulatorActive.value = true
        _isPlaying.value = true
        syncState()
    }

    fun seekTo(positionMs: Long) {
        currentPositionMs = positionMs.coerceIn(0L, durationMs)
        lastStartTime = SystemClock.elapsedRealtime()
        syncState()
    }

    fun getEstimatedPositionMs(): Long {
        return if (_isPlaying.value) {
            val elapsed = (SystemClock.elapsedRealtime() - lastStartTime).coerceAtLeast(0L)
            (currentPositionMs + elapsed).coerceIn(0L, durationMs)
        } else {
            currentPositionMs.coerceIn(0L, durationMs)
        }
    }

    private fun syncState() {
        if (!_isSimulatorActive.value) {
            MediaBridgeStateHolder.setSimulatedPlayback(null, forcePush = true)
            return
        }

        val track = currentTrack
        val pos = getEstimatedPositionMs()
        val data = if (_isPlaying.value) {
            NowPlayingData(
                hasMedia = true,
                player = track.playerPackage,
                artist = track.artist,
                title = track.title,
                album = track.album,
                positionMs = pos,
                durationMs = track.durationMs,
                positionSec = pos / 1000.0,
                playbackSpeed = 1.0f,
                isPlaying = true,
                positionKnown = true,
                accentColorHex = "#FF6D7A",
                hasArtwork = true,
                timestamp = System.currentTimeMillis()
            )
        } else {
            NowPlayingData(
                hasMedia = false,
                isPlaying = false,
                positionKnown = true
            )
        }
        MediaBridgeStateHolder.setSimulatedPlayback(data, forcePush = true)
    }
}
