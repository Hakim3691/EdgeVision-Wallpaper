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
    /** How long a previous box stays usable for expansion measurement. */
    val maxTrackAgeMs: Long = 900,
    /** Minimum IoU required to consider two boxes the same physical object. */
    val minIouToMatch: Double = 0.20,
)
