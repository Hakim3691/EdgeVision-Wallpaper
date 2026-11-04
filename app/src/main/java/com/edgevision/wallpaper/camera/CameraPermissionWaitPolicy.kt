package com.edgevision.wallpaper.camera

/**
 * Poll cadence while the wallpaper is visible but camera permission is missing.
 *
 * The engine cannot ask for the permission itself. Polling starts fast, so a grant
 * that happens while the settings screen closes is picked up almost immediately,
 * then backs off to a slow heartbeat so a wallpaper the user never fixes costs
 * almost nothing. The check budget keeps it from polling forever; showing the
 * wallpaper again resets it.
 */
class CameraPermissionWaitPolicy(
    private val baseDelayMs: Long = 500L,
    private val maxDelayMs: Long = 8_000L,
    val maxChecks: Int = 120,
) {

    init {
        require(baseDelayMs > 0) { "Base delay must be positive" }
        require(maxDelayMs >= baseDelayMs) { "Max delay must not be shorter than the base delay" }
        require(maxChecks > 0) { "The wait needs at least one check" }
    }

    private var checks = 0

    val checkCount: Int
        get() = checks

    /** Delay before the next permission check, or `null` when the budget is spent. */
    fun nextDelayMs(): Long? {
        if (checks >= maxChecks) return null
        val delay = (baseDelayMs shl checks.coerceAtMost(16)).coerceAtMost(maxDelayMs)
        checks += 1
        return delay
    }

    /** Called when permission arrives and whenever the wallpaper is hidden. */
    fun reset() {
        checks = 0
    }
}
