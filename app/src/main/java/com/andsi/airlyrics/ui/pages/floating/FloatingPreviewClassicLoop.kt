package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import android.graphics.Rect
import android.text.Spannable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.ui.model.FloatingPreviewHighlightProgress

/** Advances the Classic karaoke color across the current sample line. */
internal class FloatingPreviewClassicLoop(
    private val view: TextView,
    private val sampleLine: () -> String?,
    private val highlightColor: () -> Int,
    private val progressForLine: (String) -> (Long) -> FloatingPreviewHighlightProgress,
    private val isVisible: () -> Boolean = {
        view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
            view.getGlobalVisibleRect(Rect())
    }
) {
    private var text: Spannable? = null
    private var line: String? = null
    private var lineStart = -1
    private var color = 0
    private var highlighter: ClassicPreviewHighlighter? = null
    private var animator: ValueAnimator? = null

    val hasTarget: Boolean get() = highlighter != null

    fun sync() {
        val nextLine = sampleLine()?.takeIf { it.isNotBlank() }
        val nextStart = nextLine?.let { view.text.toString().indexOf(it) } ?: -1
        if (nextStart < 0 || nextLine == null) {
            clear()
            return
        }

        val nextColor = highlightColor()
        val nextText = (view.text as? Spannable) ?: run {
            view.setText(view.text, TextView.BufferType.SPANNABLE)
            view.text as Spannable
        }
        if (text !== nextText || line != nextLine || lineStart != nextStart || color != nextColor) {
            stopAnimator()
            highlighter?.clear()
            text = nextText
            line = nextLine
            lineStart = nextStart
            color = nextColor
            highlighter = ClassicPreviewHighlighter(
                nextText, nextStart, nextColor, view.currentTextColor, progressForLine(nextLine)
            )
        }

        if (ValueAnimator.areAnimatorsEnabled() && isVisible()) {
            if (animator == null) {
                highlighter?.renderAt(0L)
                animator = ValueAnimator.ofInt(0, FloatingPageTokens.PREVIEW_CLASSIC_CYCLE_MS.toInt()).apply {
                    duration = FloatingPageTokens.PREVIEW_CLASSIC_CYCLE_MS
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { animation ->
                        highlighter?.renderAt((animation.animatedValue as Int).toLong())
                    }
                    start()
                }
            }
        } else {
            stopAnimator()
            highlighter?.renderAt(FloatingPageTokens.PREVIEW_CLASSIC_FILL_MS / 2)
        }
    }

    private fun clear() {
        stopAnimator()
        highlighter?.clear()
        highlighter = null
        text = null
        line = null
        lineStart = -1
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }
}

internal class ClassicPreviewHighlighter(
    private val text: Spannable,
    private val lineStart: Int,
    private val highlightColor: Int,
    private val baseColor: Int,
    private val progressAt: (Long) -> FloatingPreviewHighlightProgress
) {
    private var completedSpan: ForegroundColorSpan? = null
    private var activeSpan: ForegroundColorSpan? = null
    private var lastPositionMs: Long? = null

    fun renderAt(positionMs: Long) {
        val effectivePositionMs = positionMs.coerceAtMost(FloatingPageTokens.PREVIEW_CLASSIC_FILL_MS)
        if (lastPositionMs == effectivePositionMs) return
        removeSpans()
        lastPositionMs = effectivePositionMs
        val progress = progressAt(effectivePositionMs)
        if (progress.completedEnd > 0) {
            completedSpan = ForegroundColorSpan(highlightColor).also {
                text.setSpan(it, lineStart, lineStart + progress.completedEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (progress.hasActiveCharacter) {
            activeSpan = ForegroundColorSpan(
                ColorUtils.blendARGB(baseColor, highlightColor, progress.activeFraction)
            ).also {
                text.setSpan(
                    it,
                    lineStart + progress.activeStart,
                    lineStart + progress.activeEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    fun clear() {
        removeSpans()
        lastPositionMs = null
    }

    private fun removeSpans() {
        completedSpan?.let(text::removeSpan)
        activeSpan?.let(text::removeSpan)
        completedSpan = null
        activeSpan = null
    }
}
