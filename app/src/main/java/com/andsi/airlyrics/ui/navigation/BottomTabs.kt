package com.andsi.airlyrics.ui.navigation

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorSurface
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.widgets.WaterTabHighlightView

internal fun createBottomTabs(activity: MainActivity): View  = with(activity) createBottomTabs@ {
    val shell = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(86)
        )
        setPadding(dp(12), dp(8), dp(12), dp(12))
        clipToPadding = false
        clipChildren = false
        background = GradientDrawable().apply {
            setColor(colorSurface)
            cornerRadii = floatArrayOf(
                dp(24).toFloat(), dp(24).toFloat(),
                dp(24).toFloat(), dp(24).toFloat(),
                0f, 0f,
                0f, 0f
            )
        }
    }

    tabHighlight = WaterTabHighlightView(this, colorAccent).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipToPadding = false
        clipChildren = false
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
    }
    tabRow = bar

    addTab(activity, bar, Page.MEDIA, "媒体流")
    addTab(activity, bar, Page.FLOATING, "悬浮窗")
    addTab(activity, bar, Page.SETTINGS, "设置")

    shell.addView(tabHighlight)
    shell.addView(bar)
    return shell
}

internal fun addTab(activity: MainActivity, parent: LinearLayout, page: Page, title: String) = with(activity) addTab@ {
    val slot = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        clipToPadding = false
        clipChildren = false
        isClickable = true
        enableSoftPressFeedback(0.97f)
        setOnClickListener {
            if (page == Page.FLOATING && currentPage == Page.FLOATING) {
                uiActions.toggleFloatingFromNav()
                return@setOnClickListener
            }
            uiActions.selectPage(page)
        }
    }

    val tab = TextView(this).apply {
        text = title
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setPadding(dp(18), dp(8), dp(18), dp(8))
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
    }

    tabViews[page] = tab
    slot.addView(tab)
    parent.addView(slot)
}

internal fun quickFloatingTabText(activity: MainActivity, visible: Boolean): SpannableString  = with(activity) quickFloatingTabText@ {
    val icon = if (visible) "×" else "♪"
    val label = if (visible) "隐藏" else "显示"
    return SpannableString("$icon\n$label").apply {
        setSpan(AbsoluteSizeSpan(24, true), 0, icon.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(AbsoluteSizeSpan(10, true), icon.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

internal fun measureTabTextWidth(activity: MainActivity, tab: TextView): Float  = with(activity) measureTabTextWidth@ {
    val lines = tab.text.toString().split('\n')
    return lines.maxOfOrNull { tab.paint.measureText(it) } ?: tab.paint.measureText(tab.text.toString())
}

internal fun updateTabs(activity: MainActivity, animate: Boolean = true): Unit = with(activity) updateTabs@ {
    tabViews.forEach { (page, view) ->
        val selected = page == currentPage
        val quickControlSelected = page == Page.FLOATING && selected
        val targetText: CharSequence = if (quickControlSelected) {
            quickFloatingTabText(activity, quickFloatingVisible)
        } else {
            when (page) {
                Page.MEDIA -> "媒体流"
                Page.FLOATING -> "悬浮窗"
                Page.SETTINGS -> "设置"
            }
        }
        if (view.text.toString() != targetText.toString()) {
            view.animate().cancel()
            view.alpha = 0.55f
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.text = targetText
        }
        view.textSize = 15f
        view.setLineSpacing(0f, 0.92f)
        view.setTextColor(if (selected) Color.WHITE else colorTextMuted)
        view.background = null
        val targetScale = if (quickControlSelected) 1.14f else if (selected) 1.02f else 1f
        val targetAlpha = if (selected) 1f else 0.86f
        if (animate) {
            view.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .alpha(targetAlpha)
                .setDuration(190L)
                .setInterpolator(OvershootInterpolator(1.08f))
                .start()
        } else {
            view.animate().cancel()
            view.scaleX = targetScale
            view.scaleY = targetScale
            view.alpha = targetAlpha
        }
    }

    val selectedTab = tabViews[currentPage] ?: return@updateTabs
    selectedTab.post {
        val highlight = tabHighlight ?: return@post
        val selectedSlot = selectedTab.parent as? View ?: selectedTab
        val textWidth = measureTabTextWidth(activity, selectedTab)
        val horizontalPadding = if (currentPage == Page.FLOATING) dp(62) else dp(58)
        val targetWidth = (textWidth + horizontalPadding).coerceIn(
            dp(104).toFloat(),
            if (currentPage == Page.FLOATING) dp(136).toFloat() else dp(144).toFloat()
        )
        val targetHeight = if (currentPage == Page.FLOATING) dp(56).toFloat() else dp(48).toFloat()

        val slotLocation = IntArray(2)
        val highlightLocation = IntArray(2)
        selectedSlot.getLocationInWindow(slotLocation)
        highlight.getLocationInWindow(highlightLocation)

        val centerX = slotLocation[0] - highlightLocation[0] + selectedSlot.width / 2f
        val centerY = slotLocation[1] - highlightLocation[1] + selectedSlot.height / 2f
        highlight.moveTo(
            targetCenterX = centerX,
            targetCenterY = centerY,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            animate = highlight.hasPosition
        )
    }
}
