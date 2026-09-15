package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SimpleSettingsStoresTest : SettingsStoreTestBase() {
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
        assertNull(ThemeSettingsStore.getDarkModeOverride(context))

        ThemeSettingsStore.setDark(context, true)
        assertTrue(ThemeSettingsStore.isDark(context))
        assertEquals(true, ThemeSettingsStore.getDarkModeOverride(context))

        ThemeSettingsStore.setDark(context, false)
        assertFalse(ThemeSettingsStore.isDark(context))
        assertEquals(false, ThemeSettingsStore.getDarkModeOverride(context))
    }

    @Test
    fun themeSettingsStore_defaultsAndRoundTripsAccentChoice() {
        assertEquals(ThemeAccent.DEFAULT, ThemeSettingsStore.getAccent(context))

        ThemeSettingsStore.setAccent(context, ThemeAccent.BLUE)

        assertEquals(ThemeAccent.BLUE, ThemeSettingsStore.getAccent(context))
        assertEquals(
            ThemeAccent.BLUE.preferenceValue,
            context.getSharedPreferences("app_theme", Context.MODE_PRIVATE)
                .getString("accent", null)
        )
    }

    @Test
    fun themeSettingsStore_unknownAccentFallsBackToDefault() {
        context.getSharedPreferences("app_theme", Context.MODE_PRIVATE)
            .edit()
            .putString("accent", "future-accent")
            .commit()

        assertEquals(ThemeAccent.DEFAULT, ThemeSettingsStore.getAccent(context))
    }

    @Test
    fun appSettingsStore_statusPopupsMuteDefaultsOffAndRoundTrips() {
        assertFalse(AppSettingsStore.areStatusPopupsMuted(context))

        AppSettingsStore.setStatusPopupsMuted(context, true)
        assertTrue(AppSettingsStore.areStatusPopupsMuted(context))

        AppSettingsStore.setStatusPopupsMuted(context, false)
        assertFalse(AppSettingsStore.areStatusPopupsMuted(context))
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

}
