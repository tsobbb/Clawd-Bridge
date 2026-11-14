package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lyrics.LyricsLine
import com.example.model.NowPlayingData
import com.example.state.PlayerAccentSource
import com.example.state.PlayerBackgroundMode
import com.example.state.PlayerCoverStyle
import com.example.state.PlayerLyricsUiState
import com.example.state.PlayerStyleConfig
import com.example.state.PlayerTitleAlignment
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LivingAuroraBackdrop
import com.example.ui.theme.LivingPaletteState
import com.example.ui.theme.MintCta
import com.example.ui.theme.NestedSurface
import com.example.ui.theme.SecondaryGlass
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
import kotlin.math.abs

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
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showStyleSheet by remember { mutableStateOf(false) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var showOffsetPopover by remember { mutableStateOf(false) }
    var showRemainingTime by remember { mutableStateOf(false) }

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
            showOverflowMenu -> showOverflowMenu = false
            showStyleSheet -> showStyleSheet = false
            showInfoSheet -> showInfoSheet = false
            showOffsetPopover -> showOffsetPopover = false
            isLyricsMode -> isLyricsMode = false
            else -> onDismiss()
        }
    }

    // 60/120 FPS client-side display position interpolation
    var displayPositionMs by remember { mutableLongStateOf(track.positionMs) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(track.positionMs) }

    LaunchedEffect(
        track.positionMs,
        track.isPlaying,
        track.playbackSpeed,
        track.durationMs,
        track.title,
        track.artist,
        track.hasMedia
    ) {
        displayPositionMs = track.positionMs
        if (track.hasMedia && track.isPlaying) {
            var lastNanos = 0L
            while (isActive) {
                withFrameNanos { nanos ->
                    if (lastNanos != 0L) {
                        val dtMs = ((nanos - lastNanos).coerceIn(0L, 100_000_000L) / 1_000_000.0)
                        val speed = if (track.playbackSpeed > 0f) track.playbackSpeed else 1f
                        val next = displayPositionMs + (dtMs * speed).toLong()
                        displayPositionMs = if (track.durationMs > 0L) {
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

    val shownPositionMs = if (isScrubbing) scrubPositionMs else displayPositionMs
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = animatedDragOffset }
            .background(Color(0xFF06080C))
            .testTag("fullscreen_player_overlay")
    ) {
        // 1. Background layer driven by PlayerBackgroundMode
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
            // Top Header Bar (swipe-down safe handle + overflow menu)
            PlayerHeaderBar(
                isLyricsMode = isLyricsMode,
                track = track,
                artworkImage = validBitmap,
                resolvedAppName = resolvedApp?.displayName ?: "Clawd Bridge",
                resolvedAppIcon = resolvedApp?.icon,
                accentColor = effectiveAccent,
                showOverflowMenu = showOverflowMenu,
                preferredProvider = preferredLyricsProvider,
                onDismiss = onDismiss,
                onHeaderTap = {
                    if (isLyricsMode) isLyricsMode = false
                },
                onOpenMenu = { showOverflowMenu = true },
                onCloseMenu = { showOverflowMenu = false },
                onOpenStyleSheet = {
                    showOverflowMenu = false
                    showStyleSheet = true
                },
                onRefreshLyrics = {
                    showOverflowMenu = false
                    onRefreshLyrics(null)
                },
                onSelectProvider = { providerId ->
                    showOverflowMenu = false
                    isLyricsMode = true
                    onRefreshLyrics(providerId)
                },
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
                            track = track,
                            displayPositionMs = shownPositionMs,
                            syncOffsetMs = syncOffsetMs,
                            effectiveAccent = effectiveAccent,
                            livingPalette = livingPalette,
                            lyricsState = lyricsState,
                            onSeekToRawMs = onSeekToRawMs,
                            onRefreshLyrics = { onRefreshLyrics(null) }
                        )
                    }
                }
            }

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

            // Interactive Seekbar + Timecodes
            PlayerSeekSection(
                track = track,
                shownPositionMs = shownPositionMs,
                accentColor = effectiveAccent,
                wavyEnabled = styleConfig.wavySeekbarEnabled,
                showRemainingTime = showRemainingTime,
                onToggleRemainingTime = { showRemainingTime = !showRemainingTime },
                onScrubStart = { targetMs ->
                    isScrubbing = true
                    scrubPositionMs = targetMs
                },
                onScrubMove = { targetMs ->
                    scrubPositionMs = targetMs
                },
                onScrubEnd = { targetMs ->
                    isScrubbing = false
                    displayPositionMs = targetMs
                    val rawSeek = computeCompensatedSeekTargetMs(targetMs, track.durationMs, syncOffsetMs)
                    onSeekToRawMs(rawSeek)
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Transport Bar: -10s | Prev | Play/Pause | Next | +10s
            PlayerTransportControls(
                isPlaying = track.isPlaying,
                accentColor = effectiveAccent,
                onSeekMinus10 = { onSeekByDelta(-10_000L) },
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
                onSeekPlus10 = { onSeekByDelta(10_000L) }
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

    // Live Style Customization Sheet
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
            LivingAuroraBackdrop(palette = livingPalette)
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
                    .background(Color(0xFF07080B))
            ) {
                if (blurSource != null) {
                    val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier.blur(95.dp)
                    } else {
                        Modifier
                    }
                    Image(
                        bitmap = blurSource,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
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
                } else {
                    LivingAuroraBackdrop(palette = livingPalette)
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
    track: NowPlayingData,
    artworkImage: androidx.compose.ui.graphics.ImageBitmap?,
    resolvedAppName: String,
    resolvedAppIcon: androidx.compose.ui.graphics.ImageBitmap?,
    accentColor: Color,
    showOverflowMenu: Boolean,
    preferredProvider: String,
    onDismiss: () -> Unit,
    onHeaderTap: () -> Unit,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onOpenStyleSheet: () -> Unit,
    onRefreshLyrics: () -> Unit,
    onSelectProvider: (String) -> Unit,
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

        Box {
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

            DropdownMenu(
                expanded = showOverflowMenu,
                onDismissRequest = onCloseMenu,
                containerColor = SheetSurface
            ) {
                DropdownMenuItem(
                    text = { Text("Настройки стиля", color = TextPrimary) },
                    leadingIcon = {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = accentColor)
                    },
                    onClick = onOpenStyleSheet
                )
                DropdownMenuItem(
                    text = { Text("Обновить текст трека", color = TextPrimary) },
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = TextSecondary)
                    },
                    onClick = onRefreshLyrics
                )
                HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)
                LYRICS_PROVIDERS.forEach { (id, label) ->
                    val isSelected = preferredProvider == id
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                color = if (isSelected) accentColor else TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        trailingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        onClick = { onSelectProvider(id) }
                    )
                }
            }
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

@Composable
private fun PlayerLyricsStage(
    track: NowPlayingData,
    displayPositionMs: Long,
    syncOffsetMs: Int,
    effectiveAccent: Color,
    livingPalette: LivingPaletteState,
    lyricsState: PlayerLyricsUiState,
    onSeekToRawMs: (Long) -> Unit,
    onRefreshLyrics: () -> Unit
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
                        GraphiteGlassButton(onClick = onRefreshLyrics) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Искать снова")
                        }
                    }
                } else if (res.synced && res.lines.isNotEmpty()) {
                    val listState = rememberLazyListState()
                    val activeIndex = remember(res.lines, displayPositionMs) {
                        findActiveLyricsLineIndex(res.lines, displayPositionMs)
                    }
                    var lastUserScrollMs by remember { mutableLongStateOf(0L) }
                    LaunchedEffect(listState.isScrollInProgress) {
                        if (listState.isScrollInProgress) {
                            lastUserScrollMs = SystemClock.uptimeMillis()
                        }
                    }

                    LaunchedEffect(activeIndex, res.lines) {
                        if (activeIndex >= 0) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastUserScrollMs > 3000L && !listState.isScrollInProgress) {
                                runCatching {
                                    listState.animateScrollToItem(
                                        index = (activeIndex - 1).coerceAtLeast(0),
                                        scrollOffset = 0
                                    )
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(top = 36.dp, bottom = 56.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .lyricsVerticalEdgeFade()
                                .testTag("synced_lyrics_list")
                        ) {
                            itemsIndexed(
                                items = res.lines,
                                key = { idx, line -> "${idx}_${line.timeMs}" }
                            ) { index, line ->
                                val isActive = index == activeIndex
                                val isPassed = activeIndex >= 0 && index < activeIndex
                                val lineScale by animateFloatAsState(
                                    targetValue = if (isActive) 1.03f else 1.0f,
                                    animationSpec = tween(220),
                                    label = "lyric_scale"
                                )
                                val textColor = when {
                                    isActive -> Color.White
                                    isPassed -> Color.White.copy(alpha = 0.36f)
                                    else -> Color.White.copy(alpha = 0.62f)
                                }

                                Text(
                                    text = line.text,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = if (isActive) 22.sp else 19.sp,
                                        lineHeight = if (isActive) 30.sp else 27.sp,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                                        color = textColor
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            scaleX = lineScale
                                            scaleY = lineScale
                                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                        }
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            val rawTarget = computeCompensatedSeekTargetMs(
                                                targetDisplayMs = line.timeMs,
                                                durationMs = track.durationMs,
                                                syncOffsetMs = syncOffsetMs
                                            )
                                            onSeekToRawMs(rawTarget)
                                            lastUserScrollMs = 0L
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            item {
                                Text(
                                    text = "Источник: ${res.source.uppercase(Locale.US)} • Синхронный текст",
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextMuted),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                                )
                            }
                        }

                        // "Jump back to current line" helper when user manually scrolls away
                        val showResumeAutoScroll = activeIndex >= 0 &&
                            SystemClock.uptimeMillis() - lastUserScrollMs <= 3000L
                        if (showResumeAutoScroll) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = SheetSurface,
                                border = BorderStroke(0.75.dp, SpecularStroke),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable {
                                        lastUserScrollMs = 0L
                                        scope.launch {
                                            listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
                                        }
                                    }
                            ) {
                                Text(
                                    text = "К текущей строке",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = effectiveAccent,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                } else {
                    // Plain non-synced lyrics view
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .lyricsVerticalEdgeFade()
                            .padding(horizontal = 8.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = NestedSurface,
                            border = BorderStroke(0.5.dp, SpecularStroke)
                        ) {
                            Text(
                                text = "Без таймингов • ${res.source.uppercase(Locale.US)}",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                            )
                        }
                        Text(
                            text = res.plainLyrics,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 19.sp,
                                lineHeight = 29.sp,
                                color = Color.White.copy(alpha = 0.88f),
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerSeekSection(
    track: NowPlayingData,
    shownPositionMs: Long,
    accentColor: Color,
    wavyEnabled: Boolean,
    showRemainingTime: Boolean,
    onToggleRemainingTime: () -> Unit,
    onScrubStart: (Long) -> Unit,
    onScrubMove: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit
) {
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Стиль встроенного плеера",
                style = MaterialTheme.typography.titleLarge.copy(color = TextPrimary)
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
                            else "${r.source.uppercase(Locale.US)} (${if (r.synced) "синхронный LRC" else "обычный текст"})"
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

private fun Modifier.lyricsVerticalEdgeFade(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.08f to Color.Black,
                0.88f to Color.Black,
                1.0f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }
