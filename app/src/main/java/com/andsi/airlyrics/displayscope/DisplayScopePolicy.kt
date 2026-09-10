package com.andsi.airlyrics.displayscope

internal enum class DisplayScopeBlockReason {
    USAGE_ACCESS_REQUIRED,
    CHECKING_SELECTED_APPS,
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
        visibilitySnapshotAvailable: Boolean,
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
        if (!visibilitySnapshotAvailable) {
            return DisplayScopeDecision(
                allowsDisplay = false,
                blockReason = DisplayScopeBlockReason.CHECKING_SELECTED_APPS
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
