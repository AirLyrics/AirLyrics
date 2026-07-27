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
        val nativeErrorTypeName = json.optStringOrDefault("error_type", "").ifBlank { null }
        return NativeLyricsJsonResult(
            ok = json.optBoolean("ok", false),
            errorType = LyricsLookupErrorType.fromNativeName(nativeErrorTypeName),
            errorTypeName = nativeErrorTypeName,
            errorMessage = json.optStringOrDefault("error", "").ifBlank { null },
            source = json.optStringOrDefault("source", defaultSource),
            id = json.optStringOrDefault("id", ""),
            title = json.optStringOrDefault("title", fallbackTitle),
            artist = json.optStringOrDefault("artist", fallbackArtist),
            album = json.optStringOrDefault("album", fallbackAlbum),
            durationMs = json.optLong("duration_ms", fallbackDurationMs),
            lrc = json.optStringOrDefault("lrc", ""),
            mergedLrc = json.optStringOrDefault("merged_lrc", ""),
            translatedLrc = json.optStringOrDefault("translated_lrc", "").ifBlank { null }
        )
    }

    private fun JSONObject.optStringOrDefault(
        name: String,
        defaultValue: String,
    ): String = if (isNull(name)) defaultValue else optString(name, defaultValue)
}
