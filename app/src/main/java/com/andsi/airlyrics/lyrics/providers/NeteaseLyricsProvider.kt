package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsProvider
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsSearchRequest

data class NeteaseLyricsResult(
    val source: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val lrc: String,
    val translatedLrc: String?
)

object NeteaseLyricsProvider : LyricsProvider {
    override val id: String = "netease"
    override val name: String = "NetEase Lyrics"

    override fun fetch(request: LyricsSearchRequest): Result<LyricsProviderResult?> {
        return fetchBestLyrics(
            title = request.title,
            artist = request.artist,
            album = request.album,
            durationMs = request.durationMs,
            cancellationToken = request.cancellationToken
        ).map { result ->
            result?.let {
                LyricsProviderResult(
                    providerId = id,
                    providerName = name,
                    lyrics = it.lrc,
                    translatedLyrics = it.translatedLrc,
                    matchedTitle = it.title,
                    matchedArtist = it.artist,
                    matchedAlbum = it.album,
                    matchedDurationMs = it.durationMs
                )
            }
        }
    }

    fun fetchBestLyrics(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        cancellationToken: LyricsLookupCancellationToken? = null
    ): Result<NeteaseLyricsResult?> {
        return runCatching {
            cancellationToken?.throwIfCancellationRequested()
            val jsonText = withNativeLyricsCancellation(
                token = cancellationToken
            ) { lookupId ->
                NeteaseLyricsNative.fetchBestLyricsJson(
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    lookupId = lookupId,
                    requestKlyric = false
                )
            }
            cancellationToken?.throwIfCancellationRequested()

            val nativeResult = NativeLyricsResultParser.parse(
                jsonText = jsonText,
                defaultSource = "netease-rust",
                fallbackTitle = title,
                fallbackArtist = artist,
                fallbackAlbum = "",
                fallbackDurationMs = durationMs
            )
            if (!nativeResult.ok) {
                if (nativeResult.errorType == LyricsLookupErrorType.NotFound) {
                    return@runCatching null
                }

                throw nativeResult.toNativeLyricsLookupException(
                    providerId = id,
                    providerName = name,
                    defaultMessage = "NetEase lookup failed"
                )
            }

            val primaryLrc = nativeResult.primaryLyrics(allowTranslatedFallback = true)

            if (primaryLrc.isBlank()) {
                return@runCatching null
            }

            NeteaseLyricsResult(
                source = nativeResult.source,
                songId = nativeResult.id,
                title = nativeResult.title,
                artist = nativeResult.artist,
                album = nativeResult.album,
                durationMs = nativeResult.durationMs,
                lrc = primaryLrc,
                translatedLrc = nativeResult.translatedLrc
            )
        }.recoverNativeLoadFailure(
            providerId = id,
            providerName = name
        )
    }
}
