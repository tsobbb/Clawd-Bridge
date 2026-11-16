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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SecurityUpdateGood
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.network.HostCooldownGuard
import com.example.network.NetworkCategory
import com.example.state.BackgroundStyle
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
import com.example.ui.theme.SheetScrim
import com.example.ui.theme.SheetSurface
import com.example.ui.theme.SpecularStroke
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.withTabularNumbers
import com.example.updater.BridgeAppUpdater
import com.example.updater.BridgeUpdateState
import com.example.updater.RemoteApkRelease
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemAndServiceSection(
    context: Context,
    livingPalette: LivingPaletteState,
    isNotificationAccessGranted: Boolean,
    isBatteryOptIgnored: Boolean,
    dynamicBackgroundEnabled: Boolean,
    onToggleDynamicBackground: (Boolean) -> Unit,
    backgroundStyle: BackgroundStyle,
    onBackgroundStyleChange: (BackgroundStyle) -> Unit,
    onForceRebind: () -> Unit,
    openUpdateSheetRequested: Boolean = false,
    onConsumeOpenUpdateSheet: () -> Unit = {}
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

                // 3. Background Style Selector (4 styles) + Motion Toggle
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Стиль фона",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = TextMuted,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier.padding(horizontal = 18.dp)
                    )

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BackgroundStyle.entries.forEach { styleOption ->
                            val selected = backgroundStyle == styleOption
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onBackgroundStyleChange(styleOption)
                                },
                                label = {
                                    Text(
                                        text = styleOption.label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                        )
                                    )
                                },
                                shape = RoundedCornerShape(999.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = NestedSurface,
                                    labelColor = TextSecondary,
                                    selectedContainerColor = MintCta.copy(alpha = 0.18f),
                                    selectedLabelColor = MintCta
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = SpecularStroke,
                                    selectedBorderColor = MintCta.copy(alpha = 0.42f)
                                ),
                                modifier = Modifier.testTag("bg_style_chip_${styleOption.id}")
                            )
                        }
                    }
                }

                // 4. Dynamic Background Motion Toggle ("Динамический фон")
                SystemSettingRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "Движение фона",
                    subtitle = when {
                        backgroundStyle == BackgroundStyle.PURE_BLACK -> "Отключено в режиме «Чистый чёрный»"
                        dynamicBackgroundEnabled -> "Плавное перетекание цветового фона"
                        else -> "Статичный фоновый градиент (экономия)"
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
                    updateState = updateState,
                    openUpdateSheetRequested = openUpdateSheetRequested,
                    onConsumeOpenUpdateSheet = onConsumeOpenUpdateSheet
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeUpdaterBlock(
    context: Context,
    livingPalette: LivingPaletteState,
    updateState: BridgeUpdateState,
    openUpdateSheetRequested: Boolean,
    onConsumeOpenUpdateSheet: () -> Unit
) {
    var showUpdateSheet by remember { mutableStateOf(false) }
    var lastKnownRelease by remember { mutableStateOf<RemoteApkRelease?>(null) }

    LaunchedEffect(updateState) {
        when (updateState) {
            is BridgeUpdateState.UpdateAvailable -> lastKnownRelease = updateState.release
            is BridgeUpdateState.Downloading -> lastKnownRelease = updateState.release
            is BridgeUpdateState.NeedsInstallPermission -> lastKnownRelease = updateState.release
            else -> Unit
        }
    }

    LaunchedEffect(openUpdateSheetRequested) {
        if (openUpdateSheetRequested) {
            showUpdateSheet = true
            onConsumeOpenUpdateSheet()
            if (updateState is BridgeUpdateState.Idle ||
                updateState is BridgeUpdateState.UpToDate ||
                updateState is BridgeUpdateState.Error
            ) {
                BridgeAppUpdater.checkForUpdates(context)
            }
        }
    }

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
                    text = "Версия v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • Авто раз в 10 мин",
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
                        timeSecondsState = livingPalette.uiTimeSecondsState,
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
                    "Доступна ${release.versionName} (${release.sourceLabel}$sizeSuffix)"
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = NestedSurface,
                        border = BorderStroke(0.5.dp, SpecularStroke),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showUpdateSheet = true }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                    color = SemanticEmerald,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                            val previewLine = release.changelogItems.firstOrNull()
                                ?: release.commitMessage.lineSequence().firstOrNull().orEmpty()
                            if (previewLine.isNotBlank()) {
                                Text(
                                    text = previewLine,
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (release.commitShaShort.isNotBlank()) {
                                val dateStr = BridgeAppUpdater.formatCommitDateLabel(release.commitDateIso)
                                val commitSummary = listOfNotNull(
                                    "#${release.commitShaShort}",
                                    dateStr.takeIf { it.isNotBlank() },
                                    release.commitAuthor.takeIf { it.isNotBlank() }
                                ).joinToString(" • ")
                                Text(
                                    text = commitSummary,
                                    style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = TextMuted
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GraphiteGlassButton(
                            onClick = { showUpdateSheet = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_update_details_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Подробнее",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }

                        MintCtaButton(
                            onClick = { BridgeAppUpdater.downloadAndInstall(context, release) },
                            modifier = Modifier
                                .weight(1.3f)
                                .testTag("install_bridge_update_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Обновить",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = updateState is BridgeUpdateState.Downloading) {
            val dl = updateState as? BridgeUpdateState.Downloading
            if (dl != null) {
                BridgeDownloadProgressBlock(dl)
            }
        }

        AnimatedVisibility(visible = updateState is BridgeUpdateState.NeedsInstallPermission) {
            BridgeInstallPermissionPrompt(context = context)
        }

        AnimatedVisibility(visible = updateState is BridgeUpdateState.Installing) {
            val inst = updateState as? BridgeUpdateState.Installing
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WaveLoadingDots(
                    color = MintCta,
                    timeSecondsState = livingPalette.uiTimeSecondsState,
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

        val apiCooldownMsg = remember(updateState) {
            if (updateState is BridgeUpdateState.Error) {
                null
            } else {
                BridgeAppUpdater.formatGitHubRateLimitMessage(
                    rateLimit = HostCooldownGuard.getLastGitHubRateLimit(),
                    cooldown = HostCooldownGuard.checkCooldown(NetworkCategory.GITHUB_API.cooldownBucket)
                )
            }
        }
        if (apiCooldownMsg != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SemanticAmber.copy(alpha = 0.12f),
                border = BorderStroke(0.5.dp, SemanticAmber.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = apiCooldownMsg,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelSmall.copy(color = SemanticAmber)
                )
            }
        }
    }

    if (showUpdateSheet) {
        BridgeUpdateDetailsSheet(
            context = context,
            livingPalette = livingPalette,
            updateState = updateState,
            release = when (updateState) {
                is BridgeUpdateState.UpdateAvailable -> updateState.release
                is BridgeUpdateState.Downloading -> updateState.release
                is BridgeUpdateState.NeedsInstallPermission -> updateState.release
                else -> lastKnownRelease
            },
            onDismiss = { showUpdateSheet = false }
        )
    }
}

@Composable
private fun BridgeDownloadProgressBlock(dl: BridgeUpdateState.Downloading) {
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

@Composable
private fun BridgeInstallPermissionPrompt(context: Context) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeUpdateDetailsSheet(
    context: Context,
    livingPalette: LivingPaletteState,
    updateState: BridgeUpdateState,
    release: RemoteApkRelease?,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Обновление Clawd Bridge",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = release?.sourceLabel ?: "GitHub tsobbb/Clawd-Bridge",
                        style = MaterialTheme.typography.labelMedium.copy(color = TextMuted)
                    )
                }

                if (release != null && release.sizeBytes > 0L) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = NestedSurface,
                        border = BorderStroke(0.5.dp, SpecularStroke)
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.2f МБ", release.sizeBytes / (1024.0 * 1024.0)),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers().copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MintCta,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }

            if (release != null) {
                val versionBadge = if (release.isRebuildWithSameVersion) {
                    "Пересборка v${BuildConfig.VERSION_NAME} • ${release.apkName}"
                } else {
                    "v${BuildConfig.VERSION_NAME} → ${release.versionName}"
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SemanticEmerald.copy(alpha = 0.14f),
                    border = BorderStroke(0.5.dp, SemanticEmerald.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = versionBadge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers().copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = SemanticEmerald,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                if (release.commitShaShort.isNotBlank() || release.commitMessage.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = NestedSurface,
                        border = BorderStroke(0.5.dp, SpecularStroke),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = MintCta,
                                    modifier = Modifier.size(16.dp)
                                )
                                val dateStr = BridgeAppUpdater.formatCommitDateLabel(release.commitDateIso)
                                val metaLine = listOfNotNull(
                                    release.commitShaShort.takeIf { it.isNotBlank() }?.let { "#$it" },
                                    dateStr.takeIf { it.isNotBlank() },
                                    release.commitAuthor.takeIf { it.isNotBlank() }
                                ).joinToString(" • ")
                                Text(
                                    text = metaLine.ifBlank { "Коммит GitHub" },
                                    style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = TextMuted
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (release.commitMessage.isNotBlank()) {
                                Text(
                                    text = release.commitMessage,
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Что нового",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    val items = release.changelogItems.ifEmpty {
                        listOf("Свежая сборка ${release.apkName} готова к установке в 1 нажатие.")
                    }
                    items.forEach { item ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MintCta)
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    WaveLoadingDots(
                        color = MintCta,
                        timeSecondsState = livingPalette.uiTimeSecondsState,
                        modifier = Modifier.size(width = 24.dp, height = 14.dp)
                    )
                    Text(
                        text = when (updateState) {
                            is BridgeUpdateState.UpToDate -> "Установлена актуальная версия v${updateState.checkedAtVersion}."
                            is BridgeUpdateState.Error -> updateState.message
                            else -> "Загружаем сведения о сборке с GitHub..."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )
                }
            }

            when (updateState) {
                is BridgeUpdateState.Downloading -> {
                    BridgeDownloadProgressBlock(updateState)
                }
                is BridgeUpdateState.NeedsInstallPermission -> {
                    BridgeInstallPermissionPrompt(context = context)
                }
                is BridgeUpdateState.Installing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        WaveLoadingDots(
                            color = MintCta,
                            timeSecondsState = livingPalette.uiTimeSecondsState,
                            modifier = Modifier.size(width = 22.dp, height = 14.dp)
                        )
                        Text(
                            text = updateState.statusText,
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }
                }
                is BridgeUpdateState.Error -> {
                    Text(
                        text = updateState.message,
                        style = MaterialTheme.typography.bodySmall.copy(color = SemanticCoral)
                    )
                }
                else -> Unit
            }

            if (release != null &&
                updateState !is BridgeUpdateState.Downloading &&
                updateState !is BridgeUpdateState.Installing &&
                updateState !is BridgeUpdateState.NeedsInstallPermission
            ) {
                MintCtaButton(
                    onClick = { BridgeAppUpdater.downloadAndInstall(context, release) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sheet_install_bridge_update_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Скачать и установить",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
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
