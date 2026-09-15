package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsStorageDeletionTest : LyricsStorageTestBase() {
    @Test
    fun deleteLocalLyricsItem_usesExactIndexKeyAndDeletesBothFormatsOnlyForSelectedSong() {
        val selectedIdentity = SongIdentity(
            title = "Same title",
            artist = "Same artist",
            album = "Selected album",
            durationMs = 180_000L
        )
        val retainedIdentity = SongIdentity(
            title = "Same title",
            artist = "Same artist",
            album = "Retained album",
            durationMs = 300_000L
        )
        val selectedEntry = createIndexedLyricsEntry(selectedIdentity, updatedAt = 2_000L)
        val retainedEntry = createIndexedLyricsEntry(retainedIdentity, updatedAt = 1_000L)
        assertTrue(LyricsIndexStore.write(context, listOf(selectedEntry, retainedEntry)))

        val selectedItem = LyricsStorage.listAllLyrics(context)
            .single { it.indexKey == selectedEntry.key }
        val result = LyricsStorage.deleteLocalLyricsItem(context, selectedItem)

        val deleted = result as LyricsStorage.DeleteLocalLyricsItemResult.Deleted
        assertEquals(selectedIdentity, deleted.target)
        assertFalse(LyricsFileStore.managedLyricsExists(context, selectedEntry.plainFile))
        assertFalse(LyricsFileStore.managedLyricsExists(context, selectedEntry.wordByWordFile))
        assertTrue(LyricsFileStore.managedLyricsExists(context, retainedEntry.plainFile))
        assertTrue(LyricsFileStore.managedLyricsExists(context, retainedEntry.wordByWordFile))
        assertEquals(listOf(retainedEntry.key), LyricsIndexStore.read(context).map { it.key })
        assertEquals(
            listOf(retainedEntry.key),
            LyricsStorage.listAllLyrics(context).map { it.indexKey }
        )
    }

    @Test
    fun deleteLocalLyricsItem_deletesOrdinaryRootLrcByExactBaseName() {
        val rootLyrics = File(
            LyricsStoragePaths.fallbackLyricsDir(context),
            "personal-reference.lrc"
        ).apply { writeText("[00:01.00]personal") }
        val item = LyricsStorage.listAllLyrics(context).single()
        assertTrue(item.canDelete)

        val result = LyricsStorage.deleteLocalLyricsItem(context, item)

        val deleted = result as LyricsStorage.DeleteLocalLyricsItemResult.Deleted
        assertNull(deleted.target)
        assertFalse(rootLyrics.exists())
        assertTrue(LyricsStorage.listAllLyrics(context).isEmpty())
    }

    @Test
    fun deleteLocalLyricsItem_neverFallsBackFromStaleKeyOrPathName() {
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = context,
                title = "Indexed song",
                artist = "AirLyrics",
                duration = 240_000L,
                plainLrc = "[00:01.00]indexed"
            )
        )
        val indexedItem = LyricsStorage.listAllLyrics(context).single()
        val indexedEntry = LyricsIndexStore.read(context).single()
        val rootLyrics = File(
            LyricsStoragePaths.fallbackLyricsDir(context),
            "personal-reference.lrc"
        ).apply { writeText("[00:01.00]personal") }

        assertEquals(
            LyricsStorage.DeleteLocalLyricsItemResult.NotFound,
            LyricsStorage.deleteLocalLyricsItem(
                context,
                indexedItem.copy(indexKey = "stale-index-key")
            )
        )
        assertEquals(
            LyricsStorage.DeleteLocalLyricsItemResult.NotFound,
            LyricsStorage.deleteLocalLyricsItem(
                context,
                LyricsStorage.LocalLyricsItem(
                    name = "nested/${rootLyrics.name}",
                    modifiedTimeMillis = rootLyrics.lastModified(),
                    sizeBytes = rootLyrics.length()
                )
            )
        )

        assertTrue(LyricsFileStore.managedLyricsExists(context, indexedEntry.plainFile))
        assertEquals(listOf(indexedEntry.key), LyricsIndexStore.read(context).map { it.key })
        assertTrue(rootLyrics.exists())
    }

    @Test
    fun deleteAllSavedLyrics_removesManagedOrphanAndLegacyLyrics_butPreservesOtherFiles() {
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = context,
                title = "Plain song",
                artist = "AirLyrics",
                duration = 180_000L,
                plainLrc = "[00:01.00]plain"
            )
        )
        assertTrue(
            LyricsStorage.saveWordByWordLyrics(
                context = context,
                title = "Word song",
                artist = "AirLyrics",
                duration = 200_000L,
                wordByWordLines = listOf(
                    WordByWordLine(
                        startMs = 1_000L,
                        endMs = 2_000L,
                        text = "word",
                        segments = listOf(WordByWordSegment("word", 1_000L, 2_000L))
                    )
                )
            )
        )

        val root = LyricsStoragePaths.fallbackLyricsDir(context)
        val managedDir = LyricsStoragePaths.fallbackManagedLyricsDir(context)
        val legacyLyrics = File(
            root,
            LyricsFileNaming.legacyPlainFileName("Legacy song", "AirLyrics", 220_000L)
        ).apply { writeText("[00:01.00]legacy") }
        val orphanManagedLyrics = File(managedDir, "orphan.lrc").apply {
            writeText("[00:01.00]orphan")
        }
        val unrelatedRootLyrics = File(root, "personal-reference.lrc").apply {
            writeText("[00:01.00]keep")
        }
        val unrelatedManagedFile = File(managedDir, "notes.txt").apply {
            writeText("keep")
        }

        assertEquals(
            LyricsStorage.DeleteAllSavedLyricsResult.DELETED,
            LyricsStorage.deleteAllSavedLyrics(context)
        )

        assertTrue(LyricsIndexStore.read(context).isEmpty())
        assertFalse(legacyLyrics.exists())
        assertFalse(orphanManagedLyrics.exists())
        assertFalse(managedDir.listFiles().orEmpty().any { file ->
            LyricsFileNaming.isPlainLyricsFile(file.name) ||
                LyricsFileNaming.isWordByWordLyricsFile(file.name)
        })
        assertTrue(unrelatedRootLyrics.exists())
        assertTrue(unrelatedManagedFile.exists())
        assertEquals(
            LyricsStorage.DeleteAllSavedLyricsResult.NOTHING_TO_DELETE,
            LyricsStorage.deleteAllSavedLyrics(context)
        )
    }

    private fun createIndexedLyricsEntry(
        identity: SongIdentity,
        updatedAt: Long
    ): LyricsIndexEntry {
        val plainFileName = LyricsFileNaming.managedPlainFileName(identity)
        val wordByWordFileName = LyricsFileNaming.managedWordByWordFileName(identity)
        assertTrue(
            LyricsFileStore.writeManagedLyrics(
                context,
                plainFileName,
                "[00:01.00]${identity.durationMs}"
            )
        )
        assertTrue(
            LyricsFileStore.writeManagedLyrics(
                context,
                wordByWordFileName,
                "[]"
            )
        )
        return LyricsIndexEntry(
            key = identity.storageKey(),
            title = identity.title,
            artist = identity.artist,
            album = identity.album,
            durationMs = identity.durationMs,
            plainFile = LyricsFileNaming.managedRelativePath(plainFileName),
            wordByWordFile = LyricsFileNaming.managedRelativePath(wordByWordFileName),
            plainSource = LyricsStorage.SOURCE_DOWNLOADED,
            plainProvider = "plain-provider",
            wordByWordProvider = "word-provider",
            createdAt = updatedAt,
            updatedAt = updatedAt
        )
    }

}
