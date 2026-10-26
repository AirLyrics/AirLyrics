package com.andsi.airlyrics.lyrics.search

import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.parser.LrcParser

/** Empty text is an explicit choice; non-empty payloads still need playable lines. */
internal fun LyricsProviderResult.manualUnavailableReason(): LyricsUnavailableReason? {
    val tracks = listOf(plainLrc, translatedLrc.orEmpty())
    if (tracks.all { it.isBlank() }) return null
    val text = tracks.flatMap { LrcParser.parse(it) }
        .filterNot { it.isMetadata }
        .flatMap { listOf(it.text, it.translation.orEmpty()) }
        .filter { it.isNotBlank() }
    if (text.isEmpty()) return LyricsUnavailableReason.NO_USABLE_LINES
    return null
}

internal enum class LyricsUnavailableReason(val wireName: String) {
    SOURCE_EMPTY("source_empty"),
    INSTRUMENTAL("instrumental"),
    NO_TIMELINE("no_timeline"),
    UNSUPPORTED_FORMAT("unsupported_format"),
    NO_USABLE_LINES("no_usable_lines"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String): LyricsUnavailableReason = entries.firstOrNull { it.wireName == value } ?: UNKNOWN
    }
}

internal sealed interface ManualLyricsFetchResult {
    data class Available(val lyrics: LyricsProviderResult) : ManualLyricsFetchResult
    data class Unavailable(val reason: LyricsUnavailableReason) : ManualLyricsFetchResult
}
