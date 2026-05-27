package com.andsi.airlyrics

import android.content.Context

/**
 * Stores the quick floating-window state used by the center navigation button.
 *
 * The actual window is still owned by FloatingLyricsService; this store only
 * keeps UI state stable across activity recreation.
 */
object QuickFloatingStore {
    private const val PREFS_NAME = "floating_quick_control"
    private const val KEY_VISIBLE = "visible"

    fun isVisible(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VISIBLE, false)
    }

    fun setVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VISIBLE, visible)
            .apply()
    }
}
