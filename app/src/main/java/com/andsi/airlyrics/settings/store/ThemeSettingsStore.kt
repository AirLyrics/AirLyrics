package com.andsi.airlyrics.settings.store

import android.content.Context
import android.content.res.Configuration
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.core.prefs.prefs

/** Stores app-wide theme preferences. */
object ThemeSettingsStore {
    private const val PREF_NAME = "app_theme"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_ACCENT = "accent"

    private fun store(context: Context) = prefs(context, PREF_NAME)

    fun isDark(context: Context): Boolean {
        val preferences = store(context)
        return if (preferences.contains(KEY_DARK_MODE)) {
            preferences.getBoolean(KEY_DARK_MODE, false)
        } else {
            isSystemDark(context)
        }
    }

    private fun isSystemDark(context: Context): Boolean {
        val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun setDark(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_DARK_MODE, enabled)
    }

    fun getAccent(context: Context): ThemeAccent {
        return ThemeAccent.fromPreferenceValue(store(context).getString(KEY_ACCENT))
    }

    fun setAccent(context: Context, accent: ThemeAccent) {
        store(context).setString(KEY_ACCENT, accent.preferenceValue)
    }
}
