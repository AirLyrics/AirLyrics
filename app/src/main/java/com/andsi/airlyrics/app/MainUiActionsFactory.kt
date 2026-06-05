package com.andsi.airlyrics.app

import android.content.Intent
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

internal fun MainGraph.createMainUiActions(): MainUiActions {
    return MainUiActions(
        selectPage = { page ->
            if (state.currentPage != page || page == Page.SETTINGS) {
                state.currentPage = page
                if (page == Page.SETTINGS) {
                    state.settingsSubPage = SettingsSubPage.HOME
                }
                uiInvalidator.refresh()
            }
        },
        openSettingsSubPage = { subPage ->
            state.settingsSubPage = subPage
            uiInvalidator.refresh()
        },
        backToSettingsHome = {
            state.settingsSubPage = SettingsSubPage.HOME
            uiInvalidator.refresh()
        },
        toggleThemeMode = uiHost::toggleThemeMode,
        toggleFloatingFromNav = floatingController::toggleFromNav,
        showFloatingLyrics = { floatingController.showLyrics() },
        hideFloatingLyrics = { floatingController.hideLyrics() },
        toggleLock = floatingController::toggleLock,
        toggleClickThrough = floatingController::toggleClickThrough,
        reloadFloatingLyrics = floatingController::reloadLyrics,
        reloadFloatingLyricsFromOnline = floatingController::reloadLyricsFromOnline,
        currentLyricsOffsetSummary = ::currentLyricsOffsetSummary,
        adjustLyricsOffsetForCurrentMedia = ::adjustLyricsOffsetForCurrentMedia,
        resetLyricsOffsetForCurrentMedia = ::resetLyricsOffsetForCurrentMedia,
        requestOverlayPermission = ::requestOverlayPermission,
        requestNotificationPermission = ::requestNotificationPermissionIfNeeded,
        openNotificationListenerSettings = {
            activity.startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        selectLyricsDirectory = {
            launchers.selectLyricsDirectory()
        },
        copyLyricsDirectory = lyricsController::showLyricsDir,
        importLyricsForCurrentMedia = ::showImportLyricsDialog,
        deleteLyricsForCurrentMedia = lyricsController::deleteLyricsForCurrentMedia,
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
        selectLyricsSource = { sourceKey ->
            LyricsSettingsStore.setLyricsSource(activity, sourceKey)
            floatingController.reloadLyrics()
        },
        openUrl = uiHost::openUrl,
        selectMediaSource = appMediaController::selectSource
    )
}
