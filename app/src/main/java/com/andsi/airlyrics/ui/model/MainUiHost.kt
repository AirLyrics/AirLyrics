package com.andsi.airlyrics.ui.model

import androidx.lifecycle.lifecycleScope
import com.andsi.airlyrics.ui.state.forgetAuxiliaryDialog
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
    private val currentLoad = com.andsi.airlyrics.ui.async.LatestUiTaskRunner()
    private val recentLoad = com.andsi.airlyrics.ui.async.LatestUiTaskRunner()
    private val savedLoad = com.andsi.airlyrics.ui.async.LatestUiTaskRunner()
    open fun loadCurrentLyrics(force: Boolean, deliver: (CurrentLyricsUiState) -> Unit) =
        currentLoad.submit(this, { currentLyricsState() }, deliver)
    open fun loadRecentLyrics(force: Boolean, deliver: (RecentLyricsUiState) -> Unit) =
        recentLoad.submit(this, { recentLyricsState(8) }, deliver)
    open fun loadSavedLyrics(force: Boolean, deliver: (SavedLyricsUiState) -> Unit) =
        savedLoad.submit(this, { savedLyricsState() }, deliver)

    val readerDrafts by lazy { com.andsi.airlyrics.app.interaction.LyricsDraftStore.forSession(applicationContext, interactions, "reader-drafts") }
    val auxiliaryDialogs = mutableMapOf<String, android.app.Dialog>()
    var auxiliaryRestoreJob: kotlinx.coroutines.Job? = null
    var activeConfirmationId: String? = null
    open fun executeConfirmedOperation(request: android.os.Bundle) = Unit
    var windowLayout = com.andsi.airlyrics.ui.layout.WindowLayoutSpec(0f, 0f, 1f)
    open val interactions by lazy { com.andsi.airlyrics.ui.state.MainInteractionState() }
    open val editorSession by lazy {
        com.andsi.airlyrics.app.interaction.LyricsEditorSession(applicationContext, interactions, activity.lifecycleScope)
    }
    val windowGeneration = kotlinx.coroutines.flow.MutableStateFlow(0L)
    var editorObserverInstalled = false
    var editorChangeCallback: ((LocalLyricsUiChange) -> Unit)? = null
    val interactionUi = com.andsi.airlyrics.ui.state.InteractionUiRegistry()
    private var panelControlIndex = 0
    fun beginPanelControls() { panelControlIndex = 0 }
    fun nextPanelControlKey(kind: String): String? = interactions.panel?.let { "panel.control.${it.name}.$kind.${panelControlIndex++}" }

    init {
        activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                interactionUi.capture()
                if (editorObserverInstalled) editorSession.flush()
            }
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                auxiliaryRestoreJob?.cancel()
                interactionUi.destroy()
                auxiliaryDialogs.clear()
                editorChangeCallback = null
                if (activity.isFinishing) {
                    interactions.read("aux.order")?.getStringArrayList("items")?.toList()?.forEach { forgetAuxiliaryDialog(it) }
                    if (editorObserverInstalled) editorSession.cancel()
                }
            }
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
