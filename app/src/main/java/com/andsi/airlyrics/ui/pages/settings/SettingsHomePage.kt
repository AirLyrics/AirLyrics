package com.andsi.airlyrics.ui.pages.settings

import android.view.View
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.*
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorAccentPink

internal fun createSettingsHomePage(activity: MainActivity): View  = with(activity) createSettingsHomePage@ {
    val container = pageContainer(activity)

    container.addView(settingsHomeHeader())


    container.addView(
        settingsCategoryCard(
            title = "歌词获取设置",
            subtitle = "歌词源、自动保存、下载目录和最近保存的 .lrc。",
            status = "${LyricsSettingsStore.getLyricsSourceTitle(this)} · ${if (LyricsSettingsStore.isAutoSaveLocalEnabled(this)) "自动保存" else "不自动保存"}",
            accent = colorAccentPink,
            iconRes = R.drawable.ic_air_music_note
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.LYRICS)
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "系统与权限",
            subtitle = "悬浮窗、通知权限、通知访问权限。",
            status = permissionSummary(),
            accent = colorAccentMint,
            iconRes = R.drawable.ic_air_shield
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.SYSTEM)
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "关于",
            subtitle = "版本号、项目地址、更新记录。",
            status = "AirLyrics ${getAppVersionName()}",
            accent = colorAccentMint,
            iconRes = R.drawable.ic_air_info
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.ABOUT)
        }
    )


    return scroll(activity, container)
}
