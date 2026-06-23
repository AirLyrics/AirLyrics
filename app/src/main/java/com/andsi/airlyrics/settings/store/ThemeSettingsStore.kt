package com.andsi.airlyrics.settings.store

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import com.andsi.airlyrics.settings.model.ThemeSettings

/** Stores app-wide theme preferences. */
object ThemeSettingsStore {
    private const val PREF_NAME = "app_theme"
    private const val KEY_DARK_MODE = "dark_mode"

    fun getSettings(context: Context): ThemeSettings {
        return ThemeSettings(darkMode = isDark(context))
    }

    fun isDark(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_DARK_MODE, enabled)
        }
    }
}
