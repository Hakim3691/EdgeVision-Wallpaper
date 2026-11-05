package com.edgevision.wallpaper.vision

import com.edgevision.wallpaper.inference.Box
import com.edgevision.wallpaper.inference.Detection
import com.edgevision.wallpaper.inference.HazardKind

/**
 * Tracks hazard boxes across frames. A hazard is raised when a detected vehicle
 * or pole-like object covers enough of the frame, or when a smaller box is
 * expanding quickly (which is the signature of an object approaching the user).
 */
class HazardTracker(private val policy: HazardPolicy = HazardPolicy()) {

    private data class Track(
        val kind: HazardKind,
        val label: String,
        var box: Box,
        var area: Double,
        var updatedAtMs: Long,
        var headingDegrees: Float = 0f,
    )

    private val tracks = ArrayList<Track>()

    fun update(detections: List<Detection>, nowMs: Long): HazardEvent? {
        var best: HazardEvent? = null

        for (detection in detections) {
            val kind = detection.hazard ?: continue
            if (detection.confidence < policy.minConfidence) continue

            val matched = tracks
                .asSequence()
                .filter { it.kind == kind && nowMs - it.updatedAtMs <= policy.maxTrackAgeMs }
                .map { track -> track to track.box.iou(detection.box) }
                .filter { (_, iou) -> iou >= policy.minIouToMatch }
                .maxByOrNull { (_, iou) -> iou }
                ?.first

            val area = detection.box.area.coerceIn(0.0, 1.0)
            val growth = if (matched != null) {
                area / matched.area.coerceAtLeast(1e-6)
            } else {
                1.0
            }

            val isProximate = area >= policy.proximityArea ||
                (area >= policy.expandingArea && growth >= policy.expansionRatio)

            // Compute heading direction from box motion (delta in normalized coordinates)
            val headingDeg = if (matched != null) {
                val dx = detection.box.left - matched.box.left
                val dy = detection.box.top - matched.box.top
                (atan2(-dy, dx) * 180 / 3.14159265359).roundToInt() % 360
            } else {
                0
            }

            if (matched != null) {
                matched.box = detection.box
                matched.area = area
                matched.updatedAtMs = nowMs
                matched.headingDegrees = headingDeg
            } else {
                tracks += Track(
                    kind = kind,
                    label = detection.label,
                    box = detection.box,
                    area = area,
                    updatedAtMs = nowMs,
                    headingDegrees = headingDeg,
                )
            }

            if (isProximate) {
                val event = HazardEvent(
                    kind = kind,
                    label = detection.label,
                    confidence = detection.confidence.toDouble(),
                    area = area,
                    growth = growth,
                    box = detection.box,
                    headingDegrees = headingDeg,
                    timestampMs = nowMs,
                )
                if (best == null ||
                    event.area > best.area ||
                    (event.area == best.area && event.confidence > best.confidence)
                ) {
                    best = event
                }
            }
        }

        tracks.removeAll { nowMs - it.updatedAtMs > policy.maxTrackAgeMs * 2 }
        return best
    }

    fun reset() {
        tracks.clear()
    }
}
