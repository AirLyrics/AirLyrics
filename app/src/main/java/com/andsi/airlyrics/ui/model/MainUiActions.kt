package com.andsi.airlyrics.ui.model

import android.view.View
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

internal data class MainUiActions(
    val selectPage: (Page) -> Unit,
    val openSettingsSubPage: (SettingsSubPage) -> Unit,
    val backToSettingsHome: () -> Unit,
    val toggleThemeMode: () -> Unit,
    val toggleFloatingFromNav: () -> Unit,
    val showFloatingLyrics: () -> Unit,
    val hideFloatingLyrics: () -> Unit,
    val toggleLock: () -> Unit,
    val toggleClickThrough: () -> Unit,
    val reloadFloatingLyrics: () -> Unit,
    val reloadFloatingLyricsFromOnline: () -> Unit,
    val currentLyricsOffsetSummary: () -> String,
    val adjustLyricsOffsetForCurrentMedia: (Long) -> Long?,
    val resetLyricsOffsetForCurrentMedia: () -> Boolean,
    val requestOverlayPermission: () -> Unit,
    val requestNotificationPermission: () -> Unit,
    val openNotificationListenerSettings: () -> Unit,
    val selectLyricsDirectory: () -> Unit,
    val copyLyricsDirectory: () -> Unit,
    val importLyricsForCurrentMedia: () -> Unit,
    val deleteLyricsForCurrentMedia: (CurrentMediaInfo) -> Unit,
    val toggleLyricsAutoSearch: () -> Boolean,
    val toggleLyricsAutoSave: () -> Boolean,
    val selectLyricsSource: (String) -> Unit,
    val openUrl: (String) -> Unit,
    val selectMediaSource: (String, View) -> Unit
)
