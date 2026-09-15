package com.andsi.airlyrics.lyrics.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsImportParsersTest {
    @Test
    fun unknownFormatIsRejectedByEveryImportParser() {
        val results = listOf(
            "plain" to LyricsImportParsers.parsePlain(
                format = LyricsDocumentFormat.UNKNOWN,
                text = "[00:01.00]lyrics"
            ),
            "word-by-word" to LyricsImportParsers.parseWordByWord(
                format = LyricsDocumentFormat.UNKNOWN,
                text = "[00:01.00]<00:01.00>lyrics"
            )
        )

        results.forEach { (name, result) ->
            assertTrue("$name parser accepted UNKNOWN", result is LyricsTextParseResult.InvalidFormat)
            assertEquals(
                "$name parser reported content errors for an unsupported format",
                emptyList<Int>(),
                (result as LyricsTextParseResult.InvalidFormat).invalidLineNumbers
            )
        }
    }
}
