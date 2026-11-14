package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.filter.AppFilterManager
import com.example.filter.DiscoveredMediaApp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class TestApiResult(
    val isSuccess: Boolean,
    val endpoint: String,
    val headerTitle: String,
    val latencyMs: Long,
    val content: String
)

private data class EndpointSpec(
    val methodTag: String,
    val path: String,
    val fullUrl: String
)

private val LOCAL_ENDPOINTS = listOf(
    EndpointSpec("GET", "/now-playing", "http://127.0.0.1:44444/now-playing"),
    EndpointSpec("SSE", "/events", "http://127.0.0.1:44444/events"),
    EndpointSpec("IMG", "/artwork", "http://127.0.0.1:44444/artwork"),
    EndpointSpec("TXT", "/lyrics", "http://127.0.0.1:44444/lyrics")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsTab(
    context: Context,
    livingPalette: LivingPaletteState,
    artworkBitmap: Bitmap?,
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
    dynamicBackgroundEnabled: Boolean,
    onToggleDynamicBackground: (Boolean) -> Unit,
    backgroundStyle: BackgroundStyle,
    onBackgroundStyleChange: (BackgroundStyle) -> Unit,
    onSliderDragChange: (Boolean) -> Unit = {},
    onForceRebind: () -> Unit,
    openUpdateSheetRequested: Boolean = false,
    onConsumeOpenUpdateSheet: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var apiTestResult by remember { mutableStateOf<TestApiResult?>(null) }
    var isTestingApi by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HttpServerDiagnosticsCard(
            livingPalette = livingPalette,
            artworkBitmap = artworkBitmap,
            isServerRunning = isServerRunning,
            requestCount = requestCount,
            activeSseClients = activeSseClients,
            artworkDebugStatus = artworkDebugStatus,
            onCopyUrl = { urlToCopy ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Clawd Bridge API", urlToCopy))
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                Toast.makeText(context, "Скопировано: $urlToCopy", Toast.LENGTH_SHORT).show()
            },
            onTestNowPlaying = {
                coroutineScope.launch {
                    isTestingApi = true
                    apiTestResult = testLocalEndpoint("http://127.0.0.1:44444/now-playing", "/now-playing", 1500)
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
                    apiTestResult = testLocalEndpoint("http://127.0.0.1:44444/lyrics", "/lyrics", 6000)
                    isTestingApi = false
                }
            },
            isTestingApi = isTestingApi
        )

        SyncOffsetCard(
            syncOffsetMs = syncOffsetMs,
            onSyncOffsetChange = onSyncOffsetChange,
            onSliderDragChange = onSliderDragChange
        )

        AppFilterCard(
            isFilterEnabled = isFilterEnabled,
            ignoredPackages = ignoredPackages,
            discoveredApps = discoveredApps,
            onToggleFilter = { AppFilterManager.setFilterEnabled(it) },
            onAddPackage = { AppFilterManager.addPackage(it) },
            onRemovePackage = { AppFilterManager.removePackage(it) },
            onTogglePreset = { packages, currentlySelected ->
                if (currentlySelected) AppFilterManager.removePackages(packages)
                else AppFilterManager.addPackages(packages)
            },
            onClearAll = { AppFilterManager.clearAll() }
        )

        BackupStorageCard(
            context = context,
            targetPath = backupTargetPath,
            lastWrittenTime = backupWrittenTime,
            fileSize = backupFileSize
        )

        SystemAndServiceSection(
            context = context,
            livingPalette = livingPalette,
            isNotificationAccessGranted = isNotificationAccessGranted,
            isBatteryOptIgnored = isBatteryOptIgnored,
            dynamicBackgroundEnabled = dynamicBackgroundEnabled,
            onToggleDynamicBackground = onToggleDynamicBackground,
            backgroundStyle = backgroundStyle,
            onBackgroundStyleChange = onBackgroundStyleChange,
            onForceRebind = onForceRebind,
            openUpdateSheetRequested = openUpdateSheetRequested,
            onConsumeOpenUpdateSheet = onConsumeOpenUpdateSheet
        )
    }

    // ModalBottomSheet API Response Viewer
    apiTestResult?.let { result ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { apiTestResult = null },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = result.endpoint,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "${result.headerTitle} • ${result.latencyMs} мс",
                            style = MaterialTheme.typography.labelMedium.withTabularNumbers().copy(
                                color = if (result.isSuccess) SemanticEmerald else SemanticCoral,
                                fontFamily = JetBrainsMonoFontFamily
                            )
                        )
                    }

                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("API Response", result.content))
                            Toast.makeText(context, "Ответ скопирован", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Копировать ответ",
                            tint = SecondaryGlassText
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = NestedSurface,
                    border = BorderStroke(0.5.dp, SpecularStroke),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = result.content.ifBlank { "(пустой ответ)" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = if (result.isSuccess) MintCta else SemanticCoral
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun HttpServerDiagnosticsCard(
    livingPalette: LivingPaletteState,
    artworkBitmap: Bitmap?,
    isServerRunning: Boolean,
    requestCount: Long,
    activeSseClients: Int,
    artworkDebugStatus: String,
    onCopyUrl: (String) -> Unit,
    onTestNowPlaying: () -> Unit,
    onTestSse: () -> Unit,
    onTestLyrics: () -> Unit,
    isTestingApi: Boolean
) {
    var copiedPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedPath) {
        if (copiedPath != null) {
            delay(1500L)
            copiedPath = null
        }
    }

    val validBmp = remember(artworkBitmap) {
        artworkBitmap?.takeIf { !it.isRecycled }
    }
    val previewBmp = remember(validBmp) {
        validBmp?.asImageBitmap()
    }
    val effectiveArtworkStatus = remember(validBmp, artworkDebugStatus) {
        if (validBmp != null && (artworkDebugStatus.isBlank() || artworkDebugStatus == "Ожидание трека")) {
            "Готова • ${validBmp.width}×${validBmp.height}"
        } else {
            artworkDebugStatus
        }
    }

    FrostedGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("http_server_diagnostics_card"),
        containerColor = CardSurfaceDense,
        cornerRadius = 24.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        tint = SemanticEmerald
                    )
                    Text(
                        text = "Локальный сервер",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = NestedSurface
                    ) {
                        Text(
                            text = "SSE: $activeSseClients",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = SemanticEmerald
                            )
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = NestedSurface
                    ) {
                        Text(
                            text = "$requestCount запр.",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.withTabularNumbers().copy(
                                fontFamily = JetBrainsMonoFontFamily,
                                color = TextSecondary
                            )
                        )
                    }
                }
            }

            // Unified endpoint block with 0.5dp HairlineDividers
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = NestedSurface,
                border = BorderStroke(0.5.dp, SpecularStroke),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    LOCAL_ENDPOINTS.forEachIndexed { idx, ep ->
                        val isCopied = copiedPath == ep.path
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    copiedPath = ep.path
                                    onCopyUrl(ep.fullUrl)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = SecondaryGlass
                            ) {
                                Text(
                                    text = ep.methodTag,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MintCta,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }

                            Text(
                                text = "127.0.0.1:44444${ep.path}",
                                style = MaterialTheme.typography.bodySmall.withTabularNumbers().copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = TextPrimary
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            Icon(
                                imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = "Копировать ${ep.fullUrl}",
                                tint = if (isCopied) SemanticEmerald else TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (idx < LOCAL_ENDPOINTS.lastIndex) {
                            HorizontalDivider(thickness = 0.5.dp, color = HairlineDivider)
                        }
                    }
                }
            }

            // Artwork status & mini preview
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = NestedSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SecondaryGlass),
                        contentAlignment = Alignment.Center
                    ) {
                        if (previewBmp != null) {
                            Image(
                                bitmap = previewBmp,
                                contentDescription = "Мини-превью обложки",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Text(
                        text = "Обложка: $effectiveArtworkStatus",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = TextSecondary
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Unified calm frosted graphite test buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GraphiteGlassButton(
                    onClick = onTestNowPlaying,
                    enabled = !isTestingApi && isServerRunning,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("test_api_button")
                ) {
                    if (isTestingApi) {
                        WaveLoadingDots(
                            color = SecondaryGlassText,
                            timeSecondsState = livingPalette.uiTimeSecondsState,
                            modifier = Modifier.size(width = 20.dp, height = 14.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "GET /now-playing",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                GraphiteGlassButton(
                    onClick = onTestSse,
                    enabled = !isTestingApi && isServerRunning,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("test_sse_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Тест SSE",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }

            GraphiteGlassButton(
                onClick = onTestLyrics,
                enabled = !isTestingApi && isServerRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("test_lyrics_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Lyrics,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Тест GET /lyrics",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun SyncOffsetCard(
    syncOffsetMs: Int,
    onSyncOffsetChange: (Int) -> Unit,
    onSliderDragChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val formattedOffset = if (syncOffsetMs > 0) "+${syncOffsetMs} мс" else "${syncOffsetMs} мс"

    FrostedGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sync_offset_card"),
        containerColor = CardSurfaceDense,
        cornerRadius = 24.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
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
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = if (syncOffsetMs != 0) MintCta else TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Сдвиг синхронизации",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "Компенсация задержки Bluetooth / плеера",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = NestedSurface
                ) {
                    Text(
                        text = formattedOffset,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium.withTabularNumbers().copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (syncOffsetMs != 0) MintCta else TextPrimary
                        )
                    )
                }
            }

            CenteredSyncSlider(
                valueMs = syncOffsetMs,
                onValueChange = { next ->
                    if ((syncOffsetMs < 0 && next >= 0) || (syncOffsetMs > 0 && next <= 0) || (syncOffsetMs != 0 && next == 0)) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onSyncOffsetChange(next)
                },
                onDragActiveChange = onSliderDragChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            )

            // Connected Button Group: -100 мс | Сброс | +100 мс
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                GraphiteGlassButton(
                    onClick = { onSyncOffsetChange((syncOffsetMs - 100).coerceIn(-2000, 2000)) },
                    shapeOverride = RoundedCornerShape(
                        topStart = 24.dp,
                        bottomStart = 24.dp,
                        topEnd = 8.dp,
                        bottomEnd = 8.dp
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("-100 мс", style = MaterialTheme.typography.labelMedium.withTabularNumbers())
                }

                GraphiteGlassButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSyncOffsetChange(0)
                    },
                    enabled = syncOffsetMs != 0,
                    shapeOverride = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Сброс", style = MaterialTheme.typography.labelMedium)
                }

                GraphiteGlassButton(
                    onClick = { onSyncOffsetChange((syncOffsetMs + 100).coerceIn(-2000, 2000)) },
                    shapeOverride = RoundedCornerShape(
                        topStart = 8.dp,
                        bottomStart = 8.dp,
                        topEnd = 24.dp,
                        bottomEnd = 24.dp
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("+100 мс", style = MaterialTheme.typography.labelMedium.withTabularNumbers())
                }
            }
        }
    }
}

@Composable
private fun CenteredSyncSlider(
    valueMs: Int,
    onValueChange: (Int) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableIntStateOf(1) }

    fun offsetXToStep(x: Float): Int {
        val ratio = (x / widthPx.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
        val raw = -2000f + ratio * 4000f
        return ((raw / 50f).roundToInt() * 50).coerceIn(-2000, 2000)
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    widthPx = size.width
                    onValueChange(offsetXToStep(pos.x))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { onDragActiveChange(true) },
                    onDragEnd = { onDragActiveChange(false) },
                    onDragCancel = { onDragActiveChange(false) }
                ) { change, _ ->
                    change.consume()
                    widthPx = size.width
                    onValueChange(offsetXToStep(change.position.x))
                }
            }
    ) {
        val w = size.width
        val cy = size.height / 2f
        val centerX = w / 2f
        val normalized = (valueMs + 2000f) / 4000f
        val thumbX = (normalized * w).coerceIn(8.dp.toPx(), w - 8.dp.toPx())

        // Base inactive track
        drawLine(
            color = Color.White.copy(alpha = 0.14f),
            start = Offset(8.dp.toPx(), cy),
            end = Offset(w - 8.dp.toPx(), cy),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Center notch at 0 ms
        drawLine(
            color = Color.White.copy(alpha = 0.45f),
            start = Offset(centerX, cy - 7.dp.toPx()),
            end = Offset(centerX, cy + 7.dp.toPx()),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round
        )

        // Active segment extending outward from center 0 ms
        if (valueMs != 0) {
            drawLine(
                color = MintCta,
                start = Offset(centerX, cy),
                end = Offset(thumbX, cy),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Thumb capsule
        drawCircle(
            color = MintCta,
            radius = 9.dp.toPx(),
            center = Offset(thumbX, cy)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppFilterCard(
    isFilterEnabled: Boolean,
    ignoredPackages: Set<String>,
    discoveredApps: List<DiscoveredMediaApp>,
    onToggleFilter: (Boolean) -> Unit,
    onAddPackage: (String) -> Unit,
    onRemovePackage: (String) -> Unit,
    onTogglePreset: (List<String>, Boolean) -> Unit,
    onClearAll: () -> Unit
) {
    val context = LocalContext.current
    var customPackageInput by remember { mutableStateOf("") }

    FrostedGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_filter_card"),
        containerColor = CardSurfaceDense,
        cornerRadius = 24.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = null,
                        tint = if (isFilterEnabled) MintCta else TextMuted
                    )
                    Column {
                        Text(
                            text = "Фильтр приложений (игнор)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = if (isFilterEnabled) {
                                "Игнорируется пакетов: ${ignoredPackages.size}"
                            } else {
                                "Выключен — перехватываются все плееры"
                            },
                            style = MaterialTheme.typography.bodySmall.withTabularNumbers().copy(
                                color = TextMuted
                            )
                        )
                    }
                }

                ExpressiveGlassSwitch(
                    checked = isFilterEnabled,
                    onCheckedChange = onToggleFilter,
                    modifier = Modifier.testTag("app_filter_switch")
                )
            }

            AnimatedVisibility(visible = isFilterEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Обнаруженные источники звука:",
                        style = MaterialTheme.typography.labelMedium.copy(color = TextMuted)
                    )

                    if (discoveredApps.isEmpty()) {
                        Text(
                            text = "Воспроизведите любой трек или видео — приложение появится здесь для выбора в 1 тап.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            discoveredApps.forEach { app ->
                                val isIgnored = app.packageName in ignoredPackages
                                val resolved = remember(app.packageName, app.appLabel) {
                                    AppMetadataResolver.resolveApp(context, app.packageName, app.appLabel)
                                }
                                FilterChip(
                                    selected = isIgnored,
                                    onClick = {
                                        if (isIgnored) onRemovePackage(app.packageName)
                                        else onAddPackage(app.packageName)
                                    },
                                    leadingIcon = resolved.icon?.let { bmp ->
                                        {
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                            )
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = resolved.displayName,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = NestedSurface,
                                        labelColor = TextSecondary,
                                        selectedContainerColor = SemanticCoral.copy(alpha = 0.18f),
                                        selectedLabelColor = SemanticCoral
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isIgnored,
                                        borderColor = SpecularStroke,
                                        selectedBorderColor = SemanticCoral.copy(alpha = 0.35f)
                                    )
                                )
                            }
                        }
                    }

                    Text(
                        text = "Быстрые пресеты:",
                        style = MaterialTheme.typography.labelMedium.copy(color = TextMuted)
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
                                label = {
                                    Text(
                                        text = preset.title,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = NestedSurface,
                                    labelColor = TextSecondary,
                                    selectedContainerColor = MintCta.copy(alpha = 0.18f),
                                    selectedLabelColor = MintCta
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = SpecularStroke,
                                    selectedBorderColor = MintCta.copy(alpha = 0.35f)
                                )
                            )
                        }
                    }

                    // Capsule input with embedded circular '+' button inside right slot
                    TextField(
                        value = customPackageInput,
                        onValueChange = { customPackageInput = it },
                        placeholder = {
                            Text(
                                text = "Пакет (например com.example.app)",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(26.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            color = TextPrimary
                        ),
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (customPackageInput.isNotBlank()) MintCta else SecondaryGlass)
                                    .clickable(enabled = customPackageInput.isNotBlank()) {
                                        onAddPackage(customPackageInput)
                                        customPackageInput = ""
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Добавить пакет",
                                    tint = if (customPackageInput.isNotBlank()) MintCtaContent else TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = NestedSurface,
                            unfocusedContainerColor = NestedSurface,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (ignoredPackages.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Список исключений:",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
                            )
                            TextButton(onClick = onClearAll) {
                                Text("Очистить всё", style = MaterialTheme.typography.labelSmall, color = SemanticCoral)
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
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Удалить $pkg",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = NestedSurface,
                                        selectedLabelColor = TextSecondary
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = true,
                                        selectedBorderColor = SpecularStroke
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun testLocalEndpoint(
    targetUrl: String,
    endpointPath: String,
    timeoutMs: Int = 1500
): TestApiResult = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    val startNs = System.nanoTime()
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
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        val pretty = formatJsonIfPossible(raw)
        TestApiResult(
            isSuccess = (code in 200..299),
            endpoint = endpointPath,
            headerTitle = "HTTP $code $msg".trim(),
            latencyMs = elapsedMs,
            content = pretty
        )
    } catch (e: Exception) {
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        TestApiResult(
            isSuccess = false,
            endpoint = endpointPath,
            headerTitle = e.message ?: e.toString(),
            latencyMs = elapsedMs,
            content = "Не удалось связаться с $targetUrl. Убедитесь, что служба запущена."
        )
    } finally {
        conn?.disconnect()
    }
}

private suspend fun testSseEndpoint(targetUrl: String): TestApiResult = withContext(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    val startNs = System.nanoTime()
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
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            return@withContext TestApiResult(
                isSuccess = false,
                endpoint = "/events (SSE)",
                headerTitle = "HTTP $code $msg".trim(),
                latencyMs = elapsedMs,
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
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        TestApiResult(
            isSuccess = true,
            endpoint = "/events (SSE)",
            headerTitle = "HTTP $code SSE Stream OK",
            latencyMs = elapsedMs,
            content = lines.joinToString("\n")
        )
    } catch (e: Exception) {
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        TestApiResult(
            isSuccess = false,
            endpoint = "/events (SSE)",
            headerTitle = e.message ?: e.toString(),
            latencyMs = elapsedMs,
            content = "Ошибка проверки SSE $targetUrl"
        )
    } finally {
        conn?.disconnect()
    }
}

private fun formatJsonIfPossible(raw: String): String {
    val trimmed = raw.trim()
    return try {
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
            else -> raw
        }
    } catch (_: Exception) {
        raw
    }
}
