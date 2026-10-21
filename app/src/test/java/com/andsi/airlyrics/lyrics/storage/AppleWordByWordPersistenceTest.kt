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
class AppleWordByWordPersistenceTest : LyricsStorageTestBase() {

    private val lines = listOf(
        WordByWordLine(
            startMs = 22_186,
            endMs = 26_552,
            text = "攔路雨偏似雪花",
            segments = listOf(
                WordByWordSegment("攔路雨", 22_186, 23_000),
                WordByWordSegment("偏似雪花", 23_000, 26_552)
            )
        )
    )

    @Test
    fun appleWordTimingIsPersistedAndReadBack() {
        assertTrue(
            LyricsStorage.saveWordByWordLyrics(
                context = context,
                title = "富士山下",
                artist = "陈奕迅",
                duration = 259_133L,
                wordByWordLines = lines,
                wordByWordProvider = "apple"
            )
        )

        val stored = LyricsStorage.readWordByWordLyrics(context, "富士山下", "陈奕迅", 259_133L)
        assertEquals(1, stored.size)
        assertEquals(2, stored[0].segments.size)
        assertTrue(LyricsStorage.hasWordByWordLyrics(context, "富士山下", "陈奕迅", 259_133L))
    }

    @Test
    fun spicyProviderStillCannotWriteThroughThisPath() {
        assertFalse(
            LyricsStorage.saveWordByWordLyrics(
                context = context,
                title = "Spicy Song",
                artist = "Artist",
                duration = 180_000L,
                wordByWordLines = lines,
                wordByWordProvider = "spicy"
            )
        )
    }
}
