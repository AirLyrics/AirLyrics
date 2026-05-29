package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.LyricsProvider
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsSearchRequest
import android.util.Log
import org.json.JSONObject

data class MusixmatchLyricsResult(
    val source: String,
    val subtitleId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val lrc: String,
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
            durationMs = request.durationMs
        ).map { result ->
            result?.let {
                LyricsProviderResult(
                    providerId = id,
                    providerName = name,
                    lyrics = it.lrc,
                    translatedLyrics = null,
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
        durationMs: Long
    ): Result<MusixmatchLyricsResult?> {
        return runCatching {
            val jsonText = MusixmatchLyricsNative.fetchBestLyricsJson(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs
            )

            val json = JSONObject(jsonText)
            if (!json.optBoolean("ok", false)) {
                val errorType = LyricsLookupErrorType.fromNativeName(json.optString("error_type", ""))
                if (errorType == LyricsLookupErrorType.NotFound) {
                    Log.w(
                        "AirLyricsLyrics",
                        "Musixmatch not found: title=$title artist=$artist durationMs=$durationMs detail=${json.optString("error", "")}"
                    )
                    return@runCatching null
                }
                throw LyricsLookupException(
                    providerId = id,
                    providerName = name,
                    errorType = errorType,
                    detailMessage = json.optString("error", "Musixmatch lookup failed")
                )
            }

            val mergedLyrics = json.optString("merged_lrc", "")
                .ifBlank { json.optString("lrc", "") }

            if (mergedLyrics.isBlank()) {
                return@runCatching null
            }

            MusixmatchLyricsResult(
                source = json.optString("source", "musixmatch-rust"),
                subtitleId = json.optString("id", ""),
                title = json.optString("title", title),
                artist = json.optString("artist", artist),
                album = json.optString("album", album),
                durationMs = json.optLong("duration_ms", durationMs),
                lrc = mergedLyrics,
                errorType = json.optString("error_type", "").ifBlank { null },
                errorMessage = json.optString("error", "").ifBlank { null }
            )
        }
    }
}
