package com.andsi.airlyrics.floating

import android.content.Context
import android.content.Intent

internal sealed class FloatingServiceCommand {
    object Restore : FloatingServiceCommand()
    object Show : FloatingServiceCommand()
    object Hide : FloatingServiceCommand()
    object Lock : FloatingServiceCommand()
    object Unlock : FloatingServiceCommand()
    object ClickThroughOn : FloatingServiceCommand()
    object ClickThroughOff : FloatingServiceCommand()
    object ToggleVisibleFromNotification : FloatingServiceCommand()
    object ToggleAdjustModeFromNotification : FloatingServiceCommand()
    object ApplyAutoHideSettings : FloatingServiceCommand()
    object ApplyDisplayScope : FloatingServiceCommand()
    object ApplyStyle : FloatingServiceCommand()
    object RefreshText : FloatingServiceCommand()
    object ReloadLyrics : FloatingServiceCommand()
    data class ApplyLyricsOffset(val offsetMs: Long) : FloatingServiceCommand()
    object SyncMediaSource : FloatingServiceCommand()

    fun toIntent(context: Context): Intent {
        return Intent(context, FloatingLyricsService::class.java).apply {
            actionName()?.let { action = it }
            (this@FloatingServiceCommand as? ApplyLyricsOffset)?.offsetMs?.let { offsetMs ->
                putExtra(EXTRA_LYRICS_OFFSET_MS, offsetMs)
            }
        }
    }

    private fun actionName(): String? {
        return when (this) {
            Restore -> null
            Show -> ACTION_SHOW
            Hide -> ACTION_HIDE
            Lock -> ACTION_LOCK
            Unlock -> ACTION_UNLOCK
            ClickThroughOn -> ACTION_CLICK_THROUGH_ON
            ClickThroughOff -> ACTION_CLICK_THROUGH_OFF
            ToggleVisibleFromNotification -> ACTION_NOTIFICATION_TOGGLE_VISIBLE
            ToggleAdjustModeFromNotification -> ACTION_NOTIFICATION_TOGGLE_ADJUST_MODE
            ApplyAutoHideSettings -> ACTION_APPLY_AUTO_HIDE_SETTINGS
            ApplyDisplayScope -> ACTION_APPLY_DISPLAY_SCOPE
            ApplyStyle -> ACTION_APPLY_STYLE
            RefreshText -> ACTION_REFRESH_TEXT
            ReloadLyrics -> ACTION_RELOAD_LYRICS
            is ApplyLyricsOffset -> ACTION_APPLY_LYRICS_OFFSET
            SyncMediaSource -> ACTION_SELECT_MEDIA_SOURCE
        }
    }

    companion object {
        private const val ACTION_SHOW = "com.andsi.airlyrics.SHOW"
        private const val ACTION_HIDE = "com.andsi.airlyrics.HIDE"
        private const val ACTION_LOCK = "com.andsi.airlyrics.LOCK"
        private const val ACTION_UNLOCK = "com.andsi.airlyrics.UNLOCK"
        private const val ACTION_CLICK_THROUGH_ON = "com.andsi.airlyrics.CLICK_THROUGH_ON"
        private const val ACTION_CLICK_THROUGH_OFF = "com.andsi.airlyrics.CLICK_THROUGH_OFF"
        private const val ACTION_NOTIFICATION_TOGGLE_VISIBLE = "com.andsi.airlyrics.NOTIFICATION_TOGGLE_VISIBLE"
        private const val ACTION_NOTIFICATION_TOGGLE_ADJUST_MODE =
            "com.andsi.airlyrics.NOTIFICATION_TOGGLE_ADJUST_MODE"
        private const val ACTION_APPLY_AUTO_HIDE_SETTINGS =
            "com.andsi.airlyrics.APPLY_AUTO_HIDE_SETTINGS"
        private const val ACTION_APPLY_DISPLAY_SCOPE =
            "com.andsi.airlyrics.APPLY_DISPLAY_SCOPE"
        private const val ACTION_APPLY_STYLE = "com.andsi.airlyrics.APPLY_STYLE"
        private const val ACTION_REFRESH_TEXT = "com.andsi.airlyrics.REFRESH_TEXT"
        private const val ACTION_RELOAD_LYRICS = "com.andsi.airlyrics.RELOAD_LYRICS"
        private const val ACTION_APPLY_LYRICS_OFFSET = "com.andsi.airlyrics.APPLY_LYRICS_OFFSET"
        private const val ACTION_SELECT_MEDIA_SOURCE = "com.andsi.airlyrics.SELECT_MEDIA_SOURCE"

        private const val EXTRA_LYRICS_OFFSET_MS = "lyricsOffsetMs"

        fun fromIntent(intent: Intent?): FloatingServiceCommand? {
            return when (intent?.action ?: return Restore) {
                ACTION_SHOW -> Show
                ACTION_HIDE -> Hide
                ACTION_LOCK -> Lock
                ACTION_UNLOCK -> Unlock
                ACTION_CLICK_THROUGH_ON -> ClickThroughOn
                ACTION_CLICK_THROUGH_OFF -> ClickThroughOff
                ACTION_NOTIFICATION_TOGGLE_VISIBLE -> ToggleVisibleFromNotification
                ACTION_NOTIFICATION_TOGGLE_ADJUST_MODE -> ToggleAdjustModeFromNotification
                ACTION_APPLY_AUTO_HIDE_SETTINGS -> ApplyAutoHideSettings
                ACTION_APPLY_DISPLAY_SCOPE -> ApplyDisplayScope
                ACTION_APPLY_STYLE -> ApplyStyle
                ACTION_REFRESH_TEXT -> RefreshText
                ACTION_RELOAD_LYRICS -> ReloadLyrics
                ACTION_APPLY_LYRICS_OFFSET -> ApplyLyricsOffset(
                    intent.getLongExtra(EXTRA_LYRICS_OFFSET_MS, 0L)
                )
                ACTION_SELECT_MEDIA_SOURCE -> SyncMediaSource
                else -> null
            }
        }
    }
}
