package com.edgevision.wallpaper.inference

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the Python detector and a single inference worker.
 *
 * Frames are dropped rather than queued when the worker is busy, which keeps the
 * camera callback cheap and prevents a backlog from warming the device after a
 * slow frame. Stopping invalidates in-flight work and releases the Python-side
 * interpreter, so no inference thread survives a paused wallpaper.
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

    @Volatile
    private var detector: PyObject? = null
    @Volatile
    private var lastError: String? = null
    @Volatile
    private var lastErrorAtMs: Long = 0L

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
                    val module = ensureDetector()
                    val json = module.callAttr(
                        "detect",
                        rgb,
                        INPUT_SIZE,
                        INPUT_SIZE,
                        MIN_SCORE,
                    ).toString()
                    if (started.get() && session.get() == expectedSession) {
                        listener.onInferenceResult(DetectionParser.parse(json))
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

    fun close() {
        stop()
        // Let the queued detector shutdown finish; the worker is a daemon thread.
        worker.shutdown()
    }

    private fun ensureDetector(): PyObject {
        synchronized(initLock) {
            detector?.let { return it }
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(appContext))
            }
            val module = Python.getInstance().getModule("hazard_detector")
            val modelFile = ModelAssets.ensureModel(appContext)
            val status = module.callAttr(
                "initialize",
                modelFile.absolutePath,
                INPUT_SIZE,
            ).toString()
            val backend = runCatching {
                JSONObject(status).optString("backend", "unknown")
            }.getOrDefault("unknown")
            Log.i(TAG, "Python detector ready (backend=$backend): $status")
            detector = module
            return module
        }
    }

    private fun releaseDetector() {
        val module = detector ?: return
        detector = null
        runCatching { module.callAttr("shutdown") }
            .onFailure { Log.w(TAG, "Python detector shutdown failed", it) }
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
        const val INPUT_SIZE = 320
        private const val MIN_SCORE = 0.35f
        private const val ERROR_REPEAT_MS = 5_000L
        private const val TAG = "HazardInference"
    }
}
