package com.example

import android.app.Application
import android.util.Log
import com.example.filter.AppFilterManager
import com.example.server.LocalHttpServer
import com.example.service.MediaBridgeForegroundService
import com.example.service.NotificationRebindHelper

class ClawdBridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("ClawdBridgeApp", "Initializing Clawd Bridge...")

        // 0. Initialize optional App Filter (disabled and empty by default)
        AppFilterManager.init(this)

        // 1. Start zero-overhead concurrent HTTP/SSE server
        LocalHttpServer.start()

        // 2. Start Foreground Service to maintain high process priority against OOM kills
        MediaBridgeForegroundService.start(this)

        // 3. Rebind NotificationListenerService if access is already granted
        NotificationRebindHelper.rebindService(this)
    }
}
