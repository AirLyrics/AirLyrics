package com.andsi.airlyrics.ui.model

import com.andsi.airlyrics.media.model.CurrentMediaInfo

internal interface MainRuntimeHost {
    fun getCurrentMediaInfo(): CurrentMediaInfo?
    fun runOnAppIo(block: () -> Unit)
    fun runOnMainThread(block: () -> Unit)
}
