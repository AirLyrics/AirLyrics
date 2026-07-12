package com.andsi.airlyrics.ui.pages.floating

import android.view.animation.DecelerateInterpolator
import com.andsi.airlyrics.settings.model.LyricsSwitchAnimationMode

internal fun FloatingPageScope.playPreviewSwitchAnimation() {
    val view = previewHandle?.lyricTextView ?: return
    view.animate().cancel()
    when (switchAnimationMode()) {
        LyricsSwitchAnimationMode.NONE -> {
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
        }
        LyricsSwitchAnimationMode.FADE -> {
            view.alpha = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.animate()
                .alpha(1f)
                .setDuration(FloatingPageTokens.PREVIEW_FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        LyricsSwitchAnimationMode.SLIDE_UP -> {
            view.alpha = 0f
            view.translationY = host.dp(FloatingPageTokens.PREVIEW_SLIDE_Y_DP).toFloat()
            view.scaleX = 1f
            view.scaleY = 1f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(FloatingPageTokens.PREVIEW_SLIDE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        LyricsSwitchAnimationMode.SCALE_FADE -> {
            view.alpha = 0f
            view.translationY = 0f
            view.scaleX = FloatingPageTokens.PREVIEW_SCALE_START
            view.scaleY = FloatingPageTokens.PREVIEW_SCALE_START
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(FloatingPageTokens.PREVIEW_SCALE_FADE_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}
