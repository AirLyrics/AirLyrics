package com.andsi.airlyrics.ui.pages.settings

import android.view.View
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.*
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.ui.components.*

internal fun createFloatingSettingsPage(activity: MainActivity): View  = with(activity) createFloatingSettingsPage@ {
    val container = pageContainer(activity)
    val style = FloatingLyricsStyleStore.getStyle(this)

    container.addView(settingsBackHeader("悬浮窗设置", "这里放全局入口；更细的字体、颜色、气泡和布局在悬浮窗页调整。"))

    container.addView(
        card(activity) {
            addView(bigText(activity, "状态"))
            addView(settingRow(activity, "悬浮窗", if (quickFloatingVisible) "显示中" else "未显示"))
            addView(settingRow(activity, "锁定拖动", if (locked) "已锁定" else "可拖动"))
            addView(settingRow(activity, "点击穿透", if (clickThrough) "已开启" else "已关闭"))
            addView(settingRow(activity, "皮肤预设", FloatingLyricsStyleStore.getPresetTitle(style.presetName)))
        }
    )

    container.addView(
        card(activity) {
            addView(bigText(activity, "快速控制"))
            addView(horizontalButtons(activity, 
                "显示" to { uiActions.showFloatingLyrics() },
                "隐藏" to { uiActions.hideFloatingLyrics() }
            ))
            addView(horizontalButtons(activity, 
                floatingLockButtonText() to { uiActions.toggleLock(); renderCurrentPage() },
                floatingClickThroughButtonText() to { uiActions.toggleClickThrough(); renderCurrentPage() }
            ))
            addView(actionButton(activity, "重新加载当前歌词") {
                uiActions.reloadFloatingLyrics()
            })
        }
    )

    container.addView(
        card(activity) {
            addView(bigText(activity, "详细调节"))
            addView(normalText(activity, floatingPreviewSummary(style)))
            addView(actionButton(activity, "打开悬浮窗详细调节页") {
                uiActions.selectPage(Page.FLOATING)
            })
            addView(smallHint(activity, "详细调节页包含字体大小、文字颜色、背景气泡、宽度、内边距、圆角和阴影。"))
        }
    )

    return scroll(activity, container)
}
