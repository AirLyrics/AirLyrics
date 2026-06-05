package com.andsi.airlyrics.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity

internal object PermissionController {
    fun requestOverlayPermission(activity: AppCompatActivity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationListenerAccess(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()

        return enabledListeners.split(':').any { item ->
            item.contains(context.packageName, ignoreCase = true)
        }
    }

    fun requestNotificationPermissionIfNeeded(
        activity: AppCompatActivity,
        requestPermission: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openAppNotificationSettings(activity)
            return
        }

        if (hasNotificationPermission(activity)) {
            openAppNotificationSettings(activity)
            return
        }

        requestPermission(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openAppNotificationSettings(activity: AppCompatActivity) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}")
            )
        }
        activity.startActivity(intent)
    }
}
