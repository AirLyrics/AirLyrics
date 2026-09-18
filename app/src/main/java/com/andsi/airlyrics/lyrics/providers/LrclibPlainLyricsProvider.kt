package com.andsi.airlyrics.lyrics.providers

import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.PlainLyricsProvider
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest

data class LrclibPlainLyricsResult(
    val plainSource: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val lrc: String,
    val translatedLrc: String?
)

object LrclibPlainLyricsProvider : PlainLyricsProvider {
    override val id: String = "lrclib"
    override val name: String = "LRCLIB"

    override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> {
        return fetchBestPlainLyrics(
            title = request.title,
            artist = request.artist,
            album = request.album,
            durationMs = request.durationMs,
            cancellationToken = request.cancellationToken
        ).map(::toProviderResult)
    }

    fun fetchBestPlainLyrics(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        cancellationToken: LyricsLookupCancellationToken? = null
    ): Result<LrclibPlainLyricsResult?> {
        return runCatching {
            cancellationToken?.throwIfCancellationRequested()
            if (BuildConfig.DEBUG) {
                Log.d(
                    "AirLyricsLyrics",
                    "source=lrclib stage=lookup_input title=${title.debugLogValue()} " +
                        "artist=${artist.debugLogValue()} album=${album.debugLogValue()} " +
                        "durationMs=$durationMs",
                )
            }
            val jsonText = withNativeLyricsCancellation(
                token = cancellationToken
            ) { lookupId ->
                LrclibLyricsNative.fetchBestLyricsJson(
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
                fallbackAlbum = album,
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
        fallbackAlbum: String,
        fallbackDurationMs: Long,
    ): LrclibPlainLyricsResult? {
        val nativeResult = NativePlainLyricsResultParser.parse(
            jsonText = jsonText,
            defaultSource = "lrclib-rust",
            fallbackTitle = fallbackTitle,
            fallbackArtist = fallbackArtist,
            fallbackAlbum = fallbackAlbum,
            fallbackDurationMs = fallbackDurationMs,
        )
        if (!nativeResult.ok) {
            if (nativeResult.errorType == LyricsLookupErrorType.NotFound) {
                return null
            }

            throw nativeResult.toNativePlainLyricsLookupException(
                providerId = id,
                providerName = name,
                defaultMessage = "LRCLIB lookup failed",
            )
        }

        val lrc = nativeResult.primaryPlainLrc()
        if (lrc.isBlank()) return null
        val lyricsTracks = nativeResult.translatedLrc?.let { translatedLrc ->
            LrclibEmbeddedLyricsTracks(
                originalLrc = lrc,
                translatedLrc = translatedLrc
            )
        } ?: splitLrclibEmbeddedTranslations(lrc)

        return LrclibPlainLyricsResult(
            plainSource = nativeResult.plainSource,
            trackId = nativeResult.id,
            title = nativeResult.title,
            artist = nativeResult.artist,
            album = nativeResult.album,
            durationMs = nativeResult.durationMs,
            lrc = lyricsTracks.originalLrc,
            translatedLrc = lyricsTracks.translatedLrc,
        )
    }

    internal fun toProviderResult(result: LrclibPlainLyricsResult?): LyricsProviderResult? {
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

private fun String.debugLogValue(): String =
    replace(Regex("[\\r\\n\\t]+"), " ").trim().take(200)
