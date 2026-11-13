package com.example.state

import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe centralized state manager for media sessions, album artwork,
 * real-time playback extrapolation, and SSE event broadcasting.
 * Adheres strictly to monotonic SystemClock.elapsedRealtime() clock rules (ТЗ v1.3).
 */
object MediaBridgeStateHolder {

    private data class NotificationArtEntry(
        val bitmap: Bitmap,
        val notifTitle: String,
        val revision: Long
    )

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeController = AtomicReference<MediaController?>(null)
    private val simulatedState = AtomicReference<NowPlayingData?>(null)

    @Volatile
    private var appContext: Context? = null

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    private val _liveState = MutableStateFlow(NowPlayingData.IDLE)
    val liveState: StateFlow<NowPlayingData> = _liveState.asStateFlow()

    private val _artworkBitmap = MutableStateFlow<Bitmap?>(null)
    val artworkBitmap: StateFlow<Bitmap?> = _artworkBitmap.asStateFlow()

    @Volatile
    private var currentArtworkBytes: ByteArray? = null

    private val artworkLock = Any()

    @Volatile
    private var cachedTrackKey: String = ""

    @Volatile
    private var cachedArtworkSourceKey: String = ""

    @Volatile
    private var cachedExtractedArtwork: ExtractedArtwork? = null

    @Volatile
    private var lastAttemptedArtUri: String = ""

    @Volatile
    private var inFlightArtUriKey: String = ""

    private val notificationArtMap = ConcurrentHashMap<String, NotificationArtEntry>()
    private val notificationArtRevision = AtomicLong(0L)

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

    private const val PREFS_SYNC_NAME = "clawd_bridge_sync_prefs"
    private const val KEY_SYNC_OFFSET_MS = "sync_offset_ms"
    const val MIN_SYNC_OFFSET_MS = -2000
    const val MAX_SYNC_OFFSET_MS = 2000

    private val _syncOffsetMs = MutableStateFlow(0)
    val syncOffsetMs: StateFlow<Int> = _syncOffsetMs.asStateFlow()

    private val requestCounter = AtomicLong(0L)
    private val lastPushedSignature = AtomicReference<String>("")

    fun init(context: Context) {
        if (appContext == null) {
            val ctx = context.applicationContext
            appContext = ctx
            val sp = ctx.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
            _syncOffsetMs.value = sp.getInt(KEY_SYNC_OFFSET_MS, 0)
                .coerceIn(MIN_SYNC_OFFSET_MS, MAX_SYNC_OFFSET_MS)
        }
    }

    fun setSyncOffsetMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(MIN_SYNC_OFFSET_MS, MAX_SYNC_OFFSET_MS)
        _syncOffsetMs.value = clamped
        appContext?.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putInt(KEY_SYNC_OFFSET_MS, clamped)
            ?.apply()
        refreshSnapshot(forcePush = true)
    }

    internal fun applyOffset(positionMs: Long, durationMs: Long, offsetMs: Int): Long {
        val shifted = positionMs + offsetMs
        return if (durationMs > 0) {
            shifted.coerceIn(0L, durationMs)
        } else {
            shifted.coerceAtLeast(0L)
        }
    }

    fun setServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
        if (!connected && simulatedState.get() == null && !TestMediaSimulator.isSimulatorActive.value) {
            clearArtwork()
            updateLiveState(NowPlayingData.IDLE, forcePush = true)
        }
    }

    fun setActiveController(
        controller: MediaController?,
        forcePush: Boolean = true,
        notificationArtBitmap: Bitmap? = null,
        notificationTitle: String = ""
    ) {
        val pkg = controller?.packageName
        if (!pkg.isNullOrBlank() && notificationArtBitmap != null && !notificationArtBitmap.isRecycled) {
            val rev = notificationArtRevision.incrementAndGet()
            notificationArtMap[pkg] = NotificationArtEntry(
                bitmap = notificationArtBitmap,
                notifTitle = notificationTitle,
                revision = rev
            )
        }
        activeController.set(controller)
        refreshSnapshot(forcePush = forcePush)
    }

    fun updateNotificationArtwork(packageName: String, notifTitle: String, bitmap: Bitmap) {
        if (packageName.isBlank() || bitmap.isRecycled) return
        val rev = notificationArtRevision.incrementAndGet()
        notificationArtMap[packageName] = NotificationArtEntry(
            bitmap = bitmap,
            notifTitle = notifTitle,
            revision = rev
        )
        val currentPkg = activeController.get()?.packageName
        if (currentPkg == packageName) {
            refreshSnapshot(forcePush = true)
        }
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
                val rawPos = TestMediaSimulator.getEstimatedPositionMs()
                val duration = TestMediaSimulator.durationMs
                val clampedPos = applyOffset(rawPos.coerceIn(0L, duration), duration, _syncOffsetMs.value)
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
                    accentColorsHex = simArt.accentColorsHex,
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

            val (rawPosMs, positionKnown) = calculatePosition(state, duration)
            val posMs = if (positionKnown) applyOffset(rawPosMs, duration, _syncOffsetMs.value) else 0L
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
                accentColorsHex = extractedArt?.accentColorsHex ?: NowPlayingData.DEFAULT_ACCENT_COLORS,
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
        synchronized(artworkLock) {
            val existing = cachedExtractedArtwork
            if (cachedTrackKey == key && existing != null) {
                return existing
            }
            val created = ArtworkExtractor.createSimulatorArtwork()
            cachedTrackKey = key
            cachedArtworkSourceKey = key
            cachedExtractedArtwork = created
            currentArtworkBytes = created.jpegBytes
            _artworkBitmap.value = created.thumbnail
            return created
        }
    }

    private fun resolveArtwork(
        packageName: String,
        artist: String,
        title: String,
        album: String,
        metadata: MediaMetadata?
    ): ExtractedArtwork? {
        val trackKey = "$packageName|$artist|$title|$album"
        val inlineBitmap = ArtworkExtractor.extractInlineBitmap(metadata)
        val artUri = ArtworkExtractor.extractArtworkUri(metadata)
        val notifEntry = notificationArtMap[packageName]?.takeIf { entry ->
            !entry.bitmap.isRecycled &&
                (entry.notifTitle.isBlank() || title.isBlank() ||
                    entry.notifTitle.equals(title, ignoreCase = true) ||
                    title.contains(entry.notifTitle, ignoreCase = true) ||
                    entry.notifTitle.contains(title, ignoreCase = true))
        }

        val sourceKey = when {
            inlineBitmap != null -> "$trackKey|inline|${inlineBitmap.generationId}"
            notifEntry != null -> "$trackKey|notif|${notifEntry.revision}"
            !artUri.isNullOrBlank() -> "$trackKey|uri|$artUri"
            else -> "$trackKey|none"
        }

        synchronized(artworkLock) {
            if (cachedTrackKey != trackKey) {
                cachedTrackKey = trackKey
                cachedArtworkSourceKey = ""
                cachedExtractedArtwork = null
                lastAttemptedArtUri = ""
                inFlightArtUriKey = ""
            } else if (cachedArtworkSourceKey == sourceKey && cachedExtractedArtwork != null) {
                return cachedExtractedArtwork
            }

            // 1. Inline MediaMetadata Bitmap (fastest & highest fidelity)
            if (inlineBitmap != null) {
                val extracted = ArtworkExtractor.fromBitmap(inlineBitmap)
                if (extracted != null) {
                    applyResolvedArtworkLocked(trackKey, sourceKey, extracted)
                    return extracted
                }
            }

            // 2. Notification MediaStyle Icon/Bitmap (SystemUI source, handles SoundCloud/Spotify/etc.)
            if (notifEntry != null) {
                val extracted = ArtworkExtractor.fromBitmap(notifEntry.bitmap)
                if (extracted != null) {
                    applyResolvedArtworkLocked(trackKey, sourceKey, extracted)
                    return extracted
                }
            }

            // 3. Async URI fallback (content://, file://, or https:// from METADATA_KEY_ALBUM_ART_URI / ART_URI)
            val ctx = appContext
            if (ctx != null && !artUri.isNullOrBlank()) {
                val uriRequestKey = "$trackKey|$artUri"
                if (cachedExtractedArtwork != null && lastAttemptedArtUri == artUri) {
                    return cachedExtractedArtwork
                }
                if (lastAttemptedArtUri != artUri && inFlightArtUriKey != uriRequestKey) {
                    inFlightArtUriKey = uriRequestKey
                    lastAttemptedArtUri = artUri
                    fetchArtworkFromUriAsync(ctx, trackKey, sourceKey, artUri, uriRequestKey)
                }
                return cachedExtractedArtwork
            }

            // Nothing found synchronously and no URI left to load
            if (cachedExtractedArtwork == null) {
                cachedArtworkSourceKey = sourceKey
                currentArtworkBytes = null
                _artworkBitmap.value = null
            }
            return cachedExtractedArtwork
        }
    }

    private fun applyResolvedArtworkLocked(
        trackKey: String,
        sourceKey: String,
        extracted: ExtractedArtwork
    ) {
        cachedTrackKey = trackKey
        cachedArtworkSourceKey = sourceKey
        cachedExtractedArtwork = extracted
        currentArtworkBytes = extracted.jpegBytes
        _artworkBitmap.value = extracted.thumbnail
    }

    private fun fetchArtworkFromUriAsync(
        context: Context,
        trackKey: String,
        sourceKey: String,
        artUri: String,
        uriRequestKey: String
    ) {
        ioScope.launch {
            val loaded = ArtworkExtractor.loadFromUri(context, artUri)
            var shouldRefresh = false
            synchronized(artworkLock) {
                if (inFlightArtUriKey == uriRequestKey) {
                    inFlightArtUriKey = ""
                }
                if (cachedTrackKey == trackKey && loaded != null) {
                    applyResolvedArtworkLocked(trackKey, sourceKey, loaded)
                    shouldRefresh = true
                }
            }
            if (shouldRefresh) {
                refreshSnapshot(forcePush = true)
            }
        }
    }

    private fun clearArtwork() {
        synchronized(artworkLock) {
            if (cachedTrackKey.isNotEmpty() || currentArtworkBytes != null || _artworkBitmap.value != null) {
                cachedTrackKey = ""
                cachedArtworkSourceKey = ""
                cachedExtractedArtwork = null
                lastAttemptedArtUri = ""
                inFlightArtUriKey = ""
                currentArtworkBytes = null
                _artworkBitmap.value = null
            }
        }
    }

    private fun updateLiveState(data: NowPlayingData, forcePush: Boolean = false) {
        _liveState.value = data
        val signature = "${data.hasMedia}|${data.isPlaying}|${data.player}|${data.artist}|${data.title}|${data.playbackSpeed}|${data.hasArtwork}|${data.accentColorHex}"
        val prev = lastPushedSignature.getAndSet(signature)
        if (forcePush || prev != signature) {
            _stateEvents.tryEmit(data)
        }
    }
}
