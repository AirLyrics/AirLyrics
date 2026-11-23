package com.andsi.airlyrics.ui.components

import android.animation.Keyframe
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.bindAppearance
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSuccessIcon
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import kotlin.math.ceil

internal data class SettingsUndoTarget(
    val label: String,
    val value: String,
    val color: Int? = null
)

internal data class SettingsUndoAction(
    val description: String,
    val target: SettingsUndoTarget? = null
)

@SuppressLint("ViewConstructor")
internal class SettingsPanelUndoButton(
    private val host: MainUiHost,
    onUndo: () -> Unit
) : FrameLayout(host) {
    private val horizontalPadding = host.dp(AirUiTokens.Space.Xl)
    private val gap = host.dp(AirUiTokens.Space.Lg)
    private val arrowSize = host.dp(18)
    private val swatchSize = host.dp(12)
    private val label = TextView(host).apply {
        setText(R.string.ui_undo)
        textSize = AirUiTokens.TextSize.BodySmall
        typeface = Typeface.DEFAULT_BOLD
        setSingleLine()
    }
    private val targetText = TextView(host).apply {
        textSize = AirUiTokens.TextSize.Tiny
        fontFeatureSettings = "tnum"
        setSingleLine()
        visibility = GONE
    }
    private val targetColor = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val swatch = View(host).apply {
        background = targetColor
        visibility = GONE
    }
    private val trail = UndoTrail(host).apply { alpha = 0f }
    private val arrowIcon = host.airIconView(R.drawable.ic_air_undo, host.colorTextMuted).apply {
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    }
    private val arrow = FrameLayout(host).apply {
        clipChildren = false
        addView(arrowIcon, LayoutParams(arrowSize, arrowSize, Gravity.CENTER))
        addView(trail, LayoutParams(host.dp(7), host.dp(8), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = -host.dp(6)
        })
    }
    private val fill = GradientDrawable().apply {
        cornerRadius = host.dp(AirUiTokens.Radius.Sm).toFloat()
    }
    private var target: SettingsUndoTarget? = null
    private var returnAnimator: ValueAnimator? = null

    val labelWidth: Int
        get() = maxOf(minimumWidth, arrowSize + gap + horizontalPadding * 2 +
            ceil(Layout.getDesiredWidth(label.text, label.paint)).toInt())

    val maxTargetExtraWidth: Int = host.dp(48) + gap

    init {
        minimumWidth = host.dp(AirUiTokens.Layout.IconTouchSize)
        minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
        clipChildren = false
        isFocusable = true
        setTag(R.id.interaction_anchor, "floating.panel.undo")
        tooltipText = host.getString(R.string.ui_undo)
        val wrap = LayoutParams.WRAP_CONTENT
        addView(LinearLayout(host).apply {
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            isDuplicateParentStateEnabled = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            minimumWidth = host.dp(AirUiTokens.Layout.IconTouchSize)
            minimumHeight = host.dp(AirUiTokens.Layout.DialogCloseSize)
            val verticalPadding = host.dp(AirUiTokens.Space.Sm)
            setPaddingRelative(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorTextMuted, 36)), fill, null
            )
            addView(arrow, LinearLayout.LayoutParams(arrowSize, arrowSize))
            addView(label, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = gap })
            addView(targetText, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = gap })
            addView(swatch, LinearLayout.LayoutParams(swatchSize, swatchSize).apply { marginStart = gap })
        }, LayoutParams(wrap, wrap, Gravity.CENTER))
        enableSoftPressFeedback()
        setOnClickListener { if (isEnabled) onUndo() }
        updateAction(null)
        host.bindAppearance(this) { renderColors() }
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    fun setPresentation(showLabel: Boolean, targetBudget: Int) {
        label.visibility = if (showLabel) VISIBLE else GONE
        val color = target?.color
        val textWidth = ceil(Layout.getDesiredWidth(targetText.text, targetText.paint)).toInt()
        val hasRoomForText = showLabel && color == null && textWidth > 0 && textWidth + gap <= targetBudget
        val hasRoomForSwatch = showLabel && color != null && swatchSize + gap <= targetBudget
        targetText.visibility = if (hasRoomForText) VISIBLE else GONE
        swatch.visibility = if (hasRoomForSwatch) VISIBLE else GONE
    }

    fun updateAction(next: SettingsUndoAction?) {
        isEnabled = next != null
        contentDescription = next?.description ?: host.getString(R.string.ui_undo)
        val nextTarget = next?.target
        if (target != nextTarget) {
            target = nextTarget
            targetText.text = nextTarget?.takeIf { it.color == null }?.value.orEmpty()
            nextTarget?.color?.let(targetColor::setColor)
            requestLayout()
        }
        renderColors()
    }

    fun animateUndo() {
        finishAnimation()
        if (!canAnimateFloatingControl()) return
        val direction = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f
        val shift = host.resources.displayMetrics.density * direction
        returnAnimator = ValueAnimator.ofPropertyValuesHolder(
            keyframes("shift", 0f to 0f, .20f to 2f * shift, .50f to -4f * shift, .80f to shift, 1f to 0f),
            keyframes("angle", 0f to 0f, .20f to 8f * direction, .50f to -14f * direction, .80f to 0f, 1f to 0f),
            keyframes("trail", 0f to 0f, .20f to 0f, .38f to .85f, .80f to 0f, 1f to 0f),
            keyframes("trailWidth", 0f to .25f, .45f to 1f, .85f to .25f, 1f to .25f)
        ).apply {
            duration = AirUiTokens.Motion.UndoArrowMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                arrow.translationX = it.getAnimatedValue("shift") as Float
                arrow.rotation = it.getAnimatedValue("angle") as Float
                trail.alpha = it.getAnimatedValue("trail") as Float
                trail.scaleX = it.getAnimatedValue("trailWidth") as Float
            }
            doOnEnd { if (returnAnimator === it) finishAnimation() }
        }.also {
            renderColors()
            it.start()
        }
    }

    fun finishAnimation() {
        val running = returnAnimator ?: return
        returnAnimator = null
        running.cancel()
        arrow.translationX = 0f
        arrow.rotation = 0f
        trail.alpha = 0f
        renderColors()
    }

    private fun renderColors() {
        val emphasized = isEnabled || returnAnimator != null
        val surface = host.colorSurfaceLight
        val backgroundColor = if (emphasized) ColorUtils.blendARGB(surface, host.colorAccentMint, .19f) else surface
        val mint = host.colorSuccessIcon
        val ink = when {
            !emphasized -> ColorUtils.blendARGB(host.colorTextMuted, surface, .36f)
            ColorUtils.calculateContrast(mint, backgroundColor) >= 4.5 -> mint
            else -> ColorUtils.blendARGB(mint, host.colorTextStrong, .35f)
        }
        fill.setColor(backgroundColor)
        arrowIcon.imageTintList = ColorStateList.valueOf(ink)
        trail.setColor(ink)
        label.setTextColor(ink)
        targetText.setTextColor(ink)
        targetColor.setStroke(host.dp(AirUiTokens.Stroke.Hairline), host.colorStroke)
    }

    private fun keyframes(property: String, vararg points: Pair<Float, Float>): PropertyValuesHolder =
        PropertyValuesHolder.ofKeyframe(property, *points.map { (fraction, value) ->
            Keyframe.ofFloat(fraction, value).apply { interpolator = floatingPanelEaseOut }
        }.toTypedArray())
}

@SuppressLint("ViewConstructor")
private class UndoTrail(host: MainUiHost) : View(host) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = host.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    fun setColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = paint.strokeWidth
        canvas.drawLine(inset, height * .3f, width - inset, height * .3f, paint)
        canvas.drawLine(inset, height * .7f, width * .7f, height * .7f, paint)
    }
}
