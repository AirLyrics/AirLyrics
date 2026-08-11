package com.andsi.airlyrics.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.contracts.MainDialogHost
import com.andsi.airlyrics.app.contracts.MainTaskRunner
import com.andsi.airlyrics.app.controller.FloatingController
import com.andsi.airlyrics.app.controller.LyricsController
import com.andsi.airlyrics.app.controller.MediaSourceController
import com.andsi.airlyrics.app.host.MainActivityUiHost
import com.andsi.airlyrics.app.host.createMainUiActions
import com.andsi.airlyrics.app.host.updateMediaSourceSelectionVisualsImpl
import com.andsi.airlyrics.app.lifecycle.MainLaunchers
import com.andsi.airlyrics.app.lifecycle.MainReceivers
import com.andsi.airlyrics.app.platform.PermissionHelper
import com.andsi.airlyrics.app.render.MainActivityViewRefs
import com.andsi.airlyrics.app.render.MainHandRenderer
import com.andsi.airlyrics.app.render.UiInvalidator
import com.andsi.airlyrics.app.state.MainActivityState
import com.andsi.airlyrics.app.state.toBundle
import com.andsi.airlyrics.app.state.toPendingLyricsImport
import com.andsi.airlyrics.app.state.toPendingLyricsOverwrite
import com.andsi.airlyrics.app.workflow.MainLyricsWorkflow
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import com.andsi.airlyrics.lyrics.BroadcastLyricsChangedPublisher
import com.andsi.airlyrics.settings.AirToast
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.ui.components.showAirConfirmDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.refresh.PageRebuildReason
import java.util.concurrent.Executors

/** Main-screen composition root and lifecycle orchestrator. */
internal class MainGraph(
    internal val activity: MainActivity
) {
    private companion object {
        const val KEY_CURRENT_PAGE = "airlyrics.current_page"
        const val KEY_SETTINGS_SUB_PAGE = "airlyrics.settings_sub_page"
        const val KEY_PENDING_LYRICS_IMPORT = "airlyrics.pending_lyrics_import"
        const val KEY_PENDING_LYRICS_OVERWRITE = "airlyrics.pending_lyrics_overwrite"
    }

    val state: MainActivityState = MainActivityState()
    val viewRefs = MainActivityViewRefs()

    val launchers: MainLaunchers = MainLaunchers(
        activity = activity,
        onLyricsFileResult = { uri -> lyricsWorkflow.handleLyricsFileResult(uri) },
        onFloatingFontFileResult = ::handleFloatingFontFileResult,
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
            state = state,
            invalidator = uiInvalidator,
            serviceStarter = { intent -> startLyricsServiceSafely(intent) },
            overlayPermissionRequester = { requestOverlayPermission() },
            navFeedback = { playFloatingNavToggleFeedback() }
        )
    }
    val mediaSourceController: MediaSourceController by lazy {
        MediaSourceController(
            context = activity,
            mediaPageRefreshScheduler = { scheduleMediaPageRefresh() },
            sourceSelectionRenderer = { packageName ->
                uiHost.updateMediaSourceSelectionVisualsImpl(packageName)
            },
            floatingSourceNotifier = { packageName ->
                floatingController.notifySourceChangedIfVisible(packageName)
            }
        )
    }
    val lyricsController: LyricsController by lazy {
        LyricsController(
            context = activity,
            taskRunner = mainTaskRunner,
            dialogHost = mainDialogHost,
            mediaControllerProvider = mediaSourceController,
            overwriteConfirmationRequester = { request ->
                lyricsWorkflow.requestOverwriteConfirmation(request)
            },
            lyricsChangedPublisher = BroadcastLyricsChangedPublisher(activity)
        )
    }
    val uiActions: MainUiActions by lazy { createMainUiActions() }
    val lyricsWorkflow: MainLyricsWorkflow by lazy { MainLyricsWorkflow(this) }

    val mediaRefreshHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val mainTaskRunner: MainTaskRunner = object : MainTaskRunner {
        override fun runOnAppIo(block: () -> Unit) {
            this@MainGraph.runOnAppIo(block)
        }

        override fun runOnMainThread(block: () -> Unit) {
            this@MainGraph.runOnMainThread(block)
        }
    }

    private val mainDialogHost: MainDialogHost = object : MainDialogHost {
        override fun showConfirmDialog(
            title: String,
            message: String,
            positiveText: String,
            onPositive: () -> Unit,
            onNegative: () -> Unit
        ) {
            uiHost.showAirConfirmDialog(
                title = title,
                message = message,
                positiveText = positiveText,
                onPositive = onPositive,
                onNegative = onNegative
            )
        }

        override fun showInfoDialog(title: String, message: String) {
            uiHost.showAirInfoDialog(
                title = title,
                message = message
            )
        }
    }

    private val receivers: MainReceivers by lazy {
        MainReceivers(
            context = activity,
            onMediaChanged = mediaSourceController::handleMediaStatusBroadcast,
            onFloatingStateChanged = floatingController::handleWindowStateBroadcast,
            onLyricsChanged = { handleLyricsChanged() }
        )
    }

    private val appIoExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "airlyrics-app-io").apply { isDaemon = true }
        }
    }
    private var skipFirstResumeAfterCreate = false
    private var destroyed = false

    fun beforeSuperOnCreate() {
        LanguageSettingsStore.applyAppLocale(activity)
    }

    fun onCreate(savedInstanceState: Bundle?) {
        state.locked = FloatingLyricsStyleStore.isLocked(activity)
        state.clickThrough = FloatingLyricsStyleStore.isClickThrough(activity)
        state.quickFloatingVisible = false
        state.overlayPermissionGranted = Settings.canDrawOverlays(activity)
        restoreNavigationState(savedInstanceState)
        restorePendingLyricsState(savedInstanceState)
        uiHost.applySystemBarsTheme()
        registerBackNavigationCallback()
        activity.setContentView(mainHandRenderer.createMainView())
        uiHost.applySystemBarsTheme()
        receivers.register()
        mediaSourceController.autoSelectSourceOnceIfNeeded()
        floatingController.restoreVisibleWindowIfNeeded()
        uiInvalidator.rebuildCurrentPage(PageRebuildReason.INITIAL_RENDER)
        lyricsWorkflow.restorePendingOverwriteConfirmation()
        skipFirstResumeAfterCreate = true
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_CURRENT_PAGE, state.currentPage.name)
        outState.putString(KEY_SETTINGS_SUB_PAGE, state.settingsSubPage.name)
        outState.remove(KEY_PENDING_LYRICS_IMPORT)
        state.pendingLyricsImport?.let { request ->
            outState.putBundle(KEY_PENDING_LYRICS_IMPORT, request.toBundle())
        }
        outState.remove(KEY_PENDING_LYRICS_OVERWRITE)
        state.pendingLyricsOverwrite?.let { request ->
            outState.putBundle(KEY_PENDING_LYRICS_OVERWRITE, request.toBundle())
        }
    }

    fun onResume() {
        if (skipFirstResumeAfterCreate) {
            skipFirstResumeAfterCreate = false
            return
        }

        state.locked = FloatingLyricsStyleStore.isLocked(activity)
        state.clickThrough = FloatingLyricsStyleStore.isClickThrough(activity)
        state.overlayPermissionGranted = Settings.canDrawOverlays(activity)
        uiHost.applySystemBarsTheme()
        floatingController.restoreVisibleWindowIfNeeded()
        uiInvalidator.rebuildCurrentPage(PageRebuildReason.PERMISSION_CHANGED)
    }

    fun runOnAppIo(block: () -> Unit) {
        appIoExecutor.execute(block)
    }

    fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            activity.runOnUiThread {
                if (!activity.isDestroyed) block()
            }
        }
    }

    fun onDestroy() {
        destroyed = true
        state.pendingLyricsOverwrite = null
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        appIoExecutor.shutdown()
        receivers.unregister()
    }

    private fun handleLyricsChanged() {
        if (destroyed || activity.isDestroyed) return
        uiInvalidator.refreshLyricsSettingsContent()
    }

    private fun restoreNavigationState(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        state.currentPage = savedInstanceState.getString(KEY_CURRENT_PAGE)
            ?.let { runCatching { Page.valueOf(it) }.getOrNull() }
            ?: state.currentPage
        state.settingsSubPage = savedInstanceState.getString(KEY_SETTINGS_SUB_PAGE)
            ?.let { runCatching { SettingsSubPage.valueOf(it) }.getOrNull() }
            ?: state.settingsSubPage
    }

    private fun restorePendingLyricsState(savedInstanceState: Bundle?) {
        state.pendingLyricsImport = savedInstanceState
            ?.getBundle(KEY_PENDING_LYRICS_IMPORT)
            ?.toPendingLyricsImport()
        state.pendingLyricsOverwrite = savedInstanceState
            ?.getBundle(KEY_PENDING_LYRICS_OVERWRITE)
            ?.toPendingLyricsOverwrite()
    }

    fun handleBackNavigation(): Boolean {
        if (state.currentPage == Page.FLOATING && uiHost.floatingPanelBackHandler?.invoke() == true) {
            return true
        }

        if (state.currentPage == Page.SETTINGS && state.settingsSubPage != SettingsSubPage.HOME) {
            state.settingsSubPage = SettingsSubPage.HOME
            uiInvalidator.rebuildCurrentPage(PageRebuildReason.BACK_NAVIGATION)
            return true
        }

        return false
    }

    fun handleNotificationPermissionResult(granted: Boolean) {
        val message = if (granted) {
            activity.getString(R.string.ui_notification_permission_enabled)
        } else {
            activity.getString(R.string.ui_notif_permission_off_warning)
        }

        AirToast.showLong(activity, message)
        uiInvalidator.rebuildCurrentPage(PageRebuildReason.PERMISSION_CHANGED)
    }

    private fun handleFloatingFontFileResult(uri: android.net.Uri?) {
        uri ?: return
        AirToast.showShort(activity, R.string.ui_importing_font)
        runOnAppIo {
            val result = FloatingLyricsFontStore.importFont(activity, uri)
            runOnMainThread {
                if (destroyed || activity.isDestroyed) return@runOnMainThread
                val message = when (result) {
                    is FloatingLyricsFontStore.ImportResult.Success -> {
                        FloatingLyricsStyleStore.setFontFamily(
                            activity,
                            FloatingLyricsFontFamily.CUSTOM
                        )
                        floatingController.notifyStyleChanged()
                        uiInvalidator.rebuildCurrentPage(
                            reason = PageRebuildReason.FLOATING_STRUCTURE_CHANGED,
                            animateContent = false,
                            animateTabs = false
                        )
                        activity.getString(R.string.ui_font_import_success, result.displayName)
                    }

                    FloatingLyricsFontStore.ImportResult.UnsupportedFormat ->
                        activity.getString(R.string.ui_font_format_unsupported)
                    FloatingLyricsFontStore.ImportResult.TooLarge ->
                        activity.getString(R.string.ui_font_file_too_large)
                    FloatingLyricsFontStore.ImportResult.InvalidFont ->
                        activity.getString(R.string.ui_invalid_font_file)
                    FloatingLyricsFontStore.ImportResult.ReadFailed ->
                        activity.getString(R.string.ui_font_import_failed)
                }
                AirToast.showLong(activity, message)
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

    fun scheduleMediaPageRefresh() {
        if (state.currentPage != Page.MEDIA) return
        if (state.mediaPageRefreshScheduled) return

        state.mediaPageRefreshScheduled = true
        mediaRefreshHandler.postDelayed({
            state.mediaPageRefreshScheduled = false
            if (state.currentPage == Page.MEDIA) {
                uiInvalidator.rebuildCurrentPage(
                    reason = PageRebuildReason.MEDIA_CONTENT_CHANGED,
                    animateContent = false,
                    animateTabs = false
                )
            }
        }, 120L)
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
