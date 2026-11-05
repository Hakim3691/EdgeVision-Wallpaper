package com.edgevision.wallpaper.vision

import com.edgevision.wallpaper.inference.Box
import com.edgevision.wallpaper.inference.HazardKind

data class HazardEvent(
    val kind: HazardKind,
    val label: String,
    val confidence: Double,
    val area: Double,
    val growth: Double,
    val box: Box,
    val headingDegrees: Int = 0,
    val timestampMs: Long,
)
