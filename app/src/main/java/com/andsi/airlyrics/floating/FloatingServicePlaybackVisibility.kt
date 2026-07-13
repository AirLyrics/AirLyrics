package com.andsi.airlyrics.floating

import com.andsi.airlyrics.R
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal fun FloatingLyricsService.applyAutoHideWhenPausedSetting() {
    if (!QuickFloatingStore.isDesiredVisible(this)) {
        cancelPendingPauseAutoHide()
        autoHiddenForPause = false
        pauseAutoHideSuppressedByUser = false
        refreshQuickControls()
        return
    }

    if (FloatingLyricsStyleStore.isAutoHideWhenPaused(this)) {
        if (!autoHiddenForPause && isWindowControllerReady() && !windowController.isVisible) {
            showLyrics(updateDesiredVisible = false)
        } else {
            applyAutoHideWhenPaused()
        }
    } else {
        cancelPendingPauseAutoHide()
        pauseAutoHideSuppressedByUser = false
        restoreVisibleLyricsIfDesired()
    }
}

internal fun FloatingLyricsService.applyAutoHideWhenPaused() {
    if (!FloatingLyricsStyleStore.isAutoHideWhenPaused(this)) {
        cancelPendingPauseAutoHide()
        return
    }

    if (!QuickFloatingStore.isDesiredVisible(this)) {
        cancelPendingPauseAutoHide()
        autoHiddenForPause = false
        pauseAutoHideSuppressedByUser = false
        return
    }

    if (currentMedia.isEmpty) return

    if (currentMedia.isPlaying) {
        cancelPendingPauseAutoHide()
        pauseAutoHideSuppressedByUser = false
        restoreAutoHiddenLyrics()
    } else if (pauseAutoHideSuppressedByUser) {
        cancelPendingPauseAutoHide()
        refreshQuickControls()
    } else {
        scheduleHideLyricsForPausedPlayback()
    }
}

internal fun FloatingLyricsService.suppressAutoHideForCurrentPauseIfNeeded() {
    if (!FloatingLyricsStyleStore.isAutoHideWhenPaused(this)) return
    if (currentMedia.isPlaying) return

    cancelPendingPauseAutoHide()
    pauseAutoHideSuppressedByUser = true
}

internal fun FloatingLyricsService.applyScheduledAutoHideWhenPaused() {
    syncHandler.removeCallbacks(pauseAutoHideRunnable)
    if (!FloatingLyricsStyleStore.isAutoHideWhenPaused(this)) return

    if (!QuickFloatingStore.isDesiredVisible(this)) {
        autoHiddenForPause = false
        pauseAutoHideSuppressedByUser = false
        return
    }

    if (currentMedia.isEmpty || currentMedia.isPlaying || pauseAutoHideSuppressedByUser) {
        refreshQuickControls()
        return
    }

    hideLyricsForPausedPlayback()
}

private fun FloatingLyricsService.scheduleHideLyricsForPausedPlayback() {
    if (autoHiddenForPause && isWindowControllerReady() && !windowController.isVisible) {
        scheduleSelectedCurrentMediaInfoRefresh()
        refreshQuickControls()
        return
    }

    syncHandler.removeCallbacks(pauseAutoHideRunnable)
    syncHandler.postDelayed(pauseAutoHideRunnable, AUTO_HIDE_WHEN_PAUSED_DELAY_MS)
}

private fun FloatingLyricsService.hideLyricsForPausedPlayback() {
    if (!isWindowControllerReady()) return

    if (autoHiddenForPause && !windowController.isVisible) {
        scheduleSelectedCurrentMediaInfoRefresh()
        refreshQuickControls()
        return
    }

    val hidden = runCatching {
        windowController.hide(notifyVisibilityChanged = false)
    }.getOrDefault(false)
    val stillVisible = windowController.isVisible

    if (!hidden && stillVisible) {
        refreshQuickControls(getString(R.string.ui_overlay_update_failed))
        return
    }

    autoHiddenForPause = true
    stopLyricsSync()
    scheduleSelectedCurrentMediaInfoRefresh()
    refreshQuickControls()
}

private fun FloatingLyricsService.restoreAutoHiddenLyrics() {
    if (!autoHiddenForPause) return
    restoreVisibleLyricsIfDesired()
}

private fun FloatingLyricsService.restoreVisibleLyricsIfDesired() {
    cancelPendingPauseAutoHide()
    autoHiddenForPause = false
    if (!QuickFloatingStore.isDesiredVisible(this)) {
        refreshQuickControls()
        return
    }

    if (isWindowControllerReady() && windowController.isVisible) {
        startSelectedMediaObservation()
        startLyricsSync()
        refreshQuickControls()
        return
    }

    showLyrics(updateDesiredVisible = false)
}

internal fun FloatingLyricsService.cancelPendingPauseAutoHide() {
    syncHandler.removeCallbacks(pauseAutoHideRunnable)
}

private const val AUTO_HIDE_WHEN_PAUSED_DELAY_MS = 400L
