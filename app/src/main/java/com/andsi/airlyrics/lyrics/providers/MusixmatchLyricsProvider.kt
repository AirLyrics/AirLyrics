package com.andsi.airlyrics.lyrics.providers

import android.content.res.Resources
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.LyricsProvider
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsSearchRequest
import java.util.Locale
import org.json.JSONObject

data class MusixmatchLyricsResult(
    val source: String,
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

object MusixmatchLyricsProvider : LyricsProvider {
    override val id: String = "musixmatch"
    override val name: String = "Musixmatch"

    override fun fetch(request: LyricsSearchRequest): Result<LyricsProviderResult?> {
        return fetchBestLyrics(
            title = request.title,
            artist = request.artist,
            album = request.album,
            durationMs = request.durationMs,
            translationLanguageCode = systemTranslationLanguageCode()
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


    private fun systemTranslationLanguageCode(): String {
        return Resources.getSystem().configuration.locales[0]
            ?.language
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
            .orEmpty()
    }

    fun fetchBestLyrics(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        translationLanguageCode: String = ""
    ): Result<MusixmatchLyricsResult?> {
        return runCatching {
            val jsonText = MusixmatchLyricsNative.fetchBestLyricsJson(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                translationLanguageCode = translationLanguageCode,
                reserved = false
            )

            val json = JSONObject(jsonText)
            if (!json.optBoolean("ok", false)) {
                val errorType = LyricsLookupErrorType.fromNativeName(json.optString("error_type", ""))
                if (errorType == LyricsLookupErrorType.NotFound) {
                    if (BuildConfig.DEBUG) {
                        Log.w(
                            "AirLyricsLyrics",
                            "Musixmatch not found: title=$title artist=$artist durationMs=$durationMs translation=$translationLanguageCode detail=${json.optString("error", "") }"
                        )
                    }
                    return@runCatching null
                }
                throw LyricsLookupException(
                    providerId = id,
                    providerName = name,
                    errorType = errorType,
                    detailMessage = json.optString("error", "Musixmatch lookup failed")
                )
            }

            val lrc = json.optString("lrc", "")
                .ifBlank { json.optString("merged_lrc", "") }

            if (lrc.isBlank()) {
                return@runCatching null
            }

            val translatedLrc = json.optString("translated_lrc", "").ifBlank { null }
            if (translationLanguageCode.isNotBlank()) {
                if (translatedLrc.isNullOrBlank()) {
                    if (BuildConfig.DEBUG) {
                        Log.i(
                            "AirLyricsLyrics",
                            "Musixmatch translation empty: title=$title artist=$artist lang=$translationLanguageCode matched=${json.optString("title", title)} - ${json.optString("artist", artist)}"
                        )
                    }
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.i(
                            "AirLyricsLyrics",
                            "Musixmatch translation found: title=$title artist=$artist lang=$translationLanguageCode translatedChars=${translatedLrc.length}"
                        )
                    }
                }
            }
            val mergedLyrics = json.optString("merged_lrc", "").ifBlank { lrc }

            MusixmatchLyricsResult(
                source = json.optString("source", "musixmatch-rust"),
                subtitleId = json.optString("id", ""),
                title = json.optString("title", title),
                artist = json.optString("artist", artist),
                album = json.optString("album", album),
                durationMs = json.optLong("duration_ms", durationMs),
                lrc = lrc.ifBlank { mergedLyrics },
                translatedLrc = translatedLrc,
                errorType = json.optString("error_type", "").ifBlank { null },
                errorMessage = json.optString("error", "").ifBlank { null }
            )
        }
    }
}
