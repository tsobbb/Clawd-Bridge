package com.example.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lyrics.LyricsCatalogResolver
import com.example.lyrics.LyricsInterlude
import com.example.lyrics.LyricsLine
import com.example.lyrics.LyricsSyncMode
import com.example.model.NowPlayingData
import com.example.state.PlayerAccentSource
import com.example.state.PlayerBackgroundMode
import com.example.state.PlayerCoverStyle
import com.example.state.PlayerLyricsUiState
import com.example.state.PlayerStyleConfig
import com.example.state.PlayerTitleAlignment
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LivingPaletteState
import com.example.ui.theme.MintCta
import com.example.ui.theme.NestedSurface
import com.example.ui.theme.SecondaryGlass
import com.example.ui.theme.SemanticAmber
import com.example.ui.theme.SemanticCoral
import com.example.ui.theme.SheetScrim
import com.example.ui.theme.SheetSurface
import com.example.ui.theme.SpecularStroke
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.withTabularNumbers
import com.example.util.AppMetadataResolver
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sin

private val LYRICS_PROVIDERS = listOf(
    "auto" to "Авто (LRCLIB → NetEase → Genius)",
    "lrclib" to "LRCLIB (Синхронный)",
    "netease" to "NetEase Cloud Music",
    "genius" to "Genius",
    "lyrics_ovh" to "Lyrics.ovh"
)

/**
 * Pure-JVM binary search returning the active lyric line index at [displayPositionMs],
 * or -1 before the first timestamp.
 */
internal fun findActiveLyricsLineIndex(lines: List<LyricsLine>, displayPositionMs: Long): Int {
    if (lines.isEmpty()) return -1
    if (displayPositionMs < lines.first().timeMs) return -1
    var lo = 0
    var hi = lines.lastIndex
    var ans = 0
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= displayPositionMs) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

/**
 * Converts a display timestamp (which already has [syncOffsetMs] applied) back into raw media
 * seek position for MediaController / TestMediaSimulator.
 */
internal fun computeCompensatedSeekTargetMs(
    targetDisplayMs: Long,
    durationMs: Long,
    syncOffsetMs: Int
): Long {
    val raw = targetDisplayMs - syncOffsetMs.toLong()
    return if (durationMs > 0L) raw.coerceIn(0L, durationMs) else raw.coerceAtLeast(0L)
}

internal data class LyricTextToken(
    val text: String,
    val isWord: Boolean,
    val wordOrdinal: Int
)

private val LYRIC_TOKEN_REGEX = Regex("""\S+|\s+""")

internal fun tokenizeLyricWords(text: String): List<LyricTextToken> {
    if (text.isEmpty()) return emptyList()
    var wordCount = 0
    return LYRIC_TOKEN_REGEX.findAll(text).map { match ->
        val chunk = match.value
        val isWord = chunk.any { !it.isWhitespace() }
        val ord = if (isWord) wordCount++ else -1
        LyricTextToken(text = chunk, isWord = isWord, wordOrdinal = ord)
    }.toList()
}

/**
 * Computes active word ordinal and continuous transition fraction [0f..1f] inside an active line.
 * - For WORD_SYNCED lines (`line.words.isNotEmpty()`), uses authentic `<mm:ss.xx>` word timestamps.
 * - For LINE_SYNCED lines (`line.words.isEmpty()`), interpolates smoothly across word tokens
 *   weighted by character length over `[line.timeMs .. nextLineTimeMs]`.
 */
internal fun computeLineWordHighlightState(
    line: LyricsLine,
    nextLineTimeMs: Long,
    positionMs: Long
): Pair<Int, Float> {
    if (line.words.isNotEmpty()) {
        val words = line.words
        if (positionMs < words.first().startMs) {
            return -1 to 0f
        }
        var activeIdx = 0
        for (i in words.indices) {
            if (words[i].startMs <= positionMs) {
                activeIdx = i
            } else {
                break
            }
        }
        val wordStart = words[activeIdx].startMs
        val wordEnd = words.getOrNull(activeIdx + 1)?.startMs
            ?: nextLineTimeMs.coerceAtLeast(wordStart + 350L)
        val span = (wordEnd - wordStart).coerceAtLeast(120L).toFloat()
        val frac = ((positionMs - wordStart).toFloat() / span).coerceIn(0f, 1f)
        return activeIdx to frac
    }

    val wordTokens = tokenizeLyricWords(line.text).filter { it.isWord }
    if (wordTokens.isEmpty()) return 0 to 1f

    val rawLineSpanMs = (nextLineTimeMs - line.timeMs).coerceIn(750L, 12_000L)
    val effectiveDurationMs = (rawLineSpanMs.toFloat() * 0.90f).coerceAtLeast(600f)
    val overall = ((positionMs - line.timeMs).toFloat() / effectiveDurationMs).coerceIn(0f, 1f)
    if (overall >= 1f) {
        return wordTokens.lastIndex to 1f
    }
    if (overall <= 0f) {
        return 0 to 0f
    }

    val weights = FloatArray(wordTokens.size) { idx ->
        (wordTokens[idx].text.length.coerceAtLeast(1) + 1).toFloat()
    }
    val totalWeight = weights.sum().coerceAtLeast(1f)
    val targetWeight = overall * totalWeight
    var acc = 0f
    for (i in wordTokens.indices) {
        val w = weights[i]
        if (targetWeight <= acc + w || i == wordTokens.lastIndex) {
            val frac = ((targetWeight - acc) / w).coerceIn(0f, 1f)
            return i to frac
        }
        acc += w
    }
    return wordTokens.lastIndex to 1f
}

internal fun formatPlayerClock(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullscreenPlayerOverlay(
    isOpen: Boolean,
    context: Context,
    track: NowPlayingData,
    artworkBitmap: Bitmap?,
    livingPalette: LivingPaletteState,
    styleConfig: PlayerStyleConfig,
    lyricsState: PlayerLyricsUiState,
    preferredLyricsProvider: String,
    syncOffsetMs: Int,
    artworkDebugStatus: String,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekToRawMs: (Long) -> Unit,
    onSeekByDelta: (Long) -> Unit,
    onSyncOffsetChange: (Int) -> Unit,
    onUpdateStyle: ((PlayerStyleConfig) -> PlayerStyleConfig) -> Unit,
    onRefreshLyrics: (String?) -> Unit
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInVertically(
            initialOffsetY = { fullHeight -> fullHeight },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(tween(220)),
        exit = slideOutVertically(
            targetOffsetY = { fullHeight -> fullHeight },
            animationSpec = tween(240, easing = FastOutSlowInEasing)
        ) + fadeOut(tween(180))
    ) {
        FullscreenPlayerContent(
            context = context,
            track = track,
            artworkBitmap = artworkBitmap,
            livingPalette = livingPalette,
            styleConfig = styleConfig,
            lyricsState = lyricsState,
            preferredLyricsProvider = preferredLyricsProvider,
            syncOffsetMs = syncOffsetMs,
            artworkDebugStatus = artworkDebugStatus,
            onDismiss = onDismiss,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onSeekToRawMs = onSeekToRawMs,
            onSeekByDelta = onSeekByDelta,
            onSyncOffsetChange = onSyncOffsetChange,
            onUpdateStyle = onUpdateStyle,
            onRefreshLyrics = onRefreshLyrics
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenPlayerContent(
    context: Context,
    track: NowPlayingData,
    artworkBitmap: Bitmap?,
    livingPalette: LivingPaletteState,
    styleConfig: PlayerStyleConfig,
    lyricsState: PlayerLyricsUiState,
    preferredLyricsProvider: String,
    syncOffsetMs: Int,
    artworkDebugStatus: String,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekToRawMs: (Long) -> Unit,
    onSeekByDelta: (Long) -> Unit,
    onSyncOffsetChange: (Int) -> Unit,
    onUpdateStyle: ((PlayerStyleConfig) -> PlayerStyleConfig) -> Unit,
    onRefreshLyrics: (String?) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var isLyricsMode by remember { mutableStateOf(false) }
    var isFocusMode by remember { mutableStateOf(false) }
    var showOverflowSheet by remember { mutableStateOf(false) }
    var showStyleSheet by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var showOffsetPopover by remember { mutableStateOf(false) }
    var showRemainingTime by remember { mutableStateOf(false) }

    // Keep screen awake during Focus / Desk display mode
    DisposableEffect(isFocusMode, context) {
        val window = (context as? Activity)?.window
        if (isFocusMode) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Dismiss drag translation (attached ONLY to header & Mode A cover so lyrics scroll never collides)
    var dismissDragPx by remember { mutableFloatStateOf(0f) }
    val animatedDragOffset by animateFloatAsState(
        targetValue = dismissDragPx,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "player_dismiss_drag"
    )
    val dismissThresholdPx = with(density) { 125.dp.toPx() }

    BackHandler {
        when {
            showOverflowSheet -> showOverflowSheet = false
            showStyleSheet -> showStyleSheet = false
            showInfoSheet -> showInfoSheet = false
            showOffsetPopover -> showOffsetPopover = false
            isFocusMode -> isFocusMode = false
            isLyricsMode -> isLyricsMode = false
            else -> onDismiss()
        }
    }

    // 60/120 FPS client-side display position state — NEVER read at this parent scope!
    val displayPositionMsState = remember { mutableLongStateOf(track.positionMs) }
    val localSeekGraceUntilMsState = remember { mutableLongStateOf(0L) }
    val isScrubbingState = remember { mutableStateOf(false) }
    val scrubPositionMsState = remember { mutableLongStateOf(track.positionMs) }
    val shownPositionMsState = remember {
        derivedStateOf {
            if (isScrubbingState.value) scrubPositionMsState.longValue else displayPositionMsState.longValue
        }
    }

    LaunchedEffect(track.player, track.title, track.artist) {
        localSeekGraceUntilMsState.longValue = 0L
        displayPositionMsState.longValue = track.positionMs
    }

    LaunchedEffect(
        track.positionMs,
        track.isPlaying,
        track.playbackSpeed,
        track.durationMs,
        track.title,
        track.artist,
        track.hasMedia
    ) {
        if (SystemClock.elapsedRealtime() >= localSeekGraceUntilMsState.longValue) {
            displayPositionMsState.longValue = track.positionMs
        }
        if (track.hasMedia && track.isPlaying) {
            var lastNanos = 0L
            while (isActive) {
                withFrameNanos { nanos ->
                    if (lastNanos != 0L) {
                        val dtMs = ((nanos - lastNanos).coerceIn(0L, 100_000_000L) / 1_000_000.0)
                        val speed = if (track.playbackSpeed > 0f) track.playbackSpeed else 1f
                        val next = displayPositionMsState.longValue + (dtMs * speed).toLong()
                        displayPositionMsState.longValue = if (track.durationMs > 0L) {
                            next.coerceIn(0L, track.durationMs)
                        } else {
                            next.coerceAtLeast(0L)
                        }
                    }
                    lastNanos = nanos
                }
            }
        }
    }

    val seekToDisplayTarget: (Long) -> Unit = { targetDisplayMs ->
        val clampedDisplay = if (track.durationMs > 0L) {
            targetDisplayMs.coerceIn(0L, track.durationMs)
        } else {
            targetDisplayMs.coerceAtLeast(0L)
        }
        localSeekGraceUntilMsState.longValue = SystemClock.elapsedRealtime() + 650L
        displayPositionMsState.longValue = clampedDisplay
        val rawSeek = computeCompensatedSeekTargetMs(clampedDisplay, track.durationMs, syncOffsetMs)
        onSeekToRawMs(rawSeek)
    }

    val effectiveAccent = if (styleConfig.accentSource == PlayerAccentSource.COVER) {
        livingPalette.playerAccent
    } else {
        MintCta
    }

    val resolvedApp = remember(track.player) {
        if (track.player.isNotBlank()) AppMetadataResolver.resolveApp(context, track.player) else null
    }
    val validBitmap = remember(artworkBitmap) {
        artworkBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
    }

    val verticalDismissModifier = Modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { delta ->
            dismissDragPx = (dismissDragPx + delta).coerceAtLeast(0f)
        },
        onDragStopped = { velocity ->
            if (dismissDragPx > dismissThresholdPx || velocity > 1600f) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onDismiss()
            }
            dismissDragPx = 0f
        }
    )

    val containerBgColor = if (styleConfig.backgroundMode == PlayerBackgroundMode.GRADIENT) {
        Color.Transparent
    } else {
        Color(0xFF06080C)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = animatedDragOffset }
            .background(containerBgColor)
            .let { mod ->
                if (isFocusMode && !isLyricsMode) {
                    mod.clickable { isFocusMode = false }
                } else {
                    mod
                }
            }
            .testTag("fullscreen_player_overlay")
    ) {
        // 1. Background layer driven by PlayerBackgroundMode (zero double-render in GRADIENT mode)
        PlayerDynamicBackdrop(
            backgroundMode = styleConfig.backgroundMode,
            livingPalette = livingPalette,
            artworkImage = validBitmap
        )

        // 2. Main vertical scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .widthIn(max = 560.dp)
                .align(Alignment.Center)
        ) {
            // Top Header Bar (swipe-down safe handle + Focus mode toggle + opaque sheet menu)
            PlayerHeaderBar(
                isLyricsMode = isLyricsMode,
                isFocusMode = isFocusMode,
                track = track,
                artworkImage = validBitmap,
                resolvedAppName = resolvedApp?.displayName ?: "Clawd Bridge",
                resolvedAppIcon = resolvedApp?.icon,
                accentColor = effectiveAccent,
                onDismiss = onDismiss,
                onHeaderTap = {
                    if (isLyricsMode) isLyricsMode = false
                },
                onToggleFocusMode = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    isFocusMode = !isFocusMode
                },
                onOpenMenu = { showOverflowSheet = true },
                modifier = verticalDismissModifier
            )

            // Center stage: switches cleanly between Mode A (Cover) and Mode B (Synced Lyrics)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AnimatedContent(
                    targetState = isLyricsMode,
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                    },
                    label = "player_mode_transition"
                ) { lyricsVisible ->
                    if (!lyricsVisible) {
                        PlayerCoverStage(
                            track = track,
                            artworkImage = validBitmap,
                            livingPalette = livingPalette,
                            effectiveAccent = effectiveAccent,
                            styleConfig = styleConfig,
                            onSkipNext = onSkipNext,
                            onSkipPrevious = onSkipPrevious,
                            dismissDragModifier = verticalDismissModifier
                        )
                    } else {
                        PlayerLyricsStage(
                            context = context,
                            track = track,
                            shownPositionMsState = shownPositionMsState,
                            effectiveAccent = effectiveAccent,
                            livingPalette = livingPalette,
                            lyricsState = lyricsState,
                            onSeekToDisplayMs = seekToDisplayTarget,
                            onRefreshLyrics = { onRefreshLyrics(null) },
                            onOpenProviderMenu = { showOverflowSheet = true }
                        )
                    }
                }
            }

            AnimatedVisibility(visible = !isFocusMode) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Optional inline Bluetooth Sync Offset quick-adjust popover right above bottom pills
                    AnimatedVisibility(visible = showOffsetPopover) {
                        PlayerQuickOffsetBar(
                            syncOffsetMs = syncOffsetMs,
                            accentColor = effectiveAccent,
                            onSyncOffsetChange = onSyncOffsetChange,
                            onClose = { showOffsetPopover = false }
                        )
                    }

                    // Interactive Seekbar + Timecodes (isolated from parent recomposition)
                    PlayerSeekSection(
                        track = track,
                        shownPositionMsState = shownPositionMsState,
                        accentColor = effectiveAccent,
                        wavyEnabled = styleConfig.wavySeekbarEnabled,
                        showRemainingTime = showRemainingTime,
                        onToggleRemainingTime = { showRemainingTime = !showRemainingTime },
                        onScrubStart = { targetMs ->
                            isScrubbingState.value = true
                            scrubPositionMsState.longValue = targetMs
                        },
                        onScrubMove = { targetMs ->
                            scrubPositionMsState.longValue = targetMs
                        },
                        onScrubEnd = { targetMs ->
                            isScrubbingState.value = false
                            seekToDisplayTarget(targetMs)
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Transport Bar: -10s | Prev | Play/Pause | Next | +10s
                    PlayerTransportControls(
                        isPlaying = track.isPlaying,
                        accentColor = effectiveAccent,
                        onSeekMinus10 = {
                            val nextDisplay = (displayPositionMsState.longValue - 10_000L).let {
                                if (track.durationMs > 0L) it.coerceIn(0L, track.durationMs) else it.coerceAtLeast(0L)
                            }
                            localSeekGraceUntilMsState.longValue = SystemClock.elapsedRealtime() + 650L
                            displayPositionMsState.longValue = nextDisplay
                            onSeekByDelta(-10_000L)
                        },
                        onPrevious = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSkipPrevious()
                        },
                        onTogglePlayPause = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTogglePlayPause()
                        },
                        onNext = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSkipNext()
                        },
                        onSeekPlus10 = {
                            val nextDisplay = (displayPositionMsState.longValue + 10_000L).let {
                                if (track.durationMs > 0L) it.coerceIn(0L, track.durationMs) else it.coerceAtLeast(0L)
                            }
                            localSeekGraceUntilMsState.longValue = SystemClock.elapsedRealtime() + 650L
                            displayPositionMsState.longValue = nextDisplay
                            onSeekByDelta(10_000L)
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Bottom 3 Action Pills: [ ⏱ X мс ] | [ ≡ Lyrics ] | [ ☰ Инфо ]
                    PlayerBottomActionPills(
                        syncOffsetMs = syncOffsetMs,
                        isOffsetPopoverOpen = showOffsetPopover,
                        isLyricsMode = isLyricsMode,
                        hasSyncedLyrics = (lyricsState as? PlayerLyricsUiState.Loaded)?.result?.synced == true,
                        accentColor = effectiveAccent,
                        onToggleOffsetPopover = { showOffsetPopover = !showOffsetPopover },
                        onToggleLyrics = {
                            isLyricsMode = !isLyricsMode
                        },
                        onOpenInfo = { showInfoSheet = true }
                    )
                }
            }
        }
    }

    // Opaque Player Options & Lyrics Provider Sheet (eliminates transparent DropdownMenu text bleed-through)
    if (showOverflowSheet) {
        PlayerOverflowOptionsSheet(
            accentColor = effectiveAccent,
            preferredProvider = preferredLyricsProvider,
            isFocusMode = isFocusMode,
            onDismiss = { showOverflowSheet = false },
            onOpenStyleSheet = {
                showOverflowSheet = false
                showStyleSheet = true
            },
            onRefreshLyrics = {
                showOverflowSheet = false
                isLyricsMode = true
                onRefreshLyrics(null)
            },
            onToggleFocusMode = {
                showOverflowSheet = false
                isFocusMode = !isFocusMode
            },
            onSelectProvider = { providerId ->
                showOverflowSheet = false
                isLyricsMode = true
                onRefreshLyrics(providerId)
            }
        )
    }

    // Compact Live-Preview Style Customization Sheet (~50% height, low dim)
    if (showStyleSheet) {
        PlayerStyleSettingsSheet(
            styleConfig = styleConfig,
            accentColor = effectiveAccent,
            onUpdateStyle = onUpdateStyle,
            onDismiss = { showStyleSheet = false }
        )
    }

    // Track Metadata & Diagnostics Sheet
    if (showInfoSheet) {
        PlayerTrackInfoSheet(
            track = track,
            appDisplayName = resolvedApp?.displayName ?: track.player,
            lyricsState = lyricsState,
            artworkDebugStatus = artworkDebugStatus,
            accentColor = effectiveAccent,
            onDismiss = { showInfoSheet = false }
        )
    }
}

@Composable
private fun PlayerDynamicBackdrop(
    backgroundMode: PlayerBackgroundMode,
    livingPalette: LivingPaletteState,
    artworkImage: androidx.compose.ui.graphics.ImageBitmap?
) {
    when (backgroundMode) {
        PlayerBackgroundMode.GRADIENT -> {
            // Subtle darkening veil over the single root LivingAuroraBackdrop (zero duplicate shader render!)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x3806080C))
            )
        }
        PlayerBackgroundMode.BLUR -> {
            val blurSource = livingPalette.appleMusicCoverBitmap ?: artworkImage
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (blurSource != null) Color(0xFF07080B) else Color(0x3806080C))
            ) {
                if (blurSource != null) {
                    val saturatedFilter = remember {
                        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.35f) })
                    }
                    val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier.blur(95.dp)
                    } else {
                        Modifier
                    }
                    Image(
                        bitmap = blurSource,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = saturatedFilter,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.6f
                                scaleY = 1.6f
                            }
                            .then(blurMod)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x8A06080C))
                    )
                }
            }
        }
        PlayerBackgroundMode.SOLID -> {
            val solidColor = remember(livingPalette.cardTint) {
                lerp(Color(0xFF07080B), livingPalette.cardTint, 0.72f)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(solidColor)
            )
        }
    }
}

@Composable
private fun PlayerHeaderBar(
    isLyricsMode: Boolean,
    isFocusMode: Boolean,
    track: NowPlayingData,
    artworkImage: androidx.compose.ui.graphics.ImageBitmap?,
    resolvedAppName: String,
    resolvedAppIcon: androidx.compose.ui.graphics.ImageBitmap?,
    accentColor: Color,
    onDismiss: () -> Unit,
    onHeaderTap: () -> Unit,
    onToggleFocusMode: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("player_close_button")
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Свернуть плеер",
                tint = TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }

        if (isLyricsMode) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onHeaderTap)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(NestedSurface),
                    contentAlignment = Alignment.Center
                ) {
                    if (artworkImage != null) {
                        Image(
                            bitmap = artworkImage,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title.ifBlank { "Без названия" },
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist.ifBlank { resolvedAppName },
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = NestedSurface,
                border = BorderStroke(0.5.dp, SpecularStroke)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    if (resolvedAppIcon != null) {
                        Image(
                            bitmap = resolvedAppIcon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = resolvedAppName,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onToggleFocusMode,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("player_focus_mode_button")
            ) {
                Icon(
                    imageVector = if (isFocusMode) Icons.Default.CloseFullscreen else Icons.Default.CenterFocusStrong,
                    contentDescription = if (isFocusMode) "Выйти из режима экрана" else "Настольный режим (экран не гаснет)",
                    tint = if (isFocusMode) accentColor else TextSecondary,
                    modifier = Modifier.size(21.dp)
                )
            }

            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("player_more_button")
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Меню плеера",
                    tint = TextPrimary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerOverflowOptionsSheet(
    accentColor: Color,
    preferredProvider: String,
    isFocusMode: Boolean,
    onDismiss: () -> Unit,
    onOpenStyleSheet: () -> Unit,
    onRefreshLyrics: () -> Unit,
    onToggleFocusMode: () -> Unit,
    onSelectProvider: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF713161D),
        scrimColor = Color.Black.copy(alpha = 0.38f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted.copy(alpha = 0.5f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Параметры плеера",
                style = MaterialTheme.typography.titleLarge.copy(color = TextPrimary)
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF1C2029),
                border = BorderStroke(0.5.dp, SpecularStroke),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    SheetOptionRow(
                        icon = Icons.Default.Palette,
                        title = "Настройки стиля",
                        subtitle = "Обложка, фон, выравнивание, волна",
                        tint = accentColor,
                        onClick = onOpenStyleSheet
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)
                    SheetOptionRow(
                        icon = Icons.Default.Refresh,
                        title = "Обновить текст трека",
                        subtitle = "Принудительно перепроверить источники",
                        tint = TextSecondary,
                        onClick = onRefreshLyrics
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)
                    SheetOptionRow(
                        icon = Icons.Default.CenterFocusStrong,
                        title = if (isFocusMode) "Выйти из режима экрана" else "Настольный режим (экран не гаснет)",
                        subtitle = if (isFocusMode) "Вернуть элементы управления" else "Скрыть кнопки и не выключать дисплей",
                        tint = if (isFocusMode) accentColor else TextSecondary,
                        onClick = onToggleFocusMode
                    )
                }
            }

            Text(
                text = "Источник текста",
                style = MaterialTheme.typography.labelMedium.copy(color = TextMuted)
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF1C2029),
                border = BorderStroke(0.5.dp, SpecularStroke),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    LYRICS_PROVIDERS.forEachIndexed { idx, (id, label) ->
                        val isSelected = preferredProvider == id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectProvider(id) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (isSelected) accentColor else TextPrimary,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Выбран",
                                    tint = accentColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (idx < LYRICS_PROVIDERS.lastIndex) {
                            HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary, fontWeight = FontWeight.Medium)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
            )
        }
    }
}

@Composable
private fun PlayerCoverStage(
    track: NowPlayingData,
    artworkImage: androidx.compose.ui.graphics.ImageBitmap?,
    livingPalette: LivingPaletteState,
    effectiveAccent: Color,
    styleConfig: PlayerStyleConfig,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    dismissDragModifier: Modifier
) {
    val haptic = LocalHapticFeedback.current
    var horizontalSwipePx by remember { mutableFloatStateOf(0f) }
    val animatedSwipeX by animateFloatAsState(
        targetValue = horizontalSwipePx,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "cover_swipe_x"
    )
    val coverCorner by animateDpAsState(
        targetValue = if (styleConfig.coverStyle == PlayerCoverStyle.CARD) 24.dp else 14.dp,
        animationSpec = tween(260),
        label = "cover_corner"
    )
    val playScale by animateFloatAsState(
        targetValue = if (track.isPlaying) 1.0f else 0.93f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "cover_play_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val coverSwipeModifier = if (styleConfig.swipeCoverToSkip) {
            Modifier.draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    horizontalSwipePx = (horizontalSwipePx + delta).coerceIn(-240f, 240f)
                },
                onDragStopped = { velocity ->
                    when {
                        horizontalSwipePx < -120f || velocity < -900f -> {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSkipNext()
                        }
                        horizontalSwipePx > 120f || velocity > 900f -> {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSkipPrevious()
                        }
                    }
                    horizontalSwipePx = 0f
                }
            )
        } else {
            Modifier
        }

        val widthFraction = if (styleConfig.coverStyle == PlayerCoverStyle.CARD) 0.88f else 1.0f
        val aspect = if (styleConfig.coverStyle == PlayerCoverStyle.CARD) 1f else 1.15f

        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .widthIn(max = if (styleConfig.coverStyle == PlayerCoverStyle.CARD) 340.dp else 440.dp)
                .aspectRatio(aspect)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                    translationX = animatedSwipeX * 0.45f
                }
                .then(dismissDragModifier)
                .then(coverSwipeModifier)
                .shadow(
                    elevation = if (track.isPlaying) 24.dp else 10.dp,
                    shape = RoundedCornerShape(coverCorner),
                    clip = false
                )
                .clip(RoundedCornerShape(coverCorner))
                .background(livingPalette.cardTint),
            contentAlignment = Alignment.Center
        ) {
            if (artworkImage != null) {
                Image(
                    bitmap = artworkImage,
                    contentDescription = "Обложка ${track.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    livingPalette.cardTint,
                                    livingPalette.dot1.copy(alpha = 0.28f),
                                    livingPalette.dot2.copy(alpha = 0.24f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        tint = effectiveAccent,
                        modifier = Modifier.size(72.dp)
                    )
                }
            }
        }

        val alignment = if (styleConfig.titleAlignment == PlayerTitleAlignment.CENTERED) {
            Alignment.CenterHorizontally
        } else {
            Alignment.Start
        }
        val textAlign = if (styleConfig.titleAlignment == PlayerTitleAlignment.CENTERED) {
            TextAlign.Center
        } else {
            TextAlign.Start
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalAlignment = alignment,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = track.title.ifBlank { "Без названия" },
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    ),
                    textAlign = textAlign,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (styleConfig.titleAlignment == PlayerTitleAlignment.CENTERED) {
                        Modifier
                    } else {
                        Modifier.weight(1f, fill = false)
                    }
                )
                if (track.playbackSpeed != 1.0f) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = NestedSurface,
                        border = BorderStroke(0.5.dp, SpecularStroke)
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.2fx", track.playbackSpeed),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = effectiveAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }

            Text(
                text = track.artist.ifBlank { "Неизвестный артист" },
                style = MaterialTheme.typography.titleMedium.copy(
                    color = effectiveAccent.copy(alpha = 0.92f),
                    fontWeight = FontWeight.Medium
                ),
                textAlign = textAlign,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee()
            )

            if (track.album.isNotBlank()) {
                Text(
                    text = track.album,
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerLyricsStage(
    context: Context,
    track: NowPlayingData,
    shownPositionMsState: State<Long>,
    effectiveAccent: Color,
    livingPalette: LivingPaletteState,
    lyricsState: PlayerLyricsUiState,
    onSeekToDisplayMs: (Long) -> Unit,
    onRefreshLyrics: () -> Unit,
    onOpenProviderMenu: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        when (lyricsState) {
            is PlayerLyricsUiState.Idle,
            is PlayerLyricsUiState.Loading -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    WaveLoadingDots(
                        color = effectiveAccent,
                        timeSecondsState = livingPalette.uiTimeSecondsState,
                        modifier = Modifier.size(width = 36.dp, height = 22.dp)
                    )
                    Text(
                        text = "Поиск текста трека...",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                    )
                }
            }

            is PlayerLyricsUiState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lyrics,
                        contentDescription = null,
                        tint = SemanticCoral,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = lyricsState.message,
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                        textAlign = TextAlign.Center
                    )
                    GraphiteGlassButton(onClick = onRefreshLyrics) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Повторить")
                    }
                }
            }

            is PlayerLyricsUiState.Loaded -> {
                val res = lyricsState.result
                if (!res.found) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(42.dp)
                        )
                        Text(
                            text = "Текст для этого трека не найден",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            ),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Можно попробовать выбрать другой источник через меню ⋮",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GraphiteGlassButton(onClick = onRefreshLyrics) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Искать снова")
                            }
                            GraphiteGlassButton(onClick = onOpenProviderMenu) {
                                Text("Источник")
                            }
                        }
                    }
                } else if (res.syncMode != LyricsSyncMode.UNSYNCED && res.lines.isNotEmpty()) {
                    val listState = rememberLazyListState()
                    var viewportHeightPx by remember { mutableIntStateOf(0) }
                    val activeIndex by remember(res.lines) {
                        derivedStateOf {
                            findActiveLyricsLineIndex(res.lines, shownPositionMsState.value)
                        }
                    }
                    val canScrollForward by remember {
                        derivedStateOf { listState.canScrollForward }
                    }
                    var lastUserScrollMs by remember { mutableLongStateOf(0L) }
                    LaunchedEffect(listState.isScrollInProgress) {
                        if (listState.isScrollInProgress) {
                            lastUserScrollMs = SystemClock.uptimeMillis()
                        }
                    }

                    LaunchedEffect(activeIndex, res.lines, viewportHeightPx) {
                        if (activeIndex >= 0) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastUserScrollMs > 3000L && !listState.isScrollInProgress) {
                                runCatching {
                                    // Anchor active line gently in the upper third (~28% from top)
                                    val targetOffset = if (viewportHeightPx > 0) {
                                        -(viewportHeightPx * 0.28f).toInt()
                                    } else 0
                                    listState.animateScrollToItem(
                                        index = activeIndex,
                                        scrollOffset = targetOffset
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewportHeightPx = it.height }
                    ) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(top = 48.dp, bottom = 140.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .lyricsVerticalEdgeFade(
                                    fadeTopDp = 24.dp,
                                    fadeBottomDp = if (canScrollForward) 24.dp else 0.dp
                                )
                                .testTag("synced_lyrics_list")
                        ) {
                            itemsIndexed(
                                items = res.lines,
                                key = { idx, line -> "${idx}_${line.timeMs}" }
                            ) { index, line ->
                                val interlude = remember(res.lines, index) {
                                    LyricsCatalogResolver.computeInterludeBefore(res.lines, index)
                                }
                                if (interlude != null) {
                                    InterludeDotsIndicator(
                                        interlude = interlude,
                                        shownPositionMsState = shownPositionMsState,
                                        timeSecondsState = livingPalette.uiTimeSecondsState,
                                        accentColor = effectiveAccent
                                    )
                                }

                                val nextLineTimeMs = res.lines.getOrNull(index + 1)?.timeMs
                                    ?: (track.durationMs.takeIf { it > line.timeMs } ?: (line.timeMs + 4_500L))
                                SyncedLyricLineRow(
                                    line = line,
                                    index = index,
                                    activeIndex = activeIndex,
                                    nextLineTimeMs = nextLineTimeMs,
                                    isUserScrolling = listState.isScrollInProgress,
                                    shownPositionMsState = shownPositionMsState,
                                    onSeekToLine = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSeekToDisplayMs(line.timeMs)
                                        lastUserScrollMs = 0L
                                    },
                                    onCopyQuote = {
                                        val quote = if (track.artist.isNotBlank()) {
                                            "«${line.text}» — ${track.artist}"
                                        } else {
                                            "«${line.text}»"
                                        }
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("Lyric Quote", quote))
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        Toast.makeText(context, "Цитата скопирована", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }

                            item {
                                LyricsProviderFooter(
                                    source = res.source,
                                    syncLabel = if (res.syncMode == LyricsSyncMode.WORD_SYNCED) {
                                        "Пословный текст • Долгое нажатие — копировать"
                                    } else {
                                        "Синхронный текст • Долгое нажатие — копировать"
                                    },
                                    accentColor = effectiveAccent,
                                    onOpenProviderMenu = onOpenProviderMenu
                                )
                            }
                        }

                        // "Jump back to current line" helper when user manually scrolls away
                        val showResumeAutoScroll by remember(activeIndex) {
                            derivedStateOf {
                                activeIndex >= 0 &&
                                    lastUserScrollMs > 0L &&
                                    (SystemClock.uptimeMillis() - lastUserScrollMs <= 3000L)
                            }
                        }
                        if (showResumeAutoScroll) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color(0xEE14171E),
                                border = BorderStroke(0.75.dp, SpecularStroke),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable {
                                        lastUserScrollMs = 0L
                                        scope.launch {
                                            val targetOffset = if (viewportHeightPx > 0) {
                                                -(viewportHeightPx * 0.28f).toInt()
                                            } else 0
                                            listState.animateScrollToItem(activeIndex.coerceAtLeast(0), targetOffset)
                                        }
                                    }
                            ) {
                                Text(
                                    text = "К текущей строке",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = effectiveAccent,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                } else {
                    // Honest Unsynced Lyrics UI (top frosted banner + crisp >=0.85 alpha + provider footer)
                    val unsyncedScrollState = rememberScrollState()
                    val canScrollForward by remember {
                        derivedStateOf { unsyncedScrollState.canScrollForward }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .lyricsVerticalEdgeFade(
                                fadeTopDp = 24.dp,
                                fadeBottomDp = if (canScrollForward) 24.dp else 0.dp
                            )
                            .verticalScroll(unsyncedScrollState)
                            .padding(start = 6.dp, end = 6.dp, top = 48.dp, bottom = 140.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = NestedSurface,
                            border = BorderStroke(0.75.dp, SpecularStroke),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("unsynced_lyrics_banner")
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = SemanticAmber,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Текст не синхронизирован с песней",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            color = TextPrimary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                                Text(
                                    text = "Источник: ${res.source.uppercase(Locale.US)} • Прокручивайте вручную",
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = SecondaryGlass,
                                        border = BorderStroke(0.5.dp, SpecularStroke),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .clickable(onClick = onRefreshLyrics)
                                    ) {
                                        Text(
                                            text = "Проверить таймкоды",
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = effectiveAccent,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = SecondaryGlass,
                                        border = BorderStroke(0.5.dp, SpecularStroke),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .clickable(onClick = onOpenProviderMenu)
                                    ) {
                                        Text(
                                            text = "Другой источник",
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = TextSecondary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = res.plainLyrics,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 19.sp,
                                lineHeight = 29.sp,
                                color = Color.White.copy(alpha = 0.90f),
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )

                        LyricsProviderFooter(
                            source = res.source,
                            syncLabel = "Обычный текст (без таймкодов)",
                            accentColor = effectiveAccent,
                            onOpenProviderMenu = onOpenProviderMenu
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsProviderFooter(
    source: String,
    syncLabel: String,
    accentColor: Color,
    onOpenProviderMenu: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = "Источник: ${source.uppercase(Locale.US)}",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Text(
                text = syncLabel,
                style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
            )
        }
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = SecondaryGlass,
            border = BorderStroke(0.5.dp, SpecularStroke),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onOpenProviderMenu)
                .testTag("lyrics_footer_provider_button")
        ) {
            Text(
                text = "Сменить источник",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

@Composable
private fun InterludeDotsIndicator(
    interlude: LyricsInterlude,
    shownPositionMsState: State<Long>,
    timeSecondsState: State<Float>,
    accentColor: Color
) {
    val isActive by remember(interlude) {
        derivedStateOf {
            val pos = shownPositionMsState.value
            pos in interlude.startMs until interlude.endMs
        }
    }
    AnimatedVisibility(visible = isActive) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until 3) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer {
                            val pos = shownPositionMsState.value
                            val span = (interlude.endMs - interlude.startMs).coerceAtLeast(1L).toFloat()
                            val overall = ((pos - interlude.startMs) / span).coerceIn(0f, 1f)
                            val pulse = 0.55f + 0.45f * ((sin(timeSecondsState.value * 4.2f + i * 1.1f) + 1f) * 0.5f)
                            val activeDot = if (overall >= i / 3f) 1.15f else 0.85f
                            scaleX = pulse * activeDot
                            scaleY = pulse * activeDot
                            alpha = if (overall >= i / 3f) 0.95f else 0.42f
                        }
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncedLyricLineRow(
    line: LyricsLine,
    index: Int,
    activeIndex: Int,
    nextLineTimeMs: Long,
    isUserScrolling: Boolean,
    shownPositionMsState: State<Long>,
    onSeekToLine: () -> Unit,
    onCopyQuote: () -> Unit
) {
    val distance = kotlin.math.abs(index - activeIndex)
    val isActive = index == activeIndex

    val targetScale = when {
        isActive -> 1.04f
        distance == 1 -> 0.97f
        distance == 2 -> 0.96f
        else -> 0.95f
    }
    val targetAlpha = when {
        isUserScrolling -> if (isActive) 1.0f else 0.88f
        activeIndex < 0 -> 0.65f
        isActive -> 1.0f
        distance == 1 -> 0.58f
        distance == 2 -> 0.44f
        else -> 0.30f
    }

    val animSpec = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }
    val lineScale by animateFloatAsState(targetValue = targetScale, animationSpec = animSpec, label = "lyric_scale")
    val lineAlpha by animateFloatAsState(targetValue = targetAlpha, animationSpec = tween(220), label = "lyric_alpha")

    val rowModifier = Modifier
        .fillMaxWidth()
        .graphicsLayer {
            scaleX = lineScale
            scaleY = lineScale
            alpha = lineAlpha
            transformOrigin = TransformOrigin(0f, 0.5f)
        }
        .clip(RoundedCornerShape(12.dp))
        .combinedClickable(
            onClick = onSeekToLine,
            onLongClick = onCopyQuote
        )
        .padding(horizontal = 8.dp, vertical = 4.dp)

    if (isActive) {
        val unreadColor = Color.White.copy(alpha = 0.54f)
        val wordProgress by remember(line, nextLineTimeMs) {
            derivedStateOf {
                val (wIdx, rawFrac) = computeLineWordHighlightState(
                    line = line,
                    nextLineTimeMs = nextLineTimeMs,
                    positionMs = shownPositionMsState.value
                )
                // Quantize fraction to 5% steps (~20 steps/word) so scrolling text stays 120fps light
                val step = ((rawFrac * 20f).toInt().coerceIn(0, 20)) / 20f
                wIdx to step
            }
        }
        val activeWordIdx = wordProgress.first
        val activeWordFrac = wordProgress.second

        val annotated = if (line.words.isNotEmpty()) {
            remember(line.words, activeWordIdx, activeWordFrac) {
                buildAnnotatedString {
                    line.words.forEachIndexed { wIndex, w ->
                        val wordColor = when {
                            wIndex < activeWordIdx -> Color.White
                            wIndex == activeWordIdx -> lerp(unreadColor, Color.White, activeWordFrac)
                            else -> unreadColor
                        }
                        withStyle(SpanStyle(color = wordColor)) {
                            append(w.text)
                        }
                    }
                }
            }
        } else {
            val tokens = remember(line.text) { tokenizeLyricWords(line.text) }
            remember(tokens, activeWordIdx, activeWordFrac) {
                buildAnnotatedString {
                    tokens.forEach { token ->
                        if (!token.isWord) {
                            append(token.text)
                        } else {
                            val wordColor = when {
                                token.wordOrdinal < activeWordIdx -> Color.White
                                token.wordOrdinal == activeWordIdx -> lerp(unreadColor, Color.White, activeWordFrac)
                                else -> unreadColor
                            }
                            withStyle(SpanStyle(color = wordColor)) {
                                append(token.text)
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = annotated,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 21.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = rowModifier
        )
    } else {
        Text(
            text = line.text,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 21.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            ),
            modifier = rowModifier
        )
    }
}

@Composable
private fun PlayerSeekSection(
    track: NowPlayingData,
    shownPositionMsState: State<Long>,
    accentColor: Color,
    wavyEnabled: Boolean,
    showRemainingTime: Boolean,
    onToggleRemainingTime: () -> Unit,
    onScrubStart: (Long) -> Unit,
    onScrubMove: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit
) {
    val shownPositionMs = shownPositionMsState.value
    val duration = track.durationMs.coerceAtLeast(0L)
    val fraction = if (duration > 0L) (shownPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    var barWidthPx by remember { mutableIntStateOf(1) }

    fun xToTargetMs(x: Float): Long {
        if (duration <= 0L) return 0L
        val ratio = (x / barWidthPx.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
        return (duration * ratio).toLong()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .onSizeChanged { barWidthPx = it.width.coerceAtLeast(1) }
                .pointerInput(duration) {
                    if (duration > 0L) {
                        detectTapGestures { pos ->
                            val t = xToTargetMs(pos.x)
                            onScrubStart(t)
                            onScrubEnd(t)
                        }
                    }
                }
                .pointerInput(duration) {
                    if (duration > 0L) {
                        var lastTarget = 0L
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                lastTarget = xToTargetMs(start.x)
                                onScrubStart(lastTarget)
                            },
                            onDragEnd = { onScrubEnd(lastTarget) },
                            onDragCancel = { onScrubEnd(lastTarget) }
                        ) { change, _ ->
                            change.consume()
                            lastTarget = xToTargetMs(change.position.x)
                            onScrubMove(lastTarget)
                        }
                    }
                }
                .testTag("player_seek_bar"),
            contentAlignment = Alignment.Center
        ) {
            if (wavyEnabled) {
                WavyPlaybackProgressBar(
                    fraction = fraction,
                    isPlaying = track.isPlaying,
                    activeColor = accentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                )
            } else {
                CleanLinearSeekBar(
                    fraction = fraction,
                    activeColor = accentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatPlayerClock(shownPositionMs),
                style = MaterialTheme.typography.labelLarge.withTabularNumbers().copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold
                )
            )

            val rightLabel = if (showRemainingTime && duration > 0L) {
                "-${formatPlayerClock((duration - shownPositionMs).coerceAtLeast(0L))}"
            } else {
                formatPlayerClock(duration)
            }
            Text(
                text = rightLabel,
                style = MaterialTheme.typography.labelMedium.withTabularNumbers().copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = TextMuted
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleRemainingTime)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun CleanLinearSeekBar(
    fraction: Float,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val cy = size.height / 2f
        val splitX = (size.width * fraction.coerceIn(0f, 1f)).coerceIn(0f, size.width)
        drawLine(
            color = Color.White.copy(alpha = 0.16f),
            start = androidx.compose.ui.geometry.Offset(0f, cy),
            end = androidx.compose.ui.geometry.Offset(size.width, cy),
            strokeWidth = 4.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        if (splitX > 1f) {
            drawLine(
                color = activeColor,
                start = androidx.compose.ui.geometry.Offset(0f, cy),
                end = androidx.compose.ui.geometry.Offset(splitX, cy),
                strokeWidth = 4.5.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
        drawCircle(
            color = activeColor,
            radius = 6.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(splitX, cy)
        )
    }
}

@Composable
private fun PlayerTransportControls(
    isPlaying: Boolean,
    accentColor: Color,
    onSeekMinus10: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeekPlus10: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onSeekMinus10,
            modifier = Modifier
                .size(48.dp)
                .testTag("player_replay_10_button")
        ) {
            Icon(
                imageVector = Icons.Default.Replay10,
                contentDescription = "Назад 10 секунд",
                tint = TextSecondary,
                modifier = Modifier.size(26.dp)
            )
        }

        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .size(52.dp)
                .testTag("player_prev_button")
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Предыдущий трек",
                tint = TextPrimary,
                modifier = Modifier.size(34.dp)
            )
        }

        val playCorner by animateDpAsState(
            targetValue = if (isPlaying) 22.dp else 34.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "play_btn_corner"
        )
        Surface(
            shape = RoundedCornerShape(playCorner),
            color = accentColor,
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(playCorner))
                .clickable(onClick = onTogglePlayPause)
                .testTag("player_play_pause_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                    tint = Color(0xFF06080C),
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        IconButton(
            onClick = onNext,
            modifier = Modifier
                .size(52.dp)
                .testTag("player_next_button")
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Следующий трек",
                tint = TextPrimary,
                modifier = Modifier.size(34.dp)
            )
        }

        IconButton(
            onClick = onSeekPlus10,
            modifier = Modifier
                .size(48.dp)
                .testTag("player_forward_10_button")
        ) {
            Icon(
                imageVector = Icons.Default.Forward10,
                contentDescription = "Вперёд 10 секунд",
                tint = TextSecondary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun PlayerBottomActionPills(
    syncOffsetMs: Int,
    isOffsetPopoverOpen: Boolean,
    isLyricsMode: Boolean,
    hasSyncedLyrics: Boolean,
    accentColor: Color,
    onToggleOffsetPopover: () -> Unit,
    onToggleLyrics: () -> Unit,
    onOpenInfo: () -> Unit
) {
    val offsetLabel = if (syncOffsetMs > 0) "+${syncOffsetMs} мс" else "${syncOffsetMs} мс"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayerActionPill(
            active = isOffsetPopoverOpen || syncOffsetMs != 0,
            accentColor = accentColor,
            onClick = onToggleOffsetPopover,
            modifier = Modifier
                .weight(1f)
                .testTag("player_offset_pill")
        ) {
            Icon(
                imageVector = Icons.Default.Timer,
                contentDescription = null,
                tint = if (isOffsetPopoverOpen || syncOffsetMs != 0) accentColor else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = offsetLabel,
                style = MaterialTheme.typography.labelMedium.withTabularNumbers().copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = if (isOffsetPopoverOpen || syncOffsetMs != 0) accentColor else TextSecondary,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
        }

        PlayerActionPill(
            active = isLyricsMode,
            accentColor = accentColor,
            onClick = onToggleLyrics,
            modifier = Modifier
                .weight(1f)
                .testTag("player_lyrics_pill")
        ) {
            Icon(
                imageVector = Icons.Default.Lyrics,
                contentDescription = null,
                tint = if (isLyricsMode) accentColor else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "Lyrics",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = if (isLyricsMode) accentColor else TextSecondary,
                    fontWeight = if (isLyricsMode) FontWeight.SemiBold else FontWeight.Medium
                ),
                maxLines = 1
            )
            if (hasSyncedLyrics && !isLyricsMode) {
                Spacer(modifier = Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
        }

        PlayerActionPill(
            active = false,
            accentColor = accentColor,
            onClick = onOpenInfo,
            modifier = Modifier
                .weight(1f)
                .testTag("player_info_pill")
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "Инфо",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PlayerActionPill(
    active: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) accentColor.copy(alpha = 0.16f) else SecondaryGlass,
        border = BorderStroke(
            0.75.dp,
            if (active) accentColor.copy(alpha = 0.42f) else SpecularStroke
        ),
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}

@Composable
private fun PlayerQuickOffsetBar(
    syncOffsetMs: Int,
    accentColor: Color,
    onSyncOffsetChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = SheetSurface,
        border = BorderStroke(0.75.dp, SpecularStroke),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GraphiteGlassButton(
                onClick = { onSyncOffsetChange((syncOffsetMs - 100).coerceIn(-2000, 2000)) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("-100 мс", style = MaterialTheme.typography.labelMedium.withTabularNumbers())
            }

            GraphiteGlassButton(
                onClick = { onSyncOffsetChange(0) },
                enabled = syncOffsetMs != 0,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("0 мс", style = MaterialTheme.typography.labelMedium, color = accentColor)
            }

            GraphiteGlassButton(
                onClick = { onSyncOffsetChange((syncOffsetMs + 100).coerceIn(-2000, 2000)) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("+100 мс", style = MaterialTheme.typography.labelMedium.withTabularNumbers())
            }

            IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerStyleSettingsSheet(
    styleConfig: PlayerStyleConfig,
    accentColor: Color,
    onUpdateStyle: ((PlayerStyleConfig) -> PlayerStyleConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF513161D),
        scrimColor = Color.Black.copy(alpha = 0.35f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted.copy(alpha = 0.5f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Text(
                text = "Стиль плеера (живое превью)",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            )

            StyleChipSection(
                label = "Обложка",
                options = PlayerCoverStyle.entries.map { it to it.label },
                selected = styleConfig.coverStyle,
                accentColor = accentColor,
                onSelect = { chosen -> onUpdateStyle { it.copy(coverStyle = chosen) } }
            )

            StyleChipSection(
                label = "Выравнивание заголовка",
                options = PlayerTitleAlignment.entries.map { it to it.label },
                selected = styleConfig.titleAlignment,
                accentColor = accentColor,
                onSelect = { chosen -> onUpdateStyle { it.copy(titleAlignment = chosen) } }
            )

            StyleChipSection(
                label = "Фон плеера",
                options = PlayerBackgroundMode.entries.map { it to it.label },
                selected = styleConfig.backgroundMode,
                accentColor = accentColor,
                onSelect = { chosen -> onUpdateStyle { it.copy(backgroundMode = chosen) } }
            )

            StyleChipSection(
                label = "Цвет акцента",
                options = PlayerAccentSource.entries.map { it to it.label },
                selected = styleConfig.accentSource,
                accentColor = accentColor,
                onSelect = { chosen -> onUpdateStyle { it.copy(accentSource = chosen) } }
            )

            HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Свайп обложки для переключения",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary, fontWeight = FontWeight.Medium)
                    )
                    Text(
                        text = "Смахивание влево/вправо переключает треки",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                    )
                }
                ExpressiveGlassSwitch(
                    checked = styleConfig.swipeCoverToSkip,
                    onCheckedChange = { v -> onUpdateStyle { it.copy(swipeCoverToSkip = v) } }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Волнистый прогресс-бар",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary, fontWeight = FontWeight.Medium)
                    )
                    Text(
                        text = "Плавная волна Material 3 Expressive при воспроизведении",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                    )
                }
                ExpressiveGlassSwitch(
                    checked = styleConfig.wavySeekbarEnabled,
                    onCheckedChange = { v -> onUpdateStyle { it.copy(wavySeekbarEnabled = v) } }
                )
            }
        }
    }
}

@Composable
private fun <T> StyleChipSection(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    accentColor: Color,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(color = TextMuted)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, title) ->
                val isSelected = value == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(value) },
                    label = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = NestedSurface,
                        labelColor = TextSecondary,
                        selectedContainerColor = accentColor.copy(alpha = 0.20f),
                        selectedLabelColor = accentColor
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = SpecularStroke,
                        selectedBorderColor = accentColor.copy(alpha = 0.45f)
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTrackInfoSheet(
    track: NowPlayingData,
    appDisplayName: String,
    lyricsState: PlayerLyricsUiState,
    artworkDebugStatus: String,
    accentColor: Color,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetSurface,
        scrimColor = SheetScrim,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextMuted.copy(alpha = 0.5f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Информация о треке",
                style = MaterialTheme.typography.titleLarge.copy(color = TextPrimary)
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = NestedSurface,
                border = BorderStroke(0.5.dp, SpecularStroke),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Название", track.title.ifBlank { "—" })
                    InfoRow("Исполнитель", track.artist.ifBlank { "—" })
                    InfoRow("Альбом", track.album.ifBlank { "—" })
                    InfoRow("Плеер", "$appDisplayName (${track.player.ifBlank { "—" }})")
                    InfoRow("Длительность", formatPlayerClock(track.durationMs))
                    InfoRow("Скорость", String.format(Locale.US, "%.2fx", track.playbackSpeed))
                    InfoRow("Обложка", artworkDebugStatus)
                    val lyricsLabel = when (lyricsState) {
                        is PlayerLyricsUiState.Loading -> "Загрузка..."
                        is PlayerLyricsUiState.Loaded -> {
                            val r = lyricsState.result
                            if (!r.found) "Не найдена"
                            else {
                                val modeText = when (r.syncMode) {
                                    LyricsSyncMode.WORD_SYNCED -> "пословный LRC"
                                    LyricsSyncMode.LINE_SYNCED -> "синхронный LRC"
                                    LyricsSyncMode.UNSYNCED -> "обычный текст"
                                }
                                "${r.source.uppercase(Locale.US)} ($modeText)"
                            }
                        }
                        is PlayerLyricsUiState.Error -> "Ошибка: ${lyricsState.message}"
                        is PlayerLyricsUiState.Idle -> "Ожидание"
                    }
                    InfoRow("Текст песни", lyricsLabel)
                }
            }

            val swatches = track.extendedColorsHex.ifEmpty { track.accentColorsHex }
            if (swatches.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Палитра:",
                        style = MaterialTheme.typography.labelMedium.copy(color = TextMuted)
                    )
                    swatches.forEach { hex ->
                        val parsed = runCatching {
                            Color(android.graphics.Color.parseColor(hex))
                        }.getOrDefault(accentColor)
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(parsed)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(color = TextMuted),
            modifier = Modifier.width(116.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                color = TextPrimary,
                fontFamily = JetBrainsMonoFontFamily
            ),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun Modifier.lyricsVerticalEdgeFade(
    fadeTopDp: Dp = 24.dp,
    fadeBottomDp: Dp = 24.dp
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val h = size.height.coerceAtLeast(1f)
        val topFrac = if (fadeTopDp > 0.dp) (fadeTopDp.toPx() / h).coerceIn(0f, 0.30f) else 0f
        val bottomFrac = if (fadeBottomDp > 0.dp) {
            (1f - (fadeBottomDp.toPx() / h).coerceIn(0f, 0.30f)).coerceIn(topFrac + 0.01f, 1f)
        } else {
            1f
        }
        val topEdge = if (fadeTopDp > 0.dp) Color.Transparent else Color.Black
        val bottomEdge = if (fadeBottomDp > 0.dp) Color.Transparent else Color.Black
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to topEdge,
                topFrac to Color.Black,
                bottomFrac to Color.Black,
                1.0f to bottomEdge
            ),
            blendMode = BlendMode.DstIn
        )
    }
