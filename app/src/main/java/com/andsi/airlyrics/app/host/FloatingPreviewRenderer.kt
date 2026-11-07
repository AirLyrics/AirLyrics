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
import com.andsi.airlyrics.floating.SpicyLineSpan
import com.andsi.airlyrics.floating.SpicyLineTiming
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
    color: Int,
    classic: Boolean = false
) {
    val words = Regex("\\S+").findAll(currentLine).toList()
    var time = 0L
    val segments = words.mapIndexed { index, word ->
        if (classic) {
            val fill = FloatingPageTokens.PREVIEW_CLASSIC_FILL_MS
            time = fill
            WordByWordSegment(word.value, fill * word.range.first / currentLine.length,
                fill * (word.range.last + 1) / currentLine.length)
        } else {
            val duration = if (index == words.lastIndex) 1800L else 450L
            WordByWordSegment(word.value, time, time + duration).also { time += duration }
        }
    }
    val line = WordByWordLine(0L, time, currentLine, segments)
    for (timing in spicyTimings(line, currentLine, allowEmphasis = !classic)) {
        text.setSpan(
            SpicyLyricsSpan(timing, color, haloOnly = classic),
            start + timing.start,
            start + timing.end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

internal fun applyPreviewLyricsLine(
    text: SpannableStringBuilder, start: Int, end: Int, lineOffset: Int, partIndex: Int, color: Int, animated: Boolean = true
) {
    val startMs = lineOffset * 4_000L
    text.setSpan(SpicyLineSpan(SpicyLineTiming(lineOffset, partIndex, startMs, startMs + 4_000L), color, animated),
        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

internal fun updatePreviewLyricsText(view: TextView, text: CharSequence) {
    view.text = text
    if (view !is FloatingLyricsTextView) return
    val spans = (view.text as? Spanned)?.getSpans(0, view.text.length, SpicyLyricsSpan::class.java).orEmpty()
    val endMs = spans.maxOfOrNull { it.timing.endMs } ?: 0L
    val lineSpans = (view.text as? Spanned)?.getSpans(0, view.text.length, SpicyLineSpan::class.java).orEmpty()
    val lineEnd = lineSpans.firstOrNull { it.timing.startMs == 0L }?.timing?.endMs ?: 0L
    val clock = when {
        lineEnd > 0 -> FloatingPreviewPlaybackClock(lineEnd + 800, lineEnd - 1)
        endMs > 0 -> FloatingPreviewPlaybackClock(endMs + 1000)
        else -> null
    }
    view.onPreviewVisibilityChanged = clock?.let { it::setVisible }
    clock?.setVisible(view.previewEffectsVisible)
    view.updateSpicyPlayback(clock?.let { it::position }, spans.none { it.haloOnly })
}

/** Restart preview time when it returns, rather than catching up on hidden frames. */
internal class FloatingPreviewPlaybackClock(
    private val cycleMs: Long,
    private val holdAtMs: Long = Long.MAX_VALUE,
    private val uptime: () -> Long = SystemClock::uptimeMillis
) {
    private var visible = false
    private var started = 0L

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        started = uptime()
    }

    fun position(): Long = if (visible) ((uptime() - started).coerceAtLeast(0L) % cycleMs)
        .coerceAtMost(holdAtMs) else 0L
}

internal fun hasAnimatedPreviewLyricsHighlight(view: TextView): Boolean {
    val text = view.text as? Spanned ?: return false
    return text.getSpans(0, text.length, SpicyLyricsSpan::class.java).any { !it.haloOnly } ||
        text.getSpans(0, text.length, SpicyLineSpan::class.java).any { it.animated }
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
