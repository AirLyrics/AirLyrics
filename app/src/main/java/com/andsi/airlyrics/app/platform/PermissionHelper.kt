package com.andsi.airlyrics.app.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.andsi.airlyrics.displayscope.DisplayScopeCapability

internal object PermissionHelper {
    fun requestOverlayPermission(activity: AppCompatActivity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${activity.packageName}".toUri()
        )
        activity.startActivity(intent)
    }

    /** Whether this app can currently display notifications, across Android versions. */
    fun hasPostNotificationsPermission(context: Context): Boolean {
        return !needsPostNotificationsPermission(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
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

    fun hasUsageStatsAccess(context: Context): Boolean {
        return DisplayScopeCapability.hasUsageAccess(context)
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
        if (needsPostNotificationsPermission(activity)) {
            requestPermission(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openAppNotificationSettings(activity)
        }
    }

    private fun needsPostNotificationsPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    }

    private fun openAppNotificationSettings(activity: AppCompatActivity) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        }
        activity.startActivity(intent)
    }
}
