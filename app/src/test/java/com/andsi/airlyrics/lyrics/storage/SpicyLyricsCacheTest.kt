package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser
import com.andsi.airlyrics.lyrics.providers.SpicyLyricsParser
import com.andsi.airlyrics.lyrics.providers.spicyFixture
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpicyLyricsCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 1000L
    private val key = SpicyLyricsCache.key("Test", "Artist", 10000)
    private fun cache() = SpicyLyricsCache(folder.root) { now }

    @Test fun localLyricsFileSizeReportsSpicyCacheDocumentSize() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val title = "Sized Spicy Lyrics"
        val artist = "AndSi"
        val duration = 184_000L
        val cacheKey = SpicyLyricsCache.key(title, artist, duration)
        val cache = SpicyLyricsCache.forContext(context)
        try {
            assertNotNull(cache.save(cacheKey, title, artist, "", duration, spicyFixture()))
            val info = LyricsStorage.getLocalPlainLyricsInfo(context, title, artist, duration)!!
            val cacheFile = File(context.noBackupFilesDir, "spicy-lyrics/$cacheKey.json")

            assertEquals("spicy:$cacheKey", info.plainFileName)
            assertTrue(cacheFile.length() > 0L)
            assertEquals(cacheFile.length(), LyricsStorage.localLyricsFileSize(context, info.plainFileName))
        } finally {
            cache.delete(cacheKey)
        }
    }

    @Test fun editsPreserveRawMarksPreciseTimingsAndOriginalDeadline() {
        val cache = cache()
        val original = cache.save(key, "Test", "Artist", "", 10000, spicyFixture())!!
        val editor = WordByWordLrcParser.wordByWordLinesToWordByWordLrc(original.wordByWordLines)
        now += 100
        assertTrue(cache.edit(key, editor, true).saved)
        assertEquals(original, cache.read(key))
        assertTrue(cache.edit(key, editor.replace("world", "earth"), true).saved)
        val edited = cache.read(key)!!
        assertEquals(original.access, edited.access)
        assertEquals(original.wordByWordLines.single().endMs, edited.wordByWordLines.single().endMs)
        assertEquals(original.wordByWordLines.single().segments.first(), edited.wordByWordLines.single().segments.first())
        assertTrue(edited.plainLrc.contains("earth"))
        assertEquals(spicyFixture(), JSONObject(folder.root.resolve("$key.json").readText()).getString("raw"))
        now = original.access!!.expiresAtMs
        assertNull(cache.read(key))
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
    @Test fun editedLineAlsoExpiresAndCannotBeResaved() {
        val cache = cache()
        cache.save(key, "Test", "Artist", "", 10000, spicyFixture("Line", "spotify"))
        now += SpicyLyricsParser.RETENTION_MS - 1
        assertTrue(cache.edit(key, "[00:01.00]Correction", false).saved)
        assertTrue(cache.read(key)!!.plainLrc.contains("Correction"))
        now++
        assertFalse(cache.edit(key, "[00:01.00]Extend", false).saved)
        assertTrue(cache.list().isEmpty())
    }
    @Test fun privateSourceCannotBeSavedExternallyAndDoesNotBlockManualImport() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = SpicyLyricsCache.forContext(context)
        try {
            cache.save(key, "Test", "Artist", "", 10000, spicyFixture())
            assertEquals(LyricsStorage.SOURCE_PROVIDER_CACHE, LyricsStorage.getLocalPlainLyricsInfo(context, "Test", "Artist", 10000)!!.plainSource)
            assertFalse(LyricsStorage.savePlainLyrics(context, "Test", "Artist", 10000, "[00:01.00]restricted", plainProvider = "spicy"))
            assertFalse(LyricsStorage.saveWordByWordLyrics(context, "Test", "Artist", 10000, cache.read(key)!!.wordByWordLines, wordByWordProvider = "spicy"))
            val imported = folder.root.resolve("manual.lrc").apply { writeText("[00:01.00]Manual") }
            assertTrue(LyricsStorage.importPlainLyricsFromUri(context, android.net.Uri.fromFile(imported), "Test", "Artist", 10000))
            assertEquals(LyricsStorage.SOURCE_MANUAL_IMPORT, LyricsStorage.getLocalPlainLyricsInfo(context, "Test", "Artist", 10000)!!.plainSource)
            assertFalse(LyricsStorage.hasWordByWordLyrics(context, "Test", "Artist", 10000))
            assertTrue(LyricsStorage.readPlainLyrics(context, "Test", "Artist", 10000)!!.contains("Manual"))
        } finally {
            LyricsStorage.deleteLocalLyrics(context, "Test", "Artist", 10000)
            cache.clear()
        }
    }
    @Test fun clockRollbackAndCorruptionDiscardDocuments() {
        val cache = cache()
        cache.save(key, "Test", "Artist", "", 10000, spicyFixture())
        now--
        assertNull(cache.read(key))
        folder.root.resolve("$key.json").writeText("broken")
        cache.prune()
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
}
