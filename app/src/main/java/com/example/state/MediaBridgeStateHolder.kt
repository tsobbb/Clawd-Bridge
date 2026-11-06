package com.example.state

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import com.example.model.NowPlayingData
import com.example.storage.BackupFileWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Thread-safe centralized state manager for media sessions and real-time playback extrapolation.
 */
object MediaBridgeStateHolder {

    private val activeController = AtomicReference<MediaController?>(null)
    private val simulatedState = AtomicReference<NowPlayingData?>(null)

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _liveState = MutableStateFlow(NowPlayingData.IDLE)
    val liveState: StateFlow<NowPlayingData> = _liveState.asStateFlow()

    private val _requestCount = MutableStateFlow(0L)
    val requestCount: StateFlow<Long> = _requestCount.asStateFlow()

    private val _lastRequestTimestamp = MutableStateFlow(0L)
    val lastRequestTimestamp: StateFlow<Long> = _lastRequestTimestamp.asStateFlow()

    private val requestCounter = AtomicLong(0L)

    fun setServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
        if (!connected && simulatedState.get() == null) {
            updateLiveState(NowPlayingData.IDLE)
        }
    }

    fun setActiveController(controller: MediaController?) {
        activeController.set(controller)
        refreshSnapshot()
    }

    fun setSimulatedPlayback(data: NowPlayingData?) {
        simulatedState.set(data)
        refreshSnapshot()
    }

    fun recordHttpRequest() {
        val count = requestCounter.incrementAndGet()
        val now = System.currentTimeMillis()
        _requestCount.value = count
        _lastRequestTimestamp.value = now
    }

    /**
     * Calculates the exact sub-millisecond playback position per ТЗ formula:
     * currentPos += (timeDelta * state.playbackSpeed).toLong()
     */
    fun getCurrentPositionMs(state: PlaybackState): Long {
        var currentPos = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val timeDelta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1.0f
            currentPos += (timeDelta * speed).toLong()
        }
        return max(0L, currentPos)
    }

    /**
     * Produces a real-time snapshot of the current media playback.
     * Evaluates dynamic position at the exact moment of execution.
     */
    fun getCurrentSnapshot(): NowPlayingData {
        // Priority 1: Built-in simulator if active
        val sim = simulatedState.get()
        if (sim != null) {
            val snapshot = if (sim.isPlaying) {
                val elapsed = SystemClock.elapsedRealtime() - sim.timestamp
                val currentPos = min(sim.durationMs, sim.positionMs + elapsed)
                val sec = currentPos / 1000.0
                sim.copy(
                    positionMs = currentPos,
                    positionSec = sec,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                sim
            }
            updateLiveState(snapshot)
            BackupFileWriter.scheduleWrite(snapshot)
            return snapshot
        }

        // Priority 2: System Media Session
        val controller = activeController.get() ?: run {
            val idle = NowPlayingData.IDLE
            updateLiveState(idle)
            BackupFileWriter.scheduleWrite(idle)
            return idle
        }

        return try {
            val state = controller.playbackState
            if (state == null || state.state != PlaybackState.STATE_PLAYING) {
                val idle = NowPlayingData.IDLE
                updateLiveState(idle)
                BackupFileWriter.scheduleWrite(idle)
                return idle
            }

            val metadata = controller.metadata
            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

            var posMs = getCurrentPositionMs(state)
            if (duration > 0) {
                posMs = min(posMs, duration)
            }
            val posSec = posMs / 1000.0

            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: ""
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""
            val packageName = controller.packageName ?: "unknown"

            val snapshot = NowPlayingData(
                hasMedia = true,
                player = packageName,
                artist = artist,
                title = title,
                positionMs = posMs,
                durationMs = duration,
                positionSec = posSec,
                isPlaying = true,
                timestamp = System.currentTimeMillis()
            )

            updateLiveState(snapshot)
            BackupFileWriter.scheduleWrite(snapshot)
            snapshot
        } catch (e: Exception) {
            val fallback = NowPlayingData.IDLE
            updateLiveState(fallback)
            fallback
        }
    }

    fun refreshSnapshot() {
        getCurrentSnapshot()
    }

    private fun updateLiveState(data: NowPlayingData) {
        _liveState.value = data
    }
}
