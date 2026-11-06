package com.edgevision.wallpaper.diagnostics

/** User-selectable performance policy. */
enum class PerformanceMode {
    /** Chooses Balanced or Battery Saver from device state. */
    AUTOMATIC,
    BATTERY_SAVER,
    BALANCED,
    PERFORMANCE,
}

/** Thermal pressure as reported by PowerManager, reduced to what matters here. */
enum class ThermalPressure {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
}

data class PerformanceInputs(
    val mode: PerformanceMode = PerformanceMode.AUTOMATIC,
    val thermal: ThermalPressure = ThermalPressure.NONE,
    val batteryPercent: Int = 100,
    val charging: Boolean = false,
    val powerSaveMode: Boolean = false,
)

/**
 * The resulting pipeline cadence. Detection is never disabled: a late alert is
 * still useful, a missing one is not. Heat and battery only widen the interval.
 */
data class PerformanceProfile(
    val mode: PerformanceMode,
    val inferenceIntervalMs: Long,
    val previewFrameIntervalMs: Long,
    val detectionEnabled: Boolean,
    val label: String,
)

/**
 * Maps device state to a pipeline cadence.
 *
 * Display and inference are separate concerns: the preview interval controls
 * how often a frame is converted for drawing, inference interval controls how
 * often the detector runs. Both slow under thermal or battery pressure, and the
 * slower constraint wins when several apply.
 */
object PerformanceGovernor {

    const val INTERVAL_PERFORMANCE_MS = 66L
    const val INTERVAL_BALANCED_MS = 100L
    const val INTERVAL_SAVER_MS = 250L
    const val INTERVAL_CRITICAL_MS = 500L

    private const val LOW_BATTERY_PERCENT = 15

    fun profile(inputs: PerformanceInputs): PerformanceProfile {
        val effectiveMode = when (inputs.mode) {
            PerformanceMode.AUTOMATIC -> if (inputs.charging || inputs.batteryPercent > 40) {
                PerformanceMode.BALANCED
            } else {
                PerformanceMode.BATTERY_SAVER
            }

            else -> inputs.mode
        }

        val baseInterval = when (effectiveMode) {
            PerformanceMode.PERFORMANCE -> INTERVAL_PERFORMANCE_MS
            PerformanceMode.BALANCED -> INTERVAL_BALANCED_MS
            PerformanceMode.BATTERY_SAVER -> INTERVAL_SAVER_MS
            PerformanceMode.AUTOMATIC -> INTERVAL_BALANCED_MS
        }

        var interval = baseInterval
        if (inputs.powerSaveMode) interval = maxOf(interval, INTERVAL_SAVER_MS)
        if (!inputs.charging && inputs.batteryPercent <= LOW_BATTERY_PERCENT) {
            interval = maxOf(interval, INTERVAL_SAVER_MS)
        }

        interval = when (inputs.thermal) {
            ThermalPressure.NONE, ThermalPressure.LIGHT -> interval
            ThermalPressure.MODERATE -> maxOf(interval, INTERVAL_SAVER_MS)
            ThermalPressure.SEVERE -> maxOf(interval, INTERVAL_CRITICAL_MS)
            ThermalPressure.CRITICAL -> maxOf(interval, INTERVAL_CRITICAL_MS * 2)
        }

        return PerformanceProfile(
            mode = effectiveMode,
            inferenceIntervalMs = interval,
            previewFrameIntervalMs = interval,
            detectionEnabled = true,
            label = label(effectiveMode, inputs, interval),
        )
    }

    private fun label(mode: PerformanceMode, inputs: PerformanceInputs, interval: Long): String {
        val thermal = if (inputs.thermal == ThermalPressure.NONE) "" else ", thermal ${inputs.thermal.name.lowercase()}"
        val battery = if (!inputs.charging && inputs.batteryPercent <= LOW_BATTERY_PERCENT) ", low battery" else ""
        return "${mode.name.lowercase()} every ${interval}ms$thermal$battery"
    }
}
