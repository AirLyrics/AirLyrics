package com.andsi.airlyrics.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import com.andsi.airlyrics.app.controller.AppMediaController
import com.andsi.airlyrics.app.controller.FloatingController
import com.andsi.airlyrics.app.controller.FloatingLyricsReloader
import com.andsi.airlyrics.app.controller.FloatingNavFeedback
import com.andsi.airlyrics.app.controller.FloatingSourceNotifier
import com.andsi.airlyrics.app.controller.LyricsController
import com.andsi.airlyrics.app.controller.MainDialogHost
import com.andsi.airlyrics.app.controller.MainServiceStarter
import com.andsi.airlyrics.app.controller.MainTaskRunner
import com.andsi.airlyrics.app.controller.MediaPageRefreshScheduler
import com.andsi.airlyrics.app.controller.MediaSourceSelectionRenderer
import com.andsi.airlyrics.app.controller.OverlayPermissionRequester
import com.andsi.airlyrics.app.lifecycle.MainLaunchers
import com.andsi.airlyrics.app.lifecycle.MainReceivers
import com.andsi.airlyrics.app.render.MainHandRenderer
import com.andsi.airlyrics.app.render.UiInvalidator
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LanguageSettingsStore
import com.andsi.airlyrics.ui.components.showAirConfirmDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.tokens.AirUiTokens
import java.util.concurrent.Executors

/**
 * Transitional wiring graph for the main screen.
 *
 * It owns object creation and lifecycle orchestration while MainActivity keeps
 * the existing compatibility methods used by the current hand-written UI.
 * Business logic, page layout, animations, and the easter egg intentionally
 * stay in their current homes for this step.
 */
internal class MainGraph(
    private val activity: MainActivity
) {
    val state: MainActivityState = MainActivityState()

    val launchers: MainLaunchers = MainLaunchers(
        activity = activity,
        onLyricsFileSelected = activity::handleLyricsFileSelected,
        onLyricsDirectorySelected = activity::handleLyricsDirectorySelected,
        onNotificationPermissionResult = activity::handleNotificationPermissionResult
    )

    val uiHost: MainActivityUiHost by lazy { MainActivityUiHost(activity) }
    val mainHandRenderer: MainHandRenderer by lazy { MainHandRenderer(activity) }
    val uiInvalidator: UiInvalidator
        get() = mainHandRenderer
    val floatingController: FloatingController by lazy {
        FloatingController(
            context = activity,
            state = state,
            invalidator = uiInvalidator,
            serviceStarter = MainServiceStarter { intent -> activity.startLyricsServiceSafely(intent) },
            overlayPermissionRequester = OverlayPermissionRequester { activity.requestOverlayPermission() },
            navFeedback = FloatingNavFeedback { playFloatingNavToggleFeedback() }
        )
    }
    val appMediaController: AppMediaController by lazy {
        AppMediaController(
            context = activity,
            mediaPageRefreshScheduler = MediaPageRefreshScheduler { activity.scheduleMediaPageRefresh() },
            sourceSelectionRenderer = MediaSourceSelectionRenderer { packageName ->
                activity.updateMediaSourceSelectionVisuals(packageName)
            },
            floatingSourceNotifier = FloatingSourceNotifier { packageName ->
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
            mediaControllerProvider = appMediaController,
            floatingLyricsReloader = FloatingLyricsReloader { floatingController.reloadLyrics() }
        )
    }
    val uiActions: MainUiActions by lazy { activity.createMainUiActions() }

    val mediaRefreshHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val mainTaskRunner: MainTaskRunner = object : MainTaskRunner {
        override fun runOnAppIo(block: () -> Unit) {
            this@MainGraph.runOnAppIo(block)
        }

        override fun runOnMainThread(block: () -> Unit) {
            activity.runOnMainThread(block)
        }
    }

    private val mainDialogHost: MainDialogHost = object : MainDialogHost {
        override fun showConfirmDialog(
            title: String,
            message: String,
            positiveText: String,
            onPositive: () -> Unit
        ) {
            activity.showAirConfirmDialog(
                title = title,
                message = message,
                positiveText = positiveText,
                onPositive = onPositive
            )
        }

        override fun showInfoDialog(title: String, message: String) {
            activity.showAirInfoDialog(
                title = title,
                message = message
            )
        }
    }

    private val receivers: MainReceivers by lazy {
        MainReceivers(
            context = activity,
            onMediaChanged = appMediaController::handleMediaStatusBroadcast,
            onFloatingStateChanged = floatingController::handleWindowStateBroadcast
        )
    }

    private val appIoExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "airlyrics-app-io").apply { isDaemon = true }
        }
    }

    fun beforeSuperOnCreate() {
        LanguageSettingsStore.applyAppLocale(activity)
    }

    @Suppress("UNUSED_PARAMETER")
    fun onCreate(savedInstanceState: Bundle?) {
        state.locked = FloatingLyricsStyleStore.isLocked(activity)
        state.clickThrough = FloatingLyricsStyleStore.isClickThrough(activity)
        state.quickFloatingVisible = false
        activity.applySystemBarsTheme()
        registerBackNavigationCallback()
        activity.setContentView(mainHandRenderer.createMainView())
        receivers.register()
        appMediaController.autoSelectSourceOnceIfNeeded()
        floatingController.restoreVisibleWindowIfNeeded()
        uiInvalidator.refresh()
    }

    fun onResume() {
        state.locked = FloatingLyricsStyleStore.isLocked(activity)
        state.clickThrough = FloatingLyricsStyleStore.isClickThrough(activity)
        floatingController.restoreVisibleWindowIfNeeded()
        uiInvalidator.refresh()
    }

    fun runOnAppIo(block: () -> Unit) {
        appIoExecutor.execute(block)
    }

    fun onDestroy() {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        appIoExecutor.shutdownNow()
        receivers.unregister()
    }

    private fun registerBackNavigationCallback() {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activity.handleBackNavigation()) return
                isEnabled = false
                activity.onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    private fun playFloatingNavToggleFeedback() {
        val selectedTab = activity.tabViews[Page.FLOATING]
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
