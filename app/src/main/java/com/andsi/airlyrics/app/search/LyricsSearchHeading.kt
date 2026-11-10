package com.andsi.airlyrics.app.search

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Keeps a useful title width; large type and compact windows move the action to a second row. */
@android.annotation.SuppressLint("ViewConstructor")
internal class LyricsSearchHeading(
    context: Context,
    private val back: View,
    private val title: View,
    private val action: View
) : ViewGroup(context) {
    private var stacked = false
    private val gap get() = dp(8)

    init {
        addView(back)
        addView(title)
        addView(action)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        back.measure(exact(dp(48)), exact(dp(48)))
        val hasAction = action.visibility != GONE
        if (hasAction) action.measure(atMost((width - gap).coerceAtLeast(0)), unspecified())
        val actionWidth = if (hasAction) action.measuredWidth else 0
        stacked = hasAction && width - back.measuredWidth - actionWidth - gap < dp(136)
        val titleWidth = (width - back.measuredWidth - if (stacked || !hasAction) 0 else actionWidth + gap)
            .coerceAtLeast(0)
        title.measure(exact(titleWidth), unspecified())
        val firstHeight = max(back.measuredHeight, title.measuredHeight)
        val height = if (stacked) firstHeight + gap + action.measuredHeight
            else max(firstHeight, if (hasAction) action.measuredHeight else 0)
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rowHeight = if (stacked) max(back.measuredHeight, title.measuredHeight) else height
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        fun place(view: View, start: Int, top: Int) {
            val left = if (rtl) width - start - view.measuredWidth else start
            view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
        }
        place(back, 0, (rowHeight - back.measuredHeight) / 2)
        place(title, back.measuredWidth, (rowHeight - title.measuredHeight) / 2)
        if (action.visibility != GONE) {
            // This control is the physical top-right anchor of the container transform.
            val top = if (stacked) rowHeight + gap else (rowHeight - action.measuredHeight) / 2
            val left = width - action.measuredWidth
            action.layout(left, top, width, top + action.measuredHeight)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    private fun atMost(value: Int) = MeasureSpec.makeMeasureSpec(max(0, value), MeasureSpec.AT_MOST)
    private fun unspecified() = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
}
