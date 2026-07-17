package com.andsi.airlyrics.app.state

import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.ui.model.MainUiState
import com.andsi.airlyrics.ui.model.RefreshState
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

/** Mutable main-screen state owned by the app graph. */
internal class MainActivityState : MainFloatingState, MainUiState {
    override var locked: Boolean = false
    override var clickThrough: Boolean = false
    override var currentPage: Page = Page.MEDIA
    override var settingsSubPage: SettingsSubPage = SettingsSubPage.HOME
    override var quickFloatingVisible: Boolean = false
    override var overlayPermissionGranted: Boolean = false
    override val pageScrollY: MutableMap<Page, Int> = mutableMapOf()
    override var renderedPage: Page = Page.MEDIA
    override var renderedSettingsSubPage: SettingsSubPage = SettingsSubPage.HOME
    override var mediaRefreshState: RefreshState = RefreshState.IDLE
    override var mediaPageRefreshScheduled: Boolean = false
    var pendingImportAsWordByWord: Boolean = false
    var pendingImportMedia: CurrentMediaInfo? = null
}
