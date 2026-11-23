package com.andsi.airlyrics.ui.pages.settings

internal fun heightRevealContentAlpha(
    visibleHeight: Int,
    fullyVisibleHeight: Int,
    expandedHeight: Int
): Float {
    if (visibleHeight >= expandedHeight) return 1f
    if (visibleHeight <= fullyVisibleHeight) return 0f
    return ((visibleHeight - fullyVisibleHeight).toFloat() /
        (expandedHeight - fullyVisibleHeight).coerceAtLeast(1)).coerceIn(0f, 1f)
}
