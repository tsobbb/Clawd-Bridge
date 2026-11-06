package com.example.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import com.example.state.MediaBridgeStateHolder

/**
 * Background notification and media listener service.
 * Intercepts active MediaSessions from Spotify, SoundCloud, YouTube Music, VK, etc.
 */
class MediaListenerService : NotificationListenerService() {

    private companion object {
        const val TAG = "MediaListenerService"
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var componentName: ComponentName? = null
    private val activeControllers = mutableListOf<MediaController>()

    private val callbackMap = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.d(TAG, "Active media sessions changed: ${controllers?.size ?: 0} controller(s)")
        updateControllers(controllers ?: emptyList())
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MediaListenerService created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected!")
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
        Log.i(TAG, "Notification listener disconnected")
        MediaBridgeStateHolder.setServiceConnected(false)
        unregisterAllCallbacks()
    }

    override fun onDestroy() {
        super.onDestroy()
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
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    Log.d(TAG, "PlaybackState changed for ${controller.packageName}: ${state?.state}")
                    evaluateActiveSession()
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    Log.d(TAG, "Metadata changed for ${controller.packageName}")
                    evaluateActiveSession()
                }

                override fun onSessionDestroyed() {
                    Log.d(TAG, "Session destroyed for ${controller.packageName}")
                    activeControllers.remove(controller)
                    callbackMap.remove(controller)
                    evaluateActiveSession()
                }
            }
            callbackMap[controller] = callback
            try {
                controller.registerCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register callback for ${controller.packageName}: ${e.message}")
            }
        }

        evaluateActiveSession()
    }

    private fun evaluateActiveSession() {
        // Find the currently playing controller, or the most recent active one
        var playingController: MediaController? = null

        for (controller in activeControllers) {
            try {
                val state = controller.playbackState
                if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    playingController = controller
                    break
                }
            } catch (ignored: Exception) {}
        }

        if (playingController == null && activeControllers.isNotEmpty()) {
            // Check if any controller has media metadata even if paused
            for (controller in activeControllers) {
                try {
                    if (controller.playbackState != null) {
                        playingController = controller
                        break
                    }
                } catch (ignored: Exception) {}
            }
        }

        MediaBridgeStateHolder.setActiveController(playingController)
    }

    private fun unregisterAllCallbacks() {
        for ((controller, callback) in callbackMap) {
            try {
                controller.unregisterCallback(callback)
            } catch (ignored: Exception) {}
        }
        callbackMap.clear()
    }
}
