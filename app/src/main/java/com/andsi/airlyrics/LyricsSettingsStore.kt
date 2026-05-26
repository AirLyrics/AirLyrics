package com.andsi.airlyrics

import android.content.Context

object LyricsSettingsStore {
    private const val PREFS_NAME = "lyrics_settings"
    private const val KEY_LYRICS_SOURCE = "lyrics_source"
    private const val KEY_AUTO_SAVE_LOCAL = "auto_save_local"

    const val SOURCE_NETEASE = "netease"
    const val SOURCE_LOCAL_ONLY = "local_only"

    data class LyricsSourceOption(
        val key: String,
        val title: String,
        val description: String
    )

    val sourceOptions = listOf(
        LyricsSourceOption(
            SOURCE_NETEASE,
            "网易云歌词",
            "从网易云匹配歌词，适合中文和日系音乐。"
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
            .apply()
    }

    fun getLyricsSourceTitle(context: Context): String {
        val source = getLyricsSource(context)
        return sourceOptions.firstOrNull { it.key == source }?.title ?: "网易云歌词"
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
}
