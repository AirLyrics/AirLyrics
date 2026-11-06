package com.andsi.airlyrics.lyrics.display

import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference
import com.andsi.airlyrics.core.text.LyricsDisplayPolicy
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode

/** A displayed paragraph, retaining the source identity even when consecutive lyrics repeat. */
data class PlainLyricsTextRange(
    val start: Int,
    val end: Int,
    val lineIndex: Int,
    val partIndex: Int,
    val startMs: Long,
    val endMs: Long
)

data class FormattedPlainLyrics(val text: String, val ranges: List<PlainLyricsTextRange>)

/** Shared display policy for plain text and playback-driven line animation. */
object PlainLyricsDisplayFormatter {
    private const val NO_TRANSLATION_TEXT = "No translation for this lyric"
    private const val LAST_LINE_MS = 5_000L

    fun format(
        plainLines: List<LrcLine>,
        currentIndex: Int,
        contentMode: LyricsContentDisplayMode,
        scriptPreference: LyricsScriptPreference = LyricsScriptPreference.default,
        lineMode: LyricsLineDisplayMode,
        noTranslationText: String = NO_TRANSLATION_TEXT
    ): String = formatWithTiming(
        plainLines, currentIndex, contentMode, scriptPreference, lineMode, noTranslationText
    ).text

    fun formatWithTiming(
        plainLines: List<LrcLine>,
        currentIndex: Int,
        contentMode: LyricsContentDisplayMode,
        scriptPreference: LyricsScriptPreference = LyricsScriptPreference.default,
        lineMode: LyricsLineDisplayMode,
        noTranslationText: String = NO_TRANSLATION_TEXT,
        durationMs: Long = 0L
    ): FormattedPlainLyrics {
        if (currentIndex !in plainLines.indices) return FormattedPlainLyrics("", emptyList())
        val text = StringBuilder()
        val ranges = mutableListOf<PlainLyricsTextRange>()
        for (index in lineMode.indexesAround(currentIndex).filter { it in plainLines.indices }) {
            val line = plainLines[index]
            val rendered = if (line.isMetadata) {
                LyricsDisplayPolicy.Rendered(line.text.trim())
            } else {
                LyricsDisplayPolicy.render(contentMode, line.text, line.translation, scriptPreference)
            }
            // Same-time originals/translations never shorten a line to zero duration.
            // Explicit blank timestamps still end the preceding lyric.
            val endMs = plainLines.asSequence().drop(index + 1)
                .firstOrNull { !it.isMetadata && it.timeMs > line.timeMs }?.timeMs
                ?: durationMs.takeIf { it > line.timeMs }
                ?: (line.timeMs + LAST_LINE_MS)
            listOfNotNull(rendered.primary.takeIf { it.isNotBlank() }, rendered.secondary?.takeIf { it.isNotBlank() })
                .forEachIndexed { partIndex, part ->
                    if (text.isNotEmpty()) text.append('\n')
                    val start = text.length
                    text.append(part)
                    if (!line.isMetadata) {
                        ranges += PlainLyricsTextRange(start, text.length, index, partIndex, line.timeMs, endMs)
                    }
                }
        }
        if (text.isEmpty() && contentMode == LyricsContentDisplayMode.TRANSLATION_ONLY) {
            text.append(noTranslationText)
        }
        return FormattedPlainLyrics(text.toString(), ranges)
    }

    private fun LyricsLineDisplayMode.indexesAround(currentIndex: Int): List<Int> = when (this) {
        LyricsLineDisplayMode.CURRENT_ONLY -> listOf(currentIndex)
        LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(currentIndex - 1, currentIndex)
        LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(currentIndex, currentIndex + 1)
        LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(currentIndex - 1, currentIndex, currentIndex + 1)
    }
}
