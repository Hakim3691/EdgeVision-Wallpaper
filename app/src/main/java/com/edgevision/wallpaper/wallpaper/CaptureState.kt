package com.edgevision.wallpaper.wallpaper

import kotlin.concurrent.atomic.AtomicFloat
import kotlin.flow.MutableStateFlow
import kotlin.flow.StateFlow
import kotlin.system.currentTimeMillis

/**
 * Single source of truth for capture mode armed state and transient indicator fade.
 * 
 * Unifies the previously duplicated state between CameraWallpaperService and
 * HazardOverlayRenderer. All consumers should observe this StateFlow rather than
 * maintaining their own local copies.
 *
 * State flow values:
 *  - captureArmed: Boolean - whether capture mode is currently armed
 *  - captureArmedAlpha: Float in [0.0, 1.0] - alpha for the transient red-dot indicator,
 *    fades from 1.0 to 0.0 over 2000ms after armed is set to true
 *  - captureArmedToggleMs: Long - timestamp when captureArmed was last toggled,
 *    used by the renderer to determine if the indicator should still be visible
 */
object CaptureState {

    /** Current armed state of the capture mode. */
    val captureArmed = MutableStateFlow(false)

    /** Alpha of the red-dot indicator, animated from 1.0 to 0.0 over 2000ms. */
    val captureArmedAlpha = MutableStateFlow(0f)

    /** Timestamp when captureArmed was last toggled, for fade-out animation. */
    var captureArmedToggleMs = 0L

    /** Duration in milliseconds for the alpha fade-out. */
    const val CAPTURE_ARMED_FADE_MS = 2000L

    /** Reset all state to initial values. Must be called on app shutdown or when
     *  reverting to default wallpaper. */
    fun reset() {
        captureArmed.value = false
        captureArmedAlpha.value = 0f
        captureArmedToggleMs = 0L
    }
}