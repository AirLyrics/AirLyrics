package com.andsi.airlyrics.ui.pages.settings

import android.animation.ValueAnimator
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal data class RefreshableSettingsCard(
    val view: View,
    val refreshContent: () -> Unit
)

internal fun showInlineRefreshFeedback(feedback: TextView?, message: String) {
    feedback?.apply {
        animate().cancel()
        text = message
        alpha = 1f
    }
}

internal fun playLocalRefreshFeedback(
    activity: MainUiHost,
    target: View,
    feedback: TextView?,
    message: String,
    animateTarget: Boolean = true
) = with(activity) {
    feedback?.apply {
        text = message
        alpha = 0f
        animate().alpha(1f).setDuration(AirUiTokens.Motion.FeedbackInMs).withEndAction {
            postDelayed({
                animate()
                    .alpha(0f)
                    .setDuration(AirUiTokens.Motion.FeedbackOutMs)
                    .withEndAction { text = "" }
                    .start()
            }, AirUiTokens.Motion.FeedbackHoldMs)
        }.start()
    }

    if (animateTarget && ValueAnimator.areAnimatorsEnabled()) {
        target.alpha = 0.72f
        target.translationY = dp(AirUiTokens.Space.Xs).toFloat()
        target.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(AirUiTokens.Motion.ChildEnterMs)
            .start()
    }
}

internal fun beginLyricsCardContentTransition(card: ViewGroup?) {
    if (card?.isAttachedToWindow != true || !ValueAnimator.areAnimatorsEnabled()) return
    TransitionManager.endTransitions(card)
    // Motion Prompt #057 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#057
    // Content trades places while the surrounding layout catches up.
    TransitionManager.beginDelayedTransition(card, TransitionSet().apply {
        ordering = TransitionSet.ORDERING_TOGETHER
        duration = 180L
        interpolator = DecelerateInterpolator()
        addTransition(ChangeBounds())
        addTransition(Fade())
    })
}

internal fun beginLyricsCardLayoutTransition(card: ViewGroup?) {
    if (card?.isAttachedToWindow != true || !ValueAnimator.areAnimatorsEnabled()) return
    TransitionManager.endTransitions(card)
    TransitionManager.beginDelayedTransition(card, ChangeBounds().apply {
        duration = 170L
        interpolator = DecelerateInterpolator()
    })
}
