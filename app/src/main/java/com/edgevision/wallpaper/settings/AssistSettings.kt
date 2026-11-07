package com.edgevision.wallpaper.settings

import android.content.Context
import android.content.SharedPreferences
import com.edgevision.wallpaper.diagnostics.PerformanceMode
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

    /**
     * Master switch for automatic burst capture.
     *
     * This is the user's choice and only the settings screen may change it. The
     * wallpaper engine keeps its runtime arming state separately, so hiding the
     * wallpaper, opening the app drawer or letting a triple-tap window expire can
     * no longer rewrite the stored preference.
     */
    var captureEnabled: Boolean
        get() = prefs.getBoolean(
            KEY_CAPTURE_ENABLED,
            // Migrate the pre-1.3.3 value, which conflated preference and runtime state.
            prefs.getBoolean(KEY_CAPTURE_ARMED, false),
        )
        set(value) {
            prefs.edit().putBoolean(KEY_CAPTURE_ENABLED, value).apply()
        }

    @Deprecated("Use captureEnabled; runtime arming lives in the wallpaper engine")
    var captureArmed: Boolean
        get() = captureEnabled
        set(value) {
            captureEnabled = value
        }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** Images per burst, clamped to the range the capture controller accepts. */
    var burstShots: Int
        get() = prefs.getInt(KEY_BURST_SHOTS, DEFAULT_BURST_SHOTS)
            .coerceIn(MIN_BURST_SHOTS, MAX_BURST_SHOTS)
        set(value) {
            prefs.edit()
                .putInt(KEY_BURST_SHOTS, value.coerceIn(MIN_BURST_SHOTS, MAX_BURST_SHOTS))
                .apply()
        }

    var burstIntervalMs: Long
        get() = prefs.getLong(KEY_BURST_INTERVAL_MS, DEFAULT_BURST_INTERVAL_MS)
            .coerceIn(MIN_BURST_INTERVAL_MS, MAX_BURST_INTERVAL_MS)
        set(value) {
            prefs.edit()
                .putLong(KEY_BURST_INTERVAL_MS, value.coerceIn(MIN_BURST_INTERVAL_MS, MAX_BURST_INTERVAL_MS))
                .apply()
        }

    /** Minimum gap between bursts, so one approach cannot fill storage. */
    var captureCooldownMs: Long
        get() = prefs.getLong(KEY_CAPTURE_COOLDOWN_MS, DEFAULT_CAPTURE_COOLDOWN_MS)
            .coerceIn(MIN_CAPTURE_COOLDOWN_MS, MAX_CAPTURE_COOLDOWN_MS)
        set(value) {
            prefs.edit()
                .putLong(KEY_CAPTURE_COOLDOWN_MS, value.coerceIn(MIN_CAPTURE_COOLDOWN_MS, MAX_CAPTURE_COOLDOWN_MS))
                .apply()
        }

    /** Stored by name so an unknown value from a newer build degrades safely. */
    var performanceMode: PerformanceMode
        get() = PerformanceMode.entries
            .firstOrNull { it.name == prefs.getString(KEY_PERFORMANCE_MODE, null) }
            ?: PerformanceMode.AUTOMATIC
        set(value) {
            prefs.edit().putString(KEY_PERFORMANCE_MODE, value.name).apply()
        }

    companion object {
        const val PREFS_NAME = "edge_vision_settings"
        const val KEY_BLIND_ASSIST = "blind_assist"
        const val KEY_AUDIBLE_ALERTS = "audible_alerts"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_OVERLAY_OPACITY = "overlay_opacity"
        const val KEY_CAPTURE_ARMED = "capture_armed"
        const val KEY_CAPTURE_ENABLED = "capture_enabled"
        const val KEY_BURST_SHOTS = "burst_shots"
        const val KEY_BURST_INTERVAL_MS = "burst_interval_ms"
        const val KEY_CAPTURE_COOLDOWN_MS = "capture_cooldown_ms"
        const val KEY_PERFORMANCE_MODE = "performance_mode"

        const val MIN_OVERLAY_OPACITY = 20
        const val MAX_OVERLAY_OPACITY = 100
        const val DEFAULT_OVERLAY_OPACITY = 65

        const val MIN_BURST_SHOTS = 0
        const val MAX_BURST_SHOTS = 10
        const val DEFAULT_BURST_SHOTS = 3

        const val MIN_BURST_INTERVAL_MS = 0L
        const val MAX_BURST_INTERVAL_MS = 5_000L
        const val DEFAULT_BURST_INTERVAL_MS = 350L

        const val MIN_CAPTURE_COOLDOWN_MS = 0L
        const val MAX_CAPTURE_COOLDOWN_MS = 300_000L
        const val DEFAULT_CAPTURE_COOLDOWN_MS = 15_000L
    }
}
