package com.andsi.airlyrics.settings.store

import android.content.Context
import androidx.core.content.edit
import com.andsi.airlyrics.settings.model.LyricsContentDisplayMode
import com.andsi.airlyrics.settings.model.LyricsLineDisplayMode
import com.andsi.airlyrics.settings.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.settings.model.LyricsSearchSource
import com.andsi.airlyrics.settings.model.LyricsSettings
import com.andsi.airlyrics.settings.model.LyricsSourceOption

object LyricsSettingsStore {
    private const val PREFS_NAME = "lyrics_settings"
    private const val KEY_LYRICS_SOURCE = "lyrics_source"
    private const val KEY_AUTO_SEARCH_ONLINE = "auto_search_online"
    private const val KEY_AUTO_SAVE_LOCAL = "auto_save_local"
    private const val KEY_CONTENT_DISPLAY_MODE = "content_display_mode"
    private const val KEY_LINE_DISPLAY_MODE = "line_display_mode"
    private const val KEY_SWITCH_ANIMATION_MODE = "switch_animation_mode"
    private const val KEY_KARAOKE_LYRICS_ENABLED = "karaoke_lyrics_enabled"

    val sourceOptions = LyricsSearchSource.entries.map { source ->
        LyricsSourceOption(key = source.key)
    }

    fun getLyricsSearchSource(context: Context): LyricsSearchSource {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LYRICS_SOURCE, LyricsSearchSource.default.key)

        return LyricsSearchSource.fromKey(value)
    }

    fun setLyricsSearchSource(context: Context, source: LyricsSearchSource) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_LYRICS_SOURCE, source.key)
            putBoolean(KEY_AUTO_SEARCH_ONLINE, source != LyricsSearchSource.LOCAL_ONLY)
        }
    }

    fun getLyricsSource(context: Context): String {
        return getLyricsSearchSource(context).key
    }

    fun setLyricsSource(context: Context, source: String) {
        setLyricsSearchSource(context, LyricsSearchSource.fromKey(source))
    }


    fun isAutoSearchOnlineEnabled(context: Context): Boolean {
        if (getLyricsSearchSource(context) == LyricsSearchSource.LOCAL_ONLY) return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SEARCH_ONLINE, true)
    }

    fun setAutoSearchOnlineEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_AUTO_SEARCH_ONLINE, enabled)

            if (!enabled) {
                putString(KEY_LYRICS_SOURCE, LyricsSearchSource.LOCAL_ONLY.key)
            } else if (getLyricsSearchSource(context) == LyricsSearchSource.LOCAL_ONLY) {
                putString(KEY_LYRICS_SOURCE, LyricsSearchSource.default.key)
            }
        }
    }

    fun isAutoSaveLocalEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SAVE_LOCAL, true)
    }

    fun setAutoSaveLocalEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_AUTO_SAVE_LOCAL, enabled)
        }
    }


    fun getContentDisplayMode(context: Context): LyricsContentDisplayMode {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONTENT_DISPLAY_MODE, LyricsContentDisplayMode.default.key)

        return LyricsContentDisplayMode.fromKey(value)
    }

    fun setContentDisplayMode(context: Context, mode: LyricsContentDisplayMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_CONTENT_DISPLAY_MODE, mode.key)
        }
    }

    fun getLineDisplayMode(context: Context): LyricsLineDisplayMode {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LINE_DISPLAY_MODE, LyricsLineDisplayMode.default.key)

        return LyricsLineDisplayMode.fromKey(value)
    }

    fun setLineDisplayMode(context: Context, mode: LyricsLineDisplayMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_LINE_DISPLAY_MODE, mode.key)
        }
    }

    fun getSwitchAnimationMode(context: Context): LyricsSwitchAnimationMode {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SWITCH_ANIMATION_MODE, LyricsSwitchAnimationMode.default.key)

        return LyricsSwitchAnimationMode.fromKey(value)
    }

    fun setSwitchAnimationMode(context: Context, mode: LyricsSwitchAnimationMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_SWITCH_ANIMATION_MODE, mode.key)
        }
    }

    fun isKaraokeLyricsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KARAOKE_LYRICS_ENABLED, false)
    }

    fun setKaraokeLyricsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_KARAOKE_LYRICS_ENABLED, enabled)
        }
    }

    fun getSettings(context: Context): LyricsSettings {
        return LyricsSettings(
            source = getLyricsSearchSource(context),
            autoSearchOnline = isAutoSearchOnlineEnabled(context),
            autoSaveLocal = isAutoSaveLocalEnabled(context),
            contentDisplayMode = getContentDisplayMode(context),
            lineDisplayMode = getLineDisplayMode(context),
            switchAnimationMode = getSwitchAnimationMode(context),
            karaokeLyricsEnabled = isKaraokeLyricsEnabled(context)
        )
    }
}
