package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.FloatingSettingTile
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorIconOnAccent
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke

/** All setting groups share this single-line, equal-size tile, including live subtitles. */
@SuppressLint("ViewConstructor")
internal class SettingTileLayout(private val host: MainUiHost, item: FloatingSettingTile) :
    LinearLayout(host) {
    private val titleView = InlineOverflowTextView(host).apply {
        text = item.title
        textSize = AirUiTokens.TextSize.Button + 1f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(host.colorTextStrong)
    }
    private val summaryView = InlineOverflowTextView(host).apply {
        text = item.subtitle
        textSize = AirUiTokens.TextSize.Caption
        setTextColor(host.colorTextMuted)
        setPadding(0, host.dp(AirUiTokens.Space.Sm), 0, 0)
        // Keep the view even for an initially empty value: it may be updated later.
        item.onSubtitleViewCreated?.invoke(this)
    }
    private val labels = LinearLayout(host).apply {
        orientation = VERTICAL
        addView(titleView)
        addView(summaryView)
    }
    private val icon = FrameLayout(host).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(host.colorAccent)
        }
        addView(host.airIconView(item.iconRes, host.colorIconOnAccent), FrameLayout.LayoutParams(
            host.dp(AirUiTokens.Layout.IconSize), host.dp(AirUiTokens.Layout.IconSize), Gravity.CENTER
        ))
    }
    private val textScroll = InlineTextScrollController(this, listOf(titleView, summaryView))

    init {
        item.onViewCreated?.invoke(this)
        background = GradientDrawable().apply {
            cornerRadius = host.dp(AirUiTokens.Radius.Card).toFloat()
            setColor(host.colorCard)
            setStroke(host.dp(AirUiTokens.Stroke.Hairline), host.colorStroke)
        }
        addView(icon)
        addView(labels)
        alpha = if (item.enabled) 1f else 0.48f
        if (item.enabled) {
            enableSoftPressFeedback(AirUiTokens.Motion.FloatingTilePressScale)
            setOnClickListener {
                textScroll.stop()
                item.onClick(this)
            }
        }
        // Long press reads in place without consuming the normal settings action.
        // Reduced motion and screen readers retain a static full-text fallback.
        textScroll.bind(host) {
            host.showFullText(titleView.text.toString(), summaryView.text.toString())
        }
        orientation = VERTICAL
        gravity = Gravity.TOP
        minimumHeight = host.dp(AirUiTokens.Layout.FloatingTileMinHeight)
        val inset = host.dp(AirUiTokens.Space.ButtonH)
        val gap = host.dp(AirUiTokens.Layout.SettingGap)
        setPadding(inset, gap, inset, gap)
        icon.layoutParams = LayoutParams(host.dp(AirUiTokens.Layout.FloatingTileIconSize), host.dp(AirUiTokens.Layout.FloatingTileIconSize)).apply {
            bottomMargin = gap
        }
        labels.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
}
