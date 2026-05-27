package com.andsi.airlyrics.ui.pages.settings

import android.content.Intent
import android.provider.Settings
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import android.view.View
import com.andsi.airlyrics.MainActivity
import com.andsi.airlyrics.ui.components.*

internal fun MainActivity.createSystemSettingsPage(): View {
    val container = pageContainer()
    container.addView(settingsBackHeader("系统与权限", "让悬浮歌词能正常出现、读取媒体状态，并保持前台服务稳定。"))

    container.addView(
        card {
            addView(bigText("权限状态"))
            addView(settingRow("悬浮窗权限", if (Settings.canDrawOverlays(this@createSystemSettingsPage)) "已开启" else "未开启"))
            addView(settingRow("通知权限", if (hasNotificationPermission()) "已开启" else "未开启"))
            addView(settingRow("通知访问权限", if (hasNotificationListenerAccess()) "已开启" else "未开启"))
            addView(smallHint("通知访问权限负责读取媒体控制器；悬浮窗权限负责把歌词盖在其他 App 上。"))
        }
    )

    container.addView(
        card {
            addView(bigText("快捷入口"))
            addView(horizontalButtons(
                "悬浮窗权限" to { requestOverlayPermission() },
                "通知权限" to { requestNotificationPermissionIfNeeded() }
            ))
            addView(actionButton("打开通知访问设置") {
                startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        }
    )

    return scroll(container)
}
