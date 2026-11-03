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
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal data class RefreshableSettingsCard(
    val view: View,
    val refreshContent: () -> Unit
)

private fun TextView.cancelPendingRefreshFeedback() {
    (getTag(R.id.interaction_refresh_feedback_dismiss) as? Runnable)?.let(::removeCallbacks)
    setTag(R.id.interaction_refresh_feedback_dismiss, null)
    animate().cancel()
}

internal fun TextView.clearRefreshFeedbackOnDetach() {
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit

        override fun onViewDetachedFromWindow(view: View) {
            cancelPendingRefreshFeedback()
            text = ""
            alpha = 0f
        }
    })
}

internal fun showCompletedRefreshFeedback(feedback: TextView?, message: String) {
    feedback?.apply {
        cancelPendingRefreshFeedback()
        if (!isAttachedToWindow) return@apply
        val wasVisible = text.isNotEmpty() && alpha > 0f
        text = message
        lateinit var dismiss: Runnable
        dismiss = Runnable {
            if (getTag(R.id.interaction_refresh_feedback_dismiss) !== dismiss || !isAttachedToWindow) return@Runnable
            if (ValueAnimator.areAnimatorsEnabled()) {
                animate().alpha(0f).setDuration(AirUiTokens.Motion.FeedbackOutMs).withEndAction {
                    if (getTag(R.id.interaction_refresh_feedback_dismiss) === dismiss) {
                        setTag(R.id.interaction_refresh_feedback_dismiss, null)
                        text = ""
                        alpha = 0f
                    }
                }.start()
            } else {
                setTag(R.id.interaction_refresh_feedback_dismiss, null)
                text = ""
                alpha = 0f
            }
        }
        setTag(R.id.interaction_refresh_feedback_dismiss, dismiss)
        val animateFeedback = ValueAnimator.areAnimatorsEnabled()
        if (animateFeedback) {
            alpha = if (wasVisible) minOf(alpha, 0.72f) else 0f
            animate().alpha(1f).setDuration(AirUiTokens.Motion.FeedbackInMs).withEndAction {
                if (getTag(R.id.interaction_refresh_feedback_dismiss) === dismiss) {
                    postDelayed(dismiss, AirUiTokens.Motion.FeedbackHoldMs)
                }
            }.start()
        } else {
            alpha = 1f
            postDelayed(dismiss, AirUiTokens.Motion.FeedbackHoldMs)
        }
    }
}

internal fun playLocalRefreshFeedback(
    activity: MainUiHost,
    target: View,
    feedback: TextView?,
    message: String,
    animateTarget: Boolean = true
) = with(activity) {
    showCompletedRefreshFeedback(feedback, message)

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
