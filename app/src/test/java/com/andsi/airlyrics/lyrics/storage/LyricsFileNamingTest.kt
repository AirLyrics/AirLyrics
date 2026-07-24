package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.core.model.SongIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsFileNamingTest {
    @Test
    fun managedFileNames_useStableStorageKeyPrefixAndKnownExtensions() {
        val identity = SongIdentity(
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L
        )

        assertEquals(
            "2e421c87b90fd468.lrc",
            LyricsFileNaming.managedPlainFileName(identity)
        )
        assertEquals(
            "2e421c87b90fd468.karaoke.json",
            LyricsFileNaming.managedKaraokeFileName(identity)
        )
        assertEquals(
            "lyrics/2e421c87b90fd468.lrc",
            LyricsFileNaming.managedRelativePath("2e421c87b90fd468.lrc")
        )
    }

    @Test
    fun legacyPlainFileName_preservesOldReadableFormat() {
        assertEquals(
            "Bad_Name_ - Unknown Artist [ba661675].lrc",
            LyricsFileNaming.legacyPlainFileName(
                title = "Bad/Name?",
                artist = "",
                duration = 180_000L
            )
        )
    }

    @Test
    fun knownFileTypeChecks_areCaseInsensitive() {
        assertTrue(LyricsFileNaming.isPlainLyricsFile("song.LRC"))
        assertTrue(LyricsFileNaming.isKaraokeLyricsFile("song.KARAOKE.JSON"))
        assertFalse(LyricsFileNaming.isPlainLyricsFile("song.txt"))
        assertFalse(LyricsFileNaming.isKaraokeLyricsFile("song.lrc"))
    }

    @Test
    fun friendlyDisplayName_removesKnownSuffixAndLegacyHash() {
        assertEquals("Song Name", LyricsFileNaming.friendlyDisplayName("Song_Name [abc123EF].lrc"))
        assertEquals("Song Name", LyricsFileNaming.friendlyDisplayName("lyrics/Song_Name.karaoke.json"))
        assertEquals("Song Name.txt", LyricsFileNaming.friendlyDisplayName("Song_Name.txt"))
    }
}
