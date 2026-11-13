package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.filter.AppFilterManager
import com.example.filter.DiscoveredMediaApp
import com.example.ui.theme.CoralRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceHighlight
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.JetBrainsMonoFontFamily
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.ui.graphics.luminance
import com.example.ui.theme.LivingPaletteState
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.rotatingBorderBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TestApiResult(
    val isSuccess: Boolean,
    val headerTitle: String,
    val content: String
)

@Composable
fun DiagnosticsTab(
    context: Context,
    livingPalette: LivingPaletteState,
    isServerRunning: Boolean,
    requestCount: Long,
    activeSseClients: Int,
    lastRequestTimestamp: Long,
    syncOffsetMs: Int,
    onSyncOffsetChange: (Int) -> Unit,
    artworkDebugStatus: String,
    isFilterEnabled: Boolean,
    ignoredPackages: Set<String>,
    discoveredApps: List<DiscoveredMediaApp>,
    backupTargetPath: String,
    backupWrittenTime: Long,
    backupFileSize: Int,
    backupStatus: String,
    isNotificationAccessGranted: Boolean,
    isBatteryOptIgnored: Boolean,
    onForceRebind: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var apiTestResult by remember { mutableStateOf<TestApiResult?>(null) }
    var isTestingApi by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. HTTP + SSE Diagnostics Card
        HttpServerDiagnosticsCard(
            livingPalette = livingPalette,
            isServerRunning = isServerRunning,
            requestCount = requestCount,
            activeSseClients = activeSseClients,
            lastRequestTimestamp = lastRequestTimestamp,
            artworkDebugStatus = artworkDebugStatus,
            onCopyUrl = { urlToCopy ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Clawd Bridge API", urlToCopy)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "URL скопирован: $urlToCopy", Toast.LENGTH_SHORT).show()
            },
            onTestNowPlaying = {
                coroutineScope.launch {
                    isTestingApi = true
                    apiTestResult = testLocalEndpoint("http://127.0.0.1:44444/now-playing", timeoutMs = 1500)
                    isTestingApi = false
                }
            },
            onTestSse = {
                coroutineScope.launch {
                    isTestingApi = true
                    apiTestResult = testSseEndpoint("http://127.0.0.1:44444/events")
                    isTestingApi = false
                }
            },
            onTestLyrics = {
                coroutineScope.launch {
                    isTestingApi = true
                    apiTestResult = testLocalEndpoint("http://127.0.0.1:44444/lyrics", timeoutMs = 6000)
                    isTestingApi = false
                }
            },
            apiTestResult = apiTestResult,
            isTestingApi = isTestingApi
        )

        // 2. Global Sync Calibration Card
        SyncOffsetCard(
            livingPalette = livingPalette,
            syncOffsetMs = syncOffsetMs,
            onSyncOffsetChange = onSyncOffsetChange
        )

        // 3. App Filter Card (Blacklist — disabled & empty by default)
        AppFilterCard(
            livingPalette = livingPalette,
            isFilterEnabled = isFilterEnabled,
            ignoredPackages = ignoredPackages,
            discoveredApps = discoveredApps,
            onToggleFilter = { enabled -> AppFilterManager.setFilterEnabled(enabled) },
            onAddPackage = { pkg -> AppFilterManager.addPackage(pkg) },
            onRemovePackage = { pkg -> AppFilterManager.removePackage(pkg) },
            onTogglePreset = { packages, currentlySelected ->
                if (currentlySelected) {
                    AppFilterManager.removePackages(packages)
                } else {
                    AppFilterManager.addPackages(packages)
                }
            },
            onClearAll = { AppFilterManager.clearAll() }
        )

        // 4. Backup Storage Card (ТЗ 3.4)
        BackupStorageCard(
            livingPalette = livingPalette,
            targetPath = backupTargetPath,
            lastWrittenTime = backupWrittenTime,
            fileSize = backupFileSize,
            status = backupStatus
        )

        // 5. Permissions & Force Rebind Card
        PermissionsAndRebindCard(
            context = context,
            livingPalette = livingPalette,
            isNotificationAccessGranted = isNotificationAccessGranted,
            isBatteryOptIgnored = isBatteryOptIgnored,
            onForceRebind = onForceRebind
        )
    }
}

@Composable
private fun HttpServerDiagnosticsCard(
    livingPalette: LivingPaletteState,
    isServerRunning: Boolean,
    requestCount: Long,
    activeSseClients: Int,
    lastRequestTimestamp: Long,
    artworkDebugStatus: String,
    onCopyUrl: (String) -> Unit,
    onTestNowPlaying: () -> Unit,
    onTestSse: () -> Unit,
    onTestLyrics: () -> Unit,
    apiTestResult: TestApiResult?,
    isTestingApi: Boolean
) {
    val accent = livingPalette.primary
    val accent2 = livingPalette.secondary
    val accent3 = livingPalette.tertiary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("http_server_diagnostics_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.82f)),
        border = BorderStroke(1.dp, rotatingBorderBrush(livingPalette, alpha = 0.36f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
                        imageVector = Icons.Default.Http,
                        contentDescription = null,
                        tint = accent
                    )
                    Text(
                        text = "HTTP + SSE API",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accent2.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = "SSE: $activeSseClients",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = accent2
                            )
                        )
                    }
                    Text(
                        text = "$requestCount запр.",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = accent
                        )
                    )
                }
            }

            EndpointRow(
                url = "http://127.0.0.1:44444/now-playing",
                urlColor = accent,
                onCopy = { onCopyUrl("http://127.0.0.1:44444/now-playing") }
            )
            EndpointRow(
                url = "http://127.0.0.1:44444/events (SSE)",
                urlColor = accent,
                onCopy = { onCopyUrl("http://127.0.0.1:44444/events") }
            )
            EndpointRow(
                url = "http://127.0.0.1:44444/artwork",
                urlColor = accent,
                onCopy = { onCopyUrl("http://127.0.0.1:44444/artwork") }
            )
            EndpointRow(
                url = "http://127.0.0.1:44444/lyrics",
                urlColor = accent2,
                onCopy = { onCopyUrl("http://127.0.0.1:44444/lyrics") }
            )

            Text(
                text = "Обложка: $artworkDebugStatus",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onTestNowPlaying,
                    enabled = !isTestingApi && isServerRunning,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("test_api_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent.copy(alpha = 0.2f),
                        contentColor = accent
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isTestingApi) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = accent,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("GET /now-playing", fontSize = 12.sp)
                    }
                }

                OutlinedButton(
                    onClick = onTestSse,
                    enabled = !isTestingApi && isServerRunning,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("test_sse_button"),
                    border = BorderStroke(1.dp, accent2.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = accent2,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Тест SSE /events", color = accent2, fontSize = 12.sp)
                }
            }

            OutlinedButton(
                onClick = onTestLyrics,
                enabled = !isTestingApi && isServerRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("test_lyrics_button"),
                border = BorderStroke(1.dp, accent3.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lyrics,
                    contentDescription = null,
                    tint = accent3,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Тест GET /lyrics (LRCLIB + NetEase + Genius)", color = accent3, fontSize = 12.sp)
            }

            AnimatedVisibility(visible = apiTestResult != null) {
                apiTestResult?.let { result ->
                    val borderColor = if (result.isSuccess) accent else CoralRed
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DarkBackground.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (result.isSuccess) {
                                    "Ответ сервера (${result.headerTitle}):"
                                } else {
                                    "Ошибка: ${result.headerTitle}"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (result.isSuccess) TextSecondary else CoralRed,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = result.content,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = if (result.isSuccess) accent else CoralRed,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointRow(
    url: String,
    urlColor: Color = NeonEmerald,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = DarkBackground.copy(alpha = 0.80f),
        border = BorderStroke(1.dp, DarkSurfaceHighlight.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = urlColor,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = onCopy,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Копировать $url",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppFilterCard(
    livingPalette: LivingPaletteState,
    isFilterEnabled: Boolean,
    ignoredPackages: Set<String>,
    discoveredApps: List<DiscoveredMediaApp>,
    onToggleFilter: (Boolean) -> Unit,
    onAddPackage: (String) -> Unit,
    onRemovePackage: (String) -> Unit,
    onTogglePreset: (List<String>, Boolean) -> Unit,
    onClearAll: () -> Unit
) {
    var customPackageInput by remember { mutableStateOf("") }
    val accent = livingPalette.primary
    val onAccent = if (accent.luminance() > 0.42f) Color.Black else Color.White

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_filter_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.82f)),
        border = BorderStroke(
            1.dp,
            rotatingBorderBrush(livingPalette, alpha = if (isFilterEnabled) 0.55f else 0.30f, angleOffsetDeg = 120f)
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = null,
                        tint = if (isFilterEnabled) accent else TextMuted
                    )
                    Column {
                        Text(
                            text = "Фильтр приложений (игнор)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = if (isFilterEnabled) {
                                "Игнорируется пакетов: ${ignoredPackages.size}"
                            } else {
                                "Выключен — перехватываются все плееры"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }
                }

                Switch(
                    checked = isFilterEnabled,
                    onCheckedChange = onToggleFilter,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = onAccent,
                        checkedTrackColor = accent
                    ),
                    modifier = Modifier.testTag("app_filter_switch")
                )
            }

            AnimatedVisibility(visible = isFilterEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Обнаруженные источники звука (без прав QUERY_ALL_PACKAGES):",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                    )

                    if (discoveredApps.isEmpty()) {
                        Text(
                            text = "Воспроизведите любой трек или видео — приложение автоматически появится здесь для выбора в 1 тап.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            discoveredApps.forEach { app ->
                                val isIgnored = app.packageName in ignoredPackages
                                FilterChip(
                                    selected = isIgnored,
                                    onClick = {
                                        if (isIgnored) onRemovePackage(app.packageName)
                                        else onAddPackage(app.packageName)
                                    },
                                    label = {
                                        Text(
                                            text = if (app.appLabel != app.packageName) {
                                                "${app.appLabel} (${app.packageName})"
                                            } else {
                                                app.packageName
                                            },
                                            fontSize = 11.sp
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CoralRed.copy(alpha = 0.2f),
                                        selectedLabelColor = CoralRed
                                    )
                                )
                            }
                        }
                    }

                    Text(
                        text = "Быстрые пресеты (нажмите, чтобы добавить/убрать):",
                        style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppFilterManager.presets.forEach { preset ->
                            val isSelected = preset.packages.any { it in ignoredPackages }
                            FilterChip(
                                selected = isSelected,
                                onClick = { onTogglePreset(preset.packages, isSelected) },
                                label = { Text(preset.title, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = ElectricCyan
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customPackageInput,
                            onValueChange = { customPackageInput = it },
                            label = { Text("Имя пакета (например com.example.app)", fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = TextPrimary
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ElectricCyan,
                                unfocusedBorderColor = DarkSurfaceHighlight
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = {
                                if (customPackageInput.isNotBlank()) {
                                    onAddPackage(customPackageInput)
                                    customPackageInput = ""
                                }
                            },
                            modifier = Modifier.height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "Добавить пакет")
                        }
                    }

                    if (ignoredPackages.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Активный список исключений:",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                            )
                            TextButton(onClick = onClearAll) {
                                Text("Очистить всё", fontSize = 11.sp, color = CoralRed)
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ignoredPackages.sorted().forEach { pkg ->
                                FilterChip(
                                    selected = true,
                                    onClick = { onRemovePackage(pkg) },
                                    label = {
                                        Text(
                                            text = pkg,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontSize = 11.sp
                                        )
                                    },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Удалить $pkg",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncOffsetCard(
    livingPalette: LivingPaletteState,
    syncOffsetMs: Int,
    onSyncOffsetChange: (Int) -> Unit
) {
    val accent = livingPalette.primary
    val formattedOffset = if (syncOffsetMs > 0) "+${syncOffsetMs} мс" else "${syncOffsetMs} мс"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sync_offset_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.82f)),
        border = BorderStroke(
            1.dp,
            rotatingBorderBrush(livingPalette, alpha = if (syncOffsetMs != 0) 0.55f else 0.30f, angleOffsetDeg = 60f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = if (syncOffsetMs != 0) accent else TextSecondary
                    )
                    Column {
                        Text(
                            text = "Сдвиг синхронизации",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "Компенсация задержки Bluetooth / плеера",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (syncOffsetMs != 0) accent.copy(alpha = 0.16f) else DarkSurfaceHighlight
                ) {
                    Text(
                        text = formattedOffset,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (syncOffsetMs != 0) accent else TextPrimary
                        )
                    )
                }
            }

            Slider(
                value = syncOffsetMs.toFloat(),
                onValueChange = { raw ->
                    val stepped = (Math.round(raw / 50f) * 50).coerceIn(-2000, 2000)
                    onSyncOffsetChange(stepped)
                },
                valueRange = -2000f..2000f,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = DarkSurfaceHighlight
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onSyncOffsetChange((syncOffsetMs - 100).coerceIn(-2000, 2000)) },
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("-100 мс", fontSize = 12.sp, color = TextPrimary)
                }

                TextButton(
                    onClick = { onSyncOffsetChange(0) },
                    enabled = syncOffsetMs != 0
                ) {
                    Text("Сброс (0 мс)", fontSize = 12.sp, color = accent)
                }

                OutlinedButton(
                    onClick = { onSyncOffsetChange((syncOffsetMs + 100).coerceIn(-2000, 2000)) },
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("+100 мс", fontSize = 12.sp, color = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun BackupStorageCard(
    livingPalette: LivingPaletteState,
    targetPath: String,
    lastWrittenTime: Long,
    fileSize: Int,
    status: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backup_storage_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.82f)),
        border = BorderStroke(1.dp, rotatingBorderBrush(livingPalette, alpha = 0.28f, angleOffsetDeg = 210f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Резервный файл (Троттлинг 2 сек)",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
            }

            Text(
                text = targetPath,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    color = TextMuted,
                    fontSize = 11.sp
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Размер: ${if (fileSize > 0) "$fileSize / 512 байт" else "ожидание"}",
                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                )

                Text(
                    text = if (lastWrittenTime > 0) {
                        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        "Обновлено: ${sdf.format(Date(lastWrittenTime))}"
                    } else "Не записывался",
                    style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                )
            }
        }
    }
}

@Composable
private fun PermissionsAndRebindCard(
    context: Context,
    livingPalette: LivingPaletteState,
    isNotificationAccessGranted: Boolean,
    isBatteryOptIgnored: Boolean,
    onForceRebind: () -> Unit
) {
    val accent = livingPalette.primary
    val accent2 = livingPalette.secondary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("notification_permission_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isNotificationAccessGranted) DarkSurface.copy(alpha = 0.82f) else NeonEmerald,
                contentColor = if (isNotificationAccessGranted) accent else Color.Black
            ),
            border = if (isNotificationAccessGranted) {
                BorderStroke(1.dp, accent.copy(alpha = 0.45f))
            } else null,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isNotificationAccessGranted) {
                    "Доступ к уведомлениям включен ✓"
                } else {
                    "Включить доступ к уведомлениям"
                },
                fontWeight = FontWeight.SemiBold
            )
        }

        OutlinedButton(
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
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("battery_optimization_button"),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (isBatteryOptIgnored) NeonEmerald else accent2
            ),
            border = BorderStroke(
                1.dp,
                if (isBatteryOptIgnored) NeonEmerald.copy(alpha = 0.5f) else accent2.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isBatteryOptIgnored) {
                    "Оптимизация батареи отключена ✓"
                } else {
                    "Отключить оптимизацию батареи"
                },
                fontWeight = FontWeight.Medium
            )
        }

        if (isNotificationAccessGranted) {
            OutlinedButton(
                onClick = onForceRebind,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("force_rebind_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent2),
                border = BorderStroke(1.dp, accent2.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = accent2
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Сбросить и переподключить службу",
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private suspend fun testLocalEndpoint(targetUrl: String, timeoutMs: Int = 1500): TestApiResult = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        val url = URL(targetUrl)
        conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
        }
        val code = conn.responseCode
        val msg = conn.responseMessage ?: ""
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        TestApiResult(
            isSuccess = (code in 200..299),
            headerTitle = "HTTP $code $msg",
            content = text
        )
    } catch (e: Exception) {
        TestApiResult(
            isSuccess = false,
            headerTitle = e.message ?: e.toString(),
            content = "Не удалось связаться с $targetUrl. Убедитесь, что служба запущена."
        )
    } finally {
        conn?.disconnect()
    }
}

private suspend fun testSseEndpoint(targetUrl: String): TestApiResult = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
        val url = URL(targetUrl)
        conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 1500
            readTimeout = 2000
            requestMethod = "GET"
            setRequestProperty("Accept", "text/event-stream")
        }
        val code = conn.responseCode
        val msg = conn.responseMessage ?: ""
        if (code !in 200..299) {
            return@withContext TestApiResult(
                isSuccess = false,
                headerTitle = "HTTP $code $msg",
                content = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            )
        }
        val lines = mutableListOf<String>()
        conn.inputStream.bufferedReader().use { reader ->
            while (lines.size < 12) {
                val line = reader.readLine() ?: break
                lines.add(line)
                if (line.isEmpty() && lines.any { it.isNotBlank() }) break
            }
        }
        TestApiResult(
            isSuccess = true,
            headerTitle = "HTTP $code SSE Stream OK",
            content = lines.joinToString("\n")
        )
    } catch (e: Exception) {
        TestApiResult(
            isSuccess = false,
            headerTitle = e.message ?: e.toString(),
            content = "Ошибка проверки SSE $targetUrl"
        )
    } finally {
        conn?.disconnect()
    }
}
