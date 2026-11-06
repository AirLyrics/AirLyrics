package com.andsi.airlyrics.app.interaction

import com.andsi.airlyrics.lyrics.parser.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditableLrcDocumentTest {
    @Test
    fun untouchedRowsKeepMetadataTranslationsPrecisionAndOriginalLineBreaks() {
        val original = "[ar:Artist]\r\n[00:05.678]原文 / 译文\r\n[00:10.00][00:11.00]shared\r\n"
        val document = EditableLrcDocument.from(original)

        assertEquals(original, document.serialize())
        assertEquals("[00:05.678]", document.rows[1].timestamp)
        assertEquals("原文 / 译文", document.rows[1].lyric)
        assertFalse(document.rows[2].isTimed)
    }

    @Test
    fun editingOneRowLeavesEveryOtherPhysicalRowUntouched() {
        val document = EditableLrcDocument.from("[ar:Artist]\n[00:01.00]first / 第一行\n[00:02.000]second")

        document.update(1, "[00:01.25]", "changed / 已修改")

        assertEquals("[ar:Artist]\n[00:01.25]changed / 已修改\n[00:02.000]second", document.serialize())
    }

    @Test
    fun insertedRowKeepsOrderAndWorksWithExistingParserAndValidation() {
        val document = EditableLrcDocument.from("[00:01.00]first\r\n[00:03.000]third")

        document.insertAfter(0, "[00:02.345]", "second / 第二行")

        assertEquals("[00:01.00]first\r\n[00:02.345]second / 第二行\r\n[00:03.000]third", document.serialize())
        assertTrue(LrcParser.validateForStorage(document.serialize()).isValid)
        assertEquals(listOf(1000L, 2345L, 3000L), LrcParser.parse(document.serialize()).map { it.timeMs })
    }

    @Test
    fun firstRowCanBeAddedToAnEmptyDocument() {
        val document = EditableLrcDocument.from("")

        val added = document.append("[00:00.00]", "first")

        assertEquals(listOf(added), document.rows)
        assertEquals("[00:00.00]first", document.serialize())
        assertTrue(LrcParser.validateForStorage(document.serialize()).isValid)
    }

    @Test
    fun firstRowPreservesExistingBlankLinesAndLineBreakStyle() {
        val document = EditableLrcDocument.from("\r\n \r\n")

        assertFalse(document.rows.any { it.isVisible })
        document.append("[00:00.00]", "")

        assertEquals("\r\n \r\n[00:00.00]\r\n", document.serialize())
        assertTrue(document.rows.last().isTimed)
        assertTrue(document.rows.last().isVisible)
        document.insertAfter(document.rows.lastIndex, "[00:01.00]", "next")
        assertEquals("\r\n \r\n[00:00.00]\r\n[00:01.00]next\r\n", document.serialize())
    }

    @Test
    fun deletingLastRowAllowsAddingANewFirstRow() {
        val document = EditableLrcDocument.from("[00:01.00]last")
        document.removeAt(0)

        document.append("[00:02.00]", "replacement")

        assertEquals("[00:02.00]replacement", document.serialize())
        assertEquals(1, document.rows.count { it.isVisible })
    }

    @Test
    fun deletingOneTimedRowKeepsAdjacentTextAndTimestamps() {
        val document = EditableLrcDocument.from("[ar:Artist]\r\n[00:01.00]a\r\n[00:02.500]\r\n[00:03.00]♪ music ♪")

        document.removeAt(2)

        assertEquals("[ar:Artist]\r\n[00:01.00]a\r\n[00:03.00]♪ music ♪", document.serialize())
        assertEquals(listOf(1000L, 3000L), LrcParser.parse(document.serialize()).filterNot { it.isMetadata }.map { it.timeMs })
        assertTrue(LrcParser.validateForStorage(document.serialize()).isValid)
    }

    @Test
    fun deletingFirstOrFinalRowPreservesTheOriginalTrailingBreakPolicy() {
        val noTrailingBreak = EditableLrcDocument.from("[00:01.00]first\n[00:02.00]last")
        noTrailingBreak.removeAt(1)
        assertEquals("[00:01.00]first", noTrailingBreak.serialize())

        val trailingBreak = EditableLrcDocument.from("[00:01.00]first\r\n[00:02.00]last\r\n")
        trailingBreak.removeAt(1)
        assertEquals("[00:01.00]first\r\n", trailingBreak.serialize())

        val first = EditableLrcDocument.from("[00:01.00]first\r\n[00:02.00]last")
        first.removeAt(0)
        assertEquals("[00:02.00]last", first.serialize())
    }

    @Test
    fun timestampInputRejectsOutOfRangeSecondsAndKeepsPrecision() {
        assertEquals("[00:05.678]", EditableLrcDocument.normalizedTimestamp("00:05.678"))
        assertNull(EditableLrcDocument.normalizedTimestamp("[00:60.00]"))
        assertNull(EditableLrcDocument.normalizedTimestamp("00:05.1234"))
        assertEquals(
            LrcParser.TimeTagValidation.SECONDS_OUT_OF_RANGE,
            LrcParser.validateTimeTag(EditableLrcDocument.timeTagInput("00:72.10"))
        )
        assertEquals(
            LrcParser.TimeTagValidation.INVALID_FORMAT,
            LrcParser.validateTimeTag(EditableLrcDocument.timeTagInput("00:05.1234"))
        )
        assertEquals(
            LrcParser.TimeTagValidation.VALID,
            LrcParser.validateTimeTag(EditableLrcDocument.timeTagInput("00:42.10"))
        )
    }

    @Test
    fun addingBlankLineAtSameTimestampFollowsExistingStorageRules() {
        val document = EditableLrcDocument.from("[00:42.10]first")

        assertTrue(EditableLrcDocument.canStoreAsOneRow("[00:42.10]", ""))
        assertFalse(EditableLrcDocument.canStoreAsOneRow("[00:42.10]", "first[00:43.10]second"))
        document.insertAfter(0, "[00:42.10]", "")

        assertEquals("[00:42.10]first\n[00:42.10]", document.serialize())
        assertTrue(LrcParser.validateForStorage(document.serialize()).isValid)
        assertEquals(listOf(42100L, 42100L), LrcParser.parse(document.serialize()).map { it.timeMs })
    }

    @Test
    fun multilineInputBecomesExistingInlineTranslationForm() {
        val content = EditableLrcDocument.normalizedLyric("Original\n翻译")

        assertEquals("Original / 翻译", content)
        assertEquals("翻译", LrcParser.parse("[00:05.68]$content").single().translation)
        assertEquals(" Original / 翻译 ", EditableLrcDocument.normalizedLyric(" Original / 翻译 "))
    }
}
