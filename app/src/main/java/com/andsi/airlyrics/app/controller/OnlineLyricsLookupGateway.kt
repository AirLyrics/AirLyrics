package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.lyrics.LyricsSearchOrigin
import android.content.Context
import com.andsi.airlyrics.lyrics.LyricsSearchMode
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsRepository
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.settings.store.LyricsSettingsStore

internal fun interface OnlineLyricsLookupGateway {
    fun findAndSave(
        context: Context,
        media: CurrentMediaInfo,
        cancellationToken: LyricsLookupCancellationToken
    ): Result<LyricsProviderResult?>

    fun findAndSave(
        context: Context,
        media: CurrentMediaInfo,
        cancellationToken: LyricsLookupCancellationToken,
        mode: LyricsSearchMode
    ): Result<LyricsProviderResult?> {
        check(mode == LyricsSearchMode.Original) { "International search is not supported by this implementation" }
        return findAndSave(context, media, cancellationToken)
    }
}

internal object RepositoryOnlineLyricsLookupGateway : OnlineLyricsLookupGateway {
    override fun findAndSave(
        context: Context,
        media: CurrentMediaInfo,
        cancellationToken: LyricsLookupCancellationToken
    ): Result<LyricsProviderResult?> = findAndSave(context, media, cancellationToken, LyricsSearchMode.Original)

    override fun findAndSave(
        context: Context,
        media: CurrentMediaInfo,
        cancellationToken: LyricsLookupCancellationToken,
        mode: LyricsSearchMode
    ): Result<LyricsProviderResult?> {
        cancellationToken.throwIfCancellationRequested()
        return LyricsRepository.findLyrics(
            context = context,
            settings = LyricsSettingsStore.getSettings(context),
            title = media.title,
            artist = media.artist,
            album = media.album,
            durationMs = media.durationMs,
            bypassLocal = true,
            forceSaveOnline = true,
            ignoreAutoSearchSetting = true,
            cancellationToken = cancellationToken,
            searchMode = mode,
            searchOrigin = LyricsSearchOrigin.Manual
        ).mapCatching { result ->
            cancellationToken.throwIfCancellationRequested()
            if (result != null && !LyricsStorage.hasPlainLyrics(
                    context = context,
                    title = media.title,
                    artist = media.artist,
                    duration = media.durationMs
                )
            ) {
                error("Online lyrics were found but could not be saved")
            }
            cancellationToken.throwIfCancellationRequested()
            result
        }
    }
}
