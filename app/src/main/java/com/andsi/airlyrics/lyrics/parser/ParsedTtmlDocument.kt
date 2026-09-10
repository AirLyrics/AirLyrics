package com.andsi.airlyrics.lyrics.parser

internal enum class TtmlTimingMode {
    UNSPECIFIED,
    LINE,
    WORD
}

internal data class ParsedTtmlDocument(
    val lines: List<ParsedTtmlLine>,
    val timingMode: TtmlTimingMode = TtmlTimingMode.UNSPECIFIED
)

internal data class ParsedTtmlLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val segments: List<ParsedTtmlSegment> = emptyList(),
    val translations: List<String> = emptyList(),
    val key: String? = null
)

internal data class ParsedTtmlSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long
)
