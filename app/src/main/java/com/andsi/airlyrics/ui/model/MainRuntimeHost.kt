package com.andsi.airlyrics.ui.model

import androidx.annotation.StringRes

internal interface MainRuntimeHost {
    fun runOnAppIo(block: () -> Unit)
    fun runOnMainThread(block: () -> Unit)

    fun currentUiGeneration(): Long = 0L

    fun runOnStartedUi(expectedGeneration: Long, block: () -> Unit) {
        runOnMainThread(block)
    }

    fun showMessage(@StringRes messageRes: Int)
}
