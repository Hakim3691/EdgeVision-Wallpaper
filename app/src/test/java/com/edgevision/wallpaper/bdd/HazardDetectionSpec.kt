package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.inference.Box
import com.edgevision.wallpaper.inference.Detection
import com.edgevision.wallpaper.inference.HazardKind
import com.edgevision.wallpaper.vision.HazardTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Feature: hazards alert at a useful distance.
 *
 * The prototype gate (0.55 confidence and a box covering 12% of the frame)
 * almost never fired on a hand-held phone: a car that is already a clear danger
 * at walking pace covers far less than 12% of an upright frame. Detection looked
 * broken because the alert never came, not because nothing was detected.
 */
class HazardDetectionSpec {

    @Test
    fun `a mid confidence car filling the lower view raises an alert`() {
        val tracker = HazardTracker()
        var event: com.edgevision.wallpaper.vision.HazardEvent? = null

        Given("a car covering about 9% of the upright frame at 0.50 confidence") { }

        When("the frame is evaluated") {
            event = tracker.update(
                listOf(car(confidence = 0.50f, box = box(0.20f, 0.45f, 0.55f, 0.72f))),
                nowMs = 1_000L,
            )
        }

        Then("the user is warned") {
            val raised = event
            assertNotNull("a 0.50 confidence car at 9% of the frame must alert", raised)
            assertEquals(HazardKind.VEHICLE, raised!!.kind)
            assertEquals(0.0945, raised.area, 0.001)
        }
    }

    @Test
    fun `a distant static car never raises an alert`() {
        val tracker = HazardTracker()
        val distant = car(confidence = 0.80f, box = box(0.40f, 0.38f, 0.56f, 0.54f))

        Given("a car parked far ahead covering 2.6% of the frame") { }

        When("it is seen on three consecutive frames") {
            Then("no alert is raised because it is neither close nor approaching") {
                assertNull(tracker.update(listOf(distant), nowMs = 1_000L))
                assertNull(tracker.update(listOf(distant), nowMs = 1_200L))
                assertNull(tracker.update(listOf(distant), nowMs = 1_400L))
            }
        }
    }

    @Test
    fun `a car approaching at walking pace alerts within half a second`() {
        val tracker = HazardTracker()
        var event: com.edgevision.wallpaper.vision.HazardEvent? = null

        Given("a car that covers 3.2% of the frame") { }

        When("it grows to 5.5% of the frame 450ms later") {
            assertNull("the first sighting alone is not close enough", tracker.update(
                listOf(car(confidence = 0.70f, box = box(0.35f, 0.35f, 0.55f, 0.51f))),
                nowMs = 1_000L,
            ))
            event = tracker.update(
                listOf(car(confidence = 0.70f, box = box(0.33f, 0.33f, 0.58f, 0.55f))),
                nowMs = 1_450L,
            )
        }

        Then("closing speed alone is enough to warn the user") {
            val raised = event
            assertNotNull(raised)
            assertEquals(1.72, raised!!.growth, 0.01)
        }
    }

    @Test
    fun `a low confidence box never alerts no matter how close`() {
        val tracker = HazardTracker()
        val uncertain = car(confidence = 0.44f, box = box(0.0f, 0.0f, 0.95f, 0.95f))

        Given("a detection covering almost the whole frame but at 0.44 confidence") { }

        When("it is evaluated") {
            val event = tracker.update(listOf(uncertain), nowMs = 1_000L)

            Then("the uncertainty gate still wins") {
                assertNull(event)
            }
        }
    }
}

private fun box(left: Float, top: Float, right: Float, bottom: Float) =
    Box.fromNormalized(left, top, right, bottom)

private fun car(confidence: Float, box: Box) = Detection(
    label = "car",
    hazard = HazardKind.VEHICLE,
    confidence = confidence,
    box = box,
)
