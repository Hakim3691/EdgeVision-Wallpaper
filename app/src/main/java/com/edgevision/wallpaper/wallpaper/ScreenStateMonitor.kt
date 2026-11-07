package com.edgevision.wallpaper.wallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Reports display interactivity and keyguard state.
 *
 * `ACTION_USER_PRESENT` is not guaranteed on every unlock path: face unlock,
 * "lift to wake" without a secure keyguard, and OEM skins can dismiss the keyguard
 * without delivering it to a runtime receiver. Relying on that single broadcast is
 * what left the wallpaper showing one frozen frame after unlock.
 *
 * Broadcasts are still the fast path, and a low-frequency poll is the safety net:
 * it re-reads the real system state and only reports changes, so a missed event
 * recovers within [POLL_INTERVAL_MS] instead of never.
 */
class ScreenStateMonitor(
    private val context: Context,
    private val onStateChanged: (interactive: Boolean, keyguardLocked: Boolean) -> Unit,
) {

    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var lastInteractive: Boolean? = null
    private var lastLocked: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            emit()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!registered) return
            emit()
            handler.postDelayed(this, POLL_INTERVAL_MS)
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
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    /** Re-reads the current state, e.g. when the wallpaper becomes visible. */
    fun refresh() = emit()

    fun stop() {
        if (!registered) return
        registered = false
        handler.removeCallbacks(poll)
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
    }

    private fun emit() {
        val interactive = power?.isInteractive ?: true
        val locked = keyguard?.isKeyguardLocked == true
        if (interactive == lastInteractive && locked == lastLocked) return
        lastInteractive = interactive
        lastLocked = locked
        onStateChanged(interactive, locked)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_500L
    }
}
