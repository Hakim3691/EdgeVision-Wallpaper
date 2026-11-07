package com.edgevision.wallpaper.wallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Reports display interactivity and keyguard state.
 *
 * The previous revision resumed the camera on `ACTION_SCREEN_ON`, which fires for
 * the lock screen *before* the user unlocks. That immediately took the camera back
 * from face unlock. This monitor exposes the facts instead, and the lifecycle gate
 * only runs the camera when the screen is interactive **and** the keyguard is gone.
 */
class ScreenStateMonitor(
    private val context: Context,
    private val onStateChanged: (interactive: Boolean, keyguardLocked: Boolean) -> Unit,
) {

    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            emit()
        }
    }

    fun start() {
        if (!registered) {
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
        emit()
    }

    /** Re-reads the current state, e.g. when the wallpaper becomes visible. */
    fun refresh() = emit()

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
    }

    private fun emit() {
        val interactive = power?.isInteractive ?: true
        val locked = keyguard?.isKeyguardLocked == true
        onStateChanged(interactive, locked)
    }
}
