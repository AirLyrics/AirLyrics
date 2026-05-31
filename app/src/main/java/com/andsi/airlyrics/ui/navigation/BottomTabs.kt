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
import com.andsi.airlyrics.i18n.localizeText
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorSurface
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.widgets.WaterTabHighlightView
import com.andsi.airlyrics.i18n.tr
import com.andsi.airlyrics.ui.tokens.AirUiTokens

internal fun createBottomTabs(activity: MainActivity): View  = with(activity) createBottomTabs@ {
    val shell = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(AirUiTokens.Layout.BottomBarHeight)
        )
        setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xl), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs))
        clipToPadding = false
        clipChildren = false
        background = GradientDrawable().apply {
            setColor(colorSurface)
            cornerRadii = floatArrayOf(
                dp(AirUiTokens.Radius.Card).toFloat(), dp(AirUiTokens.Radius.Card).toFloat(),
                dp(AirUiTokens.Radius.Card).toFloat(), dp(AirUiTokens.Radius.Card).toFloat(),
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

    addTab(activity, bar, Page.MEDIA, tr("媒体流", "Media"))
    addTab(activity, bar, Page.FLOATING, tr("悬浮窗", "Floating"))
    addTab(activity, bar, Page.SETTINGS, tr("设置", "Settings"))

    shell.addView(tabHighlight)
    shell.addView(bar)
    return shell
}

internal fun addTab(activity: MainActivity, parent: LinearLayout, page: Page, title: String) = with(activity) addTab@ {
    val slot = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, AirUiTokens.Motion.RestScale)
        clipToPadding = false
        clipChildren = false
        isClickable = true
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
        setOnClickListener {
            if (page == Page.FLOATING && currentPage == Page.FLOATING) {
                uiActions.toggleFloatingFromNav()
                return@setOnClickListener
            }
            uiActions.selectPage(page)
        }
    }

    val tab = TextView(this).apply {
        text = localizeText(title)
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setPadding(dp(AirUiTokens.Space.CardH), dp(AirUiTokens.Space.Xl), dp(AirUiTokens.Space.CardH), dp(AirUiTokens.Space.Xl))
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
    val label = if (visible) tr("隐藏", "Hide") else tr("显示", "Show")
    return SpannableString("$icon\n$label").apply {
        setSpan(AbsoluteSizeSpan(AirUiTokens.Layout.BottomTabIconTextSp, true), 0, icon.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(AbsoluteSizeSpan(AirUiTokens.Layout.BottomTabLabelTextSp, true), icon.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
                Page.MEDIA -> tr("媒体流", "Media")
                Page.FLOATING -> tr("悬浮窗", "Floating")
                Page.SETTINGS -> tr("设置", "Settings")
            }
        }
        if (view.text.toString() != targetText.toString()) {
            view.animate().cancel()
            view.alpha = AirUiTokens.Layout.TabTextSwapAlpha
            view.scaleX = AirUiTokens.Layout.TabTextSwapScale
            view.scaleY = AirUiTokens.Layout.TabTextSwapScale
            view.text = targetText
        }
        view.textSize = AirUiTokens.TextSize.Button
        view.setLineSpacing(0f, AirUiTokens.Layout.TabTextSwapScale)
        view.setTextColor(if (selected) Color.WHITE else colorTextMuted)
        view.background = null
        val targetScale = if (quickControlSelected) AirUiTokens.Layout.TabQuickScale else if (selected) AirUiTokens.Layout.TabSelectedScale else AirUiTokens.Motion.RestScale
        val targetAlpha = if (selected) AirUiTokens.Motion.RestScale else AirUiTokens.Layout.TabUnselectedAlpha
        if (animate) {
            view.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .alpha(targetAlpha)
                .setDuration(AirUiTokens.Layout.TabAnimationMs)
                .setInterpolator(OvershootInterpolator(AirUiTokens.Layout.TabOvershoot))
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
        val horizontalPadding = if (currentPage == Page.FLOATING) dp(AirUiTokens.Layout.BottomTabFloatingPadding) else dp(AirUiTokens.Layout.BottomTabDefaultPadding)
        val targetWidth = (textWidth + horizontalPadding).coerceIn(
            dp(AirUiTokens.Layout.BottomTabMinWidth).toFloat(),
            if (currentPage == Page.FLOATING) dp(AirUiTokens.Layout.BottomTabFloatingMaxWidth).toFloat() else dp(AirUiTokens.Layout.BottomTabDefaultMaxWidth).toFloat()
        )
        val targetHeight = if (currentPage == Page.FLOATING) dp(AirUiTokens.Layout.BottomTabFloatingHeight).toFloat() else dp(AirUiTokens.Layout.BottomTabDefaultHeight).toFloat()

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
