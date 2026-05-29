package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.settings.model.LyricsSearchSource
import com.andsi.airlyrics.settings.model.LyricsSettings
import com.andsi.airlyrics.settings.model.LyricsSourceOption

object LyricsSettingsStore {
    private const val PREFS_NAME = "lyrics_settings"
    private const val KEY_LYRICS_SOURCE = "lyrics_source"
    private const val KEY_AUTO_SEARCH_ONLINE = "auto_search_online"
    private const val KEY_AUTO_SAVE_LOCAL = "auto_save_local"

    const val SOURCE_LOCAL_ONLY = "local_only"
    const val SOURCE_NETEASE = "netease"
    const val SOURCE_MUSIXMATCH = "musixmatch"

    val sourceOptions = LyricsSearchSource.entries.map { source ->
        LyricsSourceOption(
            key = source.key,
            title = source.title,
            description = source.description
        )
    }

    fun getLyricsSearchSource(context: Context): LyricsSearchSource {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LYRICS_SOURCE, LyricsSearchSource.default.key)

        return LyricsSearchSource.fromKey(value)
    }

    fun setLyricsSearchSource(context: Context, source: LyricsSearchSource) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LYRICS_SOURCE, source.key)
            .putBoolean(KEY_AUTO_SEARCH_ONLINE, source != LyricsSearchSource.LOCAL_ONLY)
            .apply()
    }

    fun getLyricsSource(context: Context): String {
        return getLyricsSearchSource(context).key
    }

    fun setLyricsSource(context: Context, source: String) {
        setLyricsSearchSource(context, LyricsSearchSource.fromKey(source))
    }

    fun getLyricsSourceTitle(context: Context): String {
        return getLyricsSearchSource(context).title
    }

    fun isAutoSearchOnlineEnabled(context: Context): Boolean {
        if (getLyricsSearchSource(context) == LyricsSearchSource.LOCAL_ONLY) return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SEARCH_ONLINE, true)
    }

    fun setAutoSearchOnlineEnabled(context: Context, enabled: Boolean) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_SEARCH_ONLINE, enabled)

        if (!enabled) {
            editor.putString(KEY_LYRICS_SOURCE, LyricsSearchSource.LOCAL_ONLY.key)
        } else if (getLyricsSearchSource(context) == LyricsSearchSource.LOCAL_ONLY) {
            editor.putString(KEY_LYRICS_SOURCE, LyricsSearchSource.default.key)
        }

        editor.apply()
    }

    fun isAutoSaveLocalEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SAVE_LOCAL, true)
    }

    fun setAutoSaveLocalEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SAVE_LOCAL, enabled)
            .apply()
    }

    fun getSettings(context: Context): LyricsSettings {
        return LyricsSettings(
            source = getLyricsSearchSource(context),
            autoSearchOnline = isAutoSearchOnlineEnabled(context),
            autoSaveLocal = isAutoSaveLocalEnabled(context)
        )
    }
}
