package com.andsi.airlyrics.ui.layout

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import com.andsi.airlyrics.ui.model.MainUiHost

@SuppressLint("ViewConstructor")
internal class ResponsiveColumns(private val host: MainUiHost) : ViewGroup(host) {
    private val leadingCards = mutableSetOf<View>()
    private val gap = host.dp(WindowLayoutSpec.COLUMN_GAP_DP)
    private var split = false
    private val columnWidths = IntArray(2)
    private val columnOffsets = IntArray(2)

    fun addLeading(view: View) {
        leadingCards.add(view)
        addView(view)
    }

    private fun column(view: View): Int = if (split && (view !in leadingCards)) 1 else 0

    override fun generateDefaultLayoutParams() = MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(params: LayoutParams) = MarginLayoutParams(params)
    override fun checkLayoutParams(params: LayoutParams): Boolean = params is MarginLayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        split = leadingCards.isNotEmpty() && childCount > leadingCards.size &&
            host.windowLayout.usesTwoColumns(width / resources.displayMetrics.density)
        columnWidths[0] = if (split) (width - gap) / 2 else width
        columnWidths[1] = if (split) width - gap - columnWidths[0] else 0
        columnOffsets.fill(0)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isGone) continue
            val column = column(child)
            val params = child.layoutParams as MarginLayoutParams
            measureChildWithMargins(
                child,
                MeasureSpec.makeMeasureSpec(columnWidths[column], MeasureSpec.EXACTLY), 0,
                MeasureSpec.UNSPECIFIED, 0,
            )
            columnOffsets[column] += params.topMargin + child.measuredHeight + params.bottomMargin
        }
        setMeasuredDimension(width, resolveSize(columnOffsets.max(), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        columnOffsets.fill(0)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isGone) continue
            val column = column(child)
            val offset = if (column == 0) 0 else columnWidths[0] + gap
            val params = child.layoutParams as MarginLayoutParams
            val left = if (rtl) width - offset - params.rightMargin - child.measuredWidth else offset + params.leftMargin
            val top = columnOffsets[column] + params.topMargin
            val bottom = top + child.measuredHeight
            child.layout(left, top, left + child.measuredWidth, bottom)
            columnOffsets[column] = bottom + params.bottomMargin
        }
    }
}
