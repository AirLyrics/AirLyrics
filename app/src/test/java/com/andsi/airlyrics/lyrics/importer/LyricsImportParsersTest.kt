package com.andsi.airlyrics.lyrics.importer

import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsImportParsersTest {
    @Test
    fun parsePlain_unknownFormatIsInvalid() {
        val result = LyricsImportParsers.parsePlain(
            format = LyricsDocumentFormat.UNKNOWN,
            text = "[00:01.00]lyrics"
        )

        assertTrue(result is LyricsTextParseResult.InvalidFormat)
        assertTrue((result as LyricsTextParseResult.InvalidFormat).invalidLineNumbers.isEmpty())
    }

    @Test
    fun parseWordByWord_unknownFormatIsInvalid() {
        val result = LyricsImportParsers.parseWordByWord(
            format = LyricsDocumentFormat.UNKNOWN,
            text = "[00:01.00]<00:01.00>lyrics"
        )

        assertTrue(result is LyricsTextParseResult.InvalidFormat)
        assertTrue((result as LyricsTextParseResult.InvalidFormat).invalidLineNumbers.isEmpty())
    }
}
