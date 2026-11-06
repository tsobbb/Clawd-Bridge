package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.NowPlayingData
import com.example.server.LocalHttpServer
import com.example.simulator.TestMediaSimulator
import com.example.state.MediaBridgeStateHolder
import com.example.storage.BackupFileWriter
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.ClawdBridgeTheme
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LocalHttpServer.start()

        setContent {
            ClawdBridgeTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold"),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    ClawdBridgeScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun ClawdBridgeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Observe State Flows
    val liveState by MediaBridgeStateHolder.liveState.collectAsState()
    val isServiceConnected by MediaBridgeStateHolder.isServiceConnected.collectAsState()
    val requestCount by MediaBridgeStateHolder.requestCount.collectAsState()
    val lastRequestTimestamp by MediaBridgeStateHolder.lastRequestTimestamp.collectAsState()
    val isServerRunning by LocalHttpServer.isRunning.collectAsState()
    val isSimulatorActive by TestMediaSimulator.isSimulatorActive.collectAsState()
    val isSimulatorPlaying by TestMediaSimulator.isPlaying.collectAsState()
    val backupWrittenTime by BackupFileWriter.lastWrittenTime.collectAsState()
    val backupFileSize by BackupFileWriter.lastFileSize.collectAsState()
    val backupStatus by BackupFileWriter.backupStatus.collectAsState()

    // Permission and battery state
    var isNotificationAccessGranted by remember { mutableStateOf(checkNotificationAccess(context)) }
    var isBatteryOptIgnored by remember { mutableStateOf(checkBatteryOptIgnored(context)) }

    // In-app API test response preview
    var apiTestResult by remember { mutableStateOf<String?>(null) }
    var isTestingApi by remember { mutableStateOf(false) }

    // Re-check permissions when returning to foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isNotificationAccessGranted = checkNotificationAccess(context)
                isBatteryOptIgnored = checkBatteryOptIgnored(context)
                MediaBridgeStateHolder.refreshSnapshot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Live Monitor 4 Hz (250 ms) ticker per ТЗ section 4
    LaunchedEffect(Unit) {
        while (isActive) {
            MediaBridgeStateHolder.refreshSnapshot()
            delay(250L)
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        AppHeader(
            isServerRunning = isServerRunning,
            onRestartServer = { LocalHttpServer.start() }
        )

        // 1. Service Status Indicator Badge (ТЗ 4.1)
        ServiceStatusBanner(
            isServiceConnected = isServiceConnected || isNotificationAccessGranted,
            isNotificationAccessGranted = isNotificationAccessGranted
        )

        // 2 & 3. Action Buttons (ТЗ 4.2 & 4.3)
        ActionButtonsSection(
            context = context,
            isNotificationAccessGranted = isNotificationAccessGranted,
            isBatteryOptIgnored = isBatteryOptIgnored
        )

        // 4. Live Monitor Diagnostic Card (ТЗ 4.4)
        LiveMonitorCard(
            liveState = liveState,
            isSimulatorActive = isSimulatorActive
        )

        // Built-in Test Player Simulator (User-confirmed feature)
        SimulatorCard(
            isActive = isSimulatorActive,
            isPlaying = isSimulatorPlaying,
            onToggle = { enable -> TestMediaSimulator.toggleSimulator(enable) },
            onPlayPause = {
                if (isSimulatorPlaying) TestMediaSimulator.pause() else TestMediaSimulator.play()
            },
            onSeekForward = {
                val current = TestMediaSimulator.getEstimatedPositionMs()
                TestMediaSimulator.seekTo(current + 15000L)
            }
        )

        // HTTP Server Status & Local Test Panel
        HttpServerDiagnosticsCard(
            isServerRunning = isServerRunning,
            requestCount = requestCount,
            lastRequestTimestamp = lastRequestTimestamp,
            onCopyUrl = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Clawd Bridge API", "http://127.0.0.1:44444/now-playing")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "URL скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
            },
            onTestApi = {
                coroutineScope.launch {
                    isTestingApi = true
                    apiTestResult = testLocalEndpoint()
                    isTestingApi = false
                }
            },
            apiTestResult = apiTestResult,
            isTestingApi = isTestingApi
        )

        // Backup Storage Protection Info (ТЗ 3.4)
        BackupStorageCard(
            targetPath = BackupFileWriter.targetFilePath,
            lastWrittenTime = backupWrittenTime,
            fileSize = backupFileSize,
            status = backupStatus
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun AppHeader(
    isServerRunning: Boolean,
    onRestartServer: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Clawd Bridge",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    letterSpacing = 0.5.sp
                )
            )
            Text(
                text = "Android Media Sync Service (v1.2)",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isServerRunning) Color(0xFF00381B) else Color(0xFF381515),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isServerRunning) NeonEmerald.copy(alpha = 0.5f) else CoralRed.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isServerRunning) NeonEmerald else CoralRed)
                )
                Text(
                    text = if (isServerRunning) "PORT 44444" else "OFFLINE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isServerRunning) NeonEmerald else CoralRed
                    )
                )
            }
        }
    }
}

/**
 * 1. Service Status Indicator Badge (ТЗ 4.1)
 */
@Composable
private fun ServiceStatusBanner(
    isServiceConnected: Boolean,
    isNotificationAccessGranted: Boolean
) {
    val isActive = isServiceConnected && isNotificationAccessGranted
    val bgColor = if (isActive) Color(0xFF0C2918) else Color(0xFF2C1010)
    val borderColor = if (isActive) NeonEmerald else CoralRed
    val statusText = if (isActive) "Служба активна" else "Требуется доступ к уведомлениям"
    val subText = if (isActive) {
        "Перехват MediaSession работает в реальном времени"
    } else {
        "Разрешите доступ к уведомлениям для чтения медиа-сессий"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("service_status_banner"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor.copy(alpha = 0.6f))
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
                    imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = borderColor,
                    modifier = Modifier.size(24.dp)
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
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary
                    )
                )
            }
        }
    }
}

/**
 * 2 & 3. Action Buttons (ТЗ 4.2 & 4.3)
 */
@Composable
private fun ActionButtonsSection(
    context: Context,
    isNotificationAccessGranted: Boolean,
    isBatteryOptIgnored: Boolean
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Кнопка 1: «Включить доступ к уведомлениям»
        Button(
            onClick = {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("notification_permission_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isNotificationAccessGranted) DarkSurfaceVariant else NeonEmerald,
                contentColor = if (isNotificationAccessGranted) NeonEmerald else Color.Black
            ),
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

        // Кнопка 2: «Отключить оптимизацию батареи»
        OutlinedButton(
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general battery settings
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("battery_optimization_button"),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (isBatteryOptIgnored) NeonEmerald else ElectricCyan
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isBatteryOptIgnored) NeonEmerald.copy(alpha = 0.5f) else ElectricCyan.copy(alpha = 0.5f)
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
    }
}

/**
 * 4. Live Monitor (Диагностика на экране) (ТЗ 4.4)
 * Обновление каждые 250 мс:
 * - Иконка/пакет текущего плеера
 * - Артист — Трек
 * - Бегущий таймкод: 00:24.35 / 03:15.00
 */
@Composable
private fun LiveMonitorCard(
    liveState: NowPlayingData,
    isSimulatorActive: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_monitor_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceHighlight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header with Live Pulse
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
                            .background(if (liveState.isPlaying) NeonEmerald else TextMuted)
                    )
                    Text(
                        text = "LIVE MONITOR (250 ms)",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (liveState.isPlaying) NeonEmerald else TextMuted,
                            letterSpacing = 1.sp
                        )
                    )
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

            HorizontalDivider(color = DarkSurfaceHighlight)

            if (liveState.hasMedia && liveState.isPlaying) {
                // Active Track Display
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${liveState.artist.ifEmpty { "Неизвестный артист" }} — ${liveState.title.ifEmpty { "Без названия" }}",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        maxLines = 2
                    )

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
                                    fontFamily = FontFamily.Monospace,
                                    color = ElectricCyan
                                )
                            )
                        }

                        Text(
                            text = "Playing",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = NeonEmerald,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }

                // Sub-millisecond Timecode: 00:24.35 / 03:15.00
                val progressFraction = if (liveState.durationMs > 0) {
                    (liveState.positionMs.toFloat() / liveState.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTimecode(liveState.positionMs),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan
                            )
                        )
                        Text(
                            text = formatTimecode(liveState.durationMs),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace,
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
                        color = NeonEmerald,
                        trackColor = DarkSurfaceHighlight
                    )
                }
            } else {
                // Idle state display
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Музыка не играет",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Text(
                        text = "Включите трек в SoundCloud, Spotify, VK или запустите тестовый плеер ниже",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Built-in test simulator card
 */
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
        border = androidx.compose.foundation.BorderStroke(
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
                Column {
                    Text(
                        text = "Тестовый симулятор плеера",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "Juice WRLD — Lean Wit Me (SoundCloud)",
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
                            .height(44.dp)
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
                            .height(44.dp)
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

/**
 * HTTP Server Diagnostics & In-App Test
 */
@Composable
private fun HttpServerDiagnosticsCard(
    isServerRunning: Boolean,
    requestCount: Long,
    lastRequestTimestamp: Long,
    onCopyUrl: () -> Unit,
    onTestApi: () -> Unit,
    apiTestResult: String?,
    isTestingApi: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("http_server_diagnostics_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceHighlight)
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Http,
                        contentDescription = null,
                        tint = ElectricCyan
                    )
                    Text(
                        text = "HTTP API (RAM-to-RAM)",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                }

                Text(
                    text = "$requestCount запр.",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = NeonEmerald
                    )
                )
            }

            // Endpoint box
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkBackground,
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceHighlight)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "http://127.0.0.1:44444/now-playing",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = NeonEmerald,
                            fontWeight = FontWeight.Medium
                        )
                    )

                    IconButton(
                        onClick = onCopyUrl,
                        modifier = Modifier.size(32.dp).testTag("copy_api_url_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Копировать URL",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onTestApi,
                    enabled = !isTestingApi && isServerRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .testTag("test_api_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan.copy(alpha = 0.2f),
                        contentColor = ElectricCyan
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isTestingApi) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = ElectricCyan, strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Протестировать GET /now-playing")
                    }
                }
            }

            // Display in-app test result preview
            AnimatedVisibility(visible = apiTestResult != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DarkBackground,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Ответ сервера (HTTP 200 OK):",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = apiTestResult ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = NeonEmerald,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Backup Storage Card (ТЗ 3.4)
 */
@Composable
private fun BackupStorageCard(
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
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceHighlight)
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
                    fontFamily = FontFamily.Monospace,
                    color = TextMuted,
                    fontSize = 11.sp
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Размер: ${if (fileSize > 0) "$fileSize / 200 байт" else "ожидание"}",
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

// Helpers
private fun checkNotificationAccess(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    if (!flat.isNullOrEmpty()) {
        val names = flat.split(":")
        for (name in names) {
            val cn = ComponentName.unflattenFromString(name)
            if (cn != null && cn.packageName == pkgName) {
                return true
            }
        }
    }
    return false
}

private fun checkBatteryOptIgnored(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

private fun formatTimecode(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hundredths = (ms % 1000) / 10
    return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths)
}

private suspend fun testLocalEndpoint(): String = withContext(Dispatchers.IO) {
    try {
        val url = URL("http://127.0.0.1:44444/now-playing")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 2000
            readTimeout = 2000
            requestMethod = "GET"
        }
        val code = conn.responseCode
        if (code == 200) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val text = reader.readText()
            reader.close()
            text
        } else {
            "HTTP $code: ${conn.responseMessage}"
        }
    } catch (e: Exception) {
        "Ошибка запроса: ${e.message}"
    }
}
