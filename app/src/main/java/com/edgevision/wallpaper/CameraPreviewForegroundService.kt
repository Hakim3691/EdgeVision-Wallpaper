package com.edgevision.wallpaper

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.annotation.RequiresApi
import android.os.Build

/** Foreground service that displays the camera preview as an overlay over other apps. */
@RequiresApi(Build.VERSION_CODES.O)
class CameraPreviewForegroundService : Service() {

    public inner class LocalBinder : android.os.Binder() {
        val service: CameraPreviewForegroundService = this@CameraPreviewForegroundService
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return LocalBinder()
    }
}