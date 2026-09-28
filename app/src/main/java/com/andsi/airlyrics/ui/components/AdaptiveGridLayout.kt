package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone

/** Action groups fit text, swatches fit available width, and setting tiles keep fixed columns. */
internal sealed interface GridColumnPolicy {
    data object Content : GridColumnPolicy
    data object Fixed : GridColumnPolicy
    data class Width(val minCellDp: Int) : GridColumnPolicy
}

/** Reflows existing children without replacing views, callbacks or selection. */
@SuppressLint("ViewConstructor")
internal open class AdaptiveGridLayout(
    context: Context,
    private val maxColumns: Int,
    private val horizontalGap: Int,
    private val verticalGap: Int,
    private val comfortableLines: Int = 1,
    private val fillCells: Boolean = true,
    private val columnPolicy: GridColumnPolicy = GridColumnPolicy.Content
) : LinearLayout(context) {
    internal var columns: Int = 1
        private set
    private var rows: List<List<View>> = emptyList()

    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val children = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            children.sumOf { child ->
                child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
                child.measuredWidth
            } + horizontalGap * (children.size - 1).coerceAtLeast(0) + paddingLeft + paddingRight
        } else MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        columns = maxColumns.coerceAtLeast(1)
        if (columnPolicy != GridColumnPolicy.Fixed) columns = columns.coerceAtMost(children.size.coerceAtLeast(1))
        if (columnPolicy is GridColumnPolicy.Width) {
            val minWidth = columnPolicy.minCellDp * resources.displayMetrics.density *
                resources.configuration.fontScale.coerceAtLeast(1f)
            columns = ((available + horizontalGap) / (minWidth + horizontalGap)).toInt()
                .coerceIn(1, columns)
        }
        fun measureColumns() {
            val cellWidth = ((available - horizontalGap * (columns - 1)) / columns).coerceAtLeast(0)
            children.forEach { child ->
                child.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
            }
        }
        if (fillCells) {
            measureColumns()
            while (columnPolicy == GridColumnPolicy.Content && columns > 1 && children.any { !textFits(it) }) {
                columns--
                measureColumns()
            }
        } else {
            children.forEach {
                it.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            }
            if (children.sumOf { it.measuredWidth } + horizontalGap * (children.size - 1) > available) columns = 1
        }
        rows = children.chunked(columns)
        var height = paddingTop + paddingBottom + verticalGap * (rows.size - 1).coerceAtLeast(0)
        rows.forEach { row ->
            val rowHeight = row.maxOf { it.measuredHeight }
            row.forEach {
                it.measure(
                    MeasureSpec.makeMeasureSpec(it.measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
                )
            }
            height += rowHeight
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height.coerceAtLeast(minimumHeight), heightMeasureSpec))
    }

    private fun textFits(view: View): Boolean = when {
        view.isGone -> true
        view is TextView -> view.fullTextLineCount() <= comfortableLines
        view is ViewGroup -> (0 until view.childCount).all { textFits(view.getChildAt(it)) }
        else -> true
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = paddingTop
        rows.forEach { row ->
            val rowWidth = row.sumOf { it.measuredWidth } + horizontalGap * (row.size - 1)
            var offset = if (fillCells) 0 else (width - paddingLeft - paddingRight - rowWidth).coerceAtLeast(0)
            row.forEach { child ->
                val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                    width - paddingRight - offset - child.measuredWidth
                } else paddingLeft + offset
                child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
                offset += child.measuredWidth + horizontalGap
            }
            y += row.first().measuredHeight + verticalGap
        }
    }
}
