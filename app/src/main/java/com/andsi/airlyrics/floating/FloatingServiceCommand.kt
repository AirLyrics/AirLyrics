package com.andsi.airlyrics.floating

import android.content.Context
import android.content.Intent
import android.net.Uri

internal sealed class FloatingServiceCommand {
    object Restore : FloatingServiceCommand()
    object Show : FloatingServiceCommand()
    object Hide : FloatingServiceCommand()
    object Lock : FloatingServiceCommand()
    object Unlock : FloatingServiceCommand()
    object ClickThroughOn : FloatingServiceCommand()
    object ClickThroughOff : FloatingServiceCommand()
    object ToggleVisibleFromNotification : FloatingServiceCommand()
    object ToggleLockFromNotification : FloatingServiceCommand()
    object ToggleClickThroughFromNotification : FloatingServiceCommand()
    object ToggleAdjustModeFromNotification : FloatingServiceCommand()
    object ApplyAutoHideWhenPaused : FloatingServiceCommand()
    object ApplyDisplayScope : FloatingServiceCommand()
    object ApplyStyle : FloatingServiceCommand()
    object ReloadLyrics : FloatingServiceCommand()
    data class ApplyLyricsOffset(val offsetMs: Long) : FloatingServiceCommand()
    data class SelectMediaSource(val packageName: String?) : FloatingServiceCommand()
    data class ImportPlainLyrics(val uri: Uri, val overwrite: Boolean = true) : FloatingServiceCommand()

    fun toIntent(context: Context): Intent {
        return Intent(context, FloatingLyricsService::class.java).apply {
            actionName()?.let { action = it }
            when (val command = this@FloatingServiceCommand) {
                is ApplyLyricsOffset -> putExtra(EXTRA_LYRICS_OFFSET_MS, command.offsetMs)
                is SelectMediaSource -> putExtra(EXTRA_SOURCE_PACKAGE, command.packageName)
                is ImportPlainLyrics -> {
                    data = command.uri
                    putExtra(EXTRA_OVERWRITE_PLAIN_LYRICS, command.overwrite)
                }
                else -> Unit
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
            ToggleLockFromNotification -> ACTION_NOTIFICATION_TOGGLE_LOCK
            ToggleClickThroughFromNotification -> ACTION_NOTIFICATION_TOGGLE_CLICK_THROUGH
            ToggleAdjustModeFromNotification -> ACTION_NOTIFICATION_TOGGLE_ADJUST_MODE
            ApplyAutoHideWhenPaused -> ACTION_APPLY_AUTO_HIDE_WHEN_PAUSED
            ApplyDisplayScope -> ACTION_APPLY_DISPLAY_SCOPE
            ApplyStyle -> ACTION_APPLY_STYLE
            ReloadLyrics -> ACTION_RELOAD_LYRICS
            is ApplyLyricsOffset -> ACTION_APPLY_LYRICS_OFFSET
            is SelectMediaSource -> ACTION_SELECT_MEDIA_SOURCE
            is ImportPlainLyrics -> ACTION_IMPORT_PLAIN_LYRICS
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
        private const val ACTION_NOTIFICATION_TOGGLE_LOCK = "com.andsi.airlyrics.NOTIFICATION_TOGGLE_LOCK"
        private const val ACTION_NOTIFICATION_TOGGLE_CLICK_THROUGH =
            "com.andsi.airlyrics.NOTIFICATION_TOGGLE_CLICK_THROUGH"
        private const val ACTION_NOTIFICATION_TOGGLE_ADJUST_MODE =
            "com.andsi.airlyrics.NOTIFICATION_TOGGLE_ADJUST_MODE"
        private const val ACTION_APPLY_AUTO_HIDE_WHEN_PAUSED =
            "com.andsi.airlyrics.APPLY_AUTO_HIDE_WHEN_PAUSED"
        private const val ACTION_APPLY_DISPLAY_SCOPE =
            "com.andsi.airlyrics.APPLY_DISPLAY_SCOPE"
        private const val ACTION_APPLY_STYLE = "com.andsi.airlyrics.APPLY_STYLE"
        private const val ACTION_RELOAD_LYRICS = "com.andsi.airlyrics.RELOAD_LYRICS"
        private const val ACTION_APPLY_LYRICS_OFFSET = "com.andsi.airlyrics.APPLY_LYRICS_OFFSET"
        private const val ACTION_SELECT_MEDIA_SOURCE = "com.andsi.airlyrics.SELECT_MEDIA_SOURCE"
        private const val ACTION_IMPORT_PLAIN_LYRICS = "com.andsi.airlyrics.IMPORT_LYRICS"

        private const val EXTRA_SOURCE_PACKAGE = "sourcePackage"
        private const val EXTRA_OVERWRITE_PLAIN_LYRICS = "overwriteLyrics"
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
                ACTION_NOTIFICATION_TOGGLE_LOCK -> ToggleLockFromNotification
                ACTION_NOTIFICATION_TOGGLE_CLICK_THROUGH -> ToggleClickThroughFromNotification
                ACTION_NOTIFICATION_TOGGLE_ADJUST_MODE -> ToggleAdjustModeFromNotification
                ACTION_APPLY_AUTO_HIDE_WHEN_PAUSED -> ApplyAutoHideWhenPaused
                ACTION_APPLY_DISPLAY_SCOPE -> ApplyDisplayScope
                ACTION_APPLY_STYLE -> ApplyStyle
                ACTION_RELOAD_LYRICS -> ReloadLyrics
                ACTION_APPLY_LYRICS_OFFSET -> ApplyLyricsOffset(
                    intent.getLongExtra(EXTRA_LYRICS_OFFSET_MS, 0L)
                )
                ACTION_SELECT_MEDIA_SOURCE -> SelectMediaSource(
                    intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
                )
                ACTION_IMPORT_PLAIN_LYRICS -> intent.data?.let { uri ->
                    ImportPlainLyrics(
                        uri = uri,
                        overwrite = intent.getBooleanExtra(EXTRA_OVERWRITE_PLAIN_LYRICS, true)
                    )
                }
                else -> null
            }
        }
    }
}
