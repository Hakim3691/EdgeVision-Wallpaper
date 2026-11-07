package com.edgevision.wallpaper.wallpaper

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.edgevision.wallpaper.accessibility.AssistModeResolver
import com.edgevision.wallpaper.alert.AlertCoordinator
import com.edgevision.wallpaper.alert.AlertPolicy
import com.edgevision.wallpaper.camera.CameraController
import com.edgevision.wallpaper.camera.ConvertedFrame
import com.edgevision.wallpaper.capture.AndroidMediaStoreGateway
import com.edgevision.wallpaper.capture.BurstCaptureRunner
import com.edgevision.wallpaper.capture.BurstConfig
import com.edgevision.wallpaper.capture.BurstScheduler
import com.edgevision.wallpaper.capture.CaptureController
import com.edgevision.wallpaper.capture.JpegSource
import com.edgevision.wallpaper.capture.MediaStoreImageSink
import com.edgevision.wallpaper.capture.TripleTapArming
import com.edgevision.wallpaper.diagnostics.PerformanceController
import com.edgevision.wallpaper.diagnostics.ThermalPressure
import com.edgevision.wallpaper.inference.HazardInferenceEngine
import com.edgevision.wallpaper.inference.InferenceResult
import com.edgevision.wallpaper.settings.AssistSettings
import com.edgevision.wallpaper.vision.HazardTracker
import com.edgevision.wallpaper.vision.ThreatDecisionEngine
import com.edgevision.wallpaper.vision.ThreatLevel

class CameraWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CameraEngine()

    private inner class CameraEngine : Engine(),
        CameraController.Listener,
        HazardInferenceEngine.Listener {

        private val mainHandler = Handler(Looper.getMainLooper())
        private lateinit var settings: AssistSettings
        private lateinit var renderer: HazardOverlayRenderer
        private lateinit var alerts: AlertCoordinator
        private lateinit var camera: CameraController
        private lateinit var performance: PerformanceController
        private lateinit var captureController: CaptureController
        private lateinit var burstRunner: BurstCaptureRunner

        private val tripleTap = TripleTapArming()
        private val tracker = HazardTracker()
        private val threatEngine = ThreatDecisionEngine()

        private var inferenceEngine: HazardInferenceEngine? = null
        private var lifecycle: WallpaperEngineController? = null
        private var surfaceHolder: SurfaceHolder? = null
        private var thermalCleanup: (() -> Unit)? = null

        @Volatile
        private var engineVisible = false

        private var pulseScheduled = false
        private var lastFrameMs = 0L
        private val pulseRunnable = object : Runnable {
            override fun run() {
                if (!pulseScheduled) return
                renderer.draw()
                if (renderer.isActive(SystemClock.elapsedRealtime())) {
                    mainHandler.postDelayed(this, PULSE_INTERVAL_MS)
                } else {
                    pulseScheduled = false
                }
            }
        }

        private val disarmRunnable = Runnable {
            if (tripleTap.onTimePassed(SystemClock.elapsedRealtime())) {
                captureController.setArmed(false)
                settings.captureArmed = false
                Log.i(TAG, "Capture disarmed after its window elapsed")
            }
        }

        private val deviceStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshDeviceState()
            }
        }

        private val screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                when (action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // Lock screen activated: handover camera control so face
                        // recognition can work unimpeded.
                        Log.i(TAG, "Screen off: pausing camera for biometric unlock")
                        lifecycle?.onVisibilityChanged(false)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        // User unlocked: resume live camera view.
                        Log.i(TAG, "Screen on: resuming live camera view")
                        lifecycle?.onVisibilityChanged(true)
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            surfaceHolder.setFormat(PixelFormat.TRANSLUCENT)
            setOffsetNotificationsEnabled(false)
            setTouchEventsEnabled(true)

            settings = AssistSettings(this@CameraWallpaperService)
            renderer = HazardOverlayRenderer(
                settings = settings,
                density = resources.displayMetrics.density,
            )
            alerts = AlertCoordinator(this@CameraWallpaperService)

            camera = CameraController(this@CameraWallpaperService, this)
            val inference = HazardInferenceEngine(this@CameraWallpaperService, this)
            inferenceEngine = inference

            captureController = CaptureController(
                config = BurstConfig(
                    shots = settings.burstShots,
                    intervalMs = settings.burstIntervalMs,
                    cooldownMs = settings.captureCooldownMs,
                ),
            )
            captureController.setArmed(settings.captureArmed)

            burstRunner = BurstCaptureRunner(
                controller = captureController,
                source = JpegSource { onCaptured -> camera.captureJpeg(onCaptured) },
                sink = MediaStoreImageSink(
                    gateway = AndroidMediaStoreGateway(this@CameraWallpaperService),
                    canWrite = { canWriteCaptures() },
                ),
                scheduler = BurstScheduler { delayMs, action ->
                    if (delayMs <= 0L) action() else mainHandler.postDelayed(action, delayMs)
                },
                clock = { SystemClock.elapsedRealtime() },
            )

            performance = PerformanceController { profile ->
                camera.setDetectionIntervalMs(profile.inferenceIntervalMs)
                Log.i(TAG, "Cadence ${profile.label}")
            }
            performance.mode = settings.performanceMode

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalCleanup = registerThermalListener()
            }
            registerDeviceStateReceiver()
            registerScreenStateReceiver()
            refreshDeviceState()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            engineVisible = visible
            lifecycle?.onVisibilityChanged(visible)

            if (visible) {
                alerts.prepare()
                syncCaptureSettings()
                refreshDeviceState()
            } else {
                // Release all rendering, audio, detection and capture state while hidden.
                mainHandler.removeCallbacks(pulseRunnable)
                mainHandler.removeCallbacks(disarmRunnable)
                pulseScheduled = false
                renderer.clear()
                alerts.stop()
                tracker.reset()
                threatEngine.reset()
                disarmCapture()
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
            pulseScheduled = false
            renderer.detach()
            this.surfaceHolder = null
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            if (!engineVisible) return
            renderer.draw()
        }

        override fun onDestroy() {
            engineVisible = false
            lifecycle?.destroy()
            mainHandler.removeCallbacksAndMessages(null)
            pulseScheduled = false
            disarmCapture()
            thermalCleanup?.invoke()
            thermalCleanup = null
            unregisterScreenStateReceiver()
            runCatching { unregisterReceiver(deviceStateReceiver) }
            alerts.close()
            renderer.release()
            inferenceEngine?.close()
            camera.close()
            super.onDestroy()
        }

        /**
         * Home-screen taps: three quick taps arm burst capture for a bounded
         * window. The gesture rules live in [TripleTapArming]; this only applies
         * the resulting state to the capture controller and settings.
         */
        override fun onTouchEvent(event: MotionEvent) {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return
            val now = SystemClock.elapsedRealtime()
            when (val result = tripleTap.onTap(now, captureEnabled = settings.burstShots > 0)) {
                is TripleTapArming.TapResult.Armed -> {
                    settings.captureArmed = true
                    captureController.setArmed(true)
                    mainHandler.removeCallbacks(disarmRunnable)
                    mainHandler.postDelayed(disarmRunnable, (result.untilMs - now).coerceAtLeast(0L))
                    Log.i(TAG, "Capture armed for ${tripleTap.armDurationMs}ms by triple tap")
                }

                else -> Unit
            }
        }

        override fun onFrame(frame: ConvertedFrame) {
            // Time-based throttle: only render if at least 33ms has passed since last render.
            // This reduces main thread load without causing frame drops/jumps.
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMs < 33L) return
            lastFrameMs = now
            mainHandler.post {
                renderer.onFrame(frame.preview, frame.rotationDegrees)
            }
            inferenceEngine?.submitFrame(frame.detectionRgb)
        }

        override fun onCameraStarted(previewSize: Size) {
            Log.i(TAG, "Camera captures at ${previewSize.width}x${previewSize.height}")
        }

        override fun onCameraError(message: String, cause: Throwable?) {
            Log.w(TAG, message, cause)
        }

        override fun onInferenceResult(result: InferenceResult) {
            if (!engineVisible) return
            val now = SystemClock.elapsedRealtime()
            val event = tracker.update(result.detections, now)
            // One weak frame informs at most; only a confirmed warning grade is
            // allowed to flash the screen, play a tone or capture a burst.
            val assessment = threatEngine.assess(event, now)
            maybeCapture(assessment.level, now)
            val hazard = assessment.event ?: return
            if (!assessment.level.isWarning) return

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

            alerts.dispatch(hazard, decision)
            mainHandler.post {
                if (!engineVisible) return@post
                renderer.onHazard(now, decision.visual)
                schedulePulse()
            }
        }

        override fun onInferenceError(message: String) {
            Log.w(TAG, "Inference unavailable: $message")
        }

        /**
         * Automatic burst capture. Admission (arming, threshold, BUSY, cooldown)
         * is entirely [CaptureController]'s decision; this only reports the result.
         */
        private fun maybeCapture(level: ThreatLevel, nowMs: Long) {
            val decision = burstRunner.onThreat(level, nowMs)
            when (decision) {
                is com.edgevision.wallpaper.capture.CaptureDecision.Burst ->
                    Log.i(TAG, "Capture burst started: ${decision.request.trigger} x${decision.request.shots}")

                is com.edgevision.wallpaper.capture.CaptureDecision.Refused -> {
                    if (decision.reason == com.edgevision.wallpaper.capture.CaptureDecision.Refusal.BUSY ||
                        decision.reason == com.edgevision.wallpaper.capture.CaptureDecision.Refusal.COOLDOWN
                    ) {
                        Log.i(TAG, "Capture refused: ${decision.reason}")
                    }
                }
            }
        }

        private fun syncCaptureSettings() {
            captureController.updateConfig(
                BurstConfig(
                    shots = settings.burstShots,
                    intervalMs = settings.burstIntervalMs,
                    cooldownMs = settings.captureCooldownMs,
                ),
            )
            captureController.setArmed(settings.captureArmed)
            if (!settings.captureArmed) {
                tripleTap.cancel()
                mainHandler.removeCallbacks(disarmRunnable)
            }
        }

        private fun disarmCapture() {
            tripleTap.cancel()
            captureController.setArmed(false)
            settings.captureArmed = false
            mainHandler.removeCallbacks(disarmRunnable)
        }

        private fun canWriteCaptures(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
            return ContextCompat.checkSelfPermission(
                this@CameraWallpaperService,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun refreshDeviceState() {
            val power = getSystemService(PowerManager::class.java)
            val battery = getSystemService(BatteryManager::class.java)
            performance.mode = settings.performanceMode
            performance.powerSaveMode = runCatching { power?.isPowerSaveMode == true }.getOrDefault(false)
            performance.batteryPercent = runCatching {
                battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            }.getOrDefault(100)
            performance.charging = runCatching { battery?.isCharging == true }.getOrDefault(false)
            performance.thermal = currentThermalPressure()
            performance.refresh()
        }

        private fun currentThermalPressure(): ThermalPressure {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalPressure.NONE
            val status = runCatching {
                getSystemService(PowerManager::class.java)?.currentThermalStatus
            }.getOrNull() ?: return ThermalPressure.NONE
            return when (status) {
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalPressure.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalPressure.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalPressure.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN,
                -> ThermalPressure.CRITICAL

                else -> ThermalPressure.NONE
            }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun registerThermalListener(): () -> Unit {
            val power = getSystemService(PowerManager::class.java)
            val listener = PowerManager.OnThermalStatusChangedListener { refreshDeviceState() }
            val executor = ContextCompat.getMainExecutor(this@CameraWallpaperService)
            power.addThermalStatusListener(executor, listener)
            return { power.removeThermalStatusListener(listener) }
        }

        private fun registerDeviceStateReceiver() {
            ContextCompat.registerReceiver(
                this@CameraWallpaperService,
                deviceStateReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        private fun registerScreenStateReceiver() {
            ContextCompat.registerReceiver(
                this@CameraWallpaperService,
                screenStateReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        private fun unregisterScreenStateReceiver() {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (e: IllegalArgumentException) {
                // Receiver may already be unregistered
            }
        }

        private fun schedulePulse() {
            if (pulseScheduled) return
            pulseScheduled = true
            mainHandler.post(pulseRunnable)
        }
    }

    private companion object {
        const val TAG = "CameraWallpaper"
        const val PULSE_INTERVAL_MS = 33L
    }
}
