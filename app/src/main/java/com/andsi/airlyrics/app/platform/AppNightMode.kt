package com.andsi.airlyrics.app.platform

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.andsi.airlyrics.settings.store.ThemeSettingsStore

/** Keeps the app's stored appearance choice and AppCompat's DayNight configuration aligned. */
internal object AppNightMode {
    fun applyStoredMode(context: Context) {
        applyMode(ThemeSettingsStore.getDarkModeOverride(context))
    }

    fun setDark(context: Context, enabled: Boolean) {
        ThemeSettingsStore.setDark(context, enabled)
        applyMode(enabled)
    }

    private fun applyMode(darkModeOverride: Boolean?) {
        val mode = when (darkModeOverride) {
            true -> AppCompatDelegate.MODE_NIGHT_YES
            false -> AppCompatDelegate.MODE_NIGHT_NO
            null -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
