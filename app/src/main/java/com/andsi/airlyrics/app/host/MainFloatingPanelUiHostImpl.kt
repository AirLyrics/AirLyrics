package com.andsi.airlyrics.app.host

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import com.andsi.airlyrics.ui.components.beginFloatingPanelContentReplacement
import com.andsi.airlyrics.ui.components.endFloatingPanelChanges
import androidx.core.view.doOnLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.components.FLOATING_PANEL_MOTION_ROOT
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.components.GridColumnPolicy
import com.andsi.airlyrics.ui.components.SettingTileLayout
import com.andsi.airlyrics.ui.components.SettingsPanelHeader
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.setAirStartIcon
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.ui.model.FloatingFocusBubbleHandle
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorBubble
import com.andsi.airlyrics.ui.theme.colorTextMuted
import kotlin.math.roundToInt

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
    onUndo: (() -> Unit)?,
    onInteraction: (() -> Unit)?,
    content: LinearLayout.() -> Unit
): FloatingFocusBubbleHandle {
    val activity = this
    val contentInset = dp(AirUiTokens.Space.PageH)
    val header = SettingsPanelHeader(activity, title, subtitle, onUndo, onClose).apply {
        setPaddingRelative(
            dp(AirUiTokens.Space.PageH), dp(AirUiTokens.Space.Xxl),
            dp(AirUiTokens.Space.Xl), dp(AirUiTokens.Space.Xl)
        )
    }
    val contentContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(contentInset, 0, contentInset, if (onReset == null) contentInset else 0)
        content()
    }
    val reset = onReset?.let { resetDefaults ->
        AppCompatButton(this).apply {
            setText(R.string.ui_reset_to_default)
            isAllCaps = false
            textSize = AirUiTokens.TextSize.Caption
            val muted = colorTextMuted
            val ink = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(
                    AirColorUtils.multiplyAlpha(muted, (255 * AirUiTokens.Button.DisabledAlpha).roundToInt()),
                    muted
                )
            )
            setTextColor(ink)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = 0
            minHeight = 0
            minimumWidth = dp(AirUiTokens.Layout.IconTouchSize)
            minimumHeight = dp(AirUiTokens.Layout.IconTouchSize)
            setPaddingRelative(0, 0, 0, 0)
            background = RippleDrawable(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(colorTextMuted, 32)),
                null,
                GradientDrawable().apply {
                    cornerRadius = dp(AirUiTokens.Radius.Sm).toFloat()
                    setColor(android.graphics.Color.WHITE)
                }
            )
            setAirStartIcon(activity, R.drawable.ic_air_reset, muted, sizeDp = 13)
            TextViewCompat.setCompoundDrawableTintList(this, ink)
            isEnabled = false
            setTag(R.id.interaction_anchor, "floating.panel.reset")
            enableSoftPressFeedback()
            setOnClickListener { if (isEnabled) resetDefaults() }
        }
    }
    val scrollContent = if (reset == null) contentContainer else LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(contentContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
            marginStart = contentInset
            marginEnd = contentInset
            bottomMargin = dp(AirUiTokens.Space.Sm)
        })
    }
    val scroll = ScrollView(this).apply {
        isFillViewport = false
        clipToPadding = false
        addView(scrollContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val shell = object : LinearLayout(this) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onInteraction?.invoke()
            return super.dispatchTouchEvent(event)
        }

        override fun onDetachedFromWindow() {
            onInteraction?.invoke()
            endFloatingPanelChanges()
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
        if (onUndo != null) isMotionEventSplittingEnabled = false
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

    var contentRevision = 0
    return FloatingFocusBubbleHandle(
        view = shell,
        rebuildContent = {
            val revision = ++contentRevision
            val scrollY = scroll.scrollY
            contentContainer.beginFloatingPanelContentReplacement()
            contentContainer.removeAllViews()
            contentContainer.content()
            contentContainer.doOnLayout {
                if (revision == contentRevision) scroll.scrollTo(0, scrollY)
            }
        },
        updateActions = { resetEnabled, undo ->
            reset?.isEnabled = resetEnabled
            header.updateUndoAction(undo)
        },
        animateUndo = header::animateUndo,
        finishUndoAnimation = header::finishUndoAnimation,
        updateNavigation = { pageTitle, onBack ->
            header.updateNavigation(pageTitle, onBack)
            androidx.core.view.ViewCompat.setAccessibilityPaneTitle(shell, pageTitle ?: title)
        },
        updateHeaderAction = { view, hideReset ->
            header.updateHeaderAction(view)
            reset?.let {
                it.visibility = if (hideReset) View.GONE else View.VISIBLE
                contentContainer.setPaddingRelative(contentInset, 0, contentInset, if (hideReset) contentInset else 0)
            }
        }
    )
}
