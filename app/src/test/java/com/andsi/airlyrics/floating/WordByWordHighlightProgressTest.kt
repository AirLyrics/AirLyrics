package com.andsi.airlyrics.floating

import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordByWordHighlightProgressTest {
    @Test
    fun singleCharacter_fadesContinuouslyInsteadOfCompletingImmediately() {
        val progress = progressAt(
            text = "你",
            positionMs = 1_100L,
            lineStartMs = 1_000L,
            lineEndMs = 1_400L,
            segments = listOf(WordByWordSegment("你", 1_000L, 1_400L))
        )

        assertEquals(0, progress.completedEnd)
        assertEquals(0, progress.activeStart)
        assertEquals(1, progress.activeEnd)
        assertEquals(0.15625f, progress.activeFraction, 0.00001f)
        assertTrue(progress.hasActiveCharacter)
    }

    @Test
    fun multiCharacterSegment_movesAtConstantCharacterSpeedWithSoftEdges() {
        val progress = progressAt(
            text = "Hello",
            positionMs = 500L,
            lineStartMs = 0L,
            lineEndMs = 1_000L,
            segments = listOf(WordByWordSegment("Hello", 0L, 1_000L))
        )

        assertEquals(2, progress.completedEnd)
        assertEquals(2, progress.activeStart)
        assertEquals(3, progress.activeEnd)
        assertEquals(0.5f, progress.activeFraction, 0.00001f)
    }

    @Test
    fun unicodeSupplementaryCharacter_isNeverSplitAcrossSpans() {
        val progress = progressAt(
            text = "A😀B",
            positionMs = 450L,
            lineStartMs = 0L,
            lineEndMs = 900L,
            segments = listOf(WordByWordSegment("A😀B", 0L, 900L))
        )

        assertEquals(1, progress.completedEnd)
        assertEquals(1, progress.activeStart)
        assertEquals(3, progress.activeEnd)
        assertEquals(0.5f, progress.activeFraction, 0.00001f)
    }

    @Test
    fun pauseBetweenSegments_keepsOnlyCompletedPrefixHighlighted() {
        val segments = listOf(
            WordByWordSegment("你", 0L, 100L),
            WordByWordSegment("好", 200L, 300L)
        )

        val betweenSegments = progressAt("你 好", 150L, 0L, 400L, segments)
        assertEquals(1, betweenSegments.completedEnd)
        assertFalse(betweenSegments.hasActiveCharacter)

        val secondSegment = progressAt("你 好", 250L, 0L, 400L, segments)
        assertEquals(2, secondSegment.completedEnd)
        assertEquals(2, secondSegment.activeStart)
        assertEquals(3, secondSegment.activeEnd)
        assertEquals(0.5f, secondSegment.activeFraction, 0.00001f)
    }

    @Test
    fun missingSegments_fallsBackToSmoothWholeLineProgress() {
        val progress = progressAt(
            text = "五個字哦啊",
            positionMs = 500L,
            lineStartMs = 0L,
            lineEndMs = 1_000L,
            segments = emptyList()
        )

        assertEquals(2, progress.completedEnd)
        assertEquals(2, progress.activeStart)
        assertEquals(3, progress.activeEnd)
        assertEquals(0.5f, progress.activeFraction, 0.00001f)
    }

    private fun progressAt(
        text: String,
        positionMs: Long,
        lineStartMs: Long,
        lineEndMs: Long,
        segments: List<WordByWordSegment>
    ): WordByWordHighlightProgress {
        return wordByWordHighlightProgress(
            line = WordByWordLine(
                startMs = lineStartMs,
                endMs = lineEndMs,
                text = text,
                segments = segments
            ),
            displayText = text,
            positionMs = positionMs
        )
    }
}
