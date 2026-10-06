package com.andsi.airlyrics.i18n

import android.content.Context
import com.andsi.airlyrics.R
import java.util.Locale

internal fun Context.localizedOffsetDescription(offsetMs: Long): String {
    if (offsetMs == 0L) return getString(R.string.ui_no_offset)

    val value = "%.2fs".format(Locale.getDefault(), kotlin.math.abs(offsetMs) / 1000f)
    val messageRes = if (offsetMs > 0L) R.string.lyrics_offset_advance else R.string.lyrics_offset_delay
    return getString(messageRes, value)
}
