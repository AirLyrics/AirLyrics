package com.andsi.airlyrics.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongIdentityTest {
    @Test
    fun normalizeText_trimsLowercasesAndCollapsesWhitespace() {
        assertEquals(
            "hello world",
            SongIdentity.normalizeText("  HeLLo   World  ")
        )
    }

    @Test
    fun storageKey_usesNormalizedTitleArtistAndDurationSeconds() {
        val first = SongIdentity(
            title = "  Song   Title ",
            artist = " ARTIST ",
            durationMs = 180_900L
        )
        val second = SongIdentity(
            title = "song title",
            artist = "artist",
            durationMs = 180_100L
        )
        val differentDuration = second.copy(durationMs = 181_000L)

        assertEquals(first.storageKey(), second.storageKey())
        assertEquals(
            first.storageKey(),
            SongIdentity.storageKeyForDurationSeconds("song title", "artist", 180L)
        )
        assertFalse(first.storageKey() == differentDuration.storageKey())
    }

    @Test
    fun isStrongSameSong_allowsSmallDurationDrift() {
        val base = SongIdentity("Song", "Artist", durationMs = 180_000L)

        assertTrue(base.isStrongSameSong(base.copy(durationMs = 185_000L)))
        assertFalse(base.isStrongSameSong(base.copy(durationMs = 185_001L)))
    }

    @Test
    fun isWeakSameSong_ignoresCaseWhitespaceAndDuration() {
        val base = SongIdentity("  Song   Title ", " ARTIST ", durationMs = 180_000L)
        val sameByText = SongIdentity("song title", "artist", durationMs = 240_000L)
        val differentArtist = SongIdentity("song title", "other artist", durationMs = 180_000L)

        assertTrue(base.isWeakSameSong(sameByText))
        assertTrue(base.isSameSong(sameByText))
        assertFalse(base.isWeakSameSong(differentArtist))
    }

    @Test
    fun isStrongSameSong_treatsMissingDurationAsMatchWhenTextMatches() {
        val missingDuration = SongIdentity("Song", "Artist", durationMs = 0L)
        val knownDuration = SongIdentity(" song ", " artist ", durationMs = 180_000L)

        assertTrue(missingDuration.isStrongSameSong(knownDuration))
    }
}
