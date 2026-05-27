package com.andsi.airlyrics.ui.pages.settings

import android.view.View
import com.andsi.airlyrics.MainActivity
import com.andsi.airlyrics.ui.components.*

internal fun MainActivity.createAboutSettingsPage(): View {
    val container = pageContainer()
    container.addView(settingsBackHeader("关于", "一些和 AirLyrics 有关的小纸条。"))

    container.addView(
        card {
            addView(bigText("AirLyrics"))
            addView(normalText("版本号：${getAppVersionName()}"))
            addView(normalText("包名：$packageName"))
            addView(actionButton("打开项目地址") {
                openUrl("https://github.com/AndSi-327/android-floating-lyrics")
            })
        }
    )

    container.addView(
        card {
            addView(bigText("更改日志"))
            addView(changelogItem("设置页模块化", "主题外观、悬浮窗设置、歌词获取设置分别拆成独立页面文件。"))
            addView(changelogItem("歌词源架构", "歌词查找已接入 LyricsProvider / LyricsRepository。"))
            addView(changelogItem("本地歌词管理", "支持自动保存开关、保存目录展示和最近下载歌词列表。"))
            addView(changelogItem("轻飘飘视觉", "整体颜色改成奶油底、淡粉蓝按钮和柔软卡片。"))
        }
    )

    return scroll(container)
}
