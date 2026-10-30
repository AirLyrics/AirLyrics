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
