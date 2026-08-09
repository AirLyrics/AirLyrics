package com.andsi.airlyrics.ui.theme

import android.content.res.ColorStateList
import androidx.appcompat.widget.SwitchCompat
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.ui.model.MainUiHost

/** Applies the runtime accent palette to controls that otherwise use XML theme colors. */
internal fun SwitchCompat.applyAirThemeTint(host: MainUiHost) {
    val states = arrayOf(
        intArrayOf(android.R.attr.state_checked),
        intArrayOf()
    )
    thumbTintList = ColorStateList(
        states,
        intArrayOf(host.colorAccent, host.colorTextMuted)
    )
    trackTintList = ColorStateList(
        states,
        intArrayOf(
            AirColorUtils.withAlpha(host.colorAccentLight, 170),
            AirColorUtils.withAlpha(host.colorStroke, 150)
        )
    )
}
