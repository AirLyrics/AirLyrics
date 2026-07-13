package com.andsi.airlyrics.app.host

import com.andsi.airlyrics.app.MainGraph
import android.content.Intent
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.refresh.PageRebuildReason
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

internal fun MainGraph.createMainUiActions(): MainUiActions {
    return MainUiActions(
        selectPage = { page ->
            if (state.currentPage != page) {
                state.currentPage = page
                if (page == Page.SETTINGS) {
                    state.settingsSubPage = SettingsSubPage.HOME
                }
                uiInvalidator.rebuildCurrentPage(PageRebuildReason.PAGE_NAVIGATION)
            }
        },
        openSettingsSubPage = { subPage ->
            state.settingsSubPage = subPage
            uiInvalidator.rebuildCurrentPage(PageRebuildReason.SETTINGS_NAVIGATION)
        },
        backToSettingsHome = {
            state.settingsSubPage = SettingsSubPage.HOME
            uiInvalidator.rebuildCurrentPage(PageRebuildReason.SETTINGS_NAVIGATION)
        },
        toggleThemeMode = uiHost::toggleThemeMode,
        toggleFloatingFromNav = floatingController::toggleFromNav,
        showFloatingLyrics = { floatingController.showLyrics() },
        hideFloatingLyrics = { floatingController.hideLyrics() },
        toggleLock = floatingController::toggleLock,
        toggleClickThrough = floatingController::toggleClickThrough,
        reloadFloatingLyrics = floatingController::reloadLyrics,
        reloadFloatingLyricsFromOnline = floatingController::reloadLyricsFromOnline,
        currentLyricsOffsetSummary = lyricsWorkflow::currentLyricsOffsetSummary,
        adjustLyricsOffsetForCurrentMedia = lyricsWorkflow::adjustLyricsOffsetForCurrentMedia,
        resetLyricsOffsetForCurrentMedia = lyricsWorkflow::resetLyricsOffsetForCurrentMedia,
        requestOverlayPermission = ::requestOverlayPermission,
        requestNotificationPermission = ::requestNotificationPermissionIfNeeded,
        openNotificationListenerSettings = {
            activity.startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        selectLyricsDirectory = {
            launchers.selectLyricsDirectory()
        },
        copyLyricsDirectory = lyricsController::showLyricsDir,
        importLyricsForCurrentMedia = lyricsWorkflow::showImportLyricsDialog,
        deleteLyricsForCurrentMedia = { mode ->
            lyricsController.getCurrentMediaInfo()?.let { media ->
                lyricsController.deleteLyricsForCurrentMedia(media, mode.toStorageDeleteMode())
            }
        },
        toggleLyricsAutoSearch = {
            val enabled = !LyricsSettingsStore.isAutoSearchOnlineEnabled(activity)
            LyricsSettingsStore.setAutoSearchOnlineEnabled(activity, enabled)
            floatingController.reloadLyrics()
            enabled
        },
        toggleLyricsAutoSave = {
            val enabled = !LyricsSettingsStore.isAutoSaveLocalEnabled(activity)
            LyricsSettingsStore.setAutoSaveLocalEnabled(activity, enabled)
            enabled
        },
        selectLyricsSource = { source ->
            LyricsSettingsStore.setLyricsSearchSource(activity, source)
            floatingController.reloadLyrics()
        },
        openUrl = uiHost::openUrl,
        selectMediaSource = mediaSourceController::selectSource
    )
}
