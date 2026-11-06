package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.capture.TripleTapArming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: triple-tap arming on the home screen.
 *
 * The gesture is intentionally strict. A slow triple tap must not arm capture,
 * and repeatedly tapping an armed wallpaper must not keep extending it.
 */
class TripleTapArmingSpec {

    private fun arming() = TripleTapArming(tapsRequired = 3, tapWindowMs = 600L, armDurationMs = 8_000L)

    @Test
    fun `three taps inside the window arm capture for the configured duration`() {
        val gesture = arming()

        Given("three taps 200ms apart") {
            assertEquals(1, (gesture.onTap(1_000, true) as TripleTapArming.TapResult.Tapped).count)
            assertEquals(2, (gesture.onTap(1_200, true) as TripleTapArming.TapResult.Tapped).count)
            val third = gesture.onTap(1_400, true)

            Then("capture is armed until eight seconds after the third tap") {
                assertTrue(third is TripleTapArming.TapResult.Armed)
                assertEquals(9_400L, (third as TripleTapArming.TapResult.Armed).untilMs)
                assertTrue(gesture.isArmed)
                assertEquals(9_400L, gesture.armedUntilMs())
            }
        }
    }

    @Test
    fun `a triple tap that is too slow does not arm`() {
        val gesture = arming()

        Given("taps at 0ms, 400ms and 1,200ms with a 600ms window") {
            gesture.onTap(0, true)
            gesture.onTap(400, true)
            val third = gesture.onTap(1_200, true)

            Then("the arm does not happen and the stale taps are discarded") {
                assertFalse(gesture.isArmed)
                // 1,200ms is outside the window of both earlier taps, so the
                // sequence restarts and only the slow tap is counted.
                assertEquals(1, (third as TripleTapArming.TapResult.Tapped).count)
                assertEquals(2, (gesture.onTap(1_300, true) as TripleTapArming.TapResult.Tapped).count)
                assertFalse(gesture.isArmed)
            }
        }
    }

    @Test
    fun `a fourth tap does not double-arm or extend the window`() {
        val gesture = arming()
        gesture.onTap(1_000, true)
        gesture.onTap(1_100, true)
        val armed = gesture.onTap(1_200, true) as TripleTapArming.TapResult.Armed

        Then("taps while armed are ignored and the expiry is unchanged") {
            val fourth = gesture.onTap(1_300, true)
            assertEquals(
                TripleTapArming.TapResult.Ignored(TripleTapArming.TapResult.Reason.ALREADY_ARMED),
                fourth,
            )
            assertEquals(armed.untilMs, gesture.armedUntilMs())
        }
    }

    @Test
    fun `taps are ignored while capture is disabled`() {
        val gesture = arming()

        Given("capture is switched off in settings") {
            repeat(3) { index ->
                assertEquals(
                    TripleTapArming.TapResult.Ignored(TripleTapArming.TapResult.Reason.DISABLED),
                    gesture.onTap(1_000L + index * 100, captureEnabled = false),
                )
            }

            Then("nothing arms and no partial count survives") {
                assertFalse(gesture.isArmed)
                assertNull(gesture.armedUntilMs())
            }
        }
    }

    @Test
    fun `arming expires back to idle when the duration elapses`() {
        val gesture = arming()
        gesture.onTap(0, true)
        gesture.onTap(100, true)
        gesture.onTap(200, true)

        Given("capture armed at 200ms until 8,200ms") {
            Then("time passing does not expire it early") {
                assertFalse(gesture.onTimePassed(8_199))
                assertTrue(gesture.isArmed)
            }

            And("the deadline disarms it and reports the expiry once") {
                assertTrue(gesture.onTimePassed(8_200))
                assertFalse(gesture.isArmed)
                assertNull(gesture.armedUntilMs())
                assertFalse("expiry must be reported only once", gesture.onTimePassed(8_300))
            }
        }
    }

    @Test
    fun `an explicit cancel disarms immediately and clears partial taps`() {
        val gesture = arming()
        gesture.onTap(0, true)
        gesture.onTap(100, true)
        gesture.onTap(200, true)

        Given("capture is armed") {
            gesture.cancel()

            Then("it is idle and a fresh triple tap is required to arm again") {
                assertFalse(gesture.isArmed)
                assertEquals(1, (gesture.onTap(1_000, true) as TripleTapArming.TapResult.Tapped).count)
            }
        }
    }

    @Test
    fun `an expired arm can be armed again by a new triple tap`() {
        val gesture = arming()
        gesture.onTap(0, true)
        gesture.onTap(100, true)
        gesture.onTap(200, true)
        gesture.onTimePassed(20_000)

        Then("a new triple tap arms again") {
            gesture.onTap(30_000, true)
            gesture.onTap(30_100, true)
            assertTrue(gesture.onTap(30_200, true) is TripleTapArming.TapResult.Armed)
        }
    }
}
