package com.andsi.airlyrics.ui.components

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted

private const val MAX_PAGE_HEIGHT_DP = 360
private const val MIN_PAGE_HEIGHT_DP = 120
private const val PAGE_HEIGHT_RATIO = 0.42f

private enum class LyricsFormatGuidePage {
    LRC,
    TTML
}

internal fun MainUiHost.showLyricsFormatGuideDialog(
    lrcGuide: String,
    ttmlGuide: String
): Dialog {
    val pageText = TextView(this).apply {
        text = lrcGuide
        textSize = AirUiTokens.TextSize.Body
        setTextColor(colorTextMuted)
        setLineSpacing(dp(AirUiTokens.Space.Xs).toFloat(), 1f)
    }
    val maxPageHeight = minOf(
        dp(MAX_PAGE_HEIGHT_DP),
        (resources.displayMetrics.heightPixels * PAGE_HEIGHT_RATIO).toInt()
            .coerceAtLeast(dp(MIN_PAGE_HEIGHT_DP))
    )
    val pageScroll = object : ScrollView(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxPageHeight, View.MeasureSpec.AT_MOST)
            )
        }
    }.apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        setPadding(
            dp(AirUiTokens.Space.ButtonH),
            dp(AirUiTokens.Space.CardV),
            dp(AirUiTokens.Space.ButtonH),
            dp(AirUiTokens.Space.CardV)
        )
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Sm).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(AirUiTokens.Space.Xl), 0, 0)
        }
        addView(
            pageText,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    lateinit var lrcTab: TextView
    lateinit var ttmlTab: TextView
    var selectedPage = LyricsFormatGuidePage.LRC
    var transitionGeneration = 0

    fun updateTab(tab: TextView, selected: Boolean) {
        tab.isSelected = selected
        tab.setTextColor(if (selected) colorOnAccent else colorTextMuted)
        tab.background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Sm).toFloat()
            setColor(if (selected) colorAccent else Color.TRANSPARENT)
        }
    }

    fun selectPage(page: LyricsFormatGuidePage) {
        if (page == selectedPage) return

        val direction = if (page == LyricsFormatGuidePage.TTML) 1f else -1f
        val slideDistance = dp(AirUiTokens.Layout.LyricsSlideDistanceDp).toFloat()
        selectedPage = page
        transitionGeneration += 1
        val generation = transitionGeneration
        updateTab(lrcTab, page == LyricsFormatGuidePage.LRC)
        updateTab(ttmlTab, page == LyricsFormatGuidePage.TTML)

        pageText.animate().cancel()
        pageText.animate()
            .alpha(0f)
            .translationX(-direction * slideDistance)
            .setDuration(AirUiTokens.Layout.FastFadeMs)
            .withEndAction {
                if (generation != transitionGeneration) return@withEndAction
                pageText.text = if (page == LyricsFormatGuidePage.LRC) lrcGuide else ttmlGuide
                pageScroll.scrollTo(0, 0)
                pageText.translationX = direction * slideDistance
                pageText.animate()
                    .alpha(AirUiTokens.Motion.RestAlpha)
                    .translationX(0f)
                    .setDuration(AirUiTokens.Motion.LayoutChangeMs)
                    .setInterpolator(DecelerateInterpolator())
                    .withLayer()
                    .start()
            }
            .start()
    }

    fun formatTab(label: String, page: LyricsFormatGuidePage): TextView {
        return TextView(this).apply {
            text = label
            textSize = AirUiTokens.TextSize.Body
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(AirUiTokens.Layout.IconTouchSize)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
            setOnClickListener {
                selectPage(page)
                playTinyPulse(this)
            }
        }
    }

    lrcTab = formatTab(getString(R.string.ui_lyrics_format_lrc_tab), LyricsFormatGuidePage.LRC)
    ttmlTab = formatTab(getString(R.string.ui_lyrics_format_ttml_tab), LyricsFormatGuidePage.TTML)
    updateTab(lrcTab, selected = true)
    updateTab(ttmlTab, selected = false)

    val tabs = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(
            dp(AirUiTokens.Space.Xs),
            dp(AirUiTokens.Space.Xs),
            dp(AirUiTokens.Space.Xs),
            dp(AirUiTokens.Space.Xs)
        )
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(lrcTab)
        addView(ttmlTab)
    }

    return showAirDialog(
        title = null,
        positiveText = getString(R.string.ui_ok),
        useOuterScroll = false,
        body = {
            addView(tabs)
            addView(pageScroll)
        }
    )
}
