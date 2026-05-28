package com.andsi.airlyrics.lyrics

/** Normalized lyrics payload returned by any provider. */
data class LyricsProviderResult(
    val providerId: String,
    val providerName: String,
    val lyrics: String,
    val translatedLyrics: String? = null,
    val matchedTitle: String = "",
    val matchedArtist: String = "",
    val matchedAlbum: String = "",
    val matchedDurationMs: Long = 0L
)
