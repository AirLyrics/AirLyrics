package com.andsi.airlyrics.ui.model

import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

internal interface MainUiState {
    var currentPage: Page
    var settingsSubPage: SettingsSubPage
    var locked: Boolean
    var clickThrough: Boolean
    var quickFloatingVisible: Boolean
    var currentLyricsLoadGeneration: Int
    var recentLyricsLoadGeneration: Int
}
