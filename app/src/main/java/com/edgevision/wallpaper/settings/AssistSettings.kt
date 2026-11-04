package com.edgevision.wallpaper.settings

import android.content.Context
import kotlin.math.roundToInt

class AssistSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    var blindAssist: Boolean
        get() = prefs.getBoolean(KEY_BLIND_ASSIST, false)
        set(value) {
            prefs.edit().putBoolean(KEY_BLIND_ASSIST, value).apply()
        }

    var audibleAlerts: Boolean
        get() = prefs.getBoolean(KEY_AUDIBLE_ALERTS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUDIBLE_ALERTS, value).apply()
        }

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HAPTICS, value).apply()
        }

    var overlayOpacityPercent: Int
        get() = prefs.getInt(KEY_OVERLAY_OPACITY, DEFAULT_OVERLAY_OPACITY)
            .coerceIn(MIN_OVERLAY_OPACITY, MAX_OVERLAY_OPACITY)
        set(value) {
            prefs.edit()
                .putInt(KEY_OVERLAY_OPACITY, value.coerceIn(MIN_OVERLAY_OPACITY, MAX_OVERLAY_OPACITY))
                .apply()
        }

    val overlayAlpha: Int
        get() = (overlayOpacityPercent / 100f * 255f).roundToInt().coerceIn(0, 255)

    companion object {
        const val PREFS_NAME = "edge_vision_settings"
        const val KEY_BLIND_ASSIST = "blind_assist"
        const val KEY_AUDIBLE_ALERTS = "audible_alerts"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_OVERLAY_OPACITY = "overlay_opacity"

        const val MIN_OVERLAY_OPACITY = 20
        const val MAX_OVERLAY_OPACITY = 100
        const val DEFAULT_OVERLAY_OPACITY = 65
    }
}
