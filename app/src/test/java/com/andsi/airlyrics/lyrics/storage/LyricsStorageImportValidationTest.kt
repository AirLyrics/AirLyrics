package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.SongIdentity
import java.io.File
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsStorageImportValidationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetStorage()
    }

    @After
    fun tearDown() {
        resetStorage()
    }

    @Test
    fun importPlainLyrics_reportsReadFailedWhenUriCannotBeOpened() {
        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
            context = context,
            uri = Uri.fromFile(File(context.cacheDir, "missing-plain.lrc")),
            title = "Plain Missing",
            artist = "AndSi",
            duration = 1_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.ReadFailed)
    }

    @Test
    fun importPlainLyrics_reportsSaveFailedWhenStorageCannotWrite() {
        File(context.getExternalFilesDir(null) ?: context.filesDir, FALLBACK_LYRICS_DIR)
            .writeText("not a directory")

        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "plain-save-failed.lrc",
                text = "[00:01.00]valid"
            ),
            title = "Plain Save Failed",
            artist = "AndSi",
            duration = 1_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.SaveFailed)
    }

    @Test
    fun importPlainLyrics_reportsInvalidLineNumbers() {
        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "plain-invalid.lrc",
                text = """
                    [ar:Artist]
                    not timed
                    [00:01.00]valid
                """.trimIndent()
            ),
            title = "Plain Invalid",
            artist = "AndSi",
            duration = 1_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.InvalidFormat)
        assertEquals(
            listOf(2),
            (result as LyricsStorage.ImportLyricsResult.InvalidFormat).invalidLineNumbers
        )
    }

    @Test
    fun importPlainLyrics_preservesMetadataWhenValid() {
        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "plain-valid.lrc",
                text = """
                    [ar:Artist]
                    [ti:Title]
                    [00:01.00]valid
                """.trimIndent()
            ),
            title = "Plain Valid",
            artist = "AndSi",
            duration = 2_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(
            "[ar:Artist]\n[ti:Title]\n[00:01.00]valid",
            LyricsStorage.readPlainLyrics(context, "Plain Valid", "AndSi", 2_000L)
        )
    }

    @Test
    fun importPlainLyrics_preservesTimedEmptyLine() {
        val result = LyricsStorage.importPlainLyricsFromUriWithResult(
            context = context,
            uri = writeImportFile(
                name = "plain-timed-empty-line.lrc",
                text = "[04:45.03]final lyric\n[04:48.92] "
            ),
            title = "Plain Timed Empty Line",
            artist = "AndSi",
            duration = 300_000L
        )

        assertTrue(result is LyricsStorage.ImportLyricsResult.Saved)
        assertEquals(
            "[04:45.03]final lyric\n[04:48.92]",
            LyricsStorage.readPlainLyrics(
                context,
                "Plain Timed Empty Line",
                "AndSi",
                300_000L
            )
        )
    }

    @Test
    fun readPlainLyrics_usesLegacyIndexPathAfterStorageKeyBecomesRootStable() {
        val plainLrc = "[00:01.00]legacy locale path"
        val identity = SongIdentity(
            title = "INDIGO",
            artist = "ARTIST",
            durationMs = 180_900L
        )
        val legacyKey = "2c38d002d54afdbf7ca9281fe90d7ae261cff2be"
        val legacyFileName = "2c38d002d54afdbf.lrc"
        val legacyRelativePath = "lyrics/$legacyFileName"

        assertEquals(
            "a11e82c8fbfbe4a976ab8e497d003f8728ab3e98",
            identity.storageKey()
        )
        assertFalse(identity.storageKey() == legacyKey)
        assertTrue(LyricsFileStore.writeManagedLyrics(context, legacyFileName, plainLrc))
        assertTrue(
            LyricsIndexStore.write(
                context,
                listOf(
                    LyricsIndexEntry(
                        key = legacyKey,
                        title = identity.title,
                        artist = identity.artist,
                        album = "",
                        durationMs = identity.durationMs,
                        plainFile = legacyRelativePath,
                        plainSource = LyricsStorage.SOURCE_DOWNLOADED,
                        plainProvider = "legacy-locale-test",
                        createdAt = 1L,
                        updatedAt = 2L
                    )
                )
            )
        )

        assertEquals(
            plainLrc,
            LyricsStorage.readPlainLyrics(
                context,
                identity.title,
                identity.artist,
                identity.durationMs
            )
        )
    }

    @Test
    fun readPlainLyrics_matchesStoredIdentityCaseUnderTurkishLocale() {
        val originalLocale = Locale.getDefault()
        val plainLrc = "[00:01.00]locale-independent match"

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(
                LyricsStorage.savePlainLyrics(
                    context = context,
                    title = "INDIGO",
                    artist = "ARTIST",
                    duration = 180_000L,
                    plainLrc = plainLrc
                )
            )

            assertEquals(
                plainLrc,
                LyricsStorage.readPlainLyrics(
                    context = context,
                    title = "indigo",
                    artist = "artist",
                    duration = 180_000L
                )
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun listRecentLyrics_doesNotExposeUnindexedManagedFileAsLegacy() {
        val identity =
            SongIdentity(
                title = "Interrupted Managed Save",
                artist = "AndSi",
                durationMs = 180_000L,
            )
        val managedFileName = LyricsFileNaming.managedPlainFileName(identity)

        assertTrue(
            LyricsFileStore.writeManagedLyrics(
                context,
                managedFileName,
                "[00:01.00]unindexed",
            ),
        )
        assertTrue(
            File(LyricsStoragePaths.fallbackManagedLyricsDir(context), managedFileName).isFile,
        )

        assertFalse(
            LyricsStorage.listRecentLyrics(context).any {
                it.name == managedFileName
            },
        )
    }

    @Test
    fun listRecentLyrics_keepsUnindexedLegacyFileVisible() {
        val identity =
            SongIdentity(
                title = "Legacy Visible",
                artist = "AndSi",
                durationMs = 181_000L,
            )
        val legacyFileName =
            LyricsFileNaming.legacyPlainFileName(
                identity.title,
                identity.artist,
                identity.durationMs,
            )
        val managedFileName = LyricsFileNaming.managedPlainFileName(identity)
        File(LyricsStoragePaths.fallbackLyricsDir(context), legacyFileName)
            .writeText("[00:01.00]legacy")
        assertTrue(
            LyricsFileStore.writeManagedLyrics(
                context,
                managedFileName,
                "[00:01.00]unindexed managed orphan",
            ),
        )

        val listed = LyricsStorage.listRecentLyrics(context)

        val legacyItem = listed.single { it.name == legacyFileName }
        assertEquals(LyricsStorage.SOURCE_LEGACY, legacyItem.source)
        assertTrue(legacyItem.hasPlainLyrics)
        assertFalse(listed.any { it.name == managedFileName })
    }

    @Test
    fun localLyricsFileSize_reportsStoredPlainLyricsSize() {
        val plainLyrics = "[00:01.00]歌詞 size"
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = context,
                title = "Sized Plain Lyrics",
                artist = "AndSi",
                duration = 182_000L,
                plainLrc = plainLyrics
            )
        )

        val info = LyricsStorage.getLocalPlainLyricsInfo(
            context,
            "Sized Plain Lyrics",
            "AndSi",
            182_000L
        )

        assertEquals(
            plainLyrics.toByteArray().size.toLong(),
            LyricsStorage.localLyricsFileSize(context, info!!.plainFileName)
        )
    }

    @Test
    fun listRecentLyrics_reportsStoredSizeForWordByWordOnlyItem() {
        val wordByWordLines = listOf(
            com.andsi.airlyrics.lyrics.WordByWordLine(
                startMs = 1_000L,
                endMs = 2_000L,
                text = "lyrics",
                segments = listOf(
                    com.andsi.airlyrics.lyrics.WordByWordSegment("lyrics", 1_000L, 2_000L)
                )
            )
        )
        assertTrue(
            LyricsStorage.saveWordByWordLyrics(
                context = context,
                title = "Sized Word By Word Lyrics",
                artist = "AndSi",
                duration = 183_000L,
                wordByWordLines = wordByWordLines
            )
        )

        val item = LyricsStorage.listRecentLyrics(context)
            .single { it.title == "Sized Word By Word Lyrics" }

        assertFalse(item.hasPlainLyrics)
        assertTrue(item.hasWordByWordLyrics)
        assertTrue(item.sizeBytes > 0L)
    }

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
            com.andsi.airlyrics.lyrics.WordByWordLine(
                startMs = 10_000L,
                endMs = 11_000L,
                text = "karaoke",
                segments = listOf(
                    com.andsi.airlyrics.lyrics.WordByWordSegment("karaoke", 10_000L, 11_000L)
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

    private fun writeImportFile(name: String, text: String): Uri {
        val file = File(context.cacheDir, name)
        file.writeText(text)
        return Uri.fromFile(file)
    }

    private fun resetStorage() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
    }
}
