package com.andsi.airlyrics.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.controller.FloatingController
import com.andsi.airlyrics.app.controller.LyricsController
import com.andsi.airlyrics.app.controller.MediaSourceController
import com.andsi.airlyrics.app.contracts.MainDialogHost
import com.andsi.airlyrics.app.contracts.MainTaskRunner
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
import com.andsi.airlyrics.app.workflow.MainLyricsWorkflow
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import com.andsi.airlyrics.ui.components.showAirConfirmDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.refresh.PageRebuildReason
import com.andsi.airlyrics.design.tokens.AirUiTokens
import java.util.concurrent.Executors

/** Main-screen composition root and lifecycle orchestrator. */
internal class MainGraph(
    internal val activity: MainActivity
) {
    val state: MainActivityState = MainActivityState()
    val viewRefs = MainActivityViewRefs()

    val launchers: MainLaunchers = MainLaunchers(
        activity = activity,
        onLyricsFileSelected = { uri -> lyricsWorkflow.handleLyricsFileSelected(uri) },
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
            invalidator = uiInvalidator,
            taskRunner = mainTaskRunner,
            dialogHost = mainDialogHost,
            mediaControllerProvider = mediaSourceController,
            floatingLyricsReloader = { floatingController.reloadLyrics() }
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
            onPositive: () -> Unit
        ) {
            uiHost.showAirConfirmDialog(
                title = title,
                message = message,
                positiveText = positiveText,
                onPositive = onPositive
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
            onFloatingStateChanged = floatingController::handleWindowStateBroadcast
        )
    }

    private val appIoExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "airlyrics-app-io").apply { isDaemon = true }
        }
    }
    private var skipFirstResumeAfterCreate = false

    fun beforeSuperOnCreate() {
        LanguageSettingsStore.applyAppLocale(activity)
    }

    @Suppress("UNUSED_PARAMETER")
    fun onCreate(savedInstanceState: Bundle?) {
        state.locked = FloatingLyricsStyleStore.isLocked(activity)
        state.clickThrough = FloatingLyricsStyleStore.isClickThrough(activity)
        state.quickFloatingVisible = false
        state.overlayPermissionGranted = Settings.canDrawOverlays(activity)
        uiHost.applySystemBarsTheme()
        registerBackNavigationCallback()
        activity.setContentView(mainHandRenderer.createMainView())
        receivers.register()
        mediaSourceController.autoSelectSourceOnceIfNeeded()
        floatingController.restoreVisibleWindowIfNeeded()
        uiInvalidator.rebuildCurrentPage(PageRebuildReason.INITIAL_RENDER)
        skipFirstResumeAfterCreate = true
    }

    fun onResume() {
        if (skipFirstResumeAfterCreate) {
            skipFirstResumeAfterCreate = false
            return
        }

        state.locked = FloatingLyricsStyleStore.isLocked(activity)
        state.clickThrough = FloatingLyricsStyleStore.isClickThrough(activity)
        state.overlayPermissionGranted = Settings.canDrawOverlays(activity)
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
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        appIoExecutor.shutdownNow()
        receivers.unregister()
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

        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        uiInvalidator.rebuildCurrentPage(PageRebuildReason.PERMISSION_CHANGED)
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
