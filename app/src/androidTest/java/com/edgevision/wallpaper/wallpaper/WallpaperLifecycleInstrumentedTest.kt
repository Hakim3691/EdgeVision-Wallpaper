package com.edgevision.wallpaper.wallpaper

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallpaperLifecycleInstrumentedTest {

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
    fun coveringTheHomeScreenReleasesCameraAndInferenceImmediately() {
        val camera = FakeResource()
        val inference = FakeResource()
        val controller = WallpaperEngineController(camera, inference)

        controller.onSurfaceAvailabilityChanged(true)
        controller.onVisibilityChanged(true)
        assertEquals(1, camera.starts)
        assertEquals(1, inference.starts)

        controller.onVisibilityChanged(false)
        assertEquals(1, camera.stops)
        assertEquals(1, inference.stops)
        assertEquals(WallpaperEngineController.State.HIDDEN, controller.state)

        controller.onVisibilityChanged(true)
        assertEquals(2, camera.starts)
        assertEquals(2, inference.starts)
        assertEquals(WallpaperEngineController.State.VISIBLE, controller.state)
    }
}
