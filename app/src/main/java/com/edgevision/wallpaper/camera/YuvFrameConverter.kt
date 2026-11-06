package com.edgevision.wallpaper.camera

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

data class ConvertedFrame(
    val preview: Bitmap,
    val detectionRgb: ByteArray,
    val rotationDegrees: Int,
)

/**
 * Converts Camera2 YUV_420_888 images without RenderScript or GPU shaders.
 *
 * The preview is rendered at a bounded 512px width, while the detector gets a
 * center-cropped 320x320 RGB buffer. Plane data is copied out once per frame so
 * the per-pixel loops never call ByteBuffer.get() hundreds of thousands of times.
 */
class YuvFrameConverter {

    private var previewPixels = IntArray(0)
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)

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

        val cropSide = min(sourceWidth, sourceHeight)
        val cropLeft = (sourceWidth - cropSide) / 2
        val cropTop = (sourceHeight - cropSide) / 2
        val detectionRgb = ByteArray(detectionSize * detectionSize * 3)
        var outputIndex = 0
        for (outY in 0 until detectionSize) {
            val sourceY = cropTop + (outY.toLong() * cropSide / detectionSize).toInt()
                .coerceIn(0, sourceHeight - 1)
            for (outX in 0 until detectionSize) {
                val sourceX = cropLeft + (outX.toLong() * cropSide / detectionSize).toInt()
                    .coerceIn(0, sourceWidth - 1)
                val argb = yuvToArgb(
                    sample(yBytes, sourceX, sourceY, yPlane.rowStride, yPlane.pixelStride),
                    sample(uBytes, sourceX / 2, sourceY / 2, uPlane.rowStride, uPlane.pixelStride),
                    sample(vBytes, sourceX / 2, sourceY / 2, vPlane.rowStride, vPlane.pixelStride),
                )
                detectionRgb[outputIndex++] = ((argb shr 16) and 0xFF).toByte()
                detectionRgb[outputIndex++] = ((argb shr 8) and 0xFF).toByte()
                detectionRgb[outputIndex++] = (argb and 0xFF).toByte()
            }
        }

        return ConvertedFrame(
            preview = bitmap,
            detectionRgb = detectionRgb,
            rotationDegrees = rotationDegrees,
        )
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
}
