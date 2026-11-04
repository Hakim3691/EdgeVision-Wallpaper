package com.edgevision.wallpaper.accessibility

object AssistModeResolver {

    /**
     * Blind Assist is active when the user enables it manually, or when Android's
     * accessibility service is using touch exploration (the usual screen-reader
     * signal). In that mode the visual hazard flash is suppressed.
     */
    fun isBlindAssistActive(
        manualBlindAssist: Boolean,
        accessibilityEnabled: Boolean,
        touchExplorationEnabled: Boolean,
    ): Boolean = manualBlindAssist || (accessibilityEnabled && touchExplorationEnabled)
}
