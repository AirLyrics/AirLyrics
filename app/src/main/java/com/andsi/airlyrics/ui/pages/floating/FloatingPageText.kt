package com.andsi.airlyrics.ui.pages.floating

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.TextView
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.ui.theme.colorTextStrong
import kotlin.math.roundToInt

internal data class FloatingPreviewLyricLine(
    val original: String,
    val translation: String,
    val isCurrent: Boolean
)

internal fun MainUiHost.floatingSectionTitle(title: CharSequence): TextView {
    return TextView(this).apply {
        text = title
        textSize = FloatingPageTokens.SECTION_TITLE_TEXT_SP
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorTextStrong)
        setPadding(
            0,
            dp(FloatingPageTokens.SECTION_TITLE_PADDING_TOP_DP),
            0,
            dp(FloatingPageTokens.SECTION_TITLE_PADDING_BOTTOM_DP)
        )
    }
}

internal fun previewMaxLines(mode: LyricsLineDisplayMode): Int {
    return when (mode) {
        LyricsLineDisplayMode.CURRENT_ONLY -> 2
        LyricsLineDisplayMode.PREVIOUS_AND_CURRENT,
        LyricsLineDisplayMode.CURRENT_AND_NEXT -> 4
        LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> 6
    }
}

/**
 * Maps the real floating-window size to a stable thumbnail size. More visible
 * lyric blocks use a smaller range so changing the setting never takes over the page.
 */
internal fun previewTextSizeSp(
    textSizeSp: Float,
    lineMode: LyricsLineDisplayMode
): Float {
    val progress = ((textSizeSp.coerceIn(14f, 56f) - 14f) / (56f - 14f))
    val (previewMin, previewMax) = when (lineMode) {
        LyricsLineDisplayMode.CURRENT_ONLY -> 16f to 30f
        LyricsLineDisplayMode.PREVIOUS_AND_CURRENT,
        LyricsLineDisplayMode.CURRENT_AND_NEXT -> 14f to 24f
        LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> 12f to 20f
    }
    return previewMin + (previewMax - previewMin) * progress
}

internal fun formattedPreviewLyrics(
    mode: LyricsContentDisplayMode,
    lines: List<FloatingPreviewLyricLine>,
    textColor: Int
): CharSequence {
    val result = SpannableStringBuilder()
    lines.forEachIndexed { lineIndex, line ->
        if (lineIndex > 0) result.append('\n')
        when (mode) {
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION -> {
                result.appendPreviewLine(
                    text = line.original,
                    relativeSize = if (line.isCurrent) 1f else 0.80f,
                    alphaFactor = if (line.isCurrent) 1f else 0.58f,
                    bold = line.isCurrent,
                    textColor = textColor
                )
                result.append('\n')
                result.appendPreviewLine(
                    text = line.translation,
                    relativeSize = if (line.isCurrent) 0.76f else 0.64f,
                    alphaFactor = if (line.isCurrent) 0.72f else 0.42f,
                    bold = false,
                    textColor = textColor
                )
            }

            LyricsContentDisplayMode.ORIGINAL_ONLY -> result.appendPreviewLine(
                text = line.original,
                relativeSize = if (line.isCurrent) 1f else 0.80f,
                alphaFactor = if (line.isCurrent) 1f else 0.58f,
                bold = line.isCurrent,
                textColor = textColor
            )

            LyricsContentDisplayMode.TRANSLATION_ONLY -> result.appendPreviewLine(
                text = line.translation,
                relativeSize = if (line.isCurrent) 1f else 0.80f,
                alphaFactor = if (line.isCurrent) 1f else 0.58f,
                bold = line.isCurrent,
                textColor = textColor
            )
        }
    }
    return result
}

private fun SpannableStringBuilder.appendPreviewLine(
    text: String,
    relativeSize: Float,
    alphaFactor: Float,
    bold: Boolean,
    textColor: Int
) {
    val start = length
    append(text)
    val end = length
    if (start == end) return

    if (relativeSize != 1f) {
        setSpan(RelativeSizeSpan(relativeSize), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    if (alphaFactor != 1f) {
        val alpha = (Color.alpha(textColor) * alphaFactor).roundToInt()
        setSpan(
            ForegroundColorSpan(AirColorUtils.withAlpha(textColor, alpha)),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    if (bold) {
        setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
