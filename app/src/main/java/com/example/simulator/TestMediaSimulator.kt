package com.example.simulator

import android.os.SystemClock
import com.example.model.NowPlayingData
import com.example.state.MediaBridgeStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Built-in test simulator allowing instant diagnostics and verification
 * without external streaming apps.
 * Operates purely on SystemClock.elapsedRealtime() (ТЗ v1.3).
 */
object TestMediaSimulator {

    const val playerPackage = "com.soundcloud.android"
    const val artistName = "Juice WRLD"
    const val trackTitle = "Lean Wit Me"
    const val albumName = "Goodbye & Good Riddance"
    const val durationMs = 175000L

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

        val pos = getEstimatedPositionMs()
        val data = if (_isPlaying.value) {
            NowPlayingData(
                hasMedia = true,
                player = playerPackage,
                artist = artistName,
                title = trackTitle,
                album = albumName,
                positionMs = pos,
                durationMs = durationMs,
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
