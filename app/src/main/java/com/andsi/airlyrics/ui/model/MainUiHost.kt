package com.andsi.airlyrics.ui.model

import android.content.ContextWrapper
import androidx.appcompat.app.AppCompatActivity
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

/**
 * UI-facing host for the handwritten main screen.
 *
 * Pages receive this small boundary instead of the concrete activity, keeping
 * layout code stable while the app layer continues to own wiring and services.
 */
internal abstract class MainUiHost(
    val activity: AppCompatActivity,
    private val uiStateProvider: () -> MainUiState
) : ContextWrapper(activity),
    MainChromeHost,
    MainRuntimeHost,
    MediaUiHost,
    OptionControlsHost,
    FloatingUiHost,
    SettingsUiHost {
    abstract val actions: MainUiActions
    val uiActions: MainUiActions
        get() = actions
    val uiState: MainUiState
        get() = uiStateProvider()

    val currentPage: Page
        get() = uiState.currentPage
    val settingsSubPage: SettingsSubPage
        get() = uiState.settingsSubPage
    val savedLyricsSearchOpen: Boolean
        get() = uiState.savedLyricsSearchOpen
    val savedLyricsSearchQuery: String
        get() = uiState.savedLyricsSearchQuery
    val quickFloatingVisible: Boolean
        get() = uiState.quickFloatingVisible
    val overlayPermissionGranted: Boolean
        get() = uiState.overlayPermissionGranted
    val mediaRefreshState: RefreshState
        get() = uiState.mediaRefreshState
}
