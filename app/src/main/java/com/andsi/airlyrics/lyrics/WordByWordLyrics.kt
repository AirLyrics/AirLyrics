package com.andsi.airlyrics.lyrics

data class WordByWordLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val segments: List<WordByWordSegment> = emptyList()
)

data class WordByWordSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long
)
