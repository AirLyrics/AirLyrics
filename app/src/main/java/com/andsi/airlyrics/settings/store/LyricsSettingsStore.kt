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
    private const val KEY_PLAIN_LYRICS_SOURCE_ORDER = "lyrics_source_order"
    // Legacy single-source key. Keep it as a migration input and downgrade-compatible first source.
    private const val KEY_PLAIN_LYRICS_SOURCE = "lyrics_source"
    private const val PLAIN_LYRICS_SOURCE_SEPARATOR = ","
    private const val KEY_AUTO_SEARCH_ONLINE = "auto_search_online"
    private const val KEY_AUTO_SAVE_LOCAL = "auto_save_local"
    private const val KEY_CONTENT_DISPLAY_MODE = "content_display_mode"
    private const val KEY_LINE_DISPLAY_MODE = "line_display_mode"
    private const val KEY_SWITCH_ANIMATION_MODE = "switch_animation_mode"
    // Persisted compatibility contract. Do not change the serialized value.
    private const val KEY_WORD_BY_WORD_LYRICS_ENABLED = "karaoke_lyrics_enabled"

    private fun store(context: Context) = prefs(context, PREFS_NAME)

    fun getPlainLyricsSearchSources(context: Context): List<PlainLyricsSearchSource> {
        val preferences = store(context)
        deserializePlainLyricsSearchSources(
            preferences.getString(KEY_PLAIN_LYRICS_SOURCE_ORDER)
        ).takeIf { it.isNotEmpty() }?.let { return it }

        val legacySource = PlainLyricsSearchSource.fromKeyOrNull(
            preferences.getString(KEY_PLAIN_LYRICS_SOURCE)
        )
        return legacySource
            ?.takeIf { it in PlainLyricsSearchSource.onlineSources }
            ?.let(::listOf)
            ?: listOf(PlainLyricsSearchSource.default)
    }

    fun setPlainLyricsSearchSources(
        context: Context,
        plainLyricsSearchSources: List<PlainLyricsSearchSource>
    ) {
        val normalizedSources = plainLyricsSearchSources
            .filter { it in PlainLyricsSearchSource.onlineSources }
            .distinct()
        require(normalizedSources.isNotEmpty()) { "At least one online lyrics source is required" }

        store(context).edit {
            putString(
                KEY_PLAIN_LYRICS_SOURCE_ORDER,
                normalizedSources.joinToString(PLAIN_LYRICS_SOURCE_SEPARATOR) { it.key }
            )
            putString(KEY_PLAIN_LYRICS_SOURCE, normalizedSources.first().key)
        }
    }

    fun isAutoSearchOnlineEnabled(context: Context): Boolean {
        val preferences = store(context)
        val persistedSources = deserializePlainLyricsSearchSources(
            preferences.getString(KEY_PLAIN_LYRICS_SOURCE_ORDER)
        )
        if (persistedSources.isNotEmpty()) {
            return preferences.getBoolean(KEY_AUTO_SEARCH_ONLINE, true)
        }

        val persistedSourceKey = preferences.getString(KEY_PLAIN_LYRICS_SOURCE)
        val persistedSource = PlainLyricsSearchSource.fromKeyOrNull(persistedSourceKey)
        if (persistedSourceKey != null && persistedSource !in PlainLyricsSearchSource.onlineSources) {
            return false
        }
        return preferences.getBoolean(KEY_AUTO_SEARCH_ONLINE, true)
    }

    fun setAutoSearchOnlineEnabled(context: Context, enabled: Boolean) {
        val preferences = store(context)
        val persistedSources = deserializePlainLyricsSearchSources(
            preferences.getString(KEY_PLAIN_LYRICS_SOURCE_ORDER)
        )
        val legacySource = PlainLyricsSearchSource.fromKeyOrNull(
            preferences.getString(KEY_PLAIN_LYRICS_SOURCE)
        )
        val hasPersistedOnlineSource = persistedSources.isNotEmpty() ||
            legacySource in PlainLyricsSearchSource.onlineSources

        preferences.edit {
            putBoolean(KEY_AUTO_SEARCH_ONLINE, enabled)
            if (enabled && !hasPersistedOnlineSource) {
                putString(KEY_PLAIN_LYRICS_SOURCE_ORDER, PlainLyricsSearchSource.default.key)
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
            plainLyricsSearchSources = getPlainLyricsSearchSources(context),
            autoSearchOnline = isAutoSearchOnlineEnabled(context),
            autoSaveLocal = isAutoSaveLocalEnabled(context),
            contentDisplayMode = getContentDisplayMode(context),
            lineDisplayMode = getLineDisplayMode(context),
            switchAnimationMode = getSwitchAnimationMode(context),
            wordByWordLyricsEnabled = isWordByWordLyricsEnabled(context)
        )
    }

    private fun deserializePlainLyricsSearchSources(value: String?): List<PlainLyricsSearchSource> {
        return value
            ?.split(PLAIN_LYRICS_SOURCE_SEPARATOR)
            .orEmpty()
            .map { it.trim() }
            .mapNotNull(PlainLyricsSearchSource::fromKeyOrNull)
            .filter { it in PlainLyricsSearchSource.onlineSources }
            .distinct()
    }
}
