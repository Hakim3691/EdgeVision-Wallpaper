package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.capture.BurstConfig
import com.edgevision.wallpaper.capture.CaptureController
import com.edgevision.wallpaper.capture.CaptureDecision
import com.edgevision.wallpaper.capture.CaptureTarget
import com.edgevision.wallpaper.capture.CaptureTrigger
import com.edgevision.wallpaper.vision.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: automatic and manual burst capture.
 *
 * The shutter must never fire from one weak detection, never overlap two
 * bursts, and never ignore the cooldown. Manual capture shares the same
 * admission path so a tap cannot corrupt an automatic sequence.
 */
class CaptureArmingSpec {

    @Test
    fun `automatic capture is refused while disarmed`() {
        val controller = CaptureController()

        Given("capture mode is disarmed") {
            Then("a critical threat is still refused") {
                val decision = controller.onThreat(ThreatLevel.CRITICAL, nowMs = 1_000)
                assertEquals(
                    CaptureDecision.Refused(CaptureDecision.Refusal.NOT_ARMED),
                    decision,
                )
            }
        }
    }

    @Test
    fun `armed capture fires a three shot burst on a high threat`() {
        val controller = CaptureController()

        Given("capture mode is armed") {
            controller.setArmed(true)

            When("a high threat is graded") {
                val decision = controller.onThreat(ThreatLevel.HIGH, nowMs = 1_000)

                Then("a three shot automatic burst is admitted") {
                    assertTrue(decision is CaptureDecision.Burst)
                    val request = (decision as CaptureDecision.Burst).request
                    assertEquals(CaptureTrigger.THREAT_AUTO, request.trigger)
                    assertEquals(3, request.shots)
                    assertTrue(controller.isBurstRunning)
                }
            }
        }
    }

    @Test
    fun `a medium threat does not fire the shutter`() {
        val controller = CaptureController()
        controller.setArmed(true)

        Given("capture mode is armed") {
            Then("a medium threat is below the capture threshold") {
                assertEquals(
                    CaptureDecision.Refused(CaptureDecision.Refusal.THREAT_TOO_LOW),
                    controller.onThreat(ThreatLevel.MEDIUM, nowMs = 1_000),
                )
            }
        }
    }

    @Test
    fun `a running burst blocks a second burst`() {
        val controller = CaptureController()
        controller.setArmed(true)

        Given("a burst is already running") {
            controller.onThreat(ThreatLevel.CRITICAL, nowMs = 1_000)

            Then("another trigger is refused as busy") {
                assertEquals(
                    CaptureDecision.Refused(CaptureDecision.Refusal.BUSY),
                    controller.onThreat(ThreatLevel.CRITICAL, nowMs = 1_100),
                )
            }
        }
    }

    @Test
    fun `cooldown suppresses capture until it elapses`() {
        val controller = CaptureController(BurstConfig(cooldownMs = 15_000L))
        controller.setArmed(true)

        Given("a burst finished at t=2000") {
            controller.onThreat(ThreatLevel.HIGH, nowMs = 1_000)
            controller.onBurstFinished(nowMs = 2_000)

            Then("capture is refused inside the cooldown") {
                assertEquals(
                    CaptureDecision.Refused(CaptureDecision.Refusal.COOLDOWN),
                    controller.onThreat(ThreatLevel.HIGH, nowMs = 10_000),
                )
                assertEquals(7_000L, controller.cooldownRemainingMs(10_000))
            }

            And("capture is admitted once the cooldown has elapsed") {
                assertTrue(controller.onThreat(ThreatLevel.HIGH, nowMs = 17_000) is CaptureDecision.Burst)
            }
        }
    }

    @Test
    fun `manual capture works while automatic capture is disarmed`() {
        val controller = CaptureController()

        Given("capture mode is disarmed") {
            When("the user requests capture manually") {
                val decision = controller.onManualRequest(nowMs = 1_000)

                Then("a manual burst is admitted") {
                    val request = (decision as CaptureDecision.Burst).request
                    assertEquals(CaptureTrigger.MANUAL, request.trigger)
                }
            }
        }
    }

    @Test
    fun `a zero shot configuration disables the shutter`() {
        val controller = CaptureController(BurstConfig(shots = 0))

        Then("manual capture is refused as disabled") {
            assertEquals(
                CaptureDecision.Refused(CaptureDecision.Refusal.DISABLED),
                controller.onManualRequest(nowMs = 0),
            )
        }
    }

    @Test
    fun `a burst completes exactly the configured number of shots`() {
        val controller = CaptureController(BurstConfig(shots = 3))

        Given("a manual burst of three shots") {
            controller.onManualRequest(nowMs = 0)
            repeat(5) { controller.onShotCaptured() }

            Then("the counter stops at the burst size") {
                assertEquals(3, controller.completedShots)
            }
        }
    }

    @Test
    fun `captures use the dedicated Android Clear MediaStore folder`() {
        Given("a threat capture") {
            Then("it is stored in the product folder and namespaced by trigger") {
                assertEquals("Pictures/Android Clear/Threat Captures/", CaptureTarget.RELATIVE_PATH)
                val auto = CaptureTarget.displayName(CaptureTrigger.THREAT_AUTO, 1_700_000_000_000, 0)
                val manual = CaptureTarget.displayName(CaptureTrigger.MANUAL, 1_700_000_000_000, 2)
                assertTrue("automatic captures are identifiable", auto.contains("-auto-"))
                assertTrue("manual captures are identifiable", manual.contains("-manual-"))
                assertTrue(auto.endsWith("-1.jpg"))
                assertTrue(manual.endsWith("-3.jpg"))
                assertFalse(auto.contains(" "))
            }
        }
    }
}
