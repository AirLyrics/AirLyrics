package com.andsi.airlyrics.app.host

import android.animation.ValueAnimator
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.andsi.airlyrics.floating.FloatingLyricsTextView
import com.andsi.airlyrics.floating.LyricsTextAnimationTarget
import com.andsi.airlyrics.floating.SpicyLyricsSpan
import com.andsi.airlyrics.floating.spicyTimings
import com.andsi.airlyrics.floating.wordByWordHighlightProgress
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.ui.model.FloatingPreviewHighlightProgress
import com.andsi.airlyrics.ui.pages.floating.FloatingPageTokens

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

internal fun hasAnimatedPreviewLyricsHighlight(view: TextView): Boolean {
    val text = view.text as? Spanned ?: return false
    return text.getSpans(0, text.length, SpicyLyricsSpan::class.java).isNotEmpty()
}

internal fun animatePreviewLyricsText(view: TextView, durationMs: Long, offsetY: Float, scale: Float) {
    val target = view as? LyricsTextAnimationTarget
    view.animate().cancel()
    target?.cancelLyricsTextAnimation()

    val animate = durationMs > 0L && ValueAnimator.areAnimatorsEnabled()
    val alpha = if (animate) 0f else 1f
    if (target != null) {
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        target.setLyricsTextAnimationState(alpha, offsetY, scale, scale)
        if (animate) target.animateLyricsTextToRest(durationMs, DecelerateInterpolator())
    } else {
        view.alpha = alpha
        view.translationY = offsetY
        view.scaleX = scale
        view.scaleY = scale
        if (animate) {
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(durationMs)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}

internal fun createClassicPreviewProgress(lineText: String): (Long) -> FloatingPreviewHighlightProgress {
    val fillDurationMs = FloatingPageTokens.PREVIEW_CLASSIC_FILL_MS
    val line = WordByWordLine(
        startMs = 0L,
        endMs = fillDurationMs,
        text = lineText,
        segments = listOf(WordByWordSegment(lineText, 0L, fillDurationMs))
    )
    return { positionMs ->
        val progress = wordByWordHighlightProgress(line, lineText, positionMs)
        FloatingPreviewHighlightProgress(
            completedEnd = progress.completedEnd,
            activeStart = progress.activeStart,
            activeEnd = progress.activeEnd,
            activeFraction = progress.activeFraction,
            hasActiveCharacter = progress.hasActiveCharacter
        )
    }
}
