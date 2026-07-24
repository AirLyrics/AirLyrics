package com.andsi.airlyrics.settings.store

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoresTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPrefs()
    }

    @After
    fun tearDown() {
        clearPrefs()
        LanguageSettingsStore.setMode(context, LanguageSettingsStore.MODE_SYSTEM)
    }

    @Test
    fun floatingStyleStore_returnsDefaultBubbleStyle() {
        val style = FloatingLyricsStyleStore.getStyle(context)

        assertEquals(FloatingLyricsStyleStore.DEFAULT_PRESET, style.presetName)
        assertEquals(28f, style.textSizeSp)
        assertEquals(Color.WHITE, style.textColor)
        assertEquals(Color.rgb(120, 220, 255), style.karaokeHighlightColor)
        assertEquals(Color.BLACK, style.shadowColor)
        assertEquals(8f, style.shadowRadius)
        assertTrue(style.backgroundEnabled)
        assertEquals(Color.rgb(10, 14, 24), style.backgroundColor)
        assertEquals(170, style.backgroundAlpha)
        assertEquals(20, style.cornerRadiusDp)
        assertEquals(18, style.paddingHorizontalDp)
        assertEquals(10, style.paddingVerticalDp)
        assertEquals(85, style.maxWidthPercent)
        assertEquals(Gravity.CENTER, style.gravity)
        assertEquals(100 to 300, FloatingLyricsStyleStore.getPosition(context))
        assertTrue(FloatingLyricsStyleStore.isPreviewExpanded(context))
        assertFalse(FloatingLyricsStyleStore.isAutoHideWhenPaused(context))
    }

    @Test
    fun floatingStyleStore_appliesPresetAndClampsEditableValues() {
        FloatingLyricsStyleStore.applyPreset(context, FloatingLyricsStyleStore.PRESET_SUBTITLE)
        FloatingLyricsStyleStore.setTextSize(context, 100f)
        FloatingLyricsStyleStore.setShadowRadius(context, -4f)
        FloatingLyricsStyleStore.setPaddingHorizontal(context, 99)
        FloatingLyricsStyleStore.setPaddingVertical(context, -10)
        FloatingLyricsStyleStore.setMaxWidthPercent(context, 20)
        FloatingLyricsStyleStore.setBackgroundColor(context, Color.argb(12, 1, 2, 3))
        FloatingLyricsStyleStore.savePosition(context, 321, 654)
        FloatingLyricsStyleStore.setPreviewExpanded(context, false)

        val style = FloatingLyricsStyleStore.getStyle(context)

        assertEquals(FloatingLyricsStyleStore.PRESET_SUBTITLE, style.presetName)
        assertEquals(56f, style.textSizeSp)
        assertEquals(0f, style.shadowRadius)
        assertEquals(36, style.paddingHorizontalDp)
        assertEquals(0, style.paddingVerticalDp)
        assertEquals(45, style.maxWidthPercent)
        assertTrue(style.backgroundEnabled)
        assertEquals(Color.rgb(1, 2, 3), style.backgroundColor)
        assertEquals(12, style.backgroundAlpha)
        assertEquals(321 to 654, FloatingLyricsStyleStore.getPosition(context))
        assertFalse(FloatingLyricsStyleStore.isPreviewExpanded(context))
    }

    @Test
    fun floatingStyleStore_clickThroughFallsBackToLockedUntilExplicitlySet() {
        assertFalse(FloatingLyricsStyleStore.isLocked(context))
        assertFalse(FloatingLyricsStyleStore.isClickThrough(context))
        assertTrue(FloatingLyricsStyleStore.isClickThroughFollowingLocked(context))

        FloatingLyricsStyleStore.setLocked(context, true)

        assertTrue(FloatingLyricsStyleStore.isClickThrough(context))
        assertTrue(FloatingLyricsStyleStore.isClickThroughFollowingLocked(context))

        FloatingLyricsStyleStore.setClickThrough(context, false)

        assertFalse(FloatingLyricsStyleStore.isClickThrough(context))
        assertFalse(FloatingLyricsStyleStore.isClickThroughFollowingLocked(context))
    }

    @Test
    fun floatingStyleStore_autoHideWhenPausedDefaultsOffAndRoundTrips() {
        assertFalse(FloatingLyricsStyleStore.isAutoHideWhenPaused(context))

        FloatingLyricsStyleStore.setAutoHideWhenPaused(context, true)
        assertTrue(FloatingLyricsStyleStore.isAutoHideWhenPaused(context))

        FloatingLyricsStyleStore.setAutoHideWhenPaused(context, false)
        assertFalse(FloatingLyricsStyleStore.isAutoHideWhenPaused(context))
    }

    @Test
    fun lyricsOffsetStore_clampsMigratesNearbyDurationAndResets() {
        val identity = SongIdentity("Song", "Artist", durationMs = 180_000L)
        val nearbyDuration = identity.copy(durationMs = 184_000L)

        assertEquals(30_000L, LyricsOffsetStore.setOffsetMs(context, identity, 40_000L))
        assertEquals(30_000L, LyricsOffsetStore.getOffsetMs(context, identity))
        assertEquals(30_000L, LyricsOffsetStore.getOffsetMs(context, nearbyDuration))

        assertEquals(29_500L, LyricsOffsetStore.adjustOffsetMs(context, nearbyDuration, -500L))

        LyricsOffsetStore.resetOffset(context, identity)

        assertEquals(0L, LyricsOffsetStore.getOffsetMs(context, identity))
        assertEquals(0L, LyricsOffsetStore.getOffsetMs(context, nearbyDuration))
    }

    @Test
    fun lyricsOffsetStore_ignoresBlankTitle() {
        val blank = SongIdentity("", "Artist", durationMs = 180_000L)

        assertEquals(0L, LyricsOffsetStore.setOffsetMs(context, blank, 1_000L))
        assertEquals(0L, LyricsOffsetStore.getOffsetMs(context, blank))
    }

    @Test
    fun lyricsOffsetStore_migratesTurkishLegacyOffsetToCanonicalKeys() {
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
    fun lyricsOffsetStore_prefersCanonicalRootOffsetOverLegacyOffset() {
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
    fun lyricsOffsetStore_checksCanonicalWeakFallbackBeforeLegacyOffset() {
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
    fun lyricsOffsetStore_withoutLegacyDataKeepsCanonicalWeakFallback() {
        val storedIdentity = SongIdentity("INDIGO", "ARTIST", durationMs = 120_000L)
        val requestedIdentity = storedIdentity.copy(durationMs = 180_900L)

        LyricsOffsetStore.setOffsetMs(context, storedIdentity, 850L)

        assertEquals(850L, LyricsOffsetStore.getOffsetMs(context, requestedIdentity))
    }

    @Test
    fun quickFloatingStore_usesLegacyVisibleUntilDesiredVisibleIsSaved() {
        context.getSharedPreferences("floating_quick_control", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("visible", true)
            .commit()

        assertTrue(QuickFloatingStore.isDesiredVisible(context))

        QuickFloatingStore.setDesiredVisible(context, false)

        assertFalse(QuickFloatingStore.isDesiredVisible(context))
    }

    @Test
    fun themeSettingsStore_roundTripsExplicitThemeChoice() {
        ThemeSettingsStore.setDark(context, true)
        assertTrue(ThemeSettingsStore.isDark(context))

        ThemeSettingsStore.setDark(context, false)
        assertFalse(ThemeSettingsStore.isDark(context))
    }

    @Test
    fun appSettingsStore_toasterMuteDefaultsOffAndRoundTrips() {
        assertFalse(AppSettingsStore.isToasterMuted(context))

        AppSettingsStore.setToasterMuted(context, true)
        assertTrue(AppSettingsStore.isToasterMuted(context))

        AppSettingsStore.setToasterMuted(context, false)
        assertFalse(AppSettingsStore.isToasterMuted(context))
    }

    @Test
    fun languageSettingsStore_normalizesUnknownModesAndSavesKnownModes() {
        context.getSharedPreferences("airlyrics_language_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("language_mode", "unknown")
            .commit()

        assertEquals(LanguageSettingsStore.MODE_SYSTEM, LanguageSettingsStore.getMode(context))

        LanguageSettingsStore.setMode(context, LanguageSettingsStore.MODE_EN)
        assertEquals(LanguageSettingsStore.MODE_EN, LanguageSettingsStore.getMode(context))

        LanguageSettingsStore.setMode(context, "other")
        assertEquals(LanguageSettingsStore.MODE_SYSTEM, LanguageSettingsStore.getMode(context))
    }

    private fun clearPrefs() {
        listOf(
            "floating_lyrics_style",
            "lyrics_offset_store",
            "floating_quick_control",
            "app_theme",
            "app_settings",
            "airlyrics_language_settings"
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
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
