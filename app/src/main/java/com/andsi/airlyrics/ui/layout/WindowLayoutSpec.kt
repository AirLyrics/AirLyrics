package com.andsi.airlyrics.ui.layout

/** All dimensions here are dp in the current window, never the physical display. */
internal data class WindowLayoutSpec(val width: Float, val height: Float, val fontScale: Float) {
    val useRail: Boolean get() = width >= 600f
    val compact: Boolean get() = height < 480f
    fun columns(contentWidth: Float): Int =
        if (contentWidth >= 720f * fontScale.coerceAtLeast(1f) + GAP) 2 else 1

    companion object {
        const val RAIL = 112
        const val SINGLE_MAX = 760
        const val DOUBLE_MAX = 1200
        const val GAP = 24
        const val DIALOG_MAX = 560
        const val LARGE_DIALOG_MAX = 840
    }
}
