package com.andsi.airlyrics

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

object NeteaseLyricsProvider {
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
                durationMs = durationMs
            )

            val json = JSONObject(jsonText)
            if (!json.optBoolean("ok", false)) {
                return@runCatching null
            }

            val mergedLyrics = json.optString("merged_lrc", "")
                .ifBlank { json.optString("lrc", "") }
                .ifBlank { json.optString("translated_lrc", "") }

            if (mergedLyrics.isBlank()) {
                return@runCatching null
            }

            NeteaseLyricsResult(
                source = json.optString("source", "netease-rust"),
                songId = json.optString("id", ""),
                title = json.optString("title", title),
                artist = json.optString("artist", artist),
                album = json.optString("album", ""),
                durationMs = json.optLong("duration_ms", durationMs),
                lrc = mergedLyrics,
                translatedLrc = json.optString("translated_lrc", "").ifBlank { null }
            )
        }
    }
}
