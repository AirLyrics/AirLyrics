package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.lyrics.providers.LocalLyricsProvider
import com.andsi.airlyrics.lyrics.providers.MusixmatchLyricsProvider
import com.andsi.airlyrics.lyrics.providers.NeteaseLyricsProvider
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.parser.LrcParser

import android.content.Context
import com.andsi.airlyrics.BuildConfig
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
        ignoreAutoSearchSetting: Boolean = false,
        cancellationToken: LyricsLookupCancellationToken? = null
    ): Result<LyricsProviderResult?> {
        val request = LyricsSearchRequest(
            context = context.applicationContext,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs
        )

        return runCatching {
            cancellationToken?.throwIfCancellationRequested()
            val settings = LyricsSettingsStore.getSettings(context)
            val wordByWordEnabled = settings.karaokeLyricsEnabled
            cancellationToken?.throwIfCancellationRequested()
            if (!bypassLocal) {
                cancellationToken?.throwIfCancellationRequested()
                LocalLyricsProvider.fetch(request).getOrThrow()?.let { localResult ->
                    cancellationToken?.throwIfCancellationRequested()
                    return@runCatching attachLocalKaraokeIfAvailable(
                        context = context,
                        result = localResult,
                        title = title,
                        artist = artist,
                        durationMs = durationMs,
                        enabled = wordByWordEnabled
                    )
                }
            }

            if (settings.source == LyricsSearchSource.LOCAL_ONLY) {
                return@runCatching null
            }
            if (!ignoreAutoSearchSetting && !settings.autoSearchOnline) {
                return@runCatching null
            }

            cancellationToken?.throwIfCancellationRequested()
            val provider = onlineProviders[settings.source] ?: NeteaseLyricsProvider
            val onlineResult = provider.fetch(request).getOrElse { error ->
                if (BuildConfig.DEBUG) {
                    Log.w(
                        "AirLyricsLyrics",
                        "${provider.name} lookup failed: title=$title artist=$artist durationMs=$durationMs",
                        error
                    )
                } else {
                    Log.w("AirLyricsLyrics", "${provider.name} lookup failed", error)
                }
                throw error
            }

            cancellationToken?.throwIfCancellationRequested()
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
                    provider = onlineResult.providerId,
                    overwrite = true
                )
            }

            cancellationToken?.throwIfCancellationRequested()
            onlineResult?.let { result ->
                attachLocalKaraokeIfAvailable(
                    context = context,
                    result = result,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    enabled = wordByWordEnabled
                )
            }
        }
    }

    private fun attachLocalKaraokeIfAvailable(
        context: Context,
        result: LyricsProviderResult,
        title: String,
        artist: String,
        durationMs: Long,
        enabled: Boolean
    ): LyricsProviderResult {
        if (!enabled) return result

        val cachedKaraokeLines = LyricsStorage.readKaraokeLyrics(
            context = context,
            title = title,
            artist = artist,
            duration = durationMs
        )
        if (cachedKaraokeLines.isEmpty()) return result

        return result.copy(karaokeLines = cachedKaraokeLines)
    }
}
