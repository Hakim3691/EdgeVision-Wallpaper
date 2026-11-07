package com.edgevision.wallpaper.inference

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.edgevision.wallpaper.diagnostics.PipelineDiagnostics
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the Python detector and a single inference worker.
 *
 * Frames are dropped rather than queued when the worker is busy, which keeps the
 * camera callback cheap and prevents a backlog from warming the device after a
 * slow frame.
 *
 * Visibility changes do **not** release the interpreter. Recreating Chaquopy and
 * TFLite on every launcher transition cost seconds and left detection cold most
 * of the time; the interpreter now stays warm until [close]. Only in-flight
 * results are invalidated by [stop].
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
    private val bufferLock = Any()
    private val buffers = ArrayDeque<ByteArray>()

    @Volatile
    private var detector: PyObject? = null
    @Volatile
    private var lastError: String? = null
    @Volatile
    private var lastErrorAtMs = 0L

    fun start() {
        started.set(true)
        session.incrementAndGet()
    }

    /** Pauses result delivery but keeps the interpreter warm for the next frame. */
    fun stop() {
        started.set(false)
        session.incrementAndGet()
    }

    /** True when the worker can take another frame without dropping it. */
    fun isIdle(): Boolean = !busy.get()

    fun submitFrame(rgb: ByteArray) {
        if (!started.get()) return
        if (!busy.compareAndSet(false, true)) return

        val expectedSession = session.get()
        val payload = obtainBuffer(rgb.size)
        rgb.copyInto(payload)

        val accepted = runCatching {
            worker.execute {
                try {
                    if (!started.get() || session.get() != expectedSession) return@execute
                    val module = ensureDetector()
                    val json = module.callAttr(
                        "detect",
                        payload,
                        INPUT_SIZE,
                        INPUT_SIZE,
                        MIN_SCORE,
                    ).toString()
                    if (started.get() && session.get() == expectedSession) {
                        PipelineDiagnostics.detectionSucceeded()
                        listener.onInferenceResult(DetectionParser.parse(json))
                    }
                } catch (throwable: Throwable) {
                    reportError(throwable)
                } finally {
                    busy.set(false)
                    recycleBuffer(payload)
                }
            }
        }
        if (accepted.isFailure) {
            busy.set(false)
            recycleBuffer(payload)
        }
    }

    fun close() {
        stop()
        // Let the queued detector shutdown finish; the worker is a daemon thread.
        runCatching { worker.execute { releaseDetector() } }
        worker.shutdown()
    }

    private fun obtainBuffer(size: Int): ByteArray = synchronized(bufferLock) {
        val pooled = buffers.removeLastOrNull()
        if (pooled != null && pooled.size == size) pooled else ByteArray(size)
    }

    private fun recycleBuffer(buffer: ByteArray) {
        synchronized(bufferLock) {
            if (buffers.size < MAX_POOLED_BUFFERS) buffers.addLast(buffer)
        }
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
            PipelineDiagnostics.detectorReady(backend)
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
        PipelineDiagnostics.inferenceError(message)
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
        private const val MAX_POOLED_BUFFERS = 2
        private const val TAG = "HazardInference"
    }
}
