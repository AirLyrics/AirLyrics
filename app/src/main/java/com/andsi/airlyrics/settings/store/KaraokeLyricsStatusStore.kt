package com.andsi.airlyrics.settings.store

import android.content.Context

/** Stores the latest karaoke capability check for the currently resolved lyrics. */
object KaraokeLyricsStatusStore {
    private const val PREFS_NAME = "karaoke_lyrics_status"
    private const val KEY_MEDIA_KEY = "media_key"
    private const val KEY_PROVIDER_ID = "provider_id"
    private const val KEY_PROVIDER_NAME = "provider_name"
    private const val KEY_HAS_LYRICS = "has_lyrics"
    private const val KEY_HAS_KARAOKE = "has_karaoke"
    private const val KEY_UPDATED_AT = "updated_at"

    data class Status(
        val mediaKey: String,
        val providerId: String,
        val providerName: String,
        val hasLyrics: Boolean,
        val hasKaraoke: Boolean,
        val updatedAt: Long
    ) {
        val isKnown: Boolean
            get() = mediaKey.isNotBlank() && providerId.isNotBlank()
    }

    fun update(
        context: Context,
        mediaKey: String,
        providerId: String,
        providerName: String,
        hasLyrics: Boolean,
        hasKaraoke: Boolean
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MEDIA_KEY, mediaKey)
            .putString(KEY_PROVIDER_ID, providerId)
            .putString(KEY_PROVIDER_NAME, providerName)
            .putBoolean(KEY_HAS_LYRICS, hasLyrics)
            .putBoolean(KEY_HAS_KARAOKE, hasKaraoke)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context, mediaKey: String) {
        update(
            context = context,
            mediaKey = mediaKey,
            providerId = "",
            providerName = "",
            hasLyrics = false,
            hasKaraoke = false
        )
    }

    fun get(context: Context, mediaKey: String): Status? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedMediaKey = prefs.getString(KEY_MEDIA_KEY, "").orEmpty()
        if (storedMediaKey != mediaKey) return null

        return Status(
            mediaKey = storedMediaKey,
            providerId = prefs.getString(KEY_PROVIDER_ID, "").orEmpty(),
            providerName = prefs.getString(KEY_PROVIDER_NAME, "").orEmpty(),
            hasLyrics = prefs.getBoolean(KEY_HAS_LYRICS, false),
            hasKaraoke = prefs.getBoolean(KEY_HAS_KARAOKE, false),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        )
    }
}
