package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsStorageListingTest : LyricsStorageTestBase() {
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
            WordByWordLine(
                startMs = 1_000L,
                endMs = 2_000L,
                text = "lyrics",
                segments = listOf(
                    WordByWordSegment("lyrics", 1_000L, 2_000L)
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

}
