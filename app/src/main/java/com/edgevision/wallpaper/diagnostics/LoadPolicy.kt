package com.edgevision.wallpaper.diagnostics

/**
 * How hard the pipeline may run right now.
 *
 * The wallpaper converts camera frames around 15 times per second and runs
 * inference on a single worker. That is fine on a cool phone, but the same load on
 * a hot or battery-saving device is what pushes an OEM build into thermal
 * throttling — and, on some devices, into killing the wallpaper process.
 *
 * Detection is never switched off. A hazard alert that arrives slightly later is
 * still useful; one that never arrives is not. Only the preview/conversion cadence
 * changes, so the preview also stays responsive instead of stuttering.
 */
data class LoadProfile(
    val frameIntervalMs: Long,
    val label: String,
) {
    /** Detection keeps running under every profile: alerts are the point of the app. */
    val detectionEnabled: Boolean
        get() = true
}

/**
 * `PowerManager` thermal status collapsed to the levels the pipeline reacts to.
 * `THERMAL_STATUS_LIGHT` still behaves like normal: it exists on devices that warm
 * up routinely and does not warrant a slower preview.
 */
enum class ThermalPressure {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
}

object LoadPolicy {

    const val NORMAL_FRAME_INTERVAL_MS = 66L
    const val BATTERY_SAVER_FRAME_INTERVAL_MS = 100L

    /**
     * Maps `PowerManager.getCurrentThermalStatus()` values onto [ThermalPressure].
     * `null` means the platform did not report a status (API < 29 or an error), which
     * is treated as normal rather than as a reason to slow down.
     */
    fun pressureFor(thermalStatus: Int?): ThermalPressure = when (thermalStatus) {
        null -> ThermalPressure.NONE
        in Int.MIN_VALUE..0 -> ThermalPressure.NONE
        1 -> ThermalPressure.LIGHT
        2 -> ThermalPressure.MODERATE
        3 -> ThermalPressure.SEVERE
        else -> ThermalPressure.CRITICAL
    }

    /**
     * The slower of the thermal and battery-saver cadences wins, so a hot phone in
     * battery saver does not accidentally run harder than either constraint allows.
     */
    fun profile(pressure: ThermalPressure, powerSaveMode: Boolean): LoadProfile {
        val thermalIntervalMs = when (pressure) {
            ThermalPressure.NONE, ThermalPressure.LIGHT -> NORMAL_FRAME_INTERVAL_MS
            ThermalPressure.MODERATE -> 150L
            ThermalPressure.SEVERE -> 250L
            ThermalPressure.CRITICAL -> 500L
        }
        val intervalMs = if (powerSaveMode) {
            maxOf(thermalIntervalMs, BATTERY_SAVER_FRAME_INTERVAL_MS)
        } else {
            thermalIntervalMs
        }

        val reasons = buildList {
            if (pressure != ThermalPressure.NONE) add("thermal-${pressure.name.lowercase()}")
            if (powerSaveMode) add("battery-saver")
        }
        return LoadProfile(
            frameIntervalMs = intervalMs,
            label = if (reasons.isEmpty()) "normal" else reasons.joinToString("+"),
        )
    }
}
