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

    @Test fun rawLineShowsExactWordTimesAndPreservesGapsOnRoundTrip() {
        val raw = WordLyricsTextEditor.rawLine(line)
        assertEquals("<00:01.101>Hello <00:02.101><00:02.202>world <00:03.703><00:04.004>again<00:05.789>", raw)
        assertEquals(line, WordLyricsTextEditor.parseRawLine(line, raw))
    }

    @Test fun rawTextChangesKeepUserDefinedTokenBoundariesAndPreciseTimes() {
        val raw = WordLyricsTextEditor.rawLine(line).replace("world ", "新 世界 ")
        val updated = WordLyricsTextEditor.parseRawLine(line, raw)!!
        assertEquals(line.segments.map { it.startMs to it.endMs }, updated.segments.map { it.startMs to it.endMs })
        assertEquals(listOf("Hello ", "新 世界 ", "again"), updated.segments.map { it.text })
        assertEquals(line.translatedSegments, updated.translatedSegments)
        assertTrue(updated.isBackground)
        assertEquals("你好", updated.translatedText)
        assertValid(updated)
    }

    @Test fun explicitNewTimestampsSplitTokensWithoutEstimatingTimings() {
        val source = WordByWordLine(0, 1000, "你好", listOf(
            WordByWordSegment("你", 0, 500), WordByWordSegment("好", 500, 1000)))
        val raw = "<00:00.000>你<00:00.400>们<00:00.800>好<00:01.000>"
        val updated = WordLyricsTextEditor.parseRawLine(source, raw)!!
        assertEquals(listOf(WordByWordSegment("你", 0, 400), WordByWordSegment("们", 400, 800),
            WordByWordSegment("好", 800, 1000)), updated.segments)
        assertValid(updated)
    }

    @Test fun editingOneTimestampOnlyUpdatesThatWordBoundary() {
        val updated = WordLyricsTextEditor.parseRawLine(line,
            WordLyricsTextEditor.rawLine(line).replace("<00:02.202>", "<00:02.500>"))!!
        assertEquals(line.segments.first(), updated.segments.first())
        assertEquals(line.segments.last(), updated.segments.last())
        assertEquals(line.segments[1].copy(startMs = 2500), updated.segments[1])
        assertValid(updated)
    }

    @Test fun movingExplicitTimesPreservesPaddingAndTranslationWithoutScaling() {
        val shifted = line.segments.map { it.copy(startMs = it.startMs + 4000, endMs = it.endMs + 4000) }
        val raw = WordLyricsTextEditor.rawLine(line.copy(segments = shifted))
        val updated = WordLyricsTextEditor.parseRawLine(line, raw)!!
        assertEquals(shifted, updated.segments)
        assertEquals(5000L, updated.startMs)
        assertEquals(10000L, updated.endMs)
        assertEquals(listOf(WordByWordSegment("你好", 5000, 10000)), updated.translatedSegments)
        assertValid(updated)
    }

    @Test fun rawRoundTripsPreserveWhitespaceUnicodeAndOverlappingWordEnds() {
        val segments = listOf(WordByWordSegment("  e\u0301 ", 0, 700),
            WordByWordSegment("👨‍👩‍👧‍👦! ", 500, 1000))
        val source = WordByWordLine(0, 1000, segments.joinToString("") { it.text }, segments)
        assertEquals(source, WordLyricsTextEditor.parseRawLine(source, WordLyricsTextEditor.rawLine(source)))
    }

    @Test fun pastedEnhancedLrcMayKeepAnExplicitLineStartTag() {
        assertEquals(line, WordLyricsTextEditor.parseRawLine(line,
            "[00:01.000]" + WordLyricsTextEditor.rawLine(line)))
    }

    @Test fun malformedOrIncompleteRawInputCannotSilentlyDropTextOrInventTimes() {
        for (raw in listOf(
            "Hello world", "<00:01.000>Hello", "<00:60.000>Hello<00:61.000>",
            "<bad>Hello<00:02.000>", "<00:02.000>A<00:03.000><00:01.000>B<00:02.000>",
            "<00:01.000>Hello<00:00.500>", "<00:01.000> <00:02.000>",
            "<00:01.000>Hello<oops><00:02.000>", "[00:09.000]<00:01.000>Hello<00:02.000>",
            "<00:01.000>Hello<00:02.000>lost text", "<00:01.000>A<00:02.000>\n<00:02.000>B<00:03.000>",
            "<00:05.000><00:01.000>Hello<00:02.000>", "<00:01.000>Hello<00:02.000><00:03.000>",
            "<00:01.000>A<00:02.000><00:02.100><00:02.200>B<00:03.000>"
        )) assertNull(raw, WordLyricsTextEditor.parseRawLine(line, raw))
    }

    private fun assertValid(line: WordByWordLine) {
        assertEquals(line.text, line.segments.joinToString("") { it.text })
        assertTrue(WordLyricsDocument(listOf(line)).invalidLines().isEmpty())
        assertEquals(line, WordLyricsDocument.parse(WordLyricsDocument(listOf(line)).serialize()).lines.single())
    }
}
