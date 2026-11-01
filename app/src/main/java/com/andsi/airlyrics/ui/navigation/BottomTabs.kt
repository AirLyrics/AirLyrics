package com.andsi.airlyrics.ui.navigation

import com.andsi.airlyrics.R

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.setAirTopIcon
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorIconOnAccent
import com.andsi.airlyrics.ui.theme.colorSurface
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.widgets.WaterTabHighlightView
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun createBottomTabs(activity: MainUiHost): View  = with(activity) createBottomTabs@ {
    val shell = object : FrameLayout(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // The MATCH_PARENT highlight is decoration, not a source of desired height.
            // Measuring it first would make a wrap-content bar fill the entire window.
            val content = getChildAt(childCount - 1)
            content.measure(
                getChildMeasureSpec(widthMeasureSpec, paddingLeft + paddingRight, LayoutParams.MATCH_PARENT),
                MeasureSpec.UNSPECIFIED
            )
            val desiredHeight = maxOf(minimumHeight, content.measuredHeight + paddingTop + paddingBottom)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(resolveSize(desiredHeight, heightMeasureSpec), MeasureSpec.EXACTLY))
        }
    }.apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        minimumHeight = dp(AirUiTokens.Layout.BottomBarHeight)
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
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
    }
    tabRow = bar

    addTab(activity, bar, Page.MEDIA, R.string.ui_media)
    addTab(activity, bar, Page.FLOATING, R.string.ui_floating)
    addTab(activity, bar, Page.SETTINGS, R.string.ui_settings)

    shell.addView(tabHighlight)
    shell.addView(bar)
    shell.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if (r - l != oldR - oldL || b - t != oldB - oldT) updateTabHighlight(activity, animate = false)
    }
    return shell
}

internal fun addTab(
    activity: MainUiHost,
    parent: LinearLayout,
    page: Page,
    @StringRes titleRes: Int
) = with(activity) addTab@ {
    val slot = FrameLayout(this).apply {
        layoutParams = if (parent.orientation == LinearLayout.VERTICAL) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        } else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, AirUiTokens.Motion.RestScale)
        minimumHeight = dp(48)
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
        setText(titleRes)
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setPadding(dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Xl), dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Xl))
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
    }

    tabViews[page] = tab
    slot.addView(tab)
    slot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            removeQuickStatusLayer(slot)
            tab.alpha = if (page == currentPage) AirUiTokens.Motion.RestScale else AirUiTokens.Layout.TabUnselectedAlpha
        }
    })
    parent.addView(slot)
}

@StringRes
private fun quickFloatingTabLabelRes(
    visible: Boolean,
    overlayPermissionGranted: Boolean
): Int {
    return when {
        !overlayPermissionGranted -> R.string.ui_permit
        visible -> R.string.ui_hide
        else -> R.string.ui_show
    }
}

private fun quickFloatingTabIconRes(visible: Boolean, overlayPermissionGranted: Boolean): Int {
    return when {
        !overlayPermissionGranted -> R.drawable.ic_air_error
        visible -> R.drawable.ic_air_visibility_off
        else -> R.drawable.ic_air_visibility
    }
}

internal fun measureTabTextWidth(tab: TextView): Float {
    val layout = tab.layout ?: return tab.paint.measureText(tab.text.toString())
    return (0 until layout.lineCount).maxOfOrNull(layout::getLineWidth) ?: 0f
}

internal fun updateTabs(
    activity: MainUiHost,
    animate: Boolean = true,
    animateQuickStatus: Boolean = false
): Unit = with(activity) updateTabs@ {
    val motion = animate && ValueAnimator.areAnimatorsEnabled()
    tabViews.forEach { (page, view) ->
        val selected = page == currentPage
        val quickControlSelected = page == Page.FLOATING && selected
        val targetTextRes = if (quickControlSelected) {
            quickFloatingTabLabelRes(quickFloatingDesiredVisible, overlayPermissionGranted)
        } else {
            when (page) {
                Page.MEDIA -> R.string.ui_media
                Page.FLOATING -> R.string.ui_floating
                Page.SETTINGS -> R.string.ui_settings
            }
        }
        val targetText = getString(targetTextRes)
        val targetIconRes = if (quickControlSelected) {
            quickFloatingTabIconRes(quickFloatingDesiredVisible, overlayPermissionGranted)
        } else {
            null
        }
        val slot = view.parent as? FrameLayout
        val activeLayer = slot?.let(::findQuickStatusLayer)
        val oldSource = activeLayer?.incoming ?: view
        val oldIconRes = activeLayer?.incomingIconRes ?: view.tag as? Int
        val textChanged = oldSource.text.toString() != targetText || oldIconRes != targetIconRes
        val quickSwap = animateQuickStatus && quickControlSelected && textChanged &&
            ValueAnimator.areAnimatorsEnabled() && view.isAttachedToWindow && view.width > 0 && slot != null
        val keepQuickLayer = activeLayer != null && quickControlSelected && !textChanged &&
            ValueAnimator.areAnimatorsEnabled()
        val outgoing = if (quickSwap) copyQuickStatusLabel(activity, oldSource, oldIconRes) else null
        if (slot != null && !keepQuickLayer) removeQuickStatusLayer(slot)
        if (view.text.toString() != targetText || view.tag != targetIconRes) {
            view.animate().cancel()
            if (motion && !quickSwap) {
                view.alpha = AirUiTokens.Layout.TabTextSwapAlpha
                view.scaleX = AirUiTokens.Layout.TabTextSwapScale
                view.scaleY = AirUiTokens.Layout.TabTextSwapScale
            }
            view.text = targetText
            view.tag = targetIconRes
        }
        view.setAirTopIcon(activity, targetIconRes, if (selected) colorIconOnAccent else colorTextMuted)
        view.textSize = if (quickControlSelected) {
            AirUiTokens.Layout.BottomTabLabelTextSp.toFloat()
        } else {
            AirUiTokens.TextSize.Button
        }
        view.setLineSpacing(0f, AirUiTokens.Layout.TabTextSwapScale)
        view.setTextColor(if (selected) colorOnAccent else colorTextMuted)
        view.background = null
        val targetScale = AirUiTokens.Motion.RestScale
        val targetAlpha = if (selected) AirUiTokens.Motion.RestScale else AirUiTokens.Layout.TabUnselectedAlpha
        if (keepQuickLayer) {
            view.animate().cancel()
            view.scaleX = targetScale
            view.scaleY = targetScale
            view.alpha = 0f
        } else if (quickSwap && outgoing != null) {
            view.animate().cancel()
            view.scaleX = targetScale
            view.scaleY = targetScale
            view.alpha = 0f
            playQuickStatusSwap(activity, slot, view, outgoing, targetIconRes, targetAlpha)
        } else if (motion) {
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

    val quickStatusStillAnimating = tabViews[Page.FLOATING]?.parent
        ?.let { it as? FrameLayout }
        ?.let(::findQuickStatusLayer) != null
    updateTabHighlight(activity, animate = motion || quickStatusStillAnimating || tabHighlight?.isAnimating == true ||
        animateQuickStatus && ValueAnimator.areAnimatorsEnabled())
}

@SuppressLint("ViewConstructor")
private class QuickStatusLayer(context: Context, val incoming: TextView, val incomingIconRes: Int?) : FrameLayout(context)

private fun findQuickStatusLayer(slot: FrameLayout): QuickStatusLayer? =
    (0 until slot.childCount).firstNotNullOfOrNull { slot.getChildAt(it) as? QuickStatusLayer }

private fun removeQuickStatusLayer(slot: FrameLayout) {
    findQuickStatusLayer(slot)?.let { layer ->
        for (index in 0 until layer.childCount) layer.getChildAt(index).animate().cancel()
        slot.removeView(layer)
        slot.contentDescription = null
    }
}

private fun copyQuickStatusLabel(activity: MainUiHost, source: TextView, iconRes: Int?): TextView =
    TextView(activity).apply {
        text = source.text
        gravity = source.gravity
        setTextSize(TypedValue.COMPLEX_UNIT_PX, source.textSize)
        typeface = source.typeface
        includeFontPadding = source.includeFontPadding
        setLineSpacing(source.lineSpacingExtra, source.lineSpacingMultiplier)
        setPadding(source.paddingLeft, source.paddingTop, source.paddingRight, source.paddingBottom)
        setTextColor(source.currentTextColor)
        setAirTopIcon(activity, iconRes, activity.colorIconOnAccent)
        alpha = source.alpha
        translationY = source.translationY
        scaleX = source.scaleX
        scaleY = source.scaleY
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
    }

private fun playQuickStatusSwap(
    activity: MainUiHost,
    slot: FrameLayout,
    tab: TextView,
    outgoing: TextView,
    incomingIconRes: Int?,
    finalAlpha: Float
) {
    // Motion Prompt #060 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#060
    // The control's label and icon hand off as one clipped state.
    val incoming = copyQuickStatusLabel(activity, tab, incomingIconRes)
    val layer = QuickStatusLayer(activity, incoming, incomingIconRes).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            tab.height,
            Gravity.CENTER
        )
        clipChildren = true
        clipToPadding = true
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(outgoing)
        addView(incoming)
    }
    slot.addView(layer)
    slot.contentDescription = tab.text
    val travel = activity.dp(7).toFloat()
    incoming.alpha = 0.35f
    incoming.translationY = travel
    incoming.scaleX = 0.97f
    incoming.scaleY = 0.97f
    outgoing.animate().alpha(0f).translationY(-travel)
        .setDuration(115L).setInterpolator(AccelerateInterpolator()).start()
    incoming.animate().alpha(finalAlpha).translationY(0f).scaleX(1f).scaleY(1f)
        .setStartDelay(20L).setDuration(160L).setInterpolator(DecelerateInterpolator())
        .withEndAction {
            if (layer.parent === slot) {
                slot.removeView(layer)
                slot.contentDescription = null
                tab.alpha = finalAlpha
            }
        }.start()
}

private fun updateTabHighlight(activity: MainUiHost, animate: Boolean = true) = with(activity) {
    val page = currentPage
    val selectedTab = tabViews[page] ?: return@with
    selectedTab.post {
        if (currentPage != page || !selectedTab.isAttachedToWindow) return@post
        val highlight = tabHighlight ?: return@post
        if (!highlight.isAttachedToWindow) return@post
        val selectedSlot = selectedTab.parent as? View ?: selectedTab
        val textWidth = measureTabTextWidth(selectedTab)
        val horizontalPadding = if (page == Page.FLOATING) dp(AirUiTokens.Layout.BottomTabFloatingPadding) else dp(AirUiTokens.Layout.BottomTabDefaultPadding)
        val targetWidth = (textWidth + horizontalPadding).coerceAtMost(selectedSlot.width.toFloat())
        val baseHeight = if (page == Page.FLOATING) dp(AirUiTokens.Layout.BottomTabFloatingHeight) else dp(AirUiTokens.Layout.BottomTabDefaultHeight)
        val targetHeight = maxOf(baseHeight, selectedTab.height).coerceAtMost(highlight.height).toFloat()

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
            animate = animate && highlight.hasPosition
        )
    }
}
