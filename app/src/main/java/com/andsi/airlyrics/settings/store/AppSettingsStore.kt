package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.prefs.prefs

/** Stores app-wide preferences that do not belong to a single feature area. */
object AppSettingsStore {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_TOASTER_MUTED = "toaster_muted"

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun isToasterMuted(context: Context): Boolean {
        return store(context).getBoolean(KEY_TOASTER_MUTED, false)
    }

    fun setToasterMuted(context: Context, muted: Boolean) {
        store(context).setBoolean(KEY_TOASTER_MUTED, muted)
    }
}
