package com.andsi.airlyrics.lyrics

/**
 * Normalized repository payload combining timed lyrics with optional local word-by-word timing.
 * A provider may return a translated-only track when no original track is available.
 */
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

internal fun LyricsProviderResult.hasUsableLyrics(): Boolean {
    return plainLrc.isNotBlank() || !translatedLrc.isNullOrBlank()
}
