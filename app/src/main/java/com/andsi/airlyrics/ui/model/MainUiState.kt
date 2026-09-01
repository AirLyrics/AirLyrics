package com.andsi.airlyrics.ui.model

import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

internal interface MainUiState {
    val currentPage: Page
    val settingsSubPage: SettingsSubPage
    val savedLyricsSearchOpen: Boolean
    val savedLyricsSearchQuery: String
    val locked: Boolean
    val clickThrough: Boolean
    val quickFloatingVisible: Boolean
    val overlayPermissionGranted: Boolean
    val postNotificationsGranted: Boolean
    val notificationListenerGranted: Boolean
    val mediaRefreshState: RefreshState
}
