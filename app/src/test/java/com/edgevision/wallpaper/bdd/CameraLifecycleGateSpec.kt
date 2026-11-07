package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.wallpaper.EngineResource
import com.edgevision.wallpaper.wallpaper.WallpaperEngineController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: lock-screen streaming.
 *
 * The wallpaper must keep streaming on an awake lock screen and only pause while
 * the display is actually off. The keyguard is not a reason to freeze the camera:
 * a locked screen still shows the wallpaper, and stopping there left a single
 * static frame until an unlock broadcast that some unlock paths never send.
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
    fun `the lock screen keeps streaming instead of freezing the last frame`() {
        val fixture = running()
        fixture.controller.onScreenStateChanged(interactive = false, keyguardLocked = false)

        When("the display wakes on the lock screen") {
            fixture.controller.onScreenStateChanged(interactive = true, keyguardLocked = true)
        }

        Then("the camera comes back without waiting for an unlock broadcast") {
            assertEquals(2, fixture.camera.starts)
            assertEquals(2, fixture.inference.starts)
            assertTrue(fixture.controller.isRunning)
            assertEquals(WallpaperEngineController.State.VISIBLE, fixture.controller.state)
        }
    }

    @Test
    fun `unlocking does not restart a pipeline that is already streaming`() {
        val fixture = running()
        fixture.controller.onScreenStateChanged(interactive = false, keyguardLocked = false)
        fixture.controller.onScreenStateChanged(interactive = true, keyguardLocked = true)

        When("the user unlocks") {
            fixture.controller.onScreenStateChanged(interactive = true, keyguardLocked = false)
        }

        Then("the running session is left alone") {
            assertEquals(2, fixture.camera.starts)
            assertEquals(1, fixture.camera.stops)
            assertTrue(fixture.controller.isRunning)
        }
    }

    @Test
    fun `surface recreation on the lock screen restarts hardware`() {
        val fixture = running()
        fixture.controller.onSurfaceAvailabilityChanged(false)
        fixture.controller.onScreenStateChanged(interactive = true, keyguardLocked = true)

        When("the surface is recreated on the lock screen") {
            fixture.controller.onSurfaceAvailabilityChanged(true)
        }

        Then("the camera starts") {
            assertTrue(fixture.controller.isRunning)
            assertEquals(2, fixture.camera.starts)
        }
    }

    @Test
    fun `a keyguard report alone never stops an idle pipeline`() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = WallpaperEngineController(camera, inference)

        controller.onScreenStateChanged(interactive = true, keyguardLocked = true)

        assertEquals(0, camera.starts)
        assertEquals(0, inference.starts)
        assertFalse(controller.isRunning)
    }
}
