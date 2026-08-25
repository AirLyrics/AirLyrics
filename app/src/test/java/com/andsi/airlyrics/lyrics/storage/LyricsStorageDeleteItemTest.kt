package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.SongIdentity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsStorageDeleteItemTest {
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
    fun listAllLyrics_returnsEveryItem_whileRecentLyricsKeepsEightAndExposesIdentity() {
        repeat(10) { index ->
            assertTrue(
                LyricsStorage.savePlainLyrics(
                    context = context,
                    title = "Song $index",
                    artist = "Artist $index",
                    duration = 180_000L + index * 1_000L,
                    plainLrc = "[00:01.00]line $index",
                    album = "Album $index",
                    plainSource = LyricsStorage.SOURCE_MANUAL_IMPORT,
                    plainProvider = "provider-$index"
                )
            )
        }

        val allLyrics = LyricsStorage.listAllLyrics(context)
        val recentLyrics = LyricsStorage.listRecentLyrics(context)

        assertEquals(10, allLyrics.size)
        assertEquals(8, recentLyrics.size)
        assertEquals(allLyrics.take(8).map { it.indexKey }, recentLyrics.map { it.indexKey })

        val item = allLyrics.single { it.title == "Song 4" }
        assertEquals("Artist 4", item.artist)
        assertEquals("Album 4", item.album)
        assertEquals(184_000L, item.durationMs)
        assertEquals(
            SongIdentity(
                title = "Song 4",
                artist = "Artist 4",
                album = "Album 4",
                durationMs = 184_000L
            ).storageKey(),
            item.indexKey
        )
        assertEquals(LyricsStorage.SOURCE_MANUAL_IMPORT, item.source)
        assertEquals("provider-4", item.provider)
        assertTrue(item.hasPlainLyrics)
        assertFalse(item.hasWordByWordLyrics)
        assertTrue(item.canDelete)
    }

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

    private fun resetStorage() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
    }
}
