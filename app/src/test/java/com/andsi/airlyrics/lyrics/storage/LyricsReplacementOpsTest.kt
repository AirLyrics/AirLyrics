package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.lyrics.importer.BatchLyricsDocument
import com.andsi.airlyrics.lyrics.importer.BatchLyricsDocumentReader
import com.andsi.airlyrics.lyrics.importer.LyricsDocumentFormat
import com.andsi.airlyrics.lyrics.providers.spicyFixture
import com.andsi.airlyrics.testutil.resetLyricsStorage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsReplacementOpsTest {
    private lateinit var context: Context
    private val plain = BatchLyricsDocument("new.lrc", "[00:02.00]replacement")
    private val words get() = BatchLyricsDocumentReader.parse("new.lrc", "[00:01.00]<00:01.00>word <00:02.00>lyrics", LyricsDocumentFormat.LRC).document!!

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        resetLyricsStorage(context)
        SpicyLyricsCache.forContext(context).clear()
    }
    @After fun cleanup() { resetLyricsStorage(context); SpicyLyricsCache.forContext(context).clear() }

    @Test fun onlineCacheTargetBecomesDurableLocalLyrics() {
        val cache = SpicyLyricsCache.forContext(context)
        val key = SpicyLyricsCache.key("Song", "Artist", 180_000)
        assertNotNull(cache.save(key, "Song", "Artist", "Album", 180_000, spicyFixture()))
        val item = LyricsStorage.listAllLyrics(context).single()
        assertEquals(LyricsReplacementOps.Result.SAVED, LyricsStorage.replaceSavedLyrics(context, item, plain))
        assertNull(cache.read(key))
        val saved = LyricsStorage.listAllLyrics(context).single()
        assertEquals(LyricsStorage.SOURCE_MANUAL_IMPORT, saved.source)
        assertEquals("Album", saved.album)
        assertEquals(plain.plainLrc, LyricsStorage.readPlainLyrics(context, "Song", "Artist", 180_000))
        assertFalse(LyricsStorage.hasWordByWordLyrics(context, "Song", "Artist", 180_000))
    }

    @Test fun replacesOrdinaryWithWordsAndThenWordsWithOrdinary() {
        val item = seed()
        assertEquals(LyricsReplacementOps.Result.SAVED, LyricsStorage.replaceSavedLyrics(context, item, words))
        assertTrue(LyricsStorage.hasWordByWordLyrics(context, "Song", "Artist", 180_000))
        assertEquals(words.plainLrc, LyricsStorage.readPlainLyrics(context, "Song", "Artist", 180_000))
        val wordPath = LyricsIndexStore.read(context).single().wordByWordFile
        val next = LyricsStorage.listAllLyrics(context).single()
        assertEquals(LyricsReplacementOps.Result.SAVED, LyricsStorage.replaceSavedLyrics(context, next, plain))
        assertFalse(LyricsFileStore.managedLyricsExists(context, wordPath))
        assertFalse(LyricsStorage.hasWordByWordLyrics(context, "Song", "Artist", 180_000))
        assertEquals(plain.plainLrc, LyricsStorage.readPlainLyrics(context, "Song", "Artist", 180_000))
        assertEquals(LyricsStorage.SOURCE_MANUAL_IMPORT, LyricsIndexStore.read(context).single().plainSource)
    }

    @Test fun deletedSelectedEntryIsNeverRecreated() {
        val item = seed()
        LyricsStorage.deleteLocalLyricsItem(context, item)
        assertEquals(LyricsReplacementOps.Result.STALE, LyricsStorage.replaceSavedLyrics(context, item, plain))
        assertTrue(LyricsStorage.listAllLyrics(context).isEmpty())
    }

    @Test fun failureAfterPartialWordWriteRestoresBothFilesAndIndex() {
        val item = seed()
        val before = LyricsIndexStore.read(context)
        var failed = false
        val io = object : ManagedLyricsIo by AndroidManagedLyricsIo {
            override fun write(context: Context, fileName: String, lyrics: String): Boolean {
                if (fileName.endsWith(".karaoke.json") && !failed) {
                    failed = true
                    AndroidManagedLyricsIo.write(context, fileName, "partial")
                    return false
                }
                return AndroidManagedLyricsIo.write(context, fileName, lyrics)
            }
        }
        assertEquals(LyricsReplacementOps.Result.FAILED, LyricsReplacementOps.replace(context, item, words, io))
        assertTrue(failed)
        assertEquals(before, LyricsIndexStore.read(context))
        assertEquals("[00:01.00]old", LyricsStorage.readPlainLyrics(context, "Song", "Artist", 180_000))
        assertEquals(1, LyricsStoragePaths.fallbackManagedLyricsDir(context).listFiles()!!.size)
    }

    @Test fun indexFailureDuringDowngradeRestoresOldWordLyrics() {
        seed()
        assertEquals(LyricsReplacementOps.Result.SAVED, LyricsStorage.replaceSavedLyrics(context, LyricsStorage.listAllLyrics(context).single(), words))
        val item = LyricsStorage.listAllLyrics(context).single()
        val before = LyricsIndexStore.read(context)
        val index = object : LyricsIndexIo by AndroidLyricsIndexIo {
            override fun write(context: Context, entries: List<LyricsIndexEntry>): Boolean {
                AndroidLyricsIndexIo.write(context, entries)
                return false
            }
        }
        assertEquals(LyricsReplacementOps.Result.FAILED, LyricsReplacementOps.replace(context, item, plain, indexIo = index))
        assertEquals(before, LyricsIndexStore.read(context))
        assertTrue(LyricsStorage.hasWordByWordLyrics(context, "Song", "Artist", 180_000))
        assertEquals(words.plainLrc, LyricsStorage.readPlainLyrics(context, "Song", "Artist", 180_000))
    }

    @Test fun failedRollbackIsReportedExplicitly() {
        val item = seed()
        val io = object : ManagedLyricsIo by AndroidManagedLyricsIo {
            override fun write(context: Context, fileName: String, lyrics: String) = false
        }
        assertEquals(LyricsReplacementOps.Result.ROLLBACK_FAILED, LyricsReplacementOps.replace(context, item, words, io))
    }

    @Test fun replacementPreservesOtherVersionsEvenWhenTitlesAndArtistsAgree() {
        val item = seed()
        val existing = LyricsIndexStore.read(context).single()
        val other = existing.copy(key = "other-version", durationMs = 240_000, plainFile = "lyrics/other.lrc")
        LyricsFileStore.writeManagedLyrics(context, "other.lrc", "[00:01.00]live")
        LyricsIndexStore.write(context, listOf(existing, other))
        assertEquals(LyricsReplacementOps.Result.SAVED, LyricsStorage.replaceSavedLyrics(context, item, plain))
        assertEquals(other, LyricsIndexStore.read(context).single { it.key == other.key })
        assertEquals("[00:01.00]live", LyricsFileStore.readManagedLyrics(context, other.plainFile))
    }

    @Test fun changedSelectedEntryPreservesCurrentFilesAndIndex() {
        val stale = seed()
        val original = LyricsIndexStore.read(context).single()
        LyricsFileStore.writeManagedLyrics(context, original.plainFile.substringAfterLast('/'), "[00:01.00]edited elsewhere")
        LyricsIndexStore.write(context, listOf(original.copy(updatedAt = original.updatedAt + 1)))
        val before = LyricsIndexStore.read(context)
        assertEquals(LyricsReplacementOps.Result.STALE, LyricsStorage.replaceSavedLyrics(context, stale, plain))
        assertEquals(before, LyricsIndexStore.read(context))
        assertEquals("[00:01.00]edited elsewhere", LyricsFileStore.readManagedLyrics(context, original.plainFile))
    }

    @Test fun unreadableFileSnapshotDoesNotWrite() {
        val item = seed()
        val before = LyricsIndexStore.read(context)
        val io = object : ManagedLyricsIo by AndroidManagedLyricsIo {
            override fun read(context: Context, relativeFile: String): String? = null
            override fun write(context: Context, fileName: String, lyrics: String): Boolean = error("Must not write without snapshot")
        }
        assertEquals(LyricsReplacementOps.Result.FAILED, LyricsReplacementOps.replace(context, item, plain, io))
        assertEquals(before, LyricsIndexStore.read(context))
        assertEquals("[00:01.00]old", LyricsStorage.readPlainLyrics(context, "Song", "Artist", 180_000))
    }

    @Test fun conflictingFileOwnerPreventsReplacement() {
        val item = seed()
        val entry = LyricsIndexStore.read(context).single()
        val entries = listOf(entry, entry.copy(key = "other", durationMs = 240_000))
        val index = object : LyricsIndexIo by AndroidLyricsIndexIo {
            override fun read(context: Context) = entries
            override fun write(context: Context, entries: List<LyricsIndexEntry>): Boolean = error("Must not write a shared path")
        }
        assertEquals(LyricsReplacementOps.Result.FAILED, LyricsReplacementOps.replace(context, item, plain, indexIo = index))
        assertEquals(listOf(entry), LyricsIndexStore.read(context))
        assertEquals("[00:01.00]old", LyricsFileStore.readManagedLyrics(context, entry.plainFile))
    }

    @Test fun failedWordFileCleanupRollsBackPlainFileAndRawIndex() {
        val item = seed()
        assertEquals(LyricsReplacementOps.Result.SAVED, LyricsStorage.replaceSavedLyrics(context, item, words))
        val original = LyricsIndexStore.read(context).single()
        val other = original.copy(key = "other-version", durationMs = 240_000, plainFile = "lyrics/other.lrc", wordByWordFile = "")
        assertTrue(LyricsFileStore.writeManagedLyrics(context, "other.lrc", "[00:01.00]other version"))
        assertTrue(LyricsIndexStore.write(context, listOf(original, other)))
        val indexFile = java.io.File(LyricsStoragePaths.fallbackLyricsDir(context), INDEX_FILE_NAME)
        val json = org.json.JSONArray(indexFile.readText())
        json.getJSONObject(0).put("futureMetadata", org.json.JSONObject().put("unknown", "preserve"))
        indexFile.writeText(json.toString(3))
        val rawBefore = indexFile.readBytes()
        val selected = LyricsStorage.listAllLyrics(context).single { it.indexKey == item.indexKey }
        val before = LyricsIndexStore.read(context)
        val files = before.flatMap { listOf(it.plainFile, it.wordByWordFile) }.filter { it.isNotBlank() }.associateWith { LyricsFileStore.readManagedLyrics(context, it) }
        val io = object : ManagedLyricsIo by AndroidManagedLyricsIo {
            override fun delete(context: Context, relativeFile: String): Boolean = false
        }
        assertEquals(LyricsReplacementOps.Result.FAILED, LyricsReplacementOps.replace(context, selected, plain, io))
        assertEquals(before, LyricsIndexStore.read(context))
        files.forEach { (path, content) -> assertEquals(content, LyricsFileStore.readManagedLyrics(context, path)) }
        assertArrayEquals(rawBefore, indexFile.readBytes())
    }

    @Test fun unreadableIndexSnapshotPreventsAnyFileWrites() {
        val item = seed()
        val index = object : LyricsIndexIo by AndroidLyricsIndexIo {
            override fun captureRaw(context: Context) = LyricsIndexStore.RawSnapshot.Unreadable
        }
        val before = (LyricsIndexStore.captureRaw(context) as LyricsIndexStore.RawSnapshot.Present).bytes
        assertEquals(LyricsReplacementOps.Result.FAILED, LyricsReplacementOps.replace(context, item, plain, indexIo = index))
        assertArrayEquals(before, (LyricsIndexStore.captureRaw(context) as LyricsIndexStore.RawSnapshot.Present).bytes)
        assertEquals("[00:01.00]old", LyricsStorage.readPlainLyrics(context, "Song", "Artist", 180_000))
    }

    private fun seed(): LyricsStorage.LocalLyricsItem {
        assertTrue(LyricsStorage.savePlainLyrics(context, "Song", "Artist", 180_000, "[00:01.00]old"))
        return LyricsStorage.listAllLyrics(context).single()
    }
}
