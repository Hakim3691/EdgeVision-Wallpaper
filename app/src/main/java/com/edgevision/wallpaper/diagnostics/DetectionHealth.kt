package com.edgevision.wallpaper.diagnostics

/**
 * Tracks whether hazard detection is actually producing results.
 *
 * A broken detector used to be invisible: the preview kept streaming, so the
 * wallpaper looked healthy while never alerting anyone. The engine now shows a
 * notice once detection has failed repeatedly, and clears it as soon as a frame is
 * inferred again.
 *
 * The threshold exists because single inference errors are normal (a model reload,
 * a transient native failure) and nagging the user for them would train them to
 * ignore the notice that matters.
 */
class DetectionHealth(
    private val failuresBeforeNotice: Int = 3,
) {

    init {
        require(failuresBeforeNotice > 0) { "At least one failure must be tolerated" }
    }

    private var consecutiveFailures = 0

    val consecutiveFailureCount: Int
        get() = consecutiveFailures

    var noticeVisible: Boolean = false
        private set

    /** Records a failure; returns true when it should make the problem visible. */
    fun onFailure(): Boolean {
        consecutiveFailures += 1
        if (noticeVisible || consecutiveFailures < failuresBeforeNotice) return false
        noticeVisible = true
        return true
    }

    /** Records a successful inference; returns true when a visible notice should clear. */
    fun onSuccess(): Boolean {
        consecutiveFailures = 0
        if (!noticeVisible) return false
        noticeVisible = false
        return true
    }

    /** Called when the wallpaper is hidden, so a new session starts clean. */
    fun reset() {
        consecutiveFailures = 0
        noticeVisible = false
    }
}
