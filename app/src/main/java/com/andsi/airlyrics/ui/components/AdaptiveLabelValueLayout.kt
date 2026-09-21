package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.text.Layout
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/**
 * Keeps a label and trailing value on one line while they fit, then stacks them when
 * either localized or user-provided text would otherwise squeeze the label away.
 */
@SuppressLint("ViewConstructor") // Programmatic-only view; its children and spacing are required.
internal class AdaptiveLabelValueLayout(
    context: Context,
    internal val labelView: View,
    internal val valueView: View,
    internal val trailingView: View? = null,
    private val horizontalGapPx: Int,
    private val verticalGapPx: Int,
    private val trailingGapPx: Int
) : ViewGroup(context) {
    internal var isStacked: Boolean = false
        private set

    init {
        addView(labelView)
        addView(valueView)
        trailingView?.let(::addView)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }

        measureAtNaturalWidth(labelView, heightMeasureSpec)
        measureAtNaturalWidth(valueView, heightMeasureSpec)
        trailingView?.let { measureAtNaturalWidth(it, heightMeasureSpec) }

        val naturalContentWidth = naturalWidth(labelView) +
            horizontalGapPx +
            naturalWidth(valueView) +
            trailingContentWidth()
        isStacked = widthMode != MeasureSpec.UNSPECIFIED && naturalContentWidth > availableWidth

        val desiredContentWidth: Int
        val desiredContentHeight: Int
        if (isStacked) {
            measureWithinWidth(labelView, availableWidth, heightMeasureSpec)

            val valueWidth = (availableWidth - trailingContentWidth()).coerceAtLeast(0)
            measureWithinWidth(valueView, valueWidth, heightMeasureSpec)

            val trailingRowWidth = valueView.measuredWidth + trailingContentWidth()
            val trailingRowHeight = max(
                valueView.measuredHeight,
                trailingView?.measuredHeight ?: 0
            )
            desiredContentWidth = max(labelView.measuredWidth, trailingRowWidth)
            desiredContentHeight = labelView.measuredHeight + verticalGapPx + trailingRowHeight
        } else {
            desiredContentWidth = naturalContentWidth
            desiredContentHeight = maxOf(
                labelView.measuredHeight,
                valueView.measuredHeight,
                trailingView?.measuredHeight ?: 0
            )
        }

        val desiredWidth = desiredContentWidth + paddingLeft + paddingRight
        val desiredHeight = desiredContentHeight + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSizeAndState(desiredWidth, widthMeasureSpec, 0),
            resolveSizeAndState(desiredHeight, heightMeasureSpec, 0)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (isStacked) {
            layoutStacked()
        } else {
            layoutHorizontal()
        }
    }

    private fun layoutHorizontal() {
        val contentTop = paddingTop
        val contentHeight = measuredHeight - paddingTop - paddingBottom
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            layoutAtRight(labelView, measuredWidth - paddingRight, contentTop, contentHeight)

            var endEdge = paddingLeft
            trailingView?.let { trailing ->
                layoutAtLeft(trailing, endEdge, contentTop, contentHeight)
                endEdge += trailing.measuredWidth + trailingGapPx
            }
            layoutAtLeft(valueView, endEdge, contentTop, contentHeight)
        } else {
            layoutAtLeft(labelView, paddingLeft, contentTop, contentHeight)

            var endEdge = measuredWidth - paddingRight
            trailingView?.let { trailing ->
                layoutAtRight(trailing, endEdge, contentTop, contentHeight)
                endEdge -= trailing.measuredWidth + trailingGapPx
            }
            layoutAtRight(valueView, endEdge, contentTop, contentHeight)
        }
    }

    private fun layoutStacked() {
        val labelTop = paddingTop
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            layoutAtRight(
                labelView,
                measuredWidth - paddingRight,
                labelTop,
                labelView.measuredHeight
            )
        } else {
            layoutAtLeft(labelView, paddingLeft, labelTop, labelView.measuredHeight)
        }

        val trailingRowTop = labelTop + labelView.measuredHeight + verticalGapPx
        val trailingRowHeight = max(
            valueView.measuredHeight,
            trailingView?.measuredHeight ?: 0
        )
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            var endEdge = paddingLeft
            trailingView?.let { trailing ->
                layoutAtLeft(trailing, endEdge, trailingRowTop, trailingRowHeight)
                endEdge += trailing.measuredWidth + trailingGapPx
            }
            layoutAtLeft(valueView, endEdge, trailingRowTop, trailingRowHeight)
        } else {
            var endEdge = measuredWidth - paddingRight
            trailingView?.let { trailing ->
                layoutAtRight(trailing, endEdge, trailingRowTop, trailingRowHeight)
                endEdge -= trailing.measuredWidth + trailingGapPx
            }
            layoutAtRight(valueView, endEdge, trailingRowTop, trailingRowHeight)
        }
    }

    private fun layoutAtLeft(view: View, left: Int, rowTop: Int, rowHeight: Int) {
        val childTop = rowTop + (rowHeight - view.measuredHeight) / 2
        view.layout(
            left,
            childTop,
            left + view.measuredWidth,
            childTop + view.measuredHeight
        )
    }

    private fun layoutAtRight(view: View, right: Int, rowTop: Int, rowHeight: Int) {
        val childTop = rowTop + (rowHeight - view.measuredHeight) / 2
        view.layout(
            right - view.measuredWidth,
            childTop,
            right,
            childTop + view.measuredHeight
        )
    }

    private fun trailingContentWidth(): Int {
        return trailingView?.let { trailingGapPx + it.measuredWidth } ?: 0
    }

    private fun naturalWidth(view: View): Int {
        if (view !is TextView) return view.measuredWidth

        val textWidth = ceil(Layout.getDesiredWidth(view.text, view.paint).toDouble()).toInt()
        return max(view.measuredWidth, textWidth + view.compoundPaddingLeft + view.compoundPaddingRight)
    }

    private fun measureAtNaturalWidth(view: View, parentHeightMeasureSpec: Int) {
        val params = view.layoutParams
        val widthSpec = if (params.width >= 0) {
            MeasureSpec.makeMeasureSpec(params.width, MeasureSpec.EXACTLY)
        } else {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        }
        view.measure(widthSpec, childHeightMeasureSpec(view, parentHeightMeasureSpec))
    }

    private fun measureWithinWidth(
        view: View,
        availableWidth: Int,
        parentHeightMeasureSpec: Int
    ) {
        val params = view.layoutParams
        val width = availableWidth.coerceAtLeast(0)
        val widthSpec = when {
            params.width >= 0 -> MeasureSpec.makeMeasureSpec(
                params.width.coerceAtMost(width),
                MeasureSpec.EXACTLY
            )
            params.width == LayoutParams.MATCH_PARENT ->
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            else -> MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        }
        view.measure(widthSpec, childHeightMeasureSpec(view, parentHeightMeasureSpec))
    }

    private fun childHeightMeasureSpec(view: View, parentHeightMeasureSpec: Int): Int {
        return getChildMeasureSpec(
            parentHeightMeasureSpec,
            paddingTop + paddingBottom,
            view.layoutParams.height
        )
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    override fun generateLayoutParams(attrs: android.util.AttributeSet): LayoutParams {
        return LayoutParams(context, attrs)
    }

    override fun generateLayoutParams(params: LayoutParams): LayoutParams {
        return LayoutParams(params)
    }

    override fun checkLayoutParams(params: LayoutParams): Boolean = true
}
