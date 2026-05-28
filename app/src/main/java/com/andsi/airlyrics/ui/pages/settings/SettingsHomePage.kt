package com.andsi.airlyrics.ui.pages.settings

import android.view.View
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.*
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorAccentPink
import com.andsi.airlyrics.ui.theme.colorAccentSoft

internal fun createSettingsHomePage(activity: MainActivity): View  = with(activity) createSettingsHomePage@ {
    val container = pageContainer(activity)

    container.addView(settingsHomeHeader())

    container.addView(
        settingsCategoryCard(
            title = "主题外观",
            subtitle = "白天 / 暗黑模式、主界面配色和视觉预览。",
            status = if (isDarkTheme()) "暗黑模式" else "白天模式",
            accent = colorAccentSoft
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.THEME)
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "悬浮窗设置",
            subtitle = "显示控制、锁定、穿透和歌词气泡样式入口。",
            status = "${FloatingLyricsStyleStore.getPresetTitle(FloatingLyricsStyleStore.getStyle(this).presetName)} · ${if (quickFloatingVisible) "显示中" else "未显示"}",
            accent = colorAccent
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.FLOATING)
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "歌词获取设置",
            subtitle = "歌词源、自动保存、下载目录和最近保存的 .lrc。",
            status = "${LyricsSettingsStore.getLyricsSourceTitle(this)} · ${if (LyricsSettingsStore.isAutoSaveLocalEnabled(this)) "自动保存" else "不自动保存"}",
            accent = colorAccentPink
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.LYRICS)
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "系统与权限",
            subtitle = "悬浮窗、通知权限、通知访问权限。",
            status = permissionSummary(),
            accent = colorAccentMint
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.SYSTEM)
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "关于",
            subtitle = "版本号、项目地址、更新记录。",
            status = "AirLyrics ${getAppVersionName()}",
            accent = colorAccentMint
        ) {
            uiActions.openSettingsSubPage(SettingsSubPage.ABOUT)
        }
    )

    container.addView(smallHint(activity, "设置已经按模块拆分：主题、悬浮窗、歌词获取、系统权限各有自己的页面。"))

    return scroll(activity, container)
}
