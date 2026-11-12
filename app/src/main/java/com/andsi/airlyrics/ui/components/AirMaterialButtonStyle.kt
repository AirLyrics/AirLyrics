package com.andsi.airlyrics.ui.components

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt

/** Keep Material's shape, ripple and insets while using the app's button preference. */
internal fun MaterialButton.styleAirTonalButton(tone: AirTonalButtonTone = AirTonalButtonTone.DEFAULT) {
    applyAirButtonColors(context.airTonalButtonStyle(tone))
}

/** Inline disclosures and navigation actions keep their text-only hierarchy in either style. */
internal fun MaterialButton.styleAirTextButton() {
    val dark = ThemeSettingsStore.isDark(context)
    val palette = AirLyricsTheme.palette(dark, ThemeSettingsStore.getAccent(context))
    val foreground = if (dark) palette.accent else ColorUtils.blendARGB(palette.accent, palette.textStrong, 0.6f)
    applyAirButtonColors(AirTonalButtonStyle(Color.TRANSPARENT, Color.TRANSPARENT, foreground, 0))
}

private fun MaterialButton.applyAirButtonColors(style: AirTonalButtonStyle) {
    fun colors(enabled: Int, disabled: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabled, enabled)
    )
    fun subdued(color: Int, alpha: Float) =
        ColorUtils.setAlphaComponent(color, (Color.alpha(color) * alpha).roundToInt())

    backgroundTintList = colors(style.fillColor, subdued(style.fillColor, 0.38f))
    strokeWidth = (style.strokeWidthDp * resources.displayMetrics.density).roundToInt()
    strokeColor = colors(style.strokeColor, subdued(style.strokeColor, 0.38f))
    val foreground = colors(style.textColor, subdued(style.textColor, 0.38f))
    setTextColor(foreground)
    iconTint = foreground
    rippleColor = colors(subdued(style.textColor, 0.16f), Color.TRANSPARENT)
}
