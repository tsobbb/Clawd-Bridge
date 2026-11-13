package com.example.state

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import com.example.media.ArtworkExtractor
import com.example.media.ExtractedArtwork
import com.example.model.NowPlayingData
import com.example.simulator.TestMediaSimulator
import com.example.storage.BackupFileWriter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe centralized state manager for media sessions, album artwork,
 * real-time playback extrapolation, and SSE event broadcasting.
 * Adheres strictly to monotonic SystemClock.elapsedRealtime() clock rules (ТЗ v1.3).
 */
object MediaBridgeStateHolder {

    private val activeController = AtomicReference<MediaController?>(null)
    private val simulatedState = AtomicReference<NowPlayingData?>(null)

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _liveState = MutableStateFlow(NowPlayingData.IDLE)
    val liveState: StateFlow<NowPlayingData> = _liveState.asStateFlow()

    private val _artworkBitmap = MutableStateFlow<Bitmap?>(null)
    val artworkBitmap: StateFlow<Bitmap?> = _artworkBitmap.asStateFlow()

    @Volatile
    private var currentArtworkBytes: ByteArray? = null

    @Volatile
    private var cachedArtworkKey: String = ""

    @Volatile
    private var cachedExtractedArtwork: ExtractedArtwork? = null

    private val _requestCount = MutableStateFlow(0L)
    val requestCount: StateFlow<Long> = _requestCount.asStateFlow()

    private val _lastRequestTimestamp = MutableStateFlow(0L)
    val lastRequestTimestamp: StateFlow<Long> = _lastRequestTimestamp.asStateFlow()

    private val _stateEvents = MutableSharedFlow<NowPlayingData>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val stateEvents: SharedFlow<NowPlayingData> = _stateEvents.asSharedFlow()

    private val requestCounter = AtomicLong(0L)
    private val lastPushedSignature = AtomicReference<String>("")

    fun setServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
        if (!connected && simulatedState.get() == null && !TestMediaSimulator.isSimulatorActive.value) {
            clearArtwork()
            updateLiveState(NowPlayingData.IDLE, forcePush = true)
        }
    }

    fun setActiveController(controller: MediaController?, forcePush: Boolean = true) {
        activeController.set(controller)
        refreshSnapshot(forcePush = forcePush)
    }

    fun setSimulatedPlayback(data: NowPlayingData?, forcePush: Boolean = true) {
        simulatedState.set(data)
        refreshSnapshot(forcePush = forcePush)
    }

    fun recordHttpRequest() {
        val count = requestCounter.incrementAndGet()
        val now = System.currentTimeMillis()
        _requestCount.value = count
        _lastRequestTimestamp.value = now
    }

    fun getCurrentArtworkBytes(): ByteArray? = currentArtworkBytes

    /**
     * Calculates the exact sub-millisecond playback position per ТЗ v1.3 formula:
     * - Strictly uses SystemClock.elapsedRealtime()
     * - Coerces position in [0, duration] (or >= 0 if duration <= 0)
     * - Handles PlaybackState.PLAYBACK_POSITION_UNKNOWN (-1) -> returns 0L to false
     */
    fun calculatePosition(state: PlaybackState, duration: Long): Pair<Long, Boolean> {
        if (state.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
            return Pair(0L, false)
        }

        var currentPos = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val timeDelta = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
            val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1.0f
            currentPos += (timeDelta * speed).toLong()
        }

        val clamped = if (duration > 0) {
            currentPos.coerceIn(0L, duration)
        } else {
            currentPos.coerceAtLeast(0L)
        }

        return Pair(clamped, true)
    }

    fun getCurrentPositionMs(state: PlaybackState, duration: Long = 0L): Long {
        return calculatePosition(state, duration).first
    }

    /**
     * Produces a real-time snapshot of the current media playback.
     * Evaluates dynamic position at the exact moment of execution.
     */
    fun getCurrentSnapshot(forcePush: Boolean = false): NowPlayingData {
        // Priority 1: Built-in simulator if active
        if (TestMediaSimulator.isSimulatorActive.value) {
            val isPlaying = TestMediaSimulator.isPlaying.value
            val snapshot = if (isPlaying) {
                val simArt = resolveSimulatorArtwork()
                val pos = TestMediaSimulator.getEstimatedPositionMs()
                val duration = TestMediaSimulator.durationMs
                val clampedPos = pos.coerceIn(0L, duration)
                val sec = clampedPos / 1000.0
                NowPlayingData(
                    hasMedia = true,
                    player = TestMediaSimulator.playerPackage,
                    artist = TestMediaSimulator.artistName,
                    title = TestMediaSimulator.trackTitle,
                    album = TestMediaSimulator.albumName,
                    positionMs = clampedPos,
                    durationMs = duration,
                    positionSec = sec,
                    playbackSpeed = 1.0f,
                    isPlaying = true,
                    positionKnown = true,
                    accentColorHex = simArt.accentColorHex,
                    hasArtwork = true,
                    timestamp = System.currentTimeMillis()
                )
            } else {
                clearArtwork()
                NowPlayingData(
                    hasMedia = false,
                    isPlaying = false,
                    positionKnown = true
                )
            }
            updateLiveState(snapshot, forcePush = forcePush)
            BackupFileWriter.scheduleWrite(snapshot)
            return snapshot
        }

        // Priority 2: System Media Session
        val controller = activeController.get() ?: run {
            clearArtwork()
            val idle = NowPlayingData.IDLE
            updateLiveState(idle, forcePush = forcePush)
            BackupFileWriter.scheduleWrite(idle)
            return idle
        }

        return try {
            val state = controller.playbackState
            if (state == null || state.state != PlaybackState.STATE_PLAYING) {
                clearArtwork()
                val idle = NowPlayingData.IDLE
                updateLiveState(idle, forcePush = forcePush)
                BackupFileWriter.scheduleWrite(idle)
                return idle
            }

            val metadata = controller.metadata
            val duration = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)

            val (posMs, positionKnown) = calculatePosition(state, duration)
            val posSec = posMs / 1000.0
            val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1.0f

            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: ""
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""
            val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val packageName = controller.packageName ?: "unknown"

            val extractedArt = resolveArtwork(packageName, artist, title, album, metadata)

            val snapshot = NowPlayingData(
                hasMedia = true,
                player = packageName,
                artist = artist,
                title = title,
                album = album,
                positionMs = posMs,
                durationMs = duration,
                positionSec = posSec,
                playbackSpeed = speed,
                isPlaying = true,
                positionKnown = positionKnown,
                accentColorHex = extractedArt?.accentColorHex ?: NowPlayingData.DEFAULT_ACCENT_HEX,
                hasArtwork = extractedArt != null,
                timestamp = System.currentTimeMillis()
            )

            updateLiveState(snapshot, forcePush = forcePush)
            BackupFileWriter.scheduleWrite(snapshot)
            snapshot
        } catch (e: Exception) {
            clearArtwork()
            val fallback = NowPlayingData.IDLE
            updateLiveState(fallback, forcePush = forcePush)
            fallback
        }
    }

    fun refreshSnapshot(forcePush: Boolean = false) {
        getCurrentSnapshot(forcePush = forcePush)
    }

    private fun resolveSimulatorArtwork(): ExtractedArtwork {
        val key = "simulator|${TestMediaSimulator.artistName}|${TestMediaSimulator.trackTitle}"
        val existing = cachedExtractedArtwork
        if (cachedArtworkKey == key && existing != null) {
            return existing
        }
        val created = ArtworkExtractor.createSimulatorArtwork()
        cachedArtworkKey = key
        cachedExtractedArtwork = created
        currentArtworkBytes = created.jpegBytes
        _artworkBitmap.value = created.thumbnail
        return created
    }

    private fun resolveArtwork(
        packageName: String,
        artist: String,
        title: String,
        album: String,
        metadata: MediaMetadata?
    ): ExtractedArtwork? {
        val hasBitmapInMeta = metadata != null && (
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null ||
                metadata.getBitmap(MediaMetadata.METADATA_KEY_ART) != null ||
                metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) != null
            )
        val key = "$packageName|$artist|$title|$album|$hasBitmapInMeta"
        if (cachedArtworkKey == key) {
            return cachedExtractedArtwork
        }
        val extracted = ArtworkExtractor.extract(metadata)
        cachedArtworkKey = key
        cachedExtractedArtwork = extracted
        currentArtworkBytes = extracted?.jpegBytes
        _artworkBitmap.value = extracted?.thumbnail
        return extracted
    }

    private fun clearArtwork() {
        if (cachedArtworkKey.isNotEmpty() || currentArtworkBytes != null || _artworkBitmap.value != null) {
            cachedArtworkKey = ""
            cachedExtractedArtwork = null
            currentArtworkBytes = null
            _artworkBitmap.value = null
        }
    }

    private fun updateLiveState(data: NowPlayingData, forcePush: Boolean = false) {
        _liveState.value = data
        val signature = "${data.hasMedia}|${data.isPlaying}|${data.player}|${data.artist}|${data.title}|${data.playbackSpeed}"
        val prev = lastPushedSignature.getAndSet(signature)
        if (forcePush || prev != signature) {
            _stateEvents.tryEmit(data)
        }
    }
}
