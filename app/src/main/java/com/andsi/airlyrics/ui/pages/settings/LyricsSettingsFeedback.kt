package com.andsi.airlyrics.ui.pages.settings

import android.view.View
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
    message: String
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

    target.alpha = 0.72f
    target.translationY = dp(AirUiTokens.Space.Xs).toFloat()
    target.animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(AirUiTokens.Motion.ChildEnterMs)
        .start()
}
