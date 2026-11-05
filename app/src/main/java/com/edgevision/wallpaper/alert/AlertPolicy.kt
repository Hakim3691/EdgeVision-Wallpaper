package com.edgevision.wallpaper.alert

data class AlertDecision(
    val visual: Boolean,
    val audio: Boolean,
    val haptic: Boolean,
    val spoken: Boolean,
    val forceAudio: Boolean = false,
)

object AlertPolicy {

    /**
     * Blind Assist explicitly bypasses the red visual flash and always keeps an
     * auditory and haptic channel active, even if the ordinary sound/vibration
     * preferences are off. In normal mode, all channels respect user settings.
     */
    fun decide(
        blindAssist: Boolean,
        audibleAlertsEnabled: Boolean,
        hapticsEnabled: Boolean,
    ): AlertDecision {
        if (blindAssist) {
            return AlertDecision(
                visual = false,
                audio = true,
                haptic = true,
                spoken = true,
            )
        }
        return AlertDecision(
            visual = true,
            audio = audibleAlertsEnabled,
            haptic = hapticsEnabled,
            spoken = false,
        )
    }
}
