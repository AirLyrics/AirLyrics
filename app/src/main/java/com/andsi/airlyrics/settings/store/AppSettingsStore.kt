package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.prefs.prefs

/** Stores app-wide preferences that do not belong to a single feature area. */
object AppSettingsStore {
    private const val PREFS_NAME = "app_settings"
    // Keep the original key so upgrades preserve the user's existing preference.
    private const val KEY_STATUS_POPUPS_MUTED = "toaster_muted"

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun areStatusPopupsMuted(context: Context): Boolean {
        return store(context).getBoolean(KEY_STATUS_POPUPS_MUTED, false)
    }

    fun setStatusPopupsMuted(context: Context, muted: Boolean) {
        store(context).setBoolean(KEY_STATUS_POPUPS_MUTED, muted)
    }
}
