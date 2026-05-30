package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.lyrics.providers.LocalLyricsProvider
import com.andsi.airlyrics.lyrics.providers.MusixmatchLyricsProvider
import com.andsi.airlyrics.lyrics.providers.NeteaseLyricsProvider
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.parser.LrcParser

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
            val settings = LyricsSettingsStore.getSettings(context)
            val wordByWordEnabled = settings.karaokeLyricsEnabled
            val wantsOnlineKaraoke = wordByWordEnabled &&
                settings.source == LyricsSearchSource.MUSIXMATCH
            var localFallback: LyricsProviderResult? = null

            if (!bypassLocal) {
                LocalLyricsProvider.fetch(request).getOrThrow()?.let { localResult ->
                    if (!wordByWordEnabled) {
                        return@runCatching localResult
                    }

                    val cachedKaraokeLines = LyricsStorage.readKaraokeLyrics(
                        context = context,
                        title = title,
                        artist = artist,
                        duration = durationMs
                    )
                    if (cachedKaraokeLines.isNotEmpty()) {
                        return@runCatching localResult.copy(
                            providerId = "local",
                            providerName = "本地缓存",
                            karaokeLines = cachedKaraokeLines
                        )
                    }

                    if (!wantsOnlineKaraoke) {
                        return@runCatching localResult
                    }

                    localFallback = localResult
                }
            }

            if (settings.source == LyricsSearchSource.LOCAL_ONLY) {
                return@runCatching localFallback
            }
            if (!ignoreAutoSearchSetting && !settings.autoSearchOnline) {
                return@runCatching localFallback
            }

            val provider = onlineProviders[settings.source] ?: NeteaseLyricsProvider
            val onlineResult = provider.fetch(request).getOrElse { error ->
                Log.w(
                    "AirLyricsLyrics",
                    "${provider.name} lookup failed: title=$title artist=$artist durationMs=$durationMs",
                    error
                )
                if (wantsOnlineKaraoke && localFallback != null) {
                    return@runCatching localFallback
                }
                throw error
            }

            if (onlineResult != null && (settings.autoSaveLocal || forceSaveOnline)) {
                LyricsStorage.saveLyrics(
                    context = context,
                    title = title,
                    artist = artist,
                    duration = durationMs,
                    lyrics = LrcParser.mergeOriginalAndTranslationForStorage(
                        lyrics = onlineResult.lyrics,
                        translatedLyrics = onlineResult.translatedLyrics
                    ),
                    album = album,
                    source = LyricsStorage.SOURCE_DOWNLOADED,
                    provider = onlineResult.providerName,
                    overwrite = true
                )
            }

            if (onlineResult != null && wantsOnlineKaraoke && onlineResult.karaokeLines.isNotEmpty()) {
                LyricsStorage.saveKaraokeLyrics(
                    context = context,
                    title = title,
                    artist = artist,
                    duration = durationMs,
                    karaokeLines = onlineResult.karaokeLines,
                    album = album,
                    source = LyricsStorage.SOURCE_DOWNLOADED,
                    provider = onlineResult.providerName,
                    overwrite = true
                )
            }

            onlineResult ?: localFallback
        }
    }
}
