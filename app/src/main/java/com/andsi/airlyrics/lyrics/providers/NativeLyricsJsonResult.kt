package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import org.json.JSONObject

internal data class NativeLyricsJsonResult(
    val ok: Boolean,
    val errorType: LyricsLookupErrorType,
    val errorTypeName: String?,
    val errorMessage: String?,
    val source: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val lrc: String,
    val mergedLrc: String,
    val translatedLrc: String?
) {
    fun primaryLyrics(allowTranslatedFallback: Boolean = false): String {
        return lrc
            .ifBlank { mergedLrc }
            .ifBlank { if (allowTranslatedFallback) translatedLrc.orEmpty() else "" }
    }
}

internal object NativeLyricsResultParser {
    fun parse(
        jsonText: String,
        defaultSource: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String,
        fallbackDurationMs: Long
    ): NativeLyricsJsonResult {
        val json = JSONObject(jsonText)
        val nativeErrorTypeName = json.optString("error_type", "").ifBlank { null }
        return NativeLyricsJsonResult(
            ok = json.optBoolean("ok", false),
            errorType = LyricsLookupErrorType.fromNativeName(nativeErrorTypeName),
            errorTypeName = nativeErrorTypeName,
            errorMessage = json.optString("error", "").ifBlank { null },
            source = json.optString("source", defaultSource),
            id = json.optString("id", ""),
            title = json.optString("title", fallbackTitle),
            artist = json.optString("artist", fallbackArtist),
            album = json.optString("album", fallbackAlbum),
            durationMs = json.optLong("duration_ms", fallbackDurationMs),
            lrc = json.optString("lrc", ""),
            mergedLrc = json.optString("merged_lrc", ""),
            translatedLrc = json.optString("translated_lrc", "").ifBlank { null }
        )
    }
}
