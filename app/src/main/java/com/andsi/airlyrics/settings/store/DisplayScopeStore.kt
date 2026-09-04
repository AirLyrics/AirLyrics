package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.prefs.prefs

/** Persists the optional app allowlist used to limit where floating lyrics appear. */
internal object DisplayScopeStore {
    private const val PREFS_NAME = "display_scope"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SELECTED_PACKAGES = "selectedPackages"

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun isEnabled(context: Context): Boolean {
        return store(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_ENABLED, enabled, commit = true)
    }

    fun selectedPackages(context: Context): Set<String> {
        return store(context).getStringSet(KEY_SELECTED_PACKAGES)
    }

    fun setSelectedPackages(context: Context, packageNames: Set<String>) {
        store(context).setStringSet(
            KEY_SELECTED_PACKAGES,
            packageNames.filterTo(linkedSetOf()) { it.isNotBlank() },
            commit = true
        )
    }
}
