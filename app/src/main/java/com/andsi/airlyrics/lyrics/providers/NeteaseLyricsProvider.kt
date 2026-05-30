package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsProvider
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsSearchRequest

import org.json.JSONObject

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
    override val name: String = "网易云歌词"

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
        durationMs: Long
    ): Result<NeteaseLyricsResult?> {
        return runCatching {
            val jsonText = NeteaseLyricsNative.fetchBestLyricsJson(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                requestKlyric = false
            )

            val json = JSONObject(jsonText)
            if (!json.optBoolean("ok", false)) {
                return@runCatching null
            }

            val originalLrc = json.optString("lrc", "")
            val translatedLrc = json.optString("translated_lrc", "").ifBlank { null }
            val fallbackMergedLrc = json.optString("merged_lrc", "")
            val primaryLrc = originalLrc
                .ifBlank { fallbackMergedLrc }
                .ifBlank { translatedLrc.orEmpty() }

            if (primaryLrc.isBlank()) {
                return@runCatching null
            }

            NeteaseLyricsResult(
                source = json.optString("source", "netease-rust"),
                songId = json.optString("id", ""),
                title = json.optString("title", title),
                artist = json.optString("artist", artist),
                album = json.optString("album", ""),
                durationMs = json.optLong("duration_ms", durationMs),
                lrc = primaryLrc,
                translatedLrc = translatedLrc
            )
        }
    }
}
