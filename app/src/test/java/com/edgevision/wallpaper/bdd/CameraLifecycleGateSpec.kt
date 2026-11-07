package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.wallpaper.EngineResource
import com.edgevision.wallpaper.wallpaper.WallpaperEngineController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: biometric handover.
 *
 * The wallpaper must never hold the camera while the keyguard is up, because that
 * starves face unlock. It must release the sensor as soon as the screen turns off
 * and resume only after the user actually unlocks.
 */
class CameraLifecycleGateSpec {

    private class FakeResource : EngineResource {
        var starts = 0
        var stops = 0

        override fun start() {
            starts += 1
        }

        override fun stop() {
            stops += 1
        }
    }

    private class Fixture(
        val controller: WallpaperEngineController,
        val camera: FakeResource,
        val inference: FakeResource,
    )

    private fun running(): Fixture {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = WallpaperEngineController(camera, inference)
        controller.onSurfaceAvailabilityChanged(true)
        controller.onVisibilityChanged(true)
        return Fixture(controller, camera, inference)
    }

    @Test
    fun `screen off releases the camera while the wallpaper stays visible`() {
        val fixture = running()
        Given("the wallpaper is visible and streaming") {
            assertEquals(1, fixture.camera.starts)
            assertEquals(1, fixture.inference.starts)
        }

        When("the screen turns off") {
            fixture.controller.onScreenStateChanged(interactive = false, keyguardLocked = false)
        }

        Then("both resources are released") {
            assertEquals(1, fixture.camera.stops)
            assertEquals(1, fixture.inference.stops)
            assertEquals(WallpaperEngineController.State.PAUSED, fixture.controller.state)
        }
    }

    @Test
    fun `screen on while locked does not take the camera from face unlock`() {
        val fixture = running()
        fixture.controller.onScreenStateChanged(interactive = false, keyguardLocked = false)

        When("the display wakes on the lock screen") {
            fixture.controller.onScreenStateChanged(interactive = true, keyguardLocked = true)
        }

        Then("the camera stays closed") {
            assertEquals(
                "the camera must not reopen behind the keyguard",
                1,
                fixture.camera.starts,
            )
            assertEquals(1, fixture.camera.stops)
        }
    }

    @Test
    fun `unlocking resumes the camera`() {
        val fixture = running()
        fixture.controller.onScreenStateChanged(interactive = false, keyguardLocked = false)
        fixture.controller.onScreenStateChanged(interactive = true, keyguardLocked = true)

        When("the user unlocks") {
            fixture.controller.onScreenStateChanged(interactive = true, keyguardLocked = false)
        }

        Then("streaming resumes") {
            assertEquals(2, fixture.camera.starts)
            assertEquals(2, fixture.inference.starts)
            assertTrue(fixture.controller.isRunning)
            assertEquals(WallpaperEngineController.State.VISIBLE, fixture.controller.state)
        }
    }

    @Test
    fun `surface creation while locked does not restart hardware`() {
        val fixture = running()
        fixture.controller.onScreenStateChanged(interactive = true, keyguardLocked = true)
        fixture.controller.onVisibilityChanged(false)
        fixture.controller.onSurfaceAvailabilityChanged(true)

        assertEquals(1, fixture.camera.starts)
        assertFalse(fixture.controller.isRunning)
    }
}
