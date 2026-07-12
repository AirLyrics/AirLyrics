package com.andsi.airlyrics.ui.model

import android.view.View
import android.widget.TextView
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.model.CurrentMediaInfo

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
        item: LyricsStorage.LocalLyricsItem,
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

    fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo, mode: LyricsStorage.DeleteMode)
}
