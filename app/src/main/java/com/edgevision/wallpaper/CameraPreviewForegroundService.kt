package com.edgevision.wallpaper

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.edgevision.wallpaper.wallpaper.WallpaperEngineController

/** Foreground service that displays the camera preview as an overlay over other apps.
 *  The overlay visibility is dynamically toggled based on whether the user is on the
 *  home screen (Live Wallpaper visible) or using another app.
 */
@RequiresApi(Build.VERSION_CODES.O)
class CameraPreviewForegroundService : Service() {

    public inner class LocalBinder : android.os.Binder() {
        val service: CameraPreviewForegroundService = this@CameraPreviewForegroundService
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var visible = false

    override fun onCreate() {
        super.onCreate()
        // Register for visibility change broadcasts from the WallpaperService
        val filter = IntentFilter(OverlayVisibilityManager.ACTION_VISIBILITY_CHANGED)
        registerReceiver(visibilityReceiver, filter)
        // Initialize overlay view hidden
        visible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(visibilityReceiver)
        removeOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = LocalBinder()

    // -----------------------------------------------------------------------
    // Overlay View (WindowManager)
    // -----------------------------------------------------------------------

    private fun initOverlay() {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_camera_preview, null)

        overlayView?.setOnTouchListener { _, _ -> true }

        windowManager?.apply {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.START or Gravity.TOP
            params.x = 0
            params.y = 0

            addView(overlayView, params)
        }
        Log.d("CameraPreviewForegroundService", "Overlay view initialized")
    }

    private fun removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: IllegalArgumentException) {
                // ignore
            }
        }
        overlayView = null
        windowManager = null
        Log.d("CameraPreviewForegroundService", "Overlay view removed")
    }

    private fun hideOverlay() {
        overlayView?.visibility = View.GONE
        visible = false
    }

    private fun showOverlay() {
        if (overlayView == null) initOverlay()
        if (overlayView != null) {
            overlayView?.visibility = View.VISIBLE
            visible = true
        }
    }

    // -----------------------------------------------------------------------
    // Broadcast receiver for visibility changes from WallpaperService
    // -----------------------------------------------------------------------

    private val visibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val newVisible = intent?.getBooleanExtra(OverlayVisibilityManager.EXTRA_VISIBLE, false) ?: false
            if (newVisible) {
                showOverlay()
            } else {
                hideOverlay()
            }
            visible = newVisible
        }
    }

    // -----------------------------------------------------------------------
    // Foreground service lifecycle
    // -----------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restore visibility state from shared manager
        visible = OverlayVisibilityManager.visible
        if (visible) {
            showOverlay()
        } else {
            hideOverlay()
        }
        return START_STICKY
    }

    // -----------------------------------------------------------------------
    // Camera resource handoff from WallpaperService
    // -----------------------------------------------------------------------

    /** Called by the WallpaperEngineController when the camera should be handed off.
     *  When the user is on the home screen (wallpaper visible), the foreground overlay
     *  should be hidden and the WallpaperService owns the camera.
     *  When the user leaves the home screen, the foreground service should take over.
     */
    fun handoffCamera(wallpaperHasCamera: Boolean) {
        if (wallpaperHasCamera && visible) {
            hideOverlay()
            Log.d("CameraPreviewForegroundService", "Camera handed off to WallpaperService (on home screen)")
        } else if (!wallpaperHasCamera && !visible) {
            Log.d("CameraPreviewForegroundService", "Camera handoff needed - user left home screen")
            // TODO: Request camera permission and open camera if needed for overlay preview
        } else if (!wallpaperHasCamera && visible) {
            hideOverlay()
        }
    }

    /** Update the internal visibility flag without broadcasting. */
    internal fun setVisibleInternal(visible: Boolean) {
        this.visible = visible
        if (visible) {
            showOverlay()
        } else {
            hideOverlay()
        }
    }
}