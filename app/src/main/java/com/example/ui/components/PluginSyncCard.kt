package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.plugin.PluginDownloader
import com.example.plugin.RemotePluginFile
import com.example.plugin.TelegramClientApp
import com.example.ui.theme.CoralRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceHighlight
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun PluginSyncCard(
    context: Context,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    var sourceUrl by remember { mutableStateOf(PluginDownloader.getSavedSourceUrl(context)) }
    var githubToken by remember { mutableStateOf(PluginDownloader.getSavedToken(context)) }
    var convertPyToPlugin by remember { mutableStateOf(PluginDownloader.getConvertPyToPlugin(context)) }
    var showSourceSettings by remember { mutableStateOf(false) }
    var showTokenText by remember { mutableStateOf(false) }

    var isBusy by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var cachedFile by remember { mutableStateOf(PluginDownloader.getCachedPluginFile(context)) }
    var availableFiles by remember { mutableStateOf<List<RemotePluginFile>>(emptyList()) }
    var showFilePickerDialog by remember { mutableStateOf(false) }
    var telegramClientsForPicker by remember { mutableStateOf<List<TelegramClientApp>>(emptyList()) }
    var pendingShareFile by remember { mutableStateOf<File?>(null) }

    fun persistSettings() {
        PluginDownloader.saveSourceUrl(context, sourceUrl)
        PluginDownloader.saveToken(context, githubToken)
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

                statusMessage = "Загрузка ${targetRemote.name} в кэш..."
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
                statusMessage = "Готово: ${downloaded.name} (${formatBytes(downloaded.length())})"
                shareFileToTelegram(downloaded)
            } catch (e: Exception) {
                isError = true
                val msg = e.message ?: "не удалось скачать плагин"
                statusMessage = "Ошибка: $msg"
                if (msg.contains("404") || msg.contains("401") || msg.contains("403")) {
                    showSourceSettings = true
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
            statusMessage = "Загрузка списка файлов из GitHub..."
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
                    statusMessage = "Найдено файлов: ${list.size}"
                    showFilePickerDialog = true
                }
            } catch (e: Exception) {
                isError = true
                val msg = e.message ?: "не удалось получить список"
                statusMessage = "Ошибка: $msg"
                if (msg.contains("404") || msg.contains("401") || msg.contains("403")) {
                    showSourceSettings = true
                }
            } finally {
                isBusy = false
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("plugin_sync_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, ElectricViolet.copy(alpha = 0.45f))
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = ElectricViolet
                    )
                    Column {
                        Text(
                            text = "Плагин Clawd (GitHub → AprelGram / ТГ)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = sourceUrl.removePrefix("https://github.com/"),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 11.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showSourceSettings = !showSourceSettings },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("toggle_plugin_source_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки URL и токена GitHub",
                            tint = if (showSourceSettings) ElectricViolet else TextSecondary
                        )
                    }

                    IconButton(
                        onClick = {
                            val target = sourceUrl.trim().ifEmpty { PluginDownloader.DEFAULT_SOURCE_URL }
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                            runCatching { context.startActivity(intent) }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("open_github_repo_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Открыть GitHub в браузере",
                            tint = TextSecondary
                        )
                    }
                }
            }

            // Collapsible source URL & private repo PAT settings
            AnimatedVisibility(visible = showSourceSettings) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkBackground,
                    border = BorderStroke(1.dp, DarkSurfaceHighlight)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = sourceUrl,
                            onValueChange = {
                                sourceUrl = it
                                PluginDownloader.saveSourceUrl(context, it)
                            },
                            label = { Text("Ссылка GitHub (папка / файл / raw)", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricViolet,
                                unfocusedBorderColor = DarkSurfaceHighlight
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("plugin_source_url_input")
                        )

                        OutlinedTextField(
                            value = githubToken,
                            onValueChange = {
                                githubToken = it
                                PluginDownloader.saveToken(context, it)
                            },
                            label = { Text("GitHub Token (если репозиторий приватный)", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = ElectricViolet,
                                    modifier = Modifier.size(18.dp)
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
                                        tint = TextSecondary
                                    )
                                }
                            },
                            visualTransformation = if (showTokenText) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricViolet,
                                unfocusedBorderColor = DarkSurfaceHighlight
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("plugin_github_token_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Хранится локально. Для приватных репо нужен read-only токен.",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = TextMuted,
                                    fontSize = 10.sp
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    sourceUrl = PluginDownloader.DEFAULT_SOURCE_URL
                                    PluginDownloader.saveSourceUrl(context, sourceUrl)
                                }
                            ) {
                                Text("Сброс URL", fontSize = 11.sp, color = ElectricCyan)
                            }
                        }
                    }
                }
            }

            // Toggle: rename .py -> .plugin for AprelGram / exteraGram
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DarkBackground,
                border = BorderStroke(1.dp, DarkSurfaceHighlight)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Конвертировать .py → .plugin",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "Для прямой установки в AprelGram / exteraGram",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }
                    Switch(
                        checked = convertPyToPlugin,
                        onCheckedChange = { enabled ->
                            convertPyToPlugin = enabled
                            PluginDownloader.setConvertPyToPlugin(context, enabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = ElectricViolet
                        ),
                        modifier = Modifier.testTag("convert_py_to_plugin_switch")
                    )
                }
            }

            // Primary action: download latest plugin into app cache and open Telegram Saved Messages picker
            Button(
                onClick = { downloadAndShare(null) },
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("download_and_share_plugin_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricViolet,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Скачивание...",
                        fontWeight = FontWeight.SemiBold
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
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Secondary row: choose specific version/file from GitHub
            OutlinedButton(
                onClick = { loadFileListForSelection() },
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("choose_plugin_file_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricCyan),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderZip,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Выбрать файл / версию на GitHub",
                    fontWeight = FontWeight.Medium
                )
            }

            AnimatedVisibility(visible = isBusy) {
                val progress = downloadProgress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = ElectricViolet,
                        trackColor = DarkSurfaceHighlight
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = ElectricViolet,
                        trackColor = DarkSurfaceHighlight
                    )
                }
            }

            AnimatedVisibility(visible = statusMessage != null) {
                statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (isError) CoralRed else NeonEmerald,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    )
                }
            }

            // Cached file bar (quick re-send or instant cache wipe)
            cachedFile?.takeIf { it.exists() }?.let { file ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DarkBackground,
                    border = BorderStroke(1.dp, DarkSurfaceHighlight)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Во временном кэше • ${formatBytes(file.length())}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = TextMuted,
                                    fontSize = 10.sp
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
                                contentDescription = "Отправить кэшированный плагин в Telegram",
                                tint = NeonEmerald
                            )
                        }

                        IconButton(
                            onClick = {
                                PluginDownloader.clearPluginCache(context)
                                cachedFile = null
                                statusMessage = "Временный кэш очищен"
                                isError = false
                                Toast.makeText(context, "Кэш плагина очищен", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("clear_plugin_cache_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Очистить временный кэш плагина",
                                tint = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialog to select a specific file if user tapped "Выбрать файл / версию"
    if (showFilePickerDialog) {
        AlertDialog(
            onDismissRequest = { showFilePickerDialog = false },
            containerColor = DarkSurfaceVariant,
            title = {
                Text(
                    text = "Найденные файлы",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableFiles.forEach { file ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DarkSurface,
                            border = BorderStroke(1.dp, DarkSurfaceHighlight),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    showFilePickerDialog = false
                                    downloadAndShare(file)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = TextPrimary,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (file.sizeBytes > 0) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = formatBytes(file.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall.copy(color = ElectricCyan)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilePickerDialog = false }) {
                    Text("Закрыть", color = ElectricCyan)
                }
            }
        )
    }

    // Dialog if multiple Telegram clients (e.g. Telegram + exteraGram + AyuGram) are installed
    pendingShareFile?.let { fileToShare ->
        AlertDialog(
            onDismissRequest = { pendingShareFile = null },
            containerColor = DarkSurfaceVariant,
            title = {
                Text(
                    text = "Выберите Telegram-клиент",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Выберите «Избранное» (Saved Messages) после открытия клиента:",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )
                    telegramClientsForPicker.forEach { client ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DarkSurface,
                            border = BorderStroke(1.dp, ElectricViolet.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    pendingShareFile = null
                                    val intent = PluginDownloader.buildShareToTelegramIntent(
                                        context = context,
                                        file = fileToShare,
                                        targetPackage = client.packageName
                                    )
                                    context.startActivity(intent)
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = client.label,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                Text(
                                    text = client.packageName,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = TextMuted,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingShareFile = null
                        val chooser = Intent.createChooser(
                            PluginDownloader.buildShareToTelegramIntent(context, fileToShare),
                            "Отправить плагин"
                        )
                        context.startActivity(chooser)
                    }
                ) {
                    Text("Другое приложение", color = ElectricCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingShareFile = null }) {
                    Text("Отмена", color = TextSecondary)
                }
            }
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
