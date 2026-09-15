package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.core.model.SongIdentity
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsStorageCompatibilityTest : LyricsStorageTestBase() {
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
}
