package com.example.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.NowPlayingData
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralRed
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceHighlight
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LivingPaletteState
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.heroRotatingBorderBrush
import com.example.ui.theme.rotatingBorderBrush
import com.example.ui.theme.shimmeringHorizontalBrush
import java.util.Locale

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
            livingPalette = livingPalette,
            isServiceConnected = isServiceConnected,
            isNotificationAccessGranted = isNotificationAccessGranted,
            onForceRebind = onForceRebind,
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        HeroLiveMonitorCard(
            liveState = liveState,
            artworkBitmap = artworkBitmap,
            livingPalette = livingPalette,
            isSimulatorActive = isSimulatorActive
        )

        SimulatorCard(
            livingPalette = livingPalette,
            isActive = isSimulatorActive,
            isPlaying = isSimulatorPlaying,
            onToggle = onToggleSimulator,
            onPlayPause = onPlayPauseSimulator,
            onSeekForward = onSeekForwardSimulator
        )
    }
}

@Composable
fun ServiceStatusBanner(
    livingPalette: LivingPaletteState,
    isServiceConnected: Boolean,
    isNotificationAccessGranted: Boolean,
    onForceRebind: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val (statusText, subText, borderColor, bgColor, icon) = when {
        isNotificationAccessGranted && isServiceConnected -> {
            StatusBannerState(
                status = "Служба активна",
                subText = "IPC подключён • перехват и SSE работают в фоне",
                color = NeonEmerald,
                bg = Color(0xFF0C2418).copy(alpha = 0.80f),
                icon = Icons.Default.CheckCircle
            )
        }
        isNotificationAccessGranted && !isServiceConnected -> {
            StatusBannerState(
                status = "Восстановление связки...",
                subText = "Авто-Rebind в процессе (нажмите для ручного сброса)",
                color = AmberWarning,
                bg = Color(0xFF28210C).copy(alpha = 0.84f),
                icon = Icons.Default.Refresh
            )
        }
        else -> {
            StatusBannerState(
                status = "Нужен доступ к уведомлениям",
                subText = "Нажмите для включения чтения медиа-сессий",
                color = CoralRed,
                bg = Color(0xFF2A1010).copy(alpha = 0.86f),
                icon = Icons.Default.Error
            )
        }
    }

    val bannerBorder = if (isNotificationAccessGranted && isServiceConnected && livingPalette.isTrackActive) {
        BorderStroke(1.dp, rotatingBorderBrush(livingPalette, alpha = 0.48f, angleOffsetDeg = 45f))
    } else {
        BorderStroke(1.dp, borderColor.copy(alpha = 0.55f))
    }

    Card(
        onClick = {
            if (!isNotificationAccessGranted) onOpenSettings()
            else if (!isServiceConnected) onForceRebind()
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("service_status_banner"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = bannerBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(borderColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = borderColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = borderColor
                    )
                )
                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
            }

            if (isNotificationAccessGranted && !isServiceConnected) {
                IconButton(
                    onClick = onForceRebind,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Сбросить и переподключить",
                        tint = AmberWarning
                    )
                }
            }
        }
    }
}

private data class StatusBannerState(
    val status: String,
    val subText: String,
    val color: Color,
    val bg: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
private fun HeroLiveMonitorCard(
    liveState: NowPlayingData,
    artworkBitmap: Bitmap?,
    livingPalette: LivingPaletteState,
    isSimulatorActive: Boolean
) {
    val accentColor = livingPalette.primary
    val accentColor2 = livingPalette.secondary
    val accentColor3 = livingPalette.tertiary
    val isPlaying = liveState.hasMedia && liveState.isPlaying

    val imageBitmap = remember(artworkBitmap) {
        artworkBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_monitor_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.80f)),
        border = BorderStroke(
            width = if (isPlaying) 2.dp else 1.2.dp,
            brush = heroRotatingBorderBrush(livingPalette)
        )
    ) {
        val washAlpha = if (isPlaying) 0.16f + 0.06f * livingPalette.auraPulse else 0.05f
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(rotatingBorderBrush(livingPalette, alpha = washAlpha, angleOffsetDeg = 90f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isPlaying) accentColor else TextMuted)
                        )
                        Text(
                            text = "LIVE MONITOR • 250 ms",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (isPlaying) accentColor else TextMuted,
                                letterSpacing = 1.sp
                            )
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isPlaying) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(accentColor, accentColor2, accentColor3).forEach { dotColor ->
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                    )
                                }
                            }
                        }

                        if (isPlaying && liveState.playbackSpeed != 1.0f) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = accentColor.copy(alpha = 0.20f)
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.2fx", liveState.playbackSpeed),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = accentColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        if (isSimulatorActive) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = ElectricViolet.copy(alpha = 0.22f)
                            ) {
                                Text(
                                    text = "SIMULATOR",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = ElectricViolet,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                if (isPlaying) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Artwork box wrapped in radial ambient halo + rotating multi-color border
                        Box(
                            modifier = Modifier.size(96.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                accentColor.copy(alpha = 0.40f + 0.14f * livingPalette.auraPulse),
                                                accentColor2.copy(alpha = 0.18f),
                                                Color.Transparent
                                            )
                                        )
                                    )
                            )

                            Surface(
                                shape = RoundedCornerShape(15.dp),
                                color = DarkSurfaceVariant,
                                border = BorderStroke(
                                    width = 2.dp,
                                    brush = heroRotatingBorderBrush(livingPalette)
                                ),
                                modifier = Modifier.size(86.dp)
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
                                            .background(rotatingBorderBrush(livingPalette, alpha = 0.30f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Album,
                                            contentDescription = "Обложка отсутствует",
                                            tint = TextPrimary.copy(alpha = 0.85f),
                                            modifier = Modifier.size(38.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = liveState.title.ifEmpty { "Без названия" },
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = liveState.artist.ifEmpty { "Неизвестный артист" },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (liveState.album.isNotBlank()) {
                                Text(
                                    text = liveState.album,
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = DarkSurfaceVariant.copy(alpha = 0.85f),
                                border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = liveState.player.ifEmpty { "com.android.music" },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = accentColor
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    val progressFraction = if (liveState.durationMs > 0) {
                        (liveState.positionMs.toFloat() / liveState.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = formatTimecode(liveState.positionMs),
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor
                                )
                            )
                            Text(
                                text = formatTimecode(liveState.durationMs),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = TextSecondary
                                )
                            )
                        }

                        // Living gradient progress bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(DarkSurfaceHighlight.copy(alpha = 0.72f))
                        ) {
                            if (progressFraction > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(progressFraction)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(shimmeringHorizontalBrush(livingPalette, alpha = 1f))
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = accentColor.copy(alpha = 0.75f),
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = "Музыка не играет",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Text(
                            text = "Включите трек в SoundCloud, Spotify, VK, Яндекс Музыке или запустите симулятор ниже",
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
private fun SimulatorCard(
    livingPalette: LivingPaletteState,
    isActive: Boolean,
    isPlaying: Boolean,
    onToggle: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit
) {
    val toggleTrack = livingPalette.primary
    val toggleThumb = if (toggleTrack.luminance() > 0.42f) Color.Black else Color.White

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("simulator_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.78f)),
        border = BorderStroke(
            1.dp,
            rotatingBorderBrush(livingPalette, alpha = if (isActive) 0.72f else 0.30f, angleOffsetDeg = 180f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Тестовый симулятор плеера",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "Juice WRLD — Lean Wit Me (с обложкой и SSE)",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )
                }

                Switch(
                    checked = isActive,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = toggleThumb,
                        checkedTrackColor = toggleTrack
                    ),
                    modifier = Modifier.testTag("simulator_toggle_switch")
                )
            }

            AnimatedVisibility(visible = isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val primaryBtnColor = if (isPlaying) CoralRed else livingPalette.primary
                    val primaryBtnContent = if (primaryBtnColor.luminance() > 0.42f) Color.Black else Color.White
                    Button(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("simulator_play_pause_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryBtnColor,
                            contentColor = primaryBtnContent
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = if (isPlaying) "Пауза" else "Играть")
                    }

                    OutlinedButton(
                        onClick = onSeekForward,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("simulator_seek_button"),
                        border = BorderStroke(1.dp, livingPalette.secondary.copy(alpha = 0.55f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = null,
                            tint = livingPalette.secondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "+15 сек", color = livingPalette.secondary)
                    }
                }
            }
        }
    }
}

fun parseHexColorOrDefault(hex: String, fallback: Color): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        fallback
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
