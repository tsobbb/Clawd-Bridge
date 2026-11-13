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
        val bitmap: Bitmap?,
        val uriFallback: String?,
        val notifTitle: String,
        val debugNote: String,
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

    private val _artworkDebugStatus = MutableStateFlow("Ожидание трека")
    val artworkDebugStatus: StateFlow<String> = _artworkDebugStatus.asStateFlow()

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
    private var lastAttemptedUriKey: String = ""

    @Volatile
    private var inFlightArtKey: String = ""

    @Volatile
    private var catalogFallbackAttemptedKey: String = ""

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
        notificationArtUri: String? = null,
        notificationTitle: String = "",
        notificationDebugNote: String = ""
    ) {
        val pkg = controller?.packageName
        if (!pkg.isNullOrBlank() && (notificationArtBitmap != null || !notificationArtUri.isNullOrBlank() || notificationDebugNote.isNotBlank())) {
            val rev = notificationArtRevision.incrementAndGet()
            notificationArtMap[pkg] = NotificationArtEntry(
                bitmap = notificationArtBitmap?.takeIf { !it.isRecycled },
                uriFallback = notificationArtUri,
                notifTitle = notificationTitle,
                debugNote = notificationDebugNote,
                revision = rev
            )
        }
        activeController.set(controller)
        refreshSnapshot(forcePush = forcePush)
    }

    fun updateNotificationArtwork(
        packageName: String,
        notifTitle: String,
        bitmap: Bitmap?,
        uriFallback: String?,
        debugNote: String
    ) {
        if (packageName.isBlank()) return
        val validBmp = bitmap?.takeIf { !it.isRecycled }
        if (validBmp == null && uriFallback.isNullOrBlank()) return
        val rev = notificationArtRevision.incrementAndGet()
        notificationArtMap[packageName] = NotificationArtEntry(
            bitmap = validBmp,
            uriFallback = uriFallback,
            notifTitle = notifTitle,
            debugNote = debugNote,
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
            _artworkDebugStatus.value = "Simulator gradient"
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
        val metaArtUri = ArtworkExtractor.extractArtworkUri(metadata)
        val notifEntry = notificationArtMap[packageName]?.takeIf { entry ->
            (entry.notifTitle.isBlank() || title.isBlank() ||
                entry.notifTitle.equals(title, ignoreCase = true) ||
                title.contains(entry.notifTitle, ignoreCase = true) ||
                entry.notifTitle.contains(title, ignoreCase = true))
        }
        val effectiveUri = metaArtUri ?: notifEntry?.uriFallback

        val sourceKey = when {
            inlineBitmap != null -> "$trackKey|inline|${inlineBitmap.generationId}"
            notifEntry?.bitmap != null -> "$trackKey|notif|${notifEntry.revision}"
            !effectiveUri.isNullOrBlank() -> "$trackKey|uri|$effectiveUri"
            else -> "$trackKey|catalog"
        }

        synchronized(artworkLock) {
            if (cachedTrackKey != trackKey) {
                cachedTrackKey = trackKey
                cachedArtworkSourceKey = ""
                cachedExtractedArtwork = null
                lastAttemptedUriKey = ""
                inFlightArtKey = ""
                catalogFallbackAttemptedKey = ""
            } else if (cachedExtractedArtwork != null && (cachedArtworkSourceKey == sourceKey || inlineBitmap == null && notifEntry?.bitmap == null)) {
                return cachedExtractedArtwork
            }

            // 1. Inline MediaMetadata Bitmap
            if (inlineBitmap != null) {
                val extracted = ArtworkExtractor.fromBitmap(inlineBitmap)
                if (extracted != null) {
                    applyResolvedArtworkLocked(trackKey, sourceKey, extracted, "MediaMetadata Bitmap")
                    return extracted
                }
            }

            // 2. Notification MediaStyle Icon / Bitmap
            val notifBmp = notifEntry?.bitmap?.takeIf { !it.isRecycled }
            if (notifBmp != null) {
                val extracted = ArtworkExtractor.fromBitmap(notifBmp)
                if (extracted != null) {
                    applyResolvedArtworkLocked(trackKey, sourceKey, extracted, notifEntry.debugNote)
                    return extracted
                }
            }

            // 3. Async URI (content://, file://, https://) + Public Catalog Fallback
            val ctx = appContext
            if (ctx != null && !effectiveUri.isNullOrBlank()) {
                val uriRequestKey = "$trackKey|$effectiveUri"
                if (lastAttemptedUriKey != uriRequestKey && inFlightArtKey != uriRequestKey) {
                    inFlightArtKey = uriRequestKey
                    lastAttemptedUriKey = uriRequestKey
                    _artworkDebugStatus.value = "Загрузка URI (${effectiveUri.substringBefore(':')}...)"
                    fetchArtworkAsync(
                        context = ctx,
                        trackKey = trackKey,
                        sourceKey = sourceKey,
                        artUri = effectiveUri,
                        artist = artist,
                        title = title,
                        requestKey = uriRequestKey,
                        metaSummary = ArtworkExtractor.summarizeMetadataKeys(metadata),
                        notifDebug = notifEntry?.debugNote ?: "notif=none"
                    )
                }
                return cachedExtractedArtwork
            }

            // 4. Local sources all absent/unreadable -> try Public Catalog Fallback once per track
            if (ctx != null && catalogFallbackAttemptedKey != trackKey && inFlightArtKey != trackKey && (artist.isNotBlank() || title.isNotBlank())) {
                catalogFallbackAttemptedKey = trackKey
                inFlightArtKey = trackKey
                val metaSummary = ArtworkExtractor.summarizeMetadataKeys(metadata)
                val notifSummary = notifEntry?.debugNote ?: "notif=none"
                _artworkDebugStatus.value = "Поиск обложки ($metaSummary; $notifSummary)"
                fetchCatalogFallbackAsync(
                    trackKey = trackKey,
                    sourceKey = sourceKey,
                    artist = artist,
                    title = title,
                    metaSummary = metaSummary,
                    notifSummary = notifSummary
                )
            } else if (cachedExtractedArtwork == null && inFlightArtKey.isBlank()) {
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
        extracted: ExtractedArtwork,
        debugNote: String
    ) {
        cachedTrackKey = trackKey
        cachedArtworkSourceKey = sourceKey
        cachedExtractedArtwork = extracted
        currentArtworkBytes = extracted.jpegBytes
        _artworkBitmap.value = extracted.thumbnail
        _artworkDebugStatus.value = debugNote
    }

    private fun fetchArtworkAsync(
        context: Context,
        trackKey: String,
        sourceKey: String,
        artUri: String,
        artist: String,
        title: String,
        requestKey: String,
        metaSummary: String,
        notifDebug: String
    ) {
        ioScope.launch {
            val uriRes = ArtworkExtractor.loadFromUriDetailed(context, artUri)
            var finalArtwork = uriRes.artwork
            var finalNote = uriRes.debugNote

            if (finalArtwork == null && (artist.isNotBlank() || title.isNotBlank())) {
                val catalogRes = ArtworkExtractor.fetchPublicCatalogArtwork(artist, title)
                if (catalogRes.artwork != null) {
                    finalArtwork = catalogRes.artwork
                    finalNote = "${catalogRes.debugNote} (после ${uriRes.debugNote})"
                } else {
                    finalNote = "${uriRes.debugNote}; ${catalogRes.debugNote}; $metaSummary; $notifDebug"
                }
            }

            var shouldRefresh = false
            synchronized(artworkLock) {
                if (inFlightArtKey == requestKey) {
                    inFlightArtKey = ""
                }
                if (cachedTrackKey == trackKey) {
                    if (finalArtwork != null) {
                        applyResolvedArtworkLocked(trackKey, sourceKey, finalArtwork, finalNote)
                        shouldRefresh = true
                    } else {
                        _artworkDebugStatus.value = finalNote
                    }
                }
            }
            if (shouldRefresh) {
                refreshSnapshot(forcePush = true)
            }
        }
    }

    private fun fetchCatalogFallbackAsync(
        trackKey: String,
        sourceKey: String,
        artist: String,
        title: String,
        metaSummary: String,
        notifSummary: String
    ) {
        ioScope.launch {
            val catalogRes = ArtworkExtractor.fetchPublicCatalogArtwork(artist, title)
            var shouldRefresh = false
            synchronized(artworkLock) {
                if (inFlightArtKey == trackKey) {
                    inFlightArtKey = ""
                }
                if (cachedTrackKey == trackKey) {
                    val art = catalogRes.artwork
                    if (art != null) {
                        applyResolvedArtworkLocked(trackKey, sourceKey, art, "${catalogRes.debugNote} ($metaSummary; $notifSummary)")
                        shouldRefresh = true
                    } else {
                        _artworkDebugStatus.value = "${catalogRes.debugNote} ($metaSummary; $notifSummary)"
                    }
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
                lastAttemptedUriKey = ""
                inFlightArtKey = ""
                catalogFallbackAttemptedKey = ""
                currentArtworkBytes = null
                _artworkBitmap.value = null
                _artworkDebugStatus.value = "Ожидание трека"
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
