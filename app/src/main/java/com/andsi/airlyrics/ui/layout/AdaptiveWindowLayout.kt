package com.andsi.airlyrics.ui.layout

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.LinearLayout
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.navigation.createBottomTabs
import com.andsi.airlyrics.ui.navigation.updateTabs

@SuppressLint("ViewConstructor")
internal class AdaptiveWindowLayout(private val host: MainUiHost, private val content: View) : ViewGroup(host) {
    private var rail: Boolean? = null
    private val tabs = createBottomTabs(host)
    private val bottomHorizontalPadding = tabs.paddingLeft
    private val labelHorizontalPadding = host.tabViews.values.first().paddingLeft
    private val railScroll = ScrollView(host).apply { isFillViewport = false }
    private var navigation: View = tabs

    init {
        addView(content)
        addView(tabs)
        updateTabs(host, animate = false)
    }

    private fun configureNavigation(useRail: Boolean) {
        if (rail == useRail) return
        if (tabs.parent === railScroll) railScroll.removeView(tabs) else removeView(tabs)
        removeView(railScroll)
        val row = requireNotNull(host.tabRow)
        tabs.setPadding(if (useRail) host.dp(2) else bottomHorizontalPadding, tabs.paddingTop,
            if (useRail) host.dp(2) else bottomHorizontalPadding, tabs.paddingBottom)
        host.tabViews.values.forEach { label ->
            val horizontal = if (useRail) host.dp(2) else labelHorizontalPadding
            label.setPadding(horizontal, label.paddingTop, horizontal, label.paddingBottom)
        }
        row.orientation = if (useRail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (index in 0 until row.childCount) {
            row.getChildAt(index).layoutParams = if (useRail) {
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            } else LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        navigation = if (useRail) railScroll.also { it.addView(tabs) } else tabs
        addView(navigation)
        rail = useRail
        updateTabs(host, animate = false)
    }

    private fun updateWindowSpec(width: Float, height: Float) {
        val old = host.windowLayout
        val scale = resources.configuration.fontScale
        if (old.width != width || old.height != height || old.fontScale != scale) {
            host.windowLayout = WindowLayoutSpec(width, height, scale)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val density = resources.displayMetrics.density
        val useRail = availableWidth / density >= 600f
        configureNavigation(useRail)
        val nav = navigation
        val navWidth = if (useRail) host.dp(WindowLayoutSpec.RAIL).coerceAtMost(availableWidth) else availableWidth
        nav.measure(MeasureSpec.makeMeasureSpec(navWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(availableHeight, if (useRail) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
        val contentHeight = (availableHeight - if (useRail) 0 else nav.measuredHeight).coerceAtLeast(0)
        updateWindowSpec(availableWidth / density, contentHeight / density)
        content.measure(MeasureSpec.makeMeasureSpec((availableWidth - if (useRail) navWidth else 0).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((availableHeight - if (useRail) 0 else nav.measuredHeight).coerceAtLeast(0), MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val nav = navigation
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        if (rail == true) {
            val navX = if (rtl) width - paddingRight - nav.measuredWidth else paddingLeft
            val contentX = if (rtl) paddingLeft else paddingLeft + nav.measuredWidth
            nav.layout(navX, paddingTop, navX + nav.measuredWidth, height - paddingBottom)
            content.layout(contentX, paddingTop, contentX + content.measuredWidth, height - paddingBottom)
        } else {
            content.layout(paddingLeft, paddingTop, width - paddingRight, paddingTop + content.measuredHeight)
            nav.layout(paddingLeft, height - paddingBottom - nav.measuredHeight, width - paddingRight, height - paddingBottom)
        }
    }
}
