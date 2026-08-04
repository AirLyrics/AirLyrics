package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LyricsStorageInstrumentedTest {
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
    fun saveReadListAndDeletePlainLyrics_usesManagedIndex() {
        val title = "Storage Test Song"
        val artist = "AndSi"
        val duration = 123_456L
        val plainLrc = "[00:01.00]hello\n[00:02.00]world"

        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = context,
                title = title,
                artist = artist,
                duration = duration,
                plainLrc = plainLrc,
                album = "Test Album",
                plainSource = LyricsStorage.SOURCE_DOWNLOADED,
                plainProvider = "unit-provider"
            )
        )

        assertEquals(plainLrc, LyricsStorage.readPlainLyrics(context, title, artist, duration))
        assertTrue(LyricsStorage.hasPlainLyrics(context, title, artist, duration))

        val info = LyricsStorage.getLocalPlainLyricsInfo(context, title, artist, duration)
        assertNotNull(info)
        assertEquals(title, info!!.title)
        assertEquals(artist, info.artist)
        assertEquals("unit-provider", info.plainProvider)

        val recentItem = LyricsStorage.listRecentLyrics(context, limit = 10)
            .firstOrNull { it.title == title && it.artist == artist }
        assertNotNull(recentItem)
        assertEquals(title, recentItem!!.displayTitle)
        assertTrue(recentItem.hasPlainLyrics)
        assertFalse(recentItem.hasWordByWordLyrics)

        assertTrue(LyricsStorage.deleteLocalLyrics(context, title, artist, duration))
        assertNull(LyricsStorage.readPlainLyrics(context, title, artist, duration))
        assertFalse(LyricsStorage.hasPlainLyrics(context, title, artist, duration))
    }

    @Test
    fun saveWordByWordLyrics_deleteWordByWordOnly_keepsPlainLyrics() {
        val title = "Storage Karaoke Song"
        val artist = "AndSi"
        val duration = 222_000L
        val plainLrc = "[00:01.00]plain line"
        val wordByWordLines = listOf(
            WordByWordLine(
                startMs = 1_000L,
                endMs = 2_000L,
                text = "sing",
                segments = listOf(
                    WordByWordSegment("si", 1_000L, 1_500L),
                    WordByWordSegment("ng", 1_500L, 2_000L)
                )
            )
        )

        assertTrue(LyricsStorage.savePlainLyrics(context, title, artist, duration, plainLrc))
        assertTrue(LyricsStorage.saveWordByWordLyrics(context, title, artist, duration, wordByWordLines))
        assertTrue(LyricsStorage.hasWordByWordLyrics(context, title, artist, duration))
        assertEquals(wordByWordLines, LyricsStorage.readWordByWordLyrics(context, title, artist, duration))

        assertTrue(
            LyricsStorage.deleteLocalLyrics(
                context = context,
                title = title,
                artist = artist,
                duration = duration,
                mode = LyricsStorage.DeleteMode.WORD_BY_WORD
            )
        )

        assertEquals(plainLrc, LyricsStorage.readPlainLyrics(context, title, artist, duration))
        assertFalse(LyricsStorage.hasWordByWordLyrics(context, title, artist, duration))
    }

    @Test
    fun savePlainLyrics_respectsOverwriteFalse() {
        val title = "Overwrite Test Song"
        val artist = "AndSi"
        val duration = 333_000L
        val first = "[00:01.00]first"
        val second = "[00:01.00]second"

        assertTrue(LyricsStorage.savePlainLyrics(context, title, artist, duration, first, overwrite = true))
        assertFalse(LyricsStorage.savePlainLyrics(context, title, artist, duration, second, overwrite = false))
        assertEquals(first, LyricsStorage.readPlainLyrics(context, title, artist, duration))
    }

    @Test
    fun concurrentSavePlainLyrics_keepsEveryIndexEntry() {
        val count = 32
        val startGate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val futures = (0 until count).map { index ->
            executor.submit<Boolean> {
                startGate.await()
                LyricsStorage.savePlainLyrics(
                    context = context,
                    title = "Concurrent Song $index",
                    artist = "AndSi",
                    duration = 400_000L + index,
                    plainLrc = "[00:01.00]line $index",
                    plainProvider = "concurrent-test"
                )
            }
        }

        startGate.countDown()
        futures.forEach { future -> assertTrue(future.get(10, TimeUnit.SECONDS)) }
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        val entries = LyricsIndexStore.read(context)
            .filter { it.plainProvider == "concurrent-test" }

        assertEquals(count, entries.size)
        (0 until count).forEach { index ->
            assertTrue(entries.any { it.title == "Concurrent Song $index" })
        }
    }

    private fun resetStorage() {
        context.getSharedPreferences("lyrics_storage", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, "lyrics").deleteRecursively()
    }
}
