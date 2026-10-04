package com.andsi.airlyrics.ui.pages.floating

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.softLayoutTransition
import com.andsi.airlyrics.ui.theme.colorTextMuted

internal data class FloatingPreviewCardHandle(
    val cardView: View,
    val lyricTextView: TextView,
    val bodyView: View,
    val updateText: (CharSequence) -> Unit,
    val updateLineMode: (LyricsLineDisplayMode) -> Unit,
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
    var compactExpanded = interactions.read("floating.preview.compact")?.getBoolean("expanded") ?: false
    var fullText = if (isWordByWordLyricsEnabled()) wordByWordPreviewText() else plainPreviewText()
    var displayedCompact: Boolean? = null

    fun renderText(compact: Boolean) {
        val end = fullText.indexOf('\n').takeIf { it >= 0 } ?: fullText.length
        lyricView.text = if (compact) fullText.subSequence(0, end) else fullText
        displayedCompact = compact
    }

    fun togglePreview() {
        if (windowLayout.compact) {
            compactExpanded = !compactExpanded
            interactions.write("floating.preview.compact") { putBoolean("expanded", compactExpanded) }
            handle.cardView.requestLayout()
            return
        }
        val next = !isExpanded()
        setExpanded(next)
        playTinyPulse(toggleView)
        handle.updateFold(next)
    }

    val card = object : LinearLayout(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val compact = windowLayout.compact
            val abbreviated = compact && !compactExpanded
            if (displayedCompact != abbreviated) renderText(abbreviated)
            val lines = if (abbreviated) 1 else previewMaxLines(lineDisplayMode())
            if (lyricView.maxLines != lines) lyricView.maxLines = lines
            lyricView.visibility = if (compact || isExpanded()) VISIBLE else GONE
            val expanded = if (compact) compactExpanded else isExpanded()
            toggleView.text = if (expanded) "⌃" else "⌄"
            toggleView.contentDescription = getString(if (expanded) R.string.ui_collapse_preview else R.string.ui_expand_preview)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
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
            fullText,
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
            setTextColor(colorTextMuted)
            background = null
            layoutParams = LinearLayout.LayoutParams(
                dp(48),
                dp(48)
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
        updateText = { text ->
            fullText = text
            renderText(windowLayout.compact && !compactExpanded)
        },
        updateLineMode = { mode ->
            lyricView.maxLines = previewMaxLines(mode)
            lyricView.requestLayout()
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
    handle.updateFold(isExpanded())
    return handle
}
