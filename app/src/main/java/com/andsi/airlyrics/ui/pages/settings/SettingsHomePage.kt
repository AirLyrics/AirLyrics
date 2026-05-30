package com.andsi.airlyrics.ui.pages.settings

import android.view.View
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizeText
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.*
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorAccentPink
import com.andsi.airlyrics.i18n.tr

internal fun createSettingsHomePage(activity: MainActivity): View  = with(activity) createSettingsHomePage@ {
    val container = pageContainer(activity)

    container.addView(settingsHomeHeader())


    container.addView(
        settingsCategoryCard(
            title = tr("歌词获取设置", "Lyrics"),
            subtitle = tr("歌词源、自动保存、下载目录和最近保存的 .lrc。", "Source, auto-save, folder, and recent .lrc files."),
            status = "${localizeText(LyricsSettingsStore.getLyricsSourceTitle(this))} · ${if (LyricsSettingsStore.isAutoSaveLocalEnabled(this)) tr("自动保存", "Auto-save") else tr("不自动保存", "No auto-save")}",
            accent = colorAccentPink,
            iconRes = R.drawable.ic_air_music_note
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.LYRICS)
        }
    )

    container.addView(
        settingsCategoryCard(
            title = tr("系统与权限", "System"),
            subtitle = tr("悬浮窗、通知权限、通知访问权限。", "Overlay and notification permissions."),
            status = permissionSummary(),
            accent = colorAccentMint,
            iconRes = R.drawable.ic_air_shield
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.SYSTEM)
        }
    )

    container.addView(
        settingsCategoryCard(
            title = tr("关于", "About"),
            subtitle = tr("版本号、项目地址、更新记录。", "Version, project link, and changelog."),
            status = "AirLyrics ${getAppVersionName()}",
            accent = colorAccentMint,
            iconRes = R.drawable.ic_air_info
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.ABOUT)
        }
    )


    return scroll(activity, container)
}
