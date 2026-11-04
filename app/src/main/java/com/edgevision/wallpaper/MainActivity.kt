package com.edgevision.wallpaper

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.edgevision.wallpaper.accessibility.AssistModeResolver
import com.edgevision.wallpaper.databinding.ActivityMainBinding
import com.edgevision.wallpaper.settings.AssistSettings
import com.edgevision.wallpaper.wallpaper.CameraWallpaperService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: AssistSettings

    private val requestCameraPermission = registerForActivityResult(
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
}
