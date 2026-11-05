package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.diagnostics.DetectionHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: a wallpaper that cannot detect says so.
 *
 * The preview can keep streaming while the detector is dead, which looks healthy
 * and silently stops alerting. Repeated failures now raise a notice, and a single
 * transient error does not nag the user.
 */
class DetectionHealthSpec {

    @Test
    fun `one failed inference is not worth a notice`() {
        val health = DetectionHealth(failuresBeforeNotice = 3)

        Given("a detector that fails once") {
            When("the failure is recorded") {
                Then("nothing is shown and the preview keeps running") {
                    assertFalse(health.onFailure())
                    assertFalse(health.noticeVisible)
                    assertEquals(1, health.consecutiveFailureCount)
                }
            }
        }
    }

    @Test
    fun `repeated failures raise the notice exactly once`() {
        val health = DetectionHealth(failuresBeforeNotice = 3)
        var raised = 0

        Given("a detector that keeps failing") {
            repeat(5) {
                if (health.onFailure()) raised += 1
            }
        }

        Then("the third failure shows the notice and later ones do not repeat it") {
            assertEquals("the notice is raised once", 1, raised)
            assertTrue(health.noticeVisible)
            assertEquals(5, health.consecutiveFailureCount)
        }
    }

    @Test
    fun `a working inference clears the notice`() {
        val health = DetectionHealth(failuresBeforeNotice = 2)

        Given("a visible detection-unavailable notice") {
            health.onFailure()
            assertTrue(health.onFailure())
            assertTrue(health.noticeVisible)
        }

        When("inference succeeds again") {
            val cleared = health.onSuccess()

            Then("the notice is removed and the failure streak restarts") {
                assertTrue(cleared)
                assertFalse(health.noticeVisible)
                assertEquals(0, health.consecutiveFailureCount)
            }
        }

        Then("later successes have nothing left to clear") {
            assertFalse(health.onSuccess())
        }
    }

    @Test
    fun `a failure streak needs to restart after a success`() {
        val health = DetectionHealth(failuresBeforeNotice = 2)

        Given("two failures separated by a success") {
            health.onFailure()
            health.onSuccess()
        }

        When("a single further failure happens") {
            Then("the notice waits for the streak to build again") {
                assertFalse(health.onFailure())
                assertFalse(health.noticeVisible)
            }
        }
    }

    @Test
    fun `hiding the wallpaper starts a clean session`() {
        val health = DetectionHealth(failuresBeforeNotice = 2)

        Given("a notice raised by a failing detector") {
            health.onFailure()
            health.onFailure()
            assertTrue(health.noticeVisible)
        }

        When("the wallpaper is hidden") {
            health.reset()
        }

        Then("the next session starts without a stale notice") {
            assertFalse(health.noticeVisible)
            assertEquals(0, health.consecutiveFailureCount)
            assertFalse(health.onFailure())
        }
    }
}
