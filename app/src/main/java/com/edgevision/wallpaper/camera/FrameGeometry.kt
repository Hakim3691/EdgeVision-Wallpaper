package com.edgevision.wallpaper.camera

/**
 * Maps detector pixels from the display-oriented view back to the sensor buffer.
 *
 * Camera2 delivers frames in sensor orientation. On a phone held upright the
 * sensor buffer is landscape while the user sees portrait, so a detector that
 * samples the raw buffer sees the world rotated by 90 degrees. Rotated input
 * badly degrades COCO detectors: cars and poles become side-on objects the model
 * rarely recognises, which is why detection appeared to "not work".
 *
 * The previous pipeline also took a centre square crop of the landscape buffer,
 * discarding 44% of the horizontal field of view. Sampling the whole rotated
 * frame keeps every hazard the user can actually see in view.
 */
object FrameGeometry {

    fun normalize(rotationDegrees: Int): Int = ((rotationDegrees % 360) + 360) % 360

    /** True when the displayed image is the sensor buffer turned on its side. */
    fun isSwapped(rotationDegrees: Int): Boolean {
        val rotation = normalize(rotationDegrees)
        return rotation == 90 || rotation == 270
    }

    fun displayWidth(sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): Int =
        if (isSwapped(rotationDegrees)) sourceHeight else sourceWidth

    fun displayHeight(sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): Int =
        if (isSwapped(rotationDegrees)) sourceWidth else sourceHeight

    /**
     * Source column for a pixel of the upright view. [displayX] and [displayY]
     * are coordinates in the display-oriented image, whose size comes from
     * [displayWidth] and [displayHeight].
     */
    fun sourceX(
        rotationDegrees: Int,
        displayX: Int,
        displayY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Int {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source frame must have a positive size" }
        val x = when (normalize(rotationDegrees)) {
            90 -> displayY
            180 -> sourceWidth - 1 - displayX
            270 -> sourceWidth - 1 - displayY
            else -> displayX
        }
        return x.coerceIn(0, sourceWidth - 1)
    }

    /** Source row for a pixel of the upright view. */
    fun sourceY(
        rotationDegrees: Int,
        displayX: Int,
        displayY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Int {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source frame must have a positive size" }
        val y = when (normalize(rotationDegrees)) {
            90 -> sourceHeight - 1 - displayX
            180 -> sourceHeight - 1 - displayY
            270 -> displayX
            else -> displayY
        }
        return y.coerceIn(0, sourceHeight - 1)
    }
}
