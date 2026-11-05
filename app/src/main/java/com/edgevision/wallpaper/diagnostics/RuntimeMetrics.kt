package com.edgevision.wallpaper.diagnostics

import java.util.Locale

/**
 * Windowed counters that make field behaviour measurable.
 *
 * Phase 0 of production hardening needs numbers from real hardware: how many
 * frames arrive, how many are superseded, how long a full-surface draw takes,
 * and how long inference takes. The service logs one line per window so a
 * `adb logcat` capture turns a "feels laggy" report into evidence.
 */
class RuntimeMetrics {

    data class Snapshot(
        val windowMs: Long,
        val frames: Long,
        val draws: Long,
        val drawAverageMs: Double,
        val drawMaxMs: Long,
        val inferences: Long,
        val inferenceAverageMs: Double,
        val hazards: Long,
        val droppedFrames: Long,
    ) {
        val framesPerSecond: Double
            get() = if (windowMs <= 0L) 0.0 else frames * 1_000.0 / windowMs
    }

    private val lock = Any()
    private var windowStartMs = 0L
    private var frames = 0L
    private var draws = 0L
    private var drawTotalMs = 0L
    private var drawMaxMs = 0L
    private var inferences = 0L
    private var inferenceTotalMs = 0L
    private var hazards = 0L
    private var dropped = 0L

    fun start(nowMs: Long) {
        synchronized(lock) {
            windowStartMs = nowMs
            frames = 0
            draws = 0
            drawTotalMs = 0
            drawMaxMs = 0
            inferences = 0
            inferenceTotalMs = 0
            hazards = 0
            dropped = 0
        }
    }

    fun onFrame() {
        synchronized(lock) { frames += 1 }
    }

    fun onDraw(durationMs: Long) {
        synchronized(lock) {
            draws += 1
            val safe = durationMs.coerceAtLeast(0)
            drawTotalMs += safe
            if (safe > drawMaxMs) drawMaxMs = safe
        }
    }

    fun onInference(latencyMs: Long) {
        synchronized(lock) {
            inferences += 1
            inferenceTotalMs += latencyMs.coerceAtLeast(0)
        }
    }

    fun onHazard() {
        synchronized(lock) { hazards += 1 }
    }

    fun onDroppedFrames(count: Long) {
        synchronized(lock) { dropped = count }
    }

    fun snapshot(nowMs: Long): Snapshot {
        synchronized(lock) {
            if (windowStartMs == 0L) windowStartMs = nowMs
            val window = (nowMs - windowStartMs).coerceAtLeast(0L)
            val snapshot = Snapshot(
                windowMs = window,
                frames = frames,
                draws = draws,
                drawAverageMs = if (draws == 0L) 0.0 else drawTotalMs.toDouble() / draws,
                drawMaxMs = drawMaxMs,
                inferences = inferences,
                inferenceAverageMs = if (inferences == 0L) 0.0 else {
                    inferenceTotalMs.toDouble() / inferences
                },
                hazards = hazards,
                droppedFrames = dropped,
            )
            windowStartMs = nowMs
            frames = 0
            draws = 0
            drawTotalMs = 0
            drawMaxMs = 0
            inferences = 0
            inferenceTotalMs = 0
            hazards = 0
            return snapshot
        }
    }

    fun describe(nowMs: Long): String {
        val snapshot = snapshot(nowMs)
        return String.format(
            Locale.US,
            "window=%.1fs fps=%.1f draws=%d draw_avg=%.1fms draw_max=%dms " +
                "infer=%d infer_avg=%.1fms dropped=%d hazards=%d",
            snapshot.windowMs / 1_000.0,
            snapshot.framesPerSecond,
            snapshot.draws,
            snapshot.drawAverageMs,
            snapshot.drawMaxMs,
            snapshot.inferences,
            snapshot.inferenceAverageMs,
            snapshot.droppedFrames,
            snapshot.hazards,
        )
    }
}
