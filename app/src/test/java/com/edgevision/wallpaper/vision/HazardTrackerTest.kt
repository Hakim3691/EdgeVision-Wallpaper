package com.edgevision.wallpaper.vision

import com.edgevision.wallpaper.inference.Box
import com.edgevision.wallpaper.inference.Detection
import com.edgevision.wallpaper.inference.HazardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HazardTrackerTest {

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        Box.fromNormalized(left, top, right, bottom)

    private fun detection(
        kind: HazardKind? = HazardKind.VEHICLE,
        label: String = "car",
        confidence: Float = 0.9f,
        box: Box,
    ) = Detection(label = label, hazard = kind, confidence = confidence, box = box)

    @Test
    fun proximityAreaRaisesHazard() {
        val tracker = HazardTracker()
        val event = tracker.update(
            listOf(detection(box = box(0.10f, 0.10f, 0.70f, 0.70f))),
            nowMs = 1_000,
        )

        assertNotNull(event)
        assertEquals(HazardKind.VEHICLE, event!!.kind)
        assertEquals("car", event.label)
        assertEquals(0.36, event.area, 0.001)
    }

    @Test
    fun rapidlyExpandingSmallBoxRaisesHazard() {
        val tracker = HazardTracker()
        assertNull(
            tracker.update(
                listOf(detection(box = box(0.35f, 0.35f, 0.55f, 0.55f))),
                nowMs = 1_000,
            ),
        )

        val event = tracker.update(
            listOf(detection(box = box(0.30f, 0.30f, 0.60f, 0.60f))),
            nowMs = 1_300,
        )

        assertNotNull(event)
        assertEquals(2.25, event!!.growth, 0.01)
        assertEquals(0.09, event.area, 0.001)
    }

    @Test
    fun smallStaticBoxDoesNotRaiseHazard() {
        val tracker = HazardTracker()
        val small = detection(box = box(0.40f, 0.40f, 0.55f, 0.55f))
        assertNull(tracker.update(listOf(small), nowMs = 1_000))
        assertNull(tracker.update(listOf(small), nowMs = 1_200))
        assertNull(tracker.update(listOf(small), nowMs = 1_400))
    }

    @Test
    fun lowConfidenceIsIgnoredEvenWhenClose() {
        val tracker = HazardTracker()
        val event = tracker.update(
            listOf(detection(confidence = 0.30f, box = box(0.0f, 0.0f, 0.9f, 0.9f))),
            nowMs = 1_000,
        )
        assertNull(event)
    }

    @Test
    fun unknownClassIsIgnored() {
        val tracker = HazardTracker()
        val event = tracker.update(
            listOf(
                detection(
                    kind = null,
                    label = "person",
                    confidence = 0.99f,
                    box = box(0.0f, 0.0f, 1.0f, 1.0f),
                ),
            ),
            nowMs = 1_000,
        )
        assertNull(event)
    }

    @Test
    fun largestNearbyHazardWins() {
        val tracker = HazardTracker()
        val event = tracker.update(
            listOf(
                detection(
                    label = "bicycle",
                    box = box(0.05f, 0.05f, 0.45f, 0.45f),
                ),
                detection(
                    label = "truck",
                    box = box(0.10f, 0.10f, 0.75f, 0.75f),
                ),
            ),
            nowMs = 1_000,
        )

        assertNotNull(event)
        assertEquals("truck", event!!.label)
    }

    @Test
    fun oldTracksAreEvicted() {
        val tracker = HazardTracker()
        tracker.update(
            listOf(detection(box = box(0.40f, 0.40f, 0.50f, 0.50f))),
            nowMs = 1_000,
        )
        tracker.update(
            listOf(detection(box = box(0.30f, 0.30f, 0.60f, 0.60f))),
            nowMs = 5_000,
        )
        // The old track is gone, so this is treated as a new small box and does
        // not produce a growth alert.
        val event = tracker.update(
            listOf(detection(box = box(0.20f, 0.20f, 0.40f, 0.40f))),
            nowMs = 5_100,
        )
        assertNull(event)
    }
}
