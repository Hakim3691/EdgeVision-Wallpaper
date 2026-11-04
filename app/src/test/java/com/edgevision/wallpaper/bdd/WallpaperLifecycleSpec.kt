package com.edgevision.wallpaper.bdd

import com.edgevision.wallpaper.wallpaper.EngineResource
import com.edgevision.wallpaper.wallpaper.WallpaperEngineController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature: battery-optimised camera feed (original PRD requirement).
 *
 * Given the wallpaper is active, when the home screen is covered then the camera
 * session and Python inference must stop synchronously, and both must restart
 * when the launcher is visible again.
 */
class WallpaperLifecycleSpec {

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
    fun `covering the home screen releases the camera and inference immediately`() {
        val camera = FakeResource()
        val inference = FakeResource()
        lateinit var controller: WallpaperEngineController

        Given("a wallpaper that is visible with a surface") {
            controller = WallpaperEngineController(camera, inference)
            controller.onSurfaceAvailabilityChanged(true)
            controller.onVisibilityChanged(true)
            assertEquals(1, camera.starts)
            assertEquals(1, inference.starts)
        }

        When("the launcher is covered by another app") {
            controller.onVisibilityChanged(false)
        }

        Then("camera hardware and Python inference are released in the same call") {
            assertEquals(1, camera.stops)
            assertEquals(1, inference.stops)
            assertEquals(WallpaperEngineController.State.HIDDEN, controller.state)
        }
    }

    @Test
    fun `returning to the launcher restarts detection`() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = WallpaperEngineController(camera, inference)

        Given("a wallpaper that was visible and then hidden") {
            controller.onSurfaceAvailabilityChanged(true)
            controller.onVisibilityChanged(true)
            controller.onVisibilityChanged(false)
        }

        When("the user returns to the home screen") {
            controller.onVisibilityChanged(true)
        }

        Then("both resources stream again") {
            assertEquals(2, camera.starts)
            assertEquals(2, inference.starts)
            assertEquals(WallpaperEngineController.State.VISIBLE, controller.state)
        }
    }

    @Test
    fun `a visible wallpaper without a surface never acquires the camera`() {
        val camera = FakeResource()
        val controller = WallpaperEngineController(camera, FakeResource())

        Given("the wallpaper is visible but the surface is not ready") {
            controller.onVisibilityChanged(true)
        }

        When("nothing else happens") {
            Then("the camera stays closed and the engine reports IDLE") {
                assertEquals(0, camera.starts)
                assertEquals(WallpaperEngineController.State.IDLE, controller.state)
            }
        }
    }

    @Test
    fun `a camera start failure closes inference and reports the error`() {
        val inference = FakeResource()
        val errors = mutableListOf<String>()
        val failingCamera = object : EngineResource {
            override fun start() = throw IllegalStateException("camera busy")
            override fun stop() = Unit
        }

        Given("a camera pipeline that refuses to start") { }

        When("the wallpaper becomes visible") {
            val controller = WallpaperEngineController(failingCamera, inference) { errors += it }
            controller.onSurfaceAvailabilityChanged(true)
            controller.onVisibilityChanged(true)

            Then("inference is torn down and the failure is reported") {
                assertEquals(1, inference.stops)
                assertTrue(errors.any { it.contains("camera pipeline") })
            }
        }
    }
}
