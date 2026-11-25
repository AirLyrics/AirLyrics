package com.andsi.airlyrics.app.host

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal class LyricsEditorModeSwitch(
    host: MainActivityUiHost,
    bulkEditing: Boolean,
    onModeClick: (Boolean) -> Unit,
) {
    val view = FrameLayout(host)
    private val inset = host.dp(3)
    private val selection = View(host).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = GradientDrawable().apply {
            cornerRadius = host.dp(10).toFloat()
            setColor(ColorUtils.blendARGB(host.colorSurfaceLight, host.colorAccent, 0.20f))
        }
    }
    private val modes = RadioGroup(host).apply { orientation = LinearLayout.HORIZONTAL }
    private val timeline = modeButton(host, R.string.ui_lyrics_mode_timeline) { onModeClick(false) }
    private val text = modeButton(host, R.string.ui_lyrics_mode_text) { onModeClick(true) }
    private var selected = if (bulkEditing) text else timeline

    init {
        view.setPadding(inset, inset, inset, inset)
        view.background = GradientDrawable().apply {
            cornerRadius = host.dp(13).toFloat()
            setColor(host.colorSurfaceLight)
            setStroke(host.dp(AirUiTokens.Stroke.Hairline), host.colorStroke)
        }
        view.addView(selection, LayoutParams(0, 0, Gravity.START))
        modes.addView(timeline, RadioGroup.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        modes.addView(text, RadioGroup.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        view.addView(modes, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        modes.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val boundsChanged = (left != oldLeft) || (top != oldTop) ||
                (right != oldRight) || (bottom != oldBottom)
            if (boundsChanged || (selection.layoutParams.width == 0)) {
                positionSelection(animate = false)
            }
        }
        modes.check(selected.id)
    }

    fun select(bulkEditing: Boolean) {
        val next = if (bulkEditing) text else timeline
        modes.check(next.id)
        if (next === selected) return
        selected = next
        positionSelection(ValueAnimator.areAnimatorsEnabled())
    }

    fun setInteractive(enabled: Boolean) {
        timeline.isEnabled = enabled
        text.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.55f
    }

    private fun positionSelection(animate: Boolean) {
        val width = selected.width
        val height = modes.height
        if ((width == 0) || (height == 0)) return
        if ((selection.layoutParams.width != width) || (selection.layoutParams.height != height)) {
            selection.layoutParams = selection.layoutParams.apply {
                this.width = width
                this.height = height
            }
        }
        val origin = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) modes.width - width else 0
        val target = (selected.left - origin).toFloat()
        selection.animate().cancel()
        if (animate) {
            selection.animate().translationX(target)
                .setDuration(AirUiTokens.Layout.TabAnimationMs)
                .setInterpolator(PathInterpolator(0.77f, 0f, 0.175f, 1f)).start()
        } else {
            selection.translationX = target
        }
    }

    private fun modeButton(host: MainActivityUiHost, label: Int, onClick: () -> Unit): RadioButton =
        RadioButton(host).apply {
            id = View.generateViewId()
            buttonDrawable = null
            minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
            minWidth = 0
            setPadding(host.dp(6), host.dp(5), host.dp(6), host.dp(5))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = AirUiTokens.TextSize.Button
            setText(label)
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(host.colorTextStrong, host.colorTextMuted),
                ),
            )
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorAccent, 36)),
                null,
                GradientDrawable().apply {
                    cornerRadius = host.dp(10).toFloat()
                    setColor(Color.WHITE)
                },
            )
            enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
            setOnClickListener { onClick() }
        }
}
