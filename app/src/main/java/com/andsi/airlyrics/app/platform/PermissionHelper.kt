package com.andsi.airlyrics.app.platform

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri

internal object PermissionHelper {
    fun requestOverlayPermission(activity: AppCompatActivity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${activity.packageName}".toUri()
        )
        activity.startActivity(intent)
    }

    fun hasPostNotificationsPermission(context: Context): Boolean {
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

    @Suppress("DEPRECATION")
    fun hasUsageStatsAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(activity: AppCompatActivity) {
        val appDetailsIntent = Intent(
            Settings.ACTION_USAGE_ACCESS_SETTINGS,
            "package:${activity.packageName}".toUri()
        )
        runCatching { activity.startActivity(appDetailsIntent) }
            .onFailure {
                activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
    }

    fun requestNotificationPermissionIfNeeded(
        activity: AppCompatActivity,
        requestPermission: (String) -> Unit
    ) {
        if (hasPostNotificationsPermission(activity)) {
            openAppNotificationSettings(activity)
            return
        }

        requestPermission(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openAppNotificationSettings(activity: AppCompatActivity) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        }
        activity.startActivity(intent)
    }
}
