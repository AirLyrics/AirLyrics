package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode

/** Effects start only once the whole preview is inside its scrolling viewport. */
internal fun View.isPreviewFullyVisible(): Boolean {
    if (!isAttachedToWindow || !isShown || windowVisibility != View.VISIBLE || width <= 0 || height <= 0) return false
    val visible = Rect()
    return getLocalVisibleRect(visible) && visible.left <= 0 && visible.top <= 0 &&
        visible.right >= width && visible.bottom >= height
}

/** Replays line-switch motion only while the sample lyric is on screen. */
internal class FloatingPreviewSwitchLoop(
    private val view: TextView,
    private val shouldAnimate: () -> Boolean,
    private val play: () -> Unit,
    private val isVisible: () -> Boolean = {
        view.isPreviewFullyVisible()
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

    val duration: Long
    val offset: Float
    val scale: Float
    when (switchAnimationMode()) {
        LyricsSwitchAnimationMode.NONE, LyricsSwitchAnimationMode.SPICY -> {
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

    host.animateFloatingPreviewText(view, duration, offset, scale)
}
