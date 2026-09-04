package com.andsi.airlyrics.app.host

import com.andsi.airlyrics.app.MainGraph
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.model.MainUiActions

internal fun MainGraph.createMainUiActions(): MainUiActions {
    return MainUiActions(
        selectPage = viewModel::selectPage,
        openSettingsSubPage = viewModel::openSettingsSubPage,
        setSavedLyricsSearchOpen = viewModel::setSavedLyricsSearchOpen,
        updateSavedLyricsSearchQuery = viewModel::updateSavedLyricsSearchQuery,
        setMediaRefreshState = viewModel::setMediaRefreshState,
        toggleThemeMode = uiHost::toggleThemeMode,
        selectThemeAccent = uiHost::selectThemeAccent,
        toggleFloatingFromNav = ::toggleFloatingFromNav,
        showFloatingLyrics = ::showFloatingLyrics,
        hideFloatingLyrics = ::hideFloatingLyrics,
        toggleLock = ::toggleFloatingLock,
        toggleClickThrough = ::toggleFloatingClickThrough,
        toggleAutoHideWhenPaused = floatingController::toggleAutoHideWhenPaused,
        toggleDisplayScope = ::toggleDisplayScope,
        chooseDisplayScopeApps = { displayScopeWorkflow.showAppPicker() },
        reloadFloatingLyrics = floatingController::reloadLyrics,
        searchOnlineLyricsForCurrentMedia = viewModel::searchOnlineLyricsForCurrentMedia,
        currentLyricsOffsetSummary = lyricsWorkflow::currentLyricsOffsetSummary,
        adjustLyricsOffsetForCurrentMedia = lyricsWorkflow::adjustLyricsOffsetForCurrentMedia,
        resetLyricsOffsetForCurrentMedia = lyricsWorkflow::resetLyricsOffsetForCurrentMedia,
        requestOverlayPermission = viewModel::requestOverlayPermission,
        requestNotificationPermission = viewModel::requestNotificationPermission,
        openNotificationListenerSettings = viewModel::openNotificationListenerSettings,
        openUsageAccessSettings = viewModel::openUsageAccessSettings,
        selectLyricsDirectory = viewModel::selectLyricsDirectory,
        copyLyricsDirectory = ::copyLyricsDirectory,
        importLyricsForCurrentMedia = viewModel::requestLyricsImport,
        deleteLyricsForCurrentMedia = { mode ->
            viewModel.deleteLyricsForCurrentMedia(mode.toStorageDeleteMode())
        },
        deleteSavedLyrics = { item, onCompleted ->
            deleteSavedLyrics(item.toStorageItem(), onCompleted)
        },
        deleteAllSavedLyrics = viewModel::deleteAllSavedLyrics,
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
        selectPlainLyricsSource = { plainLyricsSearchSource ->
            LyricsSettingsStore.setPlainLyricsSearchSource(activity, plainLyricsSearchSource)
            floatingController.reloadLyrics()
        },
        openUrl = uiHost::openUrl,
        selectMediaSource = { packageName, sourceCard ->
            mediaSourceController.selectSource(packageName)
            uiHost.updateMediaSourceSelectionVisualsImpl(packageName)
            scheduleMediaPageRefresh()
            playTinyPulse(sourceCard)
        }
    )
}
