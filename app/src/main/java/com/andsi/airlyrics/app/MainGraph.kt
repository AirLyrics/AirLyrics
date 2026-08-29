package com.andsi.airlyrics.app

import android.content.Intent
import android.media.MediaMetadata
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
import com.andsi.airlyrics.floating.FloatingWindowRuntimeState
import com.andsi.airlyrics.floating.FloatingWindowStateBroadcast
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import com.andsi.airlyrics.lyrics.BroadcastLyricsChangedPublisher
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.ui.components.showAirConfirmDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.feedback.AirFeedback
import com.andsi.airlyrics.ui.feedback.SnackbarAirFeedback
import com.andsi.airlyrics.ui.feedback.ToastAirFeedback
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.model.RefreshState
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.navigation.parentPage
import com.andsi.airlyrics.ui.refresh.PageRebuildReason
import java.util.concurrent.Executors

/** Main-screen composition root and lifecycle orchestrator. */
internal class MainGraph(
    internal val activity: MainActivity
) {
    private companion object {
        const val KEY_CURRENT_PAGE = "airlyrics.current_page"
        const val KEY_SETTINGS_SUB_PAGE = "airlyrics.settings_sub_page"
        const val KEY_SAVED_LYRICS_SEARCH_OPEN = "airlyrics.saved_lyrics_search_open"
        const val KEY_SAVED_LYRICS_SEARCH_QUERY = "airlyrics.saved_lyrics_search_query"
        const val KEY_PENDING_LYRICS_IMPORT = "airlyrics.pending_lyrics_import"
        const val KEY_PENDING_LYRICS_OVERWRITE = "airlyrics.pending_lyrics_overwrite"
        const val MEDIA_PAGE_REFRESH_DELAY_MS = 120L
    }

    private data class PermissionSnapshot(
        val overlayGranted: Boolean,
        val postNotificationsGranted: Boolean,
        val notificationListenerGranted: Boolean
    )

    private data class FloatingUiSnapshot(
        val visible: Boolean,
        val locked: Boolean,
        val clickThrough: Boolean
    )

    private data class MediaPlayerUiSnapshot(
        val packageName: String,
        val title: String,
        val artist: String,
        val playbackState: Int?
    )

    private data class MediaUiSnapshot(
        val selectedPackage: String?,
        val players: List<MediaPlayerUiSnapshot>
    )

    private data class ForegroundUiSnapshot(
        val permissions: PermissionSnapshot,
        val floating: FloatingUiSnapshot,
        val media: MediaUiSnapshot,
        val lyricsRevision: Long
    )

    private data class ForegroundChanges(
        val overlayBecameGranted: Boolean = false
    )

    private data class UiRefreshPlan(
        val rebuildReason: PageRebuildReason? = null,
        val refreshTabs: Boolean = false,
        val refreshFloatingChrome: Boolean = false,
        val refreshFloatingControls: Boolean = false,
        val refreshLyricsContent: Boolean = false
    )

    private data class PendingUiDelivery(
        val generation: Long,
        val block: () -> Unit
    )

    val state: MainActivityState = MainActivityState()
    val viewRefs = MainActivityViewRefs()
    private val toastFeedback: AirFeedback = ToastAirFeedback(activity)
    val feedback: AirFeedback = SnackbarAirFeedback(
        activity = activity,
        anchorProvider = { viewRefs.feedbackAnchor },
        fallback = toastFeedback
    )
    val crossWindowFeedback: AirFeedback
        get() = toastFeedback

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
            navFeedback = { playFloatingNavToggleFeedback() },
            feedback = feedback,
            crossWindowFeedback = crossWindowFeedback
        )
    }
    val mediaSourceController: MediaSourceController by lazy {
        MediaSourceController(
            context = activity,
            mediaPageRefreshScheduler = { scheduleMediaPageRefresh() },
            sourceSelectionRenderer = { packageName ->
                uiHost.updateMediaSourceSelectionVisualsImpl(packageName)
                scheduleMediaPageRefresh()
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
            lyricsChangedPublisher = BroadcastLyricsChangedPublisher(activity),
            feedback = feedback
        )
    }
    val uiActions: MainUiActions by lazy { createMainUiActions() }
    val lyricsWorkflow: MainLyricsWorkflow by lazy { MainLyricsWorkflow(this) }

    /** Owns only the refresh-button feedback sequence exposed through MainUiHost. */
    val mediaRefreshHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** Kept separate so button feedback cannot cancel broadcast debounce work. */
    private val mediaPageRefreshHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val mediaPageRefreshRunnable = Runnable {
        state.mediaPageRefreshScheduled = false
        if (!canRenderUi() || !mediaDirty) return@Runnable
        syncForegroundState()
    }

    private val mainTaskRunner: MainTaskRunner = object : MainTaskRunner {
        override fun runOnAppIo(block: () -> Unit) {
            this@MainGraph.runOnAppIo(block)
        }

        override fun runOnMainThread(block: () -> Unit) {
            this@MainGraph.runOnMainThread(block)
        }

        override fun currentUiGeneration(): Long = this@MainGraph.currentUiGeneration()

        override fun runOnStartedUi(expectedGeneration: Long, block: () -> Unit) {
            this@MainGraph.runOnStartedUi(expectedGeneration, block)
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
            onFloatingStateChanged = ::handleFloatingStateChanged,
            onLyricsChanged = { handleLyricsChanged() }
        )
    }

    private val appIoExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "airlyrics-app-io").apply { isDaemon = true }
        }
    }
    private var lastAppliedSnapshot: ForegroundUiSnapshot? = null
    private var mediaDirty = false
    private var lyricsDirty = false
    private var floatingStructureDirty = false
    private var uiGeneration = 0L
    private var uiStarted = false
    private var destroyed = false
    private val pendingUiDeliveries = ArrayDeque<PendingUiDelivery>()

    fun beforeSuperOnCreate() {
        LanguageSettingsStore.applyAppLocale(activity)
    }

    fun onCreate(savedInstanceState: Bundle?) {
        restoreNavigationState(savedInstanceState)
        restorePendingLyricsState(savedInstanceState)
        mediaSourceController.autoSelectSourceOnceIfNeeded()
        val initialSnapshot = readForegroundSnapshot()
        applySnapshotToState(initialSnapshot)
        uiHost.applySystemBarsTheme()
        registerBackNavigationCallback()
        activity.setContentView(mainHandRenderer.createMainView())
        uiHost.applySystemBarsTheme()
        uiInvalidator.rebuildCurrentPage(PageRebuildReason.INITIAL_RENDER)
        lastAppliedSnapshot = initialSnapshot
        lyricsWorkflow.restorePendingOverwriteConfirmation()
    }

    fun onStart() {
        if (destroyed || uiStarted) return

        uiStarted = true
        receivers.register()
        syncForegroundState()
        restoreDesiredFloatingWindow()
        deliverPendingUiCallbacks()
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_CURRENT_PAGE, state.currentPage.name)
        outState.putString(KEY_SETTINGS_SUB_PAGE, state.settingsSubPage.name)
        outState.putBoolean(KEY_SAVED_LYRICS_SEARCH_OPEN, state.savedLyricsSearchOpen)
        outState.putString(KEY_SAVED_LYRICS_SEARCH_QUERY, state.savedLyricsSearchQuery)
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
        uiHost.applySystemBarsTheme()
        val changes = syncForegroundState()
        if (changes.overlayBecameGranted) {
            restoreDesiredFloatingWindow()
        }
    }

    fun onStop() {
        if (!uiStarted) return

        uiStarted = false
        cancelPendingUiRefreshes()
        receivers.unregister()
    }

    fun runOnAppIo(block: () -> Unit) {
        appIoExecutor.execute(block)
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
        runOnMainThread {
            if (uiGeneration != expectedGeneration) return@runOnMainThread
            if (canRenderUi()) {
                block()
            } else {
                pendingUiDeliveries.addLast(
                    PendingUiDelivery(expectedGeneration, block)
                )
            }
        }
    }

    internal fun beginPageRebuild() {
        uiGeneration += 1L
        pendingUiDeliveries.clear()
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        state.mediaRefreshState = RefreshState.IDLE
    }

    fun onDestroy() {
        destroyed = true
        uiStarted = false
        uiGeneration += 1L
        feedback.dismiss()
        state.pendingLyricsOverwrite = null
        mediaPageRefreshHandler.removeCallbacksAndMessages(null)
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        state.mediaPageRefreshScheduled = false
        state.mediaRefreshState = RefreshState.IDLE
        pendingUiDeliveries.clear()
        appIoExecutor.shutdown()
        receivers.unregister()
    }

    private fun handleLyricsChanged() {
        lyricsDirty = true
        if (canRenderUi()) syncForegroundState()
    }

    private fun handleFloatingStateChanged(intent: Intent) {
        if (!canRenderUi()) return
        val windowState = FloatingWindowStateBroadcast.readState(intent) ?: return
        FloatingWindowRuntimeState.update(windowState)
        syncForegroundState()
    }

    private fun restoreNavigationState(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        state.currentPage = savedInstanceState.getString(KEY_CURRENT_PAGE)
            ?.let { runCatching { Page.valueOf(it) }.getOrNull() }
            ?: state.currentPage
        state.settingsSubPage = savedInstanceState.getString(KEY_SETTINGS_SUB_PAGE)
            ?.let { runCatching { SettingsSubPage.valueOf(it) }.getOrNull() }
            ?: state.settingsSubPage
        state.savedLyricsSearchOpen = savedInstanceState.getBoolean(
            KEY_SAVED_LYRICS_SEARCH_OPEN,
            state.savedLyricsSearchOpen
        )
        state.savedLyricsSearchQuery = savedInstanceState.getString(
            KEY_SAVED_LYRICS_SEARCH_QUERY,
            state.savedLyricsSearchQuery
        )
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
            state.settingsSubPage = state.settingsSubPage.parentPage() ?: SettingsSubPage.HOME
            uiInvalidator.rebuildCurrentPage(PageRebuildReason.BACK_NAVIGATION)
            return true
        }

        return false
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

    private fun handleFloatingFontFileResult(uri: android.net.Uri?) {
        uri ?: return
        feedback.showMessage(R.string.ui_importing_font)
        val expectedUiGeneration = currentUiGeneration()
        runOnAppIo {
            when (val result = FloatingLyricsFontStore.importFont(activity, uri)) {
                is FloatingLyricsFontStore.ImportResult.Success -> {
                    runOnMainThread {
                        if (destroyed || activity.isDestroyed) return@runOnMainThread
                        FloatingLyricsStyleStore.setFontFamily(
                            activity,
                            FloatingLyricsFontFamily.CUSTOM
                        )
                        floatingController.notifyStyleChanged()
                        floatingStructureDirty = true
                        if (canRenderUi()) {
                            syncForegroundState()
                            feedback.showMessage(
                                activity.getString(
                                    R.string.ui_font_import_success,
                                    result.displayName
                                )
                            )
                        }
                    }
                }

                FloatingLyricsFontStore.ImportResult.UnsupportedFormat ->
                    showFloatingFontImportError(
                        expectedUiGeneration,
                        R.string.ui_font_format_unsupported
                    )
                FloatingLyricsFontStore.ImportResult.TooLarge ->
                    showFloatingFontImportError(
                        expectedUiGeneration,
                        R.string.ui_font_file_too_large
                    )
                FloatingLyricsFontStore.ImportResult.InvalidFont ->
                    showFloatingFontImportError(
                        expectedUiGeneration,
                        R.string.ui_invalid_font_file
                    )
                FloatingLyricsFontStore.ImportResult.ReadFailed ->
                    showFloatingFontImportError(
                        expectedUiGeneration,
                        R.string.ui_font_import_failed
                    )
            }
        }
    }

    private fun showFloatingFontImportError(
        expectedUiGeneration: Long,
        messageRes: Int
    ) {
        runOnStartedUi(expectedUiGeneration) {
            feedback.showError(messageRes)
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
        mediaDirty = true
        if (!canRenderUi()) return
        if (state.mediaPageRefreshScheduled) return

        state.mediaPageRefreshScheduled = true
        mediaPageRefreshHandler.postDelayed(
            mediaPageRefreshRunnable,
            MEDIA_PAGE_REFRESH_DELAY_MS
        )
    }

    private fun syncForegroundState(): ForegroundChanges {
        if (!canRenderUi()) return ForegroundChanges()

        val previous = lastAppliedSnapshot
        val latest = readForegroundSnapshot()
        val refreshPlan = buildRefreshPlan(previous, latest)
        val changes = ForegroundChanges(
            overlayBecameGranted = previous?.permissions?.overlayGranted == false &&
                latest.permissions.overlayGranted
        )

        applySnapshotToState(latest)
        lastAppliedSnapshot = latest
        applyRefreshPlan(refreshPlan)
        mediaDirty = false
        lyricsDirty = false
        floatingStructureDirty = false
        return changes
    }

    private fun readForegroundSnapshot(): ForegroundUiSnapshot {
        return ForegroundUiSnapshot(
            permissions = PermissionSnapshot(
                overlayGranted = Settings.canDrawOverlays(activity),
                postNotificationsGranted = PermissionHelper.hasPostNotificationsPermission(activity),
                notificationListenerGranted = PermissionHelper.hasNotificationListenerAccess(activity)
            ),
            floating = FloatingUiSnapshot(
                visible = FloatingWindowRuntimeState.snapshot()?.visible ?: false,
                locked = FloatingLyricsStyleStore.isLocked(activity),
                clickThrough = FloatingLyricsStyleStore.isClickThrough(activity)
            ),
            media = readMediaUiSnapshot(),
            lyricsRevision = LyricsStorage.currentRevision()
        )
    }

    private fun readMediaUiSnapshot(): MediaUiSnapshot {
        val controllers = CurrentMediaReader
            .selectedControllersByPackage(mediaSourceController.getActiveControllers())
            .values
            .toList()
        return MediaUiSnapshot(
            selectedPackage = MediaSourceStore.getSelectedPackage(activity),
            players = controllers.map { controller ->
                MediaPlayerUiSnapshot(
                    packageName = controller.packageName,
                    title = controller.metadata
                        ?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        .orEmpty(),
                    artist = controller.metadata
                        ?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: controller.metadata
                            ?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                            .orEmpty(),
                    playbackState = controller.playbackState?.state
                )
            }
        )
    }

    private fun applySnapshotToState(snapshot: ForegroundUiSnapshot) {
        state.overlayPermissionGranted = snapshot.permissions.overlayGranted
        state.quickFloatingVisible = snapshot.floating.visible
        state.locked = snapshot.floating.locked
        state.clickThrough = snapshot.floating.clickThrough
    }

    private fun buildRefreshPlan(
        previous: ForegroundUiSnapshot?,
        latest: ForegroundUiSnapshot
    ): UiRefreshPlan {
        previous ?: return UiRefreshPlan()

        val overlayPermissionChanged =
            previous.permissions.overlayGranted != latest.permissions.overlayGranted
        val postNotificationsChanged =
            previous.permissions.postNotificationsGranted != latest.permissions.postNotificationsGranted
        val notificationListenerChanged =
            previous.permissions.notificationListenerGranted !=
                latest.permissions.notificationListenerGranted
        val mediaChanged = previous.media != latest.media
        val lyricsChanged = previous.lyricsRevision != latest.lyricsRevision || lyricsDirty
        val floatingVisibilityChanged = previous.floating.visible != latest.floating.visible
        val floatingControlsChanged = previous.floating.locked != latest.floating.locked ||
            previous.floating.clickThrough != latest.floating.clickThrough

        val permissionRebuildRequired = when (state.currentPage) {
            Page.MEDIA -> notificationListenerChanged
            Page.FLOATING -> overlayPermissionChanged
            Page.SETTINGS -> state.settingsSubPage == SettingsSubPage.HOME ||
                state.settingsSubPage == SettingsSubPage.SYSTEM
        } && (overlayPermissionChanged || postNotificationsChanged || notificationListenerChanged)

        val rebuildReason = when {
            permissionRebuildRequired -> PageRebuildReason.PERMISSION_CHANGED
            state.currentPage == Page.FLOATING && floatingStructureDirty ->
                PageRebuildReason.FLOATING_STRUCTURE_CHANGED
            state.currentPage == Page.MEDIA && mediaChanged ->
                PageRebuildReason.MEDIA_CONTENT_CHANGED
            else -> null
        }
        if (rebuildReason != null) {
            return UiRefreshPlan(rebuildReason = rebuildReason)
        }

        val lyricsPageMounted = state.currentPage == Page.SETTINGS &&
            (state.settingsSubPage == SettingsSubPage.LYRICS ||
                state.settingsSubPage == SettingsSubPage.SAVED_LYRICS)
        return UiRefreshPlan(
            refreshTabs = overlayPermissionChanged && !floatingVisibilityChanged,
            refreshFloatingChrome = floatingVisibilityChanged,
            refreshFloatingControls = floatingControlsChanged,
            refreshLyricsContent = lyricsPageMounted &&
                (lyricsChanged || (mediaChanged && state.settingsSubPage == SettingsSubPage.LYRICS))
        )
    }

    private fun applyRefreshPlan(plan: UiRefreshPlan) {
        plan.rebuildReason?.let { reason ->
            cancelPendingMediaPageRefresh()
            uiInvalidator.rebuildCurrentPage(
                reason = reason,
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
        cancelPendingMediaPageRefresh()
        mediaRefreshHandler.removeCallbacksAndMessages(null)
        state.mediaRefreshState = RefreshState.IDLE
    }

    private fun cancelPendingMediaPageRefresh() {
        mediaPageRefreshHandler.removeCallbacks(mediaPageRefreshRunnable)
        state.mediaPageRefreshScheduled = false
    }

    private fun canRenderUi(): Boolean {
        return uiStarted && !destroyed && !activity.isDestroyed
    }

    private fun deliverPendingUiCallbacks() {
        while (canRenderUi() && pendingUiDeliveries.isNotEmpty()) {
            val delivery = pendingUiDeliveries.removeFirst()
            if (delivery.generation == uiGeneration) {
                delivery.block()
            }
        }
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
