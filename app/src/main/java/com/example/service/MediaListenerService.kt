package com.example.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import com.example.filter.AppFilterManager
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

        MediaBridgeStateHolder.setActiveController(playingController, forcePush = true)
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
