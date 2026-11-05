package com.edgevision.wallpaper.camera

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer

data class ConvertedFrame(
    val preview: Bitmap,
    val detectionRgb: ByteArray,
    val rotationDegrees: Int,
)

/**
 * Converts Camera2 YUV_420_888 images without RenderScript or GPU shaders.
 *
 * The preview is rendered at a bounded 512px width, while the detector gets a
 * 320x320 RGB buffer of the whole frame in display orientation. Plane data is
 * copied out once per frame so the per-pixel loops never call ByteBuffer.get()
 * hundreds of thousands of times.
 *
 * Detection buffers rotate through a small pool: the inference worker may still
 * be reading the buffer handed out on a previous frame, and reusing a single
 * array would let the converter write over data that is being decoded.
 */
class YuvFrameConverter {

    private var previewPixels = IntArray(0)
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)
    private val detectionBuffers = arrayOfNulls<ByteArray>(DETECTION_BUFFER_COUNT)
    private var detectionCursor = 0

    fun convert(
        image: Image,
        previewWidth: Int,
        previewHeight: Int,
        detectionSize: Int,
        rotationDegrees: Int,
    ): ConvertedFrame {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        yBytes = readPlane(yPlane.buffer, yBytes)
        uBytes = readPlane(uPlane.buffer, uBytes)
        vBytes = readPlane(vPlane.buffer, vBytes)

        val sourceWidth = image.width
        val sourceHeight = image.height
        val pixels = ensurePixels(previewWidth * previewHeight)

        for (outY in 0 until previewHeight) {
            val sourceY = (outY.toLong() * sourceHeight / previewHeight).toInt()
                .coerceIn(0, sourceHeight - 1)
            val rowBase = outY * previewWidth
            for (outX in 0 until previewWidth) {
                val sourceX = (outX.toLong() * sourceWidth / previewWidth).toInt()
                    .coerceIn(0, sourceWidth - 1)
                pixels[rowBase + outX] = yuvToArgb(
                    sample(yBytes, sourceX, sourceY, yPlane.rowStride, yPlane.pixelStride),
                    sample(uBytes, sourceX / 2, sourceY / 2, uPlane.rowStride, uPlane.pixelStride),
                    sample(vBytes, sourceX / 2, sourceY / 2, vPlane.rowStride, vPlane.pixelStride),
                )
            }
        }

        val bitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, previewWidth, 0, 0, previewWidth, previewHeight)

        val detectionRgb = fillDetectionRgb(
            size = detectionSize,
            rotationDegrees = rotationDegrees,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
        )

        return ConvertedFrame(
            preview = bitmap,
            detectionRgb = detectionRgb,
            rotationDegrees = rotationDegrees,
        )
    }

    /**
     * Samples the whole frame through [FrameGeometry] so the detector receives an
     * upright image that covers the entire field of view, not a rotated centre crop.
     */
    private fun fillDetectionRgb(
        size: Int,
        rotationDegrees: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        yRowStride: Int,
        yPixelStride: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vRowStride: Int,
        vPixelStride: Int,
    ): ByteArray {
        val detectionRgb = acquireDetectionBuffer(size * size * RGB_CHANNELS)
        val displayWidth = FrameGeometry.displayWidth(sourceWidth, sourceHeight, rotationDegrees)
        val displayHeight = FrameGeometry.displayHeight(sourceWidth, sourceHeight, rotationDegrees)
        var outputIndex = 0

        for (outY in 0 until size) {
            val displayY = (outY.toLong() * displayHeight / size).toInt()
                .coerceIn(0, displayHeight - 1)
            for (outX in 0 until size) {
                val displayX = (outX.toLong() * displayWidth / size).toInt()
                    .coerceIn(0, displayWidth - 1)
                val sourceX = FrameGeometry.sourceX(
                    rotationDegrees = rotationDegrees,
                    displayX = displayX,
                    displayY = displayY,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                )
                val sourceY = FrameGeometry.sourceY(
                    rotationDegrees = rotationDegrees,
                    displayX = displayX,
                    displayY = displayY,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                )
                val argb = yuvToArgb(
                    sample(yBytes, sourceX, sourceY, yRowStride, yPixelStride),
                    sample(uBytes, sourceX / 2, sourceY / 2, uRowStride, uPixelStride),
                    sample(vBytes, sourceX / 2, sourceY / 2, vRowStride, vPixelStride),
                )
                detectionRgb[outputIndex++] = ((argb shr 16) and 0xFF).toByte()
                detectionRgb[outputIndex++] = ((argb shr 8) and 0xFF).toByte()
                detectionRgb[outputIndex++] = (argb and 0xFF).toByte()
            }
        }
        return detectionRgb
    }

    private fun acquireDetectionBuffer(size: Int): ByteArray {
        val index = detectionCursor
        detectionCursor = (detectionCursor + 1) % DETECTION_BUFFER_COUNT
        val existing = detectionBuffers[index]
        val buffer = if (existing != null && existing.size == size) existing else ByteArray(size)
        detectionBuffers[index] = buffer
        return buffer
    }

    private fun ensurePixels(size: Int): IntArray {
        if (previewPixels.size != size) {
            previewPixels = IntArray(size)
        }
        return previewPixels
    }

    private fun readPlane(buffer: ByteBuffer, reusable: ByteArray): ByteArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        val size = duplicate.remaining()
        val target = if (reusable.size == size) reusable else ByteArray(size)
        duplicate.get(target, 0, size)
        return target
    }

    private fun sample(
        bytes: ByteArray,
        x: Int,
        y: Int,
        rowStride: Int,
        pixelStride: Int,
    ): Int {
        if (bytes.isEmpty()) return 0
        val index = y * rowStride + x * pixelStride
        return bytes[index.coerceIn(0, bytes.lastIndex)].toInt() and 0xFF
    }

    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val c = (y - 16).coerceAtLeast(0)
        val d = u - 128
        val e = v - 128
        val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private companion object {
        const val RGB_CHANNELS = 3
        const val DETECTION_BUFFER_COUNT = 3
    }
}
