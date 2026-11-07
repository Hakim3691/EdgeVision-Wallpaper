package com.edgevision.wallpaper.wallpaper

interface EngineResource {
    fun start()
    fun stop()
}

/**
 * Single authority over whether the camera and detector may run.
 *
 * Three independent inputs decide it:
 *
 *  - wallpaper visibility (the launcher can hide the wallpaper while the surface
 *    still exists),
 *  - surface availability (the surface can be destroyed while still "visible"),
 *  - screen interactivity (the display can be off while the engine is bound).
 *
 * The keyguard is deliberately *not* part of the gate. A locked, awake screen is
 * still a screen the wallpaper is drawn on, and stopping there froze the last
 * frame for the whole lock screen. Holding the rear camera does not block face
 * unlock on devices that authenticate with the front camera, and on devices that
 * do preempt the sensor the camera reports a disconnect and the controller retries
 * once the sensor is free again. That makes the pause coincide with the biometric
 * event instead of the entire lock screen.
 *
 * The class is deliberately Android-free so the Gherkin scenarios can run on the
 * JVM with fakes.
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
        PAUSED,
        DESTROYED,
    }

    private var visible = false
    private var surfaceAvailable = false
    private var screenInteractive = true
    private var keyguardLocked = false
    private var resourcesRunning = false
    private var destroyed = false

    @get:Synchronized
    var state: State = State.IDLE
        private set

    @get:Synchronized
    val isRunning: Boolean
        get() = resourcesRunning

    /** Informational: used to report why the pipeline is paused, not to gate it. */
    @get:Synchronized
    val isKeyguardLocked: Boolean
        get() = keyguardLocked

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

    /**
     * Applies screen and keyguard state.
     *
     * A locked keyguard does not stop an already-running pipeline; only the screen
     * going dark does. The keyguard flag is retained for diagnostics so the status
     * line can distinguish "screen off" from "biometric prompt".
     */
    @Synchronized
    fun onScreenStateChanged(interactive: Boolean, keyguardLocked: Boolean) {
        if (destroyed) return
        this.screenInteractive = interactive
        this.keyguardLocked = keyguardLocked
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
        val shouldRun = visible && surfaceAvailable && screenInteractive
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
        } else if (!shouldRun) {
            if (resourcesRunning) {
                stopResources()
            }
            state = when {
                !visible -> State.HIDDEN
                !screenInteractive -> State.PAUSED
                else -> State.IDLE
            }
        }
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
