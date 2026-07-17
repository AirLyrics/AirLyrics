package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.core.model.LyricsSearchSource
import com.andsi.airlyrics.core.model.LyricsSettings
import com.andsi.airlyrics.core.prefs.prefs

object LyricsSettingsStore {
    private const val PREFS_NAME = "lyrics_settings"
    private const val KEY_LYRICS_SOURCE = "lyrics_source"
    private const val KEY_AUTO_SEARCH_ONLINE = "auto_search_online"
    private const val KEY_AUTO_SAVE_LOCAL = "auto_save_local"
    private const val KEY_CONTENT_DISPLAY_MODE = "content_display_mode"
    private const val KEY_LINE_DISPLAY_MODE = "line_display_mode"
    private const val KEY_SWITCH_ANIMATION_MODE = "switch_animation_mode"
    private const val KEY_KARAOKE_LYRICS_ENABLED = "karaoke_lyrics_enabled"

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun getLyricsSearchSource(context: Context): LyricsSearchSource {
        val value = store(context).getString(KEY_LYRICS_SOURCE)

        return if (value == null) {
            LyricsSearchSource.default
        } else {
            LyricsSearchSource.fromKeyOrNull(value) ?: LyricsSearchSource.LOCAL_ONLY
        }
    }

    fun setLyricsSearchSource(context: Context, source: LyricsSearchSource) {
        store(context).edit {
            putString(KEY_LYRICS_SOURCE, source.key)
            putBoolean(KEY_AUTO_SEARCH_ONLINE, source != LyricsSearchSource.LOCAL_ONLY)
        }
    }

    fun setLyricsSource(context: Context, source: String) {
        setLyricsSearchSource(context, LyricsSearchSource.fromKeyOrNull(source) ?: LyricsSearchSource.LOCAL_ONLY)
    }


    fun isAutoSearchOnlineEnabled(context: Context): Boolean {
        if (getLyricsSearchSource(context) == LyricsSearchSource.LOCAL_ONLY) return false
        return store(context).getBoolean(KEY_AUTO_SEARCH_ONLINE, true)
    }

    fun setAutoSearchOnlineEnabled(context: Context, enabled: Boolean) {
        val currentSource = getLyricsSearchSource(context)
        store(context).edit {
            putBoolean(KEY_AUTO_SEARCH_ONLINE, enabled)

            if (!enabled) {
                putString(KEY_LYRICS_SOURCE, LyricsSearchSource.LOCAL_ONLY.key)
            } else if (currentSource == LyricsSearchSource.LOCAL_ONLY) {
                putString(KEY_LYRICS_SOURCE, LyricsSearchSource.default.key)
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

    fun isKaraokeLyricsEnabled(context: Context): Boolean {
        return store(context).getBoolean(KEY_KARAOKE_LYRICS_ENABLED, false)
    }

    fun setKaraokeLyricsEnabled(context: Context, enabled: Boolean) {
        store(context).setBoolean(KEY_KARAOKE_LYRICS_ENABLED, enabled)
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
