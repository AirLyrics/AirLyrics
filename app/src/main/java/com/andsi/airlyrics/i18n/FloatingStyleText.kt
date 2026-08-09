package com.andsi.airlyrics.i18n

import android.content.Context
import android.view.Gravity
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.FloatingLyricsFontWeight

internal fun Context.localizedFloatingPresetTitle(key: String): String = when (key) {
    "subtitle" -> getString(R.string.ui_clean_letters)
    "bubble" -> getString(R.string.ui_vinyl_bubble)
    else -> getString(R.string.ui_vinyl_bubble)
}

internal fun Context.localizedFloatingGravityTitle(gravity: Int): String = when (gravity) {
    Gravity.START or Gravity.CENTER_VERTICAL -> getString(R.string.ui_left)
    Gravity.END or Gravity.CENTER_VERTICAL -> getString(R.string.ui_right)
    else -> getString(R.string.ui_center)
}

internal fun Context.localizedFloatingFontFamilyTitle(fontFamily: FloatingLyricsFontFamily): String =
    when (fontFamily) {
        FloatingLyricsFontFamily.SYSTEM_DEFAULT -> getString(R.string.ui_system_default_font)
        FloatingLyricsFontFamily.SANS_SERIF -> getString(R.string.ui_sans_serif)
        FloatingLyricsFontFamily.SERIF -> getString(R.string.ui_serif)
        FloatingLyricsFontFamily.MONOSPACE -> getString(R.string.ui_monospace)
        FloatingLyricsFontFamily.CUSTOM -> getString(R.string.ui_custom_font)
    }

internal fun Context.localizedFloatingFontWeightTitle(fontWeight: Int): String {
    val weight = FloatingLyricsFontWeight.normalize(fontWeight)
    val label = getString(
        when (weight) {
            in 100..150 -> R.string.ui_weight_thin
            in 160..250 -> R.string.ui_weight_extra_light
            in 260..350 -> R.string.ui_weight_light
            in 360..450 -> R.string.ui_weight_regular
            in 460..550 -> R.string.ui_weight_medium
            in 560..650 -> R.string.ui_weight_semi_bold
            in 660..750 -> R.string.ui_weight_bold
            in 760..850 -> R.string.ui_weight_extra_bold
            else -> R.string.ui_weight_black
        }
    )
    return getString(R.string.floating_font_weight_summary, weight, label)
}
