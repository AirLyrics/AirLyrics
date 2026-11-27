package com.andsi.airlyrics.ui.layout

import com.andsi.airlyrics.design.tokens.AirUiTokens

internal data class WindowLayoutSpec(val widthDp: Float, val heightDp: Float, val fontScale: Float) {
    val compact: Boolean get() = heightDp < 480f

    val railWidthDp: Int
        get() = when {
            widthDp >= 900f && !compact -> WIDE_RAIL_WIDTH_DP
            widthDp >= 620f && compact -> 84
            else -> 0
        }

    val pageInsetDp: Int
        get() = AirUiTokens.Space.PageH + if (railWidthDp == WIDE_RAIL_WIDTH_DP) AirUiTokens.Space.Sm else 0

    fun usesTwoColumns(contentWidthDp: Float): Boolean =
        railWidthDp == WIDE_RAIL_WIDTH_DP && contentWidthDp >= 720f * fontScale.coerceAtLeast(1f) + COLUMN_GAP_DP

    companion object {
        private const val WIDE_RAIL_WIDTH_DP = 104
        const val PAGE_MAX_WIDTH_DP = 1160
        const val COLUMN_GAP_DP = 24
        const val DIALOG_MAX = 560
        const val LARGE_DIALOG_MAX = 840
    }
}
