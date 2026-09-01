package com.andsi.airlyrics.app.contracts

import android.content.Intent
import android.media.session.MediaController

/** Small seams used while controllers are detached from the concrete screen host. */

internal fun interface MainServiceStarter {
    fun startLyricsServiceSafely(intent: Intent): Boolean
}

internal fun interface FloatingSourceNotifier {
    fun notifySourceChangedIfVisible(packageName: String?)
}

internal interface MediaControllerProvider {
    fun getActiveControllers(): List<MediaController>
}
