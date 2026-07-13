package com.andsi.airlyrics.common

/**
 * Central place for app-local broadcast / service actions.
 *
 * Keeping action strings here avoids coupling UI and media code to
 * FloatingLyricsService just to share Intent constants.
 */
object BroadcastActions {
    const val SHOW = "com.andsi.airlyrics.SHOW"
    const val HIDE = "com.andsi.airlyrics.HIDE"
    const val LOCK = "com.andsi.airlyrics.LOCK"
    const val UNLOCK = "com.andsi.airlyrics.UNLOCK"
    const val CLICK_THROUGH_ON = "com.andsi.airlyrics.CLICK_THROUGH_ON"
    const val CLICK_THROUGH_OFF = "com.andsi.airlyrics.CLICK_THROUGH_OFF"
    const val NOTIFICATION_TOGGLE_VISIBLE = "com.andsi.airlyrics.NOTIFICATION_TOGGLE_VISIBLE"
    const val NOTIFICATION_TOGGLE_LOCK = "com.andsi.airlyrics.NOTIFICATION_TOGGLE_LOCK"
    const val NOTIFICATION_TOGGLE_CLICK_THROUGH = "com.andsi.airlyrics.NOTIFICATION_TOGGLE_CLICK_THROUGH"
    const val NOTIFICATION_TOGGLE_ADJUST_MODE = "com.andsi.airlyrics.NOTIFICATION_TOGGLE_ADJUST_MODE"
    const val QUICK_CONTROL_CHANGED = "com.andsi.airlyrics.QUICK_CONTROL_CHANGED"
    const val MEDIA_UPDATE = "com.andsi.airlyrics.MEDIA_UPDATE"
    const val MEDIA_SOURCE_LOST = "com.andsi.airlyrics.MEDIA_SOURCE_LOST"
    const val IMPORT_LYRICS = "com.andsi.airlyrics.IMPORT_LYRICS"
    const val SELECT_MEDIA_SOURCE = "com.andsi.airlyrics.SELECT_MEDIA_SOURCE"
    const val APPLY_STYLE = "com.andsi.airlyrics.APPLY_STYLE"
    const val APPLY_AUTO_HIDE_WHEN_PAUSED = "com.andsi.airlyrics.APPLY_AUTO_HIDE_WHEN_PAUSED"
    const val RELOAD_LYRICS = "com.andsi.airlyrics.RELOAD_LYRICS"
    const val RELOAD_ONLINE_LYRICS = "com.andsi.airlyrics.RELOAD_ONLINE_LYRICS"
    const val APPLY_LYRICS_OFFSET = "com.andsi.airlyrics.APPLY_LYRICS_OFFSET"
    const val WINDOW_VISIBILITY_CHANGED = "com.andsi.airlyrics.WINDOW_VISIBILITY_CHANGED"

    const val EXTRA_SOURCE_PACKAGE = "sourcePackage"
    const val EXTRA_WINDOW_VISIBLE = "windowVisible"
    const val EXTRA_LOCKED = "locked"
    const val EXTRA_CLICK_THROUGH = "clickThrough"
    const val EXTRA_OVERWRITE_LYRICS = "overwriteLyrics"
    const val EXTRA_LYRICS_OFFSET_MS = "lyricsOffsetMs"
    const val EXTRA_MEDIA_SNAPSHOT_SEQUENCE = "mediaSnapshotSequence"
}
