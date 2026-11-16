package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.example.filter.AppFilterManager
import com.example.server.LocalHttpServer
import com.example.service.NotificationRebindHelper
import com.example.state.MediaBridgeStateHolder
import java.util.concurrent.atomic.AtomicInteger

class ClawdBridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("ClawdBridgeApp", "Initializing Clawd Bridge...")

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                startedActivitiesCount.incrementAndGet()
            }
            override fun onActivityResumed(activity: Activity) {
                if (startedActivitiesCount.get() <= 0) {
                    startedActivitiesCount.set(1)
                }
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                val next = startedActivitiesCount.decrementAndGet()
                if (next < 0) startedActivitiesCount.set(0)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // 0. Initialize ApplicationContext singletons
        AppFilterManager.init(this)
        MediaBridgeStateHolder.init(this)

        // 1. Start zero-overhead concurrent HTTP/SSE server
        LocalHttpServer.start()

        // 2. Rebind NotificationListenerService if access is already granted
        // Note: ForegroundService is promoted cleanly from MainActivity foreground UI
        // so background wakes on Android 12+ never throw ForegroundServiceStartNotAllowedException.
        NotificationRebindHelper.rebindService(this)
    }

    companion object {
        private val startedActivitiesCount = AtomicInteger(0)

        val isForegroundUiActive: Boolean
            get() = startedActivitiesCount.get() > 0
    }
}
