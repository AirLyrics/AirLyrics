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
    fun storageCommitRestoresAfterPartialWordIndexFailure() {
        val song = com.andsi.airlyrics.core.model.SongIdentity("Storage failure", "Artist", durationMs = 10000)
        val result = com.andsi.airlyrics.lyrics.LyricsProviderResult("apple", "Apple", "[00:22.18]old", wordByWordLines = lines)
        fun commit(value: com.andsi.airlyrics.lyrics.LyricsProviderResult) = ManualLyricsSelectionStore.commit(
            context, song, com.andsi.airlyrics.core.model.LyricsSearchMode.Original, value,
            manual = true, savePlain = false)
        commit(result)
        val before = ManualLyricsSelectionStore.read(context, song)
        val raw = (LyricsIndexStore.captureRaw(context) as LyricsIndexStore.RawSnapshot.Present).bytes.toList()
        ManualLyricsSelectionStore.beforeCommitStep = { step ->
            if (step == "wordIndex") {
                assertTrue(LyricsIndexStore.write(context, emptyList()))
                error("partial index failure")
            }
        }
        try {
            assertTrue(runCatching { commit(result.copy(plainLrc = "[00:22.18]new")) }.isFailure)
            assertEquals(before, ManualLyricsSelectionStore.read(context, song))
            assertEquals(raw, (LyricsIndexStore.captureRaw(context) as LyricsIndexStore.RawSnapshot.Present).bytes.toList())
        } finally {
            ManualLyricsSelectionStore.beforeCommitStep = null
        }
    }

    @Test
    fun manualReferenceRestoresOnlyItsOwnCompleteResult() {
        val song = com.andsi.airlyrics.core.model.SongIdentity("Apple manual", "Artist", durationMs = 10000)
        val result = com.andsi.airlyrics.lyrics.LyricsProviderResult("apple", "Apple",
            "[00:22.18]攔路雨偏似雪花", "[00:22.18]translation", lines)
        fun commit(value: com.andsi.airlyrics.lyrics.LyricsProviderResult) = ManualLyricsSelectionStore.commit(
            context, song, com.andsi.airlyrics.core.model.LyricsSearchMode.Original, value,
            manual = true, savePlain = false)
        commit(result)
        val restored = ManualLyricsSelectionStore.read(context, song)!!.result
        assertEquals(result.plainLrc, restored.plainLrc)
        assertEquals(result.translatedLrc, restored.translatedLrc)
        assertEquals(result.wordByWordLines, restored.wordByWordLines)
        val selectionFile = java.io.File(java.io.File(context.noBackupFilesDir, "lyrics-selections"),
            song.copy(durationMs = 0L).storageKey() + ".json")
        val legacy = org.json.JSONObject(selectionFile.readText()).apply { remove("words") }
        selectionFile.writeText(legacy.toString())
        assertTrue(ManualLyricsSelectionStore.read(context, song)!!.result.wordByWordLines.isEmpty())
        commit(result)
        val entry = LyricsIndexStore.find(context, song.title, song.artist, song.durationMs)!!
        val originalJson = LyricsFileStore.readManagedLyrics(context, entry.wordByWordFile)!!
        assertTrue(LyricsFileStore.writeManagedLyrics(context, entry.wordByWordFile.substringAfterLast('/'), "$originalJson "))
        assertTrue(ManualLyricsSelectionStore.read(context, song)!!.result.wordByWordLines.isEmpty())
        commit(result)
        assertTrue(LyricsIndexStore.write(context, LyricsIndexStore.read(context).map {
            it.copy(wordByWordProvider = "other")
        }))
        assertTrue(ManualLyricsSelectionStore.read(context, song)!!.result.wordByWordLines.isEmpty())
        commit(result.copy(wordByWordLines = emptyList()))
        assertTrue(ManualLyricsSelectionStore.read(context, song)!!.result.wordByWordLines.isEmpty())
    }

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
