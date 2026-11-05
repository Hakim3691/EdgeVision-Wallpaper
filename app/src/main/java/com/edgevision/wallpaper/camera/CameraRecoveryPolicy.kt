package com.edgevision.wallpaper.camera

/**
 * Bounded exponential backoff for reopening the camera.
 *
 * OxygenOS and other OEM builds routinely take the camera away from a bound
 * wallpaper service (another app opens the camera, a privacy toggle fires, the
 * device is unlocked, or the sensor reports a transient error). The previous
 * pipeline treated every one of those as terminal, so the wallpaper stayed
 * black until the user happened to cover and uncover the home screen.
 *
 * The budget keeps a persistent hardware failure from spinning: after
 * [maxAttempts] the controller stops retrying until the wallpaper is visible
 * again, which resets the policy.
 */
class CameraRecoveryPolicy(
    private val baseDelayMs: Long = 400L,
    private val maxDelayMs: Long = 8_000L,
    val maxAttempts: Int = 6,
) {

    init {
        require(baseDelayMs > 0) { "Base delay must be positive" }
        require(maxDelayMs >= baseDelayMs) { "Max delay must not be shorter than the base delay" }
        require(maxAttempts > 0) { "Recovery needs at least one attempt" }
    }

    private var attempts = 0

    val attemptCount: Int
        get() = attempts

    /** Delay before the next reopen attempt, or `null` when the budget is spent. */
    fun nextDelayMs(): Long? {
        if (attempts >= maxAttempts) return null
        val delay = (baseDelayMs shl attempts.coerceAtMost(16)).coerceAtMost(maxDelayMs)
        attempts += 1
        return delay
    }

    /** Called after a successful open and whenever the wallpaper is hidden. */
    fun reset() {
        attempts = 0
    }
}
