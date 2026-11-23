package com.andsi.airlyrics.i18n

import android.content.Context
import android.view.Gravity
import androidx.annotation.StringRes
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.FloatingLyricsFontWeight

internal fun Context.localizedFloatingPresetTitle(key: String): String =
    getString(floatingPresetTitleRes(key))

@StringRes
internal fun floatingPresetTitleRes(key: String): Int = when (key) {
    "subtitle" -> R.string.ui_clean_letters
    "bubble" -> R.string.ui_vinyl_bubble
    else -> R.string.ui_vinyl_bubble
}

internal fun Context.localizedFloatingGravityTitle(gravity: Int): String =
    getString(floatingGravityTitleRes(gravity))

@StringRes
internal fun floatingGravityTitleRes(gravity: Int): Int = when (gravity) {
    Gravity.START or Gravity.CENTER_VERTICAL -> R.string.ui_left
    Gravity.END or Gravity.CENTER_VERTICAL -> R.string.ui_right
    else -> R.string.ui_center
}

internal fun Context.localizedFloatingFontFamilyTitle(fontFamily: FloatingLyricsFontFamily): String =
    getString(floatingFontFamilyTitleRes(fontFamily))

@StringRes
internal fun floatingFontFamilyTitleRes(fontFamily: FloatingLyricsFontFamily): Int = when (fontFamily) {
    FloatingLyricsFontFamily.SYSTEM_DEFAULT -> R.string.ui_system_default_font
    FloatingLyricsFontFamily.SERIF -> R.string.ui_serif
    FloatingLyricsFontFamily.ROUNDED -> R.string.ui_rounded_font
    FloatingLyricsFontFamily.HANDWRITING -> R.string.ui_handwriting_font
    FloatingLyricsFontFamily.CUSTOM -> R.string.ui_custom_font
}

internal fun localizedFloatingFontWeightTitle(fontWeight: Int): String {
    return FloatingLyricsFontWeight.toLevel(fontWeight).toString()
}

internal fun Context.localizedFloatingFontSize(bytes: Long): String {
    val locale = resources.configuration.locales[0]
    return when {
        bytes >= 1_000_000L -> String.format(locale, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000L -> String.format(locale, "%.1f KB", bytes / 1_000.0)
        else -> String.format(locale, "%d B", bytes)
    }
}
