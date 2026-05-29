package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.lyrics.providers.LocalLyricsProvider
import com.andsi.airlyrics.lyrics.providers.MusixmatchLyricsProvider
import com.andsi.airlyrics.lyrics.providers.NeteaseLyricsProvider
import com.andsi.airlyrics.lyrics.storage.LyricsStorage

import android.content.Context
import android.util.Log
import com.andsi.airlyrics.settings.model.LyricsSearchSource
import com.andsi.airlyrics.settings.store.LyricsSettingsStore

/**
 * Central lyrics lookup entry point.
 *
 * Lookup order:
 * 1. Local lyrics, so imported/saved files always win.
 * 2. Online provider, only when the user allows online search.
 * 3. Optional local cache save for successful online results.
 */
object LyricsRepository {
    private val onlineProviders: Map<LyricsSearchSource, LyricsProvider> = mapOf(
        LyricsSearchSource.NETEASE to NeteaseLyricsProvider,
        LyricsSearchSource.MUSIXMATCH to MusixmatchLyricsProvider
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
            if (settings.source == LyricsSearchSource.LOCAL_ONLY) {
                return@runCatching null
            }
            if (!ignoreAutoSearchSetting && !settings.autoSearchOnline) {
                return@runCatching null
            }

            val provider = onlineProviders[settings.source] ?: NeteaseLyricsProvider
            val onlineResult = provider.fetch(request).getOrElse { error ->
                Log.w(
                    "AirLyricsLyrics",
                    "${provider.name} lookup failed: title=$title artist=$artist durationMs=$durationMs",
                    error
                )
                throw error
            }

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
