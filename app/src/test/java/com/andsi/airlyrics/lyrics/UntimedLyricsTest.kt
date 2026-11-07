package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.*
import com.andsi.airlyrics.lyrics.providers.*
import com.andsi.airlyrics.lyrics.search.*
import com.andsi.airlyrics.lyrics.storage.*
import com.andsi.airlyrics.lyrics.importer.*
import com.andsi.airlyrics.lyrics.exporter.BatchLyricsExporter
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipInputStream

/** Synthetic response in the upstream Static.Lines[].Text schema. */
internal fun staticSpicyFixture(id: String = "123", text: String = "First line\n\n第二段"): String =
    JSONObject(spicyFixture()).apply {
        getJSONObject("track").put("id", id)
        getJSONObject("response").getJSONObject("Body").apply {
            put("id", id); put("Type", "Static"); remove("Content")
            put("Lines", JSONArray().apply { text.split('\n').forEach { put(JSONObject().put("Text", it)) } })
        }
    }.toString()

@RunWith(RobolectricTestRunner::class)
class UntimedLyricsTest : LyricsStorageTestBase() {
    private val song = SongIdentity("Song", "Artist", "Album", 10000)
    private val text = "First line\r\n\r\n第二段 / literal text\n[00:01.00]Still plain text"
    private fun token() = LyricsLookupCancellationToken(song.storageKey(), 1)
    private fun result() = LyricsProviderResult("lrclib", "LRCLIB", "", untimedLyrics = UntimedLyrics(text, "Translation\n\n译文"))
    private fun timed() = LyricsProviderResult("netease", "NetEase", "[00:01.00]Timed")
    private fun local() = LocalPlainLyricsProvider.fetch(PlainLyricsSearchRequest(context, song.title, song.artist,
        song.album, song.durationMs)).getOrThrow()!!

    @Test fun staticParsingKeepsParagraphsCreditsAndAnExplicitTextType() {
        val parsed = SpicyLyricsParser.parse(staticSpicyFixture(text = text), "key", 100)!!
        assertEquals(LyricsPrecision.TEXT, parsed.precision)
        assertEquals(text, parsed.untimedLyrics!!.text)
        assertEquals("", parsed.plainLrc)
        assertTrue(parsed.wordByWordLines.isEmpty())
        assertEquals(listOf("Uploader", "Maker"), parsed.access!!.attribution.credits.map { it.name })
        assertFalse(parsed.access.allowExport)
        assertNull(parsed.manualUnavailableReason())
    }

    @Test fun spotifyIdAndOrdinaryPreviewsAcceptStaticWithoutWriting() {
        val id = "5l94Up12BJSjhH7Mhr2ji4"
        val service = ManualLyricsSearch(context) { _, _ -> staticSpicyFixture(id) }
        val found = service.findBySpotifyId(id, null, song, token())!!
        assertTrue(found.lyrics.isUntimed)
        assertEquals(id, found.candidate.id)
        assertTrue((service.fetch(found.candidate, song, token()) as ManualLyricsFetchResult.Available).lyrics.isUntimed)
        assertTrue(SpicyLyricsCache.forContext(context).list().isEmpty())
        assertNull(ManualLyricsSelectionStore.read(context, song))
    }

    @Test fun lrclibNativeTextIsAnAvailableManualResult() {
        val service = ManualLyricsSearch(context) { _, _ ->
            JSONObject().put("ok", true).put("id", "42").put("lrc", "").put("untimed_text", text).toString()
        }
        val candidate = LyricsCandidate(PlainLyricsSearchSource.LRCLIB, "42", song, 1.0)
        val fetched = service.fetch(candidate, song, token()) as ManualLyricsFetchResult.Available
        assertEquals(text, fetched.lyrics.untimedLyrics!!.text)
        val provider = LrclibPlainLyricsProvider.mapNativePlainLyricsResultJson(
            JSONObject().put("ok",true).put("untimed_text",text).toString(), song.title, song.artist, song.album, song.durationMs)
        assertEquals(text, LrclibPlainLyricsProvider.toProviderResult(provider)!!.untimedLyrics!!.text)
    }

    @Test fun applyAndReloadUseTxtAndDoNotAttachOldWords() {
        assertTrue(WordByWordLyricsStorageOps.saveWordByWordLyrics(context, song.title, song.artist, song.durationMs,
            listOf(WordByWordLine(1000, 2000, "Old", listOf(WordByWordSegment("Old",1000,2000))))))
        assertTrue(LyricsStorage.savePlainLyrics(context, song.title, song.artist, song.durationMs,
            "[00:01.00]Old", plainSource = LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK))
        val service = ManualLyricsSearch(context)
        assertFalse(service.apply(song, result(), token(), null))
        assertTrue(service.apply(song, result(), token(), service.protectedRevision(song)))
        repeat(2) {
            assertEquals(result().untimedLyrics, local().untimedLyrics)
            assertTrue(local().selectedManually)
            assertTrue(local().wordByWordLines.isEmpty())
            assertFalse(LyricsStorage.hasWordByWordLyrics(context, song.title, song.artist, song.durationMs))
        }
        val info = LyricsStorage.getLocalPlainLyricsInfo(context, song.title, song.artist, song.durationMs)!!
        assertTrue(info.isUntimed)
        assertTrue(info.plainFileName.endsWith(".txt"))
        assertTrue(LyricsStorage.listAllLyrics(context).single().isUntimed)
        assertTrue(service.apply(song, timed(), token(), null))
        assertFalse(local().isUntimed)
        assertFalse(LyricsFileStore.managedLyricsExists(context, LyricsFileNaming.managedRelativePath(info.plainFileName)))
    }

    @Test fun failedSelectionRollsBackAcrossBothFormatChanges() {
        val service = ManualLyricsSearch(context)
        for ((before, after) in listOf(timed() to result(), result() to timed())) {
            assertTrue(service.apply(song, before, token(), service.protectedRevision(song)))
            val entries = LyricsIndexStore.read(context)
            ManualLyricsSelectionStore.beforeCommitStep = { if (it == "selection") error("Injected selection failure") }
            try {
                assertTrue(runCatching { service.apply(song, after, token(), service.protectedRevision(song)) }.isFailure)
            } finally { ManualLyricsSelectionStore.beforeCommitStep = null }
            assertEquals(before.isUntimed, local().isUntimed)
            assertEquals(before.storageText, local().storageText)
            assertEquals(entries, LyricsIndexStore.read(context))
        }
    }

    @Test fun txtImportEditExportAndReplacementPreserveRawText() {
        assertTrue(LyricsStorage.importPlainLyricsFromUriWithResult(context, writeImportFile("Song.txt", text),
            song.title, song.artist, song.durationMs) is LyricsStorage.ImportLyricsResult.Saved)
        var item = LyricsStorage.listAllLyrics(context).single()
        assertTrue(item.isUntimed)
        val edited = text + "\n\nLast paragraph"
        assertTrue(LyricsStorage.updatePlainLyricsItemTextWithResult(context, item, edited).saved)
        item = LyricsStorage.listAllLyrics(context).single()
        val destination = File(context.cacheDir, "export.zip")
        val exported = BatchLyricsExporter.export(context, listOf(item), LyricsStorage.getLyricsDirRawPath(context),
            android.net.Uri.fromFile(destination))
        assertEquals(1, exported.exported)
        ZipInputStream(destination.inputStream()).use {
            val entry = it.nextEntry!!
            assertTrue(entry.name.endsWith(".txt"))
            assertEquals(edited, it.readBytes().toString(Charsets.UTF_8))
        }
        val imported = BatchLyricsDocumentReader.parse("Song.txt", edited, LyricsDocumentFormat.TXT).document!!
        assertTrue(imported.isUntimed)
        assertEquals(LyricsReplacementOps.Result.SAVED, LyricsStorage.replaceSavedLyrics(context, item, imported))
        assertEquals(edited, local().untimedLyrics!!.text)
        assertEquals(LyricsStorage.DeleteAllSavedLyricsResult.DELETED, LyricsStorage.deleteAllSavedLyrics(context))
        assertTrue(LyricsStorage.listAllLyrics(context).isEmpty())
    }

    @Test fun restrictedTextEditsKeepTheRawDocumentAndExpiry() {
        val cache = SpicyLyricsCache.forContext(context)
        val key = SpicyLyricsCache.key(song.title, song.artist, song.durationMs)
        val original = cache.save(key, song.title, song.artist, song.album, song.durationMs, staticSpicyFixture())!!
        assertTrue(cache.item(key)!!.isUntimed)
        assertTrue(cache.edit(key, text, false).saved)
        val edited = cache.read(key)!!
        assertEquals(text, edited.untimedLyrics!!.text)
        assertEquals(original.access, edited.access)
        assertTrue(String(cache.snapshot(key)!!).contains("preserve-me"))
        assertFalse(edited.access!!.allowExport)
    }

    @Test fun textTypeSurvivesSeparateWordCacheWritesAndNeverFallsBackToLegacyLrc() {
        assertTrue(LyricsStorage.savePlainLyrics(context, song.title, song.artist, song.durationMs,
            text, isUntimed = true, untimedTranslation = "Translation"))
        assertTrue(LyricsStorage.saveWordByWordLyrics(context, song.title, song.artist, song.durationMs,
            listOf(WordByWordLine(1000, 2000, "Old", listOf(WordByWordSegment("Old", 1000, 2000))))))
        assertTrue(local().isUntimed)
        assertEquals("Translation", local().untimedLyrics!!.translatedText)
        assertTrue(local().wordByWordLines.isEmpty())
        val entry = LyricsIndexStore.find(context, song.title, song.artist, song.durationMs)!!
        assertTrue(LyricsFileStore.deleteManagedLyrics(context, entry.plainFile))
        File(LyricsStoragePaths.fallbackLyricsDir(context),
            LyricsFileNaming.legacyPlainFileName(song.title, song.artist, song.durationMs)).writeText("[00:01.00]Legacy")
        assertNull(LocalPlainLyricsProvider.fetch(PlainLyricsSearchRequest(context, song.title, song.artist,
            song.album, song.durationMs)).getOrThrow())
    }

    @Test fun malformedTimedContentIsNotReinterpretedAsText() {
        val invalid = LyricsProviderResult("lrclib", "LRCLIB", "not LRC")
        assertNotNull(invalid.manualUnavailableReason())
        assertFalse(invalid.isUntimed)
        assertTrue(LyricsImportParsers.parsePlain(LyricsDocumentFormat.LRC, "text") is LyricsTextParseResult.InvalidFormat)
        assertTrue(LyricsImportParsers.parseWordByWord(LyricsDocumentFormat.TXT, text) is LyricsTextParseResult.InvalidFormat)
    }
}
