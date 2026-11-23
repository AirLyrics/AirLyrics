package com.andsi.airlyrics.ui.components

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import androidx.core.animation.doOnEnd
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import kotlin.math.roundToInt

/** The same value layout and drag target are used for style and RGB sliders. */
@SuppressLint("ViewConstructor")
internal class SettingsSliderRow(
    host: MainUiHost,
    title: String,
    initialValue: String,
    compact: Boolean = false
) : LinearLayout(host) {
    val valueView = TextView(host).apply {
        text = initialValue
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
        gravity = Gravity.END
        setTextColor(host.colorTextStrong)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var range: SliderRange? = null

    fun updateValue(value: String, announce: Boolean = true) {
        if (valueView.text != value) valueView.text = value
        if (announce) ViewCompat.setStateDescription(seekBar, value)
    }

    fun bindRange(
        value: Int,
        min: Int,
        max: Int,
        step: Int = 1,
        format: (Int) -> String,
        onPreview: (Int) -> Unit,
        onCommit: (Int) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val labelWidth = maxOf(valueView.paint.measureText(format(min)), valueView.paint.measureText(format(max)))
        valueView.minWidth = maxOf(valueView.minWidth, kotlin.math.ceil(labelWidth).toInt())
        seekBar.max = (max - min) / step
        range = SliderRange(min, step, format, onPreview, onCommit, onCancel).also {
            it.show(value, animate = false)
            seekBar.setOnSeekBarChangeListener(it)
        }
    }

    fun showRangeValue(value: Int) {
        range?.show(value, animate = false)
    }

    fun captureRestore(): (Int) -> Unit {
        val startProgress = seekBar.progress
        return { target -> range?.show(target, animate = true, fromProgress = startProgress) }
    }

    fun finishRangeAnimation() {
        range?.finishAnimation()
    }

    override fun onDetachedFromWindow() {
        range?.finishAnimation()
        range?.cancelDrag()
        super.onDetachedFromWindow()
    }

    val seekBar = object : AppCompatSeekBar(host) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    finishRangeAnimation()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP -> parent?.requestDisallowInterceptTouchEvent(false)
                MotionEvent.ACTION_CANCEL -> {
                    range?.cancelDrag()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return super.dispatchTouchEvent(event)
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            finishRangeAnimation()
            return super.onKeyDown(keyCode, event)
        }

        override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id ||
                action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                finishRangeAnimation()
            }
            return super.performAccessibilityAction(action, arguments)
        }
    }.apply {
        contentDescription = title
        minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
        thumbTintList = ColorStateList.valueOf(host.colorAccent)
        progressTintList = ColorStateList.valueOf(host.colorAccent)
        splitTrack = false
        ViewCompat.setStateDescription(this, initialValue)
    }

    init {
        orientation = VERTICAL
        setPadding(0, host.dp(AirUiTokens.Space.Xl), 0, host.dp(AirUiTokens.Space.Sm))
        val label = TextView(host).apply {
            text = title
            textSize = AirUiTokens.TextSize.Button
            setTextColor(host.colorTextStrong)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (compact) {
            valueView.minWidth = kotlin.math.ceil(valueView.paint.measureText(
                if (initialValue.endsWith("%")) "200%" else "255"
            ).toDouble()).toInt()
            addView(AdaptiveSliderLayout(host, label, seekBar, valueView),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            addView(AdaptiveLabelValueLayout(
                host, label, valueView,
                horizontalGapPx = host.dp(AirUiTokens.SettingsPanel.ControlGap),
                verticalGapPx = host.dp(AirUiTokens.Space.Sm),
                trailingGapPx = 0
            ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(seekBar, LayoutParams(LayoutParams.MATCH_PARENT, host.dp(AirUiTokens.Layout.IconTouchSize)))
        }
    }

    private inner class SliderRange(
        private val min: Int,
        private val step: Int,
        private val format: (Int) -> String,
        private val onPreview: (Int) -> Unit,
        private val onCommit: (Int) -> Unit,
        private val onCancel: (() -> Unit)?
    ) : SeekBar.OnSeekBarChangeListener {
        private var targetProgress = 0
        private var tracking = false
        private var changed = false
        private var animator: ValueAnimator? = null

        private fun value(progress: Int) = min + progress * step

        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            val value = value(progress)
            updateValue(format(value), announce = animator == null)
            if (!fromUser) return
            onPreview(value)
            if (tracking) changed = true else commit(progress)
        }

        override fun onStartTrackingTouch(bar: SeekBar) {
            tracking = true
            changed = false
        }

        override fun onStopTrackingTouch(bar: SeekBar) {
            tracking = false
            if (changed) commit(bar.progress)
            changed = false
        }

        private fun commit(progress: Int) {
            targetProgress = progress
            onCommit(value(progress))
        }

        fun cancelDrag() {
            val discard = onCancel ?: return
            val hadPreview = changed
            tracking = false
            changed = false
            if (hadPreview) {
                drawTarget()
                discard()
            }
        }

        fun show(value: Int, animate: Boolean, fromProgress: Int = seekBar.progress) {
            finishAnimation()
            targetProgress = ((value - min).toFloat() / step).roundToInt().coerceIn(0, seekBar.max)
            if (!animate || !seekBar.canAnimateFloatingControl() || fromProgress == targetProgress) {
                drawTarget()
                return
            }
            seekBar.progress = fromProgress
            ViewCompat.setStateDescription(seekBar, format(value(targetProgress)))
            animator = ValueAnimator.ofFloat(fromProgress.toFloat(), targetProgress.toFloat()).apply {
                duration = AirUiTokens.Motion.UndoControlMs
                interpolator = floatingPanelEaseOut
                addUpdateListener { seekBar.progress = (it.animatedValue as Float).roundToInt() }
                doOnEnd {
                    if (animator === it) {
                        animator = null
                        drawTarget()
                    }
                }
            }.also { it.start() }
        }

        fun finishAnimation() {
            val running = animator ?: return
            animator = null
            running.cancel()
            drawTarget()
        }

        private fun drawTarget() {
            seekBar.progress = targetProgress
            updateValue(format(value(targetProgress)))
        }
    }
}

internal fun panelSectionLabel(host: MainUiHost, text: String): TextView = TextView(host).apply {
    this.text = text
    textSize = AirUiTokens.TextSize.BodySmall
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(host.colorTextMuted)
    setPadding(0, host.dp(AirUiTokens.SettingsPanel.SectionGap), 0, host.dp(AirUiTokens.SettingsPanel.ControlGap))
    ViewCompat.setAccessibilityHeading(this, true)
}

internal fun panelSwitch(
    host: MainUiHost,
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
): SwitchCompat = settingSwitch(host, text, checked, onCheckedChange).apply {
    minHeight = host.dp(AirUiTokens.SettingsPanel.SwitchMinHeight)
    switchPadding = host.dp(AirUiTokens.Space.ButtonH)
    setPadding(0, host.dp(AirUiTokens.SettingsPanel.ControlGap), 0, host.dp(AirUiTokens.SettingsPanel.ControlGap))
}
