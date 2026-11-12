package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.server.LocalHttpServer
import com.example.state.MediaBridgeStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service that keeps Clawd Bridge alive in high-priority OOM state,
 * preventing Android OS (HyperOS / MIUI / Samsung OneUI) from terminating the process
 * and losing the NotificationListenerService Binder connection when swiped from Recents.
 */
class MediaBridgeForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MediaBridgeForegroundService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "MediaBridgeForegroundService onStartCommand (START_STICKY)")

        // 1. Promote to Foreground Service
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Ensure Local HTTP server is running
        LocalHttpServer.start()

        // 3. Trigger initial rebind check
        NotificationRebindHelper.rebindService(this)

        // 4. Start active watchdog for auto-recovery from zombie states
        startWatchdog()

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App swiped from Recents. Maintaining foreground service & requesting rebind...")
        NotificationRebindHelper.rebindService(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MediaBridgeForegroundService destroyed")
        watchdogJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Постоянное тихое уведомление фонового моста Clawd Bridge"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Clawd Bridge")
            .setContentText("Служба медиа-моста активна (порт 44444)")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return

        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val hasPermission = NotificationRebindHelper.isNotificationAccessGranted(this@MediaBridgeForegroundService)
                val isConnected = MediaBridgeStateHolder.isServiceConnected.value

                // If permission is granted in settings, but IPC listener is disconnected -> auto force rebind!
                if (hasPermission && !isConnected) {
                    Log.w(TAG, "Watchdog detected disconnected listener with granted permission. Auto-recovering...")
                    NotificationRebindHelper.forceRebindService(this@MediaBridgeForegroundService)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ForegroundService"
        private const val CHANNEL_ID = "clawd_bridge_foreground_channel"
        private const val CHANNEL_NAME = "Clawd Bridge Background Sync"
        private const val NOTIFICATION_ID = 44444
        private const val WATCHDOG_INTERVAL_MS = 10000L

        fun start(context: Context) {
            try {
                val intent = Intent(context, MediaBridgeForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MediaBridgeForegroundService: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, MediaBridgeForegroundService::class.java)
                context.stopService(intent)
            } catch (ignored: Exception) {}
        }
    }
}
