package com.andsi.airlyrics.app.contracts

import android.content.Intent
import android.media.session.MediaController

internal fun interface MainServiceStarter {
    fun startLyricsServiceSafely(intent: Intent): Boolean
}

internal fun interface FloatingSourceNotifier {
    fun notifySourceChangedIfVisible(packageName: String?)
}

internal interface MediaControllerProvider {
    fun getActiveControllers(): List<MediaController>
}
