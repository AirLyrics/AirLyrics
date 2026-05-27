package com.andsi.airlyrics.core.settings

import android.content.Context
import com.andsi.airlyrics.core.settings.model.ThemeSettings

/** Stores app-wide theme preferences. */
object ThemeSettingsStore {
    private const val PREF_NAME = "app_theme"
    private const val KEY_DARK_MODE = "dark_mode"

    fun getSettings(context: Context): ThemeSettings {
        return ThemeSettings(darkMode = isDark(context))
    }

    fun isDark(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_MODE, false)
    }

    fun setDark(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, enabled)
            .apply()
    }
}
