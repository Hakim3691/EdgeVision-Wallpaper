package com.edgevision.wallpaper.capture

import com.edgevision.wallpaper.vision.ThreatLevel

/** What asked for the burst. Recorded so history can separate the two paths. */
enum class CaptureTrigger {
    /** Fired by the threat decision engine. */
    THREAT_AUTO,

    /** Fired by the user from the app. */
    MANUAL,
}

/** A burst the camera layer should execute. */
data class BurstRequest(
    val trigger: CaptureTrigger,
    val shots: Int,
    val intervalMs: Long,
    val requestedAtMs: Long,
)

/** Why a capture was or was not produced. Every refusal is explicit. */
sealed interface CaptureDecision {
    data class Burst(val request: BurstRequest) : CaptureDecision

    data class Refused(val reason: Refusal) : CaptureDecision

    enum class Refusal {
        /** Automatic capture is disarmed by the user. */
        NOT_ARMED,

        /** Threat level below the capture threshold. */
        THREAT_TOO_LOW,

        /** A burst is already running. */
        BUSY,

        /** Inside the post-burst cooldown window. */
        COOLDOWN,

        /** Burst size configured to zero. */
        DISABLED,
    }
}

data class BurstConfig(
    val shots: Int = 3,
    val intervalMs: Long = 350L,
    val cooldownMs: Long = 15_000L,
) {
    init {
        require(shots in 0..10) { "shots must be between 0 and 10, was $shots" }
        require(intervalMs >= 0L) { "intervalMs must not be negative" }
        require(cooldownMs >= 0L) { "cooldownMs must not be negative" }
    }
}

/**
 * Owns capture mode arming, burst admission and the cooldown window.
 *
 * This is deliberately free of Android and camera types: the wallpaper engine
 * asks it whether a burst is allowed, and a camera implementation performs the
 * shutter. That keeps the safety-critical rules ("one weak detection must not
 * fire the shutter", "never overlap bursts", "respect cooldown") unit-testable.
 *
 * Automatic capture requires both arming and a threat at or above
 * [captureThreshold]. Manual capture ignores arming but still honours a running
 * burst and the cooldown, so a user tap cannot corrupt an automatic sequence.
 */
class CaptureController(
    private var config: BurstConfig = BurstConfig(),
    private val captureThreshold: ThreatLevel = ThreatLevel.HIGH,
) {

    var isArmed: Boolean = false
        private set

    var isBurstRunning: Boolean = false
        private set

    /** Shots completed in the running burst. */
    var completedShots: Int = 0
        private set

    private var runningTrigger: CaptureTrigger? = null
    private var lastBurstFinishedAtMs: Long? = null

    fun updateConfig(newConfig: BurstConfig) {
        config = newConfig
    }

    fun currentConfig(): BurstConfig = config

    /** Returns the new armed state so callers can post feedback without re-reading. */
    fun setArmed(armed: Boolean): Boolean {
        isArmed = armed
        return isArmed
    }

    fun toggleArmed(): Boolean = setArmed(!isArmed)

    /** Called by the decision path for every graded threat. */
    fun onThreat(level: ThreatLevel, nowMs: Long): CaptureDecision {
        if (!isArmed) return CaptureDecision.Refused(CaptureDecision.Refusal.NOT_ARMED)
        if (!level.atLeast(captureThreshold)) {
            return CaptureDecision.Refused(CaptureDecision.Refusal.THREAT_TOO_LOW)
        }
        return admit(CaptureTrigger.THREAT_AUTO, nowMs)
    }

    /** Called when the user explicitly asks for a burst. */
    fun onManualRequest(nowMs: Long): CaptureDecision = admit(CaptureTrigger.MANUAL, nowMs)

    /** Marks the burst as started; returns the request so the caller runs exactly that plan. */
    fun onBurstStarted(trigger: CaptureTrigger, nowMs: Long): BurstRequest {
        isBurstRunning = true
        completedShots = 0
        runningTrigger = trigger
        return BurstRequest(
            trigger = trigger,
            shots = config.shots,
            intervalMs = config.intervalMs,
            requestedAtMs = nowMs,
        )
    }

    /** Records one completed shutter press. */
    fun onShotCaptured() {
        if (completedShots < config.shots) completedShots += 1
    }

    fun onBurstFinished(nowMs: Long) {
        isBurstRunning = false
        runningTrigger = null
        lastBurstFinishedAtMs = nowMs
    }

    fun onBurstFailed(nowMs: Long) {
        isBurstRunning = false
        runningTrigger = null
        lastBurstFinishedAtMs = nowMs
    }

    fun cooldownRemainingMs(nowMs: Long): Long {
        val finishedAt = lastBurstFinishedAtMs ?: return 0L
        val elapsed = nowMs - finishedAt
        return (config.cooldownMs - elapsed).coerceAtLeast(0L)
    }

    fun reset() {
        isArmed = false
        isBurstRunning = false
        completedShots = 0
        runningTrigger = null
        lastBurstFinishedAtMs = null
    }

    private fun admit(trigger: CaptureTrigger, nowMs: Long): CaptureDecision {
        if (config.shots <= 0) return CaptureDecision.Refused(CaptureDecision.Refusal.DISABLED)
        if (isBurstRunning) return CaptureDecision.Refused(CaptureDecision.Refusal.BUSY)
        if (cooldownRemainingMs(nowMs) > 0L) {
            return CaptureDecision.Refused(CaptureDecision.Refusal.COOLDOWN)
        }
        return CaptureDecision.Burst(onBurstStarted(trigger, nowMs))
    }
}
