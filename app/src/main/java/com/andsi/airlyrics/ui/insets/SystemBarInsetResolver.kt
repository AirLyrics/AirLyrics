package com.andsi.airlyrics.ui.insets

import android.view.View

/**
 * Returns only the part of the top inset that has not already been consumed by
 * the window/content parent. This avoids double top spacing after AppCompat
 * recreates the activity during locale changes.
 */
internal fun View.remainingTopSystemInset(topInset: Int): Int {
    if (topInset <= 0) return 0

    val location = IntArray(2)
    getLocationOnScreen(location)
    val alreadyConsumedTop = location[1].coerceAtLeast(0)
    return (topInset - alreadyConsumedTop).coerceAtLeast(0)
}
