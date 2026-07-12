package com.andsi.airlyrics.i18n

import com.andsi.airlyrics.R

import android.content.Context
import android.view.Gravity

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
