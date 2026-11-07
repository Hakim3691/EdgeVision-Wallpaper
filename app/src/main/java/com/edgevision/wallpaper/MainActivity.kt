package com.edgevision.wallpaper

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.usage.UsageStatsManager
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.view.View
import android.widget.CheckBox
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.edgevision.wallpaper.accessibility.AssistModeResolver
import com.edgevision.wallpaper.databinding.ActivityMainBinding
import com.edgevision.wallpaper.diagnostics.PerformanceMode
import com.edgevision.wallpaper.diagnostics.PipelineDiagnostics
import com.edgevision.wallpaper.legal.LegalActivity
import com.edgevision.wallpaper.settings.AssistSettings
import com.edgevision.wallpaper.wallpaper.CameraWallpaperService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.ServiceConnection
import android.util.Log
import android.widget.CompoundButton
import com.google.android.material.button.MaterialButtonToggleGroup

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AssistSettings

    /** Guards the capture switch while the safety acknowledgement is pending. */
    private var suppressCaptureToggle = false

    /** Foreground service binding */
    private lateinit var foregroundServiceConnection: ServiceConnection
    private var foregroundServiceBinder: CameraPreviewForegroundService.LocalBinder? = null

    /** Runs on UI thread; polls UsageStats to drive overlay visibility */
    private val overlayVisibilityRunnable = Runnable {
        updateOverlayVisibilityFromStats()
    }
    private val foregroundServiceHandler = Handler(Looper.getMainLooper())

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshStatus()
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            showCameraSettingsFallback()
        }
    }

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AssistSettings(this)

        binding.switchBlindAssist.isChecked = settings.blindAssist
        binding.switchAudibleAlerts.isChecked = settings.audibleAlerts
        binding.switchHapticsEnabled.isChecked = settings.hapticsEnabled
        binding.sliderOpacity.value = settings.overlayOpacityPercent.toFloat()
        updateOpacityLabel(settings.overlayOpacityPercent)

        binding.switchBlindAssist.setOnCheckedChangeListener { _, checked ->
            settings.blindAssist = checked
            updateBlindModeUi()
        }
        binding.switchAudibleAlerts.setOnCheckedChangeListener { _, checked ->
            settings.audibleAlerts = checked
        }
        binding.switchHapticsEnabled.setOnCheckedChangeListener { _, checked ->
            settings.hapticsEnabled = checked
        }
        binding.sliderOpacity.addOnChangeListener { _, value, _ ->
            val percent = value.toInt()
            settings.overlayOpacityPercent = percent
            updateOpacityLabel(percent)
        }

        binding.btnGrantCamera.setOnClickListener {
            if (hasCameraPermission()) {
                refreshStatus()
            } else {
                showCameraRationale()
            }
        }
        binding.btnDismissPowerNotice.setOnClickListener {
            settings.powerNoticeDismissed = true
            binding.cardPowerLimit.isVisible = false
        }
        binding.btnOpenBatterySettings.setOnClickListener { openAppSettings() }
        binding.btnSetWallpaper.setOnClickListener { openLiveWallpaperPicker() }
        binding.btnGrantStorage.setOnClickListener {
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        // Bind foreground service for camera overlay
        foregroundServiceConnection = object : ServiceConnection {
            // Note: className may be null in some test environments (Robolectric)
            // even though the Android interface declares it as non-null
            override fun onServiceConnected(className: ComponentName?, ibinder: IBinder) {
                foregroundServiceBinder = ibinder as CameraPreviewForegroundService.LocalBinder?
                if (foregroundServiceBinder != null) {
                    Log.d("MainActivity", "Foreground service bound successfully")
                }
            }

            override fun onServiceDisconnected(className: ComponentName?) {
                foregroundServiceBinder = null
            }
        }
        bindService(Intent(this, CameraPreviewForegroundService::class.java), foregroundServiceConnection, Context.BIND_AUTO_CREATE)

        // Capture settings are read by the wallpaper engine; this screen only
        // persists them and shows the current values. Turning capture on for the
        // first time requires the safety acknowledgement, so the switch reverts
        // until the user accepts it.
        binding.switchCaptureArmed.setOnCheckedChangeListener { button, checked ->
            if (suppressCaptureToggle) return@setOnCheckedChangeListener
            if (checked && !settings.safetyNoticeAccepted) {
                suppressCaptureToggle = true
                button.isChecked = false
                suppressCaptureToggle = false
                showSafetyNotice {
                    settings.captureEnabled = true
                    refreshCaptureUi()
                }
                return@setOnCheckedChangeListener
            }
            settings.captureEnabled = checked
        }
        binding.sliderBurstShots.addOnChangeListener { _, value, _ ->
            settings.burstShots = value.toInt()
            updateCaptureLabels()
        }
        binding.sliderBurstInterval.addOnChangeListener { _, value, _ ->
            settings.burstIntervalMs = value.toLong()
            updateCaptureLabels()
        }
        binding.sliderCaptureCooldown.addOnChangeListener { _, value, _ ->
            settings.captureCooldownMs = value.toLong()
            updateCaptureLabels()
        }
        binding.togglePerformanceMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) modeForButton(checkedId)?.let { settings.performanceMode = it }
        }

        binding.btnAboutLegal.setOnClickListener {
            startActivity(Intent(this, LegalActivity::class.java))
        }

        updateBlindModeUi()
        maybeShowLegalOnboarding()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        foregroundServiceHandler.postDelayed(overlayVisibilityRunnable, 2000)
    }

    override fun onPause() {
        super.onPause()
        foregroundServiceHandler.removeCallbacks(overlayVisibilityRunnable)
    }

    private fun refreshStatus() {
        val cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        binding.tvCameraStatus.text = getString(
            if (cameraGranted) R.string.camera_status_granted else R.string.camera_status_missing,
        )
        binding.btnGrantCamera.isVisible = !cameraGranted

        binding.tvWallpaperStatus.text = getString(
            if (isWallpaperActive()) R.string.wallpaper_status_active else R.string.wallpaper_status_inactive,
        )

        val accessibility = getSystemService(AccessibilityManager::class.java)
        val accessibilityEnabled = accessibility?.isEnabled == true
        val touchExplorationEnabled = accessibility?.isTouchExplorationEnabled == true
        val autoBlindMode = AssistModeResolver.isBlindAssistActive(
            manualBlindAssist = settings.blindAssist,
            accessibilityEnabled = accessibilityEnabled,
            touchExplorationEnabled = touchExplorationEnabled,
        )
        binding.tvAccessibilityNote.isVisible = autoBlindMode
        binding.tvPipelineStatus.text = PipelineDiagnostics.summary()
        binding.cardPowerLimit.isVisible =
            isPowerSaveMode() && !settings.powerNoticeDismissed

        refreshCaptureUi()
    }

    private fun refreshCaptureUi() {
        binding.switchCaptureArmed.isChecked = settings.captureEnabled
        binding.sliderBurstShots.value = settings.burstShots
            .coerceIn(MIN_VISIBLE_BURST_SHOTS, MAX_BURST_SHOTS)
            .toFloat()
        binding.sliderBurstInterval.value = settings.burstIntervalMs
            .coerceIn(MIN_BURST_INTERVAL_MS, MAX_BURST_INTERVAL_MS)
            .toFloat()
        binding.sliderCaptureCooldown.value = settings.captureCooldownMs
            .coerceIn(0L, MAX_CAPTURE_COOLDOWN_MS)
            .toFloat()
        updateCaptureLabels()
        binding.togglePerformanceMode.check(modeButtonId(settings.performanceMode))
        binding.btnGrantStorage.isVisible = needsStoragePermission()
    }

    private fun updateCaptureLabels() {
        binding.tvBurstShotsValue.text = getString(
            R.string.burst_shots_value,
            binding.sliderBurstShots.value.toInt(),
        )
        binding.tvBurstIntervalValue.text = getString(
            R.string.burst_interval_value,
            binding.sliderBurstInterval.value.toInt(),
        )
        binding.tvCaptureCooldownValue.text = getString(
            R.string.capture_cooldown_value,
            binding.sliderCaptureCooldown.value.toInt() / 1_000,
        )
    }

    private fun needsStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return false
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun modeButtonId(mode: PerformanceMode): Int = when (mode) {
        PerformanceMode.AUTOMATIC -> R.id.btn_mode_automatic
        PerformanceMode.BATTERY_SAVER -> R.id.btn_mode_battery_saver
        PerformanceMode.BALANCED -> R.id.btn_mode_balanced
        PerformanceMode.PERFORMANCE -> R.id.btn_mode_performance
    }

    private fun modeForButton(buttonId: Int): PerformanceMode? = when (buttonId) {
        R.id.btn_mode_automatic -> PerformanceMode.AUTOMATIC
        R.id.btn_mode_battery_saver -> PerformanceMode.BATTERY_SAVER
        R.id.btn_mode_balanced -> PerformanceMode.BALANCED
        R.id.btn_mode_performance -> PerformanceMode.PERFORMANCE
        else -> null
    }

    private fun updateBlindModeUi() {
        val blind = settings.blindAssist
        binding.tvBlindAudioNote.isVisible = blind
        binding.switchAudibleAlerts.isEnabled = !blind
        binding.switchHapticsEnabled.isEnabled = !blind
    }

    private fun updateOpacityLabel(percent: Int) {
        binding.tvOpacityValue.text = getString(R.string.opacity_value, percent)
    }

    private fun isWallpaperActive(): Boolean {
        return runCatching {
            val info = WallpaperManager.getInstance(this).wallpaperInfo
            info?.component == ComponentName(this, CameraWallpaperService::class.java)
        }.getOrDefault(false)
    }

    private fun openLiveWallpaperPicker() {
        val component = ComponentName(this, CameraWallpaperService::class.java)
        val changeIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        val opened = runCatching { startActivity(changeIntent) }.isSuccess ||
            runCatching {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            }.isSuccess

        if (!opened) {
            binding.tvWallpaperStatus.text = getString(R.string.wallpaper_picker_unavailable)
        }
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun isPowerSaveMode(): Boolean =
        getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        runCatching { startActivity(intent) }
    }

    private fun showCameraRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camera_rationale_title)
            .setMessage(R.string.camera_rationale_body)
            .setPositiveButton(R.string.camera_rationale_grant) { _, which ->
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
    }

    private fun showCameraSettingsFallback() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camera_settings_title)
            .setMessage(R.string.camera_settings_body)
            .setPositiveButton(R.string.action_open_settings) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** First-run AI disclosure, then the safety acknowledgement if still pending. */
    private fun maybeShowLegalOnboarding() {
        if (settings.aiDisclosureSeen) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_disclosure_title)
            .setMessage(R.string.ai_disclosure_dialog_body)
            .setCancelable(false)
            .setPositiveButton(R.string.action_understand) { _, _ ->
                settings.aiDisclosureSeen = true
                if (!settings.safetyNoticeAccepted) showSafetyNotice()
            }
            .setNeutralButton(R.string.action_read_more) { _, _ ->
                settings.aiDisclosureSeen = true
                startActivity(Intent(this, LegalActivity::class.java))
                if (!settings.safetyNoticeAccepted) showSafetyNotice()
            }
            .show()
    }

    private fun showSafetyNotice(onAccepted: (() -> Unit)? = null) {
        val content = layoutInflater.inflate(R.layout.dialog_safety_ack, null)
        val acknowledge = content.findViewById<CheckBox>(R.id.check_acknowledge)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.safety_dialog_title)
            .setView(content)
            .setPositiveButton(R.string.safety_accept, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            positive.isEnabled = acknowledge.isChecked
            acknowledge.setOnCheckedChangeListener { _, checked -> positive.isEnabled = checked }
            positive.setOnClickListener {
                settings.safetyNoticeAccepted = true
                dialog.dismiss()
                onAccepted?.invoke()
            }
        }
        dialog.show()
    }


    private fun bindForegroundService() {
        val intent = Intent(this, CameraPreviewForegroundService::class.java)
        intent.action = "com.edgevision.wallpaper.action.START_OVERLAY"
        // Bind using 2-param form; service connection callback handles binding
        bindService(intent, foregroundServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindForegroundService() {
        if (foregroundServiceBinder != null) {
            unbindService(foregroundServiceConnection)
            foregroundServiceBinder = null
        }
    }

    private fun updateOverlayVisibility(visibility: Int) {
        // Overlay visibility updated
    }

    private fun isHomePackage(topPackage: String?): Boolean {
        if (topPackage == null) return true
        // Known launcher packages
        val launcherPackages = listOf(
            "com.android.launcher3",
            "com.android.launcher2",
            "com.google.android.apps.googleapp",
            "com.google.android.deslauncher"
        )
        // Packages starting with known prefixes
        val prefixPatterns = listOf("com.android", "google")
        val startsWithPrefix = prefixPatterns.any { topPackage.startsWith(it) }
        val isLauncher = launcherPackages.contains(topPackage)
        return startsWithPrefix || isLauncher
    }

    /** Poll UsageStatsManager for the top foreground package and update overlay visibility */
    private fun updateOverlayVisibilityFromStats() {
        val um = getSystemService(UsageStatsManager::class.java) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 16 * 60 * 1000 // last 16 minutes
        // Overlay visibility update - requires PACKAGE_USAGE_STATS permission
        // Method placeholder - full implementation requires permission and UsageStats handling
    }

    private companion object {
        // The sliders expose the usable 1-5 range; larger persisted values are
        // clamped for display so the slider never receives an out-of-range value.
        const val MIN_VISIBLE_BURST_SHOTS = 1
        const val MAX_BURST_SHOTS = 5
        const val MIN_BURST_INTERVAL_MS = 100L
        const val MAX_BURST_INTERVAL_MS = 2_000L
        const val MAX_CAPTURE_COOLDOWN_MS = 60_000L
    }
}