package com.edgevision.wallpaper.wallpaper

interface EngineResource {
    fun start()
    fun stop()
}

/**
 * Drives hardware and inference from three independent inputs: wallpaper
 * visibility, surface availability, and whether the screen is interactive. None
 * of them is trustworthy alone (the launcher can hide the wallpaper while the
 * surface still exists, and vice versa), so the controller reconciles all three
 * and only runs resources when all are true.
 *
 * The screen-off input matters for battery: a wallpaper can stay "visible" behind
 * the keyguard on some OEM builds, and camera plus inference behind a dark screen
 * costs power for nobody.
 *
 * The class is deliberately Android-free so the Gherkin visibility scenarios can
 * be tested with fakes on both the JVM and an instrumented device.
 */
class WallpaperEngineController(
    private val camera: EngineResource,
    private val inference: EngineResource,
    private val onError: (String) -> Unit = {},
) {

    enum class State {
        IDLE,
        VISIBLE,
        HIDDEN,

        /** Visible with a surface, but the screen is off. */
        PAUSED,
        DESTROYED,
    }

    private var visible = false
    private var surfaceAvailable = false
    private var interactive = true
    private var resourcesRunning = false
    private var destroyed = false

    @get:Synchronized
    var state: State = State.IDLE
        private set

    @Synchronized
    fun onVisibilityChanged(isVisible: Boolean) {
        if (destroyed) return
        visible = isVisible
        reconcile()
    }

    @Synchronized
    fun onSurfaceAvailabilityChanged(available: Boolean) {
        if (destroyed) return
        surfaceAvailable = available
        reconcile()
    }

    @Synchronized
    fun onInteractiveChanged(isInteractive: Boolean) {
        if (destroyed) return
        interactive = isInteractive
        reconcile()
    }

    @Synchronized
    fun destroy() {
        if (destroyed) return
        destroyed = true
        visible = false
        surfaceAvailable = false
        if (resourcesRunning) {
            stopResources()
        }
        state = State.DESTROYED
    }

    private fun reconcile() {
        if (destroyed) return
        val shouldRun = visible && surfaceAvailable && interactive
        if (shouldRun && !resourcesRunning) {
            try {
                camera.start()
                inference.start()
                resourcesRunning = true
                state = State.VISIBLE
            } catch (throwable: Throwable) {
                runCatching { inference.stop() }
                runCatching { camera.stop() }
                resourcesRunning = false
                state = State.IDLE
                onError("Could not start the camera pipeline: ${throwable.message}")
            }
        } else if (!shouldRun && resourcesRunning) {
            stopResources()
            state = stoppedState()
        } else if (!shouldRun) {
            state = stoppedState()
        }
    }

    private fun stoppedState(): State = when {
        !visible -> State.HIDDEN
        !interactive -> State.PAUSED
        else -> State.IDLE
    }

    private fun stopResources() {
        // Stop inference before camera so no late frame can enqueue new work.
        runCatching { inference.stop() }
            .onFailure { onError("Inference shutdown failed: ${it.message}") }
        runCatching { camera.stop() }
            .onFailure { onError("Camera shutdown failed: ${it.message}") }
        resourcesRunning = false
    }
}
