package com.andsi.airlyrics.lyrics

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WordLyricsTextEditorTest {
    private val line = WordByWordLine(1000, 6000, "Hello world again", listOf(
        WordByWordSegment("Hello ", 1101, 2101),
        WordByWordSegment("world ", 2202, 3703),
        WordByWordSegment("again", 4004, 5789)
    ), isBackground = true, translatedText = "你好", translatedSegments = listOf(
        WordByWordSegment("你好", 1000, 6000)))

    @Test fun replacingTheMiddleKeepsUnchangedTokensAndTheirPreciseGaps() {
        val updated = WordLyricsTextEditor.replaceText(line, "Hello 新世界 again")
        assertEquals(line.segments.first(), updated.segments.first())
        assertEquals(line.segments.last(), updated.segments.last())
        assertEquals(listOf("新", "世", "界 "), updated.segments.subList(1, 4).map { it.text })
        assertEquals(2202L, updated.segments[1].startMs)
        assertEquals(3703L, updated.segments[3].endMs)
        assertTrue(updated.isBackground)
        assertEquals(line.translatedSegments, updated.translatedSegments)
        assertValid(updated)
    }

    @Test fun unchangedTextNeverRetimesExistingWords() {
        assertEquals(line, WordLyricsTextEditor.replaceText(line, line.text))
    }

    @Test fun insertingAtATouchingBoundaryBorrowsTimeWithoutZeroDurationWords() {
        val source = WordByWordLine(0, 1000, "你好", listOf(
            WordByWordSegment("你", 0, 500), WordByWordSegment("好", 500, 1000)))
        val updated = WordLyricsTextEditor.replaceText(source, "你们好")
        assertEquals("你们好", updated.segments.joinToString("") { it.text })
        assertEquals(source.segments.last(), updated.segments.last())
        assertValid(updated)
    }

    @Test fun insertingSpacesDoesNotCreateBlankWordTokensOrLoseText() {
        val source = WordByWordLine(0, 1000, "你好", listOf(
            WordByWordSegment("你", 0, 500), WordByWordSegment("好", 500, 1000)))
        val updated = WordLyricsTextEditor.replaceText(source, "你 好")
        assertEquals(listOf("你 ", "好"), updated.segments.map { it.text })
        assertEquals(source.segments.map { it.startMs to it.endMs }, updated.segments.map { it.startMs to it.endMs })
        assertValid(updated)
    }

    @Test fun deletingTextPreservesSurvivingWordTimes() {
        val updated = WordLyricsTextEditor.replaceText(line, "Hello again")
        assertEquals(listOf(line.segments.first(), line.segments.last()), updated.segments)
        assertValid(updated)
    }

    @Test fun newWordsKeepSpacesCombiningMarksAndEmojiClusters() {
        val source = line.copy(text = "", segments = emptyList())
        val updated = WordLyricsTextEditor.replaceText(source, "  Café e\u0301 👨‍👩‍👧‍👦!")
        assertEquals(listOf("  Café ", "e\u0301 ", "👨‍👩‍👧‍👦!"), updated.segments.map { it.text })
        assertValid(updated)
    }

    @Test fun veryShortLinesUseAValidCombinedToken() {
        val source = WordByWordLine(0, 1, "", emptyList())
        val updated = WordLyricsTextEditor.replaceText(source, "新歌词")
        assertEquals(listOf(WordByWordSegment("新歌词", 0, 1)), updated.segments)
        assertValid(updated)
    }

    @Test fun blankTextHasNoWordTokensAndCannotBeSaved() {
        val updated = WordLyricsTextEditor.replaceText(line, " ")
        assertTrue(updated.segments.isEmpty())
        assertFalse(WordLyricsDocument(listOf(updated)).invalidLines().isEmpty())
    }

    private fun assertValid(line: WordByWordLine) {
        assertEquals(line.text, line.segments.joinToString("") { it.text })
        assertTrue(WordLyricsDocument(listOf(line)).invalidLines().isEmpty())
        assertTrue(line.segments.all { it.startMs < it.endMs })
        assertEquals(line, WordLyricsDocument.parse(WordLyricsDocument(listOf(line)).serialize()).lines.single())
    }
}
