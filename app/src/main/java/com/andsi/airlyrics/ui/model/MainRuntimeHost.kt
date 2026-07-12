package com.andsi.airlyrics.ui.model

internal interface MainRuntimeHost {
    fun runOnAppIo(block: () -> Unit)
    fun runOnMainThread(block: () -> Unit)
}
