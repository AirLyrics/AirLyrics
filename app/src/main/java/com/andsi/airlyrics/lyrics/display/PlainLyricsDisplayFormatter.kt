package com.andsi.airlyrics.lyrics.display

import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference
import com.andsi.airlyrics.core.text.LyricsDisplayPolicy
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode

/**
 * Converts parsed plain lyric lines into the exact text shown by the floating window.
 *
 * Providers decide what plain lyrics are available; this formatter decides how the
 * user wants those plain lyrics rendered.
 */
object PlainLyricsDisplayFormatter {
    private const val NO_TRANSLATION_TEXT = "No translation for this lyric"

    fun format(
        plainLines: List<LrcLine>,
        currentIndex: Int,
        contentMode: LyricsContentDisplayMode,
        scriptPreference: LyricsScriptPreference = LyricsScriptPreference.default,
        lineMode: LyricsLineDisplayMode,
        noTranslationText: String = NO_TRANSLATION_TEXT
    ): String {
        if (plainLines.isEmpty() || currentIndex !in plainLines.indices) return ""

        val indexes = lineMode.indexesAround(currentIndex)
            .filter { it in plainLines.indices }

        if (indexes.isEmpty()) return ""

        val renderedLines = indexes.mapNotNull { index ->
            renderPlainLine(plainLines[index], contentMode, scriptPreference).takeIf { it.isNotBlank() }
        }

        if (renderedLines.isNotEmpty()) {
            return renderedLines.joinToString("\n")
        }

        return if (contentMode == LyricsContentDisplayMode.TRANSLATION_ONLY) {
            noTranslationText
        } else {
            ""
        }
    }

    private fun LyricsLineDisplayMode.indexesAround(currentIndex: Int): List<Int> {
        return when (this) {
            LyricsLineDisplayMode.CURRENT_ONLY -> listOf(currentIndex)
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(currentIndex - 1, currentIndex)
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(currentIndex, currentIndex + 1)
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(currentIndex - 1, currentIndex, currentIndex + 1)
        }
    }

    private fun renderPlainLine(
        plainLine: LrcLine,
        contentMode: LyricsContentDisplayMode,
        scriptPreference: LyricsScriptPreference
    ): String {
        val original = plainLine.text.trim()
        val translation = plainLine.translation.orEmpty().trim()
        if (plainLine.isMetadata) return original

        return LyricsDisplayPolicy.join(
            LyricsDisplayPolicy.render(contentMode, original, translation, scriptPreference)
        )
    }
}
