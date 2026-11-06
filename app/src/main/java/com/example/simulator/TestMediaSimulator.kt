package com.example.simulator

import android.os.SystemClock
import com.example.model.NowPlayingData
import com.example.state.MediaBridgeStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

/**
 * Built-in test simulator allowing instant diagnostics and verification
 * in Android emulator environments without external streaming apps.
 */
object TestMediaSimulator {

    private const val DEFAULT_PLAYER = "com.soundcloud.android"
    private const val DEFAULT_ARTIST = "Juice WRLD"
    private const val DEFAULT_TITLE = "Lean Wit Me"
    private const val DEFAULT_DURATION_MS = 175000L

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
            MediaBridgeStateHolder.setSimulatedPlayback(null)
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
            val elapsed = SystemClock.elapsedRealtime() - lastStartTime
            currentPositionMs = min(DEFAULT_DURATION_MS, currentPositionMs + elapsed)
            _isPlaying.value = false
            syncState()
        }
    }

    fun seekTo(positionMs: Long) {
        currentPositionMs = max(0L, min(DEFAULT_DURATION_MS, positionMs))
        lastStartTime = SystemClock.elapsedRealtime()
        syncState()
    }

    fun getEstimatedPositionMs(): Long {
        return if (_isPlaying.value) {
            val elapsed = SystemClock.elapsedRealtime() - lastStartTime
            min(DEFAULT_DURATION_MS, currentPositionMs + elapsed)
        } else {
            currentPositionMs
        }
    }

    private fun syncState() {
        if (!_isSimulatorActive.value) {
            MediaBridgeStateHolder.setSimulatedPlayback(null)
            return
        }

        val pos = getEstimatedPositionMs()
        val data = if (_isPlaying.value) {
            NowPlayingData(
                hasMedia = true,
                player = DEFAULT_PLAYER,
                artist = DEFAULT_ARTIST,
                title = DEFAULT_TITLE,
                positionMs = pos,
                durationMs = DEFAULT_DURATION_MS,
                positionSec = pos / 1000.0,
                isPlaying = true,
                timestamp = System.currentTimeMillis()
            )
        } else {
            NowPlayingData(
                hasMedia = false,
                isPlaying = false
            )
        }
        MediaBridgeStateHolder.setSimulatedPlayback(data)
    }
}
