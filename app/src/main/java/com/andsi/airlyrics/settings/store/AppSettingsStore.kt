package com.andsi.airlyrics.settings.store

import android.content.Context
import androidx.core.content.edit

/** Stores app-wide preferences that do not belong to a single feature area. */
object AppSettingsStore {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_TOASTER_MUTED = "toaster_muted"

    fun isToasterMuted(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOASTER_MUTED, false)
    }

    fun setToasterMuted(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_TOASTER_MUTED, muted)
        }
    }
}
