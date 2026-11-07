package com.edgevision.wallpaper.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.SystemClock
import android.view.SurfaceHolder
import com.edgevision.wallpaper.settings.AssistSettings
import kotlin.math.max
import kotlin.math.sin

/**
 * Draws into the wallpaper surface with a translucent backing. All calls must be
 * made on the wallpaper's main thread.
 *
 * The renderer never owns the preview bitmap: buffers come from the camera pool
 * and are returned by the engine after drawing. It keeps the last frame so a
 * surface redraw (launcher resume, keyguard dismissal) repaints immediately
 * instead of showing an empty screen until the camera reopens.
 */
class HazardOverlayRenderer(
    private val settings: AssistSettings,
    private val density: Float,
) {

    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = BORDER_COLOR
        strokeWidth = 9f * density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = BORDER_COLOR
        strokeWidth = 22f * density
    }

    private var holder: SurfaceHolder? = null
    private var preview: Bitmap? = null
    private var rotationDegrees = 0
    private var hazardUntilMs = 0L
    private var visualAlertEnabled = false

    fun attach(surfaceHolder: SurfaceHolder) {
        holder = surfaceHolder
        draw()
    }

    fun detach() {
        holder = null
    }

    fun release() {
        hazardUntilMs = 0L
        visualAlertEnabled = false
        preview = null
        detach()
    }

    fun clear() {
        hazardUntilMs = 0L
        visualAlertEnabled = false
        preview = null
        draw()
    }

    fun onFrame(bitmap: Bitmap, rotation: Int) {
        if (holder == null) return
        preview = bitmap
        rotationDegrees = rotation
        draw()
    }

    fun onHazard(nowMs: Long, showVisualAlert: Boolean) {
        hazardUntilMs = nowMs + HAZARD_HOLD_MS
        visualAlertEnabled = showVisualAlert
        draw(nowMs)
    }

    fun isActive(nowMs: Long): Boolean = nowMs < hazardUntilMs

    fun draw(nowMs: Long = SystemClock.uptimeMillis()) {
        val surfaceHolder = holder ?: return
        val canvas = try {
            surfaceHolder.lockCanvas()
        } catch (throwable: Throwable) {
            null
        } ?: return

        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            preview?.let { bitmap -> drawPreview(canvas, bitmap) }
            if (visualAlertEnabled && nowMs < hazardUntilMs) {
                drawHazardBorder(canvas, nowMs)
            }
        } finally {
            runCatching { surfaceHolder.unlockCanvasAndPost(canvas) }
        }
    }

    private fun drawPreview(canvas: Canvas, bitmap: Bitmap) {
        if (bitmap.isRecycled || canvas.width == 0 || canvas.height == 0) return
        previewPaint.alpha = settings.overlayAlpha

        val canvasWidth = canvas.width.toFloat()
        val canvasHeight = canvas.height.toFloat()
        val swapped = rotationDegrees % 180 != 0
        val rotatedWidth = if (swapped) bitmap.height else bitmap.width
        val rotatedHeight = if (swapped) bitmap.width else bitmap.height
        if (rotatedWidth <= 0 || rotatedHeight <= 0) return
        val scale = max(canvasWidth / rotatedWidth, canvasHeight / rotatedHeight)

        canvas.save()
        canvas.translate(canvasWidth / 2f, canvasHeight / 2f)
        canvas.rotate(rotationDegrees.toFloat())
        canvas.scale(scale, scale)
        canvas.drawBitmap(
            bitmap,
            -bitmap.width / 2f,
            -bitmap.height / 2f,
            previewPaint,
        )
        canvas.restore()
    }

    private fun drawHazardBorder(canvas: Canvas, nowMs: Long) {
        val pulse = ((sin(nowMs / 140.0) + 1.0) / 2.0).toFloat()
        val margin = 10f * density
        val rect = RectF(
            margin,
            margin,
            canvas.width - margin,
            canvas.height - margin,
        )

        borderPaint.alpha = (140 + 115 * pulse).toInt().coerceIn(0, 255)
        canvas.drawRect(rect, borderPaint)

        val glowRect = RectF(rect)
        glowRect.inset(4f * density, 4f * density)
        glowPaint.alpha = (24 + 46 * pulse).toInt().coerceIn(0, 255)
        canvas.drawRect(glowRect, glowPaint)
    }

    private companion object {
        val BORDER_COLOR = Color.rgb(255, 69, 58)
        const val HAZARD_HOLD_MS = 1_400L
    }
}
