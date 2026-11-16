package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.filter.AppFilterManager
import com.example.server.LocalHttpServer
import com.example.service.MediaBridgeForegroundService
import com.example.service.NotificationRebindHelper
import com.example.simulator.TestMediaSimulator
import com.example.state.MediaBridgeStateHolder
import com.example.storage.BackupFileWriter
import com.example.ui.components.DiagnosticsTab
import com.example.ui.components.FullscreenPlayerOverlay
import com.example.ui.components.MonitorTab
import com.example.ui.components.PluginSyncCard
import com.example.ui.theme.ActivePillSurface
import com.example.ui.theme.ClawdBridgeTheme
import com.example.ui.theme.FloatingBarSurface
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LivingAuroraBackdrop
import com.example.ui.theme.NestedSurface
import com.example.ui.theme.SemanticCoral
import com.example.ui.theme.SemanticEmerald
import com.example.ui.theme.SpecularStroke
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.rememberLivingPaletteState
import com.example.ui.theme.withTabularNumbers
import com.example.updater.BridgeAppUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

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
    private val openUpdateSheetSignal = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        AppFilterManager.init(this)
        MediaBridgeStateHolder.init(this)
        LocalHttpServer.start()
        NotificationRebindHelper.rebindService(this)
        handleIncomingIntent(intent)

        setContent {
            ClawdBridgeTheme {
                ClawdBridgeMainApp(openUpdateSheetTrigger = openUpdateSheetSignal.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(BridgeAppUpdater.EXTRA_OPEN_UPDATE_SHEET, false) == true) {
            intent.removeExtra(BridgeAppUpdater.EXTRA_OPEN_UPDATE_SHEET)
            openUpdateSheetSignal.intValue += 1
        }
    }
}

@Composable
fun ClawdBridgeMainApp(openUpdateSheetTrigger: Int = 0) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    val uiScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { BRIDGE_TABS.size })
    var isSliderDragging by remember { mutableStateOf(false) }
    var isFullscreenPlayerOpen by remember { mutableStateOf(false) }
    var pendingUpdateSheet by remember { mutableStateOf(false) }

    LaunchedEffect(openUpdateSheetTrigger) {
        if (openUpdateSheetTrigger > 0) {
            pendingUpdateSheet = true
            isFullscreenPlayerOpen = false
            pagerState.animateScrollToPage(2)
        }
    }

    BackHandler(enabled = !isFullscreenPlayerOpen && pagerState.currentPage != 0) {
        uiScope.launch { pagerState.animateScrollToPage(0) }
    }

    val liveState by MediaBridgeStateHolder.liveState.collectAsStateWithLifecycle()
    val playerUiState by MediaBridgeStateHolder.playerUiState.collectAsStateWithLifecycle()
    val artworkBitmap by MediaBridgeStateHolder.artworkBitmap.collectAsStateWithLifecycle()
    val isServiceConnected by MediaBridgeStateHolder.isServiceConnected.collectAsStateWithLifecycle()
    val requestCount by MediaBridgeStateHolder.requestCount.collectAsStateWithLifecycle()
    val lastRequestTimestamp by MediaBridgeStateHolder.lastRequestTimestamp.collectAsStateWithLifecycle()
    val isServerRunning by LocalHttpServer.isRunning.collectAsStateWithLifecycle()
    val activeSseClients by LocalHttpServer.activeSseClients.collectAsStateWithLifecycle()
    val isSimulatorActive by TestMediaSimulator.isSimulatorActive.collectAsStateWithLifecycle()
    val isSimulatorPlaying by TestMediaSimulator.isPlaying.collectAsStateWithLifecycle()
    val backupWrittenTime by BackupFileWriter.lastWrittenTime.collectAsStateWithLifecycle()
    val backupFileSize by BackupFileWriter.lastFileSize.collectAsStateWithLifecycle()
    val backupStatus by BackupFileWriter.backupStatus.collectAsStateWithLifecycle()
    val isFilterEnabled by AppFilterManager.isFilterEnabled.collectAsStateWithLifecycle()
    val ignoredPackages by AppFilterManager.ignoredPackages.collectAsStateWithLifecycle()
    val discoveredApps by AppFilterManager.discoveredApps.collectAsStateWithLifecycle()
    val syncOffsetMs by MediaBridgeStateHolder.syncOffsetMs.collectAsStateWithLifecycle()
    val dynamicBackgroundEnabled by MediaBridgeStateHolder.dynamicBackgroundEnabled.collectAsStateWithLifecycle()
    val backgroundStyle by MediaBridgeStateHolder.backgroundStyle.collectAsStateWithLifecycle()
    val playerStyleConfig by MediaBridgeStateHolder.playerStyleConfig.collectAsStateWithLifecycle()
    val lyricsUiState by MediaBridgeStateHolder.lyricsUiState.collectAsStateWithLifecycle()
    val preferredLyricsProvider by MediaBridgeStateHolder.preferredLyricsProvider.collectAsStateWithLifecycle()
    val artworkDebugStatus by MediaBridgeStateHolder.artworkDebugStatus.collectAsStateWithLifecycle()

    LaunchedEffect(playerUiState.hasMedia) {
        if (!playerUiState.hasMedia) {
            isFullscreenPlayerOpen = false
        }
    }

    LaunchedEffect(isFullscreenPlayerOpen, playerUiState.artist, playerUiState.title) {
        if (isFullscreenPlayerOpen && playerUiState.hasMedia) {
            MediaBridgeStateHolder.loadLyricsForCurrentTrack()
        }
    }

    val hideRootBackdrop = isFullscreenPlayerOpen &&
        playerUiState.hasMedia &&
        playerStyleConfig.backgroundMode != com.example.state.PlayerBackgroundMode.GRADIENT

    val livingPalette = rememberLivingPaletteState(
        liveState = if (isFullscreenPlayerOpen && playerUiState.hasMedia) playerUiState else liveState,
        artworkBitmap = artworkBitmap,
        dynamicBackgroundEnabled = dynamicBackgroundEnabled,
        backgroundStyle = backgroundStyle,
        suppressBackgroundMotion = hideRootBackdrop
    )

    var isNotificationAccessGranted by remember {
        mutableStateOf(NotificationRebindHelper.isNotificationAccessGranted(context))
    }
    var isBatteryOptIgnored by remember { mutableStateOf(checkBatteryOptIgnored(context)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            MediaBridgeForegroundService.start(context, fromForegroundUi = true)
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
                MediaBridgeForegroundService.start(context, fromForegroundUi = true)
                NotificationRebindHelper.rebindService(context)
                MediaBridgeStateHolder.refreshSnapshot()
                BridgeAppUpdater.retryInstallIfPermitted(context)
                BridgeAppUpdater.checkIfIntervalElapsed(context)
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
        MediaBridgeForegroundService.start(context, fromForegroundUi = true)
        Toast.makeText(context, "Служба переподключена", Toast.LENGTH_SHORT).show()
    }

    val playerProgress by animateFloatAsState(
        targetValue = if (isFullscreenPlayerOpen && playerUiState.hasMedia) 1f else 0f,
        animationSpec = tween(240),
        label = "root_player_progress"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 1: Lowest base dark canvas (#060709) — sits UNDER LivingAuroraBackdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF060709))
        )

        // Layer 2: Dynamic Ambient Backdrop (shared across Home & GRADIENT Fullscreen Player)
        if (!hideRootBackdrop) {
            LivingAuroraBackdrop(palette = livingPalette)
        }

        // Layer 3: Main Scaffold fades smoothly out as FullscreenPlayer opens so nothing bleeds through
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = (1f - playerProgress).coerceIn(0f, 1f) }
                .testTag("main_scaffold"),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                BridgeTopAppBar(
                    isServerRunning = isServerRunning,
                    timeSecondsState = livingPalette.uiTimeSecondsState,
                    onCopyEndpoint = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Clawd Bridge Server", "http://127.0.0.1:44444"))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        Toast.makeText(context, "Скопировано: http://127.0.0.1:44444", Toast.LENGTH_SHORT).show()
                    },
                    onRestartServer = {
                        LocalHttpServer.start()
                        MediaBridgeForegroundService.start(context, fromForegroundUi = true)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        Toast.makeText(context, "Сервер перезапущен", Toast.LENGTH_SHORT).show()
                    }
                )
            },
            bottomBar = {
                FloatingGlassBottomBar(
                    selectedTab = pagerState.currentPage,
                    onSelectTab = { idx ->
                        uiScope.launch { pagerState.animateScrollToPage(idx) }
                    }
                )
            }
        ) { innerPadding ->
            // Progressive vertical edge fade under TopAppBar and floating BottomBar
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .progressiveVerticalEdgeFade()
            ) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 2,
                    userScrollEnabled = !isSliderDragging && !isFullscreenPlayerOpen,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_horizontal_pager")
                ) { pageIndex ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (pageIndex) {
                            0 -> MonitorTab(
                                liveState = liveState,
                                artworkBitmap = artworkBitmap,
                                livingPalette = livingPalette,
                                isServiceConnected = isServiceConnected,
                                isNotificationAccessGranted = isNotificationAccessGranted,
                                isSimulatorActive = isSimulatorActive,
                                isSimulatorPlaying = isSimulatorPlaying,
                                onForceRebind = forceRebindAction,
                                onOpenFullscreenPlayer = { isFullscreenPlayerOpen = true },
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
                                artworkBitmap = artworkBitmap,
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
                                dynamicBackgroundEnabled = dynamicBackgroundEnabled,
                                onToggleDynamicBackground = { MediaBridgeStateHolder.setDynamicBackgroundEnabled(it) },
                                backgroundStyle = backgroundStyle,
                                onBackgroundStyleChange = { MediaBridgeStateHolder.setBackgroundStyle(it) },
                                onSliderDragChange = { isSliderDragging = it },
                                onForceRebind = forceRebindAction,
                                openUpdateSheetRequested = pendingUpdateSheet,
                                onConsumeOpenUpdateSheet = { pendingUpdateSheet = false }
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }

        FullscreenPlayerOverlay(
            isOpen = isFullscreenPlayerOpen && playerUiState.hasMedia,
            context = context,
            track = playerUiState,
            artworkBitmap = artworkBitmap,
            livingPalette = livingPalette,
            styleConfig = playerStyleConfig,
            lyricsState = lyricsUiState,
            preferredLyricsProvider = preferredLyricsProvider,
            syncOffsetMs = syncOffsetMs,
            artworkDebugStatus = artworkDebugStatus,
            onDismiss = { isFullscreenPlayerOpen = false },
            onTogglePlayPause = { MediaBridgeStateHolder.togglePlayPause() },
            onSkipNext = { MediaBridgeStateHolder.skipToNext() },
            onSkipPrevious = { MediaBridgeStateHolder.skipToPrevious() },
            onSeekToRawMs = { rawMs -> MediaBridgeStateHolder.seekToPosition(rawMs) },
            onSeekByDelta = { deltaMs -> MediaBridgeStateHolder.seekByDelta(deltaMs) },
            onSyncOffsetChange = { offset -> MediaBridgeStateHolder.setSyncOffsetMs(offset) },
            onUpdateStyle = { transform -> MediaBridgeStateHolder.updatePlayerStyle(transform) },
            onRefreshLyrics = { providerId ->
                MediaBridgeStateHolder.loadLyricsForCurrentTrack(
                    forceRefresh = true,
                    preferredProvider = providerId
                )
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BridgeTopAppBar(
    isServerRunning: Boolean,
    timeSecondsState: State<Float>,
    onCopyEndpoint: () -> Unit,
    onRestartServer: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Clawd Bridge",
                style = MaterialTheme.typography.headlineMedium.copy(color = TextPrimary)
            )
            Text(
                text = "Media Sync & SSE",
                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
            )
        }

        // Strict Semantic Emerald dot + Port capsule (tap copies http://127.0.0.1:44444)
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = NestedSurface,
            border = BorderStroke(0.75.dp, SpecularStroke),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .combinedClickable(
                    onClick = onCopyEndpoint,
                    onLongClick = onRestartServer
                )
                .testTag("server_port_pill")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer {
                            alpha = if (isServerRunning) {
                                0.55f + 0.45f * ((sin(timeSecondsState.value * 3.6f) + 1f) * 0.5f)
                            } else {
                                1f
                            }
                        }
                        .clip(CircleShape)
                        .background(if (isServerRunning) SemanticEmerald else SemanticCoral)
                )
                Text(
                    text = if (isServerRunning) "44444" else "OFFLINE",
                    style = MaterialTheme.typography.labelMedium.withTabularNumbers().copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isServerRunning) TextPrimary else SemanticCoral
                    )
                )
            }
        }
    }
}

@Composable
private fun FloatingGlassBottomBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = FloatingBarSurface,
            border = BorderStroke(0.75.dp, SpecularStroke),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BRIDGE_TABS.forEach { tab ->
                    val selected = selectedTab == tab.index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (selected) ActivePillSurface else Color.Transparent)
                            .clickable { onSelectTab(tab.index) }
                            .testTag(tab.testTag),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label,
                                tint = if (selected) TextPrimary else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (selected) TextPrimary else TextMuted,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.progressiveVerticalEdgeFade(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.025f to Color.Black,
                0.96f to Color.Black,
                1.0f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }

private fun checkBatteryOptIgnored(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}
