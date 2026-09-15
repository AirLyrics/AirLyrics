package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsStorageWordByWordImportTest : LyricsStorageTestBase() {
    @Test
    fun importWordByWordLyrics_reportsInvalidLineNumbers() {
        val result = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "word-by-word-invalid.lrc",
                text = """
                    [ar:Artist]
                    not timed
                    [00:10.00]<00:10.00>valid
                """.trimIndent()
            ),
            title = "Karaoke Invalid",
            artist = "AndSi",
            duration = 10_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.InvalidFormat)
        assertEquals(
            listOf(2),
            (result as LyricsStorage.ImportLyricsResult.InvalidFormat).invalidLineNumbers
        )
    }

    @Test
    fun importWordByWordLyrics_preservesMetadataInStoredWordByWordLrc() {
        val result = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "word-by-word-valid.lrc",
                text = """
                    [ar:Artist]
                    [00:10.00]<00:10.00>valid
                """.trimIndent()
            ),
            title = "Karaoke Valid",
            artist = "AndSi",
            duration = 10_000L
        )

        val item = LyricsStorage.listRecentLyrics(context, limit = 8)
            .single { it.title == "Karaoke Valid" }

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(
            "[ar:Artist]\n[00:10.00]<00:10.00>valid",
            LyricsStorage.readLocalLyricsItemText(
                context = context,
                item = item,
                target = LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD
            )
        )
    }

    @Test
    fun importWordByWordLyrics_convertsWordTimedTtmlAndGeneratesPlainFallback() {
        val result = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile("word-by-word.ttml", wordTimedTtml),
            title = "Word TTML",
            artist = "AndSi",
            duration = 13_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(
            listOf(
                WordByWordLine(
                    startMs = 10_000L,
                    endMs = 12_000L,
                    text = "hello world",
                    segments = listOf(
                        WordByWordSegment("he", 10_000L, 10_300L),
                        WordByWordSegment("llo ", 10_300L, 10_600L),
                        WordByWordSegment("world", 10_600L, 12_000L)
                    )
                )
            ),
            LyricsStorage.readWordByWordLyrics(context, "Word TTML", "AndSi", 13_000L)
        )
        assertEquals(
            "[00:10.00]hello world / 你好，世界",
            LyricsStorage.readPlainLyrics(context, "Word TTML", "AndSi", 13_000L)
        )

        val entry = requireNotNull(LyricsIndexStore.find(context, "Word TTML", "AndSi", 13_000L))
        assertEquals(LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK, entry.plainSource)
        assertTrue(entry.plainFile.endsWith(".lrc"))
        assertTrue(entry.wordByWordFile.endsWith(".karaoke.json"))

        val item = LyricsStorage.listRecentLyrics(context).single { it.title == "Word TTML" }
        assertEquals(
            "[00:10.00]<00:10.00>he<00:10.30>llo <00:10.60>world",
            LyricsStorage.readLocalLyricsItemText(
                context,
                item,
                LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD
            )
        )
    }

    @Test
    fun importWordByWordLyrics_rejectsLineTimedTtmlWithoutWritingLyrics() {
        val result = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile("line-only-word-import.ttml", lineTimedTtml),
            title = "Line TTML As Word",
            artist = "AndSi",
            duration = 6_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.InvalidFormat)
        assertEquals(null, LyricsIndexStore.find(context, "Line TTML As Word", "AndSi", 6_000L))
        assertFalse(LyricsStorage.hasPlainLyrics(context, "Line TTML As Word", "AndSi", 6_000L))
        assertFalse(LyricsStorage.hasWordByWordLyrics(context, "Line TTML As Word", "AndSi", 6_000L))
    }

    @Test
    fun importWordByWordLyrics_rejectsMixedTtmlWithoutDroppingLineTimedLyrics() {
        val result = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile("mixed-word-import.ttml", mixedTimingTtml),
            title = "Mixed TTML As Word",
            artist = "AndSi",
            duration = 6_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.InvalidFormat)
        assertEquals(null, LyricsIndexStore.find(context, "Mixed TTML As Word", "AndSi", 6_000L))
        assertFalse(LyricsStorage.hasPlainLyrics(context, "Mixed TTML As Word", "AndSi", 6_000L))
        assertFalse(LyricsStorage.hasWordByWordLyrics(context, "Mixed TTML As Word", "AndSi", 6_000L))
    }

    @Test
    fun importWordByWordLyrics_keepsInternalJsonCompatibilityBeforeFormatDetection() {
        val expected = listOf(
            WordByWordLine(
                startMs = 5_000L,
                endMs = 6_000L,
                text = "json",
                segments = listOf(WordByWordSegment("json", 5_000L, 6_000L))
            )
        )
        val result = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                "word-document.karaoke.json",
                WordByWordLyricsJsonCodec.wordByWordLinesToJson(expected)
            ),
            title = "Word JSON Compatibility",
            artist = "AndSi",
            duration = 6_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(
            expected,
            LyricsStorage.readWordByWordLyrics(context, "Word JSON Compatibility", "AndSi", 6_000L)
        )
        assertEquals(
            "[00:05.00]json",
            LyricsStorage.readPlainLyrics(context, "Word JSON Compatibility", "AndSi", 6_000L)
        )
    }

    @Test
    fun editWordByWordLyrics_roundTripPreservesSpacesBetweenWords() {
        val originalLyrics = "[00:10.00]<00:10.00>I <00:10.30>love <00:10.60>you"
        val result = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "word-by-word-spaces.lrc",
                text = originalLyrics
            ),
            title = "Karaoke Spaces",
            artist = "AndSi",
            duration = 11_000L
        )
        val item = LyricsStorage.listRecentLyrics(context, limit = 8)
            .single { it.title == "Karaoke Spaces" }

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        val editorText = requireNotNull(
            LyricsStorage.readLocalLyricsItemText(
                context = context,
                item = item,
                target = LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD
            )
        )
        assertEquals(originalLyrics, editorText)

        val update = LyricsStorage.updateWordByWordLyricsItemTextWithResult(
            context = context,
            item = item,
            wordByWordLrc = editorText
        )

        assertTrue(update.saved)
        assertEquals(
            originalLyrics,
            LyricsStorage.readLocalLyricsItemText(
                context = context,
                item = item,
                target = LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD
            )
        )
        assertEquals(
            "[00:10.00]I love you",
            LyricsStorage.readPlainLyrics(context, "Karaoke Spaces", "AndSi", 11_000L)
        )
    }

}
