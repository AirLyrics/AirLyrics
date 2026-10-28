package com.andsi.airlyrics.app.host

import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.widget.TextView
import com.andsi.airlyrics.floating.FloatingLyricsTextView
import com.andsi.airlyrics.floating.SpicyLyricsSpan
import com.andsi.airlyrics.floating.spicyTimings
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment

internal fun applyPreviewLyricsHighlight(
    text: SpannableStringBuilder,
    currentLine: String,
    start: Int,
    color: Int
) {
    val words = Regex("\\S+").findAll(currentLine).toList()
    var time = 0L
    val segments = words.mapIndexed { index, word ->
        val duration = if (index == words.lastIndex) 1800L else 450L
        WordByWordSegment(word.value, time, time + duration).also { time += duration }
    }
    val line = WordByWordLine(0L, time, currentLine, segments)
    for (timing in spicyTimings(line, currentLine)) {
        text.setSpan(
            SpicyLyricsSpan(timing, color),
            start + timing.start,
            start + timing.end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

internal fun updatePreviewLyricsText(view: TextView, text: CharSequence) {
    view.text = text
    if (view !is FloatingLyricsTextView) return
    val spans = (view.text as? Spanned)?.getSpans(0, view.text.length, SpicyLyricsSpan::class.java).orEmpty()
    val endMs = spans.maxOfOrNull { it.timing.endMs } ?: 0L
    val started = SystemClock.uptimeMillis()
    view.updateSpicyPlayback(
        if (endMs > 0) ({ (SystemClock.uptimeMillis() - started) % (endMs + 1000) }) else null,
        true
    )
}
