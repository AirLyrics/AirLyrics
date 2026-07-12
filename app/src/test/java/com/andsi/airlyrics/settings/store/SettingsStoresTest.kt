package com.andsi.airlyrics.settings.store

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.i18n.LanguageSettingsStore
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
            "airlyrics_language_settings"
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }
}
