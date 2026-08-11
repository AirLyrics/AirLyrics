package com.andsi.airlyrics.app.controller

import android.content.Context
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsRepository
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.settings.store.LyricsSettingsStore

internal fun interface OnlineLyricsLookupGateway {
    fun findAndSave(context: Context, media: CurrentMediaInfo): Result<LyricsProviderResult?>
}

internal object RepositoryOnlineLyricsLookupGateway : OnlineLyricsLookupGateway {
    override fun findAndSave(
        context: Context,
        media: CurrentMediaInfo
    ): Result<LyricsProviderResult?> {
        return LyricsRepository.findLyrics(
            context = context,
            settings = LyricsSettingsStore.getSettings(context),
            title = media.title,
            artist = media.artist,
            album = media.album,
            durationMs = media.durationMs,
            bypassLocal = true,
            forceSaveOnline = true,
            ignoreAutoSearchSetting = true
        ).mapCatching { result ->
            if (result != null && !LyricsStorage.hasPlainLyrics(
                    context = context,
                    title = media.title,
                    artist = media.artist,
                    duration = media.durationMs
                )
            ) {
                error("Online lyrics were found but could not be saved")
            }
            result
        }
    }
}
