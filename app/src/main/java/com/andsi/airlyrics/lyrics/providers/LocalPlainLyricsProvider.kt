package com.andsi.airlyrics.lyrics.providers

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
                matchedDurationMs = request.durationMs
            )
        }
    }
}
