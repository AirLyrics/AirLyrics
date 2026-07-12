package com.andsi.airlyrics.ui.model

import android.media.session.MediaController
import android.view.View

internal interface MediaUiHost {
    fun getActiveMediaControllers(): List<MediaController>
    fun mediaPageState(): MediaPageState
    fun getAppName(packageName: String): String
    fun getPlaybackStateText(state: Int?): String

    fun refreshMediaButton(): View
    fun mediaSourceCard(controller: MediaController, selected: Boolean): View
}
