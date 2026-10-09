package com.andsi.airlyrics.core.model

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.URLSpan

/** Link targets are supplied by the provider, never by editable lyric content. */
fun LyricsAttribution.displayText(): CharSequence = SpannableStringBuilder(provider).apply {
    credits.forEach { credit ->
        append(" · ")
        val start = length
        append(credit.name)
        credit.url?.takeIf { it.startsWith("https://") }?.let { setSpan(URLSpan(it), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
}
