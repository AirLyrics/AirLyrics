package com.andsi.airlyrics.ui.pages.settings

import android.view.View
import com.andsi.airlyrics.MainActivity
import com.andsi.airlyrics.MainActivity.Page
import com.andsi.airlyrics.core.settings.FloatingLyricsStyleStore
import com.andsi.airlyrics.ui.components.*

internal fun MainActivity.createFloatingSettingsPage(): View {
    val container = pageContainer()
    val style = FloatingLyricsStyleStore.getStyle(this)

    container.addView(settingsBackHeader("悬浮窗设置", "这里放全局入口；更细的字体、颜色、气泡和布局在悬浮窗页调整。"))

    container.addView(
        card {
            addView(bigText("状态"))
            addView(settingRow("悬浮窗", if (quickFloatingVisible) "显示中" else "未显示"))
            addView(settingRow("锁定拖动", if (locked) "已锁定" else "可拖动"))
            addView(settingRow("点击穿透", if (clickThrough) "已开启" else "已关闭"))
            addView(settingRow("皮肤预设", FloatingLyricsStyleStore.getPresetTitle(style.presetName)))
        }
    )

    container.addView(
        card {
            addView(bigText("快速控制"))
            addView(horizontalButtons(
                "显示" to { showFloatingLyrics() },
                "隐藏" to { hideFloatingLyrics() }
            ))
            addView(horizontalButtons(
                floatingLockButtonText() to { toggleLock(); renderCurrentPage() },
                floatingClickThroughButtonText() to { toggleClickThrough(); renderCurrentPage() }
            ))
            addView(actionButton("重新加载当前歌词") {
                reloadFloatingLyrics()
            })
        }
    )

    container.addView(
        card {
            addView(bigText("详细调节"))
            addView(normalText(floatingPreviewSummary(style)))
            addView(actionButton("打开悬浮窗详细调节页") {
                currentPage = Page.FLOATING
                renderCurrentPage()
            })
            addView(smallHint("详细调节页包含字体大小、文字颜色、背景气泡、宽度、内边距、圆角和阴影。"))
        }
    )

    return scroll(container)
}
