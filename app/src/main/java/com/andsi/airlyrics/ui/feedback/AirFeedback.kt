package com.andsi.airlyrics.ui.feedback

import androidx.annotation.StringRes

/** Delivers brief, non-blocking feedback on the surface that owns the interaction. */
internal interface AirFeedback {
    fun showMessage(@StringRes messageRes: Int)

    fun showMessage(message: CharSequence)

    fun showError(@StringRes messageRes: Int)

    fun showError(message: CharSequence)

    /** Returns whether an actionable surface was shown. Actions never fall back to a Toast. */
    fun showAction(
        message: CharSequence,
        actionLabel: CharSequence,
        onAction: () -> Unit
    ): Boolean

    fun dismiss()
}
