package com.andsi.airlyrics.ui.model

import android.view.View

internal interface SettingsUiHost {
    fun settingsHomeHeader(): View
    fun settingsBackHeader(
        title: String,
        subtitle: String = "",
        titleAction: View? = null,
        inlineTitleAction: View? = null
    ): View
    fun themeToggleButton(): View
    fun settingsCategoryCard(
        title: String,
        subtitle: String,
        status: String,
        iconRes: Int,
        onClick: () -> Unit
    ): View

    fun localLyricsRow(
        item: LocalLyricsUiItem,
        onLyricsChanged: ((LocalLyricsUiChange) -> Unit)? = null,
        badgeText: CharSequence? = null,
        onClickIntercept: (() -> Boolean)? = null
    ): View

    fun exportSavedLyrics(items: List<LocalLyricsUiItem>)

    fun manageSavedLyrics(items: List<LocalLyricsUiItem>, delete: Boolean)

    fun changelogItem(title: String, body: String): View
    fun permissionSummary(): String
    fun getAppVersionName(): String
    fun openUrl(url: String)
    fun reloadFloatingLyricsAfterLanguageChanged()

    fun hasNotificationPermission(): Boolean
    fun hasNotificationListenerAccess(): Boolean

    fun currentLyricsState(): CurrentLyricsUiState
    fun recentLyricsState(limit: Int): RecentLyricsUiState
    fun savedLyricsState(): SavedLyricsUiState
    fun lyricsSettingsState(): LyricsSettingsUiState
    fun languageSettingsState(): LanguageSettingsUiState
    fun setLanguageMode(mode: String)
    fun areStatusPopupsMuted(): Boolean
    fun setStatusPopupsMuted(muted: Boolean)
}
