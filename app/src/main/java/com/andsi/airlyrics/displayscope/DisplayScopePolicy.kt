package com.andsi.airlyrics.displayscope

internal enum class DisplayScopeBlockReason {
    USAGE_ACCESS_REQUIRED,
    WAITING_FOR_SELECTED_APP
}

internal data class DisplayScopeDecision(
    val allowsDisplay: Boolean,
    val blockReason: DisplayScopeBlockReason? = null
)

internal object DisplayScopePolicy {
    fun decide(
        enabled: Boolean,
        usageAccessGranted: Boolean,
        selectedPackages: Set<String>,
        visiblePackages: Set<String>
    ): DisplayScopeDecision {
        if (!enabled) return DisplayScopeDecision(allowsDisplay = true)
        if (!usageAccessGranted) {
            return DisplayScopeDecision(
                allowsDisplay = false,
                blockReason = DisplayScopeBlockReason.USAGE_ACCESS_REQUIRED
            )
        }
        val selectedAppVisible = selectedPackages.any(visiblePackages::contains)
        return if (selectedAppVisible) {
            DisplayScopeDecision(allowsDisplay = true)
        } else {
            DisplayScopeDecision(
                allowsDisplay = false,
                blockReason = DisplayScopeBlockReason.WAITING_FOR_SELECTED_APP
            )
        }
    }
}
