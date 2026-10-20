package com.andsi.airlyrics.lyrics.exporter

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.importer.BatchLyricsDocumentReader
import com.andsi.airlyrics.lyrics.importer.BatchLyricsMatcher
import com.andsi.airlyrics.lyrics.importer.LyricsDocumentFormat
import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser
import com.andsi.airlyrics.lyrics.providers.spicyFixture
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.SpicyLyricsCache
import com.andsi.airlyrics.testutil.resetLyricsStorage
import java.io.File
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchLyricsExporterTest {
    private lateinit var context: Context
    private lateinit var destination: File
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        resetLyricsStorage(context)
        SpicyLyricsCache.forContext(context).clear()
        destination = File(context.cacheDir, "test-export.zip")
    }
    @After fun cleanup() {
        resetLyricsStorage(context)
        SpicyLyricsCache.forContext(context).clear()
        destination.delete()
    }

    @Test fun safeUniqueFilenamesAndIdentitySurviveEditingAndReimport() {
        for (title in listOf("../Song:Live", "../Song/Live")) {
            assertTrue(LyricsStorage.savePlainLyrics(context, title, "Artist/Name", 180_000,
                "[00:01.00]old"))
        }
        val items = LyricsStorage.listAllLyrics(context)
        val result = export(items)
        assertEquals(2, result.exported)
        assertTrue(result.failures.isEmpty())
        val documents = ZipFile(destination).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(2, entries.map { it.name.lowercase() }.distinct().size)
            assertTrue(entries.all { '/' !in it.name && '\\' !in it.name && ':' !in it.name && it.name.endsWith(".lrc") })
            entries.map { entry ->
                val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }.replace("old", "edited")
                val parsed = BatchLyricsDocumentReader.parse(entry.name, text, LyricsDocumentFormat.LRC)
                assertNull(parsed.failure)
                assertFalse(parsed.document!!.plainLrc.contains("airlyrics-key"))
                parsed.document
            }
        }
        val matches = BatchLyricsMatcher.match(documents.map { it.matchInfo }, items.map {
            BatchLyricsMatcher.Target(it.indexKey, SongIdentity(it.title, it.artist, it.album, it.durationMs))
        })
        assertEquals(items.map { it.indexKey }.toSet(), matches.map { it.target?.key }.toSet())
        assertTrue(LyricsStorage.listAllLyrics(context) == items)
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("lyrics-export-") })
    }

    @Test fun wordLyricsExportAsEditableEnhancedLrc() {
        val words = WordByWordLrcParser.parse("[00:01.00]<00:01.00>hello <00:02.00>world")
        assertTrue(LyricsStorage.saveWordByWordLyrics(context, "Words", "Artist", 180_000, words))
        assertEquals(1, export(LyricsStorage.listAllLyrics(context)).exported)
        ZipFile(destination).use { zip ->
            val entry = zip.entries().nextElement()
            val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val document = BatchLyricsDocumentReader.parse(entry.name, text, LyricsDocumentFormat.LRC).document!!
            assertEquals(listOf("hello ", "world"), document.words.single().segments.map { it.text })
            assertEquals(listOf(1000L, 2000L), document.words.single().segments.map { it.startMs })
        }
    }

    @Test fun onlyRequestedItemsExportAndRestrictedCacheIsReported() {
        assertTrue(LyricsStorage.savePlainLyrics(context, "Local", "Artist", 180_000, "[00:01.00]local"))
        assertTrue(LyricsStorage.savePlainLyrics(context, "Unselected", "Artist", 180_000, "[00:01.00]other"))
        val cache = SpicyLyricsCache.forContext(context)
        val key = SpicyLyricsCache.key("Cached", "Artist", 180_000)
        assertNotNull(cache.save(key, "Cached", "Artist", "", 180_000, spicyFixture()))
        val result = export(LyricsStorage.listAllLyrics(context).filter { it.title != "Unselected" })
        assertEquals(1, result.exported)
        assertEquals(BatchLyricsExporter.Problem.RESTRICTED, result.failures.single().problem)
        ZipFile(destination).use { assertEquals(1, it.size()) }
    }

    @Test fun staleItemsAndChangedDirectoryAreSkippedWithoutWritingDestination() {
        assertTrue(LyricsStorage.savePlainLyrics(context, "Local", "Artist", 180_000, "[00:01.00]local"))
        val items = LyricsStorage.listAllLyrics(context)
        val result = BatchLyricsExporter.export(context, items, "changed-directory", Uri.fromFile(destination))
        assertEquals(0, result.exported)
        assertEquals(BatchLyricsExporter.Problem.CHANGED, result.failures.single().problem)
        assertFalse(destination.exists())
        LyricsStorage.deleteLocalLyricsItem(context, items.single())
        assertEquals(BatchLyricsExporter.Problem.CHANGED, export(items).failures.single().problem)
    }

    @Test fun destinationFailureIsReportedAndStagingFileIsRemoved() {
        assertTrue(LyricsStorage.savePlainLyrics(context, "Local", "Artist", 180_000, "[00:01.00]local"))
        val result = BatchLyricsExporter.export(context, LyricsStorage.listAllLyrics(context),
            LyricsStorage.getLyricsDirRawPath(context), Uri.fromFile(context.cacheDir))
        assertTrue(result.writeFailed)
        assertEquals(0, result.exported)
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("lyrics-export-") })
    }

    private fun export(items: List<LyricsStorage.LocalLyricsItem>) = BatchLyricsExporter.export(
        context, items, LyricsStorage.getLyricsDirRawPath(context), Uri.fromFile(destination))
}
