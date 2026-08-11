package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.LyricsSettings
import com.andsi.airlyrics.core.prefs.prefs

object LyricsSettingsStore {
    private const val PREFS_NAME = "lyrics_settings"
    private const val KEY_PLAIN_LYRICS_SOURCE = "lyrics_source"
    private const val KEY_AUTO_SEARCH_ONLINE = "auto_search_online"
    private const val KEY_AUTO_SAVE_LOCAL = "auto_save_local"
    private const val KEY_CONTENT_DISPLAY_MODE = "content_display_mode"
    private const val KEY_LINE_DISPLAY_MODE = "line_display_mode"
    private const val KEY_SWITCH_ANIMATION_MODE = "switch_animation_mode"
    // Persisted compatibility contract. Do not change the serialized value.
    private const val KEY_WORD_BY_WORD_LYRICS_ENABLED = "karaoke_lyrics_enabled"

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun getPlainLyricsSearchSource(context: Context): PlainLyricsSearchSource {
        val value = store(context).getString(KEY_PLAIN_LYRICS_SOURCE)
        val persisted = PlainLyricsSearchSource.fromKeyOrNull(value)
        return persisted?.takeIf { it in PlainLyricsSearchSource.onlineSources }
            ?: PlainLyricsSearchSource.default
    }

    fun setPlainLyricsSearchSource(context: Context, plainLyricsSearchSource: PlainLyricsSearchSource) {
        if (plainLyricsSearchSource == PlainLyricsSearchSource.LOCAL_ONLY) {
            store(context).edit {
                putString(KEY_PLAIN_LYRICS_SOURCE, PlainLyricsSearchSource.default.key)
                putBoolean(KEY_AUTO_SEARCH_ONLINE, false)
            }
        } else {
            store(context).setString(KEY_PLAIN_LYRICS_SOURCE, plainLyricsSearchSource.key)
        }
    }

    fun setPlainLyricsSource(context: Context, plainLyricsSource: String) {
        setPlainLyricsSearchSource(
            context,
            PlainLyricsSearchSource.fromKeyOrNull(plainLyricsSource) ?: PlainLyricsSearchSource.LOCAL_ONLY
        )
    }


    fun isAutoSearchOnlineEnabled(context: Context): Boolean {
        val persistedSourceKey = store(context).getString(KEY_PLAIN_LYRICS_SOURCE)
        val persistedSource = PlainLyricsSearchSource.fromKeyOrNull(persistedSourceKey)
        if (persistedSourceKey != null && persistedSource !in PlainLyricsSearchSource.onlineSources) {
            return false
        }
        return store(context).getBoolean(KEY_AUTO_SEARCH_ONLINE, true)
    }

    fun setAutoSearchOnlineEnabled(context: Context, enabled: Boolean) {
        val persistedSource = PlainLyricsSearchSource.fromKeyOrNull(
            store(context).getString(KEY_PLAIN_LYRICS_SOURCE)
        )
        store(context).edit {
            putBoolean(KEY_AUTO_SEARCH_ONLINE, enabled)
            if (enabled && persistedSource !in PlainLyricsSearchSource.onlineSources) {
                putString(KEY_PLAIN_LYRICS_SOURCE, PlainLyricsSearchSource.default.key)
            }
        }
    }

    fun isAutoSaveLocalEnabled(context: Context): Boolean {
        return store(context).getBoolean(KEY_AUTO_SAVE_LOCAL, true)
    }

    fun setAutoSaveLocalEnabled(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_AUTO_SAVE_LOCAL, enabled)
    }


    fun getContentDisplayMode(context: Context): LyricsContentDisplayMode {
        val value = store(context).getString(KEY_CONTENT_DISPLAY_MODE, LyricsContentDisplayMode.default.key)

        return LyricsContentDisplayMode.fromKey(value)
    }

    fun setContentDisplayMode(context: Context, mode: LyricsContentDisplayMode) {
        store(context).setString(KEY_CONTENT_DISPLAY_MODE, mode.key)
    }

    fun getLineDisplayMode(context: Context): LyricsLineDisplayMode {
        val value = store(context).getString(KEY_LINE_DISPLAY_MODE, LyricsLineDisplayMode.default.key)

        return LyricsLineDisplayMode.fromKey(value)
    }

    fun setLineDisplayMode(context: Context, mode: LyricsLineDisplayMode) {
        store(context).setString(KEY_LINE_DISPLAY_MODE, mode.key)
    }

    fun getSwitchAnimationMode(context: Context): LyricsSwitchAnimationMode {
        val value = store(context).getString(KEY_SWITCH_ANIMATION_MODE, LyricsSwitchAnimationMode.default.key)

        return LyricsSwitchAnimationMode.fromKey(value)
    }

    fun setSwitchAnimationMode(context: Context, mode: LyricsSwitchAnimationMode) {
        store(context).setString(KEY_SWITCH_ANIMATION_MODE, mode.key)
    }

    fun isWordByWordLyricsEnabled(context: Context): Boolean {
        return store(context).getBoolean(KEY_WORD_BY_WORD_LYRICS_ENABLED, false)
    }

    fun setWordByWordLyricsEnabled(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_WORD_BY_WORD_LYRICS_ENABLED, enabled)
    }

    fun getSettings(context: Context): LyricsSettings {
        return LyricsSettings(
            plainLyricsSearchSource = getPlainLyricsSearchSource(context),
            autoSearchOnline = isAutoSearchOnlineEnabled(context),
            autoSaveLocal = isAutoSaveLocalEnabled(context),
            contentDisplayMode = getContentDisplayMode(context),
            lineDisplayMode = getLineDisplayMode(context),
            switchAnimationMode = getSwitchAnimationMode(context),
            wordByWordLyricsEnabled = isWordByWordLyricsEnabled(context)
        )
    }
}
