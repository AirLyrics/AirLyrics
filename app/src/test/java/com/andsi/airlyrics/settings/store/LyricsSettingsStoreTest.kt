package com.andsi.airlyrics.settings.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSearchSource
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
        context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun getSettings_returnsDefaultsWhenNothingWasSaved() {
        val settings = LyricsSettingsStore.getSettings(context)

        assertEquals(LyricsSearchSource.NETEASE, settings.source)
        assertTrue(settings.autoSearchOnline)
        assertTrue(settings.autoSaveLocal)
        assertEquals(LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION, settings.contentDisplayMode)
        assertEquals(LyricsLineDisplayMode.CURRENT_ONLY, settings.lineDisplayMode)
        assertEquals(LyricsSwitchAnimationMode.SLIDE_UP, settings.switchAnimationMode)
        assertFalse(settings.karaokeLyricsEnabled)
    }

    @Test
    fun setLyricsSearchSource_localOnlyDisablesOnlineSearch() {
        LyricsSettingsStore.setLyricsSearchSource(context, LyricsSearchSource.LOCAL_ONLY)

        assertEquals(LyricsSearchSource.LOCAL_ONLY, LyricsSettingsStore.getLyricsSearchSource(context))
        assertFalse(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    @Test
    fun setAutoSearchOnlineEnabled_reselectsDefaultProviderWhenReEnabledFromLocalOnly() {
        LyricsSettingsStore.setLyricsSearchSource(context, LyricsSearchSource.LOCAL_ONLY)
        LyricsSettingsStore.setAutoSearchOnlineEnabled(context, true)

        assertEquals(LyricsSearchSource.NETEASE, LyricsSettingsStore.getLyricsSearchSource(context))
        assertTrue(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    @Test
    fun displayModeSettingsRoundTrip() {
        LyricsSettingsStore.setContentDisplayMode(context, LyricsContentDisplayMode.TRANSLATION_ONLY)
        LyricsSettingsStore.setLineDisplayMode(context, LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT)
        LyricsSettingsStore.setSwitchAnimationMode(context, LyricsSwitchAnimationMode.FADE)
        LyricsSettingsStore.setKaraokeLyricsEnabled(context, true)
        LyricsSettingsStore.setAutoSaveLocalEnabled(context, false)

        val settings = LyricsSettingsStore.getSettings(context)

        assertEquals(LyricsContentDisplayMode.TRANSLATION_ONLY, settings.contentDisplayMode)
        assertEquals(LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT, settings.lineDisplayMode)
        assertEquals(LyricsSwitchAnimationMode.FADE, settings.switchAnimationMode)
        assertTrue(settings.karaokeLyricsEnabled)
        assertFalse(settings.autoSaveLocal)
    }

    @Test
    fun legacyStringSetterHandlesUnknownSourceAsLocalOnly() {
        LyricsSettingsStore.setLyricsSource(context, "unknown-provider")

        assertEquals(LyricsSearchSource.LOCAL_ONLY, LyricsSettingsStore.getLyricsSearchSource(context))
        assertFalse(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }

    @Test
    fun getLyricsSearchSource_handlesPersistedUnknownSourceAsLocalOnly() {
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE).edit()
            .putString("lyrics_source", "unknown-provider")
            .commit()

        assertEquals(LyricsSearchSource.LOCAL_ONLY, LyricsSettingsStore.getLyricsSearchSource(context))
        assertFalse(LyricsSettingsStore.isAutoSearchOnlineEnabled(context))
    }
}
