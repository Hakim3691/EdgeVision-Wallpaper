package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.camera.CameraAccess
import com.edgevision.wallpaper.camera.CameraPermissionWaitPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: a camera the wallpaper cannot use is explained, not hidden.
 *
 * A `WallpaperService` cannot raise a permission dialog. Before this, a missing
 * permission produced an empty home screen with one log line, which is
 * indistinguishable from a broken app. The engine now reports the access state so
 * the surface can say what to do, and keeps checking for the permission while the
 * wallpaper stays visible.
 */
class CameraAccessSpec {

    @Test
    fun `a wallpaper without camera permission reports what the user must do`() {
        var access: CameraAccess? = null

        Given("the wallpaper is visible and camera permission was never granted") {
            access = CameraAccess.resolve(permissionGranted = false, cameraAvailable = true)
        }

        Then("the pipeline idles and asks the user to act") {
            assertEquals(CameraAccess.MISSING_PERMISSION, access)
            assertTrue("a notice is needed", access!!.needsUserAction)
        }
    }

    @Test
    fun `granting permission later needs no wallpaper reset`() {
        val wait = CameraPermissionWaitPolicy(baseDelayMs = 500L, maxDelayMs = 8_000L, maxChecks = 8)

        Given("a visible wallpaper that is waiting for camera permission") {
            assertEquals(CameraAccess.MISSING_PERMISSION, CameraAccess.resolve(false, true))
            assertEquals("the first check is quick", 500L, wait.nextDelayMs())
            assertEquals(1, wait.checkCount)
        }

        When("the user grants camera permission from the settings screen") {
            wait.reset()
        }

        Then("the next check finds a ready pipeline") {
            assertEquals(CameraAccess.READY, CameraAccess.resolve(true, true))
            assertEquals("the wait restarts from the base delay", 500L, wait.nextDelayMs())
        }
    }

    @Test
    fun `waiting for permission backs off to a heartbeat and then stops`() {
        val wait = CameraPermissionWaitPolicy(baseDelayMs = 500L, maxDelayMs = 8_000L, maxChecks = 6)
        var delays: List<Long> = emptyList()

        Given("a wallpaper whose user never grants camera permission") { }

        When("the engine polls while the wallpaper stays visible") {
            delays = (1..6).mapNotNull { wait.nextDelayMs() }
        }

        Then("the delay doubles to an eight second heartbeat and the budget ends") {
            assertEquals(
                listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L),
                delays,
            )
            assertNull("polling stops instead of spinning forever", wait.nextDelayMs())
        }
    }

    @Test
    fun `a device without a camera is not treated as a permission problem`() {
        var access: CameraAccess? = null

        Given("camera permission granted on a device that exposes no camera") {
            access = CameraAccess.resolve(permissionGranted = true, cameraAvailable = false)
        }

        Then("the wallpaper says the hardware is missing instead of asking for permission") {
            assertEquals(CameraAccess.NO_HARDWARE, access)
            assertTrue("a notice is needed", access!!.needsUserAction)
        }
    }

    @Test
    fun `a granted permission with hardware runs the camera`() {
        Given("camera permission granted and a camera present") {
            val access = CameraAccess.resolve(permissionGranted = true, cameraAvailable = true)

            Then("nothing waits for the user and the pipeline may start") {
                assertEquals(CameraAccess.READY, access)
                assertFalse(access.needsUserAction)
            }
        }
    }
}
