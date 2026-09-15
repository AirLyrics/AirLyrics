package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.model.SongIdentity
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsOffsetStoreTest : SettingsStoreTestBase() {
    @Test
    fun clampsMigratesNearbyDurationAndResets() {
        val identity = SongIdentity("Song", "Artist", durationMs = 180_000L)
        val nearbyDuration = identity.copy(durationMs = 184_000L)

        assertEquals(300_000L, LyricsOffsetStore.setOffsetMs(context, identity, 400_000L))
        assertEquals(300_000L, LyricsOffsetStore.getOffsetMs(context, identity))
        assertEquals(300_000L, LyricsOffsetStore.getOffsetMs(context, nearbyDuration))

        assertEquals(299_500L, LyricsOffsetStore.adjustOffsetMs(context, nearbyDuration, -500L))

        LyricsOffsetStore.resetOffset(context, identity)

        assertEquals(0L, LyricsOffsetStore.getOffsetMs(context, identity))
        assertEquals(0L, LyricsOffsetStore.getOffsetMs(context, nearbyDuration))
    }

    @Test
    fun ignoresBlankTitle() {
        val blank = SongIdentity("", "Artist", durationMs = 180_000L)

        assertEquals(0L, LyricsOffsetStore.setOffsetMs(context, blank, 1_000L))
        assertEquals(0L, LyricsOffsetStore.getOffsetMs(context, blank))
    }

    @Test
    fun migratesTurkishLegacyOffsetToCanonicalKeys() {
        val originalLocale = Locale.getDefault()
        val preferences = context.getSharedPreferences("lyrics_offset_store", Context.MODE_PRIVATE)
        val identity = SongIdentity("INDIGO", "ARTIST", durationMs = 180_900L)

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            preferences.edit()
                .putLong(TURKISH_LEGACY_EXACT_OFFSET_KEY, 1_250L)
                .commit()

            assertEquals(1_250L, LyricsOffsetStore.getOffsetMs(context, identity))
            assertEquals(1_250L, preferences.getLong(ROOT_EXACT_OFFSET_KEY, Long.MIN_VALUE))
            assertEquals(1_250L, preferences.getLong(ROOT_WEAK_OFFSET_KEY, Long.MIN_VALUE))
            assertTrue(preferences.contains(TURKISH_LEGACY_EXACT_OFFSET_KEY))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun prefersCanonicalRootOffsetOverLegacyOffset() {
        val preferences = context.getSharedPreferences("lyrics_offset_store", Context.MODE_PRIVATE)
        val identity = SongIdentity("INDIGO", "ARTIST", durationMs = 180_900L)
        preferences.edit()
            .putLong(ROOT_EXACT_OFFSET_KEY, 700L)
            .putLong(TURKISH_LEGACY_EXACT_OFFSET_KEY, 1_250L)
            .commit()

        assertEquals(700L, LyricsOffsetStore.getOffsetMs(context, identity))
        assertEquals(1_250L, preferences.getLong(TURKISH_LEGACY_EXACT_OFFSET_KEY, Long.MIN_VALUE))
    }

    @Test
    fun checksCanonicalWeakFallbackBeforeLegacyOffset() {
        val preferences = context.getSharedPreferences("lyrics_offset_store", Context.MODE_PRIVATE)
        val identity = SongIdentity("INDIGO", "ARTIST", durationMs = 180_900L)
        preferences.edit()
            .putLong(ROOT_WEAK_OFFSET_KEY, 600L)
            .putLong(TURKISH_LEGACY_EXACT_OFFSET_KEY, 1_250L)
            .commit()

        assertEquals(600L, LyricsOffsetStore.getOffsetMs(context, identity))
        assertEquals(600L, preferences.getLong(ROOT_EXACT_OFFSET_KEY, Long.MIN_VALUE))
    }

    @Test
    fun withoutLegacyDataKeepsCanonicalWeakFallback() {
        val storedIdentity = SongIdentity("INDIGO", "ARTIST", durationMs = 120_000L)
        val requestedIdentity = storedIdentity.copy(durationMs = 180_900L)

        LyricsOffsetStore.setOffsetMs(context, storedIdentity, 850L)

        assertEquals(850L, LyricsOffsetStore.getOffsetMs(context, requestedIdentity))
    }

    private companion object {
        const val ROOT_EXACT_OFFSET_KEY =
            "song_offset_ms_a11e82c8fbfbe4a976ab8e497d003f8728ab3e98"
        const val ROOT_WEAK_OFFSET_KEY =
            "song_offset_ms_a77e270ec72363280a1960d0328479b0916cf5a7"
        const val TURKISH_LEGACY_EXACT_OFFSET_KEY =
            "song_offset_ms_2c38d002d54afdbf7ca9281fe90d7ae261cff2be"
    }
}
