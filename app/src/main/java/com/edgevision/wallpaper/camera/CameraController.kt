package com.edgevision.wallpaper.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Owns Camera2 hardware for the wallpaper. The capture stream is capped at
 * 1280x720 / 30 fps. Frames are converted at most 15 times per second, and the
 * detector input is a 320x320 upright view of the full frame produced by
 * [YuvFrameConverter].
 *
 * Camera failures are recoverable: a disconnect, a transient device error, or a
 * session configuration failure schedules a bounded exponential reopen through
 * [CameraRecoveryPolicy] instead of leaving a permanently black wallpaper.
 */
class CameraController(
    private val context: Context,
    private val listener: Listener,
    private val recovery: CameraRecoveryPolicy = CameraRecoveryPolicy(),
) {

    interface Listener {
        fun onFrame(frame: ConvertedFrame)
        fun onCameraStarted(previewSize: Size)
        fun onCameraError(message: String, cause: Throwable? = null)
    }

    private val lock = Any()
    private val cameraThread = HandlerThread("edgevision-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val converter = YuvFrameConverter()
    private val openRunnable = Runnable { openCamera() }

    /** User intent: the wallpaper wants frames. Cleared only by [stop]. */
    @Volatile
    private var running = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var sensorOrientation = 90
    private var previewSize: Size? = null
    private var fpsRange: Range<Int>? = null
    private var continuousAfSupported = false
    private var lastProcessedAtMs = 0L

    @Synchronized
    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onCameraError("Camera permission has not been granted")
            return
        }
        running = true
        lastProcessedAtMs = 0L
        recovery.reset()
        cameraHandler.post(openRunnable)
    }

    /**
     * Stops frame delivery and closes the capture session/device immediately.
     * The close calls are allowed from any thread, so pausing the wallpaper is
     * not delayed by the camera handler queue. Pending reopen attempts are
     * cancelled: only a later [start] may bring the camera back.
     */
    @Synchronized
    fun stop() {
        running = false
        cameraHandler.removeCallbacks(openRunnable)
        recovery.reset()
        closeHardware()
    }

    fun close() {
        stop()
        cameraHandler.removeCallbacksAndMessages(null)
        cameraThread.quitSafely()
    }

    private fun closeHardware() {
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
        }
    }

    /**
     * Releases whatever hardware is still open and either schedules a retry or,
     * once the recovery budget is spent, gives up until the next [start].
     */
    private fun fail(message: String, cause: Throwable? = null) {
        closeHardware()
        if (!running) return

        val delayMs = recovery.nextDelayMs()
        if (delayMs == null) {
            running = false
            listener.onCameraError(
                "$message; giving up after ${recovery.maxAttempts} recovery attempts",
                cause,
            )
            return
        }
        listener.onCameraError("$message; reopening in ${delayMs}ms", cause)
        cameraHandler.postDelayed(openRunnable, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!running) return
        val manager = context.getSystemService(CameraManager::class.java)
            ?: run {
                fail("Camera service is unavailable")
                return
            }

        try {
            val cameraId = selectCameraId(manager) ?: run {
                fail("No camera is available on this device")
                return
            }
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val streamConfiguration = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: run {
                    fail("Camera stream configuration is unavailable")
                    return
                }
            val size = selectPreviewSize(
                streamConfiguration.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray(),
            ) ?: run {
                fail("Camera does not expose a YUV preview size")
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

            manager.openCamera(cameraId, deviceCallback, cameraHandler)
        } catch (throwable: Throwable) {
            fail("Could not open the camera: ${throwable.message}", throwable)
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
            fail("Camera was disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            fail("Camera device error $error")
        }
    }

    private fun createSession(camera: CameraDevice) {
        val reader = synchronized(lock) { imageReader }
        if (reader == null || !running) {
            camera.close()
            return
        }

        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(reader.surface),
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
                        fail("Camera capture session configuration failed")
                    }
                },
                cameraHandler,
            )
        } catch (throwable: Throwable) {
            fail("Could not create a camera session: ${throwable.message}", throwable)
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
            // Streaming again: the next failure starts a fresh backoff sequence.
            recovery.reset()
            listener.onCameraStarted(Size(reader.width, reader.height))
        } catch (throwable: Throwable) {
            fail("Could not start camera frames: ${throwable.message}", throwable)
        }
    }

    private fun handleImage(reader: ImageReader) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProcessedAtMs < FRAME_INTERVAL_MS) return

        val image = try {
            reader.acquireLatestImage()
        } catch (throwable: Throwable) {
            return
        } ?: return

        image.use { frame ->
            if (!running) return
            lastProcessedAtMs = now
            try {
                val sourceAspect = frame.width.toFloat() / frame.height.toFloat()
                val previewWidth = PREVIEW_MAX_WIDTH
                val previewHeight = (previewWidth / sourceAspect)
                    .roundToInt()
                    .coerceAtLeast(2)
                val converted = converter.convert(
                    image = frame,
                    previewWidth = previewWidth,
                    previewHeight = previewHeight,
                    detectionSize = DETECTION_SIZE,
                    rotationDegrees = rotationDegrees(),
                )
                listener.onFrame(converted)
            } catch (throwable: Throwable) {
                Log.w(TAG, "Frame conversion failed", throwable)
            }
        }
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
            // No <=720p stream: choose the smallest available stream, never the largest.
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
        private const val FRAME_INTERVAL_MS = 66L
        private const val PREVIEW_MAX_WIDTH = 512
        private const val DETECTION_SIZE = 320
    }
}
