package com.example

import android.app.Application
import android.util.Log
import com.example.server.LocalHttpServer

class ClawdBridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("ClawdBridgeApp", "Initializing Clawd Bridge...")
        LocalHttpServer.start()
    }
}
