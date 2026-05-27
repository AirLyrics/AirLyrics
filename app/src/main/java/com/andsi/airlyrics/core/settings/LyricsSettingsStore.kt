package com.andsi.airlyrics.core.settings

import android.content.Context
import com.andsi.airlyrics.core.settings.model.LyricsSettings
import com.andsi.airlyrics.core.settings.model.LyricsSourceOption

object LyricsSettingsStore {
    private const val PREFS_NAME = "lyrics_settings"
    private const val KEY_LYRICS_SOURCE = "lyrics_source"
    private const val KEY_AUTO_SEARCH_ONLINE = "auto_search_online"
    private const val KEY_AUTO_SAVE_LOCAL = "auto_save_local"

    const val SOURCE_NETEASE = "netease"
    const val SOURCE_LOCAL_ONLY = "local_only"

    val sourceOptions = listOf(
        LyricsSourceOption(
            SOURCE_NETEASE,
            "网易云歌词",
            "本地没有歌词时，从网易云匹配歌词。"
        ),
        LyricsSourceOption(
            SOURCE_LOCAL_ONLY,
            "仅使用本地歌词",
            "不联网查找，只读取已经导入或保存的 .lrc。"
        )
    )

    fun getLyricsSource(context: Context): String {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LYRICS_SOURCE, SOURCE_NETEASE)

        return sourceOptions.firstOrNull { it.key == value }?.key ?: SOURCE_NETEASE
    }

    fun setLyricsSource(context: Context, source: String) {
        val safeSource = sourceOptions.firstOrNull { it.key == source }?.key ?: SOURCE_NETEASE
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LYRICS_SOURCE, safeSource)
            .putBoolean(KEY_AUTO_SEARCH_ONLINE, safeSource != SOURCE_LOCAL_ONLY)
            .apply()
    }

    fun getLyricsSourceTitle(context: Context): String {
        val source = getLyricsSource(context)
        return sourceOptions.firstOrNull { it.key == source }?.title ?: "网易云歌词"
    }

    fun isAutoSearchOnlineEnabled(context: Context): Boolean {
        if (getLyricsSource(context) == SOURCE_LOCAL_ONLY) return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SEARCH_ONLINE, true)
    }

    fun setAutoSearchOnlineEnabled(context: Context, enabled: Boolean) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_SEARCH_ONLINE, enabled)

        if (!enabled) {
            editor.putString(KEY_LYRICS_SOURCE, SOURCE_LOCAL_ONLY)
        } else if (getLyricsSource(context) == SOURCE_LOCAL_ONLY) {
            editor.putString(KEY_LYRICS_SOURCE, SOURCE_NETEASE)
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
            source = getLyricsSource(context),
            autoSearchOnline = isAutoSearchOnlineEnabled(context),
            autoSaveLocal = isAutoSaveLocalEnabled(context)
        )
    }
}
