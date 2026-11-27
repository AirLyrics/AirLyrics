package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.WordLyricsDocument
import com.andsi.airlyrics.lyrics.WordLyricsTextEditor
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

    @Test fun exactIdCacheDoesNotReturnAnotherRecording() {
        val cache = cache()
        cache.save(key, "Test", "Artist", "", 10000, spicyFixture())
        assertNotNull(cache.readForTrack(key, "synthetic"))
        assertNull(cache.readForTrack(key, "another-recording"))
        assertNotNull(cache.read(key))
    }

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
        val draft = WordLyricsDocument(original.wordByWordLines, translatedLrc = original.translatedLrc)
        now += 100
        assertTrue(cache.editWords(key, draft).saved)
        assertEquals(original, cache.read(key))

        val line = draft.lines.single()
        val segments = line.segments.map { it.copy(text = it.text.replace("world", "earth")) }
        val updated = draft.copy(lines = listOf(WordLyricsTextEditor.withSegments(line, segments)))
        assertTrue(cache.editWords(key, updated).saved)
        val edited = cache.read(key)!!
        assertEquals(original.access, edited.access)
        assertEquals(original.wordByWordLines.single().endMs, edited.wordByWordLines.single().endMs)
        assertEquals(original.wordByWordLines.single().segments.first(), edited.wordByWordLines.single().segments.first())
        assertTrue(edited.plainLrc.contains("earth"))
        assertEquals(spicyFixture(), JSONObject(folder.root.resolve("$key.json").readText()).getString("raw"))
        val access = requireNotNull(original.access)
        val revision = LyricsStorage.currentRevision()
        now = access.expiresAtMs
        assertNull(cache.read(key))
        assertEquals(1L, LyricsStorage.currentRevision() - revision)
        assertNull(cache.save(key, "Test", "Artist", "", 10000, spicyFixture(), fetchedAtMs = access.fetchedAtMs))
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun readsLegacyCachedWordEditsWithTheirOriginalTimingAndExpiry() {
        val cache = cache()
        val original = requireNotNull(cache.save(key, "Test", "Artist", "", 10000, spicyFixture()))
        val cacheFile = folder.root.resolve("$key.json")
        val legacy = JSONObject(cacheFile.readText()).apply {
            put("edit", "[00:01.00]<00:01.00>Edited<00:03.78>")
            put(
                "wordEdit",
                """[{"startMs":1001,"endMs":3789,"text":"Edited",
                    "tokens":[{"startMs":1001,"endMs":3789,"text":"Edited"}]}]""",
            )
        }
        cacheFile.writeText(legacy.toString())

        val restored = requireNotNull(cache.read(key))
        assertEquals("[00:01.001]Edited", restored.plainLrc)
        assertEquals(
            WordByWordSegment("Edited", 1001L, 3789L),
            restored.wordByWordLines.single().segments.single()
        )
        assertEquals(original.access, restored.access)
    }

    @Test fun editedLineAlsoExpiresAndCannotBeResaved() {
        val cache = cache()
        cache.save(key, "Test", "Artist", "", 10000, spicyFixture("Line", "spotify"))
        now += SpicyLyricsParser.RETENTION_MS - 1
        assertTrue(cache.editPlain(key, "[00:01.00]Correction").saved)
        assertTrue(cache.read(key)!!.plainLrc.contains("Correction"))
        now++
        assertFalse(cache.editPlain(key, "[00:01.00]Extend").saved)
        assertTrue(cache.list().isEmpty())
    }
    @Test fun privateCacheStaysSeparateAndDoesNotBlockManualImport() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val cache = SpicyLyricsCache.forContext(context)
        try {
            cache.save(key, "Test", "Artist", "", 10000, spicyFixture())
            assertEquals(LyricsStorage.SOURCE_PROVIDER_CACHE, LyricsStorage.getLocalPlainLyricsInfo(context, "Test", "Artist", 10000)!!.plainSource)
            assertTrue(LyricsIndexStore.read(context).isEmpty())
            assertFalse(requireNotNull(LyricsStorage.restrictedLyricsAccess(context, "spicy:$key")).allowExport)
            val imported = folder.root.resolve("manual.lrc").apply { writeText("[00:01.00]Manual") }
            assertEquals(
                LyricsStorage.ImportLyricsResult.Saved,
                LyricsStorage.importPlainLyricsFromUriWithResult(
                    context, android.net.Uri.fromFile(imported), "Test", "Artist", 10000
                )
            )
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
