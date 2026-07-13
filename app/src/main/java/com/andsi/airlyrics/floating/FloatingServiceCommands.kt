package com.andsi.airlyrics.floating

import android.content.Intent
import com.andsi.airlyrics.R
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal fun FloatingLyricsService.handleCommand(intent: Intent?, startId: Int) {
    when (intent?.action) {
        null -> restoreFromDesiredState()
        BroadcastActions.SHOW -> showLyrics()
        BroadcastActions.HIDE -> {
            hideLyrics()
            stopSelf(startId)
        }
        BroadcastActions.LOCK -> setLocked(locked = true)
        BroadcastActions.UNLOCK -> setLocked(locked = false)
        BroadcastActions.CLICK_THROUGH_ON -> setClickThrough(clickThrough = true)
        BroadcastActions.CLICK_THROUGH_OFF -> setClickThrough(clickThrough = false)
        BroadcastActions.NOTIFICATION_TOGGLE_VISIBLE -> toggleVisibleFromNotification()
        BroadcastActions.NOTIFICATION_TOGGLE_LOCK -> toggleLockFromNotification()
        BroadcastActions.NOTIFICATION_TOGGLE_CLICK_THROUGH -> toggleClickThroughFromNotification()
        BroadcastActions.NOTIFICATION_TOGGLE_ADJUST_MODE -> toggleAdjustModeFromNotification()
        BroadcastActions.APPLY_AUTO_HIDE_WHEN_PAUSED -> applyAutoHideWhenPausedSetting()
        BroadcastActions.APPLY_STYLE -> {
            val applied = windowController.applyStyle()
            if (applied) renderer.refresh()
            refreshQuickControls(if (applied) null else getString(R.string.ui_overlay_update_failed))
        }
        BroadcastActions.RELOAD_LYRICS -> reloadCurrentLyrics()
        BroadcastActions.APPLY_LYRICS_OFFSET -> applyLyricsOffset(
            intent.getLongExtra(BroadcastActions.EXTRA_LYRICS_OFFSET_MS, 0L)
        )
        BroadcastActions.RELOAD_ONLINE_LYRICS -> reloadCurrentLyrics(
            bypassLocal = true,
            forceSaveOnline = true,
            ignoreAutoSearchSetting = true
        )
        BroadcastActions.SELECT_MEDIA_SOURCE -> selectMediaSource(
            intent.getStringExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE)
        )
        BroadcastActions.IMPORT_LYRICS -> intent.data?.let { uri ->
            importLyrics(uri = uri, overwrite = intent.getBooleanExtra(BroadcastActions.EXTRA_OVERWRITE_LYRICS, true))
        }
    }
}

internal fun FloatingLyricsService.restoreFromDesiredState() {
    selectedSourcePackage = MediaSourceStore.getSelectedPackage(this)
    if (QuickFloatingStore.isDesiredVisible(this)) {
        showLyrics(updateDesiredVisible = false)
    } else {
        stopLyricsSync()
        stopSelectedMediaObservation()
        broadcastWindowVisibility(false)
        refreshQuickControls()
    }
}

internal fun FloatingLyricsService.showLyrics(updateDesiredVisible: Boolean = true): Boolean {
    if (updateDesiredVisible) {
        QuickFloatingStore.setDesiredVisible(this, true)
        suppressAutoHideForCurrentPauseIfNeeded()
    }
    val shown = runCatching { windowController.show() }.getOrElse {
        windowController.hide()
        false
    }
    if (!shown) {
        broadcastWindowVisibility(false)
    } else {
        autoHiddenForPause = false
        startSelectedMediaObservation()
        startLyricsSync()
        if (currentMedia.isEmpty) {
            scheduleCurrentMediaRestore()
        }
        applyAutoHideWhenPaused()
    }
    refreshQuickControls()
    return shown
}

internal fun FloatingLyricsService.hideLyrics() {
    cancelPendingPauseAutoHide()
    autoHiddenForPause = false
    pauseAutoHideSuppressedByUser = false
    QuickFloatingStore.setDesiredVisible(this, false)
    val hidden = if (isWindowControllerReady()) {
        runCatching { windowController.hide() }.getOrDefault(false)
    } else {
        true
    }
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
    val nextVisible = !windowController.isVisible
    if (nextVisible) {
        if (!showLyrics()) {
            refreshQuickControls(getString(R.string.ui_overlay_permission_required))
            showQuickFeedback(getString(R.string.ui_enable_overlay_permission_first))
        }
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
