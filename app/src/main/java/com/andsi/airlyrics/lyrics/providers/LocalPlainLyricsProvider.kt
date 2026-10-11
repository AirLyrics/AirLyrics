package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.storage.ManualLyricsSelectionStore
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.PlainLyricsProvider
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest
import com.andsi.airlyrics.lyrics.storage.LyricsStorage

/** Reads plain LRC from the user-selected lyrics directory or the app fallback directory. */
object LocalPlainLyricsProvider : PlainLyricsProvider {
    override val id: String = "local"
    override val name: String = "Local lyrics"

    override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> {
        return runCatching {
            request.cancellationToken?.throwIfCancellationRequested()
            ManualLyricsSelectionStore.read(request.context,
                SongIdentity(request.title, request.artist, request.album, request.durationMs))
                ?.let { return@runCatching it.result }
            val info = LyricsStorage.getLocalPlainLyricsInfo(request.context, request.title, request.artist, request.durationMs)
            if (info?.plainProvider == "spicy" && info.indexKey.startsWith("spicy:")) {
                return@runCatching com.andsi.airlyrics.lyrics.storage.SpicyLyricsCache.forContext(request.context)
                    .read(info.indexKey.removePrefix("spicy:"))
            }
            val plainLrc = LyricsStorage.readPlainLyrics(
                context = request.context,
                title = request.title,
                artist = request.artist,
                duration = request.durationMs
            ) ?: return@runCatching null
            request.cancellationToken?.throwIfCancellationRequested()

            LyricsProviderResult(
                plainProviderId = id,
                plainProviderName = name,
                plainLrc = plainLrc,
                matchedTitle = request.title,
                matchedArtist = request.artist,
                matchedAlbum = request.album,
                matchedDurationMs = request.durationMs,
                manualLocal = info?.plainSource != LyricsStorage.SOURCE_DOWNLOADED
            )
        }
    }
}
