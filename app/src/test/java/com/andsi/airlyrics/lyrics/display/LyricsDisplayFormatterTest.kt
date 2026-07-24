package com.andsi.airlyrics.lyrics.display

import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsDisplayFormatterTest {
    private val lines = listOf(
        LrcLine(timeMs = 1_000L, text = "first", translation = "第一句"),
        LrcLine(timeMs = 2_000L, text = "second", translation = "第二句"),
        LrcLine(timeMs = 3_000L, text = "third", translation = null)
    )

    @Test
    fun format_returnsEmptyTextForInvalidIndexes() {
        assertEquals(
            "",
            LyricsDisplayFormatter.format(
                lines = lines,
                currentIndex = -1,
                contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
                lineMode = LyricsLineDisplayMode.CURRENT_ONLY
            )
        )
        assertEquals(
            "",
            LyricsDisplayFormatter.format(
                lines = lines,
                currentIndex = lines.size,
                contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
                lineMode = LyricsLineDisplayMode.CURRENT_ONLY
            )
        )
    }

    @Test
    fun format_originalWithTranslationRendersCurrentLineInDisplayOrder() {
        val rendered = LyricsDisplayFormatter.format(
            lines = lines,
            currentIndex = 1,
            contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            lineMode = LyricsLineDisplayMode.CURRENT_ONLY
        )

        assertEquals("second\n第二句", rendered)
    }

    @Test
    fun format_translationOnlyUsesFallbackWhenSelectedLinesHaveNoTranslation() {
        val rendered = LyricsDisplayFormatter.format(
            lines = lines,
            currentIndex = 2,
            contentMode = LyricsContentDisplayMode.TRANSLATION_ONLY,
            lineMode = LyricsLineDisplayMode.CURRENT_ONLY,
            noTranslationText = "暂无翻译"
        )

        assertEquals("暂无翻译", rendered)
    }

    @Test
    fun format_metadataLinesRenderRegardlessOfContentMode() {
        val rendered = LyricsDisplayFormatter.format(
            lines = listOf(
                LrcLine(
                    timeMs = 0L,
                    text = "[ar:Artist]\n[ti:Title]",
                    isMetadata = true
                )
            ),
            currentIndex = 0,
            contentMode = LyricsContentDisplayMode.TRANSLATION_ONLY,
            lineMode = LyricsLineDisplayMode.CURRENT_ONLY,
            noTranslationText = "暂无翻译"
        )

        assertEquals("[ar:Artist]\n[ti:Title]", rendered)
    }

    @Test
    fun format_previousCurrentNextPreservesOrderAndSkipsOutOfBoundsIndexes() {
        val middleRendered = LyricsDisplayFormatter.format(
            lines = lines,
            currentIndex = 1,
            contentMode = LyricsContentDisplayMode.ORIGINAL_ONLY,
            lineMode = LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT
        )
        val firstRendered = LyricsDisplayFormatter.format(
            lines = lines,
            currentIndex = 0,
            contentMode = LyricsContentDisplayMode.ORIGINAL_ONLY,
            lineMode = LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT
        )
        val lastRendered = LyricsDisplayFormatter.format(
            lines = lines,
            currentIndex = 2,
            contentMode = LyricsContentDisplayMode.ORIGINAL_ONLY,
            lineMode = LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT
        )

        assertEquals("first\nsecond\nthird", middleRendered)
        assertEquals("first\nsecond", firstRendered)
        assertEquals("second\nthird", lastRendered)
    }

    @Test
    fun format_previousAndCurrentPreservesNeighborOrder() {
        val rendered = LyricsDisplayFormatter.format(
            lines = lines,
            currentIndex = 1,
            contentMode = LyricsContentDisplayMode.ORIGINAL_ONLY,
            lineMode = LyricsLineDisplayMode.PREVIOUS_AND_CURRENT
        )

        assertEquals("first\nsecond", rendered)
    }
}
