package com.edgevision.wallpaper.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.edgevision.wallpaper.R
import com.edgevision.wallpaper.accessibility.AssistModeResolver
import com.edgevision.wallpaper.alert.AlertCoordinator
import com.edgevision.wallpaper.alert.AlertPolicy
import com.edgevision.wallpaper.camera.CameraAccess
import com.edgevision.wallpaper.camera.CameraController
import com.edgevision.wallpaper.camera.ConvertedFrame
import com.edgevision.wallpaper.diagnostics.DetectionHealth
import com.edgevision.wallpaper.diagnostics.LoadPolicy
import com.edgevision.wallpaper.diagnostics.LoadProfile
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
        private lateinit var powerManager: PowerManager

        private val tracker = HazardTracker()
        private val metrics = RuntimeMetrics()
        private val detectionHealth = DetectionHealth()

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
        private var loadProfile: LoadProfile? = null
        private var thermalCleanup: (() -> Unit)? = null

        /** A camera problem explains missing detections by itself, so it wins. */
        private var cameraNotice: String? = null
        private var detectionNotice: String? = null

        @Volatile
        private var engineVisible = false

        private var pulseScheduled = false

        private val drainRunnable = Runnable { drainLatestFrame() }

        private val powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                applyLoadProfile()
            }
        }

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                applyInteractiveState()
            }
        }

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
            powerManager = getSystemService(PowerManager::class.java)

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

            registerLoadObservers()
            applyLoadProfile()
            applyInteractiveState()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            engineVisible = visible
            lifecycle?.onVisibilityChanged(visible)

            if (visible) {
                alerts.prepare()
                applyLoadProfile()
                applyInteractiveState()
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
                cameraNotice = null
                detectionNotice = null
                detectionHealth.reset()
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
            thermalCleanup?.invoke()
            thermalCleanup = null
            runCatching { unregisterReceiver(powerSaveReceiver) }
            runCatching { unregisterReceiver(screenReceiver) }
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

        /**
         * A wallpaper cannot ask for permission itself, so an unusable camera is shown
         * as text on the surface instead of an empty home screen. Recovery is silent:
         * the controller keeps polling while visible, and the notice disappears as soon
         * as capture starts.
         */
        override fun onCameraAccessChanged(access: CameraAccess) {
            cameraNotice = when (access) {
                CameraAccess.READY -> null
                CameraAccess.MISSING_PERMISSION -> getString(R.string.wallpaper_notice_camera_permission)
                CameraAccess.NO_HARDWARE -> getString(R.string.wallpaper_notice_no_camera)
            }
            mainHandler.post { refreshNotice() }
        }

        override fun onInferenceResult(result: InferenceResult) {
            if (!engineVisible) return
            if (detectionHealth.onSuccess()) {
                detectionNotice = null
                mainHandler.post { refreshNotice() }
            }
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

        /**
         * A dead detector used to be invisible while the preview kept streaming.
         * Repeated failures now raise a notice, cleared by the next real result.
         */
        override fun onInferenceError(message: String) {
            Log.w(TAG, "Inference unavailable: $message")
            if (detectionHealth.onFailure()) {
                detectionNotice = getString(R.string.wallpaper_notice_detection)
                mainHandler.post { refreshNotice() }
            }
        }

        private fun schedulePulse() {
            if (pulseScheduled) return
            pulseScheduled = true
            mainHandler.post(pulseRunnable)
        }

        private fun refreshNotice() {
            if (!engineVisible) return
            metrics.onDraw(renderer.setNotice(cameraNotice ?: detectionNotice))
        }

        private fun registerLoadObservers() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalCleanup = registerThermalObserver()
            }
            ContextCompat.registerReceiver(
                this@CameraWallpaperService,
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            ContextCompat.registerReceiver(
                this@CameraWallpaperService,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun registerThermalObserver(): () -> Unit {
            val executor = ContextCompat.getMainExecutor(this@CameraWallpaperService)
            val listener = PowerManager.OnThermalStatusChangedListener { applyLoadProfile() }
            powerManager.addThermalStatusListener(executor, listener)
            return { powerManager.removeThermalStatusListener(listener) }
        }

        /**
         * A wallpaper can stay bound behind the keyguard, and capturing through a dark
         * screen costs battery and can trip background-camera restrictions.
         */
        private fun applyInteractiveState() {
            val interactive = runCatching { powerManager.isInteractive }.getOrDefault(true)
            lifecycle?.onInteractiveChanged(interactive)
        }

        private fun applyLoadProfile() {
            val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { powerManager.currentThermalStatus }.getOrNull()
            } else {
                null
            }
            val profile = LoadPolicy.profile(
                pressure = LoadPolicy.pressureFor(thermalStatus),
                powerSaveMode = runCatching { powerManager.isPowerSaveMode }.getOrDefault(false),
            )
            if (profile == loadProfile) return
            loadProfile = profile
            cameraController?.setFrameIntervalMs(profile.frameIntervalMs)
            Log.i(
                TAG,
                "Load profile ${profile.label}: converting every ${profile.frameIntervalMs}ms, " +
                    "detection enabled=${profile.detectionEnabled}",
            )
        }
    }

    private companion object {
        const val TAG = "CameraWallpaper"
        const val PULSE_INTERVAL_MS = 66L
        const val METRICS_INTERVAL_MS = 5_000L
    }
}
