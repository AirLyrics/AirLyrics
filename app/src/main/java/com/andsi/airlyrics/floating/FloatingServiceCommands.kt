package com.andsi.airlyrics.floating

import android.content.Intent
import android.provider.Settings
import com.andsi.airlyrics.R
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal fun FloatingLyricsService.handleCommand(intent: Intent?, startId: Int) {
    when (val command = FloatingServiceCommand.fromIntent(intent)) {
        null -> Unit
        FloatingServiceCommand.Restore -> restoreFromDesiredState()
        FloatingServiceCommand.Show -> {
            if (QuickFloatingStore.isDesiredVisible(this)) {
                syncSelectedMediaSource()
                showLyrics(manual = true)
            }
        }
        FloatingServiceCommand.Hide -> {
            if (!QuickFloatingStore.isDesiredVisible(this)) {
                hideLyrics()
                stopSelf(startId)
            }
        }
        FloatingServiceCommand.Lock -> setLocked(locked = true)
        FloatingServiceCommand.Unlock -> setLocked(locked = false)
        FloatingServiceCommand.ClickThroughOn -> setClickThrough(clickThrough = true)
        FloatingServiceCommand.ClickThroughOff -> setClickThrough(clickThrough = false)
        FloatingServiceCommand.ToggleVisibleFromNotification -> toggleVisibleFromNotification()
        FloatingServiceCommand.ToggleLockFromNotification -> toggleLockFromNotification()
        FloatingServiceCommand.ToggleClickThroughFromNotification -> toggleClickThroughFromNotification()
        FloatingServiceCommand.ToggleAdjustModeFromNotification -> toggleAdjustModeFromNotification()
        FloatingServiceCommand.ApplyAutoHideSettings -> applyAutoHideSettings()
        FloatingServiceCommand.ApplyDisplayScope -> applyDisplayScopeSetting()
        FloatingServiceCommand.RefreshText -> renderer.refresh()
        FloatingServiceCommand.ApplyStyle -> {
            val applied = windowController.applyStyle()
            if (applied) renderer.refresh()
            refreshQuickControls(if (applied) null else getString(R.string.ui_overlay_update_failed))
        }
        FloatingServiceCommand.ReloadLyrics -> reloadCurrentLyrics()
        is FloatingServiceCommand.ApplyLyricsOffset -> applyLyricsOffset(command.offsetMs)
        FloatingServiceCommand.SyncMediaSource -> syncSelectedMediaSource()
        is FloatingServiceCommand.ImportPlainLyrics -> importPlainLyrics(uri = command.uri, overwrite = command.overwrite)
    }
}

internal fun FloatingLyricsService.restoreFromDesiredState() {
    syncSelectedMediaSource()
    if (!QuickFloatingStore.isDesiredVisible(this)) {
        hideLyrics()
    } else if (windowController.isVisible && !Settings.canDrawOverlays(this)) {
        windowController.hide()
        refreshQuickControls()
    } else {
        reevaluateAutoHide()
    }
}

internal fun FloatingLyricsService.showLyrics(manual: Boolean): Boolean {
    if (manual) suppressAutoHideForManualShow()
    startDisplayScopeObservation()
    if (isDisplayScopeBlockingWindow()) {
        hideLyricsForDisplayScope()
        return false
    }
    val shown = runCatching { windowController.show() }.getOrElse {
        windowController.hide()
        false
    }
    if (!shown) {
        broadcastWindowVisibility(false)
    } else {
        startSelectedMediaObservation()
        startLyricsSync()
        if (currentMedia.isEmpty) {
            scheduleCurrentMediaRestore()
        }
        reevaluateAutoHide()
    }
    refreshQuickControls()
    return shown
}

internal fun FloatingLyricsService.hideLyrics() {
    clearAutoHideVisibilityState()
    autoHiddenForDisplayScope = false
    displayScopeBlockReason = null
    stopDisplayScopeObservation()
    val hidden = !isWindowControllerReady() ||
        runCatching { windowController.hide(notifyVisibilityChanged = false) }.getOrDefault(false)
    val stillVisible = isWindowControllerReady() && windowController.isVisible
    if (hidden || !stillVisible) {
        syncHandler.removeCallbacks(mediaRestoreRunnable)
        mediaRestoreAttempt = 0
        stopLyricsSync()
        stopSelectedMediaObservation()
        broadcastWindowVisibility(false)
    }
    refreshQuickControls()
}

internal fun FloatingLyricsService.setLocked(locked: Boolean): Boolean {
    val updated = windowController.setLocked(locked)
    refreshQuickControls(if (updated) null else getString(R.string.ui_overlay_update_failed))
    return updated
}

internal fun FloatingLyricsService.setClickThrough(clickThrough: Boolean): Boolean {
    val updated = windowController.setClickThrough(clickThrough)
    refreshQuickControls(if (updated) null else getString(R.string.ui_overlay_update_failed))
    return updated
}

internal fun FloatingLyricsService.toggleVisibleFromNotification() {
    val desiredVisible = !QuickFloatingStore.isDesiredVisible(this)
    QuickFloatingStore.setDesiredVisible(this, desiredVisible)
    if (desiredVisible) {
        if (!Settings.canDrawOverlays(this)) {
            refreshQuickControls(getString(R.string.ui_overlay_permission_required))
            showQuickFeedback(R.string.ui_enable_overlay_permission_first)
            return
        }
        syncSelectedMediaSource()
        showLyrics(manual = true)
    } else {
        hideLyrics()
    }
}

internal fun FloatingLyricsService.toggleLockFromNotification() {
    val nextLocked = !FloatingLyricsStyleStore.isLocked(this)
    setLocked(locked = nextLocked)
}

internal fun FloatingLyricsService.toggleClickThroughFromNotification() {
    val nextClickThrough = !FloatingLyricsStyleStore.isClickThrough(this)
    setClickThrough(clickThrough = nextClickThrough)
}

internal fun FloatingLyricsService.toggleAdjustModeFromNotification() {
    val currentlyEditing = !FloatingLyricsStyleStore.isLocked(this) &&
        !FloatingLyricsStyleStore.isClickThrough(this)
    val nextEditing = !currentlyEditing

    val lockedUpdated = windowController.setLocked(!nextEditing)
    val clickThroughUpdated = windowController.setClickThrough(!nextEditing)

    if (!lockedUpdated || !clickThroughUpdated) {
        refreshQuickControls(getString(R.string.ui_overlay_update_failed))
    } else {
        refreshQuickControls()
    }
}
