package com.andsi.airlyrics.app.contracts

import android.content.Intent
import android.media.session.MediaController

/** Small seams used while controllers are detached from the concrete screen host. */

internal fun interface MainServiceStarter {
    fun startLyricsServiceSafely(intent: Intent): Boolean
}

internal fun interface OverlayPermissionRequester {
    fun requestOverlayPermission()
}

internal fun interface FloatingNavFeedback {
    fun playFloatingNavToggleFeedback()
}

internal fun interface MediaPageRefreshScheduler {
    fun scheduleMediaPageRefresh()
}

internal fun interface MediaSourceSelectionRenderer {
    fun updateMediaSourceSelection(packageName: String)
}

internal fun interface FloatingSourceNotifier {
    fun notifySourceChangedIfVisible(packageName: String?)
}

internal interface MediaControllerProvider {
    fun getActiveControllers(): List<MediaController>
}

internal interface MainTaskRunner {
    fun runOnAppIo(block: () -> Unit)
    fun runOnMainThread(block: () -> Unit)

    fun currentUiGeneration(): Long = 0L

    fun runOnStartedUi(expectedGeneration: Long, block: () -> Unit) {
        runOnMainThread(block)
    }
}

internal interface MainDialogHost {
    fun showConfirmDialog(
        title: String,
        message: String,
        positiveText: String,
        onPositive: () -> Unit,
        onNegative: () -> Unit
    )

    fun showInfoDialog(
        title: String,
        message: String
    )
}
