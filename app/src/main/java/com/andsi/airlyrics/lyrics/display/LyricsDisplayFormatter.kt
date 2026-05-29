package com.andsi.airlyrics.lyrics.display

import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.settings.model.LyricsContentDisplayMode
import com.andsi.airlyrics.settings.model.LyricsLineDisplayMode

/**
 * Converts parsed lyric lines into the exact text shown by the floating window.
 *
 * Providers decide what lyrics are available; this formatter decides how the
 * user wants those lyrics rendered.
 */
object LyricsDisplayFormatter {
    private const val NO_TRANSLATION_TEXT = "当前歌词没有翻译"

    fun format(
        lines: List<LrcLine>,
        currentIndex: Int,
        contentMode: LyricsContentDisplayMode,
        lineMode: LyricsLineDisplayMode
    ): String {
        if (lines.isEmpty() || currentIndex !in lines.indices) return ""

        val indexes = lineMode.indexesAround(currentIndex)
            .filter { it in lines.indices }

        if (indexes.isEmpty()) return ""

        val renderedLines = indexes.mapNotNull { index ->
            renderLine(lines[index], contentMode).takeIf { it.isNotBlank() }
        }

        if (renderedLines.isNotEmpty()) {
            return renderedLines.joinToString("\n")
        }

        return if (contentMode == LyricsContentDisplayMode.TRANSLATION_ONLY) {
            NO_TRANSLATION_TEXT
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

    private fun renderLine(line: LrcLine, contentMode: LyricsContentDisplayMode): String {
        val original = line.text.trim()
        val translation = line.translation.orEmpty().trim()

        return when (contentMode) {
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION -> {
                buildList {
                    if (original.isNotBlank()) add(original)
                    if (translation.isNotBlank()) add(translation)
                }.joinToString("\n")
            }

            LyricsContentDisplayMode.ORIGINAL_ONLY -> original

            LyricsContentDisplayMode.TRANSLATION_ONLY -> translation
        }
    }
}
