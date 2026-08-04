package com.andsi.airlyrics.lyrics

/** Normalized repository payload combining a plain-lyrics result with optional local word-by-word timing. */
data class LyricsProviderResult(
    val plainProviderId: String,
    val plainProviderName: String,
    val plainLrc: String,
    val translatedLrc: String? = null,
    val wordByWordLines: List<WordByWordLine> = emptyList(),
    val matchedTitle: String = "",
    val matchedArtist: String = "",
    val matchedAlbum: String = "",
    val matchedDurationMs: Long = 0L
)
