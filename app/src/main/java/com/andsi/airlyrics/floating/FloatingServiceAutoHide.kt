package com.andsi.airlyrics.floating

import com.andsi.airlyrics.R
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal enum class AutoHideReason {
    PAUSED,
    LYRICS_UNAVAILABLE
}

internal enum class LyricsAvailability {
    UNKNOWN,
    LOADING,
    AVAILABLE,
    UNAVAILABLE
}

internal fun FloatingLyricsService.applyAutoHideSettings() {
    if (!QuickFloatingStore.isDesiredVisible(this)) {
        clearAutoHideVisibilityState()
        refreshQuickControls()
        return
    }

    reevaluateAutoHide()
}

internal fun FloatingLyricsService.reevaluateAutoHide() {
    reevaluatePausedAutoHide()
    reevaluateUnavailableLyricsAutoHide()
    reconcileAutoHideVisibility()
}

internal fun FloatingLyricsService.markLyricsLoading(playbackKey: PlaybackLyricsKey) {
    val songChanged = lyricsAvailabilityKey != playbackKey
    lyricsAvailabilityKey = playbackKey
    lyricsAvailability = LyricsAvailability.LOADING
    cancelPendingUnavailableLyricsAutoHide()
    if (songChanged) {
        lyricsUnavailableAutoHideSuppressedKey = null
    }
    // Keep an already-active unavailable-lyrics reason while the replacement lookup runs.
    // This avoids flashing the window between two songs that both have no usable lyrics.
    reevaluateAutoHide()
}

internal fun FloatingLyricsService.markLyricsAvailable(playbackKey: PlaybackLyricsKey) {
    lyricsAvailabilityKey = playbackKey
    lyricsAvailability = LyricsAvailability.AVAILABLE
    lyricsUnavailableAutoHideSuppressedKey = null
    cancelPendingUnavailableLyricsAutoHide()
    activeAutoHideReasons.remove(AutoHideReason.LYRICS_UNAVAILABLE)
    reevaluateAutoHide()
}

internal fun FloatingLyricsService.markLyricsUnavailable(playbackKey: PlaybackLyricsKey) {
    lyricsAvailabilityKey = playbackKey
    lyricsAvailability = LyricsAvailability.UNAVAILABLE
    reevaluateAutoHide()
}

internal fun FloatingLyricsService.resetLyricsAvailability() {
    lyricsAvailabilityKey = null
    lyricsAvailability = LyricsAvailability.UNKNOWN
    lyricsUnavailableAutoHideSuppressedKey = null
    cancelPendingUnavailableLyricsAutoHide()
    activeAutoHideReasons.remove(AutoHideReason.LYRICS_UNAVAILABLE)
    reevaluateAutoHide()
}

internal fun FloatingLyricsService.suppressAutoHideForManualShow() {
    val pausedReasonApplies = AutoHideReason.PAUSED in activeAutoHideReasons ||
        (!currentMedia.isEmpty && !currentMedia.isPlaying)
    if (FloatingLyricsStyleStore.isAutoHideWhenPaused(this) && pausedReasonApplies) {
        pauseAutoHideSuppressedByUser = true
        cancelPendingPauseAutoHide()
        activeAutoHideReasons.remove(AutoHideReason.PAUSED)
    }

    val playbackKey = currentMedia.takeUnless { it.isEmpty }?.playbackLyricsKey()
    val unavailableReasonApplies = lyricsAvailability == LyricsAvailability.UNAVAILABLE ||
        AutoHideReason.LYRICS_UNAVAILABLE in activeAutoHideReasons
    if (FloatingLyricsStyleStore.isAutoHideWhenLyricsUnavailable(this) &&
        playbackKey != null &&
        unavailableReasonApplies
    ) {
        lyricsUnavailableAutoHideSuppressedKey = playbackKey
        cancelPendingUnavailableLyricsAutoHide()
        activeAutoHideReasons.remove(AutoHideReason.LYRICS_UNAVAILABLE)
    }
}

internal fun FloatingLyricsService.applyScheduledPauseAutoHide() {
    pauseAutoHidePending = false
    if (!shouldAutoHidePausedPlayback()) {
        reevaluateAutoHide()
        return
    }

    activeAutoHideReasons += AutoHideReason.PAUSED
    reconcileAutoHideVisibility()
}

internal fun FloatingLyricsService.applyScheduledUnavailableLyricsAutoHide() {
    unavailableLyricsAutoHidePending = false
    if (!shouldAutoHideUnavailableLyrics()) {
        reevaluateAutoHide()
        return
    }

    activeAutoHideReasons += AutoHideReason.LYRICS_UNAVAILABLE
    reconcileAutoHideVisibility()
}

private fun FloatingLyricsService.reevaluatePausedAutoHide() {
    if (!FloatingLyricsStyleStore.isAutoHideWhenPaused(this) ||
        !QuickFloatingStore.isDesiredVisible(this) ||
        currentMedia.isEmpty
    ) {
        cancelPendingPauseAutoHide()
        activeAutoHideReasons.remove(AutoHideReason.PAUSED)
        pauseAutoHideSuppressedByUser = false
        return
    }

    if (currentMedia.isPlaying) {
        cancelPendingPauseAutoHide()
        activeAutoHideReasons.remove(AutoHideReason.PAUSED)
        pauseAutoHideSuppressedByUser = false
        return
    }

    if (pauseAutoHideSuppressedByUser) {
        cancelPendingPauseAutoHide()
        activeAutoHideReasons.remove(AutoHideReason.PAUSED)
        return
    }

    if (AutoHideReason.PAUSED !in activeAutoHideReasons) {
        schedulePauseAutoHide()
    }
}

private fun FloatingLyricsService.reevaluateUnavailableLyricsAutoHide() {
    if (!FloatingLyricsStyleStore.isAutoHideWhenLyricsUnavailable(this) ||
        !QuickFloatingStore.isDesiredVisible(this) ||
        currentMedia.isEmpty
    ) {
        cancelPendingUnavailableLyricsAutoHide()
        activeAutoHideReasons.remove(AutoHideReason.LYRICS_UNAVAILABLE)
        lyricsUnavailableAutoHideSuppressedKey = null
        return
    }

    when (lyricsAvailability) {
        LyricsAvailability.UNKNOWN,
        LyricsAvailability.AVAILABLE -> {
            cancelPendingUnavailableLyricsAutoHide()
            activeAutoHideReasons.remove(AutoHideReason.LYRICS_UNAVAILABLE)
            lyricsUnavailableAutoHideSuppressedKey = null
        }

        LyricsAvailability.LOADING -> {
            cancelPendingUnavailableLyricsAutoHide()
        }

        LyricsAvailability.UNAVAILABLE -> {
            if (lyricsUnavailableAutoHideSuppressedKey == lyricsAvailabilityKey) {
                cancelPendingUnavailableLyricsAutoHide()
                activeAutoHideReasons.remove(AutoHideReason.LYRICS_UNAVAILABLE)
            } else if (AutoHideReason.LYRICS_UNAVAILABLE !in activeAutoHideReasons) {
                scheduleUnavailableLyricsAutoHide()
            }
        }
    }
}

private fun FloatingLyricsService.shouldAutoHidePausedPlayback(): Boolean {
    return FloatingLyricsStyleStore.isAutoHideWhenPaused(this) &&
        QuickFloatingStore.isDesiredVisible(this) &&
        !currentMedia.isEmpty &&
        !currentMedia.isPlaying &&
        !pauseAutoHideSuppressedByUser
}

private fun FloatingLyricsService.shouldAutoHideUnavailableLyrics(): Boolean {
    return FloatingLyricsStyleStore.isAutoHideWhenLyricsUnavailable(this) &&
        QuickFloatingStore.isDesiredVisible(this) &&
        !currentMedia.isEmpty &&
        lyricsAvailability == LyricsAvailability.UNAVAILABLE &&
        lyricsAvailabilityKey != null &&
        lyricsUnavailableAutoHideSuppressedKey != lyricsAvailabilityKey
}

private fun FloatingLyricsService.schedulePauseAutoHide() {
    if (pauseAutoHidePending) return
    pauseAutoHidePending = true
    syncHandler.postDelayed(pauseAutoHideRunnable, AUTO_HIDE_WHEN_PAUSED_DELAY_MS)
}

private fun FloatingLyricsService.scheduleUnavailableLyricsAutoHide() {
    if (unavailableLyricsAutoHidePending) return
    unavailableLyricsAutoHidePending = true
    syncHandler.postDelayed(
        unavailableLyricsAutoHideRunnable,
        AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS
    )
}

private fun FloatingLyricsService.reconcileAutoHideVisibility() {
    if (!QuickFloatingStore.isDesiredVisible(this)) return

    if (activeAutoHideReasons.isEmpty()) {
        restoreVisibleLyricsIfDesired()
    } else {
        hideLyricsForAutoHide()
    }
}

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

    if (activeAutoHideReasons.isNotEmpty()) {
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

    showLyrics(updateDesiredVisible = false)
}

internal fun FloatingLyricsService.cancelPendingPauseAutoHide() {
    pauseAutoHidePending = false
    syncHandler.removeCallbacks(pauseAutoHideRunnable)
}

internal fun FloatingLyricsService.cancelPendingUnavailableLyricsAutoHide() {
    unavailableLyricsAutoHidePending = false
    syncHandler.removeCallbacks(unavailableLyricsAutoHideRunnable)
}

internal fun FloatingLyricsService.cancelPendingAutoHide() {
    cancelPendingPauseAutoHide()
    cancelPendingUnavailableLyricsAutoHide()
}

internal fun FloatingLyricsService.clearAutoHideVisibilityState() {
    cancelPendingAutoHide()
    activeAutoHideReasons.clear()
    pauseAutoHideSuppressedByUser = false
    lyricsUnavailableAutoHideSuppressedKey = null
}

internal const val AUTO_HIDE_WHEN_PAUSED_DELAY_MS = 400L
internal const val AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS = 3_000L
