package com.andsi.airlyrics.app.render

import com.andsi.airlyrics.ui.navigation.SettingsNavigation
import com.andsi.airlyrics.ui.refresh.PageRebuildReason

internal interface UiInvalidator {
    fun rebuildCurrentPage(
        animateContent: Boolean = true,
        animateTabs: Boolean = true,
        reason: PageRebuildReason? = null,
        settingsNavigation: SettingsNavigation? = null
    )

    fun refreshMediaContent()

    fun refreshTabs(animate: Boolean = true)

    fun refreshFloatingChrome(animateQuickStatus: Boolean = false)

    fun refreshFloatingControls()

    fun refreshFloatingDisplayScope()

    fun refreshLyricsSettingsContent()

    fun recreateMainView()
}
