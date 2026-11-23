package com.andsi.airlyrics.ui.components

import com.andsi.airlyrics.ui.state.bindInteractionScroll
import android.animation.ValueAnimator
import android.app.Dialog
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
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted

private const val MAX_PAGE_HEIGHT_DP = 360

private enum class LyricsFormatGuidePage {
    LRC,
    TTML
}

internal fun MainUiHost.showLyricsFormatGuideDialog(
    lrcGuide: String,
    ttmlGuide: String
): Dialog {
    val restoredPage = interactions.read("formatGuide")?.getString("page")
    if (interactions.read("formatGuide") == null) interactions.write("formatGuide") { putString("page", "LRC") }
    val pageText = TextView(this).apply {
        text = if (restoredPage == "TTML") ttmlGuide else lrcGuide
        textSize = AirUiTokens.TextSize.Body
        setTextColor(colorTextMuted)
        setLineSpacing(dp(AirUiTokens.Space.Xs).toFloat(), 1f)
    }
    val pageScroll = object : ScrollView(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(
                    if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) dp(MAX_PAGE_HEIGHT_DP)
                    else MeasureSpec.getSize(heightMeasureSpec).coerceAtMost(dp(MAX_PAGE_HEIGHT_DP)),
                    MeasureSpec.AT_MOST
                )
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
            dp(MAX_PAGE_HEIGHT_DP), 1f
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

    lateinit var lrcSelection: TonalSelectionMotion
    lateinit var ttmlSelection: TonalSelectionMotion
    var selectedPage = if (restoredPage == "TTML") LyricsFormatGuidePage.TTML else LyricsFormatGuidePage.LRC
    bindInteractionScroll(pageScroll, "formatGuide.scroll")
    var transitionGeneration = 0

    fun selectPage(page: LyricsFormatGuidePage, animate: Boolean) {
        if (page == selectedPage) return

        val direction = if (page == LyricsFormatGuidePage.TTML) 1f else -1f
        val slideDistance = dp(AirUiTokens.Layout.LyricsSlideDistanceDp).toFloat()
        selectedPage = page
        interactions.write("formatGuide") { putString("page", page.name) }
        transitionGeneration += 1
        val generation = transitionGeneration
        lrcSelection.select(page == LyricsFormatGuidePage.LRC, animate)
        ttmlSelection.select(page == LyricsFormatGuidePage.TTML, animate)

        val targetText = if (page == LyricsFormatGuidePage.LRC) lrcGuide else ttmlGuide
        pageText.animate().cancel()
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || !pageText.isAttachedToWindow) {
            pageText.text = targetText
            pageScroll.scrollTo(0, 0)
            pageText.alpha = 1f
            pageText.translationX = 0f
            return
        }
        if (pageText.text.toString() == targetText) {
            pageText.animate().alpha(1f).translationX(0f)
                .setDuration(AirUiTokens.Motion.LayoutChangeMs)
                .setInterpolator(floatingPanelEaseOut)
                .start()
            return
        }
        pageText.animate()
            .alpha(0f)
            .translationX(-direction * slideDistance)
            .setDuration(AirUiTokens.Layout.FastFadeMs)
            .withEndAction {
                if (generation != transitionGeneration) return@withEndAction
                pageText.text = targetText
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
            setOnClickListener { selectPage(page, animate = canAnimateFloatingControl()) }
        }
    }

    val lrcTab = formatTab(getString(R.string.ui_lyrics_format_lrc_tab), LyricsFormatGuidePage.LRC)
    val ttmlTab = formatTab(getString(R.string.ui_lyrics_format_ttml_tab), LyricsFormatGuidePage.TTML)
    lrcSelection = TonalSelectionMotion(this, lrcTab, selectedPage == LyricsFormatGuidePage.LRC, AirUiTokens.Radius.Sm)
    ttmlSelection = TonalSelectionMotion(this, ttmlTab, selectedPage == LyricsFormatGuidePage.TTML, AirUiTokens.Radius.Sm)

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
        onUserDismiss = { interactions.removePrefix("formatGuide") },
        body = {
            addView(tabs)
            addView(pageScroll)
        }
    )
}
