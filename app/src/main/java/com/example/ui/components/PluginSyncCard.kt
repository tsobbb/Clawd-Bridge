package com.example.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.plugin.PluginDownloader
import com.example.plugin.RemotePluginFile
import com.example.plugin.TelegramClientApp
import com.example.ui.theme.CardSurfaceDense
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LivingPaletteState
import com.example.ui.theme.MintCta
import com.example.ui.theme.MintCtaContent
import com.example.ui.theme.NestedSurface
import com.example.ui.theme.SecondaryGlass
import com.example.ui.theme.SecondaryGlassText
import com.example.ui.theme.SemanticCoral
import com.example.ui.theme.SemanticEmerald
import com.example.ui.theme.SheetScrim
import com.example.ui.theme.SheetSurface
import com.example.ui.theme.SpecularStroke
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.withTabularNumbers
import com.example.util.AppMetadataResolver
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSyncCard(
    context: Context,
    livingPalette: LivingPaletteState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var sourceUrl by remember { mutableStateOf(PluginDownloader.getSavedSourceUrl(context)) }
    var githubToken by remember { mutableStateOf(PluginDownloader.getSavedToken(context)) }
    var convertPyToPlugin by remember { mutableStateOf(PluginDownloader.getConvertPyToPlugin(context)) }

    var showSourceSettingsSheet by remember { mutableStateOf(false) }
    var showTokenText by remember { mutableStateOf(false) }

    var isBusy by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var cachedFile by remember { mutableStateOf(PluginDownloader.getCachedPluginFile(context)) }
    var availableFiles by remember { mutableStateOf<List<RemotePluginFile>>(emptyList()) }
    var showFilePickerSheet by remember { mutableStateOf(false) }
    var telegramClientsForPicker by remember { mutableStateOf<List<TelegramClientApp>>(emptyList()) }
    var pendingShareFile by remember { mutableStateOf<File?>(null) }

    fun persistSettings(urlValue: String = sourceUrl, tokenValue: String = githubToken) {
        PluginDownloader.saveSourceUrl(context, urlValue)
        PluginDownloader.saveToken(context, tokenValue)
    }

    fun shareFileToTelegram(file: File) {
        val clients = PluginDownloader.findInstalledTelegramClients(context)
        when {
            clients.size == 1 -> {
                val intent = PluginDownloader.buildShareToTelegramIntent(
                    context = context,
                    file = file,
                    targetPackage = clients.first().packageName
                )
                runCatching { context.startActivity(intent) }
                    .onFailure {
                        val chooser = Intent.createChooser(
                            PluginDownloader.buildShareToTelegramIntent(context, file),
                            "Отправить плагин в Избранное Telegram"
                        )
                        context.startActivity(chooser)
                    }
            }
            clients.size > 1 -> {
                telegramClientsForPicker = clients
                pendingShareFile = file
            }
            else -> {
                val chooser = Intent.createChooser(
                    PluginDownloader.buildShareToTelegramIntent(context, file),
                    "Отправить плагин в Избранное Telegram"
                )
                context.startActivity(chooser)
            }
        }
    }

    fun downloadAndShare(selectedRemote: RemotePluginFile? = null) {
        if (isBusy) return
        persistSettings()
        scope.launch {
            isBusy = true
            isError = false
            downloadProgress = null
            try {
                val targetRemote = if (selectedRemote != null) {
                    selectedRemote
                } else {
                    statusMessage = "Поиск плагина на GitHub..."
                    val list = PluginDownloader.fetchAvailablePlugins(
                        sourceUrl = sourceUrl,
                        githubToken = githubToken
                    )
                    availableFiles = list
                    if (list.isEmpty()) {
                        throw IllegalStateException("Файлы плагина не найдены")
                    }
                    list.first()
                }

                statusMessage = "Загрузка ${targetRemote.name}..."
                val downloaded = PluginDownloader.downloadPluginToCache(
                    context = context,
                    remoteFile = targetRemote,
                    githubToken = githubToken,
                    convertPyToPlugin = convertPyToPlugin
                ) { progress ->
                    downloadProgress = progress
                }
                cachedFile = downloaded
                downloadProgress = 1f
                statusMessage = null // Avoid duplicating cached bar below
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                shareFileToTelegram(downloaded)
            } catch (e: Exception) {
                isError = true
                val msg = e.message ?: "не удалось скачать плагин"
                statusMessage = "Ошибка: $msg"
                if (msg.contains("404") || msg.contains("401") || msg.contains("403")) {
                    showSourceSettingsSheet = true
                }
            } finally {
                isBusy = false
            }
        }
    }

    fun loadFileListForSelection() {
        if (isBusy) return
        persistSettings()
        scope.launch {
            isBusy = true
            isError = false
            downloadProgress = null
            statusMessage = "Загрузка списка файлов..."
            try {
                val list = PluginDownloader.fetchAvailablePlugins(
                    sourceUrl = sourceUrl,
                    githubToken = githubToken
                )
                availableFiles = list
                if (list.isEmpty()) {
                    isError = true
                    statusMessage = "Файлы не найдены"
                } else {
                    statusMessage = null
                    showFilePickerSheet = true
                }
            } catch (e: Exception) {
                isError = true
                val msg = e.message ?: "не удалось получить список"
                statusMessage = "Ошибка: $msg"
                if (msg.contains("404") || msg.contains("401") || msg.contains("403")) {
                    showSourceSettingsSheet = true
                }
            } finally {
                isBusy = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FrostedGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("plugin_sync_card"),
            containerColor = CardSurfaceDense,
            cornerRadius = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header: Title + GitHub -> Telegram badge + two 38dp circular buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(NestedSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = null,
                                tint = MintCta,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "Плагин Clawd",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            )
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = NestedSurface,
                                border = BorderStroke(0.5.dp, SpecularStroke)
                            ) {
                                Text(
                                    text = "GitHub → Telegram",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = TextMuted
                                    )
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CircularToolbarIconButton(
                            onClick = { showSourceSettingsSheet = true },
                            icon = Icons.Default.Settings,
                            contentDescription = "Настройки URL и токена GitHub",
                            modifier = Modifier.testTag("toggle_plugin_source_settings_button")
                        )

                        CircularToolbarIconButton(
                            onClick = {
                                val target = sourceUrl.trim().ifEmpty { PluginDownloader.DEFAULT_SOURCE_URL }
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                runCatching { context.startActivity(intent) }
                            },
                            icon = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Открыть GitHub в браузере",
                            modifier = Modifier.testTag("open_github_repo_button")
                        )
                    }
                }

                // Monospace repo path chip
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = NestedSurface,
                    border = BorderStroke(0.5.dp, SpecularStroke),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = sourceUrl.removePrefix("https://github.com/"),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = SecondaryGlassText
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Toggle: convert .py -> .plugin
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = NestedSurface,
                    border = BorderStroke(0.5.dp, SpecularStroke),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                text = "Конвертировать .py → .plugin",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = "Для прямой установки в AprelGram / exteraGram",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                            )
                        }

                        ExpressiveGlassSwitch(
                            checked = convertPyToPlugin,
                            onCheckedChange = { enabled ->
                                convertPyToPlugin = enabled
                                PluginDownloader.setConvertPyToPlugin(context, enabled)
                            },
                            modifier = Modifier.testTag("convert_py_to_plugin_switch")
                        )
                    }
                }

                // Primary Mint Mist CTA (#A7F3D0 / #042016)
                MintCtaButton(
                    onClick = { downloadAndShare(null) },
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("download_and_share_plugin_button")
                ) {
                    if (isBusy) {
                        WaveLoadingDots(
                            color = MintCtaContent,
                            timeSecondsState = livingPalette.uiTimeSecondsState,
                            modifier = Modifier.size(width = 24.dp, height = 16.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val pct = downloadProgress?.let { " (${(it * 100).toInt()}%)" } ?: ""
                        Text(
                            text = "Скачивание$pct",
                            style = MaterialTheme.typography.labelLarge.withTabularNumbers().copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Скачать и отправить в Избранное",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }

                // Secondary Frosted Graphite Button
                GraphiteGlassButton(
                    onClick = { loadFileListForSelection() },
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("choose_plugin_file_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderZip,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Выбрать файл / версию на GitHub",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
                    )
                }

                AnimatedVisibility(visible = isBusy) {
                    val progress = downloadProgress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MintCta,
                            trackColor = NestedSurface,
                            gapSize = 0.dp,
                            drawStopIndicator = {}
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MintCta,
                            trackColor = NestedSurface,
                            gapSize = 0.dp
                        )
                    }
                }

                // Only show transient/error status if NOT duplicating the cached file card below
                val showStatusLine = statusMessage != null && (isError || cachedFile == null)
                AnimatedVisibility(visible = showStatusLine) {
                    statusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isError) SemanticCoral else SemanticEmerald,
                                fontFamily = JetBrainsMonoFontFamily
                            )
                        )
                    }
                }

                // Single deduplicated cached file row
                cachedFile?.takeIf { it.exists() }?.let { file ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = NestedSurface,
                        border = BorderStroke(0.5.dp, SpecularStroke),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = MintCta,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = TextPrimary,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "В кэше • ${formatBytes(file.length())}",
                                    style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                        color = TextMuted
                                    )
                                )
                            }

                            IconButton(
                                onClick = { shareFileToTelegram(file) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("reshare_cached_plugin_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Отправить плагин в Telegram",
                                    tint = MintCta,
                                    modifier = Modifier.size(19.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    PluginDownloader.clearPluginCache(context)
                                    cachedFile = null
                                    statusMessage = null
                                    isError = false
                                    Toast.makeText(context, "Кэш плагина очищен", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("clear_plugin_cache_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Удалить из кэша",
                                    tint = TextMuted,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quick Install Guide Card
        FrostedGlassCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = CardSurfaceDense,
            cornerRadius = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Быстрая установка",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                )
                QuickInstallStepRow(
                    number = "1",
                    text = "Нажмите «Скачать и отправить в Избранное»"
                )
                QuickInstallStepRow(
                    number = "2",
                    text = "Выберите AprelGram / exteraGram и отправьте файл себе"
                )
                QuickInstallStepRow(
                    number = "3",
                    text = "Откройте чат «Избранное» и тапните по .plugin для установки"
                )
            }
        }
    }

    // ModalBottomSheet: GitHub URL & Token Settings (Filled TextFields, zero neon)
    if (showSourceSettingsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var draftUrl by remember(showSourceSettingsSheet) { mutableStateOf(sourceUrl) }
        var draftToken by remember(showSourceSettingsSheet) { mutableStateOf(githubToken) }

        ModalBottomSheet(
            onDismissRequest = { showSourceSettingsSheet = false },
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
                    text = "Источник плагина GitHub",
                    style = MaterialTheme.typography.titleLarge.copy(color = TextPrimary)
                )

                TextField(
                    value = draftUrl,
                    onValueChange = { draftUrl = it },
                    label = { Text("Ссылка GitHub (папка / файл / raw)") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = MintCta
                        )
                    },
                    trailingIcon = {
                        if (draftUrl.isNotEmpty()) {
                            IconButton(onClick = { draftUrl = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Очистить ссылку",
                                    tint = TextMuted
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val pasted = cm.primaryClip?.getItemAt(0)?.text?.toString()
                                    if (!pasted.isNullOrBlank()) draftUrl = pasted
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Вставить из буфера",
                                    tint = TextMuted
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        color = TextPrimary
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = NestedSurface,
                        unfocusedContainerColor = NestedSurface,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedLabelColor = MintCta,
                        unfocusedLabelColor = TextMuted
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("plugin_source_url_input")
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextField(
                        value = draftToken,
                        onValueChange = { draftToken = it },
                        label = { Text("GitHub Token (опционально)") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = TextSecondary
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { showTokenText = !showTokenText },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = if (showTokenText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Показать или скрыть токен",
                                    tint = TextMuted
                                )
                            }
                        },
                        visualTransformation = if (showTokenText) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = TextPrimary
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = NestedSurface,
                            unfocusedContainerColor = NestedSurface,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedLabelColor = MintCta,
                            unfocusedLabelColor = TextMuted
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("plugin_github_token_input")
                    )

                    Text(
                        text = "Хранится локально. Для приватных репозиториев нужен read-only токен.",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GraphiteGlassButton(
                        onClick = {
                            draftUrl = PluginDownloader.DEFAULT_SOURCE_URL
                            sourceUrl = PluginDownloader.DEFAULT_SOURCE_URL
                            PluginDownloader.saveSourceUrl(context, draftUrl)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Сброс URL", style = MaterialTheme.typography.labelLarge)
                    }

                    MintCtaButton(
                        onClick = {
                            sourceUrl = draftUrl.trim().ifEmpty { PluginDownloader.DEFAULT_SOURCE_URL }
                            githubToken = draftToken.trim()
                            persistSettings(sourceUrl, githubToken)
                            showSourceSettingsSheet = false
                            Toast.makeText(context, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Сохранить",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }

    // ModalBottomSheet: Select Remote File / Version on GitHub
    if (showFilePickerSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFilePickerSheet = false },
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Файлы на GitHub",
                    style = MaterialTheme.typography.titleLarge.copy(color = TextPrimary)
                )

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = NestedSurface,
                    border = BorderStroke(0.5.dp, SpecularStroke),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        availableFiles.forEachIndexed { idx, file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showFilePickerSheet = false
                                        downloadAndShare(file)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = TextPrimary,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (file.sizeBytes > 0) {
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = formatBytes(file.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                            color = MintCta
                                        )
                                    )
                                }
                            }
                            if (idx < availableFiles.lastIndex) {
                                HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)
                            }
                        }
                    }
                }
            }
        }
    }

    // ModalBottomSheet: Telegram Client Picker with real PackageManager icons
    pendingShareFile?.let { fileToShare ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { pendingShareFile = null },
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Выберите Telegram-клиент",
                        style = MaterialTheme.typography.titleLarge.copy(color = TextPrimary)
                    )
                    Text(
                        text = "Отправьте файл в «Избранное» (Saved Messages) для быстрой установки",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = NestedSurface,
                    border = BorderStroke(0.5.dp, SpecularStroke),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        telegramClientsForPicker.forEachIndexed { idx, client ->
                            val resolved = remember(client.packageName) {
                                AppMetadataResolver.resolveApp(context, client.packageName, client.label)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pendingShareFile = null
                                        val intent = PluginDownloader.buildShareToTelegramIntent(
                                            context = context,
                                            file = fileToShare,
                                            targetPackage = client.packageName
                                        )
                                        context.startActivity(intent)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (resolved.icon != null) {
                                    Image(
                                        bitmap = resolved.icon,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(SecondaryGlass),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = null,
                                            tint = MintCta,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = resolved.displayName,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            color = TextPrimary,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = client.packageName,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = TextMuted,
                                            fontFamily = JetBrainsMonoFontFamily
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            if (idx < telegramClientsForPicker.lastIndex) {
                                HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)
                            }
                        }
                    }
                }

                GraphiteGlassButton(
                    onClick = {
                        pendingShareFile = null
                        val chooser = Intent.createChooser(
                            PluginDownloader.buildShareToTelegramIntent(context, fileToShare),
                            "Отправить плагин"
                        )
                        context.startActivity(chooser)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Другое приложение (системный выбор)",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun CircularToolbarIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = NestedSurface,
            border = BorderStroke(0.75.dp, SpecularStroke),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = SecondaryGlassText,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickInstallStepRow(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(NestedSurface),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                    color = MintCta,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f КБ", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.2f МБ", mb)
}
