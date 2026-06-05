package com.andsi.airlyrics.app

import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

/**
 * Mutable UI and feature state owned by [MainGraph].
 *
 * MainActivity still exposes compatibility proxies for the existing View code,
 * but the graph now creates the state container during main screen wiring.
 */
internal interface MainFloatingState {
    var locked: Boolean
    var clickThrough: Boolean
    var quickFloatingVisible: Boolean
}

internal class MainActivityState : MainFloatingState {
    override var locked: Boolean = false
    override var clickThrough: Boolean = false
    var currentPage: Page = Page.MEDIA
    var settingsSubPage: SettingsSubPage = SettingsSubPage.HOME
    override var quickFloatingVisible: Boolean = false
    val pageScrollY: MutableMap<Page, Int> = mutableMapOf()
    var renderedPage: Page = Page.MEDIA
    var renderedSettingsSubPage: SettingsSubPage = SettingsSubPage.HOME
    var mediaRefreshState: RefreshState = RefreshState.IDLE
    var mediaPageRefreshScheduled: Boolean = false
    var currentLyricsLoadGeneration: Int = 0
    var recentLyricsLoadGeneration: Int = 0
    var pendingImportAsWordByWord: Boolean = false
}
