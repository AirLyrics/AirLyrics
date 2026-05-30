package com.andsi.airlyrics.lyrics.providers

import android.util.Log
import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.lyrics.KaraokeToken
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.LyricsProvider
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsSearchRequest
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import org.json.JSONArray
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
    val karaokeLines: List<KaraokeLine> = emptyList(),
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
            translationLanguageCode = LyricsSettingsStore
                .getMusixmatchTranslationLanguage(request.context)
                .languageCode,
            enableKaraoke = LyricsSettingsStore.isKaraokeLyricsEnabled(request.context)
        ).map { result ->
            result?.let {
                LyricsProviderResult(
                    providerId = id,
                    providerName = name,
                    lyrics = it.lrc,
                    translatedLyrics = it.translatedLrc,
                    karaokeLines = it.karaokeLines,
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
        translationLanguageCode: String = "",
        enableKaraoke: Boolean = false
    ): Result<MusixmatchLyricsResult?> {
        return runCatching {
            val jsonText = MusixmatchLyricsNative.fetchBestLyricsJson(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                translationLanguageCode = translationLanguageCode,
                enableKaraoke = enableKaraoke
            )

            val json = JSONObject(jsonText)
            if (!json.optBoolean("ok", false)) {
                val errorType = LyricsLookupErrorType.fromNativeName(json.optString("error_type", ""))
                if (errorType == LyricsLookupErrorType.NotFound) {
                    Log.w(
                        "AirLyricsLyrics",
                        "Musixmatch not found: title=$title artist=$artist durationMs=$durationMs translation=$translationLanguageCode detail=${json.optString("error", "") }"
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

            val lrc = json.optString("lrc", "")
                .ifBlank { json.optString("merged_lrc", "") }

            if (lrc.isBlank()) {
                return@runCatching null
            }

            val translatedLrc = json.optString("translated_lrc", "").ifBlank { null }
            val karaokeLines = parseKaraokeLines(json.optString("karaoke_json", ""))
            if (translationLanguageCode.isNotBlank()) {
                if (translatedLrc.isNullOrBlank()) {
                    Log.i(
                        "AirLyricsLyrics",
                        "Musixmatch translation empty: title=$title artist=$artist lang=$translationLanguageCode matched=${json.optString("title", title)} - ${json.optString("artist", artist)}"
                    )
                } else {
                    Log.i(
                        "AirLyricsLyrics",
                        "Musixmatch translation found: title=$title artist=$artist lang=$translationLanguageCode translatedChars=${translatedLrc.length}"
                    )
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
                karaokeLines = karaokeLines,
                errorType = json.optString("error_type", "").ifBlank { null },
                errorMessage = json.optString("error", "").ifBlank { null }
            )
        }
    }
}


internal fun parseKaraokeLines(rawJson: String): List<KaraokeLine> {
    if (rawJson.isBlank()) return emptyList()

    return runCatching {
        val array = JSONArray(rawJson)
        buildList {
            for (lineIndex in 0 until array.length()) {
                val line = array.optJSONObject(lineIndex) ?: continue
                val startMs = line.optLong("startMs", -1L)
                val endMs = line.optLong("endMs", -1L)
                val text = line.optString("text", "").trim()
                val tokenArray = line.optJSONArray("tokens") ?: JSONArray()

                if (startMs < 0L || endMs <= startMs || text.isBlank() || tokenArray.length() == 0) {
                    continue
                }

                val tokenStarts = mutableListOf<Pair<String, Long>>()
                for (tokenIndex in 0 until tokenArray.length()) {
                    val token = tokenArray.optJSONObject(tokenIndex) ?: continue
                    val tokenText = token.optString("text", "").trim()
                    val tokenStartMs = token.optLong("startMs", -1L)
                    if (tokenText.isNotBlank() && tokenStartMs >= startMs) {
                        tokenStarts += tokenText to tokenStartMs
                    }
                }

                val tokens = tokenStarts.mapIndexed { index, (tokenText, tokenStartMs) ->
                    val nextStart = tokenStarts.getOrNull(index + 1)?.second ?: endMs
                    KaraokeToken(
                        text = tokenText,
                        startMs = tokenStartMs,
                        endMs = nextStart.coerceAtLeast(tokenStartMs + 1L)
                    )
                }

                if (tokens.isNotEmpty()) {
                    add(
                        KaraokeLine(
                            startMs = startMs,
                            endMs = endMs,
                            text = text,
                            tokens = tokens
                        )
                    )
                }
            }
        }.sortedBy { it.startMs }
    }.getOrElse { emptyList() }
}
