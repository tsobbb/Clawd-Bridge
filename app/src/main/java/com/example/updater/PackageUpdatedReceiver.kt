package com.example.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.server.LocalHttpServer
import com.example.service.MediaBridgeForegroundService
import com.example.service.NotificationRebindHelper

/**
 * Automatically brings Clawd Bridge foreground server and NotificationListenerService back up
 * right after an in-app APK replacement finishes (MY_PACKAGE_REPLACED).
 */
class PackageUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "MY_PACKAGE_REPLACED received — restarting bridge service and rebinding listener")
        BridgeAppUpdater.onPackageReplaced(context)
        LocalHttpServer.start()
        MediaBridgeForegroundService.start(context, fromForegroundUi = false)
        NotificationRebindHelper.rebindService(context)
    }

    companion object {
        private const val TAG = "PackageUpdatedReceiver"
    }
}
