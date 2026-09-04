package com.andsi.airlyrics.floating

import android.app.NotificationManager
import androidx.annotation.StringRes
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal fun FloatingLyricsService.refreshQuickControls(feedback: String? = null) {
    val notification = FloatingServiceNotification.create(this, currentQuickControlState(feedback))
    val manager = getSystemService(NotificationManager::class.java)
    manager.notify(FloatingServiceNotification.NOTIFICATION_ID, notification)
    broadcastQuickControlState()
}

internal fun FloatingLyricsService.currentQuickControlState(
    feedback: String? = null
): FloatingServiceNotification.QuickControlState {
    return FloatingServiceNotification.QuickControlState(
        visible = isWindowControllerReady() && windowController.isVisible,
        desiredVisible = QuickFloatingStore.isDesiredVisible(this),
        locked = FloatingLyricsStyleStore.isLocked(this),
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this),
        displayScopeBlockReason = displayScopeBlockReason,
        feedback = feedback
    )
}

internal fun FloatingLyricsService.showQuickFeedback(@StringRes messageRes: Int) {
    feedback.showMessage(messageRes)
}

internal fun FloatingLyricsService.broadcastWindowVisibility(visible: Boolean) {
    val state = FloatingWindowStateBroadcast.State(
        visible = visible,
        locked = FloatingLyricsStyleStore.isLocked(this),
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this)
    )
    FloatingWindowRuntimeState.update(state)
    val intent = FloatingWindowStateBroadcast.windowVisibilityChangedIntent(this, state)
    sendBroadcast(intent)
}

internal fun FloatingLyricsService.broadcastQuickControlState() {
    val state = FloatingWindowStateBroadcast.State(
        visible = isWindowControllerReady() && windowController.isVisible,
        locked = FloatingLyricsStyleStore.isLocked(this),
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this)
    )
    FloatingWindowRuntimeState.update(state)
    val intent = FloatingWindowStateBroadcast.quickControlChangedIntent(this, state)
    sendBroadcast(intent)
}
