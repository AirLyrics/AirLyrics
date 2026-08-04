package com.andsi.airlyrics.lyrics.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsIndexCompatibilityContractTest {
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
    fun read_acceptsPersistedPlainAndWordByWordFieldNames() {
        indexFile().writeText(
            """
            [
              {
                "key": "legacy-word-timing",
                "title": "Legacy title",
                "artist": "Legacy artist",
                "album": "Legacy album",
                "durationMs": 185000,
                "file": "lyrics/legacy.lrc",
                "karaokeFile": "lyrics/legacy.karaoke.json",
                "source": "local",
                "provider": "local",
                "karaokeProvider": "legacy-provider",
                "createdAt": 10,
                "updatedAt": 20
              }
            ]
            """.trimIndent()
        )

        val entry = LyricsIndexStore.read(context).single()

        assertEquals("lyrics/legacy.lrc", entry.plainFile)
        assertEquals("local", entry.plainSource)
        assertEquals("local", entry.plainProvider)
        assertEquals("lyrics/legacy.karaoke.json", entry.wordByWordFile)
        assertEquals("legacy-provider", entry.wordByWordProvider)
    }

    @Test
    fun write_preservesPersistedPlainAndWordByWordFieldNames() {
        val entry = LyricsIndexEntry(
            key = "word-timing",
            title = "Title",
            artist = "Artist",
            album = "Album",
            durationMs = 185_000L,
            plainFile = "lyrics/current.lrc",
            wordByWordFile = "lyrics/current.karaoke.json",
            plainSource = "downloaded",
            plainProvider = "plain-provider",
            wordByWordProvider = "current-provider",
            createdAt = 30L,
            updatedAt = 40L
        )

        assertTrue(LyricsIndexStore.write(context, listOf(entry)))

        val json = JSONArray(indexFile().readText()).getJSONObject(0)
        assertTrue(json.has("file"))
        assertEquals("lyrics/current.lrc", json.getString("file"))
        assertTrue(json.has("source"))
        assertEquals("downloaded", json.getString("source"))
        assertTrue(json.has("provider"))
        assertEquals("plain-provider", json.getString("provider"))
        assertTrue(json.has("karaokeFile"))
        assertEquals("lyrics/current.karaoke.json", json.getString("karaokeFile"))
        assertTrue(json.has("karaokeProvider"))
        assertEquals("current-provider", json.getString("karaokeProvider"))
    }

    private fun indexFile(): File =
        File(LyricsStoragePaths.fallbackLyricsDir(context), INDEX_FILE_NAME)

    private fun resetStorage() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        File(baseDir, FALLBACK_LYRICS_DIR).deleteRecursively()
    }
}
