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

internal fun localizedFloatingFontWeightTitle(fontWeight: Int): String {
    return FloatingLyricsFontWeight.toLevel(fontWeight).toString()
}
