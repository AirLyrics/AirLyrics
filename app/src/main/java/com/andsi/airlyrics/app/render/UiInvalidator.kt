package com.andsi.airlyrics.app.render

/**
 * Small boundary for UI refresh requests from controllers.
 *
 * Controllers should ask for invalidation here instead of reaching into
 * MainActivity rendering functions directly. During the transition this is
 * still backed by MainActivity's existing hand-written View renderer.
 */
internal interface UiInvalidator {
    fun refresh(
        animateContent: Boolean = true,
        animateTabs: Boolean = true
    )

    fun refreshFloatingState()

    fun recreateForThemeChange()
}
