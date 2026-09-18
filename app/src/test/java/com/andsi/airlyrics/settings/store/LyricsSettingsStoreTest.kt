package com.andsi.airlyrics.settings.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsSettingsStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun getSettings_returnsDefaultsWhenNothingWasSaved() {
        val settings = LyricsSettingsStore.getSettings(context)

        assertEquals(listOf(PlainLyricsSearchSource.NETEASE), settings.plainLyricsSearchSources)
        assertTrue(settings.autoSearchOnline)
        assertTrue(settings.autoSaveLocal)
        assertEquals(LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION, settings.contentDisplayMode)
        assertEquals(LyricsLineDisplayMode.CURRENT_ONLY, settings.lineDisplayMode)
        assertEquals(LyricsSwitchAnimationMode.FADE, settings.switchAnimationMode)
        assertFalse(settings.wordByWordLyricsEnabled)
    }

    @Test
    fun setPlainLyricsSearchSources_roundTripsInExactOrderAndMirrorsFirstSource() {
        val sources = listOf(
            PlainLyricsSearchSource.LRCLIB,
            PlainLyricsSearchSource.NETEASE,
            PlainLyricsSearchSource.MUSIXMATCH
        )

        LyricsSettingsStore.setPlainLyricsSearchSources(context, sources)

        assertEquals(sources, LyricsSettingsStore.getPlainLyricsSearchSources(context))
        val preferences = context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)
        assertEquals("lrclib,netease,musixmatch", preferences.getString("lyrics_source_order", null))
        assertEquals("lrclib", preferences.getString("lyrics_source", null))
    }

    @Test
    fun setPlainLyricsSearchSources_filtersCompatibilityValuesAndDuplicates() {
        LyricsSettingsStore.setPlainLyricsSearchSources(
            context,
            listOf(
                PlainLyricsSearchSource.LOCAL_ONLY,
                PlainLyricsSearchSource.LRCLIB,
                PlainLyricsSearchSource.NETEASE,
                PlainLyricsSearchSource.LRCLIB
            )
        )

        assertEquals(
            listOf(PlainLyricsSearchSource.LRCLIB, PlainLyricsSearchSource.NETEASE),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
    }

    @Test
    fun setPlainLyricsSearchSources_roundTripsExplicitEmptySelection() {
        LyricsSettingsStore.setPlainLyricsSearchSources(context, emptyList())

        assertEquals(
            emptyList<PlainLyricsSearchSource>(),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
        val preferences = context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)
        assertEquals("", preferences.getString("lyrics_source_order", null))
        assertEquals(
            PlainLyricsSearchSource.LOCAL_ONLY.key,
            preferences.getString("lyrics_source", null)
        )
    }

    @Test
    fun setPlainLyricsSearchSources_normalizesLegacyLocalOnlyToExplicitEmptySelection() {
        LyricsSettingsStore.setPlainLyricsSearchSources(
            context,
            listOf(PlainLyricsSearchSource.LOCAL_ONLY)
        )

        assertEquals(
            emptyList<PlainLyricsSearchSource>(),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
        val preferences = context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)
        assertEquals("", preferences.getString("lyrics_source_order", null))
        assertEquals(
            PlainLyricsSearchSource.LOCAL_ONLY.key,
            preferences.getString("lyrics_source", null)
        )
    }

    @Test
    fun getPlainLyricsSearchSources_readsEveryLegacyOnlineSourceAsSingleton() {
        val preferences = context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)

        PlainLyricsSearchSource.onlineSources.forEach { source ->
            preferences.edit().clear().putString("lyrics_source", source.key).commit()

            assertEquals(
                listOf(source),
                LyricsSettingsStore.getPlainLyricsSearchSources(context)
            )
        }
    }

    @Test
    fun getSettings_readsLegacyLocalOnlyAsDefaultSourceWithAutomaticSearchOff() {
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE).edit()
            .putString("lyrics_source", PlainLyricsSearchSource.LOCAL_ONLY.key)
            .putBoolean("auto_search_online", true)
            .commit()

        val settings = LyricsSettingsStore.getSettings(context)

        assertEquals(listOf(PlainLyricsSearchSource.NETEASE), settings.plainLyricsSearchSources)
        assertFalse(settings.autoSearchOnline)
    }

    @Test
    fun getSettings_readsUnknownLegacySourceAsDefaultWithAutomaticSearchOff() {
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE).edit()
            .putString("lyrics_source", "unknown-provider")
            .putBoolean("auto_search_online", true)
            .commit()

        val settings = LyricsSettingsStore.getSettings(context)

        assertEquals(listOf(PlainLyricsSearchSource.NETEASE), settings.plainLyricsSearchSources)
        assertFalse(settings.autoSearchOnline)
    }

    @Test
    fun getPlainLyricsSearchSources_normalizesPersistedOrderAndPrefersItOverLegacySource() {
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE).edit()
            .putString(
                "lyrics_source_order",
                "lrclib,unknown-provider,lrclib,local_only,,musixmatch"
            )
            .putString("lyrics_source", PlainLyricsSearchSource.LOCAL_ONLY.key)
            .commit()

        assertEquals(
            listOf(PlainLyricsSearchSource.LRCLIB, PlainLyricsSearchSource.MUSIXMATCH),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
        assertTrue(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    @Test
    fun getPlainLyricsSearchSources_invalidPersistedOrderFallsBackToLegacySource() {
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE).edit()
            .putString("lyrics_source_order", "unknown-provider,local_only")
            .putString("lyrics_source", PlainLyricsSearchSource.LRCLIB.key)
            .commit()

        assertEquals(
            listOf(PlainLyricsSearchSource.LRCLIB),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
    }

    @Test
    fun getPlainLyricsSearchSources_explicitEmptyOrderOverridesLegacyOnlineSource() {
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE).edit()
            .putString("lyrics_source_order", "")
            .putString("lyrics_source", PlainLyricsSearchSource.LRCLIB.key)
            .commit()

        assertEquals(
            emptyList<PlainLyricsSearchSource>(),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
    }

    @Test
    fun setAutoSearchOnlineEnabled_falsePreservesOrderForManualSearch() {
        val sources = listOf(PlainLyricsSearchSource.MUSIXMATCH, PlainLyricsSearchSource.LRCLIB)
        LyricsSettingsStore.setPlainLyricsSearchSources(context, sources)
        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, false)

        val settings = LyricsSettingsStore.getSettings(context)
        assertEquals(sources, settings.plainLyricsSearchSources)
        assertFalse(settings.autoSearchOnline)
    }

    @Test
    fun setAutoSearchOnlineEnabled_truePreservesExistingOrder() {
        val sources = listOf(PlainLyricsSearchSource.LRCLIB, PlainLyricsSearchSource.MUSIXMATCH)
        LyricsSettingsStore.setPlainLyricsSearchSources(context, sources)
        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, false)

        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, true)

        assertEquals(sources, LyricsSettingsStore.getPlainLyricsSearchSources(context))
        assertTrue(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    @Test
    fun setAutoSearchOnlineEnabled_trueDoesNotReplaceExplicitEmptySelection() {
        LyricsSettingsStore.setPlainLyricsSearchSources(context, emptyList())
        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, false)

        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, true)

        assertEquals(
            emptyList<PlainLyricsSearchSource>(),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
        assertTrue(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
        val preferences = context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)
        assertEquals("", preferences.getString("lyrics_source_order", null))
        assertEquals(
            PlainLyricsSearchSource.LOCAL_ONLY.key,
            preferences.getString("lyrics_source", null)
        )
    }

    @Test
    fun selectingSourcesDoesNotChangeAutomaticSearchPreference() {
        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, false)
        LyricsSettingsStore.setPlainLyricsSearchSources(
            context,
            listOf(PlainLyricsSearchSource.MUSIXMATCH, PlainLyricsSearchSource.LRCLIB)
        )

        assertFalse(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    @Test
    fun enablingAutomaticSearchMigratesPersistedLegacyLocalOnlySource() {
        val preferences = context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)
        preferences.edit()
            .putString("lyrics_source", PlainLyricsSearchSource.LOCAL_ONLY.key)
            .putBoolean("auto_search_online", false)
            .commit()

        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, true)

        assertEquals(
            listOf(PlainLyricsSearchSource.NETEASE),
            LyricsSettingsStore.getPlainLyricsSearchSources(context)
        )
        assertEquals("netease", preferences.getString("lyrics_source_order", null))
        assertEquals("netease", preferences.getString("lyrics_source", null))
        assertTrue(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    @Test
    fun displayModeSettingsRoundTrip() {
        LyricsSettingsStore.setContentDisplayMode(context, LyricsContentDisplayMode.TRANSLATION_ONLY)
        LyricsSettingsStore.setLineDisplayMode(context, LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT)
        LyricsSettingsStore.setSwitchAnimationMode(context, LyricsSwitchAnimationMode.FADE)
        LyricsSettingsStore.setWordByWordLyricsEnabled(context, true)
        LyricsSettingsStore.setAutoSaveLocalEnabled(context, false)

        val settings = LyricsSettingsStore.getSettings(context)

        assertEquals(LyricsContentDisplayMode.TRANSLATION_ONLY, settings.contentDisplayMode)
        assertEquals(LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT, settings.lineDisplayMode)
        assertEquals(LyricsSwitchAnimationMode.FADE, settings.switchAnimationMode)
        assertTrue(settings.wordByWordLyricsEnabled)
        assertFalse(settings.autoSaveLocal)
    }

    @Test
    fun wordByWordLyricsEnabled_usesCompatibilityPreferenceKey() {
        val preferences = context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)

        LyricsSettingsStore.setWordByWordLyricsEnabled(context, true)

        assertTrue(preferences.contains("karaoke_lyrics_enabled"))
        assertTrue(preferences.getBoolean("karaoke_lyrics_enabled", false))

        preferences.edit()
            .clear()
            .putBoolean("karaoke_lyrics_enabled", true)
            .commit()

        assertTrue(LyricsSettingsStore.isWordByWordLyricsEnabled(context))
    }

}
