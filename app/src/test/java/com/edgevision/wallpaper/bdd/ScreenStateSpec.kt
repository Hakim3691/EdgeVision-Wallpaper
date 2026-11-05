package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.wallpaper.EngineResource
import com.edgevision.wallpaper.wallpaper.WallpaperEngineController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: a dark screen is not a reason to run the camera.
 *
 * Android usually hides the wallpaper when the screen turns off, but not on every
 * OEM build and not always promptly. Camera capture plus inference behind the
 * keyguard burns battery and can trip background-camera restrictions, so the
 * controller treats "not interactive" as a third reason to stop, alongside
 * "hidden" and "no surface".
 */
class ScreenStateSpec {

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

    @Test
    fun `turning the screen off releases the camera`() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = WallpaperEngineController(camera, inference)

        Given("a visible wallpaper with a live surface") {
            controller.onSurfaceAvailabilityChanged(true)
            controller.onInteractiveChanged(true)
            controller.onVisibilityChanged(true)
            assertEquals(1, camera.starts)
            assertEquals(1, inference.starts)
        }

        When("the screen turns off while the wallpaper stays visible") {
            controller.onInteractiveChanged(false)
        }

        Then("both resources stop and the engine reports a pause, not a hide") {
            assertEquals(1, camera.stops)
            assertEquals(1, inference.stops)
            assertEquals(WallpaperEngineController.State.PAUSED, controller.state)
        }
    }

    @Test
    fun `turning the screen back on resumes capture`() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = WallpaperEngineController(camera, inference)

        Given("a paused engine behind a dark screen") {
            controller.onSurfaceAvailabilityChanged(true)
            controller.onVisibilityChanged(true)
            controller.onInteractiveChanged(false)
            assertEquals(WallpaperEngineController.State.PAUSED, controller.state)
        }

        When("the screen turns on again") {
            controller.onInteractiveChanged(true)
        }

        Then("the camera and detector start again without user action") {
            assertEquals(2, camera.starts)
            assertEquals(2, inference.starts)
            assertEquals(WallpaperEngineController.State.VISIBLE, controller.state)
        }
    }

    @Test
    fun `a dark screen never starts hardware on its own`() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = WallpaperEngineController(camera, inference)

        Given("the screen is off") {
            controller.onInteractiveChanged(false)
        }

        When("the wallpaper becomes visible and gains a surface") {
            controller.onVisibilityChanged(true)
            controller.onSurfaceAvailabilityChanged(true)
        }

        Then("nothing is captured until the screen is interactive") {
            assertEquals(0, camera.starts)
            assertEquals(0, inference.starts)
            assertEquals(WallpaperEngineController.State.PAUSED, controller.state)
        }
    }

    @Test
    fun `hiding the wallpaper still wins over the screen state`() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = WallpaperEngineController(camera, inference)

        Given("a running wallpaper whose screen turns off") {
            controller.onSurfaceAvailabilityChanged(true)
            controller.onVisibilityChanged(true)
            controller.onInteractiveChanged(false)
        }

        When("the wallpaper is hidden as well") {
            controller.onVisibilityChanged(false)
        }

        Then("the engine reports a hidden wallpaper rather than a pause") {
            assertEquals(WallpaperEngineController.State.HIDDEN, controller.state)
            assertEquals(1, camera.stops)
            assertTrue("resources stay stopped", camera.stops == inference.stops)
        }
    }
}
