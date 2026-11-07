package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.app.interaction.EditableLrcDocument
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.WordLyricsDocument
import com.andsi.airlyrics.lyrics.providers.spicyFixture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WordLyricsEditorStorageTest : LyricsStorageTestBase() {
    private val lead = WordByWordLine(1001, 3789, "Hello world", listOf(
        WordByWordSegment("Hel", 1001, 1432),
        WordByWordSegment("lo ", 1567, 2111),
        WordByWordSegment("world", 2444, 3789)
    ), translatedText = "你好", translatedSegments = listOf(WordByWordSegment("你好", 1001, 3789)))
    private val background = WordByWordLine(1201, 3009, "Backing", listOf(
        WordByWordSegment("Backing", 1201, 3009)
    ), isBackground = true)
    private val document = WordLyricsDocument(listOf(lead, background), listOf("[ar:Artist]"))

    private fun localItem(): LyricsStorage.LocalLyricsItem {
        assertTrue(WordByWordLyricsStorageOps.saveWordByWordLyrics(context, "Song", "Artist", 10000,
            document.lines, metadataLines = document.metadata))
        return LyricsStorage.listAllLyrics(context).single()
    }

    @Test fun draftRoundTripPreservesPrecisionTranslationsAndOverlappingVoices() {
        val projected = EditableLrcDocument.fromWords(document.serialize())
        assertEquals("Hello world", projected.rows.first().lyric)
        assertEquals("[00:01.001]", projected.rows.first().timestamp)
        assertEquals(document, WordLyricsDocument.parse(projected.serialize()))
        assertTrue(document.invalidLines().isEmpty())
        val changed = lead.copy(text = "Hello there", segments = lead.segments.dropLast(1) +
            lead.segments.last().copy(text = "there"))
        projected.updateWord(projected.rows.first(), changed)
        val restored = WordLyricsDocument.parse(projected.serialize())
        assertEquals(changed, restored.lines.first())
        assertEquals(background, restored.lines.last())
        assertEquals(document.metadata, restored.metadata)
    }

    @Test fun unchangedSaveLeavesTheWordFileAndIndexUntouched() {
        val item = localItem()
        val entry = LyricsIndexStore.read(context).single()
        val before = LyricsFileStore.readManagedLyrics(context, entry.wordByWordFile)
        val draft = requireNotNull(WordLyricsEditorStorage.read(context, item))
        assertTrue(WordLyricsEditorStorage.save(context, item, draft).saved)
        assertEquals(before, LyricsFileStore.readManagedLyrics(context, entry.wordByWordFile))
        assertEquals(entry, LyricsIndexStore.read(context).single())
    }

    @Test fun editedSavePreservesOtherVoicesAndTranslationsAndRebuildsVocalFallback() {
        val item = localItem()
        val draft = WordLyricsDocument.parse(requireNotNull(WordLyricsEditorStorage.read(context, item)))
        val changed = lead.copy(text = "Hello there", segments = lead.segments.dropLast(1) +
            lead.segments.last().copy(text = "there"))
        assertTrue(WordLyricsEditorStorage.save(context, item, draft.copy(lines = listOf(changed, background)).serialize()).saved)
        val reloaded = WordLyricsDocument.parse(requireNotNull(WordLyricsEditorStorage.read(context, item)))
        assertEquals(listOf(changed, background), reloaded.lines)
        assertEquals(document.metadata, reloaded.metadata)
        val plain = requireNotNull(PlainLyricsStorageOps.readPlainLyrics(context, "Song", "Artist", 10000))
        assertTrue(plain.contains("Hello there"))
        assertTrue(plain.contains("你好"))
        assertFalse(plain.contains("Backing"))
    }

    @Test fun wordTimesRejectReversedRangesAndKeepMilliseconds() {
        assertEquals(1001L, WordLyricsDocument.parseTime("00:01.001"))
        assertEquals(1200L, WordLyricsDocument.parseTime("00:01.2"))
        assertNull(WordLyricsDocument.parseTime("00:60.000"))
        assertFalse(document.copy(lines = listOf(lead.copy(segments = listOf(
            WordByWordSegment("bad", 2000, 1000))))).invalidLines().isEmpty())
    }

    @Test fun cacheEditsKeepAccessRawResponseAndExactWordData() {
        val cache = SpicyLyricsCache.forContext(context)
        val key = SpicyLyricsCache.key("Song", "Artist", 10000)
        val raw = spicyFixture()
        val initial = cache.save(key, "Song", "Artist", "", 10000, raw)!!
        val item = cache.item(key)!!
        val draft = WordLyricsDocument.parse(requireNotNull(WordLyricsEditorStorage.read(context, item)))
        val line = draft.lines.single()
        val changed = line.copy(text = "Hello there", segments = line.segments.dropLast(1) +
            line.segments.last().copy(text = "there"))
        assertTrue(WordLyricsEditorStorage.save(context, item, draft.copy(lines = listOf(changed)).serialize()).saved)
        val reloaded = cache.read(key)!!
        assertEquals(initial.access, reloaded.access)
        assertEquals(listOf(changed), reloaded.wordByWordLines)
        assertTrue(String(cache.snapshot(key)!!).contains("preserve-me"))
        assertFalse(reloaded.access!!.allowExport)
    }

    @Test fun backgroundOnlyEditsDoNotRestoreTheOldLead() {
        val cache = SpicyLyricsCache.forContext(context)
        val key = SpicyLyricsCache.key("Song", "Artist", 10000)
        val original = cache.save(key, "Song", "Artist", "", 10000, spicyFixture())!!
        val edited = WordLyricsDocument(original.wordByWordLines.map { it.copy(isBackground = true) })
        assertTrue(cache.editWords(key, edited).saved)
        val restored = cache.read(key)!!
        assertEquals(edited.lines, restored.wordByWordLines)
        assertEquals("", restored.plainLrc)
    }

    @Test fun invalidStructuredRowsCannotBeSilentlyDroppedDuringSave() {
        val item = localItem()
        val before = WordLyricsEditorStorage.read(context, item)
        val broken = document.copy(lines = listOf(lead, background.copy(endMs = 1))).serialize()
        assertThrows(IllegalArgumentException::class.java) { WordLyricsEditorStorage.save(context, item, broken) }
        assertEquals(before, WordLyricsEditorStorage.read(context, item))
    }

    @Test fun deletingAllRowsKeepsMetadataForTheNextInsertion() {
        val empty = document.copy(lines = emptyList())
        assertEquals(empty, WordLyricsDocument.parse(empty.serialize()))
    }

    @Test fun legacyLrcDraftsStillRestore() {
        val restored = WordLyricsDocument.parse("[ar:Artist]\n[00:01.00]<00:01.00>Hello <00:02.00>world<00:03.00>")
        assertEquals("Hello world", restored.lines.single().text)
        assertEquals(listOf("[ar:Artist]"), restored.metadata)
    }
}
