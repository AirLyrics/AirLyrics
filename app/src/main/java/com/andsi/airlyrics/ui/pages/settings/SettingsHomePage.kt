package com.andsi.airlyrics.ui.pages.settings

import android.view.View
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorAccentPink
import com.andsi.airlyrics.i18n.localizedLyricsSourceTitle

internal fun createSettingsHomePage(activity: MainUiHost): View  = with(activity) createSettingsHomePage@ {
    val container = pageContainer(activity)
    val lyricsSettings = lyricsSettingsState()

    container.addView(settingsHomeHeader())


    container.addView(
        settingsCategoryCard(
            title = getString(R.string.ui_lyrics),
            subtitle = getString(R.string.ui_lyrics_settings_summary),
            status = "${localizedLyricsSourceTitle(lyricsSettings.selectedSource)} · ${if (lyricsSettings.autoSaveLocal) getString(R.string.ui_auto_save) else getString(R.string.ui_no_auto_save)}",
            accent = colorAccentPink,
            iconRes = R.drawable.ic_air_music_note
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.LYRICS)
        }
    )

    container.addView(
        settingsCategoryCard(
            title = getString(R.string.ui_system),
            subtitle = getString(R.string.ui_overlay_and_notification_permissions),
            status = permissionSummary(),
            accent = colorAccentMint,
            iconRes = R.drawable.ic_air_shield
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.SYSTEM)
        }
    )

    container.addView(
        settingsCategoryCard(
            title = getString(R.string.ui_about),
            subtitle = getString(R.string.ui_version_project_link_and_changelog),
            status = "AirLyrics ${getAppVersionName()}",
            accent = colorAccentMint,
            iconRes = R.drawable.ic_air_info
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.ABOUT)
        }
    )


    return scroll(activity, container)
}
