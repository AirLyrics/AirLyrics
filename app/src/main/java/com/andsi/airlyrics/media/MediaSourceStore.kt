package com.andsi.airlyrics.media

import android.content.Context
import com.andsi.airlyrics.core.prefs.prefs

object MediaSourceStore {
    private const val PREFS_NAME = "media_source"
    private const val KEY_SELECTED_PACKAGE = "selected_package"

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun getSelectedPackage(context: Context): String? {
        return store(context).getString(KEY_SELECTED_PACKAGE)
    }

    fun saveSelectedPackage(context: Context, packageName: String?) {
        store(context).setString(KEY_SELECTED_PACKAGE, packageName)
    }
}
