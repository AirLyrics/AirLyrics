package com.andsi.airlyrics.core.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

internal fun prefs(context: Context, name: String): PreferenceStore {
    return PreferenceStore(context, name)
}

internal class PreferenceStore internal constructor(
    context: Context,
    name: String
) {
    private val preferences: SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(name, Context.MODE_PRIVATE)

    fun contains(key: String): Boolean {
        return preferences.contains(key)
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return preferences.getString(key, defaultValue)
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return preferences.getBoolean(key, defaultValue)
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return preferences.getInt(key, defaultValue)
    }

    fun getLong(key: String, defaultValue: Long): Long {
        return preferences.getLong(key, defaultValue)
    }

    fun getFloat(key: String, defaultValue: Float): Float {
        return preferences.getFloat(key, defaultValue)
    }

    fun setString(key: String, value: String?, commit: Boolean = false) {
        edit(commit = commit) {
            if (value == null) {
                remove(key)
            } else {
                putString(key, value)
            }
        }
    }

    fun setBoolean(key: String, value: Boolean, commit: Boolean = false) {
        edit(commit = commit) {
            putBoolean(key, value)
        }
    }

    fun setInt(key: String, value: Int, commit: Boolean = false) {
        edit(commit = commit) {
            putInt(key, value)
        }
    }

    fun setLong(key: String, value: Long, commit: Boolean = false) {
        edit(commit = commit) {
            putLong(key, value)
        }
    }

    fun setFloat(key: String, value: Float, commit: Boolean = false) {
        edit(commit = commit) {
            putFloat(key, value)
        }
    }

    fun edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
        preferences.edit(commit = commit, action = action)
    }
}
