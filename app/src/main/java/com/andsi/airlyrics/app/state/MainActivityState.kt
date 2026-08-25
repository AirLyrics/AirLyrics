package com.andsi.airlyrics.app.state

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
    override var savedLyricsSearchOpen: Boolean = false
    override var savedLyricsSearchQuery: String = ""
    override var quickFloatingVisible: Boolean = false
    override var overlayPermissionGranted: Boolean = false
    override val pageScrollY: MutableMap<Page, Int> = mutableMapOf()
    override var renderedPage: Page = Page.MEDIA
    override var renderedSettingsSubPage: SettingsSubPage = SettingsSubPage.HOME
    override var mediaRefreshState: RefreshState = RefreshState.IDLE
    override var mediaPageRefreshScheduled: Boolean = false
    var pendingLyricsImport: PendingLyricsImport? = null
    var pendingLyricsOverwrite: PendingLyricsOverwrite? = null

    fun consumePendingLyricsImport(): PendingLyricsImport? {
        return pendingLyricsImport.also { pendingLyricsImport = null }
    }

    @Synchronized
    fun consumePendingLyricsOverwrite(
        expected: PendingLyricsOverwrite
    ): PendingLyricsOverwrite? {
        val current = pendingLyricsOverwrite
        if (current != expected) return null
        pendingLyricsOverwrite = null
        return current
    }

    @Synchronized
    fun clearPendingLyricsOverwrite(expected: PendingLyricsOverwrite): Boolean {
        if (pendingLyricsOverwrite != expected) return false
        pendingLyricsOverwrite = null
        return true
    }
}
