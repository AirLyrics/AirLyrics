package com.andsi.airlyrics.app

import android.content.Intent
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.ui.model.MainUiActions
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

internal fun MainActivity.createMainUiActions(): MainUiActions {
    return MainUiActions(
        selectPage = { page ->
            if (currentPage != page || page == Page.SETTINGS) {
                currentPage = page
                if (page == Page.SETTINGS) {
                    settingsSubPage = SettingsSubPage.HOME
                }
                renderCurrentPage()
            }
        },
        openSettingsSubPage = { subPage ->
            settingsSubPage = subPage
            renderCurrentPage()
        },
        backToSettingsHome = {
            settingsSubPage = SettingsSubPage.HOME
            renderCurrentPage()
        },
        toggleThemeMode = ::toggleThemeMode,
        toggleFloatingFromNav = ::toggleFloatingFromNav,
        showFloatingLyrics = { showFloatingLyrics() },
        hideFloatingLyrics = { hideFloatingLyrics() },
        toggleLock = ::toggleLock,
        toggleClickThrough = ::toggleClickThrough,
        reloadFloatingLyrics = ::reloadFloatingLyrics,
        reloadFloatingLyricsFromOnline = ::reloadFloatingLyricsFromOnline,
        requestOverlayPermission = ::requestOverlayPermission,
        requestNotificationPermission = ::requestNotificationPermissionIfNeeded,
        openNotificationListenerSettings = {
            startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        selectLyricsDirectory = {
            selectLyricsDirLauncher.launch(null)
        },
        copyLyricsDirectory = ::showLyricsDir,
        importLyricsForCurrentMedia = ::showImportLyricsDialog,
        deleteLyricsForCurrentMedia = ::deleteLyricsForCurrentMedia,
        toggleLyricsAutoSearch = {
            val enabled = !LyricsSettingsStore.isAutoSearchOnlineEnabled(this)
            LyricsSettingsStore.setAutoSearchOnlineEnabled(this, enabled)
            reloadFloatingLyrics()
            enabled
        },
        toggleLyricsAutoSave = {
            val enabled = !LyricsSettingsStore.isAutoSaveLocalEnabled(this)
            LyricsSettingsStore.setAutoSaveLocalEnabled(this, enabled)
            enabled
        },
        selectLyricsSource = { sourceKey ->
            LyricsSettingsStore.setLyricsSource(this, sourceKey)
            reloadFloatingLyrics()
        },
        openUrl = ::openUrl,
        selectMediaSource = { packageName, sourceCard ->
            appMediaController.selectSource(packageName, sourceCard)
        }
    )
}

