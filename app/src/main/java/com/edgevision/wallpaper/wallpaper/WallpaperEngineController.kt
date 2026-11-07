package com.edgevision.wallpaper.wallpaper

interface EngineResource {
    fun start()
    fun stop()
}

/**
 * Single authority over whether the camera and detector may run.
 *
 * Four independent inputs decide it, and none is trustworthy alone:
 *
 *  - wallpaper visibility (the launcher can hide the wallpaper while the surface
 *    still exists),
 *  - surface availability (the surface can be destroyed while still "visible"),
 *  - screen interactivity (the display can be off while the engine is bound),
 *  - keyguard state (the lock screen must be able to use the camera for face
 *    unlock; holding it there breaks biometrics).
 *
 * Resources run only when all four agree. Keyguard-locked while otherwise visible
 * is reported as [State.PAUSED] so a screen-on event on the lock screen can never
 * reopen the camera behind the keyguard.
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
     * Applies screen and keyguard state. A locked keyguard always pauses the
     * pipeline, regardless of visibility, so biometric unlock keeps the camera.
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
        val shouldRun = visible && surfaceAvailable && screenInteractive && !keyguardLocked
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
            state = when {
                !visible -> State.HIDDEN
                !screenInteractive || keyguardLocked -> State.PAUSED
                else -> State.IDLE
            }
        } else if (!shouldRun) {
            state = when {
                !visible -> State.HIDDEN
                !screenInteractive || keyguardLocked -> State.PAUSED
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
