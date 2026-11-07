package com.edgevision.wallpaper.diagnostics

import android.os.SystemClock

/**
 * In-memory status of the camera pipeline, surfaced by the settings screen.
 *
 * Failures used to be log-only, which is why a wallpaper that silently lost the
 * camera or the detector looked identical to one that was working. The fields are
 * volatile because they are written from the camera, inference and main threads.
 */
object PipelineDiagnostics {

    @Volatile
    var cameraStatus: String = "idle"
        private set

    @Volatile
    var lastCameraError: String? = null
        private set

    @Volatile
    var detectorBackend: String? = null
        private set

    @Volatile
    var lastInferenceError: String? = null
        private set

    @Volatile
    var cadenceMs: Long = 0L
        private set

    @Volatile
    var lastDetectionAtMs: Long = 0L
        private set

    fun cameraStarted(size: String) {
        cameraStatus = "streaming $size"
        lastCameraError = null
    }

    fun cameraStopped() {
        cameraStatus = "idle"
    }

    fun cameraError(message: String) {
        cameraStatus = "error"
        lastCameraError = message
    }

    fun cadenceChanged(intervalMs: Long) {
        cadenceMs = intervalMs
    }

    fun detectorReady(backend: String) {
        detectorBackend = backend
        lastInferenceError = null
    }

    fun inferenceError(message: String) {
        lastInferenceError = message
    }

    fun detectionSucceeded() {
        lastDetectionAtMs = SystemClock.elapsedRealtime()
    }

    /** Human-readable one-liner; the settings screen adds its own labels. */
    fun summary(): String {
        val error = lastCameraError ?: lastInferenceError
        return buildString {
            append("camera=")
            append(cameraStatus)
            append(", cadence=")
            append(cadenceMs)
            append("ms")
            detectorBackend?.let {
                append(", detector=")
                append(it)
            }
            if (error != null) {
                append(", error=")
                append(error)
            }
        }
    }
}
