package com.andsi.airlyrics.lyrics.display

import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsDisplaySegmentsTest {
    @After
    fun resetDictionaries() = ChineseText.resetForTest()

    @Test
    fun wholePhraseConversionKeepsSplitTokenTimingAndDisplayPunctuation() {
        ChineseText.installTableForTest(emptyMap(), phrases = mapOf("甲乙" to "丙丁"))
        val segments = listOf(WordByWordSegment("甲", 1_000, 1_200), WordByWordSegment("乙", 1_200, 1_500))

        assertEquals(
            listOf(WordByWordSegment("丙", 1_000, 1_200), WordByWordSegment("， 丁。", 1_200, 1_500)),
            conversionSegments("丙， 丁。", segments),
        )
        assertEquals(
            listOf(WordByWordSegment("丙", 1_000, 1_200), WordByWordSegment("丁", 1_200, 1_500)),
            mapDisplaySegments("甲乙", "丙丁", segments),
        )
    }

    @Test
    fun splitLengthChangingConversionLacksAnUnambiguousTimingBoundary() {
        ChineseText.installTableForTest(emptyMap(), phrases = mapOf("甲乙" to "丙丁戊"))
        val segments = listOf(WordByWordSegment("甲", 1_000, 1_200), WordByWordSegment("乙", 1_200, 1_500))

        assertNull(conversionSegments("丙丁戊", segments))
        assertNull(mapDisplaySegments("甲乙", "丙丁戊", segments))
    }

    @Test
    fun timingCannotBeReusedForARealTranslationOrDifferentWord() {
        ChineseText.installTableForTest(mapOf('蘇' to '苏', '說' to '说'))
        val source = listOf(WordByWordSegment("我今天真的很想你", 1_000, 1_500))
        val scriptSource = listOf(WordByWordSegment("蘇", 1_000, 1_100), WordByWordSegment("三說", 1_100, 1_500))

        assertNull(conversionSegments("苏三他这样说", scriptSource))
        assertNull(conversionSegments("我今天真的很想他", source))
        assertNull(conversionSegments("我今天真的不想你", source))
    }

    @Test
    fun echoRetainsOverlappingTimingWhenBothScriptsCarryIt() {
        ChineseText.installTableForTest(emptyMap())
        val original = listOf(
            WordByWordSegment("想妳", 1_000, 1_500),
            WordByWordSegment("(想", 1_400, 1_600),
            WordByWordSegment("妳)", 1_600, 1_800),
        )

        assertEquals(listOf(
            WordByWordSegment("想你", 1_000, 1_500),
            WordByWordSegment(" （想", 1_400, 1_600),
            WordByWordSegment("你）", 1_600, 1_800),
        ), conversionSegments("想你 （想你）", original))
        assertEquals(
            listOf(WordByWordSegment("想你", 1_000, 1_500)),
            conversionSegments("想你", original),
        )
        assertEquals(
            listOf(original[0], original[1].copy(text = " (想"), original[2]),
            mapDisplaySegments("想妳(想妳)", "想妳 (想妳)", original),
        )
    }
}
