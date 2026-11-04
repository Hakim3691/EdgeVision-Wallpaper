package com.edgevision.wallpaper.wallpaper

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.accessibility.AccessibilityManager
import com.edgevision.wallpaper.accessibility.AssistModeResolver
import com.edgevision.wallpaper.alert.AlertCoordinator
import com.edgevision.wallpaper.alert.AlertPolicy
import com.edgevision.wallpaper.camera.CameraController
import com.edgevision.wallpaper.camera.ConvertedFrame
import com.edgevision.wallpaper.diagnostics.RuntimeMetrics
import com.edgevision.wallpaper.inference.HazardInferenceEngine
import com.edgevision.wallpaper.inference.InferenceResult
import com.edgevision.wallpaper.settings.AssistSettings
import com.edgevision.wallpaper.vision.HazardTracker

class CameraWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CameraEngine()

    private inner class CameraEngine : Engine(),
        CameraController.Listener,
        HazardInferenceEngine.Listener {

        private val mainHandler = Handler(Looper.getMainLooper())
        private lateinit var settings: AssistSettings
        private lateinit var renderer: HazardOverlayRenderer
        private lateinit var alerts: AlertCoordinator

        private val tracker = HazardTracker()
        private val metrics = RuntimeMetrics()

        /**
         * At most one camera frame waits for the main thread. A slow full-surface
         * draw therefore supersedes the waiting frame instead of queueing it,
         * which keeps the preview live and bounds memory use.
         */
        private val mailbox = RenderMailbox<ConvertedFrame> { frame -> frame.preview.recycle() }

        private var cameraController: CameraController? = null
        private var inferenceEngine: HazardInferenceEngine? = null
        private var lifecycle: WallpaperEngineController? = null
        private var surfaceHolder: SurfaceHolder? = null

        @Volatile
        private var engineVisible = false

        private var pulseScheduled = false

        private val drainRunnable = Runnable { drainLatestFrame() }

        private val pulseRunnable = object : Runnable {
            override fun run() {
                if (!pulseScheduled) return
                if (!engineVisible) {
                    pulseScheduled = false
                    return
                }
                metrics.onDraw(renderer.draw())
                if (renderer.isActive(SystemClock.elapsedRealtime())) {
                    mainHandler.postDelayed(this, PULSE_INTERVAL_MS)
                } else {
                    pulseScheduled = false
                }
            }
        }

        private val metricsRunnable = object : Runnable {
            override fun run() {
                if (!engineVisible) return
                Log.i(TAG, metrics.describe(SystemClock.elapsedRealtime()))
                mainHandler.postDelayed(this, METRICS_INTERVAL_MS)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            surfaceHolder.setFormat(PixelFormat.TRANSLUCENT)
            setOffsetNotificationsEnabled(false)

            settings = AssistSettings(this@CameraWallpaperService)
            renderer = HazardOverlayRenderer(
                settings = settings,
                density = resources.displayMetrics.density,
            )
            alerts = AlertCoordinator(this@CameraWallpaperService)

            val camera = CameraController(this@CameraWallpaperService, this)
            val inference = HazardInferenceEngine(this@CameraWallpaperService, this)
            cameraController = camera
            inferenceEngine = inference

            lifecycle = WallpaperEngineController(
                camera = object : EngineResource {
                    override fun start() = camera.start()
                    override fun stop() = camera.stop()
                },
                inference = object : EngineResource {
                    override fun start() = inference.start()
                    override fun stop() = inference.stop()
                },
                onError = { message -> Log.w(TAG, message) },
            )
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            engineVisible = visible
            lifecycle?.onVisibilityChanged(visible)

            if (visible) {
                alerts.prepare()
                metrics.start(SystemClock.elapsedRealtime())
                mainHandler.removeCallbacks(metricsRunnable)
                mainHandler.postDelayed(metricsRunnable, METRICS_INTERVAL_MS)
            } else {
                // Release all rendering, audio, and detection state while hidden.
                mainHandler.removeCallbacks(pulseRunnable)
                mainHandler.removeCallbacks(drainRunnable)
                mainHandler.removeCallbacks(metricsRunnable)
                pulseScheduled = false
                mailbox.discard()
                renderer.clear()
                alerts.stop()
                tracker.reset()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            this.surfaceHolder = holder
            renderer.attach(holder)
            lifecycle?.onSurfaceAvailabilityChanged(true)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            renderer.draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            lifecycle?.onSurfaceAvailabilityChanged(false)
            mainHandler.removeCallbacks(pulseRunnable)
            mainHandler.removeCallbacks(drainRunnable)
            pulseScheduled = false
            mailbox.discard()
            renderer.detach()
            this.surfaceHolder = null
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            renderer.draw()
        }

        override fun onDestroy() {
            engineVisible = false
            lifecycle?.destroy()
            mainHandler.removeCallbacksAndMessages(null)
            pulseScheduled = false
            mailbox.discard()
            alerts.close()
            renderer.release()
            inferenceEngine?.close()
            cameraController?.close()
            super.onDestroy()
        }

        override fun onFrame(frame: ConvertedFrame) {
            if (!engineVisible) {
                frame.preview.recycle()
                return
            }
            metrics.onFrame()
            val scheduleDrain = mailbox.offer(frame)
            metrics.onDroppedFrames(mailbox.droppedCount)
            if (scheduleDrain) {
                mainHandler.post(drainRunnable)
            }
            inferenceEngine?.submitFrame(frame.detectionRgb)
        }

        private fun drainLatestFrame() {
            val frame = mailbox.take() ?: return
            if (!engineVisible) {
                frame.preview.recycle()
                return
            }
            metrics.onDraw(renderer.onFrame(frame.preview, frame.rotationDegrees))
        }

        override fun onCameraStarted(previewSize: Size) {
            Log.i(TAG, "Camera captures at ${previewSize.width}x${previewSize.height}")
        }

        override fun onCameraError(message: String, cause: Throwable?) {
            Log.w(TAG, message, cause)
        }

        override fun onInferenceResult(result: InferenceResult) {
            if (!engineVisible) return
            metrics.onInference(result.latencyMs)

            val now = SystemClock.elapsedRealtime()
            val event = tracker.update(result.detections, now) ?: return
            metrics.onHazard()

            val accessibility = getSystemService(AccessibilityManager::class.java)
            val blindAssist = AssistModeResolver.isBlindAssistActive(
                manualBlindAssist = settings.blindAssist,
                accessibilityEnabled = accessibility?.isEnabled == true,
                touchExplorationEnabled = accessibility?.isTouchExplorationEnabled == true,
            )
            val decision = AlertPolicy.decide(
                blindAssist = blindAssist,
                audibleAlertsEnabled = settings.audibleAlerts,
                hapticsEnabled = settings.hapticsEnabled,
            )

            alerts.dispatch(event, decision)
            mainHandler.post {
                if (!engineVisible) return@post
                metrics.onDraw(renderer.onHazard(now, decision.visual))
                schedulePulse()
            }
        }

        override fun onInferenceError(message: String) {
            Log.w(TAG, "Inference unavailable: $message")
        }

        private fun schedulePulse() {
            if (pulseScheduled) return
            pulseScheduled = true
            mainHandler.post(pulseRunnable)
        }
    }

    private companion object {
        const val TAG = "CameraWallpaper"
        const val PULSE_INTERVAL_MS = 66L
        const val METRICS_INTERVAL_MS = 5_000L
    }
}
