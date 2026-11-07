package com.edgevision.wallpaper.vision

data class HazardPolicy(
    /** Detections below this confidence never raise an alert. */
    val minConfidence: Double = 0.55,
    /** A box covering this fraction of the frame is already close enough. */
    val proximityArea: Double = 0.12,
    /** A smaller box can alert if it is expanding quickly toward the user. */
    val expandingArea: Double = 0.06,
    /** Area ratio over the tracking window that counts as rapid expansion. */
    val expansionRatio: Double = 1.5,
    /**
     * How long a previous box stays usable for expansion measurement.
     *
     * Must stay comfortably above the slowest detection interval (1000 ms under
     * thermal critical). The old 900 ms expired before the next frame arrived, so
     * growth was always 1.0 and an approaching object could never be graded.
     */
    val maxTrackAgeMs: Long = 2_500,
    /** Minimum IoU required to consider two boxes the same physical object. */
    val minIouToMatch: Double = 0.20,
    /**
     * Normalized centre distance that also counts as the same object.
     *
     * A hand-held phone at 4–10 fps moves boxes far enough that IoU alone drops
     * below 0.20 between frames, resetting the track every time and preventing
     * the two-frame confirmation from ever completing.
     */
    val maxCenterDistance: Double = 0.35,
)
