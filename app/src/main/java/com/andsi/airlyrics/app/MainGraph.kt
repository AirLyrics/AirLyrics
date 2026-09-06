package com.andsi.airlyrics.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.LifecycleDestroyedException
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.controller.FloatingController
import com.andsi.airlyrics.app.controller.FloatingVisibilityOutcome
import com.andsi.airlyrics.app.controller.MediaSourceController
import com.andsi.airlyrics.app.host.MainActivityUiHost
import com.andsi.airlyrics.app.host.createMainUiActions
import com.andsi.airlyrics.app.lifecycle.MainLaunchers
import com.andsi.airlyrics.app.lifecycle.MainReceivers
import com.andsi.airlyrics.app.platform.PermissionHelper
import com.andsi.airlyrics.app.render.MainActivityViewRefs
import com.andsi.airlyrics.app.render.MainHandRenderer
import com.andsi.airlyrics.app.render.UiInvalidator
import com.andsi.airlyrics.app.viewmodel.MainScreenState
import com.andsi.airlyrics.app.viewmodel.MainUiEffect
import com.andsi.airlyrics.app.viewmodel.MainViewModel
import com.andsi.airlyrics.app.workflow.MainLyricsWorkflow
import com.andsi.airlyrics.app.workflow.MainDisplayScopeWorkflow
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.feedback.AirFeedback
import com.andsi.airlyrics.feedback.ToastAirFeedback
import com.andsi.airlyrics.floating.FloatingWindowRuntimeState
import com.andsi.airlyrics.floating.FloatingWindowStateBroadcast
import com.andsi.airlyrics.i18n.localizedLyricsLookupMessage
import com.andsi.airlyrics.lyrics.importer.plainLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.importer.wordByWordLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.settings.store.AppSettingsStore
import com.andsi.airlyrics.settings.store.DisplayScopeStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.feedback.SnackbarAirFeedback
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.model.RefreshState
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.refresh.PageRebuildReason
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-screen composition root and lifecycle orchestrator. */
internal class MainGraph(
    internal val activity: MainActivity,
    internal val viewModel: MainViewModel
) {
    private data class ForegroundChanges(
        val overlayBecameGranted: Boolean = false,
        val usageAccessChanged: Boolean = false
    )

    private data class UiRefreshPlan(
        val rebuildReason: PageRebuildReason? = null,
        val refreshTabs: Boolean = false,
        val refreshFloatingChrome: Boolean = false,
        val refreshFloatingControls: Boolean = false,
        val refreshFloatingDisplayScope: Boolean = false,
        val refreshLyricsContent: Boolean = false
    )

    val state: MainScreenState
        get() = viewModel.uiState.value
    val viewRefs = MainActivityViewRefs()
    private val canShowFeedback: () -> Boolean = {
        !AppSettingsStore.areStatusPopupsMuted(activity)
    }
    private val toastFeedback: AirFeedback = ToastAirFeedback(
        context = activity,
        canShow = canShowFeedback
    )
    val feedback: AirFeedback = SnackbarAirFeedback(
        activity = activity,
        anchorProvider = { viewRefs.feedbackAnchor },
        fallback = toastFeedback,
        canShow = canShowFeedback,
        paletteProvider = {
            AirLyricsTheme.palette(
                isDark = ThemeSettingsStore.isDark(activity),
                accent = ThemeSettingsStore.getAccent(activity)
            )
        }
    )
    val crossWindowFeedback: AirFeedback
        get() = toastFeedback

    val launchers: MainLaunchers = MainLaunchers(
        activity = activity,
        onLyricsFileResult = viewModel::handleLyricsFileResult,
        onFloatingFontFileResult = viewModel::importFloatingFont,
        onLyricsDirectorySelected = { uri -> lyricsWorkflow.handleLyricsDirectorySelected(uri) },
        onNotificationPermissionResult = ::handleNotificationPermissionResult
    )

    val uiHost: MainActivityUiHost by lazy { MainActivityUiHost(this) }
    val mainHandRenderer: MainHandRenderer by lazy { MainHandRenderer(this) }
    val uiInvalidator: UiInvalidator
        get() = mainHandRenderer
    val floatingController: FloatingController by lazy {
        FloatingController(
            context = activity,
            state = viewModel,
            serviceStarter = { intent -> startLyricsServiceSafely(intent) }
        )
    }
    val mediaSourceController: MediaSourceController by lazy {
        MediaSourceController(
            context = activity,
            floatingSourceNotifier = { packageName ->
                floatingController.notifySourceChangedIfVisible(packageName)
            }
        )
    }
    val uiActions: MainUiActions by lazy { createMainUiActions() }
    val lyricsWorkflow: MainLyricsWorkflow by lazy { MainLyricsWorkflow(this) }
    val displayScopeWorkflow: MainDisplayScopeWorkflow by lazy { MainDisplayScopeWorkflow(this) }

    /** Owns only the refresh-button feedback sequence exposed through MainUiHost. */
    val mediaRefreshHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val receivers: MainReceivers by lazy {
        MainReceivers(
            context = activity,
            onMediaChanged = { intent ->
                if (mediaSourceController.isMediaStatusBroadcast(intent)) {
                    scheduleMediaPageRefresh()
                }
            },
            onFloatingStateChanged = ::handleFloatingStateChanged,
            onLyricsChanged = { handleLyricsChanged() }
        )
    }

    private var lastRenderedState: MainScreenState = state
    private var uiGeneration = 0L
    private var uiStarted = false
    private var destroyed = false
    private var stateObserverJob: Job? = null
    private var effectObserverJob: Job? = null
    private var overlayPermissionHintShown = false
    private val pendingSavedLyricsDeletionCallbacks = mutableMapOf<Long, (Boolean) -> Unit>()

    fun onCreate() {
        mediaSourceController.autoSelectSourceOnceIfNeeded()
        viewModel.refreshForegroundState()
        uiHost.applySystemBarsTheme()
        registerBackNavigationCallback()
        activity.setContentView(mainHandRenderer.createMainView())
        uiHost.applySystemBarsTheme()
        uiInvalidator.rebuildCurrentPage()
        lastRenderedState = state
        lyricsWorkflow.restorePendingOverwriteConfirmation()
    }

    fun onStart() {
        if (destroyed || uiStarted) return

        uiStarted = true
        observeViewModelState()
        observeViewModelEffects()
        receivers.register()
        syncForegroundState()
        restoreDesiredFloatingWindow()
    }

    fun onResume() {
        uiHost.applySystemBarsTheme()
        val changes = syncForegroundState()
        if (changes.overlayBecameGranted) {
            restoreDesiredFloatingWindow()
        }
        if (changes.usageAccessChanged) {
            floatingController.notifyDisplayScopeChanged()
        }
    }

    fun onStop() {
        if (!uiStarted) return

        uiStarted = false
        stateObserverJob?.cancel()
        stateObserverJob = null
        effectObserverJob?.cancel()
        effectObserverJob = null
        cancelPendingUiRefreshes()
        receivers.unregister()
    }

    fun runOnAppIo(block: () -> Unit) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            block()
        }
    }

    fun runOnMainThread(block: () -> Unit) {
        if (destroyed || activity.isDestroyed) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            activity.runOnUiThread {
                if (!destroyed && !activity.isDestroyed) block()
            }
        }
    }

    fun currentUiGeneration(): Long = uiGeneration

    fun runOnStartedUi(expectedGeneration: Long, block: () -> Unit) {
        activity.lifecycleScope.launch {
            try {
                activity.withStarted {
                    if (uiGeneration == expectedGeneration && canRenderUi()) {
                        block()
                    }
                }
            } catch (_: LifecycleDestroyedException) {
                // The UI owner went away before this result could be delivered.
            }
        }
    }

    internal fun beginPageRebuild() {
        uiGeneration += 1L
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        if (state.mediaRefreshState == RefreshState.REFRESHING) {
            viewModel.setMediaRefreshState(RefreshState.IDLE)
        }
    }

    fun onDestroy() {
        destroyed = true
        uiStarted = false
        uiGeneration += 1L
        feedback.dismiss()
        stateObserverJob?.cancel()
        stateObserverJob = null
        effectObserverJob?.cancel()
        effectObserverJob = null
        cancelPendingUiRefreshes()
        pendingSavedLyricsDeletionCallbacks.clear()
        receivers.unregister()
    }

    private fun handleLyricsChanged() {
        viewModel.notifyLyricsChanged(LyricsStorage.currentRevision())
    }

    private fun handleFloatingStateChanged(intent: Intent) {
        if (!canRenderUi()) return
        val windowState = FloatingWindowStateBroadcast.readState(intent) ?: return
        FloatingWindowRuntimeState.update(windowState)
        syncForegroundState()
    }

    fun handleBackNavigation(): Boolean {
        if (state.currentPage == Page.FLOATING && uiHost.floatingPanelBackHandler?.invoke() == true) {
            return true
        }

        return viewModel.navigateBack()
    }

    fun handleNotificationPermissionResult(granted: Boolean) {
        val messageRes = if (granted) {
            R.string.ui_notification_permission_enabled
        } else {
            R.string.ui_notif_permission_off_warning
        }

        if (granted) {
            feedback.showMessage(messageRes)
        } else {
            feedback.showError(messageRes)
        }
        syncForegroundState()
    }

    fun toggleFloatingFromNav() {
        playFloatingNavToggleFeedback()
        handleFloatingVisibilityOutcome(floatingController.toggleLyrics())
    }

    fun showFloatingLyrics() {
        handleFloatingVisibilityOutcome(floatingController.showLyrics())
    }

    fun hideFloatingLyrics() {
        handleFloatingVisibilityOutcome(floatingController.hideLyrics())
    }

    fun toggleFloatingLock() {
        if (!floatingController.toggleLock()) {
            feedback.showError(R.string.ui_overlay_update_failed)
        }
    }

    fun toggleFloatingClickThrough() {
        if (!floatingController.toggleClickThrough()) {
            feedback.showError(R.string.ui_overlay_update_failed)
        }
    }

    fun toggleDisplayScope(): Boolean {
        val enabled = !DisplayScopeStore.isEnabled(activity)
        if (enabled && (!PermissionHelper.hasUsageStatsAccess(activity) ||
                DisplayScopeStore.selectedPackages(activity).isEmpty())) {
            return false
        }
        DisplayScopeStore.setEnabled(activity, enabled)
        floatingController.notifyDisplayScopeChanged()
        uiInvalidator.refreshFloatingDisplayScope()
        return enabled
    }

    fun onDisplayScopeSelectionChanged() {
        if (DisplayScopeStore.selectedPackages(activity).isEmpty()) {
            DisplayScopeStore.setEnabled(activity, false)
        }
        floatingController.notifyDisplayScopeChanged()
        uiInvalidator.refreshFloatingDisplayScope()
    }

    private fun handleFloatingVisibilityOutcome(outcome: FloatingVisibilityOutcome) {
        when (outcome) {
            FloatingVisibilityOutcome.SUCCESS -> Unit
            FloatingVisibilityOutcome.PERMISSION_REQUIRED -> {
                if (!overlayPermissionHintShown) {
                    crossWindowFeedback.showError(R.string.ui_enable_overlay_permission_first)
                    overlayPermissionHintShown = true
                }
                viewModel.requestOverlayPermission()
            }
            FloatingVisibilityOutcome.COMMAND_FAILED -> {
                feedback.showError(R.string.ui_overlay_update_failed)
                viewModel.notifyFloatingStructureChanged()
            }
        }
    }

    fun requestOverlayPermission() {
        PermissionHelper.requestOverlayPermission(activity)
    }

    fun requestNotificationPermissionIfNeeded() {
        PermissionHelper.requestNotificationPermissionIfNeeded(
            activity = activity,
            requestPermission = launchers::requestNotificationPermission
        )
    }

    fun copyLyricsDirectory() {
        val path = viewModel.lyricsDirectoryPath()
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(activity.getString(R.string.ui_lyrics_save_folder), path)
        )
        feedback.showMessage(R.string.ui_lyrics_save_folder_copied)
    }

    fun deleteSavedLyrics(
        item: LyricsStorage.LocalLyricsItem,
        onCompleted: (Boolean) -> Unit
    ) {
        val requestId = viewModel.deleteSavedLyricsItem(item)
        pendingSavedLyricsDeletionCallbacks[requestId] = onCompleted
    }

    fun scheduleMediaPageRefresh() {
        viewModel.scheduleMediaRefresh()
    }

    private fun syncForegroundState(): ForegroundChanges {
        if (!canRenderUi()) return ForegroundChanges()
        val usageAccessWasGranted = state.usageStatsGranted
        return ForegroundChanges(
            overlayBecameGranted = viewModel.refreshForegroundState(),
            usageAccessChanged = usageAccessWasGranted != state.usageStatsGranted
        )
    }

    private fun buildRefreshPlan(
        previous: MainScreenState,
        latest: MainScreenState
    ): UiRefreshPlan {
        if (previous.currentPage != latest.currentPage) {
            return UiRefreshPlan(rebuildReason = PageRebuildReason.PAGE_NAVIGATION)
        }
        if (previous.settingsSubPage != latest.settingsSubPage) {
            return UiRefreshPlan(rebuildReason = PageRebuildReason.SETTINGS_NAVIGATION)
        }

        val overlayPermissionChanged =
            previous.foreground.permissions.overlayGranted !=
                latest.foreground.permissions.overlayGranted
        val postNotificationsChanged =
            previous.foreground.permissions.postNotificationsGranted !=
                latest.foreground.permissions.postNotificationsGranted
        val notificationListenerChanged =
            previous.foreground.permissions.notificationListenerGranted !=
                latest.foreground.permissions.notificationListenerGranted
        val usageStatsChanged =
            previous.foreground.permissions.usageStatsGranted !=
                latest.foreground.permissions.usageStatsGranted
        val mediaChanged = previous.foreground.media != latest.foreground.media
        val lyricsChanged = previous.foreground.lyricsRevision != latest.foreground.lyricsRevision ||
            previous.lyricsChangeSequence != latest.lyricsChangeSequence
        val lyricsDirectoryChanged =
            previous.lyricsDirectoryRevision != latest.lyricsDirectoryRevision
        val floatingVisibilityChanged =
            previous.foreground.floating.visible != latest.foreground.floating.visible
        val floatingDesiredVisibilityChanged =
            previous.foreground.floating.desiredVisible != latest.foreground.floating.desiredVisible
        val floatingControlsChanged =
            previous.foreground.floating.locked != latest.foreground.floating.locked ||
                previous.foreground.floating.clickThrough != latest.foreground.floating.clickThrough

        val permissionRebuildRequired = when (latest.currentPage) {
            Page.MEDIA -> notificationListenerChanged
            Page.FLOATING -> overlayPermissionChanged
            Page.SETTINGS -> latest.settingsSubPage == SettingsSubPage.HOME ||
                latest.settingsSubPage == SettingsSubPage.SYSTEM
        } && (overlayPermissionChanged || postNotificationsChanged ||
            notificationListenerChanged || usageStatsChanged)

        val rebuildReason = when {
            permissionRebuildRequired -> PageRebuildReason.PERMISSION_CHANGED
            latest.currentPage == Page.SETTINGS &&
                latest.settingsSubPage == SettingsSubPage.LYRICS &&
                lyricsDirectoryChanged -> PageRebuildReason.LYRICS_DIRECTORY_CHANGED
            latest.currentPage == Page.FLOATING &&
                previous.floatingStructureRevision != latest.floatingStructureRevision ->
                PageRebuildReason.FLOATING_STRUCTURE_CHANGED
            latest.currentPage == Page.MEDIA && mediaChanged ->
                PageRebuildReason.MEDIA_CONTENT_CHANGED
            else -> null
        }
        if (rebuildReason != null) {
            return UiRefreshPlan(rebuildReason = rebuildReason)
        }

        val lyricsPageMounted = latest.currentPage == Page.SETTINGS &&
            (latest.settingsSubPage == SettingsSubPage.LYRICS ||
                latest.settingsSubPage == SettingsSubPage.SAVED_LYRICS)
        return UiRefreshPlan(
            refreshTabs = overlayPermissionChanged &&
                !floatingVisibilityChanged && !floatingDesiredVisibilityChanged,
            refreshFloatingChrome = floatingVisibilityChanged || floatingDesiredVisibilityChanged,
            refreshFloatingControls = floatingControlsChanged,
            refreshFloatingDisplayScope =
                latest.currentPage == Page.FLOATING && usageStatsChanged,
            refreshLyricsContent = lyricsPageMounted &&
                (lyricsChanged ||
                    (mediaChanged && latest.settingsSubPage == SettingsSubPage.LYRICS))
        )
    }

    private fun observeViewModelState() {
        if (stateObserverJob != null) return
        stateObserverJob = activity.lifecycleScope.launch {
            viewModel.uiState.collect { latest ->
                if (!canRenderUi()) return@collect
                val previous = lastRenderedState
                if (previous == latest) return@collect
                lastRenderedState = latest
                if (previous.pendingLyricsOverwrite != latest.pendingLyricsOverwrite) {
                    latest.pendingLyricsOverwrite?.let(
                        lyricsWorkflow::showOverwriteConfirmation
                    )
                }
                applyRefreshPlan(buildRefreshPlan(previous, latest))
            }
        }
    }

    private fun observeViewModelEffects() {
        if (effectObserverJob != null) return
        effectObserverJob = activity.lifecycleScope.launch {
            viewModel.uiEffects.collect(::handleUiEffect)
        }
    }

    private fun handleUiEffect(effect: MainUiEffect) {
        if (!canRenderUi()) return
        when (effect) {
            MainUiEffect.RequestOverlayPermission -> requestOverlayPermission()
            MainUiEffect.RequestNotificationPermission -> requestNotificationPermissionIfNeeded()
            MainUiEffect.OpenNotificationListenerSettings ->
                activity.startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
            MainUiEffect.OpenUsageAccessSettings ->
                PermissionHelper.openUsageAccessSettings(activity)
            MainUiEffect.SelectLyricsDirectory -> launchers.selectLyricsDirectory()
            MainUiEffect.SelectLyricsFile -> launchers.selectLyricsFile()
            MainUiEffect.SelectFloatingFontFile -> launchers.selectFloatingFontFile()
            is MainUiEffect.ShowMessage -> {
                if (effect.error) {
                    feedback.showError(effect.messageRes)
                } else {
                    feedback.showMessage(effect.messageRes)
                }
            }
            is MainUiEffect.ShowImportFormatError -> {
                val message = if (effect.wordByWord) {
                    activity.wordByWordLyricsFormatErrorMessage(effect.invalidLineNumbers)
                } else {
                    activity.plainLyricsFormatErrorMessage(effect.invalidLineNumbers)
                }
                uiHost.showAirInfoDialog(
                    title = activity.getString(R.string.ui_invalid_format),
                    message = message
                )
            }
            is MainUiEffect.ShowLyricsLookupError ->
                feedback.showError(activity.localizedLyricsLookupMessage(effect.error))
            is MainUiEffect.ShowLyricsImportChoices ->
                lyricsWorkflow.showImportLyricsDialog(
                    target = effect.target,
                    plainImportEnabled = effect.plainImportEnabled,
                    wordByWordImportEnabled = effect.wordByWordImportEnabled
                )
            is MainUiEffect.SavedLyricsDeletionCompleted -> {
                pendingSavedLyricsDeletionCallbacks
                    .remove(effect.requestId)
                    ?.invoke(effect.deleted)
            }
            is MainUiEffect.FloatingFontImported -> {
                floatingController.notifyStyleChanged()
                feedback.showMessage(
                    activity.getString(R.string.ui_font_import_success, effect.displayName)
                )
            }
        }
    }

    private fun applyRefreshPlan(plan: UiRefreshPlan) {
        if (plan.rebuildReason != null) {
            uiInvalidator.rebuildCurrentPage(
                animateContent = false,
                animateTabs = false
            )
            return
        }

        if (plan.refreshFloatingChrome) {
            uiInvalidator.refreshFloatingChrome()
        } else if (plan.refreshTabs) {
            uiInvalidator.refreshTabs(animate = false)
        }
        if (plan.refreshFloatingControls && !plan.refreshFloatingChrome) {
            uiInvalidator.refreshFloatingControls()
        }
        if (plan.refreshFloatingDisplayScope) {
            uiInvalidator.refreshFloatingDisplayScope()
        }
        if (plan.refreshLyricsContent) {
            uiInvalidator.refreshLyricsSettingsContent()
        }
    }

    private fun restoreDesiredFloatingWindow() {
        if (QuickFloatingStore.isDesiredVisible(activity) && state.overlayPermissionGranted) {
            floatingController.restoreVisibleWindowIfNeeded()
        }
    }

    private fun cancelPendingUiRefreshes() {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        viewModel.cancelMediaRefresh()
        viewModel.setMediaRefreshState(RefreshState.IDLE)
    }

    private fun canRenderUi(): Boolean {
        return uiStarted && !destroyed && !activity.isDestroyed
    }

    fun startLyricsServiceSafely(intent: Intent): Boolean {
        return runCatching {
            activity.startForegroundService(intent)
        }.isSuccess
    }

    private fun registerBackNavigationCallback() {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (handleBackNavigation()) return
                isEnabled = false
                activity.onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    private fun playFloatingNavToggleFeedback() {
        val selectedTab = uiHost.tabViews[Page.FLOATING]
        selectedTab?.animate()
            ?.scaleX(AirUiTokens.Layout.TabTextSwapScale)
            ?.scaleY(AirUiTokens.Layout.TabTextSwapScale)
            ?.setDuration(AirUiTokens.Layout.NavTapDownMs)
            ?.withEndAction {
                selectedTab.animate()
                    .scaleX(AirUiTokens.Layout.TabQuickScale)
                    .scaleY(AirUiTokens.Layout.TabQuickScale)
                    .setDuration(AirUiTokens.Layout.NavTapUpMs)
                    .setInterpolator(OvershootInterpolator(AirUiTokens.Layout.NavTapOvershoot))
                    .start()
            }
            ?.start()
    }

}
