package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.PlainLyricsProvider
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest

data class NeteasePlainLyricsResult(
    val plainSource: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val lrc: String,
    val translatedLrc: String?
)

object NeteasePlainLyricsProvider : PlainLyricsProvider {
    override val id: String = "netease"
    override val name: String = "NetEase Lyrics"

    override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> {
        return fetchBestPlainLyrics(
            title = request.title,
            artist = request.artist,
            album = request.album,
            durationMs = request.durationMs,
            cancellationToken = request.cancellationToken
        ).map { result ->
            toProviderResult(result)
        }
    }

    fun fetchBestPlainLyrics(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        cancellationToken: LyricsLookupCancellationToken? = null
    ): Result<NeteasePlainLyricsResult?> {
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
                    lookupId = lookupId
                )
            }
            cancellationToken?.throwIfCancellationRequested()

            mapNativePlainLyricsResultJson(
                jsonText = jsonText,
                fallbackTitle = title,
                fallbackArtist = artist,
                fallbackDurationMs = durationMs,
            )
        }.recoverNativeLoadFailure(
            providerId = id,
            providerName = name
        )
    }

    internal fun mapNativePlainLyricsResultJson(
        jsonText: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackDurationMs: Long,
    ): NeteasePlainLyricsResult? {
        val nativeResult = NativePlainLyricsResultParser.parse(
            jsonText = jsonText,
            defaultSource = "netease-rust",
            fallbackTitle = fallbackTitle,
            fallbackArtist = fallbackArtist,
            fallbackAlbum = "",
            fallbackDurationMs = fallbackDurationMs,
        )
        if (!nativeResult.ok) {
            if (nativeResult.errorType == LyricsLookupErrorType.NotFound) {
                return null
            }

            throw nativeResult.toNativePlainLyricsLookupException(
                providerId = id,
                providerName = name,
                defaultMessage = "NetEase lookup failed",
            )
        }

        val primaryLrc = nativeResult.primaryPlainLrc(allowTranslatedFallback = true)
        if (primaryLrc.isBlank()) return null

        return NeteasePlainLyricsResult(
            plainSource = nativeResult.plainSource,
            songId = nativeResult.id,
            title = nativeResult.title,
            artist = nativeResult.artist,
            album = nativeResult.album,
            durationMs = nativeResult.durationMs,
            lrc = primaryLrc,
            translatedLrc = nativeResult.translatedLrc,
        )
    }

    internal fun toProviderResult(result: NeteasePlainLyricsResult?): LyricsProviderResult? {
        return result?.let {
            LyricsProviderResult(
                plainProviderId = id,
                plainProviderName = name,
                plainLrc = it.lrc,
                translatedLrc = it.translatedLrc,
                matchedTitle = it.title,
                matchedArtist = it.artist,
                matchedAlbum = it.album,
                matchedDurationMs = it.durationMs,
            )
        }
    }
}
