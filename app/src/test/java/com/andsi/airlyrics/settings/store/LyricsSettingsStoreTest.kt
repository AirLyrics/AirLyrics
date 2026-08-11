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

        assertEquals(PlainLyricsSearchSource.NETEASE, settings.plainLyricsSearchSource)
        assertTrue(settings.autoSearchOnline)
        assertTrue(settings.autoSaveLocal)
        assertEquals(LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION, settings.contentDisplayMode)
        assertEquals(LyricsLineDisplayMode.CURRENT_ONLY, settings.lineDisplayMode)
        assertEquals(LyricsSwitchAnimationMode.FADE, settings.switchAnimationMode)
        assertFalse(settings.wordByWordLyricsEnabled)
    }

    @Test
    fun setPlainLyricsSearchSource_localOnlyMigratesToDefaultManualProviderAndDisablesAutomaticSearch() {
        LyricsSettingsStore.setPlainLyricsSearchSource(context, PlainLyricsSearchSource.LOCAL_ONLY)

        assertEquals(
            PlainLyricsSearchSource.NETEASE,
            LyricsSettingsStore.getPlainLyricsSearchSource(context)
        )
        assertFalse(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    @Test
    fun setAutoSearchOnlineEnabled_falsePreservesProviderForManualSearch() {
        LyricsSettingsStore.setPlainLyricsSearchSource(context, PlainLyricsSearchSource.MUSIXMATCH)
        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, false)

        val settings = LyricsSettingsStore.getSettings(context)
        assertEquals(PlainLyricsSearchSource.MUSIXMATCH, settings.plainLyricsSearchSource)
        assertFalse(settings.autoSearchOnline)
    }

    @Test
    fun selectingProviderDoesNotChangeAutomaticSearchPreference() {
        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, false)
        LyricsSettingsStore.setPlainLyricsSearchSource(context, PlainLyricsSearchSource.MUSIXMATCH)

        assertEquals(PlainLyricsSearchSource.MUSIXMATCH, LyricsSettingsStore.getPlainLyricsSearchSource(context))
        assertFalse(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    @Test
    fun enablingAutomaticSearchMigratesPersistedLegacyLocalOnlySource() {
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE).edit()
            .putString("lyrics_source", PlainLyricsSearchSource.LOCAL_ONLY.key)
            .putBoolean("auto_search_online", false)
            .commit()

        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, true)

        assertEquals(PlainLyricsSearchSource.NETEASE, LyricsSettingsStore.getPlainLyricsSearchSource(context))
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

    @Test
    fun legacyStringSetterHandlesUnknownSourceAsAutomaticOffWithDefaultManualProvider() {
        LyricsSettingsStore.setPlainLyricsSource(context, "unknown-provider")

        assertEquals(
            PlainLyricsSearchSource.NETEASE,
            LyricsSettingsStore.getPlainLyricsSearchSource(context)
        )
        assertFalse(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    @Test
    fun getPlainLyricsSearchSource_handlesPersistedUnknownSourceAsAutomaticOffWithDefaultManualProvider() {
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE).edit()
            .putString("lyrics_source", "unknown-provider")
            .commit()

        assertEquals(
            PlainLyricsSearchSource.NETEASE,
            LyricsSettingsStore.getPlainLyricsSearchSource(context)
        )
        assertFalse(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }
}
