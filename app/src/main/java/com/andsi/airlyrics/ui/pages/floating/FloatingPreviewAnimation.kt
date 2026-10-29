package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.floating.LyricsTextAnimationTarget

/** Replays line-switch motion only while the sample lyric is on screen. */
internal class FloatingPreviewSwitchLoop(
    private val view: TextView,
    private val shouldAnimate: () -> Boolean,
    private val play: () -> Unit,
    private val isVisible: () -> Boolean = {
        view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
            view.getGlobalVisibleRect(Rect())
    }
) {
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running || !canRun()) {
                stop()
                return
            }
            play()
            view.postDelayed(this, FloatingPageTokens.PREVIEW_LOOP_INTERVAL_MS)
        }
    }

    fun sync() {
        if (canRun()) {
            if (!running) {
                running = true
                view.postDelayed(tick, FloatingPageTokens.PREVIEW_LOOP_START_DELAY_MS)
            }
        } else {
            stop()
        }
    }

    private fun canRun(): Boolean =
        shouldAnimate() && ValueAnimator.areAnimatorsEnabled() && isVisible()

    private fun stop() {
        if (!running) return
        running = false
        view.removeCallbacks(tick)
    }
}

internal fun FloatingPageScope.playPreviewSwitchAnimation() {
    val view = previewHandle?.lyricTextView ?: return
    val target = view as? LyricsTextAnimationTarget
    view.animate().cancel()
    target?.cancelLyricsTextAnimation()

    val duration: Long
    val offset: Float
    val scale: Float
    when (switchAnimationMode()) {
        LyricsSwitchAnimationMode.NONE -> {
            duration = 0L
            offset = 0f
            scale = 1f
        }
        LyricsSwitchAnimationMode.FADE -> {
            duration = FloatingPageTokens.PREVIEW_FADE_MS
            offset = 0f
            scale = 1f
        }
        LyricsSwitchAnimationMode.SLIDE_UP -> {
            duration = FloatingPageTokens.PREVIEW_SLIDE_MS
            offset = host.dp(FloatingPageTokens.PREVIEW_SLIDE_Y_DP).toFloat()
            scale = 1f
        }
        LyricsSwitchAnimationMode.SCALE_FADE -> {
            duration = FloatingPageTokens.PREVIEW_SCALE_FADE_MS
            offset = 0f
            scale = FloatingPageTokens.PREVIEW_SCALE_START
        }
    }

    val animate = duration > 0L && ValueAnimator.areAnimatorsEnabled()
    val alpha = if (animate) 0f else 1f
    if (target != null) {
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        target.setLyricsTextAnimationState(alpha, offset, scale, scale)
        if (animate) target.animateLyricsTextToRest(duration, DecelerateInterpolator())
    } else {
        view.alpha = alpha
        view.translationY = offset
        view.scaleX = scale
        view.scaleY = scale
        if (animate) {
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}
