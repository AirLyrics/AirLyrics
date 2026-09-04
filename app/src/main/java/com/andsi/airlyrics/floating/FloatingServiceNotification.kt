package com.andsi.airlyrics.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.theme.ThemeAccentPalettes
import com.andsi.airlyrics.displayscope.DisplayScopeBlockReason
import com.andsi.airlyrics.settings.store.ThemeSettingsStore

/**
 * Builds the foreground-service notification used by the floating lyrics service.
 *
 * The notification doubles as the always-available remote control for the floating
 * window, so users can unlock / move / restore click-through without opening the app.
 */
internal object FloatingServiceNotification {
    const val NOTIFICATION_ID = 1

    private const val CHANNEL_ID = "floating_lyrics"
    private const val CHANNEL_NAME = "Floating Lyrics"

    data class QuickControlState(
        val visible: Boolean,
        val desiredVisible: Boolean = visible,
        val locked: Boolean,
        val clickThrough: Boolean,
        val displayScopeBlockReason: DisplayScopeBlockReason? = null,
        val feedback: String? = null
    )

    fun create(context: Context, state: QuickControlState): Notification {
        ensureChannel(context)

        val contentText = state.feedback?.let { "${state.summary(context)} · $it" } ?: state.summary(context)
        val accentColor = ThemeAccentPalettes.resolve(
            accent = ThemeSettingsStore.getAccent(context),
            isDark = ThemeSettingsStore.isDark(context)
        ).accent

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.ui_airlyrics_floating_lyrics))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_air_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(accentColor)
            .setColorized(false)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_air_visibility,
                    context.getText(if (state.desiredVisible) R.string.ui_hide else R.string.ui_show),
                    serviceActionIntent(context, FloatingServiceCommand.ToggleVisibleFromNotification, 1001)
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_air_open_with,
                    context.getText(R.string.ui_adjustment_mode),
                    serviceActionIntent(context, FloatingServiceCommand.ToggleAdjustModeFromNotification, 1002)
                ).build()
            )

        openAppIntent(context)?.let { builder.setContentIntent(it) }

        return builder.build()
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun serviceActionIntent(
        context: Context,
        command: FloatingServiceCommand,
        requestCode: Int
    ): PendingIntent {
        return PendingIntent.getService(
            context,
            requestCode,
            command.toIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: return null

        return PendingIntent.getActivity(
            context,
            1000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun QuickControlState.summary(context: Context): String {
        val visibleText = when {
            visible -> context.getString(R.string.ui_shown)
            desiredVisible && displayScopeBlockReason == DisplayScopeBlockReason.USAGE_ACCESS_REQUIRED ->
                context.getString(R.string.ui_usage_access_required)
            desiredVisible && displayScopeBlockReason == DisplayScopeBlockReason.WAITING_FOR_SELECTED_APP ->
                context.getString(R.string.ui_waiting_for_selected_app)
            else -> context.getString(R.string.ui_hidden)
        }
        return "$visibleText · ${windowModeText(context)}"
    }

    private fun QuickControlState.windowModeText(context: Context): CharSequence {
        val messageRes = when {
            !locked && !clickThrough -> R.string.ui_adjustment_mode
            locked && clickThrough -> R.string.ui_lock_touch
            locked && !clickThrough -> R.string.ui_locked_touchable
            else -> R.string.ui_draggable_click_through
        }
        return context.getText(messageRes)
    }
}
