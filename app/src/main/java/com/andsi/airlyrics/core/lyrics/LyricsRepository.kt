package com.andsi.airlyrics

import android.content.Context
import com.andsi.airlyrics.core.settings.LyricsSettingsStore

/**
 * Central lyrics lookup entry point.
 *
 * Lookup order:
 * 1. Local lyrics, so imported/saved files always win.
 * 2. Online provider, only when the user allows online search.
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
        durationMs: Long,
        bypassLocal: Boolean = false,
        forceSaveOnline: Boolean = false,
        ignoreAutoSearchSetting: Boolean = false
    ): Result<LyricsProviderResult?> {
        val request = LyricsSearchRequest(
            context = context.applicationContext,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs
        )

        return runCatching {
            if (!bypassLocal) {
                LocalLyricsProvider.fetch(request).getOrThrow()?.let { localResult ->
                    return@runCatching localResult
                }
            }

            val settings = LyricsSettingsStore.getSettings(context)
            if (!ignoreAutoSearchSetting && (!settings.autoSearchOnline || settings.source == LyricsSettingsStore.SOURCE_LOCAL_ONLY)) {
                return@runCatching null
            }

            val providerKey = if (settings.source == LyricsSettingsStore.SOURCE_LOCAL_ONLY) {
                LyricsSettingsStore.SOURCE_NETEASE
            } else {
                settings.source
            }
            val provider = onlineProviders[providerKey] ?: NeteaseLyricsProvider
            val onlineResult = provider.fetch(request).getOrThrow()

            if (onlineResult != null && (settings.autoSaveLocal || forceSaveOnline)) {
                LyricsStorage.saveLyrics(
                    context = context,
                    title = title,
                    artist = artist,
                    duration = durationMs,
                    lyrics = onlineResult.lyrics,
                    album = album,
                    source = LyricsStorage.SOURCE_DOWNLOADED,
                    provider = onlineResult.providerName,
                    overwrite = true
                )
            }

            onlineResult
        }
    }
}
