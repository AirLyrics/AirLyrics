package com.andsi.airlyrics.ui.components

import android.animation.ValueAnimator
import android.view.View
import androidx.core.animation.doOnEnd
import androidx.core.view.doOnDetach
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.bindAppearance

/** Keeps selection semantics current while the mounted button interpolates its appearance. */
internal class TonalSelectionMotion(
    private val host: MainUiHost,
    private val view: View,
    selected: Boolean,
    private val radiusDp: Int = AirUiTokens.Radius.Md,
    private val renderContent: (AirTonalButtonStyle, Float) -> Unit = { _, _ -> },
) {
    private var amount = if (selected) 1f else 0f
    private var animator: ValueAnimator? = null

    init {
        view.isSelected = selected
        host.bindAppearance(view) { render() }
        view.doOnDetach {
            stopAnimation()
            amount = if (view.isSelected) 1f else 0f
            render()
        }
    }

    fun select(selected: Boolean, animate: Boolean = true) {
        if (view.isSelected == selected) return
        view.isSelected = selected
        stopAnimation()
        val target = if (selected) 1f else 0f
        if (!animate || !view.isLaidOut || !view.canAnimateFloatingControl()) {
            amount = target
            render()
            return
        }

        val motion = ValueAnimator.ofFloat(amount, target).apply {
            duration = AirUiTokens.Motion.LayoutChangeMs
            interpolator = floatingPanelEaseOut
            addUpdateListener {
                amount = it.animatedValue as Float
                render()
            }
            doOnEnd { if (animator === it) animator = null }
        }
        animator = motion
        motion.start()
    }

    private fun stopAnimation() {
        val running = animator
        animator = null
        running?.cancel()
    }

    private fun render() {
        val idle = host.airTonalButtonStyle(AirTonalButtonTone.DEFAULT)
        val selected = host.airTonalButtonStyle(AirTonalButtonTone.SELECTED)
        val style = idle.interpolate(selected, amount)
        host.applyAirTonalButtonStyle(view, style, radiusDp)
        renderContent(style, amount)
    }
}
