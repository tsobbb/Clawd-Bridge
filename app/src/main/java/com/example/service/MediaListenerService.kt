package com.example.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.filter.AppFilterManager
import com.example.media.ArtworkExtractor
import com.example.state.MediaBridgeStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Background notification and media listener service.
 * Intercepts active MediaSessions from Spotify, SoundCloud, YouTube Music, VK, etc.,
 * extracts notification album artwork hints (when players omit inline Metadata bitmaps),
 * tracks seen media apps for the optional blacklist filter, and reacts instantaneously
 * to filter changes.
 */
class MediaListenerService : NotificationListenerService() {

    private companion object {
        const val TAG = "MediaListenerService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var mediaSessionManager: MediaSessionManager? = null
    private var componentName: ComponentName? = null
    private val activeControllers = CopyOnWriteArrayList<MediaController>()
    private val callbackMap = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d(TAG, "Active media sessions changed: ${controllers?.size ?: 0} controller(s)")
        updateControllers(controllers ?: emptyList())
    }

    override fun onCreate() {
        super.onCreate()
        AppFilterManager.init(this)
        MediaBridgeStateHolder.init(this)
        serviceScope.launch {
            combine(
                AppFilterManager.isFilterEnabled,
                AppFilterManager.ignoredPackages
            ) { _, _ -> Unit }.collect {
                if (activeControllers.isNotEmpty()) {
                    evaluateActiveSession()
                }
            }
        }
        Log.i(TAG, "MediaListenerService created")
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "MediaListenerService onStartCommand (START_STICKY)")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed in MediaListenerService. Requesting rebind...")
        NotificationRebindHelper.rebindService(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected!")
        AppFilterManager.init(this)
        MediaBridgeStateHolder.init(this)
        MediaBridgeStateHolder.setServiceConnected(true)

        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            componentName = ComponentName(this, MediaListenerService::class.java)

            mediaSessionManager?.let { mgr ->
                componentName?.let { comp ->
                    mgr.addOnActiveSessionsChangedListener(sessionsListener, comp)
                    val initialControllers = try {
                        mgr.getActiveSessions(comp)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Permission needed for getActiveSessions: ${e.message}")
                        emptyList()
                    }
                    updateControllers(initialControllers)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing session listener: ${e.message}", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val pkg = sbn.packageName ?: return
        val isTrackedMediaApp = activeControllers.any { it.packageName == pkg }
        val isMediaNotification = isMediaStyleNotification(sbn.notification)
        if (!isTrackedMediaApp && !isMediaNotification) return

        val bitmap = ArtworkExtractor.extractBitmapFromNotification(this, sbn.notification) ?: return
        val notifTitle = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        MediaBridgeStateHolder.updateNotificationArtwork(pkg, notifTitle, bitmap)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected. Attempting auto-rebind...")
        MediaBridgeStateHolder.setServiceConnected(false)
        unregisterAllCallbacks()
        NotificationRebindHelper.rebindService(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        unregisterAllCallbacks()
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (ignored: Exception) {}
        MediaBridgeStateHolder.setServiceConnected(false)
    }

    private fun updateControllers(controllers: List<MediaController>) {
        unregisterAllCallbacks()
        activeControllers.clear()
        activeControllers.addAll(controllers)

        for (controller in controllers) {
            AppFilterManager.recordSeenPackage(this, controller.packageName)
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    Log.d(TAG, "PlaybackState changed for ${controller.packageName}: ${state?.state}")
                    AppFilterManager.recordSeenPackage(this@MediaListenerService, controller.packageName)
                    evaluateActiveSession()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    Log.d(TAG, "Metadata changed for ${controller.packageName}")
                    AppFilterManager.recordSeenPackage(this@MediaListenerService, controller.packageName)
                    evaluateActiveSession()
                }

                override fun onSessionDestroyed() {
                    Log.d(TAG, "Session destroyed for ${controller.packageName}")
                    activeControllers.remove(controller)
                    synchronized(callbackMap) {
                        callbackMap.remove(controller)
                    }
                    evaluateActiveSession()
                }
            }
            synchronized(callbackMap) {
                callbackMap[controller] = callback
            }
            try {
                controller.registerCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register callback for ${controller.packageName}: ${e.message}")
            }
        }

        evaluateActiveSession()
    }

    private fun evaluateActiveSession() {
        var playingController: MediaController? = null

        for (controller in activeControllers) {
            try {
                AppFilterManager.recordSeenPackage(this, controller.packageName)
                if (AppFilterManager.shouldIgnore(controller.packageName)) continue
                val state = controller.playbackState
                if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    playingController = controller
                    break
                }
            } catch (ignored: Exception) {}
        }

        if (playingController == null && activeControllers.isNotEmpty()) {
            for (controller in activeControllers) {
                try {
                    if (AppFilterManager.shouldIgnore(controller.packageName)) continue
                    if (controller.playbackState != null) {
                        playingController = controller
                        break
                    }
                } catch (ignored: Exception) {}
            }
        }

        val (notifBitmap, notifTitle) = extractNotificationArtForController(playingController)
        MediaBridgeStateHolder.setActiveController(
            controller = playingController,
            forcePush = true,
            notificationArtBitmap = notifBitmap,
            notificationTitle = notifTitle
        )
    }

    private fun extractNotificationArtForController(controller: MediaController?): Pair<Bitmap?, String> {
        if (controller == null) return Pair(null, "")
        val pkg = controller.packageName ?: return Pair(null, "")
        val notifications = try {
            activeNotifications
        } catch (_: Throwable) {
            null
        } ?: return Pair(null, "")

        val targetToken = controller.sessionToken

        // Pass 1: exact EXTRA_MEDIA_SESSION token match
        val exactMatch = notifications.firstOrNull { sbn ->
            sbn.packageName == pkg && getMediaSessionToken(sbn.notification) == targetToken
        }
        if (exactMatch != null) {
            val bmp = ArtworkExtractor.extractBitmapFromNotification(this, exactMatch.notification)
            val title = exactMatch.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            if (bmp != null) return Pair(bmp, title)
        }

        // Pass 2: package match with MediaStyle / TRANSPORT category or large icon
        val pkgMatches = notifications.filter { it.packageName == pkg }
            .sortedByDescending { if (isMediaStyleNotification(it.notification)) 1 else 0 }

        for (sbn in pkgMatches) {
            val bmp = ArtworkExtractor.extractBitmapFromNotification(this, sbn.notification)
            if (bmp != null) {
                val title = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                return Pair(bmp, title)
            }
        }

        return Pair(null, "")
    }

    private fun isMediaStyleNotification(notification: Notification?): Boolean {
        if (notification == null) return false
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        val extras = notification.extras ?: return false
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        return template.contains("MediaStyle", ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun getMediaSessionToken(notification: Notification?): MediaSession.Token? {
        val extras = notification?.extras ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun unregisterAllCallbacks() {
        synchronized(callbackMap) {
            for ((controller, callback) in callbackMap) {
                try {
                    controller.unregisterCallback(callback)
                } catch (ignored: Exception) {}
            }
            callbackMap.clear()
        }
    }
}
