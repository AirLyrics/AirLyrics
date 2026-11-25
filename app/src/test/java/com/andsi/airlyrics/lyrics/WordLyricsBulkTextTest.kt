package com.andsi.airlyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordLyricsBulkTextTest {
    @Test fun rangeFormatPreservesGapsOverlapsBackgroundAndTranslations() {
        val foreground = WordByWordLine(
            startMs = 900,
            endMs = 4200,
            text = "Displayed line",
            segments = listOf(
                WordByWordSegment("A < B ", 1001, 2000),
                WordByWordSegment("C\\D", 1800, 2100),
                WordByWordSegment("\n後ろ", 2800, 3800),
            ),
            translatedText = "第一行\n第二行",
            translatedSegments = listOf(WordByWordSegment("第一行", 920, 2020)),
        )
        val background = WordByWordLine(
            startMs = foreground.startMs,
            endMs = 2400,
            text = "Ah",
            segments = listOf(WordByWordSegment("Ah", 950, 2100)),
            isBackground = true,
        )
        val document = WordLyricsDocument(
            listOf(foreground, background),
            listOf("[ar:Artist]", "[al:A\\B]"),
            "[00:00.900]Separate translation\r\n[00:04.200]次の行",
        )

        assertEquals(document, WordLyricsBulkText.parse(WordLyricsBulkText.format(document)).document)
    }

    @Test fun legacyLineCanBePastedAndMalformedNativeLineIsReportedAtItsSourcePosition() {
        val legacy = "[ar:Artist]\n[00:01.000]<00:01.000>Hello <00:02.000>world<00:03.000>"
        assertEquals("Hello world", WordLyricsBulkText.parse(legacy).document?.lines?.single()?.text)

        val malformed = "[ar:Artist]\n\n[00:01.000-00:03.000]<00:01.000-00:04.000>overruns"
        val (document, invalidLines) = WordLyricsBulkText.parse(malformed)
        assertNull(document)
        assertEquals(listOf(3), invalidLines)
    }
}
