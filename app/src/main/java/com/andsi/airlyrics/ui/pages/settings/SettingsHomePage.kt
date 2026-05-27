package com.andsi.airlyrics.ui.pages.settings

import android.view.View
import com.andsi.airlyrics.MainActivity
import com.andsi.airlyrics.MainActivity.SettingsSubPage
import com.andsi.airlyrics.core.settings.FloatingLyricsStyleStore
import com.andsi.airlyrics.core.settings.LyricsSettingsStore
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorAccentPink
import com.andsi.airlyrics.ui.theme.colorAccentSoft

internal fun MainActivity.createSettingsHomePage(): View {
    val container = pageContainer()

    container.addView(settingsHomeHeader())

    container.addView(
        settingsCategoryCard(
            title = "主题外观",
            subtitle = "白天 / 暗黑模式、主界面配色和视觉预览。",
            status = if (isDarkTheme()) "暗黑模式" else "白天模式",
            accent = colorAccentSoft
        ) {
            settingsSubPage = SettingsSubPage.THEME
            renderCurrentPage()
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "悬浮窗设置",
            subtitle = "显示控制、锁定、穿透和歌词气泡样式入口。",
            status = "${FloatingLyricsStyleStore.getPresetTitle(FloatingLyricsStyleStore.getStyle(this).presetName)} · ${if (quickFloatingVisible) "显示中" else "未显示"}",
            accent = colorAccent
        ) {
            settingsSubPage = SettingsSubPage.FLOATING
            renderCurrentPage()
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "歌词获取设置",
            subtitle = "歌词源、自动保存、下载目录和最近保存的 .lrc。",
            status = "${LyricsSettingsStore.getLyricsSourceTitle(this)} · ${if (LyricsSettingsStore.isAutoSaveLocalEnabled(this)) "自动保存" else "不自动保存"}",
            accent = colorAccentPink
        ) {
            settingsSubPage = SettingsSubPage.LYRICS
            renderCurrentPage()
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "系统与权限",
            subtitle = "悬浮窗、通知权限、通知访问权限。",
            status = permissionSummary(),
            accent = colorAccentMint
        ) {
            settingsSubPage = SettingsSubPage.SYSTEM
            renderCurrentPage()
        }
    )

    container.addView(
        settingsCategoryCard(
            title = "关于",
            subtitle = "版本号、项目地址、更新记录。",
            status = "AirLyrics ${getAppVersionName()}",
            accent = colorAccentMint
        ) {
            settingsSubPage = SettingsSubPage.ABOUT
            renderCurrentPage()
        }
    )

    container.addView(smallHint("设置已经按模块拆分：主题、悬浮窗、歌词获取、系统权限各有自己的页面。"))

    return scroll(container)
}
