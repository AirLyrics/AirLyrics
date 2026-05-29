package com.andsi.airlyrics.ui.pages.settings

import android.view.View
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.*
import com.andsi.airlyrics.ui.components.*

internal fun createAboutSettingsPage(activity: MainActivity): View  = with(activity) createAboutSettingsPage@ {
    val container = pageContainer(activity)
    container.addView(settingsBackHeader("关于", "轻量的 Android 悬浮歌词工具。"))

    container.addView(
        card(activity) {
            addView(bigText(activity, "AirLyrics"))
            addView(normalText(activity, "版本号：${getAppVersionName()}"))
            addView(actionButton(activity, "打开项目地址") {
                uiActions.openUrl("https://github.com/AndSi-327/android-floating-lyrics")
            })
        }
    )

    container.addView(
        card(activity) {
            addView(bigText(activity, "功能概览"))
            addView(changelogItem("悬浮歌词", "在其他应用上方显示当前歌词，并支持外观自定义。"))
            addView(changelogItem("歌词来源", "支持本地歌词、网易云音乐和 Musixmatch。"))
            addView(changelogItem("本地管理", "可导入 .lrc 歌词，也可自动保存在线匹配结果。"))
            addView(changelogItem("显示偏好", "支持原文、翻译、显示范围和歌词切换动画设置。"))
        }
    )

    return scroll(activity, container)
}
