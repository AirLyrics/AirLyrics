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
class LyricsStorageCrossFormatLifecycleTest : LyricsStorageTestBase() {
    @Test
    fun importWordByWordLyrics_marksGeneratedPlainFallbackSource() {
        val result = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "word-by-word-fallback-source.lrc",
                text = """
                    [00:10.00]<00:10.00>valid
                """.trimIndent()
            ),
            title = "Karaoke Fallback Source",
            artist = "AndSi",
            duration = 10_000L
        )

        val info = LyricsStorage.getLocalPlainLyricsInfo(context, "Karaoke Fallback Source", "AndSi", 10_000L)

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals("karaoke_fallback", LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK)
        assertEquals("karaoke_fallback", info?.plainSource)
    }

    @Test
    fun updateWordByWordLyrics_syncsGeneratedPlainFallbackAndPreservesTranslation() {
        LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "word-by-word-fallback-sync.lrc",
                text = """
                    [00:10.00]<00:10.00>old
                    [00:10.00]旧翻译
                """.trimIndent()
            ),
            title = "Karaoke Fallback Sync",
            artist = "AndSi",
            duration = 10_000L
        )
        val item = LyricsStorage.listRecentLyrics(context, limit = 8)
            .single { it.title == "Karaoke Fallback Sync" }

        val update = LyricsStorage.updateWordByWordLyricsItemTextWithResult(
            context = context,
            item = item,
            wordByWordLrc = "[00:10.00]<00:10.00>new"
        )

        assertTrue(update.saved)
        assertEquals(
            "[00:10.00]new / 旧翻译",
            LyricsStorage.readPlainLyrics(context, "Karaoke Fallback Sync", "AndSi", 10_000L)
        )
    }

    @Test
    fun importWordByWordLyrics_blocksWhenManualPlainLyricsExist() {
        val manualPlain = "[00:10.00]manual plain"
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = context,
                title = "Karaoke Manual Plain",
                artist = "AndSi",
                duration = 10_000L,
                plainLrc = manualPlain,
                plainSource = LyricsStorage.SOURCE_MANUAL_IMPORT
            )
        )
        val result = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "word-by-word-manual-plain.lrc",
                text = "[00:10.00]<00:10.00>karaoke"
            ),
            title = "Karaoke Manual Plain",
            artist = "AndSi",
            duration = 10_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists)
        assertFalse(LyricsStorage.hasWordByWordLyrics(context, "Karaoke Manual Plain", "AndSi", 10_000L))
        assertEquals(manualPlain, LyricsStorage.readPlainLyrics(context, "Karaoke Manual Plain", "AndSi", 10_000L))
    }

    @Test
    fun importPlainLyrics_blocksWhenWordByWordLyricsExist() {
        val wordByWordResult = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "word-by-word-before-plain.lrc",
                text = "[00:10.00]<00:10.00>karaoke"
            ),
            title = "Plain Blocked By Karaoke",
            artist = "AndSi",
            duration = 10_000L
        )
        val plainResult = LyricsStorage.importPlainLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "plain-after-word-by-word.lrc",
                text = "[00:10.00]manual plain"
            ),
            title = "Plain Blocked By Karaoke",
            artist = "AndSi",
            duration = 10_000L
        )

        assertTrue(wordByWordResult is LyricsStorage.ImportLyricsResult.Saved)
        assertTrue(plainResult is LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists)
        assertEquals(
            "[00:10.00]karaoke",
            LyricsStorage.readPlainLyrics(context, "Plain Blocked By Karaoke", "AndSi", 10_000L)
        )
    }

    @Test
    fun importWordByWordLyrics_replacesGeneratedPlainFallback() {
        val firstResult = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "word-by-word-first-fallback.lrc",
                text = "[00:10.00]<00:10.00>old"
            ),
            title = "Karaoke Replace Fallback",
            artist = "AndSi",
            duration = 10_000L
        )
        val secondResult = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "word-by-word-second-fallback.lrc",
                text = "[00:10.00]<00:10.00>new"
            ),
            title = "Karaoke Replace Fallback",
            artist = "AndSi",
            duration = 10_000L,
            overwrite = true
        )

        assertTrue(firstResult is LyricsStorage.ImportLyricsResult.Saved)
        assertTrue(secondResult is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(
            LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK,
            LyricsStorage.getLocalPlainLyricsInfo(
                context,
                "Karaoke Replace Fallback",
                "AndSi",
                10_000L
            )?.plainSource
        )
        assertEquals(
            "[00:10.00]new",
            LyricsStorage.readPlainLyrics(context, "Karaoke Replace Fallback", "AndSi", 10_000L)
        )
    }

    @Test
    fun deleteWordByWordLyrics_removesGeneratedPlainFallback() {
        val importResult = LyricsStorage.importWordByWordLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "word-by-word-delete-fallback.lrc",
                text = "[00:10.00]<00:10.00>karaoke"
            ),
            title = "Karaoke Delete Fallback",
            artist = "AndSi",
            duration = 10_000L
        )
        val deleted = LyricsStorage.deleteLocalLyrics(
            context = context,
            title = "Karaoke Delete Fallback",
            artist = "AndSi",
            duration = 10_000L,
            mode = LyricsStorage.DeleteMode.WORD_BY_WORD
        )

        assertTrue(importResult is LyricsStorage.ImportLyricsResult.Saved)
        assertTrue(deleted)
        assertFalse(LyricsStorage.hasWordByWordLyrics(context, "Karaoke Delete Fallback", "AndSi", 10_000L))
        assertFalse(LyricsStorage.hasPlainLyrics(context, "Karaoke Delete Fallback", "AndSi", 10_000L))
    }

    @Test
    fun updateWordByWordLyrics_replacesPlainLyricsWithGeneratedFallback() {
        val wordByWordLines = listOf(
            WordByWordLine(
                startMs = 10_000L,
                endMs = 11_000L,
                text = "karaoke",
                segments = listOf(
                    WordByWordSegment("karaoke", 10_000L, 11_000L)
                )
            )
        )
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = context,
                title = "Karaoke Manual Plain",
                artist = "AndSi",
                duration = 10_000L,
                plainLrc = "[00:10.00]manual plain / 手动翻译",
                plainSource = LyricsStorage.SOURCE_MANUAL_IMPORT
            )
        )
        assertTrue(
            LyricsStorage.saveWordByWordLyrics(
                context = context,
                title = "Karaoke Manual Plain",
                artist = "AndSi",
                duration = 10_000L,
                wordByWordLines = wordByWordLines
            )
        )
        val item = LyricsStorage.listRecentLyrics(context, limit = 8)
            .single { it.title == "Karaoke Manual Plain" }

        val update = LyricsStorage.updateWordByWordLyricsItemTextWithResult(
            context = context,
            item = item,
            wordByWordLrc = "[00:10.00]<00:10.00>changed"
        )

        assertTrue(update.saved)
        assertEquals(
            LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK,
            LyricsStorage.getLocalPlainLyricsInfo(
                context,
                "Karaoke Manual Plain",
                "AndSi",
                10_000L
            )?.plainSource
        )
        assertEquals(
            "[00:10.00]changed / 手动翻译",
            LyricsStorage.readPlainLyrics(context, "Karaoke Manual Plain", "AndSi", 10_000L)
        )
    }

}
