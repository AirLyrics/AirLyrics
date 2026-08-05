package com.andsi.airlyrics.lyrics.providers

import android.content.res.Resources
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.PlainLyricsProvider
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest
import java.util.Locale

data class MusixmatchPlainLyricsResult(
    val plainSource: String,
    val subtitleId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val lrc: String,
    val translatedLrc: String? = null,
    val errorType: String? = null,
    val errorMessage: String? = null
)

object MusixmatchPlainLyricsProvider : PlainLyricsProvider {
    override val id: String = "musixmatch"
    override val name: String = "Musixmatch"

    override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> {
        return fetchBestPlainLyrics(
            title = request.title,
            artist = request.artist,
            album = request.album,
            durationMs = request.durationMs,
            translationLanguageCode = systemTranslationLanguageCode(),
            cancellationToken = request.cancellationToken
        ).map { result ->
            toProviderResult(result)
        }
    }


    private fun systemTranslationLanguageCode(): String {
        return Resources.getSystem().configuration.locales[0]
            ?.language
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
    }

    fun fetchBestPlainLyrics(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        translationLanguageCode: String = "",
        cancellationToken: LyricsLookupCancellationToken? = null
    ): Result<MusixmatchPlainLyricsResult?> {
        return runCatching {
            cancellationToken?.throwIfCancellationRequested()
            val jsonText = withNativeLyricsCancellation(
                token = cancellationToken
            ) { lookupId ->
                MusixmatchLyricsNative.fetchBestLyricsJson(
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    translationLanguageCode = translationLanguageCode,
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
                translationLanguageCode = translationLanguageCode,
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
        translationLanguageCode: String,
    ): MusixmatchPlainLyricsResult? {
        val nativeResult = NativePlainLyricsResultParser.parse(
            jsonText = jsonText,
            defaultSource = "musixmatch-rust",
            fallbackTitle = fallbackTitle,
            fallbackArtist = fallbackArtist,
            fallbackAlbum = fallbackAlbum,
            fallbackDurationMs = fallbackDurationMs,
        )
        if (!nativeResult.ok) {
            if (nativeResult.errorType == LyricsLookupErrorType.NotFound) {
                if (BuildConfig.DEBUG) {
                    Log.w(
                        "AirLyricsLyrics",
                        "Musixmatch not found: title=$fallbackTitle artist=$fallbackArtist " +
                            "durationMs=$fallbackDurationMs translation=$translationLanguageCode " +
                            "detail=${nativeResult.errorMessage.orEmpty()}",
                    )
                }
                return null
            }
            throw nativeResult.toNativePlainLyricsLookupException(
                providerId = id,
                providerName = name,
                defaultMessage = "Musixmatch lookup failed",
            )
        }

        val lrc = nativeResult.primaryPlainLrc()
        if (lrc.isBlank()) return null

        val translatedLrc = nativeResult.translatedLrc
        if (translationLanguageCode.isNotBlank()) {
            if (translatedLrc.isNullOrBlank()) {
                if (BuildConfig.DEBUG) {
                    Log.i(
                        "AirLyricsLyrics",
                        "Musixmatch translation empty: title=$fallbackTitle " +
                            "artist=$fallbackArtist lang=$translationLanguageCode " +
                            "matched=${nativeResult.title} - ${nativeResult.artist}",
                    )
                }
            } else {
                if (BuildConfig.DEBUG) {
                    Log.i(
                        "AirLyricsLyrics",
                        "Musixmatch translation found: title=$fallbackTitle " +
                            "artist=$fallbackArtist lang=$translationLanguageCode " +
                            "translatedChars=${translatedLrc.length}",
                    )
                }
            }
        }

        return MusixmatchPlainLyricsResult(
            plainSource = nativeResult.plainSource,
            subtitleId = nativeResult.id,
            title = nativeResult.title,
            artist = nativeResult.artist,
            album = nativeResult.album,
            durationMs = nativeResult.durationMs,
            lrc = lrc,
            translatedLrc = translatedLrc,
            errorType = nativeResult.errorTypeName,
            errorMessage = nativeResult.errorMessage,
        )
    }

    internal fun toProviderResult(result: MusixmatchPlainLyricsResult?): LyricsProviderResult? {
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
