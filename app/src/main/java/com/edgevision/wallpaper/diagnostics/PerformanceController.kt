package com.edgevision.wallpaper.diagnostics

/**
 * Holds the live device state and pushes the resulting cadence to the pipeline.
 *
 * This is the seam the wallpaper service uses: whatever [apply] does is what the
 * camera actually does. In production it calls `CameraController`, in
 * specifications it records the profiles, so "the governor governs" is a
 * testable claim rather than a comment.
 */
class PerformanceController(
    private val apply: (PerformanceProfile) -> Unit,
) {

    var mode: PerformanceMode = PerformanceMode.AUTOMATIC
    var thermal: ThermalPressure = ThermalPressure.NONE
    var batteryPercent: Int = 100
    var charging: Boolean = false
    var powerSaveMode: Boolean = false

    var current: PerformanceProfile? = null
        private set

    /**
     * Recomputes the profile and applies it when it changed. Returns the current
     * profile so callers can log what the pipeline is actually using.
     */
    fun refresh(): PerformanceProfile {
        val profile = PerformanceGovernor.profile(
            PerformanceInputs(
                mode = mode,
                thermal = thermal,
                batteryPercent = batteryPercent,
                charging = charging,
                powerSaveMode = powerSaveMode,
            ),
        )
        if (profile != current) {
            current = profile
            apply(profile)
        }
        return profile
    }
}
