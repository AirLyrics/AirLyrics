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
    val uiState: MainUiState
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

    var currentPage: Page
        get() = uiState.currentPage
        set(value) { uiState.currentPage = value }
    var settingsSubPage: SettingsSubPage
        get() = uiState.settingsSubPage
        set(value) { uiState.settingsSubPage = value }
    var quickFloatingVisible: Boolean
        get() = uiState.quickFloatingVisible
        set(value) { uiState.quickFloatingVisible = value }
    var overlayPermissionGranted: Boolean
        get() = uiState.overlayPermissionGranted
        set(value) { uiState.overlayPermissionGranted = value }
    var currentLyricsLoadGeneration: Int
        get() = uiState.currentLyricsLoadGeneration
        set(value) { uiState.currentLyricsLoadGeneration = value }
    var recentLyricsLoadGeneration: Int
        get() = uiState.recentLyricsLoadGeneration
        set(value) { uiState.recentLyricsLoadGeneration = value }
    var mediaRefreshState: RefreshState
        get() = uiState.mediaRefreshState
        set(value) { uiState.mediaRefreshState = value }
}
