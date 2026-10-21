package com.andsi.airlyrics.lyrics.parser

import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordByWordLrcParserTest {
    @Test
    fun parseWordByWordLrc_buildsSegmentTimingLines() {
        val wordByWordLines = WordByWordLrcParser.parse(
            """
            [00:10.00]<00:10.00>he<00:10.30>llo <00:10.60>world
            [00:12.00]<00:12.00>next
            """.trimIndent()
        )

        assertEquals(2, wordByWordLines.size)
        assertEquals(10_000L, wordByWordLines[0].startMs)
        assertEquals(12_000L, wordByWordLines[0].endMs)
        assertEquals("hello world", wordByWordLines[0].text)
        assertEquals(listOf("he", "llo ", "world"), wordByWordLines[0].segments.map { it.text })
        assertEquals(listOf(10_000L, 10_300L, 10_600L), wordByWordLines[0].segments.map { it.startMs })
        assertEquals(12_000L, wordByWordLines[0].segments.last().endMs)
    }

    @Test
    fun parseWordByWordLrc_ignoresInvalidOrUntimedLines() {
        val wordByWordLines = WordByWordLrcParser.parse(
            """
            plain text
            [00:01.00]no word tags
            [00:02.00]<00:01.50>starts before line
            [00:03.00]<00:03.00>valid
            """.trimIndent()
        )

        assertEquals(1, wordByWordLines.size)
        assertEquals("valid", wordByWordLines.single().text)
    }

    @Test
    fun parseWordByWordImport_buildsPlainLrcWithSameTimestampTranslation() {
        val plainLrc = WordByWordLrcParser.parseImport(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:10.00]你的背影
            [00:12.00]<00:12.00>next
            [00:12.00]下一句
            """.trimIndent()
        ).plainLrc

        assertEquals("[00:10.00]君の背中 / 你的背影\n[00:12.00]next / 下一句", plainLrc)
    }

    @Test
    fun parseWordByWordImport_ignoresUnmatchedTranslationLine() {
        val plainLrc = WordByWordLrcParser.parseImport(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:11.00]不会被误合并的翻译
            """.trimIndent()
        ).plainLrc

        assertEquals("[00:10.00]君の背中", plainLrc)
    }

    @Test
    fun parseWordByWordImport_reportsTranslationPresence() {
        assertTrue(
            WordByWordLrcParser.parseImport(
                """
                [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
                [00:10.00]你的背影
                """.trimIndent()
            ).hasTranslation
        )
    }

    @Test
    fun parseWordByWordImport_preservesMetadataInPlainLrc() {
        val wordByWordLrc = """
            [ar:Artist]
            [ti:Title]
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:10.00]你的背影
        """.trimIndent()

        val parsedWordByWordLyrics = WordByWordLrcParser.parseImport(wordByWordLrc)

        assertEquals(listOf("[ar:Artist]", "[ti:Title]"), parsedWordByWordLyrics.metadataLines)
        assertEquals(
            "[ar:Artist]\n[ti:Title]\n[00:10.00]君の背中 / 你的背影",
            parsedWordByWordLyrics.plainLrc
        )
    }

    @Test
    fun wordByWordLinesToPlainLrc_preservesLineTextAndTiming() {
        val plainLrc = WordByWordLrcParser.wordByWordLinesToPlainLrc(
            listOf(
                WordByWordLine(
                    startMs = 1_230L,
                    endMs = 2_000L,
                    text = "hello",
                    segments = listOf(WordByWordSegment("hello", 1_230L, 2_000L))
                ),
                WordByWordLine(
                    startMs = 62_345L,
                    endMs = 63_000L,
                    text = "world",
                    segments = listOf(WordByWordSegment("world", 62_345L, 63_000L))
                )
            )
        )

        assertEquals("[00:01.23]hello\n[01:02.345]world", plainLrc)
    }

    @Test
    fun validateWordByWordLrcForStorage_allowsSameTimestampTranslationLines() {
        val result = WordByWordLrcParser.validateForStorage(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:10.00]你的背影
            """.trimIndent()
        )

        assertTrue(result.isValid)
        assertEquals(emptyList<Int>(), result.invalidLineNumbers)
    }

    @Test
    fun validateWordByWordLrcForStorage_ignoresMetadataAndReportsInvalidLines() {
        val result = WordByWordLrcParser.validateForStorage(
            """
            [ar:Artist]
            not timed
            [00:10.00]<00:10.00>valid
            """.trimIndent()
        )

        assertEquals(false, result.isValid)
        assertEquals(listOf(2), result.invalidLineNumbers)
    }

    @Test
    fun validateWordByWordLrcForStorage_rejectsInvalidSecondValues() {
        val result = WordByWordLrcParser.validateForStorage(
            """
            [00:60.00]<00:60.00>bad
            [01:00.00]<01:00.00>good
            """.trimIndent()
        )

        assertEquals(false, result.isValid)
        assertEquals(listOf(1), result.invalidLineNumbers)
    }

    @Test
    fun wordByWordLinesToWordByWordLrc_preservesMetadata() {
        val wordByWordLines = listOf(
            WordByWordLine(
                startMs = 10_000L,
                endMs = 10_400L,
                text = "hi",
                segments = listOf(WordByWordSegment("hi", 10_000L, 10_400L))
            )
        )

        val wordByWordLrc = WordByWordLrcParser.wordByWordLinesToWordByWordLrc(
            wordByWordLines = wordByWordLines,
            metadataLines = listOf("[ar:Artist]", "[ti:Title]")
        )

        assertEquals("[ar:Artist]\n[ti:Title]\n[00:10.00]<00:10.00>hi<00:10.40>", wordByWordLrc)
    }

    @Test fun lddcFormatsPreserveWordEndsAndPauses() {
        for (text in listOf(
            "[00:01.000]你[00:01.301][00:01.500]好[00:02.000]",
            "[00:01.000]<00:01.000>你<00:01.301><00:01.500>好<00:02.000>"
        )) {
            assertTrue(WordByWordLrcParser.hasWordTiming(text))
            assertTrue(WordByWordLrcParser.validateForStorage(text).isValid)
            val line = WordByWordLrcParser.parse(text).single()
            assertEquals("你好", line.text)
            assertEquals(listOf(1301L, 2000L), line.segments.map { it.endMs })
            assertEquals(listOf(1000L, 1500L), line.segments.map { it.startMs })
            assertEquals(2000L, line.endMs)
            val exported = WordByWordLrcParser.wordByWordLinesToWordByWordLrc(listOf(line))
            assertEquals(listOf(line), WordByWordLrcParser.parse(exported))
        }
    }

    @Test fun lddcMultilingualLinesKeepOriginalAndTranslations() {
        for (text in listOf(
            "[00:01.00]你[00:01.50]好[00:02.00]\n[00:01.00]hello[00:02.00]\n[00:01.00]ni hao[00:02.00]",
            "[00:01.00]<00:01.00>你<00:01.50>好<00:02.00>\n[00:01.00]hello<00:02.00>\n[00:01.00]ni hao<00:02.00>"
        )) {
            assertTrue(WordByWordLrcParser.validateForStorage(text).isValid)
            val parsed = WordByWordLrcParser.parseImport(text)
            assertEquals(1, parsed.wordByWordLines.size)
            assertEquals("[00:01.00]你好 / hello / ni hao", parsed.plainLrc)
        }
    }

    @Test fun ordinaryRepeatedAndCompactLinesAreNotWordTiming() {
        for (text in listOf("[00:01.00][00:03.00]chorus", "[00:01.00]first[00:03.00]second")) {
            assertEquals(false, WordByWordLrcParser.hasWordTiming(text))
            assertEquals(false, WordByWordLrcParser.validateForStorage(text).isValid)
        }
    }

    @Test fun invalidWordIntervalsAreRejected() {
        for (text in listOf(
            "[00:01.00]你[00:00.50]好[00:02.00]",
            "[00:01.00]<00:01.00>你<00:00.50>好<00:02.00>",
            "[00:01.00]<00:01.00>你<00:bad>好"
        )) assertEquals(false, WordByWordLrcParser.validateForStorage(text).isValid)
    }

}
