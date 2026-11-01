package com.andsi.airlyrics.app.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.andsi.airlyrics.design.tokens.AirUiTokens

// Motion Prompt #057 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#057
// Motion Prompt #137 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#137
/** Short, directional handoff between bottom-navigation pages. */
internal class MainPageTransition {
    private val exitEase = AccelerateInterpolator()
    private val enterEase = DecelerateInterpolator(1.25f)
    private var animator: ValueAnimator? = null
    private var outgoing: View? = null
    private var touchShield: View? = null
    private var incoming: View? = null

    fun start(
        container: FrameLayout,
        outgoing: View,
        incoming: View,
        fromRight: Boolean,
        distance: Float
    ) {
        val direction = if (fromRight) 1f else -1f
        val outgoingStartX = outgoing.translationX
        val outgoingStartAlpha = outgoing.alpha
        val incomingStartAlpha = (1f - outgoingStartAlpha).coerceIn(0f, 1f)
        val incomingStartX = distance * direction

        outgoing.findFocus()?.clearFocus()
        outgoing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val shield = View(container.context).apply {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        container.addView(
            shield,
            container.indexOfChild(incoming),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        this.outgoing = outgoing
        this.touchShield = shield
        this.incoming = incoming
        incoming.alpha = incomingStartAlpha
        incoming.translationX = incomingStartX

        val totalMs = AirUiTokens.Motion.PageEnterMs.toFloat()
        val exitMs = AirUiTokens.Motion.PageExitMs.toFloat()
        val enterDelayMs = AirUiTokens.Motion.PageEnterDelayMs.toFloat()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AirUiTokens.Motion.PageEnterMs
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val elapsed = (animation.animatedValue as Float) * totalMs
                val exitFraction = (elapsed / exitMs).coerceIn(0f, 1f)
                val enterFraction = ((elapsed - enterDelayMs) / (totalMs - enterDelayMs)).coerceIn(0f, 1f)

                if (exitFraction < 1f) {
                    val progress = exitEase.getInterpolation(exitFraction)
                    outgoing.alpha = outgoingStartAlpha * (1f - progress)
                    outgoing.translationX = outgoingStartX - direction * distance * progress
                } else {
                    removeRetiring()
                }
                val progress = enterEase.getInterpolation(enterFraction)
                incoming.alpha = incomingStartAlpha + (1f - incomingStartAlpha) * progress
                incoming.translationX = incomingStartX * (1f - progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    removeRetiring()
                    incoming.alpha = 1f
                    incoming.translationX = 0f
                    this@MainPageTransition.incoming = null
                    animator = null
                }
            })
            start()
        }
    }

    /** Keep the entering view's current pose for an immediate next navigation. */
    fun cancel(settleIncoming: Boolean = true) {
        animator?.let {
            it.removeAllUpdateListeners()
            it.removeAllListeners()
            it.cancel()
        }
        animator = null
        removeRetiring()
        if (settleIncoming) {
            incoming?.alpha = 1f
            incoming?.translationX = 0f
        }
        incoming = null
    }

    private fun removeRetiring() {
        outgoing?.let { (it.parent as? ViewGroup)?.removeView(it) }
        touchShield?.let { (it.parent as? ViewGroup)?.removeView(it) }
        outgoing = null
        touchShield = null
    }
}
