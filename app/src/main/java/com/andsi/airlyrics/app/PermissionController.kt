package com.andsi.airlyrics.app

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

internal object PermissionController {
    fun requestOverlayPermission(activity: MainActivity) {
        if (!Settings.canDrawOverlays(activity)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        } else {
            Toast.makeText(activity, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
        }
    }

    fun hasNotificationPermission(activity: MainActivity): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationListenerAccess(activity: MainActivity): Boolean {
        val enabledListeners = Settings.Secure.getString(
            activity.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()

        return enabledListeners.split(':').any { item ->
            item.contains(activity.packageName, ignoreCase = true)
        }
    }

    fun requestNotificationPermissionIfNeeded(activity: MainActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activity, "当前系统不需要单独开启通知权限", Toast.LENGTH_SHORT).show()
            return
        }

        if (hasNotificationPermission(activity)) {
            Toast.makeText(activity, "通知权限已开启", Toast.LENGTH_SHORT).show()
            return
        }

        activity.notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}
