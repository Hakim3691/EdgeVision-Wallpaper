package com.edgevision.wallpaper.inference

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the Kotlin TFLite interpreter for hazard detection.
 *
 * Replaces the Chaquopy Python pipeline with a direct TensorFlow Lite Java
 * interpreter, eliminating Python startup overhead and reducing per-frame latency.
 * Frames are dropped rather than queued when the worker is busy, which keeps the
 * camera callback cheap and prevents a backlog from warming the device after a
 * slow frame. Stopping invalidates in-flight work and releases the interpreter,
 * so no inference thread survives a paused wallpaper.
 */
class HazardInferenceEngine(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onInferenceResult(result: InferenceResult)
        fun onInferenceError(message: String)
    }

    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "edgevision-inference").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val session = AtomicInteger(0)
    private val initLock = Any()

    /** Kotlin TFLite detector wrapping the EfficientDet-Lite0 model. */
    @Volatile
    private var detector: TfliteDetector? = null

    fun start() {
        started.set(true)
        session.incrementAndGet()
    }

    fun stop() {
        started.set(false)
        session.incrementAndGet()
        runCatching { worker.execute { releaseDetector() } }
    }

    fun submitFrame(rgb: ByteArray) {
        if (!started.get()) return
        if (!busy.compareAndSet(false, true)) return

        val expectedSession = session.get()
        val accepted = runCatching {
            worker.execute {
                try {
                    if (!started.get() || session.get() != expectedSession) return@execute
                        val result = detector?.detect(rgb)
                        if (started.get() && session.get() == expectedSession && result != null) {
                            listener.onInferenceResult(result)
                        }
                    }
                } catch (throwable: Throwable) {
                    reportError(throwable)
                } finally {
                    busy.set(false)
                }
            }
        }
        if (accepted.isFailure) {
            busy.set(false)
        }
    }

    private fun releaseDetector() {
        val d = detector ?: return
        synchronized(initLock) {
            detector = null
        }
        runCatching { d.close() }
            .onFailure { Log.w(TAG, "Tflite detector close failed", it) }
    }

    private fun reportError(throwable: Throwable) {
        val message = throwable.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(240)
            ?: throwable.javaClass.simpleName
        val now = SystemClock.elapsedRealtime()
        if (message != lastError || now - lastErrorAtMs > ERROR_REPEAT_MS) {
            lastError = message
            lastErrorAtMs = now
            Log.w(TAG, "Inference failed: $message", throwable)
            listener.onInferenceError(message)
        }
    }

    companion object {
        private const val TAG = "HazardInference"
    }
}
