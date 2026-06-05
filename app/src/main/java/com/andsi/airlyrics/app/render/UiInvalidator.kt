package com.andsi.airlyrics.app.render

/**
 * Small boundary for UI refresh requests from controllers.
 *
 * Controllers ask for invalidation here instead of reaching into the activity
 * or concrete renderer.
 */
internal interface UiInvalidator {
    fun refresh(
        animateContent: Boolean = true,
        animateTabs: Boolean = true
    )

    fun refreshFloatingState()

    fun recreateForThemeChange()
}
