package com.andsi.airlyrics.app.render

/**
 * Small boundary for UI refresh requests from controllers.
 *
 * Controllers ask for invalidation here instead of reaching into the activity
 * or concrete renderer.
 */
internal interface UiInvalidator {
    /** Recreates the current page tree. Use only when the visible structure changes. */
    fun rebuildCurrentPage(
        animateContent: Boolean = true,
        animateTabs: Boolean = true
    )

    /** Updates bottom navigation text, selection, and highlight without touching page content. */
    fun refreshTabs(animate: Boolean = true)

    /** Updates floating-window visibility chrome: tabs plus current Floating page controls. */
    fun refreshFloatingChrome()

    /** Updates lock/click-through labels inside the current Floating page, if it is mounted. */
    fun refreshFloatingControls()

    /** Refreshes mounted lyrics-setting cards without replacing the current page tree. */
    fun refreshLyricsSettingsContent()

    fun recreateMainView()
}
