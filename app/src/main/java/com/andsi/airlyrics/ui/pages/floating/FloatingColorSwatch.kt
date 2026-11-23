package com.andsi.airlyrics.ui.pages.floating

import android.animation.Keyframe
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withScale
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.canAnimateFloatingControl
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.floatingPanelEaseOut
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorBubble
import com.andsi.airlyrics.ui.theme.colorDanger
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorTextStrong

@SuppressLint("ViewConstructor")
internal class FloatingColorSwatch(
    private val host: MainUiHost,
    private val color: Int,
    private val label: String
) : View(host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selectionAmount = 0f
    private var removalAmount = 0f
    private var discScale = 1f
    private var stateAnimator: ValueAnimator? = null
    var removalArmed: Boolean = false
        private set
    private val check = requireNotNull(AppCompatResources.getDrawable(host, R.drawable.ic_air_check)).mutate().apply {
        setTint(if (ColorUtils.calculateContrast(Color.BLACK, color) >=
            ColorUtils.calculateContrast(Color.WHITE, color)) Color.BLACK else Color.WHITE)
    }
    private val cross = requireNotNull(AppCompatResources.getDrawable(host, R.drawable.ic_air_close)).mutate().apply {
        setTint(host.colorDanger)
    }

    init {
        minimumWidth = host.dp(AirUiTokens.Layout.IconTouchSize)
        minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
        contentDescription = label
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
        accessibilityDelegate = object : AccessibilityDelegate() {
            @Suppress("DEPRECATION")
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = if (removalArmed) "android.widget.Button" else "android.widget.RadioButton"
                info.isCheckable = !removalArmed
                info.isChecked = isSelected && !removalArmed
                if (removalArmed) {
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        this@FloatingColorSwatch.host.getString(R.string.ui_delete_color_swatch)
                    ))
                }
            }
        }
    }

    override fun setSelected(selected: Boolean) {
        showSelection(selected, animate = true)
    }

    fun showSelection(selected: Boolean, animate: Boolean, returning: Boolean = false) {
        if (isSelected == selected) return
        super.setSelected(selected)
        if (!selected && removalArmed) {
            removalArmed = false
            updateRemovalAction()
        }
        animateState(animate, returning && selected)
    }

    fun setRemovalArmed(armed: Boolean) {
        val next = armed && isSelected
        if (removalArmed == next) return
        removalArmed = next
        updateRemovalAction()
        animateState()
    }

    private fun updateRemovalAction() {
        val action = host.getString(R.string.ui_delete_color_swatch)
        contentDescription = if (removalArmed) "$label, $action" else label
        tooltipText = if (removalArmed) action else null
    }

    private fun animateState(animate: Boolean = true, bounce: Boolean = false) {
        stateAnimator?.cancel()
        stateAnimator = null
        val selectionTarget = if (isSelected) 1f else 0f
        val removalTarget = if (removalArmed) 1f else 0f
        if (!animate || !canAnimateFloatingControl()) {
            settleSelection()
            return
        }
        val fromSelection = selectionAmount
        val fromRemoval = removalAmount
        val scale = if (bounce) PropertyValuesHolder.ofKeyframe(
            "scale", Keyframe.ofFloat(0f, .90f), Keyframe.ofFloat(.60f, 1.10f),
            Keyframe.ofFloat(.86f, .99f), Keyframe.ofFloat(1f, 1f)
        ) else PropertyValuesHolder.ofFloat("scale", discScale, 1f)
        stateAnimator = ValueAnimator.ofPropertyValuesHolder(PropertyValuesHolder.ofFloat("progress", 0f, 1f), scale).apply {
            duration = if (bounce) AirUiTokens.Motion.UndoArrowMs else AirUiTokens.Motion.LayoutChangeMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                val fraction = floatingPanelEaseOut.getInterpolation(it.animatedFraction)
                selectionAmount = fromSelection + (selectionTarget - fromSelection) * fraction
                removalAmount = fromRemoval + (removalTarget - fromRemoval) * fraction
                discScale = it.getAnimatedValue("scale") as Float
                invalidate()
            }
            doOnEnd {
                if (stateAnimator === it) {
                    stateAnimator = null
                    settleSelection()
                }
            }
        }.also { it.start() }
    }

    fun finishAnimation() {
        val running = stateAnimator ?: return
        stateAnimator = null
        running.cancel()
        settleSelection()
    }

    private fun settleSelection() {
        selectionAmount = if (isSelected) 1f else 0f
        removalAmount = if (removalArmed) 1f else 0f
        discScale = 1f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        finishAnimation()
        super.onDetachedFromWindow()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = host.dp(18) - host.dp(AirUiTokens.Stroke.Selected) * selectionAmount
        canvas.withScale(discScale, discScale, cx, cy) {
            paint.style = Paint.Style.FILL
            paint.color = color
            drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = host.dp(AirUiTokens.Stroke.Hairline).toFloat()
            paint.color = ColorUtils.blendARGB(host.colorStroke, host.colorTextStrong, 0.5f)
            drawCircle(cx, cy, radius, paint)
        }

        val ringAmount = if (isFocused) 1f else selectionAmount
        if (ringAmount > 0f) {
            paint.color = ColorUtils.setAlphaComponent(
                ColorUtils.blendARGB(host.colorAccentText, host.colorDanger, removalAmount),
                (255 * ringAmount).toInt()
            )
            canvas.drawCircle(cx, cy, host.dp(18) + host.dp(4) * ringAmount, paint)
        }

        val deleteAlpha = selectionAmount * removalAmount
        if (deleteAlpha > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = ColorUtils.setAlphaComponent(host.colorBubble, (255 * deleteAlpha).toInt())
            canvas.drawCircle(cx, cy, host.dp(13).toFloat(), paint)
        }
        drawMark(canvas, check, cx, cy, selectionAmount * (1f - removalAmount),
            discScale * (0.9f + 0.1f * selectionAmount))
        drawMark(canvas, cross, cx, cy, deleteAlpha, 0.9f + 0.1f * removalAmount)
    }

    private fun drawMark(canvas: Canvas, icon: Drawable, cx: Float, cy: Float, amount: Float, scale: Float) {
        if (amount <= 0f) return
        canvas.withScale(scale, scale, cx, cy) {
            icon.alpha = (255 * amount).toInt()
            val half = host.dp(11)
            icon.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
            icon.draw(this)
        }
    }
}
