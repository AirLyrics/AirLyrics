package com.andsi.airlyrics.ui.model

import android.view.View
import android.widget.TextView

internal interface SettingsUiHost {
    fun settingsHomeHeader(): View
    fun settingsBackHeader(title: String, subtitle: String = ""): View
    fun themeToggleButton(): TextView
    fun settingsCategoryCard(
        title: String,
        subtitle: String,
        status: String,
        accent: Int,
        iconRes: Int,
        onClick: () -> Unit
    ): View

    fun localLyricsRow(
        item: LocalLyricsUiItem,
        onLyricsSaved: (() -> Unit)? = null,
        badgeText: CharSequence? = null
    ): View

    fun changelogItem(title: String, body: String): View
    fun permissionSummary(): String
    fun getAppVersionName(): String
    fun openUrl(url: String)
    fun refreshAfterLanguageChanged()

    fun hasNotificationPermission(): Boolean
    fun hasNotificationListenerAccess(): Boolean

    fun currentLyricsState(): CurrentLyricsUiState
    fun recentLyricsState(limit: Int): RecentLyricsUiState
    fun lyricsSettingsState(): LyricsSettingsUiState
    fun languageSettingsState(): LanguageSettingsUiState
    fun setLanguageMode(mode: String)
    fun isToasterMuted(): Boolean
    fun setToasterMuted(muted: Boolean)
}
