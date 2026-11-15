package com.andsi.airlyrics.app.host

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.transition.TransitionManager
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.components.FLOATING_PANEL_MOTION_ROOT
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.components.GridColumnPolicy
import com.andsi.airlyrics.ui.components.SettingTileLayout
import com.andsi.airlyrics.ui.components.SettingsPanelHeader
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.core.color.AirColorUtils
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
    val header = SettingsPanelHeader(activity, title, subtitle, onReset, onClose).apply {
        setPaddingRelative(
            dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.Xxl),
            dp(AirUiTokens.Space.Xl), dp(AirUiTokens.Space.Xl)
        )
    }
    val contentContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(AirUiTokens.Space.PageH), 0,
            dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.PageH)
        )
        content()
    }
    val scroll = ScrollView(this).apply {
        isFillViewport = false
        clipToPadding = false
        addView(contentContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val shell = object : LinearLayout(this) {
        override fun onDetachedFromWindow() {
            TransitionManager.endTransitions(this)
            super.onDetachedFromWindow()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                dp(AirUiTokens.Layout.FloatingPanelMaxWidth)
            } else MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(MeasureSpec.makeMeasureSpec(available.coerceAtMost(dp(AirUiTokens.Layout.FloatingPanelMaxWidth)), MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setTag(R.id.interaction_anchor, FLOATING_PANEL_MOTION_ROOT)
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Dialog).toFloat()
            setColor(AirColorUtils.opaqueRgb(colorBubble))
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorAccentSoft)
        }
        elevation = dp(AirUiTokens.Space.Xxl).toFloat()
        clipToOutline = true
        androidx.core.view.ViewCompat.setAccessibilityPaneTitle(this, title)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md), dp(AirUiTokens.Radius.Md))
        }
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    return FloatingFocusBubbleHandle(
        view = shell,
        rebuildContent = {
            contentContainer.removeAllViews()
            contentContainer.content()
        },
        updateResetAction = { enabled, isUndo -> header.updateResetAction(enabled, isUndo) },
        updateNavigation = { pageTitle, onBack ->
            header.updateNavigation(pageTitle, onBack)
            androidx.core.view.ViewCompat.setAccessibilityPaneTitle(shell, pageTitle ?: title)
        }
    )
}
