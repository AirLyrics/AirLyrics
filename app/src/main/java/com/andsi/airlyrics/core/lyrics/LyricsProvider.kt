package com.andsi.airlyrics

import android.content.Context

/** Information required by a lyrics provider to look up a song. */
data class LyricsSearchRequest(
    val context: Context,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long
)

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

/** A pluggable lyrics source. New online sources should implement this interface. */
interface LyricsProvider {
    val id: String
    val name: String

    fun fetch(request: LyricsSearchRequest): Result<LyricsProviderResult?>
}
