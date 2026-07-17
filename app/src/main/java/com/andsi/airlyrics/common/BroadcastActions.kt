package com.andsi.airlyrics.common

/**
 * Central place for app-local broadcast actions shared by independent components.
 */
object BroadcastActions {
    const val QUICK_CONTROL_CHANGED = "com.andsi.airlyrics.QUICK_CONTROL_CHANGED"
    const val MEDIA_UPDATE = "com.andsi.airlyrics.MEDIA_UPDATE"
    const val MEDIA_SOURCE_LOST = "com.andsi.airlyrics.MEDIA_SOURCE_LOST"
    const val WINDOW_VISIBILITY_CHANGED = "com.andsi.airlyrics.WINDOW_VISIBILITY_CHANGED"

    const val EXTRA_WINDOW_VISIBLE = "windowVisible"
    const val EXTRA_LOCKED = "locked"
    const val EXTRA_CLICK_THROUGH = "clickThrough"
}
