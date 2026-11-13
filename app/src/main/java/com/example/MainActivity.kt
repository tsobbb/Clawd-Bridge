package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.filter.AppFilterManager
import com.example.server.LocalHttpServer
import com.example.service.MediaBridgeForegroundService
import com.example.service.NotificationRebindHelper
import com.example.simulator.TestMediaSimulator
import com.example.state.MediaBridgeStateHolder
import com.example.storage.BackupFileWriter
import com.example.ui.components.DiagnosticsTab
import com.example.ui.components.MonitorTab
import com.example.ui.components.PluginSyncCard
import com.example.ui.theme.ClawdBridgeTheme
import com.example.ui.theme.CoralRed
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LivingAuroraBackdrop
import com.example.ui.theme.LivingPaletteState
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.rememberLivingPaletteState
import com.example.ui.theme.rotatingBorderBrush
import com.example.ui.theme.shimmeringHorizontalBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class BridgeNavTab(
    val index: Int,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val BRIDGE_TABS = listOf(
    BridgeNavTab(0, "Монитор", Icons.Filled.GraphicEq, Icons.Outlined.GraphicEq, "nav_tab_monitor"),
    BridgeNavTab(1, "Плагин", Icons.Filled.Extension, Icons.Outlined.Extension, "nav_tab_plugin"),
    BridgeNavTab(2, "Диагностика", Icons.Filled.Tune, Icons.Outlined.Tune, "nav_tab_diagnostics")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AppFilterManager.init(this)
        MediaBridgeStateHolder.init(this)
        LocalHttpServer.start()
        MediaBridgeForegroundService.start(this)
        NotificationRebindHelper.rebindService(this)

        setContent {
            ClawdBridgeTheme {
                ClawdBridgeMainApp()
            }
        }
    }
}

@Composable
fun ClawdBridgeMainApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedTab by remember { mutableIntStateOf(0) }

    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    // State Flows
    val liveState by MediaBridgeStateHolder.liveState.collectAsState()
    val artworkBitmap by MediaBridgeStateHolder.artworkBitmap.collectAsState()
    val isServiceConnected by MediaBridgeStateHolder.isServiceConnected.collectAsState()
    val requestCount by MediaBridgeStateHolder.requestCount.collectAsState()
    val lastRequestTimestamp by MediaBridgeStateHolder.lastRequestTimestamp.collectAsState()
    val isServerRunning by LocalHttpServer.isRunning.collectAsState()
    val activeSseClients by LocalHttpServer.activeSseClients.collectAsState()
    val isSimulatorActive by TestMediaSimulator.isSimulatorActive.collectAsState()
    val isSimulatorPlaying by TestMediaSimulator.isPlaying.collectAsState()
    val backupWrittenTime by BackupFileWriter.lastWrittenTime.collectAsState()
    val backupFileSize by BackupFileWriter.lastFileSize.collectAsState()
    val backupStatus by BackupFileWriter.backupStatus.collectAsState()
    val isFilterEnabled by AppFilterManager.isFilterEnabled.collectAsState()
    val ignoredPackages by AppFilterManager.ignoredPackages.collectAsState()
    val discoveredApps by AppFilterManager.discoveredApps.collectAsState()
    val syncOffsetMs by MediaBridgeStateHolder.syncOffsetMs.collectAsState()
    val artworkDebugStatus by MediaBridgeStateHolder.artworkDebugStatus.collectAsState()

    val livingPalette = rememberLivingPaletteState(liveState)

    var isNotificationAccessGranted by remember {
        mutableStateOf(NotificationRebindHelper.isNotificationAccessGranted(context))
    }
    var isBatteryOptIgnored by remember { mutableStateOf(checkBatteryOptIgnored(context)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            MediaBridgeForegroundService.start(context)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isNotificationAccessGranted = NotificationRebindHelper.isNotificationAccessGranted(context)
                isBatteryOptIgnored = checkBatteryOptIgnored(context)
                MediaBridgeForegroundService.start(context)
                NotificationRebindHelper.rebindService(context)
                MediaBridgeStateHolder.refreshSnapshot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 4 Hz Live Monitor ticker + periodic rebind check
    var tickerCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            MediaBridgeStateHolder.refreshSnapshot()
            tickerCount++
            if (tickerCount % 20 == 0) {
                val granted = NotificationRebindHelper.isNotificationAccessGranted(context)
                isNotificationAccessGranted = granted
                if (granted && !MediaBridgeStateHolder.isServiceConnected.value) {
                    NotificationRebindHelper.rebindService(context)
                }
            }
            delay(250L)
        }
    }

    val forceRebindAction = {
        NotificationRebindHelper.forceRebindService(context)
        MediaBridgeForegroundService.start(context)
        Toast.makeText(context, "Служба принудительно переподключена", Toast.LENGTH_SHORT).show()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LivingAuroraBackdrop(palette = livingPalette)

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .testTag("main_scaffold"),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(
                                shimmeringHorizontalBrush(
                                    livingPalette,
                                    alpha = if (livingPalette.isTrackActive) 0.55f else 0.25f
                                )
                            )
                    )
                    NavigationBar(
                        containerColor = DarkSurface.copy(alpha = 0.86f),
                        tonalElevation = 0.dp
                    ) {
                        BRIDGE_TABS.forEach { tab ->
                            val selected = selectedTab == tab.index
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectedTab = tab.index },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 11.sp
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = livingPalette.primary,
                                    selectedTextColor = livingPalette.primary,
                                    indicatorColor = livingPalette.primary.copy(alpha = 0.16f),
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AppHeader(
                    isServerRunning = isServerRunning,
                    livingPalette = livingPalette,
                    onRestartServer = {
                        LocalHttpServer.start()
                        MediaBridgeForegroundService.start(context)
                        Toast.makeText(context, "Сервер перезапущен", Toast.LENGTH_SHORT).show()
                    }
                )

                Crossfade(
                    targetState = selectedTab,
                    animationSpec = tween(durationMillis = 180),
                    label = "bridge_tab_crossfade"
                ) { tabIndex ->
                    when (tabIndex) {
                        0 -> MonitorTab(
                            liveState = liveState,
                            artworkBitmap = artworkBitmap,
                            livingPalette = livingPalette,
                            isServiceConnected = isServiceConnected,
                            isNotificationAccessGranted = isNotificationAccessGranted,
                            isSimulatorActive = isSimulatorActive,
                            isSimulatorPlaying = isSimulatorPlaying,
                            onForceRebind = forceRebindAction,
                            onToggleSimulator = { enable -> TestMediaSimulator.toggleSimulator(enable) },
                            onPlayPauseSimulator = {
                                if (isSimulatorPlaying) TestMediaSimulator.pause() else TestMediaSimulator.play()
                            },
                            onSeekForwardSimulator = {
                                val current = TestMediaSimulator.getEstimatedPositionMs()
                                TestMediaSimulator.seekTo(current + 15000L)
                            }
                        )
                        1 -> PluginSyncCard(
                            context = context,
                            livingPalette = livingPalette
                        )
                        else -> DiagnosticsTab(
                            context = context,
                            livingPalette = livingPalette,
                            isServerRunning = isServerRunning,
                            requestCount = requestCount,
                            activeSseClients = activeSseClients,
                            lastRequestTimestamp = lastRequestTimestamp,
                            syncOffsetMs = syncOffsetMs,
                            onSyncOffsetChange = { MediaBridgeStateHolder.setSyncOffsetMs(it) },
                            artworkDebugStatus = artworkDebugStatus,
                            isFilterEnabled = isFilterEnabled,
                            ignoredPackages = ignoredPackages,
                            discoveredApps = discoveredApps,
                            backupTargetPath = BackupFileWriter.targetFilePath,
                            backupWrittenTime = backupWrittenTime,
                            backupFileSize = backupFileSize,
                            backupStatus = backupStatus,
                            isNotificationAccessGranted = isNotificationAccessGranted,
                            isBatteryOptIgnored = isBatteryOptIgnored,
                            onForceRebind = forceRebindAction
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(
    isServerRunning: Boolean,
    livingPalette: LivingPaletteState,
    onRestartServer: () -> Unit
) {
    val activeAccent = if (livingPalette.isTrackActive) livingPalette.primary else NeonEmerald
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
                    letterSpacing = 0.3.sp
                )
            )
            Text(
                text = "Android Media Sync & SSE Bridge",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isServerRunning) DarkSurface.copy(alpha = 0.80f) else Color(0xFF381515),
            border = if (isServerRunning) {
                BorderStroke(1.2.dp, rotatingBorderBrush(livingPalette, alpha = 0.72f))
            } else {
                BorderStroke(1.dp, CoralRed.copy(alpha = 0.6f))
            },
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onRestartServer)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isServerRunning) activeAccent else CoralRed)
                )
                Text(
                    text = if (isServerRunning) "PORT 44444" else "OFFLINE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = if (isServerRunning) activeAccent else CoralRed
                    )
                )
            }
        }
    }
}

private fun checkBatteryOptIgnored(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}
