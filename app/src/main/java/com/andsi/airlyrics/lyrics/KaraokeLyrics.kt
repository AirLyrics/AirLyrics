package com.andsi.airlyrics.lyrics

data class KaraokeLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val tokens: List<KaraokeToken> = emptyList()
)

data class KaraokeToken(
    val text: String,
    val startMs: Long,
    val endMs: Long
)
