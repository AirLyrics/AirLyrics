package com.andsi.airlyrics.feedback

import androidx.annotation.StringRes

internal interface AirFeedback {
    fun showMessage(@StringRes messageRes: Int)

    fun showMessage(message: CharSequence)

    fun showError(@StringRes messageRes: Int)

    fun showError(message: CharSequence)

    fun dismiss()
}
