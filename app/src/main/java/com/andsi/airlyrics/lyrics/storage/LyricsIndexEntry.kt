package com.andsi.airlyrics.lyrics.storage

internal data class LyricsIndexEntry(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val file: String,
    val karaokeFile: String = "",
    val source: String,
    val provider: String,
    val karaokeProvider: String = "",
    val createdAt: Long,
    val updatedAt: Long
)
