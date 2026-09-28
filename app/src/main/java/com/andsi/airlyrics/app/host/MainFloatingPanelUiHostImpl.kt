package com.andsi.airlyrics.app.host

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.components.GridColumnPolicy
import com.andsi.airlyrics.ui.components.SettingTileLayout
import com.andsi.airlyrics.ui.components.SettingsPanelHeader
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.spacer
import com.andsi.airlyrics.ui.model.FloatingFocusBubbleHandle
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorBubble

internal fun MainUiHost.settingGridImpl(vararg items: FloatingSettingTile): LinearLayout {
    return AdaptiveGridLayout(
        this, AirUiTokens.Layout.OptionColumns,
        dp(AirUiTokens.Layout.SettingGap), dp(AirUiTokens.Layout.SettingGap),
        columnPolicy = GridColumnPolicy.Fixed
    ).apply {
        items.forEach { addView(floatingTile(it)) }
        setPadding(0, 0, 0, dp(AirUiTokens.Layout.SettingGap))
    }
}

internal fun MainUiHost.floatingTileImpl(item: FloatingSettingTile): LinearLayout = SettingTileLayout(this, item)

internal fun MainUiHost.floatingFocusBubbleImpl(
    title: String,
    subtitle: String,
    onReset: (() -> Unit)?,
    onClose: () -> Unit,
    content: LinearLayout.() -> Unit
): FloatingFocusBubbleHandle {
    val activity = this
    lateinit var contentContainer: LinearLayout
    lateinit var header: SettingsPanelHeader
    val bubble = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.ButtonH), dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Radius.Md))
        elevation = dp(AirUiTokens.Space.Xxl).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Dialog).toFloat()
            setColor(colorBubble)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorAccentSoft)
        }
        header = SettingsPanelHeader(activity, title, subtitle, onReset, onClose)
        addView(header)
        addView(spacer(activity, 8))
        contentContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            content()
        }
        addView(contentContainer)
    }

    val scroll = ScrollView(this).apply {
        isFillViewport = false
        addView(bubble, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val shell = object : LinearLayout(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                dp(AirUiTokens.Layout.FloatingPanelMaxWidth)
            } else MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(MeasureSpec.makeMeasureSpec(available.coerceAtMost(dp(AirUiTokens.Layout.FloatingPanelMaxWidth)), MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        background = bubble.background
        bubble.background = null
        elevation = bubble.elevation
        bubble.elevation = 0f
        clipToOutline = true
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md))
        }
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    return FloatingFocusBubbleHandle(
        view = shell,
        rebuildContent = {
            contentContainer.removeAllViews()
            contentContainer.content()
        },
        updateResetAction = { enabled, isUndo -> header.updateResetAction(enabled, isUndo) }
    )
}
