package com.edgevision.wallpaper.capture

/**
 * Triple-tap arming for the home screen.
 *
 * Pure and time-injected: callers pass `SystemClock.elapsedRealtime()` (the
 * service) or a fake clock (specifications). Three taps inside
 * [tapWindowMs] arm capture until [armDurationMs] after the third tap; taps that
 * arrive too slowly never accumulate into an arm.
 *
 * Further taps while armed are ignored rather than re-arming, so a fourth tap
 * cannot extend the window indefinitely or fire a second arm transition.
 */
class TripleTapArming(
    private val tapsRequired: Int = 3,
    private val tapWindowMs: Long = 600L,
    val armDurationMs: Long = 8_000L,
) {

    sealed interface TapResult {
        /** Not enough taps yet; [count] is the number inside the window. */
        data class Tapped(val count: Int) : TapResult

        /** Capture is now armed until [untilMs]. */
        data class Armed(val untilMs: Long) : TapResult

        data class Ignored(val reason: Reason) : TapResult

        enum class Reason {
            /** Capture is switched off in settings. */
            DISABLED,

            /** Already armed; extra taps must not double-arm. */
            ALREADY_ARMED,
        }
    }

    private val taps = ArrayDeque<Long>()
    private var armedUntil: Long? = null

    val isArmed: Boolean
        get() = armedUntil != null

    fun armedUntilMs(): Long? = armedUntil

    fun onTap(nowMs: Long, captureEnabled: Boolean): TapResult {
        if (!captureEnabled) {
            taps.clear()
            return TapResult.Ignored(TapResult.Reason.DISABLED)
        }

        val until = armedUntil
        if (until != null && nowMs < until) {
            return TapResult.Ignored(TapResult.Reason.ALREADY_ARMED)
        }
        if (until != null) {
            // The previous arm expired without a tick; clear it before counting.
            armedUntil = null
        }

        while (taps.isNotEmpty() && nowMs - taps.first() > tapWindowMs) {
            taps.removeFirst()
        }
        taps.addLast(nowMs)

        if (taps.size >= tapsRequired) {
            taps.clear()
            val armedUntilMs = nowMs + armDurationMs
            armedUntil = armedUntilMs
            return TapResult.Armed(armedUntilMs)
        }
        return TapResult.Tapped(taps.size)
    }

    /** Returns true when this call expired an arm. */
    fun onTimePassed(nowMs: Long): Boolean {
        val until = armedUntil ?: return false
        if (nowMs < until) return false
        armedUntil = null
        return true
    }

    /** Explicit cancel (wallpaper hidden, capture disabled, long-press). */
    fun cancel() {
        taps.clear()
        armedUntil = null
    }

    fun reset() = cancel()
}
