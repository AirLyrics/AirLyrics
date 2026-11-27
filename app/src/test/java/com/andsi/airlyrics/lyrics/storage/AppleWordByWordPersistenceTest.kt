package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsResultCommitter
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.junit.Assert.assertEquals
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
        val song = SongIdentity("Storage failure", "Artist", durationMs = 10000)
        val result = LyricsProviderResult("apple", "Apple", "[00:22.18]old", wordByWordLines = lines)
        fun commit(value: LyricsProviderResult) = ManualLyricsSelectionStore.commit(
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
        val song = SongIdentity("Apple manual", "Artist", durationMs = 10000)
        val result = LyricsProviderResult("apple", "Apple",
            "[00:22.18]攔路雨偏似雪花", "[00:22.18]translation", lines)
        fun commit(value: LyricsProviderResult) = ManualLyricsSelectionStore.commit(
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
        val song = SongIdentity("富士山下", "陈奕迅", durationMs = 259_133L)
        val result = LyricsProviderResult("apple", "Apple", "[00:22.186]攔路雨偏似雪花", wordByWordLines = lines)
        assertTrue(
            LyricsResultCommitter(context).applySelection(
                song, result, LyricsLookupCancellationToken(song.storageKey(), 1L), approvedRevision = null
            )
        )

        assertEquals(lines, LyricsStorage.readWordByWordLyrics(context, song.title, song.artist, song.durationMs))
        assertTrue(LyricsStorage.hasWordByWordLyrics(context, song.title, song.artist, song.durationMs))
    }
}
