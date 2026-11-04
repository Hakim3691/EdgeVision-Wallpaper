package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.camera.CameraRecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Feature: a lost camera recovers on its own.
 *
 * Camera2 disconnects are routine on OEM builds: another app opens the camera,
 * a privacy toggle fires, or the sensor reports a transient error. The wallpaper
 * must reopen the camera by itself instead of staying black until the user
 * covers and uncovers the home screen.
 */
class CameraRecoverySpec {

    @Test
    fun `a disconnected camera is reopened with growing delays`() {
        val policy = CameraRecoveryPolicy(baseDelayMs = 100L, maxDelayMs = 800L, maxAttempts = 4)
        var delays: List<Long> = emptyList()

        Given("a camera that fails repeatedly") { }

        When("the controller asks for the next reopen delay") {
            delays = (1..4).mapNotNull { policy.nextDelayMs() }
        }

        Then("the delay doubles up to the cap and then stops") {
            assertEquals(listOf(100L, 200L, 400L, 800L), delays)
            assertNull("the budget is spent", policy.nextDelayMs())
            assertEquals(4, policy.attemptCount)
        }
    }

    @Test
    fun `a transient disconnect is retried quickly enough to be invisible`() {
        val policy = CameraRecoveryPolicy()

        Given("the shipped recovery defaults") { }

        When("the camera is disconnected once") {
            val delay = policy.nextDelayMs()

            Then("the first retry happens within half a second") {
                assertEquals(400L, delay)
            }
        }
    }

    @Test
    fun `a successful reopen resets the delay sequence`() {
        val policy = CameraRecoveryPolicy(baseDelayMs = 100L, maxDelayMs = 800L, maxAttempts = 3)

        Given("two failed reopen attempts") {
            assertEquals(100L, policy.nextDelayMs())
            assertEquals(200L, policy.nextDelayMs())
        }

        When("the camera finally starts streaming") {
            policy.reset()
        }

        Then("the next failure starts again at the base delay") {
            assertEquals(100L, policy.nextDelayMs())
        }
    }

    @Test
    fun `hiding the wallpaper clears the backoff state`() {
        val policy = CameraRecoveryPolicy(baseDelayMs = 100L, maxDelayMs = 800L, maxAttempts = 2)

        Given("the recovery budget is already spent") {
            policy.nextDelayMs()
            policy.nextDelayMs()
            assertNull(policy.nextDelayMs())
        }

        When("the wallpaper is hidden and shown again") {
            policy.reset()
        }

        Then("detection can recover without restarting the process") {
            assertEquals(100L, policy.nextDelayMs())
        }
    }
}
