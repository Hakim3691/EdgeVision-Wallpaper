package com.edgevision.wallpaper.vision

/**
 * Thresholds for turning detections into user-facing hazard alerts.
 *
 * These were tuned down from the first prototype (0.55 confidence / 12% area /
 * 1.5x growth in 900ms), which almost never fired on a hand-held phone: a car
 * that a walking user can already see clearly covers far less than 12% of an
 * upright 320x320 frame, and a single fixed-area gate ignored closing speed.
 */
data class HazardPolicy(
    /** Detections below this confidence never raise an alert. */
    val minConfidence: Double = 0.45,
    /** A box covering this fraction of the frame is already close enough. */
    val proximityArea: Double = 0.08,
    /** A smaller box can alert if it is expanding quickly toward the user. */
    val expandingArea: Double = 0.03,
    /** Area ratio over the tracking window that counts as rapid expansion. */
    val expansionRatio: Double = 1.25,
    /** How long a previous box stays usable for expansion measurement. */
    val maxTrackAgeMs: Long = 1_200,
    /** Minimum IoU required to consider two boxes the same physical object. */
    val minIouToMatch: Double = 0.15,
)
