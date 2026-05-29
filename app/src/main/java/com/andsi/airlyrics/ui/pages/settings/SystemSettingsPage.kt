package com.andsi.airlyrics.ui.pages.settings

import android.provider.Settings
import android.view.View
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.*
import com.andsi.airlyrics.ui.components.*

internal fun createSystemSettingsPage(activity: MainActivity): View  = with(activity) createSystemSettingsPage@ {
    val container = pageContainer(activity)
    container.addView(settingsBackHeader("系统与权限", "让悬浮歌词能正常出现、读取媒体状态，并保持前台服务稳定。"))

    container.addView(
        card(activity) {
            addView(bigText(activity, "权限状态"))
            addView(settingRow(activity, "悬浮窗权限", if (Settings.canDrawOverlays(activity)) "已开启" else "未开启"))
            addView(settingRow(activity, "通知权限", if (hasNotificationPermission()) "已开启" else "未开启"))
            addView(settingRow(activity, "通知访问权限", if (hasNotificationListenerAccess()) "已开启" else "未开启"))
        }
    )

    container.addView(
        card(activity) {
            addView(bigText(activity, "快捷入口"))
            addView(horizontalButtons(activity, 
                "悬浮窗权限" to { uiActions.requestOverlayPermission() },
                "通知权限" to { uiActions.requestNotificationPermission() }
            ))
            addView(actionButton(activity, "打开通知访问设置") {
                uiActions.openNotificationListenerSettings()
            })
        }
    )

    return scroll(activity, container)
}
