package com.andsi.airlyrics.core.color

import android.graphics.Color

internal object AirColorUtils {
    fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    fun opaqueRgb(color: Int): Int {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
    }

    fun isDarkColor(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color))
        return luminance < 150
    }

    fun colorSummary(color: Int): String {
        return "R${Color.red(color)} G${Color.green(color)} B${Color.blue(color)} A${Color.alpha(color)}"
    }
}
