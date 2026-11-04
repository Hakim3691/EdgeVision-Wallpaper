package com.edgevision.wallpaper.inference

import kotlin.math.max
import kotlin.math.min

enum class HazardKind {
    VEHICLE,
    POLE;

    companion object {
        fun fromWire(value: String?): HazardKind? = when (value?.lowercase()) {
            "vehicle" -> VEHICLE
            "pole", "obstacle" -> POLE
            else -> null
        }
    }
}

/** Normalized [0, 1] box in image coordinates, with a top-left origin. */
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Double get() = width.toDouble() * height.toDouble()

    fun iou(other: Box): Double {
        val interLeft = max(left, other.left)
        val interTop = max(top, other.top)
        val interRight = min(right, other.right)
        val interBottom = min(bottom, other.bottom)
        val interWidth = (interRight - interLeft).coerceAtLeast(0f)
        val interHeight = (interBottom - interTop).coerceAtLeast(0f)
        val intersection = interWidth.toDouble() * interHeight.toDouble()
        val union = area + other.area - intersection
        return if (union <= 1e-9) 0.0 else (intersection / union).coerceIn(0.0, 1.0)
    }

    companion object {
        fun fromNormalized(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ): Box {
            val clampedLeft = left.coerceIn(0f, 1f)
            val clampedTop = top.coerceIn(0f, 1f)
            val clampedRight = right.coerceIn(0f, 1f)
            val clampedBottom = bottom.coerceIn(0f, 1f)
            return Box(
                left = min(clampedLeft, clampedRight),
                top = min(clampedTop, clampedBottom),
                right = max(clampedLeft, clampedRight),
                bottom = max(clampedTop, clampedBottom),
            )
        }
    }
}

data class Detection(
    val label: String,
    val hazard: HazardKind?,
    val confidence: Float,
    val box: Box,
)

data class InferenceResult(
    val detections: List<Detection>,
    val backend: String,
    val latencyMs: Long,
    val inputSize: Int,
)
