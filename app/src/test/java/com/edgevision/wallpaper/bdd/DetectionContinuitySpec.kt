package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.inference.Box
import com.edgevision.wallpaper.inference.Detection
import com.edgevision.wallpaper.inference.HazardKind
import com.edgevision.wallpaper.vision.HazardTracker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: detection continuity.
 *
 * The tracker used to match boxes by IoU alone with a 900 ms track window. At the
 * 4–10 fps the wallpaper runs at, a hand-held phone moves a box far enough that
 * every frame became a new track, and the 1000 ms thermal cadence expired the
 * previous box before the next frame arrived. Both made confirmation impossible.
 */
class DetectionContinuitySpec {

    private fun detection(left: Float, top: Float, right: Float, bottom: Float) = Detection(
        label = "car",
        hazard = HazardKind.VEHICLE,
        confidence = 0.9f,
        box = Box.fromNormalized(left, top, right, bottom),
    )

    @Test
    fun `a moved box stays one track and still measures approach`() {
        val tracker = HazardTracker()

        Given("a small distant vehicle") {
            assertNull(
                tracker.update(
                    listOf(detection(0.36f, 0.36f, 0.48f, 0.48f)),
                    nowMs = 1_000,
                ),
            )
        }

        When("it grows and shifts further than IoU alone tolerates") {
            val event = tracker.update(
                listOf(detection(0.40f, 0.34f, 0.70f, 0.64f)),
                nowMs = 2_000,
            )

            Then("centre-distance matching still measures the approach") {
                assertNotNull(event)
                assertTrue("growth must exceed the expansion threshold", event!!.growth > 1.5)
            }
        }
    }

    @Test
    fun `the track window covers the slowest detection cadence`() {
        val tracker = HazardTracker()
        tracker.update(
            listOf(detection(0.36f, 0.36f, 0.48f, 0.48f)),
            nowMs = 1_000,
        )

        When("the next frame arrives one thermal-critical interval later") {
            val event = tracker.update(
                listOf(detection(0.38f, 0.36f, 0.64f, 0.62f)),
                nowMs = 2_000,
            )

            Then("the earlier box is still used for growth") {
                assertNotNull(event)
                assertTrue(event!!.growth > 1.5)
            }
        }
    }

    @Test
    fun `an unrelated later box is not measured against a stale track`() {
        val tracker = HazardTracker()
        tracker.update(
            listOf(detection(0.10f, 0.10f, 0.20f, 0.20f)),
            nowMs = 1_000,
        )

        When("a small box appears elsewhere after the track window") {
            val event = tracker.update(
                listOf(detection(0.70f, 0.70f, 0.78f, 0.78f)),
                nowMs = 5_000,
            )

            Then("no phantom approach is reported") {
                assertNull(event)
            }
        }
    }
}
