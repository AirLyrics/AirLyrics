package com.andsi.airlyrics

import android.content.Context
import com.andsi.airlyrics.core.settings.LyricsSettingsStore

/**
 * Central lyrics lookup entry point.
 *
 * Lookup order:
 * 1. Local lyrics, so imported/saved files always win.
 * 2. The selected online provider, unless the user chose local-only mode.
 * 3. Optional local cache save for successful online results.
 */
object LyricsRepository {
    private val onlineProviders: Map<String, LyricsProvider> = mapOf(
        LyricsSettingsStore.SOURCE_NETEASE to NeteaseLyricsProvider
    )

    fun findLyrics(
        context: Context,
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long
    ): Result<LyricsProviderResult?> {
        val request = LyricsSearchRequest(
            context = context.applicationContext,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs
        )

        return runCatching {
            LocalLyricsProvider.fetch(request).getOrThrow()?.let { localResult ->
                return@runCatching localResult
            }

            val settings = LyricsSettingsStore.getSettings(context)
            if (settings.source == LyricsSettingsStore.SOURCE_LOCAL_ONLY) {
                return@runCatching null
            }

            val provider = onlineProviders[settings.source] ?: NeteaseLyricsProvider
            val onlineResult = provider.fetch(request).getOrThrow()

            if (onlineResult != null && settings.autoSaveLocal) {
                LyricsStorage.saveLyrics(
                    context = context,
                    title = title,
                    artist = artist,
                    duration = durationMs,
                    lyrics = onlineResult.lyrics
                )
            }

            onlineResult
        }
    }
}
