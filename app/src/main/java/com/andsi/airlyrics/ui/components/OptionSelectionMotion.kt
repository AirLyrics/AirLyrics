package com.andsi.airlyrics.ui.components

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.bindAppearance
import kotlin.math.roundToInt

/** One continuous selection value keeps the fill, outline, label and check in sync. */
internal class OptionSelectionMotion(
    private val host: MainUiHost,
    val view: TextView,
    selected: Boolean
) {
    private var amount = if (selected) 1f else 0f
    private var animator: ValueAnimator? = null
    private val shape = GradientDrawable().apply {
        cornerRadius = host.dp(AirUiTokens.Radius.Md).toFloat()
    }
    private var idleStyle = host.airTonalButtonStyle(AirTonalButtonTone.DEFAULT)
    private var selectedStyle = host.airTonalButtonStyle(AirTonalButtonTone.SELECTED)

    init {
        view.isSelected = selected
        view.background = shape
        // The check always occupies the same slot, even while invisible.
        view.setAirStartIcon(host, R.drawable.ic_air_check, selectedStyle.textColor)
        host.bindAppearance(view) {
            idleStyle = host.airTonalButtonStyle(AirTonalButtonTone.DEFAULT)
            selectedStyle = host.airTonalButtonStyle(AirTonalButtonTone.SELECTED)
            render()
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                animator?.cancel()
                animator = null
                amount = if (view.isSelected) 1f else 0f
                render()
            }
        })
    }

    fun select(selected: Boolean) {
        if (view.isSelected == selected) return
        view.isSelected = selected
        animator?.cancel()
        val target = if (selected) 1f else 0f
        if (view.floatingPanelMotionRoot() == null || !view.canAnimateFloatingControl()) {
            amount = target
            render()
            return
        }
        animator = ValueAnimator.ofFloat(amount, target).apply {
            duration = AirUiTokens.Motion.LayoutChangeMs
            interpolator = floatingPanelEaseOut
            addUpdateListener {
                amount = it.animatedValue as Float
                render()
            }
            start()
        }
    }

    private fun render() {
        shape.setColor(ColorUtils.blendARGB(idleStyle.fillColor, selectedStyle.fillColor, amount))
        shape.setStroke(
            (host.dp(idleStyle.strokeWidthDp) +
                (host.dp(selectedStyle.strokeWidthDp) - host.dp(idleStyle.strokeWidthDp)) * amount).roundToInt(),
            ColorUtils.blendARGB(idleStyle.strokeColor, selectedStyle.strokeColor, amount)
        )
        view.setTextColor(ColorUtils.blendARGB(idleStyle.textColor, selectedStyle.textColor, amount))
        view.compoundDrawablesRelative[0]?.apply {
            setTint(selectedStyle.textColor)
            alpha = (255 * amount).roundToInt()
        }
        view.invalidate()
    }
}
