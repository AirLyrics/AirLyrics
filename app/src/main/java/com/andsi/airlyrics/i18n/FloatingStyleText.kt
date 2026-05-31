package com.andsi.airlyrics.i18n

import android.content.Context
import android.view.Gravity
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore

internal fun Context.localizedFloatingPresetTitle(key: String): String = when (key) {
    "subtitle" -> tr("纯净字母", "Clean letters")
    "bubble" -> tr("黑胶气泡", "Vinyl bubble")
    else -> localizedFloatingPresetTitle(FloatingLyricsStyleStore.DEFAULT_PRESET)
}

internal fun Context.localizedFloatingGravityTitle(gravity: Int): String = when (gravity) {
    Gravity.START or Gravity.CENTER_VERTICAL -> tr("左对齐", "Left")
    Gravity.END or Gravity.CENTER_VERTICAL -> tr("右对齐", "Right")
    else -> tr("居中", "Center")
}
