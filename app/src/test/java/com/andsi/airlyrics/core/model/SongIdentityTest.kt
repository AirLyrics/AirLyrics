package com.andsi.airlyrics.core.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongIdentityTest {
    @Suppress("SpellCheckingInspection")
    @Test
    fun normalizeText_trimsLowercasesAndCollapsesWhitespace() {
        assertEquals(
            "hello world",
            SongIdentity.normalizeText("  HeLLo   World  ")
        )
    }

    @Suppress("SpellCheckingInspection")
    @Test
    fun storageKey_matchesPersistedGoldenValuesAfterNormalization() {
        val first = SongIdentity(
            title = "  Song   Title ",
            artist = " ArTist ",
            durationMs = 180_900L
        )
        val second = SongIdentity(
            title = "song title",
            artist = "artist",
            durationMs = 180_100L
        )
        val differentDuration = second.copy(durationMs = 181_000L)

        assertEquals(
            "f9437329871a3de588700c6ddd8e6388e14991db",
            first.storageKey()
        )
        assertEquals(
            "f9437329871a3de588700c6ddd8e6388e14991db",
            second.storageKey()
        )
        assertEquals(
            "f9437329871a3de588700c6ddd8e6388e14991db",
            SongIdentity.storageKeyForDurationSeconds("song title", "artist", 180L)
        )
        assertEquals(
            "47da3b64c239323357933145eb285ef513aa8774",
            differentDuration.storageKey()
        )
    }

    @Test
    fun storageKey_usesRootLocaleUnderUsAndTurkishDefaults() {
        val originalLocale = Locale.getDefault()
        val identity = SongIdentity(
            title = "INDIGO",
            artist = "ARTIST",
            durationMs = 180_900L
        )

        try {
            Locale.setDefault(Locale.US)
            assertEquals(
                "a11e82c8fbfbe4a976ab8e497d003f8728ab3e98",
                identity.storageKey()
            )

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                "a11e82c8fbfbe4a976ab8e497d003f8728ab3e98",
                identity.storageKey()
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun legacyStorageKeys_prioritizeCurrentLocaleAndRemoveDuplicateKeys() {
        val identity = SongIdentity(
            title = "INDIGO",
            artist = "ARTIST",
            durationMs = 180_900L
        )

        assertEquals(
            listOf(
                "2c38d002d54afdbf7ca9281fe90d7ae261cff2be",
                "a11e82c8fbfbe4a976ab8e497d003f8728ab3e98"
            ),
            identity.legacyStorageKeysAtDurationSeconds(
                durationSeconds = listOf(180L),
                currentLocale = Locale.forLanguageTag("tr-TR")
            )
        )
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
