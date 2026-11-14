package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SecurityUpdateGood
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.ui.theme.CardSurfaceDense
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LivingPaletteState
import com.example.ui.theme.MintCta
import com.example.ui.theme.MintCtaContent
import com.example.ui.theme.NestedSurface
import com.example.ui.theme.SecondaryGlass
import com.example.ui.theme.SecondaryGlassText
import com.example.ui.theme.SemanticAmber
import com.example.ui.theme.SemanticCoral
import com.example.ui.theme.SemanticEmerald
import com.example.ui.theme.SpecularStroke
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.withTabularNumbers
import com.example.updater.BridgeAppUpdater
import com.example.updater.BridgeUpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupStorageCard(
    context: Context,
    targetPath: String,
    lastWrittenTime: Long,
    fileSize: Int
) {
    val haptic = LocalHapticFeedback.current
    val fillFraction = (fileSize.toFloat() / 512f).coerceIn(0f, 1f)

    FrostedGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backup_storage_card"),
        containerColor = CardSurfaceDense,
        cornerRadius = 24.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(19.dp)
                    )
                    Text(
                        text = "Резервный файл",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    )
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = NestedSurface
                ) {
                    Text(
                        text = "Троттлинг 2 сек",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(color = TextMuted)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = NestedSurface,
                border = BorderStroke(0.5.dp, SpecularStroke),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Backup Path", targetPath))
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        Toast.makeText(context, "Путь скопирован", Toast.LENGTH_SHORT).show()
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = targetPath,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = TextSecondary
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Копировать путь",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { fillFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (fillFraction > 0.9f) SemanticAmber else MintCta,
                    trackColor = NestedSurface,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Размер: ${if (fileSize > 0) "$fileSize / 512 байт" else "ожидание"}",
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                            color = TextSecondary,
                            fontFamily = JetBrainsMonoFontFamily
                        )
                    )

                    Text(
                        text = if (lastWrittenTime > 0) {
                            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            "Обновлено: ${sdf.format(Date(lastWrittenTime))}"
                        } else {
                            "Не записывался"
                        },
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(color = TextMuted)
                    )
                }
            }
        }
    }
}

@Composable
fun SystemAndServiceSection(
    context: Context,
    livingPalette: LivingPaletteState,
    isNotificationAccessGranted: Boolean,
    isBatteryOptIgnored: Boolean,
    dynamicBackgroundEnabled: Boolean,
    onToggleDynamicBackground: (Boolean) -> Unit,
    onForceRebind: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val updateState by BridgeAppUpdater.state.collectAsStateWithLifecycle()
    var syncRotationTarget by remember { mutableFloatStateOf(0f) }
    val syncRotation by animateFloatAsState(
        targetValue = syncRotationTarget,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "rebind_icon_spin"
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FrostedGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("system_and_service_card"),
            containerColor = CardSurfaceDense,
            cornerRadius = 24.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = "Система и служба",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    ),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )

                // 1. Notification listener access
                SystemSettingRow(
                    icon = Icons.Default.Notifications,
                    title = "Доступ к уведомлениям",
                    subtitle = if (isNotificationAccessGranted) {
                        "Разрешение активно"
                    } else {
                        "Необходимо для перехвата треков"
                    },
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    modifier = Modifier.testTag("notification_permission_button"),
                    trailing = {
                        if (isNotificationAccessGranted) {
                            SemanticStatusPill(text = "Включен", color = SemanticEmerald)
                        } else {
                            CompactMintPillButton(
                                text = "Включить",
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }
                            )
                        }
                    }
                )

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = HairlineDivider,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )

                // 2. Battery optimization
                SystemSettingRow(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Оптимизация батареи",
                    subtitle = if (isBatteryOptIgnored) {
                        "Без ограничений в фоне"
                    } else {
                        "Может выгружать мост в фоне"
                    },
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    modifier = Modifier.testTag("battery_optimization_button"),
                    trailing = {
                        if (isBatteryOptIgnored) {
                            SemanticStatusPill(text = "Отключена", color = SemanticEmerald)
                        } else {
                            CompactGraphitePillButton(
                                text = "Настроить",
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                    }
                                }
                            )
                        }
                    }
                )

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = HairlineDivider,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )

                // 3. Dynamic Background Toggle ("Динамический фон")
                SystemSettingRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "Динамический фон",
                    subtitle = if (dynamicBackgroundEnabled) {
                        "Плавное движение цветового фона"
                    } else {
                        "Статичный фоновый градиент (экономия)"
                    },
                    onClick = { onToggleDynamicBackground(!dynamicBackgroundEnabled) },
                    modifier = Modifier.testTag("dynamic_background_row"),
                    trailing = {
                        ExpressiveGlassSwitch(
                            checked = dynamicBackgroundEnabled,
                            onCheckedChange = onToggleDynamicBackground,
                            modifier = Modifier.testTag("dynamic_background_switch")
                        )
                    }
                )

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = HairlineDivider,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )

                // 4. Hybrid In-App APK Updater (github.com/tsobbb/Clawd-Bridge)
                BridgeUpdaterBlock(
                    context = context,
                    livingPalette = livingPalette,
                    updateState = updateState
                )
            }
        }

        if (isNotificationAccessGranted) {
            GraphiteGlassButton(
                onClick = {
                    syncRotationTarget += 360f
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onForceRebind()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("force_rebind_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier
                        .size(19.dp)
                        .graphicsLayer { rotationZ = syncRotation }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Сбросить и переподключить службу",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun BridgeUpdaterBlock(
    context: Context,
    livingPalette: LivingPaletteState,
    updateState: BridgeUpdateState
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NestedSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = MintCta,
                    modifier = Modifier.size(19.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Обновление Clawd Bridge",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                )
                Text(
                    text = "Версия v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • GitHub",
                    style = MaterialTheme.typography.bodySmall.withTabularNumbers().copy(color = TextMuted)
                )
            }

            when (updateState) {
                is BridgeUpdateState.Idle -> {
                    CompactGraphitePillButton(
                        text = "Проверить",
                        onClick = { BridgeAppUpdater.checkForUpdates(context) },
                        modifier = Modifier.testTag("check_bridge_update_button")
                    )
                }
                is BridgeUpdateState.Checking -> {
                    WaveLoadingDots(
                        color = MintCta,
                        phase = livingPalette.phase,
                        modifier = Modifier.size(width = 24.dp, height = 14.dp)
                    )
                }
                is BridgeUpdateState.UpToDate -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SemanticStatusPill(text = "Актуально", color = SemanticEmerald)
                        IconButton(
                            onClick = { BridgeAppUpdater.checkForUpdates(context) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Проверить снова",
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                is BridgeUpdateState.Installed -> {
                    SemanticStatusPill(text = "Установлено", color = SemanticEmerald)
                }
                else -> Unit
            }
        }

        AnimatedVisibility(visible = updateState is BridgeUpdateState.UpdateAvailable) {
            val avail = updateState as? BridgeUpdateState.UpdateAvailable
            avail?.release?.let { release ->
                val exactMb = if (release.sizeBytes > 0) {
                    String.format(Locale.US, "%.2f МБ", release.sizeBytes / (1024.0 * 1024.0))
                } else ""
                val statusText = if (release.isRebuildWithSameVersion) {
                    if (exactMb.isNotEmpty()) "Доступна новая сборка • $exactMb"
                    else "Доступна обновлённая сборка (${release.sourceLabel})"
                } else {
                    val sizeSuffix = if (exactMb.isNotEmpty()) " • $exactMb" else ""
                    "Найдена сборка ${release.versionName} (${release.sourceLabel}$sizeSuffix)"
                }
                val buttonText = if (release.isRebuildWithSameVersion) {
                    "Обновить"
                } else {
                    "Скачать и установить ${release.apkName}"
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                            color = SemanticEmerald
                        )
                    )
                    MintCtaButton(
                        onClick = { BridgeAppUpdater.downloadAndInstall(context, release) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("install_bridge_update_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = buttonText,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = updateState is BridgeUpdateState.Downloading) {
            val dl = updateState as? BridgeUpdateState.Downloading
            if (dl != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (dl.progress != null) {
                        LinearProgressIndicator(
                            progress = { dl.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MintCta,
                            trackColor = NestedSurface,
                            gapSize = 0.dp,
                            drawStopIndicator = {}
                        )
                        val mbDone = dl.downloadedBytes / (1024.0 * 1024.0)
                        val mbTotal = dl.totalBytes / (1024.0 * 1024.0)
                        Text(
                            text = String.format(
                                Locale.US,
                                "Загрузка APK: %d%% (%.1f / %.1f МБ)",
                                (dl.progress * 100).toInt(),
                                mbDone,
                                mbTotal
                            ),
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                color = TextSecondary,
                                fontFamily = JetBrainsMonoFontFamily
                            )
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MintCta,
                            trackColor = NestedSurface,
                            gapSize = 0.dp
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = updateState is BridgeUpdateState.NeedsInstallPermission) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Разрешите установку APK из этого источника в настройках Android, затем нажмите «Установить».",
                    style = MaterialTheme.typography.bodySmall.copy(color = SemanticAmber)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MintCtaButton(
                        onClick = { BridgeAppUpdater.openInstallPermissionSettings(context) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SecurityUpdateGood,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Разрешить", style = MaterialTheme.typography.labelMedium)
                    }
                    GraphiteGlassButton(
                        onClick = { BridgeAppUpdater.retryInstallIfPermitted(context) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Установить", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        AnimatedVisibility(visible = updateState is BridgeUpdateState.Installing) {
            val inst = updateState as? BridgeUpdateState.Installing
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WaveLoadingDots(
                    color = MintCta,
                    phase = livingPalette.phase,
                    modifier = Modifier.size(width = 22.dp, height = 14.dp)
                )
                Text(
                    text = inst?.statusText ?: "Установка...",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
            }
        }

        AnimatedVisibility(visible = updateState is BridgeUpdateState.Error) {
            val err = updateState as? BridgeUpdateState.Error
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = err?.message ?: "Ошибка обновления",
                    style = MaterialTheme.typography.bodySmall.copy(color = SemanticCoral),
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                CompactGraphitePillButton(
                    text = "Повтор",
                    onClick = { BridgeAppUpdater.checkForUpdates(context) }
                )
            }
        }
    }
}

@Composable
private fun SystemSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(NestedSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(19.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        trailing()
    }
}

@Composable
private fun SemanticStatusPill(
    text: String,
    color: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = color,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
}

@Composable
private fun CompactMintPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MintCta,
            contentColor = MintCtaContent
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = modifier.height(38.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun CompactGraphitePillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SecondaryGlass,
            contentColor = SecondaryGlassText
        ),
        border = BorderStroke(0.75.dp, SpecularStroke),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = modifier.height(38.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
        )
    }
}
