package com.edgevision.wallpaper.vision

import com.edgevision.wallpaper.inference.HazardKind

/**
 * Turns a stream of single-frame [HazardEvent]s into a graded [ThreatLevel].
 *
 * One weak detection must never flash the screen or fire the shutter. The
 * engine therefore combines four independent signals:
 *
 *  - geometry: box area and expansion rate (how close, how fast),
 *  - confidence,
 *  - persistence: how many consecutive frames agree,
 *  - time: a level decays after the hazard leaves the frame.
 *
 * The rules are pure and deterministic so the behaviour can be specified with
 * Given/When/Then tests instead of being tuned on a device.
 */
class ThreatDecisionEngine(
    private val policy: ThreatPolicy = ThreatPolicy(),
) {

    data class Assessment(
        val level: ThreatLevel,
        val event: HazardEvent?,
        val consecutiveFrames: Int,
        val reason: Reason,
        val atMs: Long,
    ) {
        enum class Reason {
            /** No hazard in this frame. */
            NO_HAZARD,

            /** Below the confidence floor. */
            LOW_CONFIDENCE,

            /** A single frame is never enough for a warning. */
            AWAITING_CONFIRMATION,

            /** Confirmed, graded from geometry and persistence. */
            GRADED,
        }
    }

    private var lastKind: HazardKind? = null
    private var lastSeenAtMs = 0L
    private var consecutiveFrames = 0
    private var lastLevel = ThreatLevel.NONE
    private var lastLevelAtMs = 0L

    fun assess(event: HazardEvent?, nowMs: Long): Assessment {
        if (event == null) {
            val heldLevel = heldLevel(nowMs)
            return Assessment(
                level = heldLevel,
                event = null,
                consecutiveFrames = 0,
                reason = Assessment.Reason.NO_HAZARD,
                atMs = nowMs,
            )
        }

        if (event.confidence < policy.minConfidence) {
            return Assessment(
                level = ThreatLevel.NONE,
                event = event,
                consecutiveFrames = 0,
                reason = Assessment.Reason.LOW_CONFIDENCE,
                atMs = nowMs,
            )
        }

        val continues = event.kind == lastKind && nowMs - lastSeenAtMs <= policy.persistenceWindowMs
        consecutiveFrames = if (continues) consecutiveFrames + 1 else 1
        lastKind = event.kind
        lastSeenAtMs = nowMs

        if (consecutiveFrames < policy.confirmationsRequired) {
            // A first sighting is information at most, never a warning.
            val capped = minOf(grade(event), ThreatLevel.LOW, ThreatLevel.INFO)
            lastLevel = capped
            lastLevelAtMs = nowMs
            return Assessment(
                level = capped,
                event = event,
                consecutiveFrames = consecutiveFrames,
                reason = Assessment.Reason.AWAITING_CONFIRMATION,
                atMs = nowMs,
            )
        }

        val graded = escalate(grade(event), consecutiveFrames)
        lastLevel = graded
        lastLevelAtMs = nowMs
        return Assessment(
            level = graded,
            event = event,
            consecutiveFrames = consecutiveFrames,
            reason = Assessment.Reason.GRADED,
            atMs = nowMs,
        )
    }

    fun reset() {
        lastKind = null
        lastSeenAtMs = 0L
        consecutiveFrames = 0
        lastLevel = ThreatLevel.NONE
        lastLevelAtMs = 0L
    }

    /** Concrete distance/approach grading, before persistence is applied. */
    private fun grade(event: HazardEvent): ThreatLevel {
        val area = event.area.coerceIn(0.0, 1.0)
        val growth = event.growth.coerceAtLeast(1.0)
        return when {
            area >= policy.criticalArea ||
                (area >= policy.highArea && growth >= policy.criticalExpansion) ->
                ThreatLevel.CRITICAL

            area >= policy.highArea ||
                (area >= policy.mediumArea && growth >= policy.highExpansion) ->
                ThreatLevel.HIGH

            area >= policy.mediumArea ||
                (area >= policy.lowArea && growth >= policy.mediumExpansion) ->
                ThreatLevel.MEDIUM

            area >= policy.lowArea -> ThreatLevel.LOW

            else -> ThreatLevel.INFO
        }
    }

    /** Long-running hazards climb one level, bounded by CRITICAL. */
    private fun escalate(level: ThreatLevel, frames: Int): ThreatLevel {
        if (frames < policy.persistenceEscalationFrames) return level
        if (level.rank >= ThreatLevel.CRITICAL.rank) return level
        return ThreatLevel.entries.first { it.rank == level.rank + 1 }
    }

    /** Keeps the last real level briefly so a single dropped frame cannot flicker it. */
    private fun heldLevel(nowMs: Long): ThreatLevel {
        if (lastLevel == ThreatLevel.NONE) return ThreatLevel.NONE
        return if (nowMs - lastLevelAtMs <= policy.levelHoldMs) lastLevel else ThreatLevel.NONE
    }
}

/**
 * Tunables for [ThreatDecisionEngine]. Defaults are conservative: two agreeing
 * frames are required before any warning, and capture needs HIGH or CRITICAL.
 */
data class ThreatPolicy(
    val minConfidence: Double = 0.55,
    /** Consecutive frames of the same hazard kind required before warning. */
    val confirmationsRequired: Int = 2,
    /** How long a hazard may be missing and still count as the same approach. */
    val persistenceWindowMs: Long = 1_200,
    /** Frames at which a confirmed hazard climbs one level. */
    val persistenceEscalationFrames: Int = 4,
    /** How long a graded level survives one frame with no detection. */
    val levelHoldMs: Long = 600,
    val lowArea: Double = 0.05,
    val mediumArea: Double = 0.12,
    val highArea: Double = 0.20,
    val criticalArea: Double = 0.30,
    val mediumExpansion: Double = 1.35,
    val highExpansion: Double = 1.60,
    val criticalExpansion: Double = 2.00,
)
