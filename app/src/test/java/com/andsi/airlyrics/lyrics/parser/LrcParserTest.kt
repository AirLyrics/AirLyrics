package com.andsi.airlyrics.lyrics.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcParserTest {
    @Test
    fun parse_supportsMultipleTagsAndSortsByTime() {
        val lines = LrcParser.parse(
            """
            [00:20.00]second
            [00:10.00][00:12.50]shared
            [00:05.25]first
            """.trimIndent()
        )

        assertEquals(listOf(5_250L, 10_000L, 12_500L, 20_000L), lines.map { it.timeMs })
        assertEquals(listOf("first", "shared", "shared", "second"), lines.map { it.text })
    }

    @Test
    fun parse_supportsCompactOneLineExports() {
        val lines = LrcParser.parse("[00:01.00]hello[00:02.50]world")

        assertEquals(2, lines.size)
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals("hello", lines[0].text)
        assertEquals(2_500L, lines[1].timeMs)
        assertEquals("world", lines[1].text)
    }

    @Test
    fun parse_splitsStoredOriginalAndTranslation() {
        val lines = LrcParser.parse("[00:03.00]星が降らない街 / 星星不落的街道")

        assertEquals(1, lines.size)
        assertEquals("星が降らない街", lines[0].text)
        assertEquals("星星不落的街道", lines[0].translation)
        assertEquals("星が降らない街\n星星不落的街道", lines[0].defaultDisplayText())
    }

    @Test
    fun parseWithTranslation_matchesNearbyTranslationWithoutDuplicatingOriginal() {
        val original = """
            [00:10.00]Original A
            [00:20.00]Original B
            [00:30.00]Same line
        """.trimIndent()
        val translated = """
            [00:10.40]翻译 A
            [00:20.49]翻译 B
            [00:30.00]Same line
        """.trimIndent()

        val lines = LrcParser.parseWithTranslation(original, translated)

        assertEquals("翻译 A", lines[0].translation)
        assertEquals("翻译 B", lines[1].translation)
        assertNull(lines[2].translation)
    }

    @Test
    fun normalizeForStorage_outputsStableOneLinePerLyricFormat() {
        val normalized = LrcParser.normalizeForStorage(
            """
            [00:02:5]two point five
            [00:01.00]one / 一
            """.trimIndent()
        )

        assertEquals("[00:01.00]one / 一\n[00:02.50]two point five", normalized)
    }

    @Test
    fun findCurrentIndex_returnsLastLineAtOrBeforePosition() {
        val lines = LrcParser.parse("[00:01.00]a\n[00:02.00]b\n[00:03.00]c")

        assertNull(LrcParser.findCurrentIndex(lines, 999L))
        assertEquals(0, LrcParser.findCurrentIndex(lines, 1_000L))
        assertEquals(1, LrcParser.findCurrentIndex(lines, 2_500L))
        assertEquals(2, LrcParser.findCurrentIndex(lines, 99_000L))
    }

    @Test
    fun parse_ignoresMetadataTagsAndBlankLines() {
        val lines = LrcParser.parse(
            """
            [ar:Artist]

            [ti:Title]
            [00:01.00]hello
            """.trimIndent()
        )

        assertEquals(1, lines.size)
        assertEquals("hello", lines.single().text)
    }

    @Test
    fun parse_ignoresInvalidSecondValues() {
        val lines = LrcParser.parse(
            """
            [00:60.00]bad
            [01:00.00]good
            """.trimIndent()
        )

        assertEquals(listOf("good"), lines.map { it.text })
    }

    @Test
    fun validateForStorage_reportsOnlyNonIgnorableInvalidLines() {
        val result = LrcParser.validateForStorage(
            """
            [ar:Artist]
            not timed
            [00:01.00]valid
            [bad]also invalid
            """.trimIndent()
        )

        assertEquals(false, result.isValid)
        assertEquals(listOf(2, 4), result.invalidLineNumbers)
    }

    @Test
    fun normalizeForStorage_mergesSameTimestampLinesAsTranslation() {
        val normalized = LrcParser.normalizeForStorage(
            """
            [00:10.00]君の背中
            [00:10.00]你的背影
            [00:20.00]次の原文
            [00:20.00]下一句翻译
            """.trimIndent()
        )

        assertEquals(
            "[00:10.00]君の背中 / 你的背影\n[00:20.00]次の原文 / 下一句翻译",
            normalized
        )
    }

    @Test
    fun normalizeForStorage_mergesCompactSameTimestampSegmentsAsTranslation() {
        val normalized = LrcParser.normalizeForStorage(
            "[00:10.00]君の背中[00:10.00]你的背影"
        )

        assertEquals("[00:10.00]君の背中 / 你的背影", normalized)
    }

    @Test
    fun parse_keepsSameTimestampLinesSeparateForRuntimeCompatibility() {
        val lines = LrcParser.parse(
            """
            [00:10.00]君の背中
            [00:10.00]你的背影
            """.trimIndent()
        )

        assertEquals(2, lines.size)
        assertEquals(listOf("君の背中", "你的背影"), lines.map { it.text })
        assertNull(lines[0].translation)
        assertNull(lines[1].translation)
    }

}
