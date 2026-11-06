package com.edgevision.wallpaper.vision

/**
 * Explicit alert priority. A harmless object must never produce the same
 * response as an imminent collision, so every visual, audio and capture
 * decision is expressed against this scale instead of a single "hazard" flag.
 *
 * [rank] is ordered: comparisons use [atLeast] rather than enum ordinals so
 * inserting a level later cannot silently change a threshold comparison.
 */
enum class ThreatLevel(val rank: Int) {
    /** Nothing relevant in view. */
    NONE(0),

    /** Something plausible was detected, but it is not actionable yet. */
    INFO(1),

    /** A confirmed hazard at a distance. Subtle, non-obstructive indication. */
    LOW(2),

    /** A hazard that warrants a noticeable visual pulse. */
    MEDIUM(3),

    /** A close or rapidly approaching hazard. Short, clear warning. */
    HIGH(4),

    /** Imminent collision. Highest visual priority and the only capture trigger. */
    CRITICAL(5),
    ;

    fun atLeast(other: ThreatLevel): Boolean = rank >= other.rank

    /** True when the user should be warned, not merely informed. */
    val isWarning: Boolean
        get() = atLeast(MEDIUM)

    /** True when an automatic burst capture is warranted. */
    val warrantsCapture: Boolean
        get() = atLeast(HIGH)
}
