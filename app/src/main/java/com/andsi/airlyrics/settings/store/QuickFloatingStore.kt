package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.prefs.prefs

/**
 * Stores the user's persisted floating-window intent.
 *
 * desiredVisible is safe to persist because it means "the user wants the
 * overlay to be visible" and is used for restore. The real WindowManager
 * state is intentionally not persisted: Activity UI starts from false and is
 * updated only from Service broadcasts.
 */
object QuickFloatingStore {
    private const val PREFS_NAME = "floating_quick_control"
    private const val KEY_VISIBLE_LEGACY = "visible"
    private const val KEY_DESIRED_VISIBLE = "desiredVisible"

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun isDesiredVisible(context: Context): Boolean {
        val preferences = store(context)
        return if (preferences.contains(KEY_DESIRED_VISIBLE)) {
            preferences.getBoolean(KEY_DESIRED_VISIBLE, false)
        } else {
            preferences.getBoolean(KEY_VISIBLE_LEGACY, false)
        }
    }

    fun setDesiredVisible(context: Context, visible: Boolean) {
        store(context).setBoolean(KEY_DESIRED_VISIBLE, visible, commit = true)
    }

}
