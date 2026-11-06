package com.edgevision.wallpaper

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.edgevision.wallpaper.accessibility.AssistModeResolver
import com.edgevision.wallpaper.databinding.ActivityMainBinding
import com.edgevision.wallpaper.diagnostics.PerformanceMode
import com.edgevision.wallpaper.settings.AssistSettings
import com.edgevision.wallpaper.wallpaper.CameraWallpaperService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AssistSettings

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshStatus() }

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
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        binding.btnSetWallpaper.setOnClickListener {
            openLiveWallpaperPicker()
        }
        binding.btnGrantStorage.setOnClickListener {
            requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        // Capture settings are read by the wallpaper engine; this screen only
        // persists them and shows the current values.
        binding.switchCaptureArmed.setOnCheckedChangeListener { _, checked ->
            settings.captureArmed = checked
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
            if (!isChecked) return@addOnButtonCheckedListener
            modeForButton(checkedId)?.let { settings.performanceMode = it }
        }

        updateBlindModeUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
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

        refreshCaptureUi()
    }

    private fun refreshCaptureUi() {
        binding.switchCaptureArmed.isChecked = settings.captureArmed
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
