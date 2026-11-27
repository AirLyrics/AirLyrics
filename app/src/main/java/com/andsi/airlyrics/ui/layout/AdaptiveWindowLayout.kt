package com.andsi.airlyrics.ui.layout

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.andsi.airlyrics.ui.insets.remainingTopSystemInset
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.navigation.createBottomTabs
import com.andsi.airlyrics.ui.navigation.updateTabs

@SuppressLint("ViewConstructor")
internal class AdaptiveWindowLayout(private val host: MainUiHost, private val content: View) : ViewGroup(host) {
    private var rail = false
    private var imeOverlap = 0
    private val tabs = createBottomTabs(host)
    private val tabBackground = tabs.background as GradientDrawable
    private val bottomCorners = tabBackground.cornerRadii
    private val bottomHorizontalPadding = tabs.paddingLeft
    private val labelHorizontalPadding = host.tabViews.values.first().paddingLeft
    private val navigation = ScrollView(host).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(tabs)
    }

    init {
        addView(content)
        addView(navigation)
        updateTabs(host, animate = false)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val overlap = (ime.bottom - safe.bottom).coerceAtLeast(0)
            if (overlap != imeOverlap) {
                imeOverlap = overlap
                requestLayout()
            }
            setPadding(safe.left, remainingTopSystemInset(safe.top), safe.right, maxOf(safe.bottom, ime.bottom))
            insets
        }
        post { ViewCompat.requestApplyInsets(this) }
    }

    private fun configureNavigation(useRail: Boolean) {
        if (rail == useRail) return
        rail = useRail
        val horizontal = if (useRail) host.dp(2) else bottomHorizontalPadding
        tabs.setPadding(horizontal, tabs.paddingTop, horizontal, tabs.paddingBottom)
        tabBackground.cornerRadii = if (useRail) null else bottomCorners
        host.tabViews.values.forEach { label ->
            val inset = if (useRail) host.dp(2) else labelHorizontalPadding
            label.setPadding(inset, label.paddingTop, inset, label.paddingBottom)
        }
        val row = requireNotNull(host.tabRow)
        row.orientation = if (useRail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        row.children.forEach { slot ->
            slot.layoutParams = if (useRail) {
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        updateTabs(host, animate = false)
    }

    private fun windowSpec(widthDp: Float, heightDp: Float): WindowLayoutSpec {
        val current = host.windowLayout
        val scale = resources.configuration.fontScale
        if ((current.widthDp == widthDp) && (current.heightDp == heightDp) && (current.fontScale == scale)) return current
        return WindowLayoutSpec(widthDp, heightDp, scale).also { host.windowLayout = it }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val density = resources.displayMetrics.density
        val layout = windowSpec(availableWidth / density, (availableHeight + imeOverlap) / density)
        configureNavigation(layout.railWidthDp > 0)
        val navWidth = if (rail) host.dp(layout.railWidthDp) else availableWidth
        navigation.measure(
            MeasureSpec.makeMeasureSpec(navWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(availableHeight, if (rail) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST),
        )
        val contentWidth = if (rail) availableWidth - navWidth else availableWidth
        val contentHeight = if (rail) availableHeight else availableHeight - navigation.measuredHeight
        content.measure(
            MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val bottom = paddingTop + (height - paddingTop - paddingBottom).coerceAtLeast(0)
        if (rail) {
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val navX = if (rtl) width - paddingRight - navigation.measuredWidth else paddingLeft
            val contentX = if (rtl) paddingLeft else paddingLeft + navigation.measuredWidth
            navigation.layout(navX, paddingTop, navX + navigation.measuredWidth, bottom)
            content.layout(contentX, paddingTop, contentX + content.measuredWidth, bottom)
        } else {
            val right = paddingLeft + content.measuredWidth
            val split = paddingTop + content.measuredHeight
            content.layout(paddingLeft, paddingTop, right, split)
            navigation.layout(paddingLeft, split, right, bottom)
        }
    }
}
