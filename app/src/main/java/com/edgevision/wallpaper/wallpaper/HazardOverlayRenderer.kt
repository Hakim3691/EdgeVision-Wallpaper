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
 * made on the wallpaper's main thread; camera bitmaps are handed over through the
 * engine after posting to that thread.
 *
 * Every draw is a full-surface software composite, so it is the most expensive
 * thing the wallpaper does on the main thread. Draws return their duration so the
 * service can log real numbers instead of guessing at jank.
 *
 * A [setNotice] message replaces the otherwise empty home screen when the camera
 * cannot run (permission missing, no camera hardware). Without it those states
 * looked like a broken wallpaper.
 */
class HazardOverlayRenderer(
    private val settings: AssistSettings,
    private val density: Float,
) {

    // Dithering is deliberately off: it costs a per-pixel rounding pass on the
    // scaled preview and is invisible behind the translucent overlay.
    private val previewPaint = Paint(Paint.FILTER_BITMAP_FLAG)
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
    private val noticePanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 12, 14, 18)
    }
    private val noticeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
    }
    private val noticeRect = RectF()

    private var holder: SurfaceHolder? = null
    private var preview: Bitmap? = null
    private var rotationDegrees = 0
    private var hazardUntilMs = 0L
    private var visualAlertEnabled = false
    private var notice: String? = null
    /** True when capture mode is armed; drives the transient red-dot indicator. */
    // NOTE: captureArmed state is now managed by CaptureState object for single source of truth.
    // This local variable is kept for read-only observation only.
    private val captureArmed by CaptureState.captureArmed.asState()
    /** Alpha of the red-dot indicator, animated from 1.0 to 0.0 over 2000ms. */
    // Managed by CaptureState; renderer observes via state flow.
    // The fade is handled by CameraWallpaperService's captureFadeRunnable.
    private val captureArmedAlpha by CaptureState.captureArmedAlpha.map { it }

    fun attach(surfaceHolder: SurfaceHolder) {
        holder = surfaceHolder
        draw()
    }

    fun detach() {
        holder = null
        preview?.recycle()
        preview = null
    }

    fun release() {
        hazardUntilMs = 0L
        visualAlertEnabled = false
        notice = null
        detach()
    }

    fun clear() {
        hazardUntilMs = 0L
        visualAlertEnabled = false
        notice = null
        draw()
    }

    /**
     * Shows or removes the explanatory panel. Passing the text that is already
     * shown is a no-op, so the engine can call this on every camera state change
     * without paying for another full-surface composite.
     */
    fun setNotice(text: String?): Long {
        if (notice == text) return 0L
        notice = text
        return draw()
    }

    /** Takes ownership of [bitmap]: it is drawn once and recycled when replaced. */
    fun onFrame(bitmap: Bitmap, rotation: Int): Long {
        if (holder == null) {
            bitmap.recycle()
            return 0L
        }
        val previous = preview
        preview = bitmap
        rotationDegrees = rotation
        previous?.recycle()
        return draw()
    }

    fun onHazard(nowMs: Long, showVisualAlert: Boolean): Long {
        hazardUntilMs = nowMs + HAZARD_HOLD_MS
        visualAlertEnabled = showVisualAlert
        return draw(nowMs)
    }

    fun isActive(nowMs: Long): Boolean = nowMs < hazardUntilMs

    /** Draws one frame and returns how long the composite took, in milliseconds. */
    fun draw(nowMs: Long = SystemClock.elapsedRealtime()): Long {
        val surfaceHolder = holder ?: return 0L
        if (!surfaceHolder.surface.isValid) return 0L

        val startedAt = SystemClock.elapsedRealtime()
        val canvas = try {
            surfaceHolder.lockCanvas()
        } catch (throwable: Throwable) {
            null
        } ?: return 0L

        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            preview?.let { bitmap -> drawPreview(canvas, bitmap) }
            if (visualAlertEnabled && nowMs < hazardUntilMs) {
                drawHazardBorder(canvas, nowMs)
            }

            // Draw transient capture-armed indicator (red dot/slashed icon fading over 2000ms)
            if (captureArmed) {
                val alpha = captureArmedAlpha * 255f
                drawCaptureIndicator(canvas, nowMs, alpha.coerceAt(0f, 255f).toInt())
            }
            notice?.let { text -> drawNotice(canvas, text) }
        } finally {
            runCatching { surfaceHolder.unlockCanvasAndPost(canvas) }
        }
        return SystemClock.elapsedRealtime() - startedAt
    }

    /** Updates the capture-armed state.
     *  Must be called from the wallpaper's main thread.
     *  The alpha fade-out over 2000ms is handled by the service.
     */
    fun updateCaptureArmed(armed: Boolean, nowMs: Long) {
        // Update the shared single source of truth for captureArmed state.
        CaptureState.captureArmed.value = armed
        if (armed) {
            CaptureState.captureArmedAlpha.value = 1f
            CaptureState.captureArmedToggleMs = nowMs
        } else {
            CaptureState.captureArmedAlpha.value = 0f
            CaptureState.captureArmedToggleMs = 0L
        }
    }

    private companion object {
        val BORDER_COLOR = Color.rgb(255, 69, 58)
        const val HAZARD_HOLD_MS = 1_400L
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

    private fun drawNotice(canvas: Canvas, text: String) {
        if (canvas.width == 0 || canvas.height == 0) return
        val maxTextWidth = canvas.width * 0.76f
        val lines = wrapNotice(text, maxTextWidth)
        if (lines.isEmpty()) return

        val metrics = noticeTextPaint.fontMetrics
        val lineHeight = (metrics.descent - metrics.ascent) + 3f * density
        val padding = 15f * density
        val panelWidth = (lines.maxOf { noticeTextPaint.measureText(it) } + padding * 2f)
            .coerceIn(canvas.width * 0.3f, canvas.width * 0.9f)
        val panelHeight = lines.size * lineHeight + padding * 2f
        val left = (canvas.width - panelWidth) / 2f
        val bottom = canvas.height - 40f * density
        val top = bottom - panelHeight

        noticeRect.set(left, top, left + panelWidth, bottom)
        val radius = 18f * density
        canvas.drawRoundRect(noticeRect, radius, radius, noticePanelPaint)

        var baseline = top + padding - metrics.ascent
        for (line in lines) {
            canvas.drawText(line, left + panelWidth / 2f, baseline, noticeTextPaint)
            baseline += lineHeight
        }
    }

    /** Greedy word wrap so a long notice never runs off a narrow wallpaper. */
    private fun wrapNotice(text: String, maxWidth: Float): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && noticeTextPaint.measureText(candidate) > maxWidth) {
                lines += current.toString()
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /** Draws a transient red-dot or slashed-icon indicator for capture-armed state.
     *  Alpha animates from 1.0 to 0.0 over 2000ms once captureArmed is set.
     */
    private fun drawCaptureIndicator(canvas: Canvas, nowMs: Long, alpha: Int) {
        val size = 40f * density
        val margin = 20f * density
        val x = canvas.width - size - margin
        val y = size + margin

        // Draw outer circle (red when armed)
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            alpha = alpha
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
        }
        canvas.drawCircle(x + size / 2f, y + size / 2f, size / 2f, circlePaint)

        // Draw slashed line when armed (indicating capture mode)
        // Armed state comes from CaptureState; check its current value.
        if (captureArmed) {
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = alpha
                strokeWidth = 4f * density
            }
            val inset = size / 5f
            canvas.drawLine(x + inset, y + inset, x + size - inset, y + size - inset, linePaint)
            canvas.drawLine(x + inset, y + size - inset, x + size - inset, y + inset, linePaint)
        }
    }

    private companion object {
        val BORDER_COLOR = Color.rgb(255, 69, 58)
        const val HAZARD_HOLD_MS = 1_400L
    }
}
