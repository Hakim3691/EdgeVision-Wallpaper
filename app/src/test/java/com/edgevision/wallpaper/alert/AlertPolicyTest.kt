package com.edgevision.wallpaper.alert

import com.edgevision.wallpaper.accessibility.AssistModeResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {

    @Test
    fun blindAssistBypassesVisualFlashAndKeepsSoundAndHaptics() {
        val decision = AlertPolicy.decide(
            blindAssist = true,
            audibleAlertsEnabled = false,
            hapticsEnabled = false,
        )

        assertFalse("visual flash must be bypassed", decision.visual)
        assertTrue(decision.audio)
        assertTrue(decision.haptic)
        assertTrue(decision.spoken)
    }

    @Test
    fun normalModeUsesVisualAndRespectsPreferences() {
        val decision = AlertPolicy.decide(
            blindAssist = false,
            audibleAlertsEnabled = false,
            hapticsEnabled = false,
        )

        assertTrue(decision.visual)
        assertFalse(decision.audio)
        assertFalse(decision.haptic)
        assertFalse(decision.spoken)
    }

    @Test
    fun touchExplorationActivatesBlindAssistAutomatically() {
        assertTrue(
            AssistModeResolver.isBlindAssistActive(
                manualBlindAssist = false,
                accessibilityEnabled = true,
                touchExplorationEnabled = true,
            ),
        )
        assertFalse(
            AssistModeResolver.isBlindAssistActive(
                manualBlindAssist = false,
                accessibilityEnabled = true,
                touchExplorationEnabled = false,
            ),
        )
    }
}
