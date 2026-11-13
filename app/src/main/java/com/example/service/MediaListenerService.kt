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

private data class NotificationArtExtract(
    val bitmap: Bitmap?,
    val uriFallback: String?,
    val notifTitle: String,
    val debugNote: String
)

/**
 * Background notification and media listener service.
 * Intercepts active MediaSessions from Spotify, SoundCloud, YouTube Music, VK, etc.,
 * extracts notification album artwork hints & URI fallbacks (when players omit inline Metadata bitmaps),
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

        val probe = ArtworkExtractor.probeBitmapFromNotification(this, sbn.notification)
        if (probe.bitmap == null && probe.uriFallback == null) return
        val notifTitle = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        MediaBridgeStateHolder.updateNotificationArtwork(
            packageName = pkg,
            notifTitle = notifTitle,
            bitmap = probe.bitmap,
            uriFallback = probe.uriFallback,
            debugNote = probe.debugNote
        )
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

        val notifArt = extractNotificationArtForController(playingController)
        MediaBridgeStateHolder.setActiveController(
            controller = playingController,
            forcePush = true,
            notificationArtBitmap = notifArt.bitmap,
            notificationArtUri = notifArt.uriFallback,
            notificationTitle = notifArt.notifTitle,
            notificationDebugNote = notifArt.debugNote
        )
    }

    private fun extractNotificationArtForController(controller: MediaController?): NotificationArtExtract {
        if (controller == null) return NotificationArtExtract(null, null, "", "no_ctrl")
        val pkg = controller.packageName ?: return NotificationArtExtract(null, null, "", "no_pkg")
        val notifications = try {
            activeNotifications
        } catch (t: Throwable) {
            return NotificationArtExtract(null, null, "", "activeNotif:${t.javaClass.simpleName}")
        } ?: return NotificationArtExtract(null, null, "", "activeNotif=null")

        val targetToken = controller.sessionToken

        val exactMatch = notifications.firstOrNull { sbn ->
            sbn.packageName == pkg && getMediaSessionToken(sbn.notification) == targetToken
        }
        if (exactMatch != null) {
            val probe = ArtworkExtractor.probeBitmapFromNotification(this, exactMatch.notification)
            val title = exactMatch.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            return NotificationArtExtract(probe.bitmap, probe.uriFallback, title, probe.debugNote)
        }

        val pkgMatches = notifications.filter { it.packageName == pkg }
            .sortedByDescending { if (isMediaStyleNotification(it.notification)) 1 else 0 }

        if (pkgMatches.isEmpty()) {
            return NotificationArtExtract(null, null, "", "no_sbn_for($pkg, total=${notifications.size})")
        }

        var lastNote = "sbn_no_art"
        var bestUri: String? = null
        var bestTitle = ""
        for (sbn in pkgMatches) {
            val probe = ArtworkExtractor.probeBitmapFromNotification(this, sbn.notification)
            val title = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            if (probe.bitmap != null) {
                return NotificationArtExtract(probe.bitmap, null, title, probe.debugNote)
            }
            if (probe.uriFallback != null && bestUri == null) {
                bestUri = probe.uriFallback
                bestTitle = title
            }
            lastNote = probe.debugNote
        }

        return NotificationArtExtract(null, bestUri, bestTitle, lastNote)
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
