package com.andsi.airlyrics.ui.model

import androidx.annotation.StringRes

internal interface MainRuntimeHost {
    fun runOnAppIo(block: () -> Unit)
    fun runOnMainThread(block: () -> Unit)
    fun showMessage(@StringRes messageRes: Int)
}
