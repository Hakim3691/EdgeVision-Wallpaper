package com.edgevision.wallpaper.vision

import com.edgevision.wallpaper.inference.Box
import com.edgevision.wallpaper.inference.Detection
import com.edgevision.wallpaper.inference.HazardKind
import kotlin.math.hypot

/**
 * Tracks hazard boxes across frames. A hazard is raised when a detected vehicle
 * or pole-like object covers enough of the frame, or when a smaller box is
 * expanding quickly (which is the signature of an object approaching the user).
 *
 * Matching uses IoU **or** centre distance. At the 4–10 fps the wallpaper runs at,
 * a hand-held phone moves a box far enough that IoU alone falls below the
 * threshold between frames, which used to reset the track on every frame and stop
 * the two-frame confirmation from ever completing.
 */
class HazardTracker(private val policy: HazardPolicy = HazardPolicy()) {

    private data class Track(
        val kind: HazardKind,
        val label: String,
        var box: Box,
        var area: Double,
        var updatedAtMs: Long,
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
                .filter { track ->
                    track.box.iou(detection.box) >= policy.minIouToMatch ||
                        centerDistance(track.box, detection.box) <= policy.maxCenterDistance
                }
                .maxByOrNull { track -> track.box.iou(detection.box) }

            val area = detection.box.area.coerceIn(0.0, 1.0)
            val growth = if (matched != null) {
                area / matched.area.coerceAtLeast(1e-6)
            } else {
                1.0
            }

            val isProximate = area >= policy.proximityArea ||
                (area >= policy.expandingArea && growth >= policy.expansionRatio)

            if (matched != null) {
                matched.box = detection.box
                matched.area = area
                matched.updatedAtMs = nowMs
            } else {
                tracks += Track(
                    kind = kind,
                    label = detection.label,
                    box = detection.box,
                    area = area,
                    updatedAtMs = nowMs,
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

    private fun centerDistance(a: Box, b: Box): Double {
        val dx = ((a.left + a.right) / 2.0) - ((b.left + b.right) / 2.0)
        val dy = ((a.top + a.bottom) / 2.0) - ((b.top + b.bottom) / 2.0)
        return hypot(dx, dy)
    }
}
