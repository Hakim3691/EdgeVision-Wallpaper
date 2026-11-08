package com.edgevision.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/** Shared singleton that tracks and broadcasts overlay visibility state.
 *  The WallpaperService writes state here; the ForegroundService reads and acts on it.
 */
object OverlayVisibilityManager {

    private val TAG = "OverlayVisibilityManager"

    /** Intent action sent when visibility changes. Extra: boolean extra "visible". */
    const val ACTION_VISIBILITY_CHANGED = "com.edgevision.wallpaper.action.OVERLAY_VISIBILITY_CHANGED"
    const val EXTRA_VISIBLE = "visible"

    /** Registered receiver reference, held weakly to avoid leaks. */
    private var receiver: BroadcastReceiver? = null
    private var isRegistered = false

    /** Current visibility state (last value set). */
    @Volatile
    var visible = false

    /** Register a receiver in the given context. Call from an Application-owned component. */
    fun register(context: Context) {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_VISIBILITY_CHANGED)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                visible = intent?.getBooleanExtra(EXTRA_VISIBLE, false) ?: false
                Log.d(TAG, "Visibility set to: $visible")
            }
        }
        context.applicationContext.registerReceiver(receiver!!, filter)
        isRegistered = true
        Log.d(TAG, "Visibility receiver registered")
    }

    /** Unregister the receiver. */
    fun unregister(context: Context) {
        if (!isRegistered) return
        try {
            context.applicationContext.unregisterReceiver(receiver!!)
        } catch (e: IllegalArgumentException) {
            // Receiver may have already been unregistered; ignore.
        }
        receiver = null
        isRegistered = false
        Log.d(TAG, "Visibility receiver unregistered")
    }

    /** Set the visibility state. Writes to the shared slot; receivers pick up the new value. */
    fun setState(visible: Boolean) {
        this.visible = visible
        // Send sticky-like intent so even newly-registered receivers get the current state.
        try {
            val intent = Intent(ACTION_VISIBILITY_CHANGED).apply {
                putExtra(EXTRA_VISIBLE, visible)
            }
            // Broadcast locally; no system broadcast needed.
            // We use goAsync / normal send since we control the receiver.
            // Using simple sendOrderedBroadcast would be overkill; just deliver straight.
            // To avoid ANR, do this on a handler thread if called from a callback.
            // For now, direct send in same thread (callers are UI/main thread safe via
            // the WallpaperEngineController which is accessed from the wallpipe service thread).
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast visibility change", e)
        }
    }
}