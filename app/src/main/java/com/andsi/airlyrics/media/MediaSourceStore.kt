package com.andsi.airlyrics.media

import android.content.Context
import androidx.core.content.edit

object MediaSourceStore {
    private const val PREFS_NAME = "media_source"
    private const val KEY_SELECTED_PACKAGE = "selected_package"

    fun getSelectedPackage(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_PACKAGE, null)
    }

    fun saveSelectedPackage(context: Context, packageName: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                if (packageName == null) {
                    remove(KEY_SELECTED_PACKAGE)
                } else {
                    putString(KEY_SELECTED_PACKAGE, packageName)
                }
            }
    }
}
