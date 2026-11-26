package com.andsi.airlyrics.floating

import com.andsi.airlyrics.R
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal fun FloatingLyricsService.applyAutoHideSettings() {
    reevaluateAutoHide()
    if (!QuickFloatingStore.isDesiredVisible(this)) refreshQuickControls()
}

internal fun FloatingLyricsService.reevaluateAutoHide() {
    val conditions = autoHideConditions()
    autoHideState.evaluate(conditions)
    scheduleAutoHideEvaluation()
    if (!conditions.desiredVisible) return

    if (autoHideState.shouldHide) {
        hideLyricsForAutoHide()
    } else {
        restoreVisibleLyricsIfDesired()
    }
}

internal fun FloatingLyricsService.markLyricsLoading(playbackKey: PlaybackLyricsKey) {
    autoHideState.setLyricsAvailability(LyricsAvailability.LOADING, playbackKey)
    reevaluateAutoHide()
}

internal fun FloatingLyricsService.markLyricsAvailable(playbackKey: PlaybackLyricsKey) {
    autoHideState.setLyricsAvailability(LyricsAvailability.AVAILABLE, playbackKey)
    reevaluateAutoHide()
}

internal fun FloatingLyricsService.markLyricsUnavailable(playbackKey: PlaybackLyricsKey) {
    autoHideState.setLyricsAvailability(LyricsAvailability.UNAVAILABLE, playbackKey)
    reevaluateAutoHide()
}

internal fun FloatingLyricsService.resetLyricsAvailability() {
    autoHideState.setLyricsAvailability(LyricsAvailability.UNKNOWN)
    reevaluateAutoHide()
}

internal fun FloatingLyricsService.suppressAutoHideForManualShow() {
    autoHideState.suppressForManualShow(
        autoHideConditions(),
        currentMedia.takeUnless { it.isEmpty }?.playbackLyricsKey()
    )
    scheduleAutoHideEvaluation()
}

private fun FloatingLyricsService.scheduleAutoHideEvaluation() {
    syncHandler.removeCallbacks(autoHideRunnable)
    autoHideState.nextDeadlineUptimeMs?.let { syncHandler.postAtTime(autoHideRunnable, it) }
}

internal fun FloatingLyricsService.clearAutoHideVisibilityState() {
    autoHideState.resetVisibility()
    syncHandler.removeCallbacks(autoHideRunnable)
}

private fun FloatingLyricsService.autoHideConditions() = AutoHideConditions(
    desiredVisible = QuickFloatingStore.isDesiredVisible(this),
    isPlaying = currentMedia.takeUnless { it.isEmpty }?.isPlaying,
    hideWhenPaused = FloatingLyricsStyleStore.isAutoHideWhenPaused(this),
    hideWhenLyricsUnavailable = FloatingLyricsStyleStore.isAutoHideWhenLyricsUnavailable(this)
)

private fun FloatingLyricsService.hideLyricsForAutoHide() {
    if (!isWindowControllerReady()) return
    if (!windowController.isVisible) return

    val hidden = runCatching {
        windowController.hide(notifyVisibilityChanged = false)
    }.getOrDefault(false)
    if (!hidden && windowController.isVisible) {
        refreshQuickControls(getString(R.string.ui_overlay_update_failed))
        return
    }

    stopLyricsSync()
    scheduleSelectedCurrentMediaInfoRefresh()
    refreshQuickControls()
}

internal fun FloatingLyricsService.restoreVisibleLyricsIfDesired() {
    if (!QuickFloatingStore.isDesiredVisible(this)) {
        refreshQuickControls()
        return
    }

    if (autoHideState.shouldHide) {
        scheduleSelectedCurrentMediaInfoRefresh()
        refreshQuickControls()
        return
    }

    startDisplayScopeObservation()
    if (isDisplayScopeBlockingWindow()) {
        hideLyricsForDisplayScope()
        return
    }

    if (isWindowControllerReady() && windowController.isVisible) {
        return
    }

    showLyrics(manual = false)
}
