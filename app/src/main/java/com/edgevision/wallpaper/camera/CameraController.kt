package com.edgevision.wallpaper.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import android.hardware.display.DisplayManager
import androidx.core.content.ContextCompat
import com.edgevision.wallpaper.diagnostics.PipelineDiagnostics
import kotlin.math.roundToInt

/**
 * Owns Camera2 hardware for the wallpaper.
 *
 * Preview and detection run on independent cadences: the live view updates at
 * [setPreviewIntervalMs] (33 ms by default) while the detector receives a frame
 * every [setDetectionIntervalMs]. Coupling the two — as the previous revision did
 * — made the wallpaper drop to 1–4 fps whenever the performance governor slowed
 * detection, which is what made the live view look like a still image.
 *
 * Camera failures are retried with bounded backoff instead of killing the stream
 * permanently: a face-unlock attempt or another app briefly taking the sensor is
 * a normal event for a wallpaper, not a terminal error.
 */
class CameraController(
    private val context: Context,
    private val listener: Listener,
    private val detectionReady: () -> Boolean = { true },
) {

    interface Listener {
        fun onFrame(frame: ConvertedFrame)
        fun onCameraStarted(previewSize: Size)
        fun onCameraError(message: String, cause: Throwable? = null)
    }

    private val lock = Any()
    private val poolLock = Any()
    private val cameraThread = HandlerThread("edgevision-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val converter = YuvFrameConverter()
    private val openRunnable = Runnable { openCamera() }

    @Volatile
    private var running = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var sensorOrientation = 90
    private var previewSize: Size? = null
    private var fpsRange: Range<Int>? = null
    private var continuousAfSupported = false

    private var lastPreviewAtMs = 0L
    private var lastDetectionAtMs = 0L
    private var retryAttempt = 0

    /** Detection cadence, driven by the performance governor. */
    @Volatile
    private var detectionIntervalMs = DEFAULT_DETECTION_INTERVAL_MS

    /** Display cadence. Never widened by the governor; it only affects detection. */
    @Volatile
    private var previewIntervalMs = DEFAULT_PREVIEW_INTERVAL_MS

    /** Reusable preview bitmaps. The renderer must call [ConvertedFrame.markConsumed]. */
    private var previewPool: Array<Bitmap?> = arrayOfNulls(PREVIEW_POOL_SIZE)
    private var previewInUse = BooleanArray(PREVIEW_POOL_SIZE)
    private var poolWidth = 0
    private var poolHeight = 0

    private var detectionBuffer = ByteArray(0)

    /** JPEG output for burst capture, added to the same session as the preview. */
    private var jpegReader: ImageReader? = null
    private var pendingJpeg: ((ByteArray?) -> Unit)? = null

    fun setDetectionIntervalMs(intervalMs: Long) {
        detectionIntervalMs = intervalMs.coerceAtLeast(16L)
    }

    /**
     * Applies the display cadence. Kept separate from detection so a thermal or
     * battery profile can slow inference without freezing the wallpaper.
     */
    fun setPreviewIntervalMs(intervalMs: Long) {
        previewIntervalMs = intervalMs.coerceIn(16L, 100L)
    }

    /**
     * Requests one JPEG from the running session. The callback receives null when
     * the session is gone, so a burst aborts instead of hanging.
     */
    fun captureJpeg(onCaptured: (ByteArray?) -> Unit) {
        if (!running) {
            onCaptured(null)
            return
        }
        cameraHandler.post {
            val session = synchronized(lock) { captureSession }
            val reader = synchronized(lock) { jpegReader }
            val device = synchronized(lock) { cameraDevice }
            if (!running || session == null || reader == null || device == null) {
                onCaptured(null)
                return@post
            }
            synchronized(lock) { pendingJpeg = onCaptured }
            try {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    if (continuousAfSupported) {
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                        )
                    }
                }.build()
                session.capture(request, null, cameraHandler)
            } catch (throwable: Throwable) {
                synchronized(lock) { pendingJpeg = null }
                Log.w(TAG, "Still capture failed", throwable)
                onCaptured(null)
            }
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        if (!hasCameraPermission()) {
            listener.onCameraError("Camera permission has not been granted")
            return
        }
        running = true
        retryAttempt = 0
        lastPreviewAtMs = 0L
        lastDetectionAtMs = 0L
        cameraHandler.post(openRunnable)
    }

    /**
     * Stops frame delivery and closes the capture session/device immediately.
     * The close calls are allowed from any thread, so pausing the wallpaper is
     * not delayed by the camera handler queue.
     */
    @Synchronized
    fun stop() {
        running = false
        retryAttempt = 0
        cameraHandler.removeCallbacks(openRunnable)
        closeHardware()
        PipelineDiagnostics.cameraStopped()
    }

    fun close() {
        stop()
        synchronized(poolLock) {
            previewPool = arrayOfNulls(PREVIEW_POOL_SIZE)
            previewInUse = BooleanArray(PREVIEW_POOL_SIZE)
            poolWidth = 0
            poolHeight = 0
        }
        cameraThread.quitSafely()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun closeHardware() {
        var pending: ((ByteArray?) -> Unit)? = null
        synchronized(lock) {
            captureSession?.let { session ->
                runCatching { session.stopRepeating() }
                runCatching { session.close() }
            }
            captureSession = null
            cameraDevice?.let { device ->
                runCatching { device.close() }
            }
            cameraDevice = null
            imageReader?.let { reader ->
                runCatching { reader.setOnImageAvailableListener(null, null) }
                runCatching { reader.close() }
            }
            imageReader = null
            jpegReader?.let { reader ->
                runCatching { reader.setOnImageAvailableListener(null, null) }
                runCatching { reader.close() }
            }
            jpegReader = null
            pending = pendingJpeg
            pendingJpeg = null
        }
        // Let a pending burst fail fast instead of waiting for a frame that will
        // never arrive. Invoked outside the lock so the sink cannot deadlock us.
        pending?.invoke(null)
    }

    /**
     * Closes the hardware and retries. Fast, bounded retries handle transient
     * contention; after them a slow retry keeps the wallpaper recoverable without
     * spinning on a camera another app is holding.
     */
    private fun handleFailure(message: String, cause: Throwable? = null) {
        val wasRunning = running
        closeHardware()
        if (!wasRunning) return

        if (!hasCameraPermission()) {
            running = false
            listener.onCameraError(message, cause)
            return
        }

        val delayMs = if (retryAttempt < MAX_FAST_RETRIES) {
            fastRetryDelayMs(retryAttempt)
        } else {
            SLOW_RETRY_MS
        }
        if (retryAttempt < MAX_FAST_RETRIES) retryAttempt += 1

        listener.onCameraError(message, cause)
        Log.i(TAG, "Camera unavailable; retrying in ${delayMs}ms")
        cameraHandler.removeCallbacks(openRunnable)
        cameraHandler.postDelayed(openRunnable, delayMs)
    }

    private fun fastRetryDelayMs(attempt: Int): Long =
        (RETRY_BASE_MS shl attempt.coerceIn(0, 4)).coerceAtMost(RETRY_MAX_MS)

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!running || cameraDevice != null) return
        if (!hasCameraPermission()) {
            running = false
            listener.onCameraError("Camera permission has not been granted")
            return
        }

        val manager = context.getSystemService(CameraManager::class.java)
            ?: run {
                handleFailure("Camera service is unavailable")
                return
            }

        try {
            val cameraId = selectCameraId(manager) ?: run {
                handleFailure("No camera is available on this device")
                return
            }
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val streamConfiguration: StreamConfigurationMap = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: run {
                    handleFailure("Camera stream configuration is unavailable")
                    return
                }
            val size = selectPreviewSize(
                streamConfiguration.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray(),
            ) ?: run {
                handleFailure("Camera does not expose a YUV preview size")
                return
            }

            sensorOrientation = characteristics
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            continuousAfSupported = characteristics
                .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
            fpsRange = selectFpsRange(
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES),
            )
            previewSize = size

            val jpegSize = selectJpegSize(
                streamConfiguration.getOutputSizes(ImageFormat.JPEG) ?: emptyArray(),
                size,
            )
            val jpeg = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            jpeg.setOnImageAvailableListener({ ready -> handleJpeg(ready) }, cameraHandler)
            synchronized(lock) {
                jpegReader = jpeg
            }

            val reader = ImageReader.newInstance(
                size.width,
                size.height,
                ImageFormat.YUV_420_888,
                3,
            )
            reader.setOnImageAvailableListener({ readyReader -> handleImage(readyReader) }, cameraHandler)
            synchronized(lock) {
                imageReader = reader
            }

            lastPreviewAtMs = 0L
            lastDetectionAtMs = 0L
            manager.openCamera(cameraId, deviceCallback, cameraHandler)
        } catch (throwable: Throwable) {
            handleFailure("Could not open the camera: ${throwable.message}", throwable)
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (!running) {
                camera.close()
                return
            }
            synchronized(lock) {
                cameraDevice = camera
            }
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            handleFailure("Camera was disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            handleFailure("Camera device error $error")
        }
    }

    private fun createSession(camera: CameraDevice) {
        val reader = synchronized(lock) { imageReader }
        if (reader == null || !running) {
            camera.close()
            return
        }

        val jpeg = synchronized(lock) { jpegReader }
        val outputs = buildList {
            add(reader.surface)
            if (jpeg != null) add(jpeg.surface)
        }

        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running) {
                            session.close()
                            return
                        }
                        synchronized(lock) {
                            captureSession = session
                        }
                        startRepeating(session, camera, reader)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        handleFailure("Camera capture session configuration failed")
                    }
                },
                cameraHandler,
            )
        } catch (throwable: Throwable) {
            handleFailure("Could not create a camera session: ${throwable.message}", throwable)
        }
    }

    private fun startRepeating(
        session: CameraCaptureSession,
        camera: CameraDevice,
        reader: ImageReader,
    ) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (continuousAfSupported) {
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                }
                fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }.build()

            session.setRepeatingRequest(request, null, cameraHandler)
            retryAttempt = 0
            lastPreviewAtMs = 0L
            lastDetectionAtMs = 0L
            listener.onCameraStarted(Size(reader.width, reader.height))
        } catch (throwable: Throwable) {
            handleFailure("Could not start camera frames: ${throwable.message}", throwable)
        }
    }

    private fun handleImage(reader: ImageReader) {
        if (!running) return
        val image = try {
            reader.acquireLatestImage()
        } catch (throwable: Throwable) {
            return
        } ?: return

        // Every delivered image is closed, even when it is not converted. Leaving
        // it in the queue stalls the producer between throttle windows.
        image.use { frame ->
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastPreviewAtMs < previewIntervalMs) return

            val previewWidth = YuvFrameConverter.PREVIEW_MAX_WIDTH
            val previewHeight = converter.previewHeightFor(frame)
            val bitmap = acquirePreview(previewWidth, previewHeight) ?: return
            val detectionDue = now - lastDetectionAtMs >= detectionIntervalMs && detectionReady()
            try {
                val converted = converter.convert(
                    image = frame,
                    previewWidth = previewWidth,
                    previewHeight = previewHeight,
                    detectionSize = DETECTION_SIZE,
                    rotationDegrees = rotationDegrees(),
                    preview = bitmap,
                    detectionTarget = if (detectionDue) ensureDetectionBuffer() else null,
                    onConsumed = { releasePreview(bitmap) },
                )
                lastPreviewAtMs = now
                if (detectionDue) lastDetectionAtMs = now
                listener.onFrame(converted)
            } catch (throwable: Throwable) {
                releasePreview(bitmap)
                Log.w(TAG, "Frame conversion failed", throwable)
            }
        }
    }

    private fun acquirePreview(width: Int, height: Int): Bitmap? = synchronized(poolLock) {
        if (poolWidth != width || poolHeight != height) {
            previewPool = arrayOfNulls(PREVIEW_POOL_SIZE)
            previewInUse = BooleanArray(PREVIEW_POOL_SIZE)
            poolWidth = width
            poolHeight = height
        }
        val index = previewInUse.indexOfFirst { !it }
        if (index < 0) return null
        val bitmap = previewPool[index]
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                previewPool[index] = it
            }
        previewInUse[index] = true
        bitmap
    }

    private fun releasePreview(bitmap: Bitmap) {
        synchronized(poolLock) {
            val index = previewPool.indexOfFirst { it === bitmap }
            if (index >= 0) previewInUse[index] = false
        }
    }

    private fun ensureDetectionBuffer(): ByteArray {
        val size = DETECTION_SIZE * DETECTION_SIZE * 3
        if (detectionBuffer.size != size) detectionBuffer = ByteArray(size)
        return detectionBuffer
    }

    private fun handleJpeg(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (throwable: Throwable) {
            null
        } ?: return

        val bytes = try {
            image.use { frame ->
                frame.planes.firstOrNull()?.buffer?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "Could not read JPEG bytes", throwable)
            null
        }

        val callback = synchronized(lock) {
            pendingJpeg.also { pendingJpeg = null }
        }
        callback?.invoke(bytes)
    }

    private fun selectJpegSize(sizes: Array<Size>, preview: Size): Size {
        if (sizes.isEmpty()) return preview
        return sizes
            .filter { it.width <= preview.width && it.height <= preview.height }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: preview
    }

    private fun rotationDegrees(): Int {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val displayRotation = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - displayRotation + 360) % 360
    }

    private fun selectCameraId(manager: CameraManager): String? {
        val ids = manager.cameraIdList
        if (ids.isEmpty()) return null
        return ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.first()
    }

    private fun selectPreviewSize(sizes: Array<Size>): Size? {
        if (sizes.isEmpty()) return null
        val capped = sizes.filter { it.width <= 1280 && it.height <= 720 }
        if (capped.isEmpty()) {
            return sizes.minByOrNull { it.width.toLong() * it.height }
        }
        return capped
            .sortedWith(
                compareByDescending<Size> { it.width == 1280 && it.height == 720 }
                    .thenByDescending { it.width.toLong() * it.height },
            )
            .firstOrNull()
    }

    private fun selectFpsRange(ranges: Array<Range<Int>>?): Range<Int>? {
        if (ranges.isNullOrEmpty()) return null
        val atMostThirty = ranges.filter { it.upper <= 30 }
        if (atMostThirty.isEmpty()) return null
        return atMostThirty.maxByOrNull { it.lower } ?: atMostThirty.first()
    }

    companion object {
        private const val TAG = "CameraController"
        private const val DEFAULT_PREVIEW_INTERVAL_MS = 33L
        private const val DEFAULT_DETECTION_INTERVAL_MS = 100L
        private const val DETECTION_SIZE = 320
        private const val PREVIEW_POOL_SIZE = 3
        private const val MAX_FAST_RETRIES = 6
        private const val RETRY_BASE_MS = 500L
        private const val RETRY_MAX_MS = 8_000L
        private const val SLOW_RETRY_MS = 15_000L
    }
}
