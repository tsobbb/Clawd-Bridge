package com.example.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.NowPlayingData
import com.example.ui.theme.CardSurfaceDense
import com.example.ui.theme.CardSurfaceMonitor
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LivingPaletteState
import com.example.ui.theme.MintCta
import com.example.ui.theme.MintCtaContent
import com.example.ui.theme.NestedSurface
import com.example.ui.theme.SemanticAmber
import com.example.ui.theme.SemanticCoral
import com.example.ui.theme.SemanticEmerald
import com.example.ui.theme.SpecularStroke
import com.example.ui.theme.TextDim
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.withTabularNumbers
import com.example.util.AppMetadataResolver
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MonitorTab(
    liveState: NowPlayingData,
    artworkBitmap: Bitmap?,
    livingPalette: LivingPaletteState,
    isServiceConnected: Boolean,
    isNotificationAccessGranted: Boolean,
    isSimulatorActive: Boolean,
    isSimulatorPlaying: Boolean,
    onForceRebind: () -> Unit,
    onOpenFullscreenPlayer: () -> Unit,
    onToggleSimulator: (Boolean) -> Unit,
    onPlayPauseSimulator: () -> Unit,
    onSeekForwardSimulator: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ServiceStatusBanner(
            isServiceConnected = isServiceConnected,
            isNotificationAccessGranted = isNotificationAccessGranted,
            timeSecondsState = livingPalette.uiTimeSecondsState,
            onForceRebind = onForceRebind,
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        HeroLiveMonitorCard(
            liveState = liveState,
            artworkBitmap = artworkBitmap,
            livingPalette = livingPalette,
            isSimulatorActive = isSimulatorActive,
            onOpenFullscreenPlayer = onOpenFullscreenPlayer
        )

        SimulatorCard(
            isActive = isSimulatorActive,
            isPlaying = isSimulatorPlaying,
            onToggle = onToggleSimulator,
            onPlayPause = onPlayPauseSimulator,
            onSeekForward = onSeekForwardSimulator
        )
    }
}

private data class StatusBannerInfo(
    val title: String,
    val subtitle: String,
    val tint: Color,
    val icon: ImageVector
)

@Composable
fun ServiceStatusBanner(
    isServiceConnected: Boolean,
    isNotificationAccessGranted: Boolean,
    timeSecondsState: State<Float>,
    onForceRebind: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val info = when {
        isNotificationAccessGranted && isServiceConnected -> StatusBannerInfo(
            title = "Служба активна",
            subtitle = "IPC подключён • перехват и SSE работают в фоне",
            tint = SemanticEmerald,
            icon = Icons.Default.CheckCircle
        )
        isNotificationAccessGranted && !isServiceConnected -> StatusBannerInfo(
            title = "Восстановление связки...",
            subtitle = "Авто-Rebind в процессе • нажмите для сброса",
            tint = SemanticAmber,
            icon = Icons.Default.Refresh
        )
        else -> StatusBannerInfo(
            title = "Нужен доступ к уведомлениям",
            subtitle = "Включите разрешение для чтения медиа-сессий",
            tint = SemanticCoral,
            icon = Icons.Default.ErrorOutline
        )
    }

    FrostedGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("service_status_banner")
            .clip(RoundedCornerShape(22.dp))
            .clickable {
                if (!isNotificationAccessGranted) onOpenSettings()
                else if (!isServiceConnected) onForceRebind()
            },
        containerColor = CardSurfaceDense,
        cornerRadius = 22.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(info.tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = info.icon,
                    contentDescription = info.title,
                    tint = info.tint,
                    modifier = Modifier
                        .size(21.dp)
                        .graphicsLayer {
                            alpha = if (isServiceConnected) {
                                0.65f + 0.35f * ((sin(timeSecondsState.value * 3.2f) + 1f) * 0.5f)
                            } else {
                                1f
                            }
                        }
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                )
                Text(
                    text = info.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isNotificationAccessGranted) {
                Button(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MintCta,
                        contentColor = MintCtaContent
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(
                        text = "Включить",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            } else if (!isServiceConnected) {
                IconButton(
                    onClick = onForceRebind,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Сбросить и переподключить",
                        tint = SemanticAmber
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroLiveMonitorCard(
    liveState: NowPlayingData,
    artworkBitmap: Bitmap?,
    livingPalette: LivingPaletteState,
    isSimulatorActive: Boolean,
    onOpenFullscreenPlayer: () -> Unit
) {
    val context = LocalContext.current
    val hasMedia = liveState.hasMedia
    val isPlaying = liveState.hasMedia && liveState.isPlaying
    val playerAccent = livingPalette.playerAccent

    val imageBitmap = remember(artworkBitmap) {
        artworkBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
    }

    val coverScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.94f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "cover_pause_scale"
    )

    // Smooth 60/120 FPS client-side position interpolation between 250ms engine ticks
    var displayPositionMs by remember { mutableLongStateOf(liveState.positionMs) }
    LaunchedEffect(
        liveState.positionMs,
        liveState.isPlaying,
        liveState.playbackSpeed,
        liveState.durationMs,
        liveState.title,
        liveState.hasMedia
    ) {
        displayPositionMs = liveState.positionMs
        if (liveState.hasMedia && liveState.isPlaying) {
            var lastNanos = 0L
            while (true) {
                withFrameNanos { nanos ->
                    if (lastNanos != 0L) {
                        val dtMs = ((nanos - lastNanos).coerceIn(0L, 100_000_000L) / 1_000_000.0)
                        val speed = if (liveState.playbackSpeed > 0f) liveState.playbackSpeed else 1f
                        val next = displayPositionMs + (dtMs * speed).toLong()
                        displayPositionMs = if (liveState.durationMs > 0L) {
                            next.coerceIn(0L, liveState.durationMs)
                        } else {
                            next.coerceAtLeast(0L)
                        }
                    }
                    lastNanos = nanos
                }
            }
        }
    }

    val cardBaseColor = remember(CardSurfaceMonitor, livingPalette.cardTint, hasMedia) {
        if (hasMedia) lerp(CardSurfaceMonitor, livingPalette.cardTint.copy(alpha = 0.80f), 0.35f)
        else CardSurfaceMonitor
    }

    FrostedGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .clickable(enabled = hasMedia) { onOpenFullscreenPlayer() }
            .testTag("live_monitor_card"),
        containerColor = cardBaseColor,
        cornerRadius = 26.dp,
        borderColor = SpecularStroke
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Subtle deeply blurred artwork reflection inside hero card
            if (hasMedia && imageBitmap != null) {
                val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.blur(45.dp)
                } else {
                    Modifier
                }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .alpha(if (isPlaying) 0.16f else 0.08f)
                        .then(blurMod)
                )
            } else if (hasMedia) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    livingPalette.cardTint.copy(alpha = 0.55f),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header: Equalizer + LIVE MONITOR + 250ms | Palette dots + SIMULATOR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MiniEqualizerBars(
                            isPlaying = isPlaying,
                            color = if (isPlaying) playerAccent else TextDim,
                            timeSecondsState = livingPalette.uiTimeSecondsState,
                            modifier = Modifier.size(width = 16.dp, height = 14.dp)
                        )
                        Text(
                            text = "LIVE MONITOR",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isPlaying) playerAccent else TextMuted,
                                letterSpacing = 0.9.sp
                            )
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = NestedSurface,
                            border = BorderStroke(0.5.dp, SpecularStroke)
                        ) {
                            Text(
                                text = "250 ms",
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = TextMuted
                                )
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (hasMedia) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(
                                    livingPalette.dot1,
                                    livingPalette.dot2,
                                    livingPalette.dot3
                                ).forEach { dot ->
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(dot)
                                    )
                                }
                            }
                        }

                        if (hasMedia && liveState.playbackSpeed != 1.0f) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = NestedSurface
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.2fx", liveState.playbackSpeed),
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = playerAccent,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }

                        if (isSimulatorActive) {
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = NestedSurface,
                                border = BorderStroke(0.5.dp, SpecularStroke)
                            ) {
                                Text(
                                    text = "SIMULATOR",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = TextSecondary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }

                        if (hasMedia) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(NestedSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInFull,
                                    contentDescription = "Открыть встроенный плеер",
                                    tint = playerAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }

                if (hasMedia) {
                    val resolvedPlayer = remember(liveState.player) {
                        AppMetadataResolver.resolveApp(context, liveState.player)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Artwork 80.dp, 16.dp corner radius, subtle elevation, NO neon borders
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .graphicsLayer {
                                    scaleX = coverScale
                                    scaleY = coverScale
                                }
                                .shadow(elevation = 16.dp, shape = RoundedCornerShape(16.dp), clip = false)
                                .clip(RoundedCornerShape(16.dp))
                                .background(livingPalette.cardTint),
                            contentAlignment = Alignment.Center
                        ) {
                            if (imageBitmap != null) {
                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = "Обложка трека",
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
                                                    livingPalette.dot1.copy(alpha = 0.25f)
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Album,
                                        contentDescription = "Обложка отсутствует",
                                        tint = playerAccent,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = liveState.title.ifEmpty { "Без названия" },
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = liveState.artist.ifEmpty { "Неизвестный артист" },
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (liveState.album.isNotBlank()) {
                                Text(
                                    text = liveState.album,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = TextMuted,
                                        fontSize = 13.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(3.dp))

                            // Frosted glass source chip with real PackageManager icon & friendly player name
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = NestedSurface,
                                border = BorderStroke(0.5.dp, SpecularStroke)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (resolvedPlayer.icon != null) {
                                        Image(
                                            bitmap = resolvedPlayer.icon,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(15.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.MusicNote,
                                            contentDescription = null,
                                            tint = playerAccent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Text(
                                        text = resolvedPlayer.displayName,
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
                    }

                    val progressFraction = if (liveState.durationMs > 0L) {
                        (displayPositionMs.toFloat() / liveState.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        WavyPlaybackProgressBar(
                            fraction = progressFraction,
                            isPlaying = isPlaying,
                            activeColor = playerAccent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTimecode(displayPositionMs),
                                style = MaterialTheme.typography.titleMedium.withTabularNumbers().copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = playerAccent
                                )
                            )
                            Text(
                                text = formatTimecode(liveState.durationMs),
                                style = MaterialTheme.typography.bodySmall.withTabularNumbers().copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = TextMuted
                                )
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(NestedSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = "Музыка не играет",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            text = "Включите трек в SoundCloud, Spotify, Яндекс Музыке или запустите симулятор ниже",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MiniEqualizerBars(
    isPlaying: Boolean,
    color: Color,
    timeSecondsState: State<Float>,
    modifier: Modifier = Modifier
) {
    val activeFactorState = remember { mutableFloatStateOf(if (isPlaying) 1f else 0f) }
    LaunchedEffect(isPlaying) {
        val start = activeFactorState.floatValue
        val target = if (isPlaying) 1f else 0f
        if (abs(start - target) < 0.001f) {
            activeFactorState.floatValue = target
            return@LaunchedEffect
        }
        val startMs = SystemClock.uptimeMillis()
        while (isActive) {
            var done = false
            withFrameNanos {
                val rawT = ((SystemClock.uptimeMillis() - startMs).coerceAtLeast(0L) / 280f).coerceIn(0f, 1f)
                val eased = FastOutSlowInEasing.transform(rawT)
                activeFactorState.floatValue = start + (target - start) * eased
                if (rawT >= 1f) done = true
            }
            if (done) break
        }
        activeFactorState.floatValue = target
    }

    Canvas(modifier = modifier) {
        val t = timeSecondsState.value
        val activeFactor = activeFactorState.floatValue
        val w = size.width
        val h = size.height
        val barWidth = w / 5f
        val bar1 = 0.25f + 0.70f * ((sin(t * 7.2f) + 1f) * 0.5f)
        val bar2 = 0.25f + 0.70f * ((cos(t * 5.9f + 1.3f) + 1f) * 0.5f)
        val bar3 = 0.25f + 0.70f * ((sin(t * 8.4f + 2.6f) + 1f) * 0.5f)
        val heights = listOf(bar1, bar2, bar3).map { dynamic ->
            val rest = 0.24f
            h * (rest + (dynamic - rest) * activeFactor)
        }
        heights.forEachIndexed { index, barH ->
            val x = index * (barWidth * 1.8f)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, h - barH),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
internal fun WavyPlaybackProgressBar(
    fraction: Float,
    isPlaying: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val ampState = remember { mutableFloatStateOf(if (isPlaying) 1f else 0f) }
    LaunchedEffect(isPlaying) {
        val start = ampState.floatValue
        val target = if (isPlaying) 1f else 0f
        if (abs(start - target) < 0.001f) {
            ampState.floatValue = target
            return@LaunchedEffect
        }
        val startMs = SystemClock.uptimeMillis()
        while (isActive) {
            var done = false
            withFrameNanos {
                val rawT = ((SystemClock.uptimeMillis() - startMs).coerceAtLeast(0L) / 360f).coerceIn(0f, 1f)
                val eased = FastOutSlowInEasing.transform(rawT)
                ampState.floatValue = start + (target - start) * eased
                if (rawT >= 1f) done = true
            }
            if (done) break
        }
        ampState.floatValue = target
    }

    val wavePhaseState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastUptimeMs = SystemClock.uptimeMillis()
        while (isActive) {
            withFrameNanos {
                val nowMs = SystemClock.uptimeMillis()
                val dt = ((nowMs - lastUptimeMs).coerceIn(0L, 100L)) / 1000f
                lastUptimeMs = nowMs
                if (dt > 0f) {
                    wavePhaseState.floatValue = (wavePhaseState.floatValue + dt * 4.5f) % (2f * PI.toFloat())
                }
            }
        }
    }

    Canvas(modifier = modifier) {
        val amplitudeMultiplier = ampState.floatValue
        val wavePhase = wavePhaseState.floatValue
        val cy = size.height / 2f
        val width = size.width
        val clampedFraction = fraction.coerceIn(0f, 1f)
        val splitX = (width * clampedFraction).coerceIn(0f, width)
        val strokePx = 3.5.dp.toPx()

        // Remaining inactive track
        drawLine(
            color = Color.White.copy(alpha = 0.14f),
            start = Offset(splitX, cy),
            end = Offset(width, cy),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )

        if (splitX > 2f) {
            val minWaveLenPx = 20.dp.toPx()
            val endFadePx = 10.dp.toPx()
            val waveAmp = 2.6.dp.toPx() * amplitudeMultiplier
            val wavelengthPx = 26.dp.toPx()
            val stepPx = 3f
            val path = Path()
            var x = 0f
            while (x <= splitX) {
                val env = waveEnvelopeFactor(
                    x = x,
                    splitX = splitX,
                    minWaveLenPx = minWaveLenPx,
                    endFadePx = endFadePx
                )
                val y = cy + sin((x / wavelengthPx) * 2f * PI.toFloat() - wavePhase) * waveAmp * env
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += stepPx
            }
            path.lineTo(splitX, cy)

            drawPath(
                path = path,
                color = activeColor,
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Playhead thumb dot
        drawCircle(
            color = activeColor,
            radius = 5.dp.toPx(),
            center = Offset(splitX, cy)
        )
    }
}

@Composable
private fun SimulatorCard(
    isActive: Boolean,
    isPlaying: Boolean,
    onToggle: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit
) {
    FrostedGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("simulator_card"),
        containerColor = CardSurfaceDense,
        cornerRadius = 24.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = "Тестовый симулятор плеера",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "Плейлист из 3 треков с обложкой, лирикой и SSE",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                    )
                }

                ExpressiveGlassSwitch(
                    checked = isActive,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("simulator_toggle_switch")
                )
            }

            // Connected Button Group in frosted graphite glass
            AnimatedVisibility(visible = isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    GraphiteGlassButton(
                        onClick = onPlayPause,
                        shapeOverride = RoundedCornerShape(
                            topStart = 24.dp,
                            bottomStart = 24.dp,
                            topEnd = 8.dp,
                            bottomEnd = 8.dp
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("simulator_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isPlaying) "Пауза" else "Играть",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    GraphiteGlassButton(
                        onClick = onSeekForward,
                        shapeOverride = RoundedCornerShape(
                            topStart = 8.dp,
                            bottomStart = 8.dp,
                            topEnd = 24.dp,
                            bottomEnd = 24.dp
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("simulator_seek_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "+15 сек",
                            style = MaterialTheme.typography.labelLarge.withTabularNumbers()
                        )
                    }
                }
            }
        }
    }
}

fun formatTimecode(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hundredths = (safeMs % 1000) / 10
    return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths)
}

internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val span = edge1 - edge0
    if (kotlin.math.abs(span) < 1e-5f) {
        return if (x >= edge1) 1f else 0f
    }
    val t = ((x - edge0) / span).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun waveEnvelopeFactor(
    x: Float,
    splitX: Float,
    minWaveLenPx: Float,
    endFadePx: Float
): Float {
    if (splitX <= 0f) return 0f
    val overallFade = smoothstep(minWaveLenPx * 0.2f, minWaveLenPx, splitX)
    val startTaper = smoothstep(0f, minWaveLenPx, x)
    val endTaper = smoothstep(splitX, (splitX - endFadePx).coerceAtLeast(0f), x)
    return overallFade * startTaper * endTaper
}

