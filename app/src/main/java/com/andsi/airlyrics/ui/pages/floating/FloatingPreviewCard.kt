package com.andsi.airlyrics.ui.pages.floating

import com.andsi.airlyrics.R

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.settings.model.FloatingLyricsStyle
import com.andsi.airlyrics.settings.model.LyricsLineDisplayMode
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.floatingStatusPreviewCard
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.softLayoutTransition
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal data class FloatingPreviewCardHandle(
    val cardView: View,
    val summaryTextView: TextView,
    val lyricTextView: TextView,
    val bodyView: View,
    val updateFold: (Boolean) -> Unit
)

internal fun MainUiHost.createFloatingPreviewCard(
    isExpanded: () -> Boolean,
    setExpanded: (Boolean) -> Unit,
    style: () -> FloatingLyricsStyle,
    lineDisplayMode: () -> LyricsLineDisplayMode,
    isKaraokeEnabled: () -> Boolean,
    plainPreviewText: () -> String,
    karaokePreviewText: () -> CharSequence,
    summaryText: () -> String,
    onExpandedChanged: () -> Unit
): FloatingPreviewCardHandle {
    lateinit var handle: FloatingPreviewCardHandle
    lateinit var bodyView: View
    lateinit var summaryView: TextView
    lateinit var lyricView: TextView
    lateinit var toggleView: TextView

    val card = floatingStatusPreviewCard(this) {
        layoutTransition = softLayoutTransition()
        bodyView = LinearLayout(this@createFloatingPreviewCard).apply {
            orientation = LinearLayout.VERTICAL
            lyricView = floatingPreviewText(
                if (isKaraokeEnabled()) karaokePreviewText() else plainPreviewText(),
                style()
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(FloatingPageTokens.PREVIEW_HEIGHT_DP)
                ).apply {
                    setMargins(
                        dp(FloatingPageTokens.PREVIEW_MARGIN_HORIZONTAL_DP),
                        0,
                        dp(FloatingPageTokens.PREVIEW_MARGIN_HORIZONTAL_DP),
                        dp(FloatingPageTokens.PREVIEW_MARGIN_BOTTOM_DP)
                    )
                }
                maxLines = previewMaxLines(lineDisplayMode())
                includeFontPadding = false
            }
            addView(lyricView)
        }
        addView(bodyView)

        addView(LinearLayout(this@createFloatingPreviewCard).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            summaryView = normalText(this@createFloatingPreviewCard, summaryText()).apply {
                textSize = FloatingPageTokens.PREVIEW_SUMMARY_TEXT_SP
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            addView(summaryView)

            toggleView = TextView(this@createFloatingPreviewCard).apply {
                textSize = FloatingPageTokens.PREVIEW_TOGGLE_TEXT_SP
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(colorAccent)
                setPadding(
                    dp(FloatingPageTokens.PREVIEW_TOGGLE_PADDING_HORIZONTAL_DP),
                    dp(FloatingPageTokens.PREVIEW_TOGGLE_PADDING_VERTICAL_DP),
                    dp(FloatingPageTokens.PREVIEW_TOGGLE_PADDING_HORIZONTAL_DP),
                    dp(FloatingPageTokens.PREVIEW_TOGGLE_PADDING_VERTICAL_DP)
                )
                background = GradientDrawable().apply {
                    cornerRadius = dp(FloatingPageTokens.PREVIEW_TOGGLE_RADIUS_DP).toFloat()
                    setColor(colorSurfaceLight)
                }
                enableSoftPressFeedback(0.94f)
                setOnClickListener {
                    val next = !isExpanded()
                    setExpanded(next)
                    playTinyPulse(this)
                    handle.updateFold(next)
                    onExpandedChanged()
                }
            }
            addView(toggleView)
        })
    }

    handle = FloatingPreviewCardHandle(
        cardView = card,
        summaryTextView = summaryView,
        lyricTextView = lyricView,
        bodyView = bodyView,
        updateFold = { expanded ->
            bodyView.visibility = if (expanded) View.VISIBLE else View.GONE
            toggleView.text = if (expanded) getString(R.string.ui_collapse) else getString(R.string.ui_expand_preview)
            card.requestLayout()
        }
    )
    handle.updateFold(isExpanded())
    return handle
}
