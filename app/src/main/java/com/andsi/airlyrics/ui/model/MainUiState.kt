package com.andsi.airlyrics.ui.model

import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

internal interface MainUiState {
    var currentPage: Page
    var settingsSubPage: SettingsSubPage
    var savedLyricsSearchOpen: Boolean
    var savedLyricsSearchQuery: String
    var locked: Boolean
    var clickThrough: Boolean
    var quickFloatingVisible: Boolean
    var overlayPermissionGranted: Boolean
    val pageScrollY: MutableMap<Page, Int>
    var renderedPage: Page
    var renderedSettingsSubPage: SettingsSubPage
    var mediaRefreshState: RefreshState
    var mediaPageRefreshScheduled: Boolean
}
