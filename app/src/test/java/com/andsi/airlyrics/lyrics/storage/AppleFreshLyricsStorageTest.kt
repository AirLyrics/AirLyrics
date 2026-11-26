package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.lyrics.providers.ApplePlainLyricsProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppleFreshLyricsStorageTest : LyricsStorageTestBase() {
    @After
    fun resetDictionaries() = ChineseText.resetForTest()

    @Test
    fun nativePayloadRoundTripsWithTheSelectedIndexEntry() {
        ChineseText.resetForTest()
        ChineseText.loadFromDirectory(File("src/main/assets/opencc"))
        val song = SongIdentity("彩虹", "周杰伦", durationMs = 263_787)
        val json = requireNotNull(javaClass.getResource("/apple/rainbow-native.json")).readText()
        val result = requireNotNull(
            ApplePlainLyricsProvider.parse(json, song.title, song.artist, song.album, song.durationMs),
        )

        ManualLyricsSelectionStore.commit(
            context, song, LyricsSearchMode.Original, result, manual = true, savePlain = true,
        )

        val index = requireNotNull(LyricsIndexStore.find(context, song.title, song.artist, song.durationMs))
        val selection = requireNotNull(ManualLyricsSelectionStore.read(context, song))
        assertEquals(index.key, selection.info.indexKey)
        assertEquals(result.plainProviderId, index.plainProvider)
        assertEquals(result.plainProviderId, index.wordByWordProvider)
        assertTrue(selection.result.selectedManually)
        assertEquals(result.plainLrc, selection.result.plainLrc)
        assertEquals(result.translatedLrc, selection.result.translatedLrc)
        assertEquals(result.wordByWordLines, selection.result.wordByWordLines)
        assertEquals(result.storageText, LyricsFileStore.readManagedLyrics(context, index.plainFile))
        assertEquals(
            result.plainLrc,
            LyricsStorage.readPlainLyrics(context, song.title, song.artist, song.durationMs),
        )
        assertEquals(
            result.wordByWordLines,
            LyricsStorage.readWordByWordLyrics(context, song.title, song.artist, song.durationMs),
        )
    }
}
