package com.andsi.airlyrics.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import com.andsi.airlyrics.app.controller.AppMediaController
import com.andsi.airlyrics.app.controller.FloatingController
import com.andsi.airlyrics.app.controller.LyricsController
import com.andsi.airlyrics.app.lifecycle.MainLaunchers
import com.andsi.airlyrics.app.render.MainHandRenderer
import com.andsi.airlyrics.app.render.UiInvalidator
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LanguageSettingsStore
import com.andsi.airlyrics.ui.model.MainUiActions
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

    val mainHandRenderer: MainHandRenderer by lazy { MainHandRenderer(activity) }
    val uiInvalidator: UiInvalidator
        get() = mainHandRenderer
    val floatingController: FloatingController by lazy {
        FloatingController(activity, uiInvalidator)
    }
    val appMediaController: AppMediaController by lazy { AppMediaController(activity) }
    val lyricsController: LyricsController by lazy {
        LyricsController(activity, uiInvalidator)
    }
    val uiActions: MainUiActions by lazy { activity.createMainUiActions() }

    val mediaRefreshHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

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
        activity.locked = FloatingLyricsStyleStore.isLocked(activity)
        activity.clickThrough = FloatingLyricsStyleStore.isClickThrough(activity)
        activity.quickFloatingVisible = false
        activity.applySystemBarsTheme()
        registerBackNavigationCallback()
        activity.setContentView(mainHandRenderer.createMainView())
        activity.registerFloatingWindowStateReceiver()
        activity.registerMediaStatusReceiver()
        activity.autoSelectMediaSourceOnceIfNeeded()
        activity.restoreFloatingLyricsIfNeeded()
        uiInvalidator.refresh()
    }

    fun onResume() {
        activity.locked = FloatingLyricsStyleStore.isLocked(activity)
        activity.clickThrough = FloatingLyricsStyleStore.isClickThrough(activity)
        activity.restoreFloatingLyricsIfNeeded()
        uiInvalidator.refresh()
    }

    fun runOnAppIo(block: () -> Unit) {
        appIoExecutor.execute(block)
    }

    fun onDestroy() {
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        appIoExecutor.shutdownNow()
        runCatching { activity.unregisterReceiver(activity.floatingWindowStateReceiver) }
        runCatching { activity.unregisterReceiver(activity.mediaStatusReceiver) }
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
}
