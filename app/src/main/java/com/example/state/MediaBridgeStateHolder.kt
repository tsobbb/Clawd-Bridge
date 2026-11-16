package com.example.state

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import com.example.lyrics.LyricsCandidate
import com.example.lyrics.LyricsCatalogResolver
import com.example.lyrics.LyricsResult
import com.example.media.ArtworkCatalogResolver
import com.example.media.ArtworkExtractor
import com.example.media.ExtractedArtwork
import com.example.model.NowPlayingData
import com.example.simulator.TestMediaSimulator
import com.example.storage.BackupFileWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class BackgroundStyle(val id: String, val label: String) {
    MESH_SILK("mesh_silk", "Mesh Шёлк"),
    APPLE_MUSIC("apple_music", "Apple Music"),
    TONAL("tonal", "Тональный"),
    PURE_BLACK("pure_black", "Чистый чёрный");

    companion object {
        fun fromId(id: String?): BackgroundStyle {
            return when (id) {
                "mesh_silk", "liquid_silk", "mesh_grid_3x3" -> MESH_SILK
                "apple_music" -> APPLE_MUSIC
                "tonal", "aurora" -> TONAL
                "pure_black" -> PURE_BLACK
                else -> MESH_SILK
            }
        }
    }
}

enum class PlayerCoverStyle(val id: String, val label: String) {
    CARD("card", "Card"),
    WIDE("wide", "Wide");

    companion object {
        fun fromId(id: String?): PlayerCoverStyle = entries.firstOrNull { it.id == id } ?: CARD
    }
}

enum class PlayerTitleAlignment(val id: String, val label: String) {
    LEFT("left", "Слева"),
    CENTERED("centered", "По центру");

    companion object {
        fun fromId(id: String?): PlayerTitleAlignment = entries.firstOrNull { it.id == id } ?: LEFT
    }
}

enum class PlayerBackgroundMode(val id: String, val label: String) {
    GRADIENT("gradient", "Градиент"),
    BLUR("blur", "Размытие"),
    SOLID("solid", "Сплошной");

    companion object {
        fun fromId(id: String?): PlayerBackgroundMode = entries.firstOrNull { it.id == id } ?: GRADIENT
    }
}

enum class PlayerAccentSource(val id: String, val label: String) {
    COVER("cover", "Из обложки"),
    THEME("theme", "Из темы");

    companion object {
        fun fromId(id: String?): PlayerAccentSource = entries.firstOrNull { it.id == id } ?: COVER
    }
}

data class PlayerStyleConfig(
    val coverStyle: PlayerCoverStyle = PlayerCoverStyle.CARD,
    val titleAlignment: PlayerTitleAlignment = PlayerTitleAlignment.LEFT,
    val swipeCoverToSkip: Boolean = true,
    val wavySeekbarEnabled: Boolean = true,
    val backgroundMode: PlayerBackgroundMode = PlayerBackgroundMode.GRADIENT,
    val accentSource: PlayerAccentSource = PlayerAccentSource.COVER
)

sealed class PlayerLyricsUiState {
    data object Idle : PlayerLyricsUiState()
    data class Loading(val trackKey: String) : PlayerLyricsUiState()
    data class Loaded(val trackKey: String, val result: LyricsResult) : PlayerLyricsUiState()
    data class Error(val trackKey: String, val message: String) : PlayerLyricsUiState()
}

/**
 * Thread-safe centralized state manager for media sessions, multi-source album artwork,
 * extended palettes, playback transport controls, lyrics resolution, and SSE broadcasting.
 */
object MediaBridgeStateHolder {

    private data class NotificationArtEntry(
        val bitmap: Bitmap?,
        val uriFallback: String?,
        val notifTitle: String,
        val debugNote: String,
        val revision: Long,
        val timestampMs: Long
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

    /**
     * Retained media track state for in-app Fullscreen Player (remains populated while paused
     * so pressing Pause does not close or blank out the built-in player).
     */
    private val _playerUiState = MutableStateFlow(NowPlayingData.IDLE)
    val playerUiState: StateFlow<NowPlayingData> = _playerUiState.asStateFlow()

    private val _artworkBitmap = MutableStateFlow<Bitmap?>(null)
    val artworkBitmap: StateFlow<Bitmap?> = _artworkBitmap.asStateFlow()

    private val _artworkDebugStatus = MutableStateFlow("Ожидание трека")
    val artworkDebugStatus: StateFlow<String> = _artworkDebugStatus.asStateFlow()

    private val _lyricsUiState = MutableStateFlow<PlayerLyricsUiState>(PlayerLyricsUiState.Idle)
    val lyricsUiState: StateFlow<PlayerLyricsUiState> = _lyricsUiState.asStateFlow()

    private val _preferredLyricsProvider = MutableStateFlow("auto")
    val preferredLyricsProvider: StateFlow<String> = _preferredLyricsProvider.asStateFlow()

    private var lyricsJob: Job? = null
    private var artworkCatalogJob: Job? = null
    private var hdUpgradeJob: Job? = null

    @Volatile
    private var lastLyricsTrackKey: String = ""

    private const val AUTO_SEARCH_DEBOUNCE_MS = 500L

    @Volatile
    private var currentArtworkBytes: ByteArray? = null

    private val artworkLock = Any()

    // Track-level in-memory LRU cache so pausing/buffering recovers local/notif artwork in 0 ms
    private val trackArtworkCache = object : LinkedHashMap<String, ExtractedArtwork>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExtractedArtwork>?): Boolean {
            return size > 24
        }
    }

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

    @Volatile
    private var hdUpgradeAttemptedKey: String = ""

    @Volatile
    private var inFlightHdUpgradeKey: String = ""

    @Volatile
    private var hdUpgradedTrackKey: String = ""

    private const val SEEK_GRACE_WINDOW_MS = 650L

    @Volatile
    private var seekGraceUntilElapsedMs: Long = 0L

    @Volatile
    private var seekGraceTargetPosMs: Long = 0L

    @Volatile
    private var seekGraceTrackIdentity: String = ""

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
    private const val KEY_DYNAMIC_BACKGROUND_ENABLED = "dynamic_background_enabled"
    private const val KEY_BACKGROUND_STYLE = "background_style"
    private const val KEY_PLAYER_COVER_STYLE = "player_cover_style"
    private const val KEY_PLAYER_TITLE_ALIGN = "player_title_align"
    private const val KEY_PLAYER_SWIPE_SKIP = "player_swipe_skip"
    private const val KEY_PLAYER_WAVY_SEEK = "player_wavy_seek"
    private const val KEY_PLAYER_BG_MODE = "player_bg_mode"
    private const val KEY_PLAYER_ACCENT_SRC = "player_accent_src"
    const val MIN_SYNC_OFFSET_MS = -2000
    const val MAX_SYNC_OFFSET_MS = 2000

    private val _syncOffsetMs = MutableStateFlow(0)
    val syncOffsetMs: StateFlow<Int> = _syncOffsetMs.asStateFlow()

    private val _dynamicBackgroundEnabled = MutableStateFlow(true)
    val dynamicBackgroundEnabled: StateFlow<Boolean> = _dynamicBackgroundEnabled.asStateFlow()

    private val _backgroundStyle = MutableStateFlow(BackgroundStyle.MESH_SILK)
    val backgroundStyle: StateFlow<BackgroundStyle> = _backgroundStyle.asStateFlow()

    private val _playerStyleConfig = MutableStateFlow(PlayerStyleConfig())
    val playerStyleConfig: StateFlow<PlayerStyleConfig> = _playerStyleConfig.asStateFlow()

    private val requestCounter = AtomicLong(0L)
    private val lastPushedSignature = AtomicReference<String>("")

    @Volatile
    private var prefsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun init(context: Context) {
        if (appContext == null) {
            val ctx = context.applicationContext
            appContext = ctx
            val sp = ctx.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
            _syncOffsetMs.value = sp.getInt(KEY_SYNC_OFFSET_MS, 0)
                .coerceIn(MIN_SYNC_OFFSET_MS, MAX_SYNC_OFFSET_MS)
            _dynamicBackgroundEnabled.value = sp.getBoolean(KEY_DYNAMIC_BACKGROUND_ENABLED, true)
            _backgroundStyle.value = BackgroundStyle.fromId(sp.getString(KEY_BACKGROUND_STYLE, null))
            _playerStyleConfig.value = readPlayerStyle(sp)

            val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                when (key) {
                    KEY_DYNAMIC_BACKGROUND_ENABLED -> {
                        _dynamicBackgroundEnabled.value =
                            prefs.getBoolean(KEY_DYNAMIC_BACKGROUND_ENABLED, true)
                    }
                    KEY_SYNC_OFFSET_MS -> {
                        _syncOffsetMs.value = prefs.getInt(KEY_SYNC_OFFSET_MS, 0)
                            .coerceIn(MIN_SYNC_OFFSET_MS, MAX_SYNC_OFFSET_MS)
                    }
                    KEY_BACKGROUND_STYLE -> {
                        _backgroundStyle.value = BackgroundStyle.fromId(
                            prefs.getString(KEY_BACKGROUND_STYLE, null)
                        )
                    }
                    KEY_PLAYER_COVER_STYLE,
                    KEY_PLAYER_TITLE_ALIGN,
                    KEY_PLAYER_SWIPE_SKIP,
                    KEY_PLAYER_WAVY_SEEK,
                    KEY_PLAYER_BG_MODE,
                    KEY_PLAYER_ACCENT_SRC -> {
                        _playerStyleConfig.value = readPlayerStyle(prefs)
                    }
                }
            }
            prefsChangeListener = listener
            sp.registerOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun readPlayerStyle(sp: SharedPreferences): PlayerStyleConfig {
        return PlayerStyleConfig(
            coverStyle = PlayerCoverStyle.fromId(sp.getString(KEY_PLAYER_COVER_STYLE, null)),
            titleAlignment = PlayerTitleAlignment.fromId(sp.getString(KEY_PLAYER_TITLE_ALIGN, null)),
            swipeCoverToSkip = sp.getBoolean(KEY_PLAYER_SWIPE_SKIP, true),
            wavySeekbarEnabled = sp.getBoolean(KEY_PLAYER_WAVY_SEEK, true),
            backgroundMode = PlayerBackgroundMode.fromId(sp.getString(KEY_PLAYER_BG_MODE, null)),
            accentSource = PlayerAccentSource.fromId(sp.getString(KEY_PLAYER_ACCENT_SRC, null))
        )
    }

    fun setBackgroundStyle(style: BackgroundStyle) {
        _backgroundStyle.value = style
        appContext?.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_BACKGROUND_STYLE, style.id)
            ?.apply()
    }

    fun updatePlayerStyle(transform: (PlayerStyleConfig) -> PlayerStyleConfig) {
        val next = transform(_playerStyleConfig.value)
        _playerStyleConfig.value = next
        appContext?.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_PLAYER_COVER_STYLE, next.coverStyle.id)
            ?.putString(KEY_PLAYER_TITLE_ALIGN, next.titleAlignment.id)
            ?.putBoolean(KEY_PLAYER_SWIPE_SKIP, next.swipeCoverToSkip)
            ?.putBoolean(KEY_PLAYER_WAVY_SEEK, next.wavySeekbarEnabled)
            ?.putString(KEY_PLAYER_BG_MODE, next.backgroundMode.id)
            ?.putString(KEY_PLAYER_ACCENT_SRC, next.accentSource.id)
            ?.apply()
    }

    fun setDynamicBackgroundEnabled(enabled: Boolean) {
        _dynamicBackgroundEnabled.value = enabled
        appContext?.getSharedPreferences(PREFS_SYNC_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_DYNAMIC_BACKGROUND_ENABLED, enabled)
            ?.apply()
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

    // ---- Media Transport Controls (works seamlessly for MediaController & TestMediaSimulator) ----

    fun togglePlayPause() {
        if (TestMediaSimulator.isSimulatorActive.value) {
            if (TestMediaSimulator.isPlaying.value) TestMediaSimulator.pause()
            else TestMediaSimulator.play()
            return
        }
        val controller = activeController.get() ?: return
        runCatching {
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) controller.transportControls.pause()
            else controller.transportControls.play()
        }
    }

    fun skipToNext() {
        if (TestMediaSimulator.isSimulatorActive.value) {
            TestMediaSimulator.nextTrack()
            return
        }
        val controller = activeController.get() ?: return
        runCatching { controller.transportControls.skipToNext() }
    }

    fun skipToPrevious() {
        if (TestMediaSimulator.isSimulatorActive.value) {
            TestMediaSimulator.previousTrack()
            return
        }
        val controller = activeController.get() ?: return
        runCatching { controller.transportControls.skipToPrevious() }
    }

    internal fun resolveGraceAdjustedPositionMs(
        calculatedPosMs: Long,
        nowElapsedMs: Long,
        graceUntilElapsedMs: Long,
        graceTargetPosMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float = 1.0f
    ): Long {
        if (nowElapsedMs < graceUntilElapsedMs) {
            val startElapsedMs = graceUntilElapsedMs - SEEK_GRACE_WINDOW_MS
            val dtMs = if (isPlaying) {
                ((nowElapsedMs - startElapsedMs).coerceAtLeast(0L) * playbackSpeed.coerceAtLeast(0.25f)).toLong()
            } else {
                0L
            }
            val projected = graceTargetPosMs + dtMs
            return if (durationMs > 0L) projected.coerceIn(0L, durationMs) else projected.coerceAtLeast(0L)
        }
        return calculatedPosMs
    }

    internal fun formatArtworkDebugStatus(widthPx: Int, heightPx: Int, note: String): String {
        val quality = if (minOf(widthPx, heightPx) >= ArtworkExtractor.HD_QUALITY_MIN_PX) "HD" else "SD"
        return "Готова • ${widthPx}×${heightPx} • $quality ($note)"
    }

    fun seekToPosition(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        val snap = _playerUiState.value
        seekGraceTargetPosMs = clamped
        seekGraceUntilElapsedMs = SystemClock.elapsedRealtime() + SEEK_GRACE_WINDOW_MS
        seekGraceTrackIdentity = "${snap.player}|${snap.artist}|${snap.title}"

        if (TestMediaSimulator.isSimulatorActive.value) {
            TestMediaSimulator.seekTo(clamped)
            return
        }
        val controller = activeController.get() ?: return
        runCatching {
            controller.transportControls.seekTo(clamped)
            refreshSnapshot(forcePush = true)
        }
    }

    fun seekByDelta(deltaMs: Long) {
        val snap = _playerUiState.value
        if (!snap.hasMedia) return
        val currentRaw = if (TestMediaSimulator.isSimulatorActive.value) {
            TestMediaSimulator.getEstimatedPositionMs()
        } else {
            val ctrl = activeController.get()
            val st = ctrl?.playbackState
            val trackIdentity = "${snap.player}|${snap.artist}|${snap.title}"
            if (st != null) {
                val (calcPos, _) = calculatePosition(st, snap.durationMs)
                if (trackIdentity == seekGraceTrackIdentity) {
                    resolveGraceAdjustedPositionMs(
                        calculatedPosMs = calcPos,
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                        graceUntilElapsedMs = seekGraceUntilElapsedMs,
                        graceTargetPosMs = seekGraceTargetPosMs,
                        durationMs = snap.durationMs,
                        isPlaying = snap.isPlaying,
                        playbackSpeed = snap.playbackSpeed
                    )
                } else calcPos
            } else snap.positionMs
        }
        val target = if (snap.durationMs > 0L) {
            (currentRaw + deltaMs).coerceIn(0L, snap.durationMs)
        } else {
            (currentRaw + deltaMs).coerceAtLeast(0L)
        }
        seekToPosition(target)
    }

    // ---- Lyrics Resolution for Built-in Player ----

    fun loadLyricsForCurrentTrack(
        forceRefresh: Boolean = false,
        preferredProvider: String? = null,
        isAutomatic: Boolean = false
    ) {
        val track = _playerUiState.value.takeIf { it.hasMedia }
            ?: _liveState.value.takeIf { it.hasMedia }
            ?: return
        val effectiveProvider = preferredProvider ?: _preferredLyricsProvider.value
        if (preferredProvider != null) {
            _preferredLyricsProvider.value = preferredProvider
        }
        val trackKey = "${track.artist}|${track.title}"
        if (lastLyricsTrackKey.isNotEmpty() && lastLyricsTrackKey != trackKey) {
            // Reset any previous track's manual variant override when track changes
            LyricsCatalogResolver.clearAllManualOverrides()
        }
        if (!forceRefresh && preferredProvider == null && lastLyricsTrackKey == trackKey) {
            val current = _lyricsUiState.value
            if (current is PlayerLyricsUiState.Loaded && current.trackKey == trackKey ||
                current is PlayerLyricsUiState.Loading && current.trackKey == trackKey
            ) {
                return
            }
        }
        lastLyricsTrackKey = trackKey
        lyricsJob?.cancel()
        lyricsJob = ioScope.launch {
            _lyricsUiState.value = PlayerLyricsUiState.Loading(trackKey)
            if (isAutomatic && !forceRefresh && preferredProvider == null) {
                delay(AUTO_SEARCH_DEBOUNCE_MS)
            }
            val latestTrack = _playerUiState.value.takeIf { it.hasMedia && "${it.artist}|${it.title}" == trackKey }
                ?: track
            try {
                val res = LyricsCatalogResolver.resolveLyrics(
                    rawArtist = latestTrack.artist,
                    rawTitle = latestTrack.title,
                    trackDurationMs = latestTrack.durationMs,
                    forceRefresh = forceRefresh,
                    preferredProvider = effectiveProvider.takeIf { it != "auto" }
                )
                _lyricsUiState.value = PlayerLyricsUiState.Loaded(trackKey, res)
            } catch (t: Throwable) {
                _lyricsUiState.value = PlayerLyricsUiState.Error(
                    trackKey,
                    t.message ?: "Не удалось загрузить текст"
                )
            }
        }
    }

    fun selectLyricsCandidate(candidate: LyricsCandidate) {
        val track = _playerUiState.value.takeIf { it.hasMedia }
            ?: _liveState.value.takeIf { it.hasMedia }
            ?: return
        val trackKey = "${track.artist}|${track.title}"
        LyricsCatalogResolver.setManualOverride(track.artist, track.title, candidate)
        val existingLoaded = _lyricsUiState.value as? PlayerLyricsUiState.Loaded
        val siblingCandidates = existingLoaded?.result?.candidates?.takeIf { it.isNotEmpty() } ?: listOf(candidate)
        val statuses = existingLoaded?.result?.providerStatuses ?: emptyMap()
        _lyricsUiState.value = PlayerLyricsUiState.Loaded(
            trackKey = trackKey,
            result = candidate.toLyricsResult(siblingCandidates, statuses)
        )
    }

    fun setServiceConnected(connected: Boolean) {
        _isServiceConnected.value = connected
        if (!connected && simulatedState.get() == null && !TestMediaSimulator.isSimulatorActive.value) {
            clearArtwork()
            updateLiveState(NowPlayingData.IDLE, forcePush = true)
            _playerUiState.value = NowPlayingData.IDLE
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
                revision = rev,
                timestampMs = SystemClock.elapsedRealtime()
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
            revision = rev,
            timestampMs = SystemClock.elapsedRealtime()
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
            val track = TestMediaSimulator.currentTrack
            val isPlaying = TestMediaSimulator.isPlaying.value
            val simArt = resolveSimulatorArtwork()
            val rawPos = TestMediaSimulator.getEstimatedPositionMs()
            val duration = track.durationMs
            val clampedPos = applyOffset(rawPos.coerceIn(0L, duration), duration, _syncOffsetMs.value)
            val sec = clampedPos / 1000.0
            val uiTrack = NowPlayingData(
                hasMedia = true,
                player = track.playerPackage,
                artist = track.artist,
                title = track.title,
                album = track.album,
                positionMs = clampedPos,
                durationMs = duration,
                positionSec = sec,
                playbackSpeed = 1.0f,
                isPlaying = isPlaying,
                positionKnown = true,
                accentColorHex = simArt.accentColorHex,
                accentColorsHex = simArt.accentColorsHex,
                extendedColorsHex = simArt.extendedColorsHex,
                gridColorsHex = simArt.gridColorsHex,
                hasArtwork = true,
                timestamp = System.currentTimeMillis()
            )
            _playerUiState.value = uiTrack

            val snapshot = if (isPlaying) uiTrack else NowPlayingData(
                hasMedia = false,
                isPlaying = false,
                positionKnown = true
            )
            if (!isPlaying && _artworkBitmap.value == null) {
                _artworkBitmap.value = simArt.thumbnail
            }
            updateLiveState(snapshot, forcePush = forcePush)
            BackupFileWriter.scheduleWrite(snapshot)
            return snapshot
        }

        // Priority 2: System Media Session
        val controller = activeController.get() ?: run {
            clearArtwork()
            val idle = NowPlayingData.IDLE
            _playerUiState.value = idle
            updateLiveState(idle, forcePush = forcePush)
            BackupFileWriter.scheduleWrite(idle)
            return idle
        }

        return try {
            val state = controller.playbackState
            val isPlaying = state?.state == PlaybackState.STATE_PLAYING
            val isPausedRetained = state?.state == PlaybackState.STATE_PAUSED ||
                state?.state == PlaybackState.STATE_BUFFERING

            if (state == null || (!isPlaying && !isPausedRetained)) {
                clearArtwork()
                val idle = NowPlayingData.IDLE
                _playerUiState.value = idle
                updateLiveState(idle, forcePush = forcePush)
                BackupFileWriter.scheduleWrite(idle)
                return idle
            }

            val metadata = controller.metadata
            val duration = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)

            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: ""
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""
            val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val packageName = controller.packageName ?: "unknown"
            val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1.0f

            val (calcPosMs, calcKnown) = calculatePosition(state, duration)
            val trackIdentity = "$packageName|$artist|$title"
            val nowElapsed = SystemClock.elapsedRealtime()
            val inSeekGrace = trackIdentity == seekGraceTrackIdentity && nowElapsed < seekGraceUntilElapsedMs
            val rawPosMs = if (inSeekGrace) {
                resolveGraceAdjustedPositionMs(
                    calculatedPosMs = calcPosMs,
                    nowElapsedMs = nowElapsed,
                    graceUntilElapsedMs = seekGraceUntilElapsedMs,
                    graceTargetPosMs = seekGraceTargetPosMs,
                    durationMs = duration,
                    isPlaying = isPlaying,
                    playbackSpeed = speed
                )
            } else {
                calcPosMs
            }
            val positionKnown = calcKnown || inSeekGrace
            val posMs = if (positionKnown) applyOffset(rawPosMs, duration, _syncOffsetMs.value) else 0L
            val posSec = posMs / 1000.0

            val extractedArt = resolveArtwork(packageName, artist, title, album, metadata)
            val fallbackPalette = if (extractedArt == null) {
                ArtworkExtractor.generateTrackPalette(artist, title)
            } else {
                extractedArt.accentColorsHex
            }

            val fullTrack = NowPlayingData(
                hasMedia = true,
                player = packageName,
                artist = artist,
                title = title,
                album = album,
                positionMs = posMs,
                durationMs = duration,
                positionSec = posSec,
                playbackSpeed = speed,
                isPlaying = isPlaying,
                positionKnown = positionKnown,
                accentColorHex = extractedArt?.accentColorHex ?: fallbackPalette.first(),
                accentColorsHex = fallbackPalette,
                extendedColorsHex = extractedArt?.extendedColorsHex ?: fallbackPalette,
                gridColorsHex = extractedArt?.gridColorsHex ?: emptyList(),
                hasArtwork = extractedArt != null,
                timestamp = System.currentTimeMillis()
            )
            _playerUiState.value = fullTrack

            val externalSnapshot = if (isPlaying) fullTrack else NowPlayingData(
                hasMedia = false,
                isPlaying = false,
                positionKnown = true
            )
            updateLiveState(externalSnapshot, forcePush = forcePush)
            BackupFileWriter.scheduleWrite(externalSnapshot)
            externalSnapshot
        } catch (_: Exception) {
            clearArtwork()
            val fallback = NowPlayingData.IDLE
            _playerUiState.value = fallback
            updateLiveState(fallback, forcePush = forcePush)
            fallback
        }
    }

    fun refreshSnapshot(forcePush: Boolean = false) {
        getCurrentSnapshot(forcePush = forcePush)
    }

    private fun resolveSimulatorArtwork(): ExtractedArtwork {
        val track = TestMediaSimulator.currentTrack
        val key = "simulator|${track.artist}|${track.title}"
        synchronized(artworkLock) {
            val existing = cachedExtractedArtwork
            if (cachedTrackKey == key && existing != null) {
                _artworkBitmap.value = existing.thumbnail
                _artworkDebugStatus.value = formatArtworkDebugStatus(existing.widthPx, existing.heightPx, "Симулятор")
                return existing
            }
            val created = ArtworkExtractor.createSimulatorArtwork(
                c1 = track.color1,
                c2 = track.color2,
                c3 = track.color3
            )
            cachedTrackKey = key
            cachedArtworkSourceKey = key
            cachedExtractedArtwork = created
            currentArtworkBytes = created.jpegBytes
            _artworkBitmap.value = created.thumbnail
            _artworkDebugStatus.value = formatArtworkDebugStatus(created.widthPx, created.heightPx, "Симулятор")
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
        val notifEntry = notificationArtMap[packageName]
        val validNotifBmp = notifEntry?.bitmap?.takeIf { !it.isRecycled }
        // Accept notification bitmap if it matches session/title OR is recent from the active player
        val notifEntryUsable = notifEntry != null && (
            notifEntry.notifTitle.isBlank() ||
                title.isBlank() ||
                notifEntry.notifTitle.equals(title, ignoreCase = true) ||
                title.contains(notifEntry.notifTitle, ignoreCase = true) ||
                notifEntry.notifTitle.contains(title, ignoreCase = true) ||
                (SystemClock.elapsedRealtime() - notifEntry.timestampMs) < 15_000L
            )
        val effectiveUri = metaArtUri ?: notifEntry?.uriFallback

        val sourceKey = when {
            inlineBitmap != null -> "$trackKey|inline|${inlineBitmap.generationId}"
            notifEntryUsable && validNotifBmp != null -> "$trackKey|notif|${notifEntry!!.revision}"
            !effectiveUri.isNullOrBlank() -> "$trackKey|uri|$effectiveUri"
            else -> "$trackKey|catalog"
        }

        synchronized(artworkLock) {
            if (cachedTrackKey != trackKey) {
                artworkCatalogJob?.cancel()
                hdUpgradeJob?.cancel()
                cachedTrackKey = trackKey
                cachedArtworkSourceKey = ""
                cachedExtractedArtwork = trackArtworkCache[trackKey]
                hdUpgradeAttemptedKey = ""
                inFlightHdUpgradeKey = ""
                hdUpgradedTrackKey = if (cachedExtractedArtwork?.isHd == true) trackKey else ""
                if (cachedExtractedArtwork != null) {
                    val art = cachedExtractedArtwork!!
                    currentArtworkBytes = art.jpegBytes
                    _artworkBitmap.value = art.thumbnail
                    _artworkDebugStatus.value = formatArtworkDebugStatus(art.widthPx, art.heightPx, "Кэш")
                    if (art.needsHdUpgrade && (artist.isNotBlank() || title.isNotBlank())) {
                        triggerHdUpgradeIfNeededLocked(packageName, trackKey, sourceKey, artist, title, album)
                    }
                } else {
                    currentArtworkBytes = null
                    _artworkBitmap.value = null
                }
                lastAttemptedUriKey = ""
                inFlightArtKey = ""
                catalogFallbackAttemptedKey = ""
            } else if (cachedExtractedArtwork != null &&
                (hdUpgradedTrackKey == trackKey || cachedArtworkSourceKey == sourceKey || (inlineBitmap == null && !notifEntryUsable))
            ) {
                val art = cachedExtractedArtwork!!
                if (_artworkBitmap.value == null) _artworkBitmap.value = art.thumbnail
                if (_artworkDebugStatus.value == "Ожидание трека") {
                    _artworkDebugStatus.value = formatArtworkDebugStatus(art.widthPx, art.heightPx, "Кэш")
                }
                if (art.needsHdUpgrade && hdUpgradeAttemptedKey != trackKey && (artist.isNotBlank() || title.isNotBlank())) {
                    triggerHdUpgradeIfNeededLocked(packageName, trackKey, sourceKey, artist, title, album)
                }
                return cachedExtractedArtwork
            }

            // 1. Inline MediaMetadata Bitmap (highest priority)
            if (inlineBitmap != null) {
                val extracted = ArtworkExtractor.fromBitmap(inlineBitmap)
                if (extracted != null) {
                    applyResolvedArtworkLocked(trackKey, sourceKey, extracted, "MediaMetadata Bitmap")
                    if (extracted.needsHdUpgrade && (artist.isNotBlank() || title.isNotBlank())) {
                        triggerHdUpgradeIfNeededLocked(packageName, trackKey, sourceKey, artist, title, album)
                    }
                    return extracted
                }
            }

            // 2. Notification MediaStyle Icon / Bitmap (no brittle rejection on formatted titles)
            if (notifEntryUsable && validNotifBmp != null) {
                val extracted = ArtworkExtractor.fromBitmap(validNotifBmp)
                if (extracted != null) {
                    applyResolvedArtworkLocked(trackKey, sourceKey, extracted, notifEntry!!.debugNote)
                    if (extracted.needsHdUpgrade && (artist.isNotBlank() || title.isNotBlank())) {
                        triggerHdUpgradeIfNeededLocked(packageName, trackKey, sourceKey, artist, title, album)
                    }
                    return extracted
                }
            }

            // Return LRU cached cover immediately if already fetched
            if (cachedExtractedArtwork != null) {
                val art = cachedExtractedArtwork!!
                _artworkDebugStatus.value = formatArtworkDebugStatus(art.widthPx, art.heightPx, "Кэш")
                return cachedExtractedArtwork
            }

            // 3. Async URI (content://, file://, https://) + Public Waterfall
            val ctx = appContext
            if (!effectiveUri.isNullOrBlank() && ctx != null) {
                val uriRequestKey = "$trackKey|$effectiveUri"
                if (lastAttemptedUriKey != uriRequestKey && inFlightArtKey != uriRequestKey) {
                    inFlightArtKey = uriRequestKey
                    lastAttemptedUriKey = uriRequestKey
                    _artworkDebugStatus.value = "Загрузка URI + каскад (${effectiveUri.substringBefore(':')}...)"
                    fetchArtworkWithWaterfallAsync(
                        context = ctx,
                        packageName = packageName,
                        trackKey = trackKey,
                        sourceKey = sourceKey,
                        artUri = effectiveUri,
                        artist = artist,
                        title = title,
                        album = album,
                        requestKey = uriRequestKey,
                        metaSummary = ArtworkExtractor.summarizeMetadataKeys(metadata),
                        notifDebug = notifEntry?.debugNote ?: "notif=none"
                    )
                }
                return null
            }

            // 4. Local sources absent/blocked -> run Waterfall once per track
            if (catalogFallbackAttemptedKey != trackKey && inFlightArtKey != trackKey && (artist.isNotBlank() || title.isNotBlank())) {
                catalogFallbackAttemptedKey = trackKey
                inFlightArtKey = trackKey
                val metaSummary = ArtworkExtractor.summarizeMetadataKeys(metadata)
                val notifSummary = notifEntry?.debugNote ?: "notif=none"
                _artworkDebugStatus.value = "Поиск по каталогам..."
                fetchCatalogWaterfallAsync(
                    context = ctx,
                    packageName = packageName,
                    trackKey = trackKey,
                    sourceKey = sourceKey,
                    artist = artist,
                    title = title,
                    album = album,
                    metaSummary = metaSummary,
                    notifSummary = notifSummary
                )
            } else if (inFlightArtKey.isBlank()) {
                _artworkDebugStatus.value = "Генеративная палитра (${ArtworkExtractor.summarizeMetadataKeys(metadata)})"
            }
            return null
        }
    }

    private fun triggerHdUpgradeIfNeededLocked(
        packageName: String,
        trackKey: String,
        sourceKey: String,
        artist: String,
        title: String,
        album: String
    ) {
        if (hdUpgradeAttemptedKey == trackKey || inFlightHdUpgradeKey == trackKey || hdUpgradedTrackKey == trackKey) {
            return
        }
        hdUpgradeAttemptedKey = trackKey
        inFlightHdUpgradeKey = trackKey
        val preferSc = packageName.lowercase(Locale.US).contains("soundcloud")
        hdUpgradeJob?.cancel()
        hdUpgradeJob = ioScope.launch {
            delay(AUTO_SEARCH_DEBOUNCE_MS)
            if (cachedTrackKey != trackKey) return@launch
            val hdRes = ArtworkCatalogResolver.resolveHdUpgrade(
                rawArtist = artist,
                rawTitle = title,
                rawAlbum = album,
                preferSoundCloudFirst = preferSc
            )
            var shouldRefresh = false
            synchronized(artworkLock) {
                if (inFlightHdUpgradeKey == trackKey) {
                    inFlightHdUpgradeKey = ""
                }
                val hdArt = hdRes?.artwork
                if (cachedTrackKey == trackKey && hdArt != null) {
                    hdUpgradedTrackKey = trackKey
                    // Preserve original player sourceKey so 250ms ticks do not overwrite HD back to tiny inline bitmap
                    applyResolvedArtworkLocked(trackKey, sourceKey, hdArt, "HD-апгрейд • ${hdRes.debugNote}")
                    shouldRefresh = true
                }
            }
            if (shouldRefresh) {
                refreshSnapshot(forcePush = true)
            }
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
        trackArtworkCache[trackKey] = extracted
        currentArtworkBytes = extracted.jpegBytes
        _artworkBitmap.value = extracted.thumbnail
        _artworkDebugStatus.value = formatArtworkDebugStatus(extracted.widthPx, extracted.heightPx, debugNote)
    }

    private fun fetchArtworkWithWaterfallAsync(
        context: Context,
        packageName: String,
        trackKey: String,
        sourceKey: String,
        artUri: String,
        artist: String,
        title: String,
        album: String,
        requestKey: String,
        metaSummary: String,
        notifDebug: String
    ) {
        artworkCatalogJob?.cancel()
        artworkCatalogJob = ioScope.launch {
            val uriRes = ArtworkExtractor.loadFromUriDetailed(context, artUri)
            var finalArtwork = uriRes.artwork
            var finalNote = uriRes.debugNote

            if (finalArtwork == null && (artist.isNotBlank() || title.isNotBlank())) {
                delay(AUTO_SEARCH_DEBOUNCE_MS)
                if (cachedTrackKey != trackKey) return@launch
                val preferSc = packageName.lowercase(Locale.US).contains("soundcloud")
                val catalogRes = ArtworkCatalogResolver.resolveCoverWaterfall(
                    context = context,
                    rawArtist = artist,
                    rawTitle = title,
                    rawAlbum = album,
                    preferSoundCloudFirst = preferSc
                )
                if (catalogRes.artwork != null) {
                    finalArtwork = catalogRes.artwork
                    finalNote = "${catalogRes.debugNote} (после ${uriRes.debugNote})"
                } else {
                    finalNote = "Генеративная палитра (${uriRes.debugNote}; ${catalogRes.debugNote}; $metaSummary; $notifDebug)"
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
                        if (finalArtwork.needsHdUpgrade && (artist.isNotBlank() || title.isNotBlank())) {
                            triggerHdUpgradeIfNeededLocked(packageName, trackKey, sourceKey, artist, title, album)
                        }
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

    private fun fetchCatalogWaterfallAsync(
        context: Context?,
        packageName: String,
        trackKey: String,
        sourceKey: String,
        artist: String,
        title: String,
        album: String,
        metaSummary: String,
        notifSummary: String
    ) {
        artworkCatalogJob?.cancel()
        artworkCatalogJob = ioScope.launch {
            delay(AUTO_SEARCH_DEBOUNCE_MS)
            if (cachedTrackKey != trackKey) return@launch
            val preferSc = packageName.lowercase(Locale.US).contains("soundcloud")
            val catalogRes = ArtworkCatalogResolver.resolveCoverWaterfall(
                context = context,
                rawArtist = artist,
                rawTitle = title,
                rawAlbum = album,
                preferSoundCloudFirst = preferSc
            )
            var shouldRefresh = false
            synchronized(artworkLock) {
                if (inFlightArtKey == trackKey) {
                    inFlightArtKey = ""
                }
                if (cachedTrackKey == trackKey) {
                    val art = catalogRes.artwork
                    if (art != null) {
                        applyResolvedArtworkLocked(trackKey, sourceKey, art, "${catalogRes.debugNote} ($metaSummary; $notifSummary)")
                        if (art.needsHdUpgrade && (artist.isNotBlank() || title.isNotBlank())) {
                            triggerHdUpgradeIfNeededLocked(packageName, trackKey, sourceKey, artist, title, album)
                        }
                        shouldRefresh = true
                    } else {
                        _artworkDebugStatus.value = "Генеративная палитра (${catalogRes.debugNote}; $metaSummary; $notifSummary)"
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
                artworkCatalogJob?.cancel()
                hdUpgradeJob?.cancel()
                cachedTrackKey = ""
                cachedArtworkSourceKey = ""
                cachedExtractedArtwork = null
                lastAttemptedUriKey = ""
                inFlightArtKey = ""
                catalogFallbackAttemptedKey = ""
                hdUpgradeAttemptedKey = ""
                inFlightHdUpgradeKey = ""
                hdUpgradedTrackKey = ""
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
