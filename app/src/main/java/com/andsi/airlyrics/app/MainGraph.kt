package com.andsi.airlyrics.app

import com.andsi.airlyrics.app.controller.PermissionController
import com.andsi.airlyrics.app.host.MainActivityUiHost
import com.andsi.airlyrics.app.host.createMainUiActions
import com.andsi.airlyrics.app.host.updateMediaSourceSelectionVisualsImpl
import com.andsi.airlyrics.app.render.MainActivityViewRefs
import com.andsi.airlyrics.app.state.MainActivityState
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.controller.AppMediaController
import com.andsi.airlyrics.app.controller.FloatingController
import com.andsi.airlyrics.app.controller.LyricsController
import com.andsi.airlyrics.app.controller.MainDialogHost
import com.andsi.airlyrics.app.controller.MainTaskRunner
import com.andsi.airlyrics.app.lifecycle.MainLaunchers
import com.andsi.airlyrics.app.lifecycle.MainReceivers
import com.andsi.airlyrics.app.render.MainHandRenderer
import com.andsi.airlyrics.app.render.UiInvalidator
import com.andsi.airlyrics.i18n.localizedAssetText
import com.andsi.airlyrics.i18n.localizedOffsetDescription
import com.andsi.airlyrics.lyrics.importer.LyricsImportValidator
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LanguageSettingsStore
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.showAirConfirmDialog
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.tokens.AirUiTokens
import java.util.concurrent.Executors

/** Main-screen composition root and lifecycle orchestrator. */
internal class MainGraph(
    internal val activity: MainActivity
) {
    val state: MainActivityState = MainActivityState()
    val viewRefs = MainActivityViewRefs()

    val launchers: MainLaunchers = MainLaunchers(
        activity = activity,
        onLyricsFileSelected = ::handleLyricsFileSelected,
        onLyricsDirectorySelected = ::handleLyricsDirectorySelected,
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
    val appMediaController: AppMediaController by lazy {
        AppMediaController(
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
            mediaControllerProvider = appMediaController,
            floatingLyricsReloader = { floatingController.reloadLyrics() }
        )
    }
    val uiActions: MainUiActions by lazy { createMainUiActions() }

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
        uiHost.applySystemBarsTheme()
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
            uiInvalidator.refresh()
            return true
        }

        return false
    }

    fun handleLyricsFileSelected(uri: Uri) {
        val media = state.pendingImportMedia ?: lyricsController.getCurrentMediaSnapshot()
        if (media == null || media.title.isBlank()) {
            Toast.makeText(activity, activity.getString(R.string.ui_select_song_before_importing), Toast.LENGTH_LONG).show()
            return
        }

        val importAsWordByWord = state.pendingImportAsWordByWord
        state.pendingImportMedia = null

        if (!LyricsImportValidator.isLikelyLyricsDocument(activity, uri)) {
            val message = if (importAsWordByWord) {
                activity.getString(R.string.ui_please_choose_an_enhanced_lrc_file)
            } else {
                activity.getString(R.string.ui_please_choose_a_plain_lrc_lyrics_file)
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            return
        }

        if (LyricsImportValidator.isLyricsDocumentTooLarge(activity, uri)) {
            Toast.makeText(activity, activity.getString(R.string.ui_lrc_file_too_large), Toast.LENGTH_LONG).show()
            return
        }

        lyricsController.importLyricsForCurrentMedia(
            uri = uri,
            media = media,
            overwrite = false,
            importAsWordByWord = importAsWordByWord
        )
    }

    fun handleLyricsDirectorySelected(uri: Uri) {
        val permissionGranted = runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.isSuccess

        if (!permissionGranted) {
            Toast.makeText(activity, activity.getString(R.string.ui_lyrics_folder_permission_failed), Toast.LENGTH_LONG).show()
            return
        }

        if (!LyricsStorage.validateLyricsDir(activity, uri)) {
            Toast.makeText(activity, activity.getString(R.string.ui_lyrics_folder_write_failed), Toast.LENGTH_LONG).show()
            return
        }

        LyricsStorage.saveLyricsDirUri(activity, uri)
        Toast.makeText(activity, activity.getString(R.string.ui_lyrics_save_folder_set), Toast.LENGTH_LONG).show()
        uiInvalidator.refresh(animateContent = false, animateTabs = false)
    }

    fun handleNotificationPermissionResult(granted: Boolean) {
        val message = if (granted) {
            activity.getString(R.string.ui_notification_permission_enabled)
        } else {
            activity.getString(R.string.ui_notif_permission_off_warning)
        }

        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        uiInvalidator.refresh()
    }

    fun currentLyricsOffsetSummary(): String {
        val media = lyricsController.getCurrentMediaSnapshot() ?: return activity.getString(R.string.ui_waiting_for_current_song)
        return activity.localizedOffsetDescription(LyricsOffsetStore.getOffsetMs(activity, media))
    }

    fun adjustLyricsOffsetForCurrentMedia(deltaMs: Long): Long? {
        val media = lyricsController.getCurrentMediaSnapshot() ?: return null
        val offset = LyricsOffsetStore.adjustOffsetMs(activity, media, deltaMs)
        floatingController.applyLyricsOffset(offset)
        return offset
    }

    fun resetLyricsOffsetForCurrentMedia(): Boolean {
        val media = lyricsController.getCurrentMediaSnapshot() ?: return false
        LyricsOffsetStore.resetOffset(activity, media)
        floatingController.applyLyricsOffset(0L)
        return true
    }

    fun showImportLyricsDialog() {
        val media = lyricsController.getCurrentMediaSnapshot()
        if (media == null || media.title.isBlank()) {
            Toast.makeText(activity, activity.getString(R.string.ui_select_song_before_importing), Toast.LENGTH_LONG).show()
            return
        }

        var dialog: Dialog? = null

        fun launchImport(asWordByWord: Boolean) {
            state.pendingImportAsWordByWord = asWordByWord
            state.pendingImportMedia = media
            dialog?.dismiss()
            launchers.selectLyricsFile()
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                uiHost.dp(AirUiTokens.Space.PageH),
                uiHost.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs),
                uiHost.dp(AirUiTokens.Space.PageH),
                uiHost.dp(AirUiTokens.Space.Sm)
            )

            addView(TextView(activity).apply {
                text = media.displayText
                textSize = AirUiTokens.TextSize.Button
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(uiHost.colorTextStrong)
                setPadding(0, 0, 0, uiHost.dp(AirUiTokens.Space.Xl))
            })

            addView(TextView(activity).apply {
                text = activity.getString(R.string.ui_plain_and_enhanced_lrc_examples_short)
                textSize = AirUiTokens.TextSize.BodySmall
                setTextColor(uiHost.colorTextMuted)
                setPadding(0, 0, 0, uiHost.dp(AirUiTokens.Space.Xxl))
            })

            addView(importLyricsChoiceRow(
                title = activity.getString(R.string.ui_plain_lyrics_lrc),
                subtitle = activity.getString(R.string.ui_plain_lrc_recommended_format_hint),
                primary = true
            ) { launchImport(false) })

            addView(importLyricsChoiceRow(
                title = activity.getString(R.string.ui_enhanced_lrc_lyrics_enhanced_lrc),
                subtitle = activity.getString(R.string.ui_recommended_enhanced_lrc_format),
                primary = false
            ) { launchImport(true) })

            addView(importLyricsChoiceRow(
                title = activity.getString(R.string.ui_lyrics_format_guide),
                subtitle = activity.getString(R.string.ui_view_lrc_examples_hint),
                primary = false
            ) { showLyricsFormatGuideDialog() })
        }

        dialog = uiHost.showAirDialog(
            title = activity.getString(R.string.ui_choose_import_type),
            positiveText = null,
            negativeText = activity.getString(R.string.ui_cancel),
            body = {
                addView(content)
            }
        )
    }

    fun requestOverlayPermission() {
        PermissionController.requestOverlayPermission(activity)
    }

    fun requestNotificationPermissionIfNeeded() {
        PermissionController.requestNotificationPermissionIfNeeded(
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
                uiInvalidator.refresh(animateContent = false, animateTabs = false)
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

    private fun importLyricsChoiceRow(
        title: String,
        subtitle: String,
        primary: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = activity.getString(R.string.ui_title_subtitle, title, subtitle)
            textSize = AirUiTokens.TextSize.Button
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else uiHost.colorTextStrong)
            setLineSpacing(uiHost.dp(AirUiTokens.Space.Xxs).toFloat(), 1f)
            setPadding(
                uiHost.dp(AirUiTokens.Space.ButtonH),
                uiHost.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs),
                uiHost.dp(AirUiTokens.Space.ButtonH),
                uiHost.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
            )
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, uiHost.dp(AirUiTokens.Space.Xl), 0, 0)
            layoutParams = params
            background = GradientDrawable().apply {
                cornerRadius = uiHost.dp(AirUiTokens.Radius.Sm).toFloat()
                if (primary) {
                    setColor(uiHost.colorAccent)
                } else {
                    setColor(uiHost.colorSurfaceLight)
                    setStroke(uiHost.dp(AirUiTokens.Stroke.Hairline), uiHost.colorStroke)
                }
            }
            enableSoftPressFeedback(0.97f)
            setOnClickListener {
                onClick()
                playTinyPulse(this)
            }
        }
    }

    private fun showLyricsFormatGuideDialog() {
        uiHost.showAirInfoDialog(
            title = activity.getString(R.string.ui_lyrics_format_guide),
            message = activity.localizedAssetText(
                baseName = "help/lyrics_format",
                fallback = activity.getString(R.string.ui_lyrics_format_guide_body)
            )
        )
    }
}
