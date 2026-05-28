package com.andsi.airlyrics.app

import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

/**
 * Mutable UI and feature state owned by [MainActivity].
 *
 * This keeps Activity-level state in one small container, while preserving the
 * current Activity APIs used by the existing View code.
 */
internal class MainActivityState {
    var locked: Boolean = false
    var clickThrough: Boolean = false
    var currentPage: Page = Page.MEDIA
    var settingsSubPage: SettingsSubPage = SettingsSubPage.HOME
    var quickFloatingVisible: Boolean = false
    val pageScrollY: MutableMap<Page, Int> = mutableMapOf()
    var renderedPage: Page = Page.MEDIA
    var renderedSettingsSubPage: SettingsSubPage = SettingsSubPage.HOME
    var mediaRefreshState: RefreshState = RefreshState.IDLE
    var mediaPageRefreshScheduled: Boolean = false
}
