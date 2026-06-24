package com.andsi.airlyrics.app.state

import com.andsi.airlyrics.app.MainGraph
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import com.andsi.airlyrics.ui.model.MainUiState
import com.andsi.airlyrics.ui.model.RefreshState
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

/** Mutable UI and feature state owned by [MainGraph]. */
internal interface MainFloatingState {
    var locked: Boolean
    var clickThrough: Boolean
    var quickFloatingVisible: Boolean
}

internal class MainActivityState : MainFloatingState, MainUiState {
    override var locked: Boolean = false
    override var clickThrough: Boolean = false
    override var currentPage: Page = Page.MEDIA
    override var settingsSubPage: SettingsSubPage = SettingsSubPage.HOME
    override var quickFloatingVisible: Boolean = false
    override val pageScrollY: MutableMap<Page, Int> = mutableMapOf()
    override var renderedPage: Page = Page.MEDIA
    override var renderedSettingsSubPage: SettingsSubPage = SettingsSubPage.HOME
    override var mediaRefreshState: RefreshState = RefreshState.IDLE
    override var mediaPageRefreshScheduled: Boolean = false
    override var currentLyricsLoadGeneration: Int = 0
    override var recentLyricsLoadGeneration: Int = 0
    var pendingImportAsWordByWord: Boolean = false
    var pendingImportMedia: CurrentMediaInfo? = null
}
