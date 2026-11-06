package com.example.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Helper to manage connection health and automatic rebind for NotificationListenerService.
 * Resolves the "zombie IPC" state when the app is swiped away from Recents without
 * requiring the user to manually toggle system settings.
 */
object NotificationRebindHelper {

    private const val TAG = "NotificationRebind"

    /**
     * Checks if notification access permission is enabled in Android system settings.
     */
    fun isNotificationAccessGranted(context: Context): Boolean {
        return try {
            val pkgName = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            if (!flat.isNullOrEmpty()) {
                val names = flat.split(":")
                for (name in names) {
                    val cn = ComponentName.unflattenFromString(name)
                    if (cn != null && cn.packageName == pkgName) {
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking notification access: ${e.message}")
            false
        }
    }

    /**
     * Programmatically rebinds the NotificationListenerService.
     * Uses API 24+ requestRebind first, and automatically falls back to the
     * PackageManager component toggle trick if needed.
     */
    fun rebindService(context: Context) {
        if (!isNotificationAccessGranted(context)) {
            Log.d(TAG, "Notification access not enabled in settings; skipping rebind")
            return
        }

        val componentName = ComponentName(context, MediaListenerService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Log.i(TAG, "Requesting rebind via NotificationListenerService.requestRebind")
                NotificationListenerService.requestRebind(componentName)
            } catch (e: Exception) {
                Log.w(TAG, "requestRebind failed (${e.message}), executing forceRebind fallback")
                forceRebindService(context)
            }
        } else {
            forceRebindService(context)
        }
    }

    /**
     * Universal fallback: toggles the component enabled state via PackageManager.
     * This forces Android OS to tear down any stale dead Binder descriptors and
     * re-bind the NotificationListenerService immediately.
     */
    fun forceRebindService(context: Context) {
        if (!isNotificationAccessGranted(context)) {
            return
        }

        try {
            Log.i(TAG, "Performing forceRebind via PackageManager toggle...")
            val component = ComponentName(context, MediaListenerService::class.java)
            val pm = context.packageManager

            // Step 1: Simulate disabling component
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )

            // Step 2: Simulate re-enabling component
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "forceRebind cycle completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during forceRebind: ${e.message}", e)
        }
    }
}
