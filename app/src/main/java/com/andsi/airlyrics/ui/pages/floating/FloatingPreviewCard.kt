package com.andsi.airlyrics.ui.pages.floating

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.softLayoutTransition
import kotlin.math.roundToInt

internal data class FloatingPreviewCardHandle(
    val cardView: View,
    val lyricTextView: TextView,
    val bodyView: View,
    val updateLineMode: (LyricsLineDisplayMode) -> Unit,
    val updateStyle: (FloatingLyricsStyle) -> Unit,
    val updateFold: (Boolean) -> Unit
)

internal fun MainUiHost.createFloatingPreviewCard(
    isExpanded: () -> Boolean,
    setExpanded: (Boolean) -> Unit,
    style: () -> FloatingLyricsStyle,
    lineDisplayMode: () -> LyricsLineDisplayMode,
    isWordByWordLyricsEnabled: () -> Boolean,
    plainPreviewText: () -> CharSequence,
    wordByWordPreviewText: () -> CharSequence
): FloatingPreviewCardHandle {
    lateinit var handle: FloatingPreviewCardHandle
    lateinit var lyricView: TextView
    lateinit var toggleView: TextView

    fun togglePreview() {
        val next = !isExpanded()
        setExpanded(next)
        playTinyPulse(toggleView)
        handle.updateFold(next)
    }

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        layoutTransition = softLayoutTransition()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dp(FloatingPageTokens.PREVIEW_CARD_MARGIN_BOTTOM_DP))
        }
        lyricView = floatingPreviewText(
            if (isWordByWordLyricsEnabled()) wordByWordPreviewText() else plainPreviewText(),
            style()
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            maxLines = previewMaxLines(lineDisplayMode())
            includeFontPadding = false
        }
        addView(lyricView)

        toggleView = TextView(this@createFloatingPreviewCard).apply {
            textSize = FloatingPageTokens.PREVIEW_TOGGLE_TEXT_SP
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            background = null
            layoutParams = LinearLayout.LayoutParams(
                dp(FloatingPageTokens.PREVIEW_TOGGLE_SIZE_DP),
                dp(FloatingPageTokens.PREVIEW_TOGGLE_SIZE_DP)
            )
            enableSoftPressFeedback(0.92f)
            setOnClickListener { togglePreview() }
        }
        addView(toggleView)
    }

    handle = FloatingPreviewCardHandle(
        cardView = card,
        lyricTextView = lyricView,
        bodyView = lyricView,
        updateLineMode = { mode ->
            lyricView.maxLines = previewMaxLines(mode)
            lyricView.requestLayout()
        },
        updateStyle = { previewStyle ->
            val toggleAlpha = (255 * FloatingPageTokens.PREVIEW_TOGGLE_ALPHA).roundToInt()
            toggleView.setTextColor(AirColorUtils.withAlpha(previewStyle.textColor, toggleAlpha))
        },
        updateFold = { expanded ->
            lyricView.visibility = if (expanded) View.VISIBLE else View.GONE
            toggleView.text = if (expanded) "⌃" else "⌄"
            toggleView.contentDescription = getString(
                if (expanded) R.string.ui_collapse_preview else R.string.ui_expand_preview
            )
            card.requestLayout()
        }
    )
    handle.updateStyle(style())
    handle.updateFold(isExpanded())
    return handle
}
