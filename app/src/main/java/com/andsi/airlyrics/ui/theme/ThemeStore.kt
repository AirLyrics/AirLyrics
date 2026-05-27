package com.andsi.airlyrics.ui.theme

import android.content.Context

internal object ThemeStore {
    private const val PREF_NAME = "app_theme"
    private const val KEY_DARK_MODE = "dark_mode"

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
