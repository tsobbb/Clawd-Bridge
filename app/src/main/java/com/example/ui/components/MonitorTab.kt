package com.example.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun MonitorTab(
    liveState: NowPlayingData,
    artworkBitmap: Bitmap?,
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
            isSimulatorActive = isSimulatorActive
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

@Composable
fun ServiceStatusBanner(
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
                bg = Color(0xFF0C2918),
                icon = Icons.Default.CheckCircle
            )
        }
        isNotificationAccessGranted && !isServiceConnected -> {
            StatusBannerState(
                status = "Восстановление связки...",
                subText = "Авто-Rebind в процессе (нажмите для ручного сброса)",
                color = AmberWarning,
                bg = Color(0xFF2C240C),
                icon = Icons.Default.Refresh
            )
        }
        else -> {
            StatusBannerState(
                status = "Нужен доступ к уведомлениям",
                subText = "Нажмите для включения чтения медиа-сессий",
                color = CoralRed,
                bg = Color(0xFF2C1010),
                icon = Icons.Default.Error
            )
        }
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
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.6f))
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
    isSimulatorActive: Boolean
) {
    val targetAccent = remember(liveState.accentColorHex, liveState.isPlaying) {
        if (!liveState.isPlaying) NeonEmerald else parseHexColorOrDefault(liveState.accentColorHex, ElectricCyan)
    }
    val accentColor by animateColorAsState(
        targetValue = targetAccent,
        animationSpec = tween(durationMillis = 450),
        label = "artwork_accent_color"
    )

    val imageBitmap = remember(artworkBitmap) {
        artworkBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_monitor_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(
            1.dp,
            if (liveState.isPlaying) accentColor.copy(alpha = 0.55f) else DarkSurfaceHighlight
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            if (liveState.isPlaying) accentColor.copy(alpha = 0.14f) else Color.Transparent,
                            Color.Transparent
                        )
                    )
                )
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
                                .background(if (liveState.isPlaying) accentColor else TextMuted)
                        )
                        Text(
                            text = "LIVE MONITOR • 250 ms",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (liveState.isPlaying) accentColor else TextMuted,
                                letterSpacing = 1.sp
                            )
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (liveState.isPlaying && liveState.playbackSpeed != 1.0f) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = accentColor.copy(alpha = 0.18f)
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
                                color = ElectricViolet.copy(alpha = 0.2f)
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

                HorizontalDivider(color = DarkSurfaceHighlight)

                if (liveState.hasMedia && liveState.isPlaying) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Album Artwork Box
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = DarkSurfaceVariant,
                            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                            modifier = Modifier.size(84.dp)
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
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Album,
                                        contentDescription = "Обложка отсутствует",
                                        tint = accentColor.copy(alpha = 0.7f),
                                        modifier = Modifier.size(38.dp)
                                    )
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

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = DarkSurfaceVariant
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

                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = accentColor,
                            trackColor = DarkSurfaceHighlight
                        )
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
                            tint = TextMuted,
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
    isActive: Boolean,
    isPlaying: Boolean,
    onToggle: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("simulator_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        border = BorderStroke(
            1.dp,
            if (isActive) ElectricViolet.copy(alpha = 0.5f) else DarkSurfaceHighlight
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
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = ElectricViolet
                    ),
                    modifier = Modifier.testTag("simulator_toggle_switch")
                )
            }

            AnimatedVisibility(visible = isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("simulator_play_pause_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlaying) CoralRed else NeonEmerald,
                            contentColor = Color.Black
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
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = null,
                            tint = ElectricCyan
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "+15 сек", color = ElectricCyan)
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
