package com.andsi.airlyrics.app.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import com.andsi.airlyrics.ui.navigation.SettingsNavigation
import com.andsi.airlyrics.ui.navigation.SettingsNavigationDirection
import kotlin.math.roundToInt

// Motion Prompt #137 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#137
// Motion Prompt #005 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#005
/** Coupled, reversible depth motion for adjacent settings pages. */
internal class SettingsPageTransition {
    private val enterEase = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val exitEase = DecelerateInterpolator(1.15f)
    private var animator: ValueAnimator? = null
    private var outgoing: View? = null
    private var incoming: View? = null
    private var touchShield: View? = null
    private var incomingBackground: Drawable? = null
    private var incomingClip: Rect? = null

    fun start(
        container: FrameLayout,
        outgoing: View,
        incoming: View,
        route: SettingsNavigation,
        @ColorInt surfaceColor: Int,
        dp: (Int) -> Int
    ) {
        val firstLevel = route.depth == 1
        val durationMs = if (firstLevel) 210L else 190L
        val exitMs = if (firstLevel) 155L else 140L
        val retreat = dp(if (firstLevel) 10 else 8).toFloat()
        val entrance = dp(if (firstLevel) 24 else 20).toFloat()
        val recessedScale = if (firstLevel) 0.985f else 0.99f
        val forward = if (container.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1f else 1f
        val pushing = route.direction == SettingsNavigationDirection.PUSH
        val revealFromRight = pushing == (forward > 0f)
        val pageWidth = container.width
        val pageHeight = container.height
        val initialReveal = (pageWidth * 0.25f).roundToInt()

        fun reveal(progress: Float) {
            val visibleWidth = initialReveal + ((pageWidth - initialReveal) * progress).roundToInt()
            incoming.clipBounds = if (revealFromRight) {
                Rect(pageWidth - visibleWidth, 0, pageWidth, pageHeight)
            } else {
                Rect(0, 0, visibleWidth, pageHeight)
            }
        }

        val outgoingStartX = outgoing.translationX
        val outgoingStartScale = outgoing.scaleX
        val outgoingEndX = if (pushing) -forward * retreat else forward * entrance
        val outgoingEndScale = if (pushing) recessedScale else 1f
        val incomingStartX = if (pushing) forward * entrance else -forward * retreat
        val incomingStartScale = if (pushing) 1f else recessedScale

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
        touchShield = shield
        this.incoming = incoming
        incomingBackground = incoming.background
        incomingClip = incoming.clipBounds?.let(::Rect)
        // The foreground needs an opaque surface while the two pages overlap.
        incoming.setBackgroundColor(surfaceColor)
        reveal(0f)
        incoming.translationX = incomingStartX
        incoming.scaleX = incomingStartScale
        incoming.scaleY = incomingStartScale
        incoming.alpha = 1f

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                if (animator !== animation) return@addUpdateListener
                val elapsed = (animation.animatedValue as Float) * durationMs
                val exit = exitEase.getInterpolation((elapsed / exitMs).coerceIn(0f, 1f))
                val enter = enterEase.getInterpolation((elapsed / durationMs).coerceIn(0f, 1f))
                reveal(exit)
                if (elapsed < exitMs) {
                    outgoing.translationX = outgoingStartX + (outgoingEndX - outgoingStartX) * exit
                    val scale = outgoingStartScale + (outgoingEndScale - outgoingStartScale) * exit
                    outgoing.scaleX = scale
                    outgoing.scaleY = scale
                } else {
                    removeRetiring()
                }
                incoming.translationX = incomingStartX * (1f - enter)
                val scale = incomingStartScale + (1f - incomingStartScale) * enter
                incoming.scaleX = scale
                incoming.scaleY = scale
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    removeRetiring()
                    settleIncoming()
                    animator = null
                }
            })
            start()
        }
    }

    /** A following navigation can use the incoming page's current pose as its outgoing pose. */
    fun cancel(settleIncoming: Boolean = true) {
        animator?.let {
            it.removeAllUpdateListeners()
            it.removeAllListeners()
            it.cancel()
        }
        animator = null
        removeRetiring()
        if (settleIncoming) {
            settleIncoming()
        } else {
            restoreIncomingSurface()
            incoming = null
        }
    }

    private fun settleIncoming() {
        incoming?.apply {
            translationX = 0f
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }
        restoreIncomingSurface()
        incoming = null
    }

    private fun restoreIncomingSurface() {
        incoming?.background = incomingBackground
        incoming?.clipBounds = incomingClip
        incomingBackground = null
        incomingClip = null
    }

    private fun removeRetiring() {
        outgoing?.let { (it.parent as? ViewGroup)?.removeView(it) }
        touchShield?.let { (it.parent as? ViewGroup)?.removeView(it) }
        outgoing = null
        touchShield = null
    }
}
