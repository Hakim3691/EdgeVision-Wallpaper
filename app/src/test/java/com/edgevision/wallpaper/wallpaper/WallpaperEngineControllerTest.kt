package com.edgevision.wallpaper.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gherkin: Battery-optimized camera feed.
 *
 * Given the wallpaper is active, when the home screen is covered
 * (onVisibilityChanged(false)), then the camera session and Python inference
 * must stop immediately. Returning to the launcher (onVisibilityChanged(true))
 * must start both again.
 */
class WallpaperEngineControllerTest {

    private class FakeResource(private val failOnStart: Boolean = false) : EngineResource {
        var startCount = 0
        var stopCount = 0

        override fun start() {
            if (failOnStart) throw IllegalStateException("start failed")
            startCount += 1
        }

        override fun stop() {
            stopCount += 1
        }
    }

    private fun runningController(
        camera: FakeResource = FakeResource(),
        inference: FakeResource = FakeResource(),
        errors: MutableList<String> = mutableListOf(),
    ): WallpaperEngineController {
        val controller = WallpaperEngineController(camera, inference) { errors += it }
        controller.onSurfaceAvailabilityChanged(true)
        controller.onVisibilityChanged(true)
        return controller
    }

    @Test
    fun hidingWallpaperImmediatelyReleasesCameraAndInference() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = runningController(camera, inference)

        controller.onVisibilityChanged(false)

        assertEquals("camera must be released", 1, camera.stopCount)
        assertEquals("Python inference must stop", 1, inference.stopCount)
        assertEquals(WallpaperEngineController.State.HIDDEN, controller.state)
    }

    @Test
    fun returningToLauncherRestartsCameraAndInference() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = runningController(camera, inference)

        controller.onVisibilityChanged(false)
        controller.onVisibilityChanged(true)

        assertEquals(2, camera.startCount)
        assertEquals(1, camera.stopCount)
        assertEquals(2, inference.startCount)
        assertEquals(1, inference.stopCount)
        assertEquals(WallpaperEngineController.State.VISIBLE, controller.state)
    }

    @Test
    fun duplicateVisibilityAndSurfaceEventsAreIdempotent() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = runningController(camera, inference)

        controller.onVisibilityChanged(true)
        controller.onSurfaceAvailabilityChanged(true)

        assertEquals(1, camera.startCount)
        assertEquals(1, inference.startCount)
        assertEquals(WallpaperEngineController.State.VISIBLE, controller.state)
    }

    @Test
    fun visibleWithoutSurfaceDoesNotAcquireCamera() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = WallpaperEngineController(camera, inference)

        controller.onVisibilityChanged(true)
        assertEquals(0, camera.startCount)
        assertEquals(WallpaperEngineController.State.IDLE, controller.state)

        controller.onSurfaceAvailabilityChanged(true)
        assertEquals(1, camera.startCount)
        assertEquals(WallpaperEngineController.State.VISIBLE, controller.state)
    }

    @Test
    fun surfaceLossStopsHardwareWhileVisible() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = runningController(camera, inference)

        controller.onSurfaceAvailabilityChanged(false)

        assertEquals(1, camera.stopCount)
        assertEquals(1, inference.stopCount)
        assertEquals(WallpaperEngineController.State.IDLE, controller.state)
    }

    @Test
    fun hiddenStateIsNotRestartedBySurfaceCreation() {
        val camera = FakeResource()
        val controller = runningController(camera = camera)
        controller.onVisibilityChanged(false)

        controller.onSurfaceAvailabilityChanged(false)
        controller.onSurfaceAvailabilityChanged(true)

        assertEquals("camera must stay closed while hidden", 1, controller.let { camera.startCount })
        assertEquals(1, camera.stopCount)
    }

    @Test
    fun destroyStopsRunningResourcesOnce() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = runningController(camera, inference)

        controller.destroy()
        controller.destroy()
        controller.onVisibilityChanged(true)

        assertEquals(1, camera.stopCount)
        assertEquals(1, inference.stopCount)
        assertEquals(WallpaperEngineController.State.DESTROYED, controller.state)
    }

    @Test
    fun startFailureClosesBothResourcesAndReportsError() {
        val camera = FakeResource(failOnStart = true)
        val inference = FakeResource()
        val errors = mutableListOf<String>()
        val controller = runningController(camera, inference, errors)

        assertEquals(0, camera.startCount)
        assertEquals(1, inference.stopCount)
        assertEquals(WallpaperEngineController.State.IDLE, controller.state)
        assertTrue(errors.any { it.contains("camera pipeline") })
    }
}
