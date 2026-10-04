package com.andsi.airlyrics.ui.model

import androidx.lifecycle.lifecycleScope
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
    open val interactions by lazy { com.andsi.airlyrics.ui.state.MainInteractionState() }
    open val editorSession by lazy {
        com.andsi.airlyrics.app.interaction.LyricsEditorSession(applicationContext, interactions, activity.lifecycleScope)
    }
    var editorObserverInstalled = false
    var editorChangeCallback: ((LocalLyricsUiChange) -> Unit)? = null
    val interactionUi = com.andsi.airlyrics.ui.state.InteractionUiRegistry()
    private var panelControlIndex = 0
    fun beginPanelControls() { panelControlIndex = 0 }
    fun nextPanelControlKey(kind: String): String? = interactions.panel?.let { "panel.control.${it.name}.$kind.${panelControlIndex++}" }

    init {
        activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { interactionUi.capture(); if (editorObserverInstalled) editorSession.flush() }
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { interactionUi.destroy(); editorChangeCallback = null; if (activity.isFinishing && editorObserverInstalled) editorSession.cancel() }
        })
    }

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
    val quickFloatingDesiredVisible: Boolean
        get() = uiState.quickFloatingDesiredVisible
    val overlayPermissionGranted: Boolean
        get() = uiState.overlayPermissionGranted
    val mediaRefreshState: RefreshState
        get() = uiState.mediaRefreshState
}
