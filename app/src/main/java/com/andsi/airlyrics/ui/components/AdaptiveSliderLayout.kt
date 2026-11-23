package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import kotlin.math.max

@SuppressLint("ViewConstructor")
internal class AdaptiveSliderLayout(
    host: MainUiHost,
    private val label: View,
    private val slider: View,
    private val value: View
) : ViewGroup(host) {
    private val gap = host.dp(AirUiTokens.SettingsPanel.ControlGap)
    private val lineGap = host.dp(AirUiTokens.Space.Sm)
    private val trackMinWidth = host.dp(96)
    private val trackHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
    internal var isStacked = false
        private set
    private var stackedValue = false
    private var headerHeight = 0

    init {
        addView(label)
        addView(slider)
        addView(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        label.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        value.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            label.measuredWidth + value.measuredWidth + gap * 2 + trackMinWidth
        } else MeasureSpec.getSize(widthMeasureSpec)
        isStacked = width - label.measuredWidth - value.measuredWidth - gap * 2 < trackMinWidth
        stackedValue = isStacked && label.measuredWidth + value.measuredWidth + gap > width
        if (isStacked) {
            label.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            value.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            headerHeight = if (stackedValue) label.measuredHeight + lineGap + value.measuredHeight
                else max(label.measuredHeight, value.measuredHeight)
        }
        val sliderWidth = if (isStacked) width else width - label.measuredWidth - value.measuredWidth - gap * 2
        slider.measure(MeasureSpec.makeMeasureSpec(sliderWidth.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(trackHeight, MeasureSpec.EXACTLY))
        val height = if (isStacked) headerHeight + trackHeight
            else maxOf(trackHeight, label.measuredHeight, value.measuredHeight)
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (isStacked) {
            place(label, 0, 0)
            place(value, if (stackedValue) 0 else width - value.measuredWidth,
                if (stackedValue) label.measuredHeight + lineGap else (headerHeight - value.measuredHeight) / 2)
            place(slider, 0, headerHeight)
        } else {
            place(label, 0, (height - label.measuredHeight) / 2)
            place(slider, label.measuredWidth + gap, (height - slider.measuredHeight) / 2)
            place(value, width - value.measuredWidth, (height - value.measuredHeight) / 2)
        }
    }

    private fun place(view: View, start: Int, top: Int) {
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - view.measuredWidth else start
        view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
