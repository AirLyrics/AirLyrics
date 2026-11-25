package com.andsi.airlyrics.ui.layout

internal data class WindowLayoutSpec(val widthDp: Float, val heightDp: Float, val fontScale: Float) {
    val useRail: Boolean get() = widthDp >= 600f
    val compact: Boolean get() = heightDp < 480f
    fun columns(contentWidthDp: Float): Int =
        if (contentWidthDp >= 720f * fontScale.coerceAtLeast(1f) + GAP) 2 else 1

    companion object {
        const val RAIL = 112
        const val SINGLE_MAX = 760
        const val DOUBLE_MAX = 1200
        const val GAP = 24
        const val DIALOG_MAX = 560
        const val LARGE_DIALOG_MAX = 840
    }
}
