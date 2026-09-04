package com.andsi.airlyrics.floating

import com.andsi.airlyrics.R
import com.andsi.airlyrics.displayscope.DisplayScopeCapability
import com.andsi.airlyrics.displayscope.DisplayScopePolicy
import com.andsi.airlyrics.displayscope.DisplayScopeVisibilitySnapshot
import com.andsi.airlyrics.settings.store.DisplayScopeStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal fun FloatingLyricsService.applyDisplayScopeSetting() {
    if (!QuickFloatingStore.isDesiredVisible(this)) {
        stopDisplayScopeObservation()
        autoHiddenForDisplayScope = false
        displayScopeBlockReason = null
        refreshQuickControls()
        return
    }

    if (!displayScopeFilterEnabled()) {
        val needsRestore = autoHiddenForDisplayScope
        stopDisplayScopeObservation()
        autoHiddenForDisplayScope = false
        displayScopeBlockReason = null
        if (needsRestore && !autoHiddenForPause) {
            showLyrics(updateDesiredVisible = false)
        } else {
            refreshQuickControls()
        }
        return
    }

    startDisplayScopeObservation()
    val previousBlockReason = displayScopeBlockReason
    if (isDisplayScopeBlockingWindow()) {
        hideLyricsForDisplayScope(
            refreshEvenIfAlreadyHidden = previousBlockReason != displayScopeBlockReason
        )
    } else if (autoHiddenForDisplayScope) {
        restoreAfterDisplayScopeAllows()
    } else {
        refreshQuickControls()
    }
}

internal fun FloatingLyricsService.applyDisplayScopeSnapshot(
    snapshot: DisplayScopeVisibilitySnapshot
) {
    displayScopeUsageAccessGranted = snapshot.usageAccessGranted
    displayScopeVisiblePackages = snapshot.visiblePackages

    if (!QuickFloatingStore.isDesiredVisible(this) || !displayScopeFilterEnabled()) {
        applyDisplayScopeSetting()
        return
    }

    val previousBlockReason = displayScopeBlockReason
    if (isDisplayScopeBlockingWindow()) {
        hideLyricsForDisplayScope(
            refreshEvenIfAlreadyHidden = previousBlockReason != displayScopeBlockReason
        )
    } else {
        restoreAfterDisplayScopeAllows()
    }
}

internal fun FloatingLyricsService.isDisplayScopeBlockingWindow(): Boolean {
    val decision = DisplayScopePolicy.decide(
        enabled = displayScopeFilterEnabled(),
        usageAccessGranted = displayScopeUsageAccessGranted,
        selectedPackages = DisplayScopeStore.selectedPackages(this),
        visiblePackages = displayScopeVisiblePackages
    )
    displayScopeBlockReason = decision.blockReason
    return !decision.allowsDisplay
}

internal fun FloatingLyricsService.startDisplayScopeObservation() {
    if (!displayScopeFilterEnabled() || !QuickFloatingStore.isDesiredVisible(this)) return
    displayScopeMonitor?.start()
}

internal fun FloatingLyricsService.stopDisplayScopeObservation() {
    displayScopeMonitor?.stop()
    displayScopeVisiblePackages = emptySet()
    displayScopeUsageAccessGranted = false
}

internal fun FloatingLyricsService.hideLyricsForDisplayScope(
    refreshEvenIfAlreadyHidden: Boolean = false
) {
    if (!QuickFloatingStore.isDesiredVisible(this)) return
    val alreadyHidden = autoHiddenForDisplayScope &&
        (!isWindowControllerReady() || !windowController.isVisible)
    autoHiddenForDisplayScope = true
    if (alreadyHidden) {
        if (refreshEvenIfAlreadyHidden) refreshQuickControls()
        return
    }

    if (isWindowControllerReady() && windowController.isVisible) {
        val hidden = runCatching {
            windowController.hide(notifyVisibilityChanged = false)
        }.getOrDefault(false)
        if (!hidden && windowController.isVisible) {
            refreshQuickControls(getString(R.string.ui_overlay_update_failed))
            return
        }
    }

    stopLyricsSync()
    scheduleSelectedCurrentMediaInfoRefresh()
    refreshQuickControls()
}

private fun FloatingLyricsService.restoreAfterDisplayScopeAllows() {
    if (!autoHiddenForDisplayScope) return
    autoHiddenForDisplayScope = false
    displayScopeBlockReason = null

    if (autoHiddenForPause &&
        FloatingLyricsStyleStore.isAutoHideWhenPaused(this) &&
        !pauseAutoHideSuppressedByUser) {
        applyAutoHideWhenPaused()
    } else {
        showLyrics(updateDesiredVisible = false)
    }
}

private fun FloatingLyricsService.displayScopeFilterEnabled(): Boolean {
    return DisplayScopeCapability.isSupported() && DisplayScopeStore.isEnabled(this)
}
