package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.lyrics.KaraokeToken
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
        val lyrics = "[00:01.00]hello\n[00:02.00]world"

        assertTrue(
            LyricsStorage.saveLyrics(
                context = context,
                title = title,
                artist = artist,
                duration = duration,
                lyrics = lyrics,
                album = "Test Album",
                source = LyricsStorage.SOURCE_DOWNLOADED,
                provider = "unit-provider"
            )
        )

        assertEquals(lyrics, LyricsStorage.readLocalLyrics(context, title, artist, duration))
        assertTrue(LyricsStorage.hasLocalLyrics(context, title, artist, duration))

        val info = LyricsStorage.getLocalLyricsInfo(context, title, artist, duration)
        assertNotNull(info)
        assertEquals(title, info!!.title)
        assertEquals(artist, info.artist)
        assertEquals("unit-provider", info.provider)

        val recentItem = LyricsStorage.listRecentLyrics(context, limit = 10)
            .firstOrNull { it.title == title && it.artist == artist }
        assertNotNull(recentItem)
        assertEquals(title, recentItem!!.displayTitle)
        assertTrue(recentItem.hasPlainLyrics)
        assertFalse(recentItem.hasKaraokeLyrics)

        assertTrue(LyricsStorage.deleteLocalLyrics(context, title, artist, duration))
        assertNull(LyricsStorage.readLocalLyrics(context, title, artist, duration))
        assertFalse(LyricsStorage.hasLocalLyrics(context, title, artist, duration))
    }

    @Test
    fun saveKaraokeDeleteKaraokeOnly_keepsPlainLyrics() {
        val title = "Storage Karaoke Song"
        val artist = "AndSi"
        val duration = 222_000L
        val plainLyrics = "[00:01.00]plain line"
        val karaokeLines = listOf(
            KaraokeLine(
                startMs = 1_000L,
                endMs = 2_000L,
                text = "sing",
                tokens = listOf(
                    KaraokeToken("si", 1_000L, 1_500L),
                    KaraokeToken("ng", 1_500L, 2_000L)
                )
            )
        )

        assertTrue(LyricsStorage.saveLyrics(context, title, artist, duration, plainLyrics))
        assertTrue(LyricsStorage.saveKaraokeLyrics(context, title, artist, duration, karaokeLines))
        assertTrue(LyricsStorage.hasKaraokeLyrics(context, title, artist, duration))
        assertEquals(karaokeLines, LyricsStorage.readKaraokeLyrics(context, title, artist, duration))

        assertTrue(
            LyricsStorage.deleteLocalLyrics(
                context = context,
                title = title,
                artist = artist,
                duration = duration,
                mode = LyricsStorage.DeleteMode.KARAOKE
            )
        )

        assertEquals(plainLyrics, LyricsStorage.readLocalLyrics(context, title, artist, duration))
        assertFalse(LyricsStorage.hasKaraokeLyrics(context, title, artist, duration))
    }

    @Test
    fun saveLyrics_respectsOverwriteFalse() {
        val title = "Overwrite Test Song"
        val artist = "AndSi"
        val duration = 333_000L
        val first = "[00:01.00]first"
        val second = "[00:01.00]second"

        assertTrue(LyricsStorage.saveLyrics(context, title, artist, duration, first, overwrite = true))
        assertFalse(LyricsStorage.saveLyrics(context, title, artist, duration, second, overwrite = false))
        assertEquals(first, LyricsStorage.readLocalLyrics(context, title, artist, duration))
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
