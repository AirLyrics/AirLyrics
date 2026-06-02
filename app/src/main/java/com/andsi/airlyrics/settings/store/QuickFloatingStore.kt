package com.andsi.airlyrics.settings.store

import android.content.Context

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

    fun isDesiredVisible(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_DESIRED_VISIBLE)) {
            prefs.getBoolean(KEY_DESIRED_VISIBLE, false)
        } else {
            prefs.getBoolean(KEY_VISIBLE_LEGACY, false)
        }
    }

    fun setDesiredVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DESIRED_VISIBLE, visible)
            .commit()
    }

    /** Backward-compatible read. New code should prefer isDesiredVisible. */
    fun isVisible(context: Context): Boolean = isDesiredVisible(context)

    /** Backward-compatible write. New code should prefer setDesiredVisible. */
    fun setVisible(context: Context, visible: Boolean) {
        setDesiredVisible(context, visible)
    }
}
