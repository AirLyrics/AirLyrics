package com.andsi.airlyrics.floating

import android.app.NotificationManager
import android.content.Intent
import android.widget.Toast
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore

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
        locked = FloatingLyricsStyleStore.isLocked(this),
        clickThrough = FloatingLyricsStyleStore.isClickThrough(this),
        feedback = feedback
    )
}

internal fun FloatingLyricsService.showQuickFeedback(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

internal fun FloatingLyricsService.broadcastWindowVisibility(visible: Boolean) {
    val intent = Intent(BroadcastActions.WINDOW_VISIBILITY_CHANGED).apply {
        setPackage(packageName)
        putExtra(BroadcastActions.EXTRA_WINDOW_VISIBLE, visible)
        putExtra(BroadcastActions.EXTRA_LOCKED, FloatingLyricsStyleStore.isLocked(this@broadcastWindowVisibility))
        putExtra(BroadcastActions.EXTRA_CLICK_THROUGH, FloatingLyricsStyleStore.isClickThrough(this@broadcastWindowVisibility))
    }
    sendBroadcast(intent)
}

internal fun FloatingLyricsService.broadcastQuickControlState() {
    val actualVisible = isWindowControllerReady() && windowController.isVisible
    val intent = Intent(BroadcastActions.QUICK_CONTROL_CHANGED).apply {
        setPackage(packageName)
        putExtra(BroadcastActions.EXTRA_WINDOW_VISIBLE, actualVisible)
        putExtra(BroadcastActions.EXTRA_LOCKED, FloatingLyricsStyleStore.isLocked(this@broadcastQuickControlState))
        putExtra(BroadcastActions.EXTRA_CLICK_THROUGH, FloatingLyricsStyleStore.isClickThrough(this@broadcastQuickControlState))
    }
    sendBroadcast(intent)
}
