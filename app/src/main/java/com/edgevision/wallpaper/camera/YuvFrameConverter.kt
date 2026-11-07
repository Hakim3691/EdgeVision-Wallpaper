package com.edgevision.wallpaper.camera

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One converted camera frame.
 *
 * The preview bitmap is owned by [CameraController]'s buffer pool, not by the
 * renderer: the consumer must call [markConsumed] once it has finished drawing so
 * the buffer can be reused for a later frame. Never recycle the bitmap.
 */
class ConvertedFrame(
    val preview: Bitmap,
    val detectionRgb: ByteArray?,
    val rotationDegrees: Int,
    private val onConsumed: () -> Unit,
) {
    private val consumed = AtomicBoolean(false)

    fun markConsumed() {
        if (consumed.compareAndSet(false, true)) onConsumed()
    }
}

/**
 * Converts Camera2 YUV_420_888 images without RenderScript or GPU shaders.
 *
 * The preview is written into a caller-supplied bitmap so steady-state rendering
 * does not allocate, and the detector input is only produced when a target buffer
 * is supplied. That keeps the camera thread free of the ~300 KB per-frame garbage
 * that previously caused GC stalls in the live view.
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
        preview: Bitmap,
        detectionTarget: ByteArray?,
        onConsumed: () -> Unit,
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
        preview.setPixels(pixels, 0, previewWidth, 0, 0, previewWidth, previewHeight)

        if (detectionTarget != null) {
            writeDetectionInput(
                target = detectionTarget,
                detectionSize = detectionSize,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                yPlaneRowStride = yPlane.rowStride,
                yPlanePixelStride = yPlane.pixelStride,
                uPlaneRowStride = uPlane.rowStride,
                uPlanePixelStride = uPlane.pixelStride,
                vPlaneRowStride = vPlane.rowStride,
                vPlanePixelStride = vPlane.pixelStride,
            )
        }

        return ConvertedFrame(
            preview = preview,
            detectionRgb = detectionTarget,
            rotationDegrees = rotationDegrees,
            onConsumed = onConsumed,
        )
    }

    private fun writeDetectionInput(
        target: ByteArray,
        detectionSize: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        yPlaneRowStride: Int,
        yPlanePixelStride: Int,
        uPlaneRowStride: Int,
        uPlanePixelStride: Int,
        vPlaneRowStride: Int,
        vPlanePixelStride: Int,
    ) {
        val cropSide = min(sourceWidth, sourceHeight)
        val cropLeft = (sourceWidth - cropSide) / 2
        val cropTop = (sourceHeight - cropSide) / 2
        var outputIndex = 0
        for (outY in 0 until detectionSize) {
            val sourceY = cropTop + (outY.toLong() * cropSide / detectionSize).toInt()
                .coerceIn(0, sourceHeight - 1)
            for (outX in 0 until detectionSize) {
                val sourceX = cropLeft + (outX.toLong() * cropSide / detectionSize).toInt()
                    .coerceIn(0, sourceWidth - 1)
                val argb = yuvToArgb(
                    sample(yBytes, sourceX, sourceY, yPlaneRowStride, yPlanePixelStride),
                    sample(uBytes, sourceX / 2, sourceY / 2, uPlaneRowStride, uPlanePixelStride),
                    sample(vBytes, sourceX / 2, sourceY / 2, vPlaneRowStride, vPlanePixelStride),
                )
                target[outputIndex++] = ((argb shr 16) and 0xFF).toByte()
                target[outputIndex++] = ((argb shr 8) and 0xFF).toByte()
                target[outputIndex++] = (argb and 0xFF).toByte()
            }
        }
    }

    /**
     * Scales the source frame to a bounded preview width and fills [preview].
     * The returned height is what the caller must size the bitmap to.
     */
    fun previewHeightFor(image: Image): Int {
        val aspect = image.width.toFloat() / image.height.toFloat()
        return (PREVIEW_MAX_WIDTH / aspect).roundToInt().coerceAtLeast(2)
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

    companion object {
        const val PREVIEW_MAX_WIDTH = 512
    }
}
