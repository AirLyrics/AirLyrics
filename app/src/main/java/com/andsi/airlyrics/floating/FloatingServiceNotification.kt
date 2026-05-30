package com.andsi.airlyrics.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.i18n.localizeText

/**
 * Builds the foreground-service notification used by the floating lyrics service.
 *
 * The notification doubles as the always-available remote control for the floating
 * window, so users can unlock / move / restore click-through without opening the app.
 */
object FloatingServiceNotification {
    const val NOTIFICATION_ID = 1

    private const val CHANNEL_ID = "floating_lyrics"
    private const val CHANNEL_NAME = "Floating Lyrics"

    data class QuickControlState(
        val visible: Boolean,
        val locked: Boolean,
        val clickThrough: Boolean,
        val feedback: String? = null
    )

    fun create(context: Context, state: QuickControlState): Notification {
        ensureChannel(context)

        val contentText = state.feedback?.let { "${state.summary(context)} · ${context.localizeText(it)}" } ?: state.summary(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.localizeText("AirLyrics 悬浮歌词"))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_air_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF64B5F6.toInt())
            .setColorized(false)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_view,
                    state.actionTitle(context, active = state.visible, activeText = "显示", inactiveText = "显示"),
                    serviceActionIntent(context, BroadcastActions.NOTIFICATION_TOGGLE_VISIBLE, 1001)
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_manage,
                    state.adjustModeActionTitle(context),
                    serviceActionIntent(context, BroadcastActions.NOTIFICATION_TOGGLE_ADJUST_MODE, 1002)
                ).build()
            )

        openAppIntent(context)?.let { builder.setContentIntent(it) }

        return builder.build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun serviceActionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, FloatingLyricsService::class.java).apply {
            this.action = action
        }

        return PendingIntent.getService(
            context,
            requestCode,
            intent,
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
        val visibleText = if (visible) context.localizeText("已显示") else context.localizeText("已隐藏")
        return "$visibleText · ${windowModeText(context)}"
    }

    private val QuickControlState.isAdjustMode: Boolean
        get() = !locked && !clickThrough

    private fun QuickControlState.windowModeText(context: Context): CharSequence {
        return when {
            !locked && !clickThrough -> context.localizeText("调整模式")
            locked && clickThrough -> context.localizeText("锁定穿透")
            locked && !clickThrough -> context.localizeText("已锁定 · 可触摸")
            else -> context.localizeText("可拖动 · 已穿透")
        }
    }

    private fun QuickControlState.adjustModeActionTitle(context: Context): String {
        return if (isAdjustMode) "● ${context.localizeText("调整模式")}" else "○ ${context.localizeText("调整模式")}"
    }

    private fun QuickControlState.actionTitle(
        context: Context,
        active: Boolean,
        activeText: String,
        inactiveText: String
    ): String {
        return if (active) "● ${context.localizeText(activeText)}" else "○ ${context.localizeText(inactiveText)}"
    }
}
