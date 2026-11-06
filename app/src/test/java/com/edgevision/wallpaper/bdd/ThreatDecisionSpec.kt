package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.inference.Box
import com.edgevision.wallpaper.inference.HazardKind
import com.edgevision.wallpaper.vision.HazardEvent
import com.edgevision.wallpaper.vision.ThreatDecisionEngine
import com.edgevision.wallpaper.vision.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: graded threat decisions.
 *
 * A premium safety product cannot flash the screen for every detection. These
 * specs pin the rules that keep a single weak frame from becoming a warning,
 * while still escalating a genuinely close or approaching object.
 */
class ThreatDecisionSpec {

    private fun event(
        kind: HazardKind = HazardKind.VEHICLE,
        confidence: Double = 0.9,
        area: Double = 0.25,
        growth: Double = 1.0,
        atMs: Long = 0L,
    ) = HazardEvent(
        kind = kind,
        label = "car",
        confidence = confidence,
        area = area,
        growth = growth,
        box = Box(left = 0f, top = 0f, right = 1f, bottom = 1f),
        timestampMs = atMs,
    )

    @Test
    fun `a single frame never raises a warning`() {
        val engine = ThreatDecisionEngine()

        Given("a detection covering 25% of the frame") {
            val assessment = engine.assess(event(area = 0.25), nowMs = 1_000)

            Then("it is reported, but only as information") {
                assertEquals(ThreatLevel.INFO, assessment.level)
                assertFalse("a first sighting must not warn", assessment.level.isWarning)
                assertFalse("a first sighting must not capture", assessment.level.warrantsCapture)
                assertEquals(
                    ThreatDecisionEngine.Assessment.Reason.AWAITING_CONFIRMATION,
                    assessment.reason,
                )
            }
        }
    }

    @Test
    fun `two agreeing frames grade a close vehicle as high`() {
        val engine = ThreatDecisionEngine()

        Given("the same vehicle covers 25% of two consecutive frames") {
            engine.assess(event(area = 0.25), nowMs = 1_000)
            val second = engine.assess(event(area = 0.25), nowMs = 1_100)

            Then("the second frame is warning-grade") {
                assertEquals(ThreatLevel.HIGH, second.level)
                assertTrue(second.level.isWarning)
                assertTrue("HIGH warrants a burst", second.level.warrantsCapture)
                assertEquals(2, second.consecutiveFrames)
            }
        }
    }

    @Test
    fun `confidence below the floor is never graded`() {
        val engine = ThreatDecisionEngine()

        Given("a large box with weak confidence") {
            engine.assess(event(area = 0.4, confidence = 0.3), nowMs = 1_000)
            val assessment = engine.assess(event(area = 0.4, confidence = 0.3), nowMs = 1_100)

            Then("no level is produced") {
                assertEquals(ThreatLevel.NONE, assessment.level)
                assertEquals(
                    ThreatDecisionEngine.Assessment.Reason.LOW_CONFIDENCE,
                    assessment.reason,
                )
            }
        }
    }

    @Test
    fun `a hazard leaving the frame decays after the hold window`() {
        val engine = ThreatDecisionEngine()

        Given("a confirmed high threat") {
            engine.assess(event(area = 0.25), nowMs = 1_000)
            engine.assess(event(area = 0.25), nowMs = 1_100)

            Then("the level holds briefly, then clears") {
                assertEquals(ThreatLevel.HIGH, engine.assess(null, nowMs = 1_300).level)
                assertEquals(ThreatLevel.NONE, engine.assess(null, nowMs = 1_900).level)
            }
        }
    }

    @Test
    fun `persistence escalates a confirmed hazard one level`() {
        val engine = ThreatDecisionEngine()

        Given("a medium-size hazard held for four frames") {
            engine.assess(event(area = 0.14), nowMs = 0)
            engine.assess(event(area = 0.14), nowMs = 100)
            engine.assess(event(area = 0.14), nowMs = 200)
            val fourth = engine.assess(event(area = 0.14), nowMs = 300)

            Then("it climbs from medium to high") {
                assertEquals(4, fourth.consecutiveFrames)
                assertEquals(ThreatLevel.HIGH, fourth.level)
            }
        }
    }

    @Test
    fun `a different hazard kind restarts confirmation`() {
        val engine = ThreatDecisionEngine()

        Given("a vehicle followed by a pole") {
            engine.assess(event(kind = HazardKind.VEHICLE, area = 0.25), nowMs = 0)
            val second = engine.assess(event(kind = HazardKind.POLE, area = 0.25), nowMs = 100)

            Then("confirmation starts again at one frame") {
                assertEquals(1, second.consecutiveFrames)
                assertEquals(ThreatLevel.INFO, second.level)
            }
        }
    }
}
